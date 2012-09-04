(ns yetibot.commands.attack
  (:require [clojure.string :as s]
            [yetibot.models.users :as users])
  (:use [yetibot.util]))

(defn attack-cmd
  "attack <name> #attacks a person in the room"
  [name]
  (str "you attacked " name
       (let [dmg (+ 0 (rand-int 20))]
         (if (or (= 0 dmg) (not (some #{name} (users/get-user-names))))
           " but you missed"
           (str " for " dmg "damage")))))

(cmd-hook #"attack"
          #".+" (attack-cmd p))
