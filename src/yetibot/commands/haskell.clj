(ns yetibot.commands.haskell
  (:require [yetibot.hooks :refer [cmd-hook]]
            [yetibot.util.http :refer [get-json encode]]))

(def endpoint "http://tryhaskell.org/haskell.json?method=eval&random=0.6923271066043526&expr=")

(defn haskell-cmd
  "haskell <expression> # eval haskell expression"
  [{:keys [args]}]
  (let [json (get-json (str endpoint (encode args)))]
    ((juxt :type :result) json)))

(cmd-hook ["haskell" #"^haskell|hs$"]
          _ haskell-cmd)
