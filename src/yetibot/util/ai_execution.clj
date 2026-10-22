(ns yetibot.util.ai-execution
  "Host-owned adapter provenance and request-scoped private-output logging."
  (:require [clojure.string :as string]
            [robert.hooke :as hooke]
            [taoensso.timbre :as log]
            [yetibot.core.handler :as handler]
            [yetibot.core.parser :as parser]
            [yetibot.core.util.command :as command]))

(def ^:dynamic *event* nil)
(def ^:dynamic *direct-command* nil)
(def ^:dynamic *sensitive* nil)

(defn sensitive! []
  (when-not *sensitive*
    (throw (ex-info "Private AI output requires a trusted chat request."
                    {:ai/error true})))
  (reset! *sensitive* true))

(defn with-adapter-event
  [callback chat-source user event-type yetibot-user event-info]
  (let [sensitive (atom false)
        ;; Filter before existing middleware/appenders can inspect source. Keep
        ;; only the separately sanitized proposal state-transition audit events.
        privacy (fn [data]
                  (when (or (not @sensitive)
                            (= "yetibot.models.ai-proposals" (:?ns-str data)))
                    data))]
    (binding [*event* {:chat-source chat-source :user user
                      :event-type event-type :body (:body event-info)}
              *direct-command* nil
              *sensitive* sensitive
              log/*config* (update log/*config* :middleware
                                  #(into [privacy] %))]
      (callback chat-source user event-type yetibot-user event-info))))

(defn- direct-expression [chat-source user tree]
  (let [platform-body (case (:adapter chat-source)
                        :slack (get-in chat-source [:raw-event :text])
                        :discord (get-in chat-source [:raw-event :content])
                        nil)
        body (:body *event*)
        extract #(when (string? %) (second (command/extract-command %)))
        text (extract body)]
    (when (and (= :message (:event-type *event*))
               (= chat-source (:chat-source *event*)) (= user (:user *event*))
               text (= text (extract platform-body))
               (re-matches #"ai\s+(?:proposal|approve|cancel)\s+[A-Za-z0-9-]+(?:\s+[0-9]+)?\s*" text)
               (= tree (parser/parser text)))
      (string/join " " (string/split (string/trim text) #"\s+")))))

(defn with-direct-expression [callback chat-source user yetibot-user tree]
  (binding [*direct-command* (direct-expression chat-source user tree)]
    (callback chat-source user yetibot-user tree)))

(defn require-direct! [args]
  (when-not (and *direct-command*
                 (= *direct-command*
                    (str "ai " (string/join " "
                                (string/split (string/trim (:args args "")) #"\s+")))))
    (throw (ex-info "Send this proposal review/approval command directly in chat, without pipes, aliases, observers or computed commands."
                    {:ai/error true}))))

;; The HTTP /api route enters handle-unparsed-expr instead: EDN chat-source
;; metadata cannot establish adapter provenance. go/future convey these bindings.
(hooke/add-hook #'handler/handle-raw #'with-adapter-event)
(hooke/add-hook #'handler/handle-parsed-expr #'with-direct-expression)
