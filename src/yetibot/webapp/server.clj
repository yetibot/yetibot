(ns yetibot.webapp.server
  (:require
    [yetibot.webapp.views.common :as views]
    [compojure.route :as route]
    [compojure.handler :as handler]
    [compojure.response :as response]
    [hiccup.core :refer :all]
    [yetibot.core :refer [direct-cmd]]
    [compojure.core :refer :all]))

(defn api [command]
  (let [res (direct-cmd command nil)]
    (yetibot.campfire/chat-data-structure res)
    res))

(defroutes app-routes
  (GET "/" [] (views/layout))
  (GET "/api" [command] (api command))
  (POST "/api" [command] (api command))
  (route/resources "/"))

(def app (handler/site app-routes))
