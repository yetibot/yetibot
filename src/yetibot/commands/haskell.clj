(ns yetibot.commands.haskell
  (:require [yetibot.hooks :refer [cmd-hook]]
            [yetibot.util.http :refer [get-json encode]]))

(def endpoint "http://tryhaskell.org/haskell.json?method=eval&random=0.6923271066043526&expr=")

(defn haskell-cmd
  "hs <expression> # evaluate haskell expression"
  [{:keys [match]}]
  (let [json (get-json (str endpoint (encode match)))]
    ((juxt :type :result) json)))

(cmd-hook #"hs"
          #".*" haskell-cmd)
