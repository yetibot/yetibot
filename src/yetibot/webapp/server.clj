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
  (prn "cmd was" command)
  (direct-cmd command nil))

(defroutes app-routes
  (GET "/" [] (views/layout))
  (GET "/api" [command] (api command))
  (route/resources "/"))

(def app (handler/site app-routes))
