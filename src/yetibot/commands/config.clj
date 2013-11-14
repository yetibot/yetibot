(ns yetibot.commands.config
  (:require
    [yetibot.hooks :refer [cmd-hook]]
    [yetibot.config :as config]))

(defn lookup-config
  "config <path> # lookup config by path"
  [{:keys [match]}]
  (let [frags (map (comp keyword first) (re-seq #"([\-\w]+)+" match))
        frags (if (= :yetibot (first frags)) frags (into [:yetibot] frags))]
    (apply config/get-config frags)))

(cmd-hook #"config"
          #"([\-\w]+)+" lookup-config)
