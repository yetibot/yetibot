(ns yetibot.webapp.routes.api
  (:require
    [yetibot.webapp.views.common :as views]
    [compojure.route :as route]
    [compojure.handler :as handler]
    [compojure.response :as response]
    [yetibot.core.handler :refer [handle-unparsed-expr]]
    [yetibot.core.chat :refer [chat-data-structure]]
    [yetibot.core.adapters.campfire :refer [self]]
    [compojure.core :refer :all]))

(defn api [{:keys [command token]}]
  (if (empty? token)
    "Please provide a Campfire access token"
    (if-let [user (:user (self token))]
      (let [res (handle-unparsed-expr command user)]
        (chat-data-structure res)
        res)
      "Invalid user access token")))

(defroutes api-routes
  (GET "/api" [& params] (api params))
  (POST "/api" [& params] (api params)))
