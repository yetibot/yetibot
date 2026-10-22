(ns yetibot.test.support.ai-execution
  (:require [yetibot.core.handler :as handler]
            [yetibot.core.models.channel :as channel]
            [yetibot.core.parser :as parser]
            [yetibot.core.util.command :as command]
            [yetibot.util.ai-execution :as execution]))

(def request {:user {:id "U1" :username "alice"}
              :chat-source {:adapter :slack :uuid "workspace" :room "private"
                            :raw-event {:user "U1" :channel "C1"
                                        :ts "now" :thread_ts "root"}}})

(defn in-event [request text f]
  (let [body (str command/config-prefix text)
        field (if (= :slack (get-in request [:chat-source :adapter])) :text :content)
        chat-source (assoc-in (:chat-source request) [:raw-event field] body)]
    (execution/with-adapter-event
      (fn [cs user _ _ _] (f (assoc request :chat-source cs :user user)))
      chat-source (:user request) :message {:id "BOT"} {:body body})))

(defn run-command
  "Use the production adapter boundary and real parser/interpreter/command hooks.
   Only database-backed channel settings are stubbed."
  [request text]
  (in-event request text
    (fn [{:keys [chat-source user]}]
      (with-redefs [channel/settings-for-chat-source (constantly {})]
        (handler/handle-parsed-expr chat-source user {:id "BOT"}
                                    (parser/parser text))))))
