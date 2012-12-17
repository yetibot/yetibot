(ns yetibot.hooks
  (:require [clojure.string :as s]
            [yetibot.core :as core]
            [yetibot.models.help :as help]
            [robert.hooke :as rh]
            [clojure.stacktrace :as st])
  (:use [clojure.contrib.cond :only (cond-let)]
        [yetibot.util :only (bot-id)]))

(def ^:private Pattern java.util.regex.Pattern)

; command hook
(defmacro cmd-hook [prefix & exprs]
  ; let user pass [topic regex] as prefix arg when a (str regex) isn't enough
  (let [[topic prefix] (if (vector? prefix) prefix [(str prefix) prefix])
        callback (gensym "callback")
        cmd (gensym "cmd")
        args (gensym "args")
        user (gensym "user")
        opts (gensym "opts")
        match (gensym "match")]
    `(do
       (rh/add-hook
         #'core/handle-command
         (fn [~callback ~cmd ~args ~user ~opts]
           ; only match against the
           ; first word in ~args
           (if (re-find ~prefix (s/lower-case ~cmd))
             (do
               (println (str "found " ~prefix " on cmd " ~cmd ". args are:" ~args))
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
                                    ; send result back to handle-command
                                    :else `(~i# {:cmd ~cmd
                                                 :args ~args
                                                 :match ~match
                                                 :user ~user
                                                 :opts ~opts})))
                                exprs)
                         ; default to help
                         true (core/handle-command "help" ~topic ~user ~opts)))
             (~callback ~cmd ~args ~user ~opts))))
       ; extract the meta from the commands and use it to build docs
       (help/add-docs ~topic
                      (map
                        (fn [i#]
                          (when (and (symbol? i#) (not= i# '_))
                            (:doc (meta (resolve i#)))))
                        '~exprs)))))


; observer hook
(defn obs-hook
  "Pass a collection of event-types you're interested in and an observer function
  that accepts a single arg. If an event occurs that matches the events in your
  event-types arg, your observer will be called with the event's json."
  [event-types observer]
  (rh/add-hook
    #'core/handle-campfire-event
    (fn [callback json]
      ; when event-type is in event-types, observe it
      (when (and
              (not= (str (:user_id json)) bot-id) ; don't observer yourself
              (some #{(:type json)} event-types))
        ; swallow any exceptions from observers
        (try
          (observer json)
          (catch Exception e
            (println (str "observer exception: " e))
            (st/print-stack-trace (st/root-cause e) 3))))
      (callback json))))
