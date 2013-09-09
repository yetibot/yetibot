(ns yetibot.core
  (:require
    [yetibot.loader :refer [load-commands-and-observers]]
    [yetibot.handler :refer [handle-campfire-event handle-unparsed-expr]]
    [yetibot.logo :refer [logo]]
    [yetibot.version :refer [version]]
    [yetibot.campfire :as cf]
    [yetibot.adapters.irc :as irc]
    [yetibot.models.users :as users]
    [yetibot.db]))

(defn welcome-message []
  (println (str "Welcome to YetiBot " version))
  (println logo))

(defn -main [& args]
  (welcome-message)
  (cf/start #'handle-campfire-event)
  (irc/start)
  (load-commands-and-observers)
  (future
    (users/reset-users-from-room (cf/get-room))))
