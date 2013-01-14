(ns yetibot.webapp.server
  (:require
    [compojure.route :as route]
    [compojure.handler :as handler]
    [compojure.response :as response]
    [compojure.core :refer :all]))

(defroutes home-route
  (GET "/" [] "YetiBot says hi."))

(def app
  (-> (handler/site home-route)))
