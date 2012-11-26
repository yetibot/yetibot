(ns yetibot.commands.attack
  (:require [clojure.string :as s]
            [yetibot.models.users :as users])
  (:use [yetibot.hooks :only [cmd-hook]]))

(def config {:min-crit 6 :max-crit 10
             :min-dmg 0 :max-dmg 20})

(defn- crit []
  (let [c (rand-int (:max-crit config))]
    (if (> (:min-crit config) c) 0 c)))

(defn- dmg []
  (+ (:min-dmg config) (rand-int (:max-dmg config))))

(defn attack-cmd
  "attack <name> # attacks a person in the room"
  [{:keys [user args]}]
  (let [user-to-attack (users/get-user-by-name args)
        d (dmg)
        c (crit)
        total (+ c d)]
    (str (:name user) " attacked " args
         (if (or (= 0 d) (not user-to-attack))
           " but you missed"
           (str " for " d " damage"
                (when (> c 0)
                  (str " + " c " crit (" total " total)!")))))))

(cmd-hook #"attack"
          #"^\w+" attack-cmd)
