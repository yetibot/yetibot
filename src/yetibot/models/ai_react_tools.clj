(ns yetibot.models.ai-react-tools
  "A bounded Discord reaction capability, never a command interpreter."
  (:require [yetibot.api.ai :as ai]
            [yetibot.core.adapters.adapter :as adapters]
            [yetibot.core.adapters.discord :as discord]
            [yetibot.models.ai-tool-policy :as policy]
            [yetibot.util.ai-execution :as execution]))

(defn available-tools []
  (if (= :discord (get-in execution/*event* [:chat-source :adapter]))
    ["react"] []))

(defn arguments! [_ operation args]
  (when-not (and (= "react" operation) (map? args)
                 (every? #{"emoji" "message_id"} (keys args))
                 (contains? args "emoji")
                 (or (not (contains? args "message_id"))
                     (and (string? (get args "message_id"))
                          (re-matches #"[0-9]{1,20}" (get args "message_id")))))
    (ai/fail "Invalid reaction arguments."))
  (discord/reaction-emoji (get args "emoji"))
  args)

(def definitions
  {"react"
   {:description
    (str "Add one emoji reaction in the requesting Discord channel/thread "
         "only. Omit message_id to react to the human message requesting "
         "this AI response; use a message_id from current-channel history "
         "for another message. emoji accepts Unicode, <:name:id>, "
         "<a:name:id>, or name:id, not :shortcodes:. "
         "Never accepts channel IDs, URLs, commands or removal actions. "
         "No retry if the outcome is uncertain.")
    :annotations {:readOnlyHint false :destructiveHint false
                  :idempotentHint true :openWorldHint true}
    :inputSchema
    {:type "object" :additionalProperties false :required ["emoji"]
     :properties {:emoji {:type "string" :minLength 1 :maxLength 100}
                  :message_id {:type "string" :pattern "^[0-9]{1,20}$"
                               :minLength 1 :maxLength 20}}}}})

(defn call! [config operation args source]
  (let [attempted? (atom false)]
    (try
      (arguments! config operation args)
      (let [actor (policy/actor execution/*event*)]
        (when-not (and execution/*sensitive* actor
                       (= source (:chat-source execution/*event*))
                       (= :discord (:adapter source)))
          (ai/fail "Reactions require a trusted Discord request."))
        (policy/authorize! (:toolset config) actor)
        (execution/sensitive!)
        (let [adapter (get @adapters/adapters (:uuid source))
              channel-id (get-in source [:raw-event :channel-id])
              message-id (or (get args "message_id")
                             (get-in source [:raw-event :id]))
              emoji (discord/reaction-emoji (get args "emoji"))]
          (when-not adapter (ai/fail "Discord adapter is unavailable."))
          (reset! attempted? true)
          (discord/react adapter emoji channel-id message-id)
          {:structuredContent {:untrusted true :reacted true
                               :emoji emoji :message_id message-id}}))
      (catch Exception e
        (let [unknown? (and @attempted?
                            (not= false
                                  (:discord/reaction-unknown? (ex-data e))))]
          {:isError true :unknownOutcome (boolean unknown?)
           :structuredContent
           {:error (if unknown?
                     (str "Reaction outcome could not be confirmed; "
                          "do not repeat it.")
                     (str "Reaction was not added. Check emoji syntax, "
                          "channel access and toolset policy."))}})))))
