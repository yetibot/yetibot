(ns yetibot.test.support.ai-status
  "Synthetic app/core/SDK boundary; no core context bindings or live HTTP."
  (:require [cheshire.core :as json]
            [clojure.core.async :as async]
            [clojure.string :as string]
            [discljord.messaging.impl :as sdk]
            [midje.sweet :refer [fact =>]]
            [org.httpkit.client :as http]
            [yetibot.api.ai :as ai]
            [yetibot.commands.ai]
            [yetibot.commands.grok]
            [yetibot.core.adapters.discord :as discord]
            [yetibot.core.chat :as chat]
            [yetibot.core.commands.grok :as legacy]
            [yetibot.core.config :as config]
            [yetibot.core.db.observe :as observers]
            [yetibot.core.models.channel :as channel]
            [yetibot.core.models.history :as history]
            [yetibot.core.models.users :as users]
            [yetibot.models.ai-context :as context]
            [yetibot.models.ai-tools :as tools]))

(def ^:dynamic *fixture* false)
(def ^:dynamic *lane* nil)
(def model "status-fixture-model")
(def target "status-fixture-parent")
(def thread "status-fixture-thread")
(def forbidden
  "RAW-PHASE RAW-REASONING RAW-SUMMARY RAW-DRAFT RAW-ARG RAW-RESULT SECRET")

(defn await [p]
  (deref p 5000 :timeout))

(defn finish! [completion]
  (let [[_ port] (async/alts!! [completion (async/timeout 5000)])]
    (fact port => completion)))

(defn captioned? [text answer]
  (boolean
    (re-matches
      (re-pattern (str (java.util.regex.Pattern/quote answer)
                       "\\n\\n-# " model " · [0-9]+\\.[0-9]s"
                       "(?: · [0-9]+ tools?)? · cost n/a")) text)))

(defn status-text [call]
  (get-in call [:body :components 0 :components 0 :content]))

(defn status-calls [calls]
  (filterv #(and (= :status (:lane %)) (= 32768 (get-in % [:body :flags]))) calls))

(defn await-card [calls expected]
  (let [observed (promise)
        watch-key (Object.)
        observe (fn [records]
                  (when-let [card (some #(when (= expected (status-text %)) %)
                                       (status-calls records))]
                    (deliver observed card)))]
    (add-watch calls watch-key
               (fn [_ _ _ records] (observe records)))
    (try
      (observe @calls)
      (await observed)
      (finally (remove-watch calls watch-key)))))

(defn answer-calls [calls]
  (filterv #(and (= :answer (:lane %)) (#{:post :patch} (:method %))) calls))

(defn deletions [calls]
  (filterv #(= :delete (:method %)) calls))

(defn scenario
  "Pump the genuine SDK endpoints on isolated synthetic REST queues."
  [{:keys [generate missing-lane? status-failure? hold-create hold-edit]}
   check]
  (let [answer-queue (async/chan 32)
        status-queue (async/chan 32)
        calls (atom []) answer-id (atom 0) status-id (atom 0)
        created (promise) edited (promise) answered (promise) deleted (promise)
        adapter (discord/make-discord {:name "status-fixture-adapter"})
        conn (:conn adapter)
        originals {:post http/post :patch http/patch :delete http/delete
                   :config config/get-config :ai ai/config
                   :tools tools/complete :context context/messages
                   :settings channel/settings-for-chat-source
                   :history history/add :observers observers/find-all
                   :thread legacy/start-thread!}
        request!
        (fn [method url options]
          (if-not (string/includes? url "/channels/status-fixture-")
            ((get originals method) url options)
            (let [body (if-let [parts (:multipart options)]
                         (json/parse-string
                           (:content (first (filter
                                              #(= "payload_json" (:name %))
                                              parts))) true)
                         (when (:body options)
                           (json/parse-string (:body options) true)))
                  id (if (= method :post)
                       (str (if (= *lane* :status) "status-" "answer-")
                            (swap! (if (= *lane* :status)
                                     status-id answer-id) inc))
                       (last (string/split url #"/")))
                  call {:method method :url url :body body :id id
                        :lane *lane*
                        :file? (boolean
                                 (some #(= "file" (:name %))
                                       (:multipart options)))}]
              (swap! calls conj call)
              (when (and (= *lane* :status) (= 32768 (:flags body)))
                (if (= method :post)
                  (do (deliver created call)
                      (when hold-create (await hold-create)))
                  (do (deliver edited call)
                      (when hold-edit (await hold-edit)))))
              (when (and (= *lane* :answer) (get-in body [:components 0 :components 0 :content]))
                (deliver answered call))
              (when (= method :delete) (deliver deleted call))
              (delay
                (if (or (= method :delete)
                        (string/ends-with? url "/typing"))
                  {:status 204 :body ""}
                  {:status 200
                   :body (json/generate-string
                           (if (and status-failure? (= *lane* :status))
                             {:code 50001} {:id id}))})))))]
    (reset! conn (cond-> {:rest answer-queue}
                   (not missing-lane?) (assoc :status-rest status-queue)))
    (reset! (:yetibot-user adapter) {:id "status-fixture-bot"})
    (binding [*fixture* true]
      (with-redefs
        [http/post (partial request! :post)
         http/patch (partial request! :patch)
         http/delete (partial request! :delete)
         config/get-config
         (fn [& args]
           (if *fixture*
             (if (= [:ai] (second args))
               {:value {}} {:error :not-found})
             (apply (:config originals) args)))
         ai/config
         (fn [] (if *fixture*
                  {:providers [{:name "grok" :model model
                                :url "http://status-fixture.invalid/v1"}]}
                  ((:ai originals))))
         tools/complete
         (fn [p m messages options]
           (if (= m model) (generate p m messages options)
             ((:tools originals) p m messages options)))
         context/messages
         (fn [args prompt]
           (if *fixture* [{:role "user" :content prompt}]
             ((:context originals) args prompt)))
         channel/settings-for-chat-source
         (fn [& args] (if *fixture* {}
                       (apply (:settings originals) args)))
         history/add
         (fn [& args] (when-not *fixture*
                       (apply (:history originals) args)))
         observers/find-all
         (fn [& args] (if *fixture* []
                       (apply (:observers originals) args)))
         legacy/start-thread!
         (fn [& args] (if *fixture* thread
                       (apply (:thread originals) args)))
         users/users (atom {})]
        (let [pump (fn [lane queue]
                     (async/thread
                       (binding [*lane* lane]
                         (loop []
                           (when-let [[endpoint & data] (async/<!! queue)]
                             (sdk/dispatch-http "Bot synthetic" endpoint data)
                             (recur))))))
              workers [(pump :answer answer-queue)
                       (pump :status status-queue)]
              run! (fn [text]
                     (binding [chat/*adapter* adapter]
                       (discord/handle-event
                         :message-create
                         {:id "status-fixture-command" :channel-id target
                          :author {:id "status-fixture-user"
                                   :username "synthetic"}
                          :content (str "!" text)}
                         conn (:yetibot-user adapter))))]
          (try
            (check {:run! run! :calls calls :created created :edited edited
                    :answered answered :deleted deleted :conn conn})
            (finally
              (when hold-create (deliver hold-create true))
              (when hold-edit (deliver hold-edit true))
              (async/close! answer-queue)
              (async/close! status-queue)
              (doseq [worker workers] (async/<!! worker)))))))))

(defn typed-status! [command & [threaded?]]
  (let [ready (promise) release (promise) callback (promise)]
    (scenario
      {:generate
       (fn [_ _ _ options]
         (deliver callback (:on-progress options))
         (when-let [notify (:on-progress options)]
           (notify {:phase forbidden :summary forbidden :text forbidden
                    :reasoning forbidden :arguments forbidden
                    :result forbidden
                    :tool-budget {:used 1 :limit 7 :remaining 6}
                    :tool-event {:domain :host :call 1 :label "safe_tool"
                                 :outcome :running}})
           ;; Callback from a plain Java thread, not a binding-conveying future.
           (let [worker (Thread.
                          ^Runnable
                          #(notify
                             {:tool-event {:domain :host :call 1
                                           :label "safe_tool"
                                           :outcome :error}}))]
             (.start worker)
             (.join worker))
           (notify {:tool-event {:domain :native :response 2 :call 1
                                 :label "web search" :outcome :unknown}})
           (when (= command :grok)
             (notify {:answer "Immediate @everyone"})))
         (deliver ready true)
         (await release)
         {:result/value "Final answer @everyone"
          :result/data {:unchanged "result"}})}
      (fn [{:keys [run! calls created edited answered deleted conn]}]
        (let [completion (run! (if (= command :grok)
                                (if threaded? "grok fixture"
                                  "grok --no-thread fixture")
                                "ai fixture"))]
          (try
            (fact (await ready) => true)
            (fact (not= :timeout (await created)) => true)
            (await-card calls (str "Tools: 1/7 · 6 remaining\n"
                                   "#1 safe_tool — error\n"
                                   "Search 2 #1 web search — unknown"))
            (let [cards (status-calls @calls)
                  card (last cards)
                  body (:body card)
                  text (status-text card)]
              (fact (count (filter #(= :post (:method %)) cards)) => 1)
              (fact (:lane card) => :status
                    (:flags body) => 32768
                    (get-in body [:components 0 :type]) => 17
                    (get-in body [:components 0 :components 0 :type]) => 10
                    (:allowed_mentions body)
                    => {:parse [] :replied_user false}
                    text => (str "Tools: 1/7 · 6 remaining\n"
                                 "#1 safe_tool — error\n"
                                 "Search 2 #1 web search — unknown")
                    (boolean (re-find #"RAW-|SECRET|@everyone" (str body)))
                    => false
                    (string/includes? (or (:url card) "")
                                      (if threaded? thread target)) => true))
            (when (= command :grok)
              (fact (get-in (await answered) [:body :components 0 :components 0 :content])
                    => (str "Immediate @everyone\n\n-# " model " · cost n/a"))
              (fact (empty? (deletions @calls)) => true))
            (finally (deliver release true)))
          (finish! completion)
          (fact (not= :timeout (await deleted)) => true)
          (let [answers (answer-calls @calls)
                final (last answers)
                expected (str "Final answer @everyone"
                              (when (= command :grok)
                                (str "\n\n-# " model " · cost n/a")))]
            (fact (if (= command :ai)
                    (boolean (re-matches
                               (re-pattern
                                 (str (java.util.regex.Pattern/quote expected)
                                      "\\n\\n-# " model " · [0-9]+\\.[0-9]s · cost n/a"))
                               (get-in final [:body :components 0 :components 0 :content])))
                    (= expected (get-in final [:body :components 0 :components 0 :content]))) => true
                  (every? #(= 32768 (get-in % [:body :flags])) answers) => true)
            ;; Typing consumed a synthetic status ID; cleanup must use the
            ;; confirmed card ID, never an answer or a guessed identifier.
            (fact (mapv :id (deletions @calls))
                  => [(:id (first (status-calls @calls)))])
            (let [before @calls]
              (when-let [notify (await callback)]
                (when (fn? notify)
                  (notify {:tool-event {:domain :host :call 2
                                        :label "late" :outcome :running}})))
              (fact @calls => before))
            (fact (empty? (:active-commands @conn)) => true)))))))

(defn success-failure! []
  (doseq [failure? [false true]]
    (let [release (promise)]
      (scenario
        {:generate (fn [& _]
                     (await release)
                     (if failure?
                       (throw (Exception. "SECRET"))
                       {:result/value "Answer"}))}
        (fn [{:keys [run! calls created deleted]}]
          (let [completion (run! "grok fixture")]
            (try
              (fact (not= :timeout (await created)) => true)
              (finally (deliver release true)))
            (finish! completion)
            (fact (not= :timeout (await deleted)) => true)
            (let [answer (first (answer-calls @calls))
                  deletion (first (deletions @calls))
                  card (first (status-calls @calls))]
              (fact (get-in answer [:body :components 0 :components 0 :content])
                    => (if failure?
                         (str "The AI request failed. Check the provider "
                              "configuration and try again.")
                         (str "Answer\n\n-# " model " · cost n/a"))
                    (string/includes? (:url answer) thread) => true
                    (string/includes? (:url card) thread) => true
                    (< (.indexOf @calls answer)
                       (.indexOf @calls deletion)) => true
                    (mapv :id (deletions @calls)) => [(:id card)]))))))))

(defn late-create! []
  (let [release (promise) receipt (promise)]
    (scenario
      {:hold-create receipt
       :generate (fn [& _]
                   (await release)
                   {:result/value "Answer"})}
      (fn [{:keys [run! calls created answered deleted]}]
        (let [completion (run! "grok fixture")]
          (try
            (fact (not= :timeout (await created)) => true)
            (deliver release true)
            (fact (get-in (await answered) [:body :components 0 :components 0 :content])
                  => (str "Answer\n\n-# " model " · cost n/a"))
            (fact (deletions @calls) => [])
            (finally (deliver release true) (deliver receipt true)))
          (finish! completion)
          (fact (not= :timeout (await deleted)) => true)
          (let [card (first (status-calls @calls))]
            (fact (mapv :id (deletions @calls)) => [(:id card)]
                  (some #{(:id card)} (map :id (answer-calls @calls)))
                  => nil)))))))

(defn held-edit! []
  (let [advance (promise) release (promise) edit-receipt (promise)
        tracked (promise)]
    (scenario
      {:hold-edit edit-receipt
       :generate
       (fn [_ _ _ options]
         (await advance)
         ((:on-progress options)
          {:tool-event {:domain :host :call 1 :label "safe_tool"
                        :outcome :running}})
         (await release)
         {:result/value "Answer"})}
      (fn [{:keys [run! calls created edited answered deleted conn]}]
        (let [completion (run! "grok fixture")]
          (try
            (fact (not= :timeout (await created)) => true)
            (deliver advance true)
            (fact (not= :timeout (await edited)) => true)
            (let [entry (get-in @conn [:commands
                                      [target "status-fixture-command"]])]
              (add-watch (:replies entry) ::tracked
                         (fn [_ _ _ replies]
                           (when (seq replies)
                             (deliver tracked (mapv :id replies))))))
            (deliver release true)
            (let [answer (await answered)]
              (fact (get-in answer [:body :components 0 :components 0 :content])
                    => (str "Answer\n\n-# " model " · cost n/a")))
            ;; The ancillary HTTP request is still held. Normal answer IDs
            ;; are retained independently by the genuine command collector.
            (fact (await tracked) => ["answer-1"])
            (finally
              (deliver advance true)
              (deliver release true)
              (deliver edit-receipt true)))
          (finish! completion)
          (fact (not= :timeout (await deleted)) => true)
          (let [answer (first (answer-calls @calls))
                deletion (first (deletions @calls))]
            (fact (< (.indexOf @calls answer)
                     (.indexOf @calls deletion)) => true
                  (mapv :id (deletions @calls))
                  => [(:id (first (status-calls @calls)))])))))))
