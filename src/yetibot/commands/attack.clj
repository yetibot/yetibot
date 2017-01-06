(ns yetibot.commands.attack
  (:require
    [clojure.string :as s]
    [yetibot.core.models.users :as users]
    [yetibot.core.hooks :refer [cmd-hook]]))

(def config {:min-crit 6 :max-crit 10
             :min-dmg 0 :max-dmg 20})

(defn- crit []
  (let [c (rand-int (:max-crit config))]
    (if (> (:min-crit config) c) 0 c)))

(defn- dmg []
  (+ (:min-dmg config) (rand-int (:max-dmg config))))

(defn attack-cmd
  {:doc "attack <name> # attacks a person in the room"
   :yb/cat #{:fun}}
  [{:keys [user args chat-source]}]
  (let [user-to-attack (users/find-user-like chat-source args)
        d (dmg)
        c (crit)
        total (+ c d)]
    (str (:name user) " attacked " (:name user-to-attack)
         (if (or (zero? d) (not user-to-attack))
           " but missed"
           (str " for " d " damage"
                (when (pos? c)
                  (str " + " c " crit (" total " total)!")))))))

(cmd-hook #"attack"
          #"^\w+" attack-cmd)
