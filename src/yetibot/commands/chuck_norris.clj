(ns yetibot.commands.chuck-norris
  (:require
    [clj-http.client :as client]
    [clojure.string :as string]
    [yetibot.core.hooks :refer [cmd-hook cmd-unhook]]
    [yetibot.core.util.http :refer [html-decode]]))

(def uri "http://api.icndb.com/jokes/random")

(def qs-params {:limitTo "[nerdy]"})

(defn get-json
  ([] (get-json {}))
  ([extra-qs-params]
   (client/get uri {:as :json
                    :query-params (merge qs-params extra-qs-params)})))

(defn chuck-joke
  {:doc "chuck # tell a random Chuck Norris joke"
   :yb/cat #{:fun}}
  [_]
  (-> (get-json) :body :value :joke html-decode))

(defn chuck-named-joke
  "chuck <name> # tell a random joke using <name> instead of Chuck Norris"
  [{match :match}]
  (let [[first-name last-name] (string/split match #"\s+" 2)
        joke (-> (get-json {:firstName first-name :lastName last-name})
                 :body :value :joke html-decode)]
    (if (empty? last-name)
      ;; when last name is empty there will be weird spaces in the response;
      ;; remove them
      (string/replace joke (str first-name " ") first-name)
      joke)))

(cmd-hook ["chuck" #"^chuck(norris)*$"]
  #".+" chuck-named-joke
  _ chuck-joke)
