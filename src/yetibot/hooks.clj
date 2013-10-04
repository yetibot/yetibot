(ns yetibot.hooks
  (:require
    [yetibot.handler]
    [clojure.string :as s]
    [yetibot.interpreter :refer [handle-cmd]]
    [yetibot.models.help :as help]
    [robert.hooke :as rh]
    [clojure.stacktrace :as st])
  (:use [clojure.contrib.cond :only (cond-let)]
        [yetibot.util :only (bot-id)]))

(def ^:private Pattern java.util.regex.Pattern)

(defn suppress
  "Wraps parameter in meta data to indicate that it should not be posted to campfire"
  [data-structure]
  (with-meta data-structure {:suppress true}))

(defn cmd-unhook [topic]
  (help/remove-docs topic)
  (rh/remove-hook #'handle-cmd topic))

(defmacro cmd-hook [prefix & exprs]
  ; let consumer pass [topic regex] as prefix arg when a (str regex) isn't enough
  (let [[topic prefix] (if (vector? prefix) prefix [(str prefix) prefix])
        callback (gensym "callback")
        cmd-with-args (gensym "cmd-with-args")
        cmd (gensym "cmd")
        args (gensym "args")
        user (gensym "user")
        opts (gensym "opts")
        match (gensym "match")
        chat-source (gensym "chat-source")
        extra (gensym "extra")]
    `(do
       (rh/add-hook
         #'handle-cmd
         ~topic ; use topic string as the hook-key to enable removing/re-adding
         ; (fn [~callback ~cmd ~args ~user ~opts])
         (fn [~callback ~cmd-with-args {~chat-source :chat-source
                                        ~user :user
                                        ~opts :opts
                                        :as ~extra}]
           (let [[~cmd & ~args] (s/split ~cmd-with-args #"\s+")
                 ~args (s/join " " ~args)]
             ; only match against the first word in ~args
             (if (re-find ~prefix (s/lower-case ~cmd))
               (do
                 (prn "found" ~prefix "on cmd" ~cmd "."
                      "args:" ~args
                      "opts:" ~opts
                      "extra" ~extra)
                 ; try matching the available sub-commands
                 (cond-let [~match]
                           ; rebuild the pairs in `exprs` as valid input for cond-let
                           ~@(map (fn [i#]
                                    (cond
                                      ; prefix to match
                                      (instance? Pattern i#) `(re-find ~i# ~args)
                                      ; placeholder / fallthrough - set match equal to
                                      ; :empty, which will trigger this match for
                                      ; cond-let while not explicitly matching
                                      ; anything.
                                      (= i# '_) `(or :empty)
                                      ; send result back to hooked fn
                                      :else `(~i# {:cmd ~cmd
                                                   :args ~args
                                                   :match ~match
                                                   :user ~user
                                                   :chat-source ~chat-source
                                                   :opts ~opts})))
                                  exprs)
                           ; default to help
                           true (yetibot.handler/handle-unparsed-expr (str "help" ~topic))))
               (~callback ~cmd-with-args ~extra)))))
       ; extract the meta from the commands and use it to build docs
       (help/add-docs ~topic
                      (map
                        (fn [i#]
                          (when (and (symbol? i#) (not= i# '_))
                            (:doc (meta (resolve i#)))))
                        '~exprs)))))

(defn obs-hook
  "Pass a collection of event-types you're interested in and an observer function
   that accepts a single arg. If an event occurs that matches the events in your
   event-types arg, your observer will be called with the event's json."
  [event-types observer]
  (rh/add-hook
    #'yetibot.handler/handle-raw
    (let [event-types (set event-types)]
      (fn [callback chat-source user event-type body]
        (when (contains? event-types event-type)
          (observer {:chat-source chat-source
                     :event-type event-type
                     :user user
                     :body body}))
        (callback chat-source user event-type body)))))


    ; (fn [callback json]
    ;   ; when event-type is in event-types, observe it
    ;   (when (and
    ;           (not= (str (:user_id json)) bot-id) ; don't observer yourself
    ;           (some #{(:type json)} event-types))
    ;     ; swallow any exceptions from observers
    ;     (try
    ;       (observer json)
    ;       (catch Exception e
    ;         (println (str "observer exception: " e))
    ;         (st/print-stack-trace (st/root-cause e) 3))))
    ;   (callback json))))
