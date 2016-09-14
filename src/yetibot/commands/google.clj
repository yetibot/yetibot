(ns yetibot.commands.google
  (:require
    [taoensso.timbre :refer [info warn error]]
    [clojure.string :refer [join]]
    [yetibot.api.google :as api]
    [yetibot.core.hooks :refer [cmd-hook suppress]]))

(defn search
  "google search <query>"
  [{[_ query] :match}]
  (api/vanilla-search query))

(if (api/configured?)
  (cmd-hook #"google"
            #"^search\s+(.+)" search)
  (info "Google is not configured."))

