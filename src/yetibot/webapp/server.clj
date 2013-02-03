(ns yetibot.webapp.server
  (:require
    [yetibot.webapp.views.common :as views]
    [compojure.route :as route]
    [compojure.handler :as handler]
    [compojure.response :as response]
    [hiccup.core :refer :all]
    [compojure.core :refer :all]))

(defroutes app-routes
  (GET "/" [] (views/layout))
  (route/resources "/"))

(def app (handler/site app-routes))
