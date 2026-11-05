(ns yetibot.models.ai-context
  "Read current thread or same-channel reply ancestry; retain no session state."
  (:require [clj-slack.conversations :as slack]
            [clojure.string :as string]
            [discljord.messaging :as discord]
            [yetibot.api.ai :as ai]
            [yetibot.util.ai-execution :as execution]
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

(defn- reply-await [deadline response]
  (let [remaining (- deadline (System/nanoTime))
        timeout ::reply-timeout
        value (when (pos? remaining)
                (deref response (max 1 (quot remaining 1000000)) timeout))]
    (when (or (not (pos? remaining)) (= timeout value))
      (ai/fail "Timed out reading the Discord reply chain."))
    value))

(defn- snowflake? [value]
  (and (string? value) (boolean (re-matches #"[0-9]{1,20}" value))))

(defn- discord-reply-history [connection event deadline]
  (let [channel (:channel-id event)]
    (loop [reference (:message-reference event)
           before (:id event) messages [] characters 0]
      (if-not reference
        (vec (reverse messages))
        (let [id (:message-id reference)]
          (when-not (and (map? reference) (snowflake? id)
                         (snowflake? before) (snowflake? channel)
                         (= 0 (get reference :type 0))
                         (= channel (get reference :channel-id channel))
                         (< (bigint id) (bigint before)))
            (ai/fail "Discord reply context must reference prior messages in this channel."))
          (when (>= (count messages) 32)
            (ai/fail "Discord reply chain exceeds 32 messages. Start a fresh conversation."))
          (when (>= (System/nanoTime) deadline)
            (ai/fail "Timed out reading the Discord reply chain."))
          (let [message (reply-await deadline
                          (discord/get-channel-message! connection channel id))]
            (when-not (and (= id (:id message))
                           (= channel (:channel-id message)))
              (ai/fail "Could not read a Discord reply ancestor. No prompt was sent."))
            (let [characters (+ characters
                                (count (components/message-text message)))]
              (when (> characters 64000)
                (ai/fail "Discord reply context exceeds 64000 characters. Start a fresh conversation."))
              (recur (:message-reference message) id
                     (conj messages message) characters))))))))

(defn- discord-history [event]
  (let [connection (some-> chat/*adapter* :conn deref :rest)
        channel-id (:channel-id event)
        message-id (:id event)
        deadline (when (:message-reference event)
                   (+ (System/nanoTime) 10000000000))]
    (when-not (and connection channel-id message-id)
      (ai/fail "Discord message details are missing."))
    (let [response (discord/get-channel! connection channel-id)
          channel (if deadline (reply-await deadline response)
                      (await-response response))]
      (when-not (and (= channel-id (:id channel)) (integer? (:type channel)))
        (ai/fail "Could not read the Discord channel."))
      (if-not (#{10 11 12} (:type channel))
        (when (:message-reference event)
          (discord-reply-history connection event deadline))
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
  "Include prior thread text or explicit reply ancestry, then the prompt once.
   Failed or oversized reads must not silently become a context-free request."
  [{:keys [chat-source yetibot-user user]} prompt]
  (try
    (let [raw-event (:raw-event chat-source)
          event (if (= "message_changed" (:subtype raw-event))
                  (merge raw-event (:message raw-event))
                  raw-event)
          _ (when (and (= :discord (:adapter chat-source))
                       (:message-reference event))
              (when-not (and (= chat-source
                                (:chat-source execution/*event*))
                             (= user (:user execution/*event*))
                             (= :message (:event-type execution/*event*))
                             (:uuid chat-source)
                             (ai/nonblank-string? (:id user))
                             (= (:id user) (get-in event [:author :id]))
                             (not (:yetibot? user))
                             (not (get-in event [:author :bot])))
                (ai/fail "Discord reply context requires a trusted chat request.")))
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
      (throw (ex-info
               (if (:ai/error (ex-data e)) (.getMessage e)
                 "Could not read the conversation. No prompt was sent to the AI provider.")
               {:ai/error true :ai/context true})))))
