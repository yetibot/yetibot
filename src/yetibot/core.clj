(ns yetibot.core
  (:require
    [yetibot.config :as config]
    [yetibot.db :as db]
    [yetibot.logging]
    [yetibot.loader :refer [load-commands-and-observers]]
    [yetibot.handler :refer [handle-unparsed-expr]]
    [yetibot.logo :refer [logo]]
    [yetibot.version :refer [version]]
    [yetibot.adapters.campfire :as cf]
    [yetibot.adapters.irc :as irc]))

(defn welcome-message []
  (println (str "Welcome to YetiBot " version))
  (println logo))

(defn -main [& args]
  (welcome-message)
  (config/start)
  (db/start)
  (cf/start)
  (irc/start)
  (load-commands-and-observers))
