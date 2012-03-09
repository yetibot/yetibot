(ns yetibot.commands.http-status
  (:use [yetibot.util :only (cmd-hook)]))

(defn status-code
  "http <code>                 # look up http status code"
  [code]
  (str "http://httpcats.herokuapp.com/" code ".jpg"))


(cmd-hook #"http"
          #"^\w+$" (status-code p))

