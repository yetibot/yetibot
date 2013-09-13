(ns yetibot.observers.welcome
  (:require [robert.hooke :as rh]
            [yetibot.adapters.campfire :as cf]
            [clojure.string :as s]
            [yetibot.hooks :refer [obs-hook]]))

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
      (when resp (cf/play-sound resp)))))
