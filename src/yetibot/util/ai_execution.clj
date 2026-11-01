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
(def ^:dynamic *direct-input* nil)
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
        ;; only closed provider diagnostics and sanitized proposal audit events.
        privacy (fn [data]
                  (when (or (not @sensitive)
                            (contains? #{"yetibot.models.ai-proposals"
                                         "yetibot.api.ai-diagnostics"}
                                       (:?ns-str data)))
                    data))]
    (binding [*event* {:chat-source chat-source :user user
                      :event-type event-type :body (:body event-info)}
              *direct-command* nil
              *direct-input* nil
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
               (re-matches #"ai\s+(?:(?:proposal|approve|cancel)\s+[A-Za-z0-9-]+(?:\s+[0-9]+)?|default(?:\s+\S+){0,2})\s*" text)
               (= tree (parser/parser text)))
      (string/join " " (string/split (string/trim text) #"\s+")))))

(defn- direct-input [chat-source user tree]
  (let [platform-body (case (:adapter chat-source)
                        :slack (get-in chat-source [:raw-event :text])
                        :discord (get-in chat-source [:raw-event :content]) nil)
        extract #(when (string? %) (second (command/extract-command %)))
        text (extract (:body *event*))]
    (when (and (= :message (:event-type *event*))
               (= chat-source (:chat-source *event*)) (= user (:user *event*))
               text (= text (extract platform-body)) (= tree (parser/parser text))
               (vector? tree) (= :expr (first tree)) (= 2 (count tree))
               (= :cmd (first (second tree)))
               (not-any? #(and (vector? %) (= :sub-expr (first %))) (tree-seq coll? seq tree)))
      {:raw text :evaluated (parser/transformer (second tree))})))

(defn direct-request-key
  "Only the original standalone !ai prompt can start automatic writes.
   Event IDs, not generated text, give duplicate deliveries a stable identity."
  [{:keys [match]}]
  (let [canonical #(some-> % string/trim (string/replace-first #"^ai\s+" "ai "))
        {:keys [chat-source user]} *event*
        {:keys [adapter uuid raw-event]} chat-source
        id (case adapter :slack (:ts raw-event) :discord (:id raw-event) nil)
        channel (case adapter :slack (:channel raw-event) :discord (:channel-id raw-event) nil)]
    (when (and (string? match) (string? id) (seq id) (string? channel)
               *direct-input* (= (canonical (:evaluated *direct-input*)) (str "ai " (string/trim match))))
      (let [bytes (.digest (java.security.MessageDigest/getInstance "SHA-256")
                           (.getBytes (pr-str [adapter uuid (:id user) channel id]) "UTF-8"))]
        (apply str (map #(format "%02x" (bit-and 255 %)) bytes))))))

(defn direct-request-text [args]
  (when (direct-request-key args)
    (string/replace-first (string/trim (:raw *direct-input*)) #"^ai\s+" "")))

(defn with-direct-expression [callback chat-source user yetibot-user tree]
  (binding [*direct-command* (direct-expression chat-source user tree)
            *direct-input* (direct-input chat-source user tree)]
    (callback chat-source user yetibot-user tree)))

(defn require-direct! [args]
  (when-not (and *direct-command*
                 (= *direct-command*
                    (str "ai " (string/join " "
                                (string/split (string/trim (:args args "")) #"\s+")))))
    (throw (ex-info "Send this AI control command directly in chat, without pipes, aliases, observers or computed commands."
                    {:ai/error true}))))

;; The HTTP /api route enters handle-unparsed-expr instead: EDN chat-source
;; metadata cannot establish adapter provenance. go/future convey these bindings.
(hooke/add-hook #'handler/handle-raw #'with-adapter-event)
(hooke/add-hook #'handler/handle-parsed-expr #'with-direct-expression)
