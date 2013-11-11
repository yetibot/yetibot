(ns user
  (:require
    [yetibot.config :as config]
    [yetibot.db :as db]))

(do
  (config/start)
  (db/start))
