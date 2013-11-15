(ns yetibot.commands.config
  (:require
    [yetibot.hooks :refer [cmd-hook]]
    [clojure.pprint :refer [pprint]]
    [yetibot.config :as config]))

(defn lookup-config
  "config <path> # lookup config by path"
  [{:keys [match]}]
  (prn "match" match)
  (let [frags (map (comp keyword first) (re-seq #"([\-\w]+)+" match))
        frags (if (= :yetibot (first frags)) frags (into [:yetibot] frags))
        conf (apply config/get-config frags)]
    (if conf
      (with-out-str (pprint conf))
      (str "Couldn't find config at " match))))

(cmd-hook #"config"
          #"\w+[\s\w]*" lookup-config)
