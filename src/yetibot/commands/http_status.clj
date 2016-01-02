(ns yetibot.commands.http-status
  (:require
    [yetibot.core.hooks :refer [cmd-hook]]))

(defn status-code
  "http <code> # look up http status code"
  {:yb/cat #{:fun :img}}
  [{code :match}]
  (format "http://httpcats.herokuapp.com/%s.jpg" code))

(cmd-hook #"http"
          #"^\w+$" status-code)
