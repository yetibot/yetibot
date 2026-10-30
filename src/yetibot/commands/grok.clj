(ns yetibot.commands.grok
  "Grok shortcut over shared AI configuration, policy and MCP execution."
  (:require [clojure.core.async :as async]
            [clojure.string :as string]
            [clojure.walk :as walk]
            [discljord.messaging :as discord]
            [yetibot.api.ai :as api]
            [yetibot.api.ai-stream :as stream]
            [yetibot.api.mcp :as mcp]
            [yetibot.commands.ai :as ai]
            [yetibot.core.adapters.discord :as host-discord]
            [yetibot.core.chat :as chat]
            ;; Require before registering our hook: the loader may encounter
            ;; core before or after this namespace, but require is idempotent.
            [yetibot.core.commands.grok :as legacy]
            [yetibot.core.config :refer [get-config]]
            [yetibot.core.hooks :refer [cmd-hook]]
            [yetibot.core.util.xai :as xai]
            [yetibot.util.ai-execution :as execution]))

(def usage
  (str "Usage: grok [--model <model>] "
       "[--toolset <name> | --no-tools] [--max-tool-calls <1..20>] "
       "[--no-thread] <prompt>"))

(defn- answer-text [prepared text]
  (str text "\n\nSent via " (:model prepared)))

(defn- generate [prepared]
  (let [result (ai/complete-prompt prepared)]
    (walk/postwalk
      #(if (string? %)
         (mcp/redact % {:secrets [(get-in prepared [:provider :key])]}) %)
      (-> result
          (update :result/value #(answer-text prepared %))
          (update :result/data assoc
                  :provider (get-in prepared [:provider :name])
                  :model (:model prepared))))))

(defn- discord-result [f]
  (try (deref (f) 30000 nil) (catch Exception _ nil)))

(defn- progress-text [{:keys [view activity]} tick]
  (stream/clip
    (str "*Grok — " (:phase view "Thinking") "* "
         (apply str (repeat (inc (mod tick 10)) "▰"))
         (when (seq activity)
           (str "\n" (string/join "\n" (map #(stream/clip % 80) activity))))
         (when (seq (:summary view))
           (str "\n\n*Thinking preview*\n" (stream/tail (:summary view) 400)))
         (when (seq (:text view))
           (str "\n\n*Draft answer*\n" (stream/tail (:text view) 1000))))
    1900))

(defn- start-progress! [conn thread]
  (let [state (atom {:active? true :view {} :activity []})
        _ (try
            (when-let [pending (discord/create-message!
                                 conn thread :content "*thinking* ▰"
                                 :allowed-mentions {:parse []})]
              (async/take!
                pending
                (fn [{:keys [id]}]
                  (when id
                    (locking state
                      (if (:active? @state)
                        (swap! state assoc :id id)
                        ;; Discljord may create a queued message after the
                        ;; answer. Its completion must still queue deletion.
                        (try (discord/delete-message! conn thread id)
                             (catch Exception _ nil))))))))
            (catch Exception _ nil))
        progress
        (future
          (loop [tick 1]
            (Thread/sleep 2000)
            (when (locking state
                    (when (:active? @state)
                      (when-let [id (:id @state)]
                        (discord-result
                          #(discord/edit-message!
                             conn thread id
                             :content (progress-text @state tick)
                             ;; Discljord 1.3.1 edits keep literal JSON keys.
                             :allowed_mentions {:parse []})))
                      true))
              (recur (inc tick)))))]
    {:update!
     (fn [event]
       (swap! state
              (fn [s]
                (if-not (:active? s) s
                  (let [phase (:phase event)
                        s (update s :view merge event)]
                    (if (and (string? phase)
                             (re-find #"^(Searching|Running|Finished|Unknown)"
                                      phase)
                             (not= phase (last (:activity s))))
                      (update s :activity
                              #(vec (take-last 3 (conj % phase)))) s))))))
     :stop!
     (fn []
       (locking state
         (swap! state assoc :active? false)
         (when-let [id (:id @state)]
           (discord-result #(discord/delete-message! conn thread id))))
       (future-cancel progress))}))

(defn- answer-chunks [^String text]
  (let [length (.length text)
        maximum host-discord/discord-max-message-length]
    (loop [start 0 chunks []]
      (if (= start length) chunks
        (let [end (min length (+ start maximum))
              end (if (and (< end length)
                           (Character/isHighSurrogate (.charAt text (dec end)))
                           (Character/isLowSurrogate (.charAt text end)))
                    (dec end) end)]
          (recur end (conj chunks (subs text start end))))))))

(defn- answer-publisher [prepared thread]
  (let [state (atom {:answers [] :bytes 0 :messages 0})
        render #(mcp/redact (answer-text prepared %)
                            {:secrets [(get-in prepared [:provider :key])]})
        notice "Additional Grok answers exceeded the chat delivery limit."
        rendered-notice (render notice)
        notice (if (<= (count rendered-notice)
                       host-discord/discord-max-message-length)
                 rendered-notice notice)]
    (letfn [(blocked? []
              (some @state [:limited? :stale? :failed?]))
            (post! [chunks]
              (try
                (case (host-discord/publish-command-output! thread chunks)
                  :published true
                  :stale (do (swap! state assoc :stale? true) false)
                  :unavailable
                  (do
                    (doseq [chunk chunks]
                      (let [message
                            (deref (discord/create-message!
                                     (legacy/rest-conn) thread :content chunk
                                     :allowed-mentions {:parse []})
                                   30000 nil)]
                        (when-not (:id message)
                          (throw (ex-info "Unconfirmed Discord delivery" {})))))
                    true))
                ;; A REST batch may have partially delivered. Do not record
                ;; success or automatically retry an uncertain batch/final.
                (catch Exception _
                  (swap! state assoc :failed? true)
                  false)))
            (limit! []
              (swap! state assoc :limited? true)
              (post! [notice]))
            (admit! [text rendered publish!]
              (let [chunks (answer-chunks rendered)
                    bytes (alength (.getBytes ^String rendered "UTF-8"))]
                (if (or (> (+ (:messages @state) (count chunks))
                           chat/max-msg-count)
                        (> (+ (:bytes @state) bytes) 65536))
                  (limit!)
                  (when (publish! chunks)
                    (swap! state #(-> % (update :answers conj text)
                                       (update :messages + (count chunks))
                                       (update :bytes + bytes)))))))
            (posted? [value]
              (let [answers (:answers @state)]
                (some #(= value (render (string/join (subvec answers %))))
                      (range (count answers)))))]
      {:update!
       (fn [event]
         (locking state
           (when-not (blocked?)
             (if (:answer-limit? event)
               (limit!)
               (when (string? (:answer event))
                 (admit! (:answer event) (render (:answer event)) post!))))))
       :finish!
       (fn [value]
         (locking state
           (when-not (or (blocked?) (posted? value))
             ;; Ordinary terminal output still uses the collector, but must
             ;; pass the same whole-answer admission and safe splitting.
             (admit! nil value
                     (fn [chunks]
                       (doseq [chunk chunks]
                         (chat/chat-data-structure chunk))
                       true)))))
       :stale? #(:stale? @state)
       :failed? #(:failed? @state)})))

(defn- in-discord [prepared args channel-id message-id]
  (let [thread (if (:no-thread prepared) channel-id
                (legacy/start-thread! channel-id message-id (:prompt prepared)))
        progress (start-progress! (legacy/rest-conn) thread)
        answers (answer-publisher prepared thread)
        result (try
                 (ai/safely
                   args #(generate
                           (assoc prepared :on-progress
                                  (fn [event]
                                    ((:update! answers) event)
                                    ((:update! progress)
                                     (dissoc event :answer :answer-limit?))))))
                 (finally ((:stop! progress))))]
    (binding [chat/*target* thread]
      (when-not ((:stale? answers))
        (if-let [error (or (:result/error result)
                          (when
                            (and (get-in result [:result/data :isError])
                                 (get-in result [:result/data :tool-trace]))
                            (:result/value result)))]
          (chat/chat-data-structure error)
          (when-not ((:failed? answers))
            ((:finish! answers) (:result/value result)))))
      (when (and ((:failed? answers)) (not ((:stale? answers))))
        (chat/chat-data-structure
          "Grok answer delivery failed; some messages may have arrived.")))
    (chat/suppress
      (if (get-in result [:result/data :tool-trace])
        {:result/value (chat/suppress {}) :result/data (:result/data result)}
        {}))))

(defn- grok-config []
  (let [config (if (= :not-found (:error (get-config map? [:ai])))
                 {:providers []}
                 (api/config))
        key (:key xai/config)]
    (if (and (not-any? #(= "grok" (:name %)) (:providers config))
             (api/nonblank-string? key))
      (update config :providers conj
              {:name "grok" :url "https://api.x.ai/v1" :key key
               :model "grok-4.7" :api "responses"
               :native-tools ["web_search" "x_search"]})
      config)))

(defn grok-cmd
  {:doc (str "grok <prompt> # ask Grok (provider named grok or xai.key)\n"
             "   grok --model <model> <prompt> # choose a model "
             "from the Grok provider\n"
             "   grok --toolset <name> <prompt> # use an authorized "
             "shared MCP toolset\n"
             "   grok --max-tool-calls <1..20> <prompt> # choose a total "
             "host-call budget within the configured ceiling\n"
             "   grok --no-tools <prompt> # disable MCP and native search "
             "for this request\n"
             "   grok --no-thread <prompt> # reply here without creating "
             "a Discord thread\n"
             "   # Discord shows live thinking previews, tool status "
             "and draft answer text.")
   :yb/cat #{:ai}}
  [{:keys [chat-source] :as args}]
  (ai/safely args
    (fn []
      (let [_ (when execution/*sensitive* (execution/sensitive!))
            prepared (ai/prepare-prompt args "grok" usage grok-config
                                        {"--no-thread" :no-thread})
            event (:raw-event chat-source)
            channel-id (or (:channel-id event) chat/*target*)
            message-id (:id event)]
        (if (and (legacy/discord?) channel-id message-id)
          (in-discord prepared args channel-id message-id)
          (generate prepared))))))

(cmd-hook #"grok"
  #"(?s)^.+$" grok-cmd)
