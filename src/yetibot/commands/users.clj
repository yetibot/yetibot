(ns yetibot.commands.users
  (:require [yetibot.models.users :as users]
            [clojure.string :as s])
  (:use [yetibot.util :only (cmd-hook)]))

(defn show-users
  "users # list all users presently in the room"
  []
  (map :name (vals @users/users)))

(defn rand-user
  "users random # get a random user"
  []
  (:name (users/get-rand-user)))

(cmd-hook #"users"
          #"random" (rand-user)
          #"^$" (show-users))
