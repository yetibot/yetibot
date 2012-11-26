(ns yetibot.commands.http-status
  (:use [yetibot.hooks :only [cmd-hook]]))

(defn status-code
  "http <code>                 # look up http status code"
  [{code :match}]
  (format "http://httpcats.herokuapp.com/%s.jpg" code))

(cmd-hook #"http"
          #"^\w+$" status-code)
