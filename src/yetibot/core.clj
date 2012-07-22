(ns yetibot.core
  (:require [http.async.client :as c]
            [yetibot.campfire :as cf]
            [clojure.contrib.string :as s]
            [clojure.tools.namespace :as ns])
  (:use [clojure.tools.logging]
        [clj-logging-config.log4j]))

; Deserializes json string and extracts fields
(defmacro parse-event [event-json & body]
  `(let [~'user-id (:user_id ~event-json)
         ~'body (:body ~event-json)
         ~'event-type (:type ~event-json)]
     ~@body))

(defn handle-command [cmd args]
  "receives parsed `cmd` and `args` for commands to hook into"
  (println (str "nothing handled command " cmd " with args " args))
  ; default to looking up a random result from google image search instead of
  ; complaining about not knowing stuff.
  (cf/send-message ((eval 'yetibot.commands.image-search/image-cmd)
                                   (str cmd " " (s/join " " args)))))

  ;;; (cf/send-message (str "I don't know much about " cmd
  ;;;                       ". Use !help.")))

(defn handle-text-message [json]
  "parse a `TextMessage` campfire event into a command and its args"
  (println "handle-text-message")
  (parse-event json
               (let [parsed (s/split #"\s" 3 (s/trim body))]
                 (if (>= (count parsed) 1)
                   (cond
                     (re-find #"^yeti" (first parsed)) ; you talking to me?
                       (handle-command (second parsed) (nth parsed 2 ""))
                     (re-find #"^\!" (first parsed)) ; short syntax
                       (handle-command (s/join "" (rest (first parsed)))
                                       (s/join " " (rest parsed))))
                   (println (str "WARN: couldn't split the message into 2 parts: " body))
                   ))))

(defn handle-campfire-event [json]
  (parse-event json
               (condp = event-type ; Handle the various types of messages
                 "TextMessage" (handle-text-message json)
                 "PasteMessage" (handle-text-message json)
                 (println "Unhandled event type: " event-type))))

(defn unknown-command [cmd subcommand]
  (cf/send-message (str "I don't know how to do " subcommand " for " cmd))
  (println (str "unkown command" cmd)))

(defn find-namespaces [pattern]
  (let [all-ns (ns/find-namespaces-on-classpath)]
    (filter #(re-find pattern (str %)) all-ns)))

(defn yetibot-command-namespaces []
  (find-namespaces #"^yetibot\.commands"))

(defn yetibot-observer-namespaces []
  (find-namespaces #"^yetibot\.observer"))

(defn load-ns [arg]
  (try (require arg :reload)
    (catch Exception e
      (println "Warning: problem requiring" arg "hook:" (.getMessage e)))))

(defn load-commands []
  (doseq [command-namespace (yetibot-command-namespaces)]
    (load-ns command-namespace)))

(defn load-observers []
  (doseq [n (yetibot-observer-namespaces)]
    (load-ns n)))


(defn -main [& args]
  (trace "starting main")
  (load-observers)
  (load-commands)
  (cf/start handle-campfire-event))
