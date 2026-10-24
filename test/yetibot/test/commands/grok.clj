(ns yetibot.test.commands.grok
  (:require [clojure.core.async :as async]
            [clojure.core.async.impl.protocols :as async-protocols]
            [clojure.data.json :as json]
            [discljord.messaging :as discord]
            [discljord.messaging.impl :as discord-impl]
            [discljord.util :as discord-util]
            [midje.sweet :refer [fact facts =>]]
            [org.httpkit.client :as http]
            [yetibot.api.ai :as api]
            [yetibot.commands.ai :as command-ai]
            [yetibot.commands.grok :as grok]
            [yetibot.core.chat :as chat]
            [yetibot.core.commands.grok :as legacy]
            [yetibot.core.commands.grokimage :as image]
            [yetibot.core.config :as config]
            [yetibot.core.hooks :as hooks]
            [yetibot.core.models.help :as help]
            [yetibot.core.util.xai :as xai]
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
    => #'grok/grok-cmd
    (doseq [prompt ["--no-tools agent explain infrastructure"
                    "--provider local explain agent deployment"]]
      (fact (second (hooks/match-sub-cmds prompt commands))
            => #'grok/grok-cmd)))
  (let [[_ commands] (hooks/find-sub-cmds "gi")]
    (second (hooks/match-sub-cmds "a snowy mountain" commands))
    => #'image/grokimage-cmd)
  (fact "Grok help advertises neither coding nor provider switching"
    (some #(re-find #"grok agent|--provider" %)
          (help/get-docs-for "grok")) => nil))

(facts "Grok uses shared configuration, model flags and trusted actor identity"
  (let [captured (atom nil)]
    (with-redefs [config/get-config (constantly {:value {}})
                  xai/config {:key "LEGACY_KEY"}
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
      (:result/value (command-ai/prompt-cmd {:match "Hi"})) => "Answer"
      (take 2 @captured) => [other "local-model"]
      (:result/value (grok/grok-cmd {:match "--model grok-custom Hi"}))
      => "Answer\n\nSent via grok-custom"
      (take 2 @captured) => [provider "grok-custom"]
      (reset! captured nil)
      (grok/grok-cmd {:match "--provider local Hi"})
      => {:result/error grok/usage}
      @captured => nil
      (:result/error (grok/grok-cmd {:match "--unknown hello"}))
      => grok/usage)))

(facts "Grok never silently selects another configured provider"
  (doseq [providers [[other] [other (assoc other :name "second")]]]
    (let [calls (atom 0)]
      (with-redefs [config/get-config (constantly {:value {}})
                    xai/config {}
                    api/config (constantly {:providers providers
                                            :default-provider "local"})
                    context/messages (fn [& _] (swap! calls inc))
                    tools/complete (fn [& _] (swap! calls inc))]
        (fact "a missing Grok provider fails before context or model calls"
          (:result/error (grok/grok-cmd {:match "Hi"}))
          => "AI provider grok is not configured. Add it to ai.providers."
          @calls => 0)))))

(facts "xai.key remains Grok even with an unrelated shared AI default"
  (doseq [shared? [false true]]
    (let [captured (atom nil)]
      (with-redefs [config/get-config
                    (constantly (if shared? {:value {}}
                                  {:error :not-found}))
                    xai/config {:key "XAI_ONLY"}
                    legacy/grok-cmd
                    (fn [_] {:result/error "Unexpected legacy command path"})
                    api/config (constantly {:providers [other]
                                            :default-provider "local"})
                    context/messages (fn [_ prompt]
                                       [{:role "user" :content prompt}])
                    tools/complete (fn [p model messages options]
                                     (reset! captured
                                             [p model messages options])
                                     {:result/value "Grok answer"})]
        (fact "legacy credentials use Responses plus the shared tool runtime"
          (:result/value
            (grok/grok-cmd {:match "--toolset cloudflare_read Hi"}))
          => "Grok answer\n\nSent via grok-4.7"
          @captured
          => [{:name "grok" :url "https://api.x.ai/v1" :key "XAI_ONLY"
               :api "responses" :model "grok-4.7"
               :native-tools ["web_search" "x_search"]}
              "grok-4.7" [{:role "user" :content "Hi"}]
              {:toolset "cloudflare_read" :actor nil}])))))

(fact "invalid shared configuration does not fall back to xai.key"
  (with-redefs [config/get-config (constantly {:error :invalid})
                xai/config {:key "XAI_ONLY"}
                api/config (fn [] (api/fail "AI configuration is invalid."))]
    (grok/grok-cmd {:match "Hi"})
    => {:result/error "AI configuration is invalid."}))

(fact "xai.key compatibility still honors no-tools and model selection"
  (let [captured (atom nil)]
    (with-redefs [config/get-config (constantly {:error :not-found})
                  xai/config {:key "XAI_ONLY"}
                  context/messages (fn [_ prompt]
                                     [{:role "user" :content prompt}])
                  tools/complete (fn [& _]
                                   (throw (Exception. "Unexpected MCP call")))
                  api/complete (fn [p model _]
                                 (reset! captured [p model])
                                 {:result/value "No search answer"})]
      (:result/value
        (grok/grok-cmd {:match "--no-tools --model grok-custom Hi"}))
      => "No search answer\n\nSent via grok-custom"
      (get-in @captured [0 :name]) => "grok"
      (get-in @captured [0 :native-tools]) => nil
      (second @captured) => "grok-custom")))

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

(facts "no-thread uses the invoking channel and retains model/tool flags"
  (doseq [channel ["parent" "existing-thread"]]
    (let [posted (atom nil) prepared (atom nil)]
      (with-discord
        (fn []
          (with-redefs [legacy/start-thread!
                        (fn [& _] (throw (Exception. "Must not create thread")))
                        discord/create-message!
                        (fn [& _] (discord-promise {:id "progress"}))
                        discord/delete-message!
                        (fn [& _] (discord-promise {}))
                        tools/complete
                        (fn [p model messages options]
                          (reset! prepared [p model messages options])
                          {:result/value "Answer"})
                        chat/chat-data-structure
                        (fn [value] (reset! posted [chat/*target* value]))]
            (fact
              (:suppress
                (meta (grok/grok-cmd
                        {:match "--model chosen --no-thread Hi"
                         :chat-source
                         {:raw-event {:channel-id channel :id "trigger"
                                      :parent-id "must-not-use"}}})))
              => true
              @posted => [channel "Answer\n\nSent via chosen"]
              (second @prepared) => "chosen"
              (get-in @prepared [2 0 :content]) => "Hi"
              (fn? (get-in @prepared [3 :on-progress])) => true)))))))

(fact "no-thread plus no-tools streams without enabling or contacting tools"
  (let [captured (atom nil) posted (atom nil)]
    (with-discord
      (fn []
        (with-redefs [legacy/start-thread!
                      (fn [& _] (throw (Exception. "Must not create thread")))
                      discord/create-message!
                      (fn [& _] (discord-promise {:id "progress"}))
                      discord/delete-message!
                      (fn [& _] (discord-promise {}))
                      api/complete
                      (fn [p model messages options]
                        (reset! captured [p model messages options])
                        {:result/value "SECRET" :result/data {:text "SECRET"}})
                      tools/complete
                      (fn [& _] (throw (Exception. "Must not call tools")))
                      chat/chat-data-structure #(reset! posted %)]
          (grok/grok-cmd (assoc discord-request :match
                               "--no-tools --no-thread --model chosen Hi"))
          (fact @posted => "[redacted]\n\nSent via chosen"
                (get-in @captured [0 :native-tools]) => nil
                (second @captured) => "chosen"
                (fn? (get-in @captured [3 :on-progress])) => true))))))

(facts "thread switch is Grok-only and respects leading-flag termination"
  (with-discord
    (fn []
      (doseq [flags ["--no-thread --no-thread Hi" "--no-thread"]]
        (fact (:result/error (grok/grok-cmd {:match flags})) => grok/usage))
      (fact (:result/error (command-ai/prompt-cmd
                            {:match "--no-thread Hi"})) => command-ai/usage)
      (let [prompts (atom [])]
        (with-redefs [tools/complete
                      (fn [_ _ messages _]
                        (swap! prompts conj (get-in messages [0 :content]))
                        {:result/value "Answer"})]
          (grok/grok-cmd {:match "Explain --no-thread"})
          (grok/grok-cmd {:match "-- --no-thread"})
          (fact @prompts => ["Explain --no-thread" "--no-thread"]))))))

(fact "live Discord progress shows provider snapshots without pinging mentions"
  (let [edited (promise) posted (promise) options (atom nil)]
    (with-discord
      (fn []
        (with-redefs [discord/create-message!
                      (fn [& _] (discord-promise {:id "progress"}))
                      discord/edit-message!
                      (fn [_ _ _ & opts]
                        (reset! options (apply hash-map opts))
                        (deliver edited (:content @options))
                        (discord-promise {}))
                      discord/delete-message!
                      (fn [& _] (discord-promise {}))
                      tools/complete
                      (fn [_ _ _ options]
                        (when-let [notify (:on-progress options)]
                          (notify {:phase "Searching web"
                                   :summary "Checking sources."
                                   :text "Draft answer @everyone"}))
                        (deref edited 5000 nil)
                        {:result/value "Final answer"})
                      chat/chat-data-structure #(deliver posted %)]
          (grok/grok-cmd discord-request)
          (fact
            (boolean (re-find #"Searching web" (deref edited 1 ""))) => true
            (boolean (re-find #"Checking sources" (deref edited 1 ""))) => true
            (boolean (re-find #"Draft answer" (deref edited 1 ""))) => true)
          (fact (:allowed_mentions @options) => {:parse []}
                (deref posted 1000 nil)
                => "Final answer\n\nSent via configured-grok"))))))

(fact "live progress suppresses mentions in the actual Discord edit payload"
  (let [queue (async/chan 1) request (atom nil)]
    (with-redefs [discord/create-message!
                  (fn [& _] (discord-promise {:id "progress"}))
                  discord/delete-message!
                  (fn [& _] (discord-promise {}))
                  http/patch
                  (fn [_ options]
                    (reset! request options)
                    (delay {:status 200 :body "{\"id\":\"progress\"}"}))]
      (let [progress (#'grok/start-progress! queue "channel")]
        (try
          ((:update! progress) {:text "Draft @everyone"})
          (let [[message] (async/alts!! [queue (async/timeout 5000)])
                [endpoint & data] message]
            (when message
              (discord-impl/dispatch-http "Bot test-token" endpoint data))
            (let [body (json/read-str (or (:body @request) "{}")
                                      :key-fn keyword)]
              (fact (:allowed_mentions body) => {:parse []}
                    (boolean (re-find #"Draft @everyone"
                                      (:content body ""))) => true)))
          (finally ((:stop! progress))))))
    (async/close! queue)))

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
