(ns yetibot.util
  (:require [http.async.client :as client]
            [clojure.contrib.string :as s]
            [yetibot.core :as core]
            [yetibot.campfire :as cf]
            [yetibot.models.help :as help]
            [robert.hooke :as rh]
            [clojure.stacktrace :as st]
            [clojure.data.json :as json])
  (:use [clojure.contrib.cond]))

(def bot-id (str (System/getenv "CAMPFIRE_BOT_ID")))

(def ^:private Pattern java.util.regex.Pattern)

(def env
  (let [e (into {} (System/getenv))]
    (zipmap (map keyword (keys e)) (vals e))))

; command hook
(defmacro cmd-hook [prefix & exprs]
  (let [[topic prefix] (if (vector? prefix) prefix [(str prefix) prefix])]
  `(do
     (rh/add-hook
       #'core/handle-command
       (fn [~'callback ~'cmd ~'args ~'user ~'opts]
         ; only match against the
         ; first word in args
         (if (re-find ~prefix (s/lower-case ~'cmd))
           (do
             (println (str "found " ~prefix " on cmd " ~'cmd ". args are:" ~'args))
             ; try matching the available sub-commands
             (cond-let [~'p]
                       ; rebuild the pairs in `exprs` as valid input for cond-let
                       ~@(map (fn [i#]
                                (cond
                                  ; prefix to match
                                  (instance? Pattern i#) `(re-find ~i# ~'args)
                                  ; placeholder - set p equal to the args (which
                                  ; might by nil, so or it with :empty)
                                  (= i# '_) `(or ~'args :empty)
                                  ; send result back to handle-command
                                  :else `~i#))
                              exprs)
                       ; default to help
                       true (core/handle-command "help" (str ~prefix) ~'user ~'opts)))
           (~'callback ~'cmd ~'args ~'user ~'opts))))
     ; extract the meta from the commands and use it to build docs
     (help/add-docs ~topic
                    (map
                      (fn [i#]
                        (if (list? i#)
                          (:doc (meta (resolve (first i#))))))
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
