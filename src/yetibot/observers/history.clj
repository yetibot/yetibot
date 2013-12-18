(ns yetibot.observers.history
  (:require
    [yetibot.core.models.history :as h]
    [yetibot.core.hooks :refer [obs-hook]]))

(obs-hook #{:message}
          (fn [event-info]
            (h/add {:user-id (-> event-info :user :id str)
                    :body (:body event-info)})))
