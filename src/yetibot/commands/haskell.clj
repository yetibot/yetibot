(ns yetibot.commands.haskell
  (:require
    [clj-http.client :as client]
    [yetibot.core.hooks :refer [cmd-hook]]))

(def endpoint "http://tryhaskell.org/eval")

(defn hs-eval [exp]
  (client/post
    endpoint
    {:throw-entire-message? true
     :accept "application/json, text/javascript, */*; q=0.01"
     :content-type "application/x-www-form-urlencoded"
     :as :json
     :form-params {:exp exp}}))

(defn haskell-cmd
  "hs <expression> # evaluate haskell expression"
  [{:keys [match]}]
  (let [json (hs-eval match)]
    (if-let [success (-> json :body :success)]
      ((juxt :type :value) success)
      "Error: is tryhaskell.org down?")))

(cmd-hook #"hs"
          #".*" haskell-cmd)
