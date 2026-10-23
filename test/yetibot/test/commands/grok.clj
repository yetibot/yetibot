(ns yetibot.test.commands.grok
  (:require [clojure.core.async :as async]
            [clojure.core.async.impl.protocols :as async-protocols]
            [discljord.messaging :as discord]
            [discljord.util :as discord-util]
            [midje.sweet :refer [fact facts =>]]
            [yetibot.api.ai :as api]
            [yetibot.commands.grok :as grok]
            [yetibot.core.chat :as chat]
            [yetibot.core.commands.grok :as legacy]
            [yetibot.core.commands.grokimage :as image]
            [yetibot.core.config :as config]
            [yetibot.core.hooks :as hooks]
            [yetibot.models.ai-context :as context]
            [yetibot.models.ai-tools :as tools]
            [yetibot.test.support.ai-execution :as execution]
            [yetibot.test.models.ai-tool-policy :refer [actor]]))

(def provider
  {:name "grok" :url "https://api.x.ai/v1" :key "SECRET"
   :api "responses" :model "configured-grok"
   :native-tools ["web_search" "x_search"]})
(def other {:name "local" :url "http://localhost/v1" :model "local-model"})

(defn discord-promise [value]
  (let [result (discord-util/derefable-promise-chan)]
    (async/>!! result value)
    result))

(facts "the application hook wins regardless of core namespace discovery order"
  (require 'yetibot.core.commands.grok)
  (let [[_ commands] (hooks/find-sub-cmds "grok")]
    (hooks/match-sub-cmds "hello\nworld" commands)
    => ["hello\nworld" #'grok/grok-cmd]
    (second (hooks/match-sub-cmds "agent do work" commands))
    => #'legacy/grok-agent-cmd
    (doseq [prompt ["--no-tools agent explain infrastructure"
                    "--provider local explain agent deployment"]]
      (fact (second (hooks/match-sub-cmds prompt commands))
            => #'grok/grok-cmd)))
  (let [[_ commands] (hooks/find-sub-cmds "gi")]
    (second (hooks/match-sub-cmds "a snowy mountain" commands))
    => #'image/grokimage-cmd))

(facts "Grok uses shared configuration, model flags and trusted actor identity"
  (let [captured (atom nil)]
    (with-redefs [config/get-config (constantly {:value {}})
                  api/config (constantly {:providers [other provider]
                                          :default-provider "local"})
                  context/messages (fn [_ prompt]
                                     [{:role "user" :content prompt}])
                  tools/complete (fn [p model messages options]
                                   (reset! captured [p model messages options])
                                   {:result/value "Answer" :result/data {}})]
      (:value (execution/run-command execution/request
                                     "grok --toolset cloudflare_read Hi"))
      => "Answer\n\nSent via configured-grok"
      @captured => [provider "configured-grok"
                    [{:role "user" :content "Hi"}]
                    {:toolset "cloudflare_read" :actor actor}]
      (:result/value (grok/grok-cmd
                       {:match "--provider local --model other-model Hi"}))
      => "Answer\n\nSent via other-model"
      (take 2 @captured) => [other "other-model"]
      (:result/error (grok/grok-cmd {:match "--unknown hello"}))
      => grok/usage)))

(fact "a sole provider is selected without requiring the name grok"
  (with-redefs [config/get-config (constantly {:value {}})
                api/config (constantly {:providers [other]})
                context/messages (fn [_ prompt]
                                   [{:role "user" :content prompt}])
                tools/complete (fn [p model _ _]
                                 {:result/value (str (:name p) "/" model)})]
    (:result/value (grok/grok-cmd {:match "Hi"}))
    => "local/local-model\n\nSent via local-model"))

(facts "legacy fallback is only for absent shared configuration"
  (let [legacy-calls (atom 0)]
    (with-redefs [config/get-config (constantly {:error :not-found})
                  legacy/grok-cmd (fn [_] (swap! legacy-calls inc) :legacy)]
      (grok/grok-cmd {:match "Hi"}) => :legacy
      @legacy-calls => 1)
    (with-redefs [config/get-config (constantly {:error :invalid})
                  api/config (fn [] (api/fail "AI configuration is invalid."))
                  legacy/grok-cmd (fn [_] (swap! legacy-calls inc))]
      (grok/grok-cmd {:match "Hi"})
      => {:result/error "AI configuration is invalid."}
      @legacy-calls => 1)))

(facts "Discord progress is removed on success and safely handled failure"
  (doseq [failure? [false true]]
    (let [events (atom [])]
      (with-redefs [config/get-config (constantly {:value {}})
                    api/config (constantly {:providers [provider]})
                    context/messages (fn [_ prompt]
                                       [{:role "user" :content prompt}])
                    legacy/discord? (constantly true)
                    legacy/rest-conn (constantly :connection)
                    legacy/start-thread! (fn [channel message title]
                                           (swap! events conj
                                                  [:thread channel
                                                   message title])
                                           "new-thread")
                    discord/create-message!
                    (fn [& _] (discord-promise {:id "progress"}))
                    discord/delete-message! (fn [& _]
                                              (swap! events conj :delete)
                                              (discord-promise {}))
                    chat/chat-data-structure (fn [value]
                                               (swap! events conj
                                                      [:reply chat/*target*
                                                       value]))
                    tools/complete (fn [& _]
                                     (if failure?
                                       (throw (Exception. "SECRET"))
                                       {:result/value "Answer"}))]
        (fact
          (:suppress
            (meta (grok/grok-cmd
                    {:match "Hi" :chat-source
                     {:raw-event {:channel-id "parent" :id "trigger"}}})))
          => true
          @events
          => [[:thread "parent" "trigger" "Hi"] :delete
              [:reply "new-thread"
               (if failure?
                 (str "The AI request failed. Check the provider "
                      "configuration and try again.")
                 "Answer\n\nSent via configured-grok")]])))))

(def discord-request
  {:match "Hi" :chat-source
   {:raw-event {:channel-id "parent" :id "trigger"}}})

(defn with-discord [f]
  (with-redefs [config/get-config (constantly {:value {}})
                api/config (constantly {:providers [provider]})
                context/messages (fn [_ prompt]
                                   [{:role "user" :content prompt}])
                legacy/discord? (constantly true)
                legacy/rest-conn (constantly :connection)
                legacy/start-thread! (fn [& _] "new-thread")]
    (f)))

(fact "a progress message arriving after generation is still deleted"
  (let [pending (async/promise-chan)
        deleted (promise)
        posted (promise)
        ;; Simulate expiry without waiting thirty seconds. The result remains
        ;; a readable async port, just like Discljord's queued result.
        delayed (reify
                  clojure.lang.IBlockingDeref
                  (deref [_ _ timeout-value] timeout-value)
                  async-protocols/ReadPort
                  (take! [_ handler]
                    (async-protocols/take! pending handler)))]
    (with-discord
      (fn []
        (with-redefs [discord/create-message! (fn [& _] delayed)
                      discord/delete-message! (fn [_ _ id]
                                                (deliver deleted id)
                                                (discord-promise {}))
                      tools/complete (fn [& _] {:result/value "Answer"})
                      chat/chat-data-structure #(deliver posted %)]
          (grok/grok-cmd discord-request)
          (fact (deref posted 1000 :timeout)
                => "Answer\n\nSent via configured-grok")
          (async/>!! pending {:id "late-status"})
          (fact (deref deleted 1000 :timeout) => "late-status"))))))

(fact "cleanup waits for an in-flight progress edit before deletion"
  (let [edit-started (promise)
        generation-finished (promise)
        edit-result (discord-util/derefable-promise-chan)
        events (atom [])]
    (with-discord
      (fn []
        (with-redefs [discord/create-message!
                      (fn [& _] (discord-promise {:id "progress"}))
                      discord/edit-message!
                      (fn [& _]
                        (swap! events conj :edit)
                        (deliver edit-started true)
                        edit-result)
                      discord/delete-message!
                      (fn [& _]
                        (swap! events conj :delete)
                        (discord-promise {}))
                      tools/complete
                      (fn [& _]
                        (deref edit-started 5000 nil)
                        (deliver generation-finished true)
                        {:result/value "Answer"})
                      chat/chat-data-structure
                      (fn [_] (swap! events conj :reply))]
          (let [result (future (grok/grok-cmd discord-request))]
            (try
              (fact (deref generation-finished 5000 :timeout) => true
                    (deref result 100 :pending) => :pending
                    @events => [:edit])
              (finally (async/>!! edit-result {})))
            (fact (:suppress (meta (deref result 1000 nil))) => true
                  @events => [:edit :delete :reply])))))))

(facts "Discord progress failures do not discard the final answer"
  (doseq [failure [:create-throws :create-nil :missing-id :delete-throws]]
    (let [posted (promise)]
      (with-discord
        (fn []
          (with-redefs [discord/create-message!
                        (fn [& _]
                          (case failure
                            :create-throws (throw (Exception. "SECRET"))
                            :create-nil nil
                            :missing-id (discord-promise {})
                            (discord-promise {:id "progress"})))
                        discord/delete-message!
                        (fn [& _] (throw (Exception. "SECRET")))
                        tools/complete (fn [& _] {:result/value "Answer"})
                        chat/chat-data-structure #(deliver posted %)]
            (grok/grok-cmd discord-request)
            (fact (deref posted 1000 :timeout)
                  => "Answer\n\nSent via configured-grok")))))))
