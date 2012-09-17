(ns yetibot.commands.attack
  (:require [clojure.string :as s]
            [yetibot.models.users :as users])
  (:use [yetibot.util]))

(def settings {:min-crit 6 :max-crit 10
               :min-dmg 0 :max-dmg 20})

(defn- crit []
  (let [c (rand-int (:max-crit settings))]
    (if (> (:min-crit settings) c) 0 c)))

(defn- dmg []
  (+ (:min-dmg settings) (rand-int (:max-dmg settings))))

(defn attack-cmd
  "attack <name> # attacks a person in the room"
  [user name]
  (let [user-to-attack (users/get-user-by-name name)
        d (dmg)
        c (crit)
        total (+ c d)]
    (prn "crit" c)
    (str (:name user) " attacked " name
         (if (or (= 0 d) (not user-to-attack))
           " but you missed"
           (str " for " d " damage"
                (when (> c 0)
                  (str " + " c " crit (" total " total)!")))))))

(cmd-hook #"attack"
          _ (attack-cmd user args))
