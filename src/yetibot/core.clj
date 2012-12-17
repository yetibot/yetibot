(ns yetibot.core
  (:require [http.async.client :as c]
            [yetibot.campfire :as cf]
            [yetibot.models.users :as users]
            [clojure.string :as s]
            [clojure.string :as cs]
            [clojure.stacktrace :as st]
            [clojure.tools.namespace.find :as ns]
            [clojure.java.classpath :as cp])
  (:use [clojure.tools.logging]
        [clj-logging-config.log4j]
        [clojure.tools.namespace.repl :only (refresh)]))

; Deserializes json string and extracts fields
(defmacro parse-event [event-json & body]
  `(let [~'user-id (:user_id ~event-json)
         ~'body (:body ~event-json)
         ~'event-type (:type ~event-json)]
     ~@body))

(defn handle-command
  [cmd args user opts]
  "Receives parsed `cmd` prefix and `args` for commands to hook into. Typicall
  `args` will be a string, but it might be a seq when handle-command is called
  from handle-piped-command."
  (println (str "nothing handled command " cmd " with args " args))
  ; default to looking up a random result from google image search instead of
  ; complaining about not knowing stuff.
  (if (find-ns 'yetibot.commands.image-search)
    (handle-command "image" (str cmd " " args) user nil)
    (format "I don't know how to handle %s %s." cmd args)))

(defn chat-handle-command [& args]
  (cf/chat-data-structure (apply handle-command args)))

(defn parse-cmd-with-args
  [cmd-with-args]
  (let [[cmd args] (s/split cmd-with-args #"\s" 2)
        args (or args "")]
    [cmd args]))

(defn parse-and-handle-command
  [cmd-with-args & rest]
  (let [[cmd args] (parse-cmd-with-args cmd-with-args)
        rest-args (into rest (take (- 2 (count rest)) (repeatedly (constantly nil))))]
  (apply handle-command (list* cmd (str args) rest-args))))

(defn cmd-reader [& args]
  (parse-and-handle-command (cs/join " " args)))

(defn to-coll-if-contains-newlines
  "This might be a bit hack-ish, but it lets us get out of explicitly supporting streams
  in every command that we want it."
  [s]
  (if (and (string? s) (re-find #"\n" s))
    (s/split s #"\n")
    s))

(defn handle-piped-command
  "Parse commands out of piped delimiters and pipe the results of one to the next"
  [body user]
  ; TODO: don't scrub body of all !s since we now have a ! command. Instead,
  ; conditionally trim the first ! off only if it's not followed by a space.
  (let [cmds (map s/trim (s/split (s/replace body #"\!" "") #" \| "))]
    (prn "handle piped cmd " cmds)
    ; cmd-with-args is the unparsed string
    (let [res (reduce (fn [acc cmd-with-args]
                        (let [acc-cmd (str cmd-with-args " " acc)
                              ; split out the cmd and args
                              [cmd args] (parse-cmd-with-args cmd-with-args)
                              possible-coll-acc (to-coll-if-contains-newlines acc)]
                          ; TODO
                          ; acc could be a collection instead of a string. In that case we
                          ; could:
                          ; - take the first item and run the command with that
                          ;   (head)
                          ; - run handle-command for every item in the seq with the
                          ;   assumption that this is the last command in the pipe
                          ;   (xargs)
                          (if-let [acc-coll (or (and (coll? acc) acc)
                                               (and (coll? possible-coll-acc) possible-coll-acc))]
                            ; acc was a collection, so pass the acc as opts instead
                            ; of just concatting it to args.
                            ; This allows the collections commands to deal with them.
                            (handle-command cmd args user acc-coll)
                            ; otherwise concat args and acc as the new args. args are
                            ; likely empty anyway. (e.g. !urban random | !image - the
                            ; args to !image are empty, and acc would be the result
                            ; of !urban random). Send args as opts in this case so
                            ; that regular cmd output can be parsed as opts.
                            (let [opt-args-frags [args acc]
                                  built-args
                                    (cs/join " "
                                     (filter (complement cs/blank?) opt-args-frags))]
                              (handle-command cmd built-args user acc)))))
                      ""
                      cmds)]
      (cf/chat-data-structure res)
      (println "reduced the answer down to" res))))


(defn handle-text-message [json]
  "parse a `TextMessage` campfire event into a command and its args"
  (println "handle-text-message")
  (try
    (parse-event json
                 (let [parsed (s/split (s/trim body) #"\s" 3)
                       user (users/get-user (:user_id json))]
                   (if (>= (count parsed) 1)
                     (cond
                       ; you talking to me?
                       (re-find #"^yeti" (first parsed))
                       (chat-handle-command (second parsed) (nth parsed 2 "") user nil)
                       ; it starts with a ! and contains pipes
                       (and (re-find #"^\!" (first parsed))
                            (re-find #" \| " body))
                       (handle-piped-command body user)
                       ; short syntax
                       (re-find #"^\!" (first parsed))
                       (chat-handle-command (s/join "" (rest (first parsed)))
                                            (s/join " " (rest parsed)) user nil))
                     (println (str "WARN: couldn't split the message into 2 parts: " body)))))
    (catch Exception ex
      (println "Exception inside `handle-text-message`" ex)
      (st/print-stack-trace (st/root-cause ex) 24)
      (cf/send-paste (str "An exception occurred: " ex)))))

(defn handle-campfire-event [json]
  (parse-event json
               (condp = event-type ; Handle the various types of messages
                 "TextMessage" (handle-text-message json)
                 "PasteMessage" (handle-text-message json)
                 (println "Unhandled event type: " event-type))))

(defn find-namespaces [pattern]
  (let [all-ns (ns/find-namespaces (cp/classpath))]
    (filter #(re-matches pattern (str %)) all-ns)))

(def yetibot-command-namespaces
  [#"^yetibot\.commands.*" #"^plugins.*commands.*"])

(def yetibot-observer-namespaces
  [#"^yetibot\.observers.*" #"^plugins.*observers.*"])

(def yetibot-all-namespaces
  (merge
    (map last [yetibot-command-namespaces
                yetibot-observer-namespaces])
    ; with a negative lookahead assertion
    #"^yetibot\.(.(?!(core)))*"))

(defn load-ns [arg]
  (println "Loading namespace" arg)
  (try (require arg :reload)
    (catch Exception e
      (println "WARNING: problem requiring" arg "hook:" (.getMessage e))
      (st/print-stack-trace (st/root-cause e) 15))))

(defn find-and-load-ns [ns-patterns]
  (let [nss (flatten (map find-namespaces ns-patterns))]
    (dorun (map load-ns nss))))

(defn load-commands []
  (find-and-load-ns yetibot-command-namespaces))

(defn load-observers []
  (find-and-load-ns yetibot-observer-namespaces))

(defn load-commands-and-observers []
  (load-observers)
  (load-commands))

(defn reload-all-yetibot
  "Reloads all of YetiBot's namespaces, including plugins. Loading yetibot.core is
  temporarily disabled until we can figure out to unhook and rehook
  handle-campfire-event and handle-command"
  []
  ;;; (refresh))
  ;; only load commands and observers
  ;; until https://github.com/devth/yetibot/issues/75 is fixed
  (load-commands-and-observers))
  ;;; (find-and-load-ns yetibot-all-namespaces))

(defn -main [& args]
  (trace "starting main")
  (load-commands-and-observers)
  (future
    (users/reset-users-from-room
      (cf/get-room)))
  (cf/start #'handle-campfire-event))
