(ns yetibot.commands.users
  (:require [yetibot.models.users :as users]
            [clojure.string :as s]
            [yetibot.hooks :refer [cmd-hook]]))

(defn show-users
  "users # list all users presently in the room"
  [_] (map :name (vals @users/users)))

(defn rand-user
  "users random # get a random user"
  [_] (:name (users/get-rand-user)))

(cmd-hook #"users"
          #"random" rand-user
          #"^$" show-users)
