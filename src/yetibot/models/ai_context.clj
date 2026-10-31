(ns yetibot.models.ai-context
  "Read the current thread, without retaining any conversation state."
  (:require [clj-slack.conversations :as slack]
            [clojure.string :as string]
            [discljord.messaging :as discord]
            [yetibot.api.ai :as ai]
            [yetibot.core.chat :as chat]
            [yetibot.core.adapters.discord-components :as components]))

(defn- text-message [content assistant? speaker]
  (when (ai/nonblank-string? content)
    {:role (if assistant? "assistant" "user")
     :content (if (or assistant? (string/blank? speaker))
                content
                (str speaker ": " content))}))

(defn- slack-history [event]
  (let [config (:config chat/*adapter*)
        connection {:api-url (or (:endpoint config) "https://slack.com/api")
                    :token (:token config)
                    :conn-timeout 5000
                    :socket-timeout 30000
                    :redirect-strategy :none}]
    (when-not (and (:token connection) (:channel event) (:ts event))
      (ai/fail "Slack thread details are missing."))
    (loop [cursor nil seen #{} messages []]
      (let [page (slack/replies connection (:channel event) (:thread_ts event)
                                (cond-> {:limit "100" :latest (:ts event) :inclusive "true"}
                                  cursor (assoc :cursor cursor)))
            next-cursor (get-in page [:response_metadata :next_cursor])]
        (when-not (and (true? (:ok page)) (sequential? (:messages page)))
          (ai/fail "Could not read the Slack thread. Check the bot's history permissions."))
        (let [messages (into messages (:messages page))]
          (if (ai/nonblank-string? next-cursor)
            (do
              (when (contains? seen next-cursor)
                (ai/fail "Slack thread pagination did not advance."))
              (recur next-cursor (conj seen next-cursor) messages))
            (do
              (when (:has_more page)
                (ai/fail "Slack did not return the complete thread."))
              (->> messages
                   (filter #(and (:ts %) (< (bigdec (:ts %)) (bigdec (:ts event)))))
                   (reduce (fn [by-ts message] (assoc by-ts (:ts message) message)) {})
                   vals
                   (sort-by #(bigdec (:ts %)))))))))))

(defn- await-response [result]
  (let [value (deref result 30000 ::timeout)]
    (when (= ::timeout value)
      (ai/fail "Timed out reading the Discord thread."))
    value))

(defn- discord-history [event]
  (let [connection (some-> chat/*adapter* :conn deref :rest)
        channel-id (:channel-id event)
        message-id (:id event)]
    (when-not (and connection channel-id message-id)
      (ai/fail "Discord message details are missing."))
    (let [channel (await-response (discord/get-channel! connection channel-id))]
      (when-not (and (= channel-id (:id channel)) (integer? (:type channel)))
        (ai/fail "Could not read the Discord channel."))
      (when (#{10 11 12} (:type channel))
        (let [messages
              (loop [before message-id seen #{} messages []]
                (let [page (await-response
                             (discord/get-channel-messages!
                               connection channel-id :limit 100 :before before))]
                  (when-not (and (sequential? page) (every? :id page))
                    (ai/fail "Could not read the Discord thread. Check the bot's history permissions."))
                  (let [messages (into messages page)]
                    (if (< (count page) 100)
                      messages
                      (let [oldest (:id (apply min-key #(bigint (:id %)) page))]
                        (when (or (>= (bigint oldest) (bigint before)) (contains? seen oldest))
                          (ai/fail "Discord thread pagination did not advance."))
                        (recur oldest (conj seen oldest) messages))))))
              ;; Message-based threads keep their starter in the parent channel.
              ;; Forum starters are already in the thread; private threads have none.
              starter (when (and (#{10 11} (:type channel))
                                 (:parent-id channel)
                                 (not= channel-id message-id)
                                 (not-any? #(= channel-id (:id %)) messages))
                        (let [message (await-response
                                        (discord/get-channel-message!
                                          connection (:parent-id channel) channel-id))]
                          (when-not (= channel-id (:id message))
                            (ai/fail "Could not read the Discord thread's starting message."))
                          message))]
          (->> (cond-> messages starter (conj starter))
               (filter #(< (bigint (:id %)) (bigint message-id)))
               (reduce (fn [by-id message] (assoc by-id (:id message) message)) {})
               vals
               (sort-by #(bigint (:id %)))))))))

(defn messages
  "Include all prior thread text, then the current prompt exactly once.
   A failed thread read must not silently become a context-free request."
  [{:keys [chat-source yetibot-user]} prompt]
  (try
    (let [raw-event (:raw-event chat-source)
          event (if (= "message_changed" (:subtype raw-event))
                  (merge raw-event (:message raw-event))
                  raw-event)
          bot-id (:id yetibot-user)
          history (case (:adapter chat-source)
                    :slack (when (:thread_ts event)
                             (keep #(text-message (:text %) (and bot-id (= bot-id (:user %)))
                                                  (:user %))
                                   (slack-history event)))
                    :discord (keep #(text-message (components/message-text %)
                                                  (and bot-id (= bot-id (get-in % [:author :id])))
                                                  (get-in % [:author :username]))
                                   (discord-history event))
                    nil)]
      (conj (vec history) {:role "user" :content prompt}))
    (catch Exception e
      (if (:ai/error (ex-data e))
        (throw e)
        (ai/fail "Could not read the conversation. No prompt was sent to the AI provider.")))))
