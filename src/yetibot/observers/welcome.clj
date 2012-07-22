(ns yetibot.observers.welcome
  (:require [robert.hooke :as rh]
            [yetibot.campfire :as cf]
            [clojure.string :as s]
            [yetibot.core :as core])
  (:use [yetibot.util]))

; Map of user_id to responses
(def ids (s/split (str (System/getenv "WELCOME_IDS")) #","))
(def enter-responses
  (when ids
    (into {} (for [id ids] [(keyword id) (System/getenv (str "WELCOME_" id))]))))

(obs-hook
  ["EnterMessage"]
  (fn [json]
    (let [user_id (:user_id json)
          resp ((keyword (str user_id)) enter-responses)]
      (println (str user_id " entered"))
      (println (str "response should be " resp))

      (when resp
        (cf/play-sound resp)))))
