(ns yetibot.test.integration.ai-followups
  (:require [clojure.core.async :as async]
            [clojure.string :as str]
            [discljord.messaging :as messaging]
            [midje.sweet :refer [fact =>]]
            [yetibot.api.ai :as api]
            [yetibot.api.mcp :as mcp]
            [yetibot.api.opencode :as zen]
            [yetibot.commands.ai]
            [yetibot.commands.grok]
            [yetibot.commands.zen]
            [yetibot.core.adapters.discord :as discord]
            [yetibot.core.chat :as chat]
            [yetibot.core.config :as core-config]
            [yetibot.core.db.observe :as observers]
            [yetibot.core.models.channel :as channel]
            [yetibot.core.models.history :as history]
            [yetibot.core.models.users :as users]
            [yetibot.util.ai-execution :as execution]))

(def settings
  {:default-provider "local"
   :providers [{:name "local" :url "http://localhost:11434/v1" :model "first"}
               {:name "zen" :url "https://opencode.ai/inference/openai/v1"
                :key "fixture-secret" :model "paid" :api "responses"}
               {:name "grok" :url "https://api.x.ai/v1" :model "grok-4.7"}]})
(defn delivered [value] (deliver (promise) value))
(defn event [id text & [parent]]
  (cond-> {:id id :channel-id "10" :content text
           :author {:id "7" :username "reader"}}
    parent (assoc :message-reference {:message-id parent :channel-id "10"})))
(defn await! [result]
  (when result
    (let [[_ port] (async/alts!! [result (async/timeout 5000)])]
      (when-not (= result port)
        (throw (ex-info "AI followup timed out" {}))))))
(defn session [f]
  (let [adapter (discord/make-discord {:name "test"}) conn (:conn adapter)
        messages (atom {}) next-id (atom 199) calls (atom [])
        config (atom settings) policy (atom {:servers []})
        reject? (atom false)]
    (reset! conn {:rest :fixture})
    (reset! (:yetibot-user adapter) {:id "9"})
    (with-redefs [users/users (atom {})
                  history/add (constantly nil)
                  observers/find-all (constantly [])
                  channel/settings-for-chat-source (constantly {})
                  chat/chat-source
                  (fn [room] {:adapter :discord :uuid "test" :room room})
                  api/config #(deref config)
                  core-config/get-config (fn [_ path]
                                           (if (= [:ai] path)
                                             {:value @config}
                                             {:error :not-found}))
                  mcp/config #(deref policy)
                  api/models
                  (fn [_]
                    (with-meta ["paid" "free-a"]
                      {:catalog
                       {:models (mapv #(hash-map :id % :interface :chat
                                                :pricing {:kind (if (= % "free-a")
                                                                  "free" "paid")}
                                                :schema-summary {:api "responses"})
                                      ["paid" "free-a"])}}))
                  api/complete
                  (fn [provider model history & [options]]
                    (swap! calls conj {:provider provider :model model
                                       :messages history :options options})
                    (if @reject? (api/fail "fixture request rejected")
                      {:result/value "answer"}))
                  api/chat
                  (fn [provider model history & [options]]
                    (swap! calls conj {:provider provider :model model
                                       :messages history :options options})
                    (if @reject? (api/fail "fixture request rejected")
                      {:choices [{:message {:role "assistant"
                                            :content "answer"}}]}))
                  messaging/get-channel!
                  (fn [& _] (delivered {:id "10" :type 0}))
                  messaging/get-channel-message!
                  (fn [_ _ id] (delivered (get @messages id)))
                  messaging/create-message!
                  (fn [_ room & options]
                    (let [options (apply hash-map options)
                          reference (:message-reference options)
                          latest (apply max (map #(Long/parseLong %)
                                                 (keys @messages)))
                          id (str (swap! next-id #(inc (max % latest))))
                          result (cond-> {:id id :channel-id room
                                          :author {:id "9" :bot true}
                                          :components (:components options)}
                                   reference
                                   (assoc :message-reference
                                          {:message-id (:message_id reference)
                                           :channel-id
                                           (:channel_id reference)}))]
                      (swap! messages assoc id result)
                      (delivered result)))
                  messaging/edit-message!
                  (fn [_ room id & options]
                    (swap! messages update id merge
                           (select-keys (apply hash-map options) [:components]))
                    (delivered (get @messages id)))
                  messaging/delete-message!
                  (fn [_ _ id] (swap! messages dissoc id) (delivered true))]
      (binding [chat/*adapter* adapter]
        (f {:adapter adapter :conn conn :messages messages :calls calls
            :config config :policy policy :reject? reject?
            :send! (fn [message]
                     (swap! messages assoc (:id message) message)
                     (await!
                       (discord/handle-event :message-create message conn
                                             (:yetibot-user adapter))))})))))

(fact "plain replies inherit AI, Grok or Zen without reinterpreting their text"
  (doseq [[command provider model options]
          [["ai" "local" "first" "--no-tools "]
           ["grok" "grok" "grok-4.7" "--no-tools "]
           ["zen" "zen" "paid" "--model paid "]]]
    (session
      (fn [{:keys [send! calls config]}]
        (send! (event "100" (str "!" command " " options "first prompt")))
        (swap! config assoc :default-provider "zen")
        (swap! config assoc-in [:providers 0 :model] "changed-default")
        (send! (event "300" "--model attacker | echo `echo pwn`" "200"))
        (count @calls) => 2
        (mapv #(get-in % [:provider :name]) @calls) => [provider provider]
        (mapv :model @calls) => [model model]
        (let [history (:messages (second @calls))]
          (:content (last history)) => "--model attacker | echo `echo pwn`"
          (count (filter #(= "--model attacker | echo `echo pwn`"
                              (:content %)) history)) => 1
          (boolean (some #(= "assistant" (:role %)) history)) => true)
        (send! (event "400" "third turn" "301"))
        (count @calls) => 3))))

(fact "explicit commands switch branches; unrelated chatter stays ignored"
  (session
    (fn [{:keys [send! calls]}]
      (send! (event "100" "!zen --model paid first"))
      (send! (event "300" "!ai --provider local --no-tools switch" "200"))
      (send! (event "400" "continue the local branch" "301"))
      (mapv #(get-in % [:provider :name]) @calls) => ["zen" "local" "local"]
      (send! (event "500" "unrelated chatter"))
      (send! (assoc-in (event "501" "bot loop" "301") [:author :bot] true))
      (count @calls) => 3)))

(fact "failed model answers cannot implicitly retry"
  (session
    (fn [{:keys [send! calls reject?]}]
      (reset! reject? true)
      (send! (event "100" "!zen --model paid first"))
      (reset! reject? false)
      (send! (event "300" "do not retry" "200"))
      (count @calls) => 1)))

(fact "provider changes and unreadable context fail before another inference"
  (doseq [change [:provider :ancestor]]
    (session
      (fn [{:keys [send! calls config messages]}]
        (send! (event "100" "!ai --no-tools first"))
        (case change
          :provider (swap! config assoc-in [:providers 0 :url]
                           "https://different.test/v1")
          :ancestor (swap! messages dissoc "100"))
        (send! (event "300" "followup" "200"))
        (count @calls) => 1))))

(fact "each continuation uses current actor and current toolset permissions"
  (session
    (fn [{:keys [send! calls policy]}]
      (reset! policy
              {:servers [] :toolsets
               [{:name "scoped" :kind "search" :servers [] :native-tools []
                 :users ["7"] :providers [{:name "zen" :models ["paid"]}]}]})
      (send! (event "100" "!zen --model paid --toolset scoped first"))
      (swap! policy assoc-in [:toolsets 0 :users] ["someone-else"])
      (send! (event "300" "permissions revoked" "200"))
      (count @calls) => 1)))

(fact "pipeline and embedded AI output never replay their originating commands"
  (doseq [body ["!ai --no-tools first | echo %s"
                "`ai --no-tools first`"]]
    (session
      (fn [{:keys [send! calls conn]}]
        (send! (event "100" body))
        (send! (event "300" "plain followup" "200"))
        (count @calls) => 1
        (some-> (get-in @conn [:commands ["10" "100"]])
                :continuation deref) => nil))))

(fact "explicit toolset and budget are inherited rather than prompt flags"
  (session
    (fn [{:keys [send! calls conn policy]}]
      (reset! policy
        {:servers [] :default-toolset "scoped"
         :toolsets [{:name "scoped" :kind "search" :servers [] :native-tools []
                     :providers [{:name "local" :models ["first"]}]}
                    {:name "other" :kind "search" :servers [] :native-tools []
                     :providers [{:name "zen" :models ["paid"]}]}]})
      (send! (event "100"
               "!ai --toolset scoped --max-tool-calls 2 first"))
      (let [route (:route @(get-in @conn [:commands ["10" "100"]
                                        :continuation]))]
        (select-keys route [:toolset :max-tool-calls])
        => {:toolset "scoped" :max-tool-calls 2})
      (swap! policy assoc :default-toolset "other")
      (send! (event "300" "--toolset other --max-tool-calls 20" "200"))
      (mapv :model @calls) => ["first" "first"])))

(fact "a different replier never inherits the original actor's permission"
  (session
    (fn [{:keys [send! calls policy]}]
      (reset! policy
        {:servers [] :toolsets
         [{:name "scoped" :kind "search" :servers [] :native-tools []
           :users ["7"] :providers [{:name "zen" :models ["paid"]}]}]})
      (send! (event "100" "!zen --model paid --toolset scoped first"))
      (send! (assoc-in (event "300" "try someone else's tools" "200")
                       [:author :id] "8"))
      (count @calls) => 1)))

(fact "ownership lost while reading ancestors stops inference, not into parsing"
  (session
    (fn [{:keys [send! calls conn]}]
      (send! (event "100" "!ai --no-tools first"))
      (let [read-message messaging/get-channel-message!]
        (with-redefs [messaging/get-channel-message!
                      (fn [connection room id]
                        (when (= id "100")
                          (reset! (get-in @conn [:commands ["10" "100"]
                                                :revision]) (Object.)))
                        (read-message connection room id))]
          (send! (event "300" "literal `echo pwn` | ai retry" "200"))))
      (count @calls) => 1)))

(fact "an automatic free conversation cannot silently become paid or rotate"
  (doseq [command ["!ai --provider zen --no-tools" "!zen"]]
    (session
      (fn [{:keys [send! calls config conn]}]
        (swap! config assoc-in [:providers 1 :model] nil)
        (let [free (atom ["free-a"])]
          (with-redefs [zen/free-models (fn [_] @free)]
            (send! (event "100" (str command " first")))
            (:verified-free (:route @(get-in @conn
                                       [:commands ["10" "100"] :continuation])))
            => true
            (reset! free ["different-free-model"])
            (send! (event "300" "do not incur new charges" "200"))
            (mapv :model @calls) => ["free-a"]))))))

(fact "unexpected follow-up failures disclose uncertainty without raw errors"
  (session
    (fn [{:keys [send! calls messages]}]
      (send! (event "100" "!ai --no-tools first"))
      (with-redefs [execution/run-followup
                    (fn [& _] (throw (Exception. "PRIVATE-ERROR")))]
        (send! (event "300" "followup" "200")))
      (count @calls) => 1
      (str/includes? (pr-str (get @messages "301")) "PRIVATE") => false
      (str/includes? (pr-str (get @messages "301"))
                     "do not retry automatically") => true)))
