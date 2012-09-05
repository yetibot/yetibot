(ns yetibot.core
  (:require [http.async.client :as c]
            [yetibot.campfire :as cf]
            [yetibot.models.users :as users]
            [clojure.contrib.string :as s]
            [clojure.stacktrace :as st]
            [clojure.tools.namespace :as ns])
  (:use [clojure.tools.logging]
        [clj-logging-config.log4j]))

; Deserializes json string and extracts fields
(defmacro parse-event [event-json & body]
  `(let [~'user-id (:user_id ~event-json)
         ~'body (:body ~event-json)
         ~'event-type (:type ~event-json)]
     ~@body))

(defn handle-command
  ([cmd args] (handle-command cmd args nil))
  ([cmd args user]
   "receives parsed `cmd` and `args` for commands to hook into"
   (println (str "nothing handled command " cmd " with args " args))
   ; default to looking up a random result from google image search instead of
   ; complaining about not knowing stuff.
   ; --
   ; WARNING: if there is no "image" command this will produce an infinite loop.
   ; Might want to keep a recursion count to prevent that.
   (handle-command "image" (str cmd " " args) user)))

(defn chat-handle-command [& args]
  (cf/chat-data-structure (apply handle-command args)))

; TODO: look at the resulting data structure of single-res. If it's a sequence,
; pipe a calls for each item in the sequence.
(defn handle-piped-command
  "Parse commands out of piped delimiters and pipe the results of one to the next"
  [body user]
  (let [cmds (map s/trim (s/split #"\|" (s/replace-re #"\!" "" body)))]
    (prn "handle piped cmd " cmds)
    (let [res (reduce (fn [acc cmd]
                        (let [acc-cmd (str cmd " " acc)
                              parsed (s/split #"\s" 2 acc-cmd)
                              single-res (handle-command (first parsed) (second parsed) user)]
                          (println "result of " parsed " was " single-res)
                          single-res))
                      ""
                      cmds)]
      (cf/chat-data-structure res)
      (println "reduced the answer down to" res))))


(defn handle-text-message [json]
  "parse a `TextMessage` campfire event into a command and its args"
  (println "handle-text-message")
  (parse-event json
               (let [parsed (s/split #"\s" 3 (s/trim body))
                     user (users/get-user (:user_id json))]
                 (if (>= (count parsed) 1)
                   (cond
                     ; you talking to me?
                     (re-find #"^yeti" (first parsed))
                       (chat-handle-command (second parsed) (nth parsed 2 "") user)
                     ; it starts with a ! and contains pipes
                     (and (re-find #"^\!" (first parsed))
                          (re-find #"\|" body))
                       (handle-piped-command body user)
                     ; short syntax
                     (re-find #"^\!" (first parsed))
                       (chat-handle-command (s/join "" (rest (first parsed)))
                                       (s/join " " (rest parsed)) user))
                   (println (str "WARN: couldn't split the message into 2 parts: " body))))))

(defn handle-campfire-event [json]
  (parse-event json
               (condp = event-type ; Handle the various types of messages
                 "TextMessage" (handle-text-message json)
                 "PasteMessage" (handle-text-message json)
                 (println "Unhandled event type: " event-type))))

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
      (println "Warning: problem requiring" arg "hook:" (.getMessage e))
      (st/print-stack-trace (st/root-cause e) 3))))

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
  (future
    (users/reset-users-from-room
      (cf/get-room)))
  (cf/start handle-campfire-event))
