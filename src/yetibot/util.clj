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

; command hook
(defmacro cmd-hook [prefix & exprs]
  `(do
     (rh/add-hook
       #'core/handle-command
       (fn [~'callback ~'cmd ~'args ~'user]
         (if (re-find ~prefix (s/lower-case ~'cmd))
           (do
             (println (str "found " ~prefix ". args are:" ~'args))
             ; try matching the available sub-commands
             (cond-let [~'p]
                       ; rebuild the pairs in `exprs` as valid input for cond-let
                       ~@(map (fn [i#]
                                (if (instance? java.util.regex.Pattern i#)
                                  `(re-find ~i# ~'args) ; prefix to match
                                  `~i#)) ; send result back to handle-command ; chat results of handler
                              exprs)
                       ; default to help
                       true (core/handle-command "help" (str ~prefix))))
           (~'callback ~'cmd ~'args ~'user))))
     ; extract the meta from the commands and use it to build docs
     (help/add-docs ~prefix
                    (map
                      (fn [i#]
                        (if (list? i#)
                          (:doc (meta (resolve (first i#))))))
                      '~exprs))))

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
