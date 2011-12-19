(ns yetibot.core
  (:require [http.async.client :as c]
            [yetibot.campfire :as cf]
            [clojure.contrib.string :as s]
            [clojure.tools.namespace :as ns]))


; Deserializes json string and extracts fields
(defmacro parse-event [event-json & body]
  `(let [~'user-id (:user_id ~event-json)
         ~'body (:body ~event-json)
         ~'event-type (:type ~event-json)]
     ~@body))

(defn handle-command [cmd args]
  "receives parsed `cmd` and `args` for commands to hook into"
  (println (str "nothing handled command " cmd " with args " args)))

(defn handle-text-message [json]
  "parse a `TextMessage` campfire event into a command and its args"
  (println "handle-text-message")
  (parse-event json
               (let [parsed (s/split #"\s" 3 (s/trim body))]
                 (when (re-find #"^yeti" (first parsed)) ; you talking to me?
                   (handle-command (second parsed) (last parsed ))))))

(defn handle-campfire-event [json]
  (parse-event json
               (condp = event-type ; Handle the various types of messages
                 "TextMessage" (handle-text-message json)
                 (println "Unhandled event type: " event-type)))) 

(defn unknown-command [cmd subcommand]
  (cf/send-message (str "I don't know how to do " subcommand " for " cmd))
  (println (str "unkown command" cmd)))

(defn yetibot-command-namespaces []
  (let [all-ns (ns/find-namespaces-on-classpath)]
    (filter #(re-find #"^yetibot\.commands" (str %)) all-ns)))

(defn load-command [arg]
  (try (require arg :reload)
       (catch Exception e
         (println "Warning: problem requiring" arg "hook:" (.getMessage e)))))

(defn load-commands []
  (doseq [command-namespace (yetibot-command-namespaces)]
    (load-command command-namespace)))


(defn -main [& args]
  (load-commands)
  (cf/start handle-campfire-event))
