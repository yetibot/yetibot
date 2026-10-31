(ns yetibot.test.integration.grok-discord
  (:require [cheshire.core :as json]
            [clojure.core.async :as async]
            [clojure.java.jdbc :as jdbc]
            [clojure.string :as string]
            [discljord.messaging :as messaging]
            [discljord.util :as discord-util]
            [midje.sweet :refer [fact =>]]
            [yetibot.api.ai :as ai]
            [yetibot.commands.grok]
            [yetibot.core.adapters.discord :as discord]
            [yetibot.core.chat :as chat]
            [yetibot.core.commands.grok :as legacy]
            [yetibot.core.commands.observe]
            [yetibot.core.config :as config]
            [yetibot.core.db.observe :as observers]
            [yetibot.core.models.channel :as channel]
            [yetibot.core.models.history :as history]
            [yetibot.core.models.users :as users]
            [yetibot.models.ai-context :as context]
            [yetibot.test.support.mcp :as support]))

(defn- result-channel [value]
  (let [port (discord-util/derefable-promise-chan)]
    (async/>!! port value)
    port))

(defn- write-event! [out event]
  (.write ^java.io.OutputStream out
          (.getBytes (str "data: " (json/generate-string event) "\n\n")
                     "UTF-8"))
  (.flush ^java.io.OutputStream out))

(defn- answer [id text]
  {:id id :type "message" :role "assistant" :status "completed"
   :content [{:type "output_text" :text text}]})

(defn- await! [completion]
  (let [[_ port] (async/alts!! [completion (async/timeout 5000)])]
    (fact port => completion)))

(defn- payload-calls [calls op]
  (filterv #(and (= op (first %))
                 (string/includes? (get-in % [3 :components 0 :components 0 :content] "") "Sent via"))
           calls))

(fact "real handler and Responses SSE publish before terminal and reuse IDs"
  (let [release (promise) observed (promise)
        requests (atom []) calls (atom []) next-id (atom 1)
        status-id (atom 0)
        original-create messaging/create-message!
        original-edit messaging/edit-message!
        original-delete messaging/delete-message!
        original-typing messaging/trigger-typing-indicator!
        jdbc-attempts (atom 0) observer-lookups (atom 0)
        padding (apply str (repeat 2500 "x"))
        first-answer (answer "one" (str "First SECRET @everyone\n" padding))
        second-answer (answer "two" "Next <@123>")
        adapter (discord/make-discord {:name "offline-discord"})
        conn (:conn adapter)
        event {:id "command" :channel-id "parent"
               :author {:id "alice" :username "alice"}
               :content "!grok --no-tools --model selected Hi"}]
    (reset! conn {:rest :mock-rest :status-rest :mock-status-rest})
    (reset! (:yetibot-user adapter) {:id "bot" :username "yetibot"})
    (support/with-http-server
      (fn [request]
        (swap! requests conj request)
        {:stream
         (fn [out]
           (write-event! out {:type "response.output_item.done"
                              :output_index 0 :item first-answer})
           ;; No terminal frame is allowed until the test has observed REST.
           (deref release 5000 nil)
           (write-event! out {:type "response.output_item.done"
                              :output_index 1 :item second-answer})
           (write-event! out {:type "response.completed"
                              :response {:id "response" :status "completed"
                                         :output [first-answer
                                                  second-answer]}}))})
      (fn [url]
        (with-redefs [jdbc/get-connection
                      (fn [& _]
                        (swap! jdbc-attempts inc)
                        (throw (ex-info "Unexpected fixture JDBC access" {})))
                      observers/find-all
                      (fn [& _]
                        (swap! observer-lookups inc)
                        [])
                      config/get-config
                      (fn [_ path]
                        (if (= path [:ai]) {:value {}} {:error :not-found}))
                      ai/config
                      (constantly
                        {:providers [{:name "grok" :api "responses"
                                      :url (str url "/v1") :key "SECRET"
                                      :model "configured"}]})
                      users/users (atom {})
                      history/add (constantly nil)
                      channel/settings-for-chat-source (constantly {})
                      context/messages
                      (fn [_ prompt] [{:role "user" :content prompt}])
                      legacy/start-thread! (fn [& _] "selected-thread")
                      messaging/create-message!
                      (fn [rest target & options]
                        (cond
                          (= rest :mock-status-rest)
                          (let [payload (apply hash-map options)]
                            (fact (:allowed_mentions payload)
                                  => {:parse [] :replied_user false})
                            (result-channel
                              {:id (str "status-" (swap! status-id inc))}))
                          (= rest :mock-rest)
                          (let [id (str (swap! next-id inc))
                                payload (apply hash-map options)]
                            (fact rest => :mock-rest)
                            (swap! calls conj [:create target id payload])
                            (when (string/includes? (get-in payload [:components 0 :components 0 :content] "")
                                                    "Sent via")
                              (deliver observed true))
                            (result-channel {:id id}))
                          :else
                          (apply original-create rest target options)))
                      messaging/edit-message!
                      (fn [rest target id & options]
                        (cond
                          (= rest :mock-status-rest)
                          (do
                            (fact (:allowed_mentions
                                    (apply hash-map options))
                                  => {:parse [] :replied_user false})
                            (result-channel {:id id}))
                          (= rest :mock-rest)
                          (do
                            (swap! calls conj
                                   [:edit target id (apply hash-map options)])
                            (result-channel {:id id}))
                          :else
                          (apply original-edit rest target id options)))
                      messaging/delete-message!
                      (fn [rest target id]
                        (cond
                          (= rest :mock-status-rest) (result-channel {})
                          (= rest :mock-rest)
                          (do (swap! calls conj [:delete target id])
                              (result-channel {}))
                          :else (original-delete rest target id)))
                      messaging/trigger-typing-indicator!
                      (fn [rest target]
                        (if (= rest :mock-status-rest)
                          (result-channel true)
                          (original-typing rest target)))]
          (binding [chat/*adapter* adapter]
            (let [completion (discord/handle-event
                               :message-create event conn
                               (:yetibot-user adapter))]
              (try
                (fact (deref observed 3000 :timeout) => true
                      (count @requests) => 1
                      (payload-calls @calls :create)
                      => [[:create "selected-thread" "2"
                           {:flags 32768
                            :components
                            [{:type 17 :accent_color 0x08B8CD
                              :components
                              [{:type 10
                                :content
                                (str "First [redacted] @everyone\n" padding
                                     "\n\nSent via selected")}
                               {:type 1 :components
                                [{:type 2 :style 2 :label "Run again"
                                  :custom_id "yb:again:parent:command"}
                                 {:type 2 :style 2 :label "Help"
                                  :custom_id "yb:help:parent:command"}
                                 {:type 2 :style 2 :label "Show details"
                                  :custom_id "yb:details:parent:command"}]}]}]
                            :allowed-mentions {:parse []}}]])
                (finally (deliver release true)))
              (await! completion)
              (let [creates (payload-calls @calls :create)
                    edits (payload-calls @calls :edit)]
                (fact (count creates) => 2
                      (mapv second creates)
                      => ["selected-thread" "selected-thread"]
                      (mapv #(get-in % [3 :components 0 :components 0 :content]) creates)
                      => [(str "First [redacted] @everyone\n" padding
                               "\n\nSent via selected")
                          "Next <@123>\n\nSent via selected"]
                      (mapv #(nth % 2) edits) => ["2" "3"]
                      (every? #(= {:parse []}
                                  (get-in % [3 :allowed_mentions])) edits)
                      => true
                      (every? #(= {:parse []}
                                  (get-in % [3 :allowed-mentions])) creates)
                      => true
                      (mapv #(select-keys % [:method :path]) @requests)
                      => [{:method "POST" :path "/v1/responses"}]
                      (get-in @requests [0 :body :model]) => "selected"
                      (get-in @requests [0 :body :stream]) => true
                      (seq (get-in @requests [0 :body :tools])) => nil))
              ;; Same core command entry, new revision: reuse live answer IDs.
              (reset! calls [])
              (await! (discord/handle-event
                        :message-update
                        (assoc event :content
                               "!grok --no-tools --model selected Edited")
                        conn (:yetibot-user adapter)))
              (fact (payload-calls @calls :create) => []
                    (mapv #(nth % 2) (payload-calls @calls :edit))
                    => ["2" "3" "2" "3"]
                    (every? #(= {:parse []}
                                (get-in % [3 :allowed_mentions]))
                            (payload-calls @calls :edit)) => true
                    (count @requests) => 2
                    @observer-lookups => 2
                    @jdbc-attempts => 0))))))))
