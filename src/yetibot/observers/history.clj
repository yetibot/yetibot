(ns yetibot.observers.history
  (:require [yetibot.models.history :as h]
            [yetibot.hooks :refer [obs-hook]]))

(obs-hook #{:message}
          (fn [event-info]
            (h/add {:user-id (-> event-info :user :id str)
                    :body (:body event-info)})))
