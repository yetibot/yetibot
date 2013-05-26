(ns yetibot.webapp.server
  (:require
    [yetibot.webapp.views.common :as views]
    [compojure.route :as route]
    [compojure.handler :as handler]
    [compojure.response :as response]
    [hiccup.core :refer :all]
    [yetibot.core :refer [direct-cmd]]
    [yetibot.campfire :refer [chat-data-structure self]]
    [compojure.core :refer :all]))

(defn api [{:keys [command token]}]
  (if (empty? token)
    "Please provide a Campfire access token"
    (if-let [user (:user (yetibot.campfire/self token))]
      (-> (direct-cmd command user) yetibot.campfire/chat-data-structure)
      "Invalid user access token")))

(defroutes app-routes
  (GET "/" [] (views/layout))
  (GET "/api" [& params] (api params))
  (POST "/api" [& params] (api params))
  (route/resources "/"))

(def app (handler/site app-routes))
