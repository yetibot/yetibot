(ns yetibot.commands.man
  (:require
    [clj-http.client :as client]
    [clojure.string :as string]
    [yetibot.core.hooks :refer [cmd-hook]]))

(def uri "https://www.mankier.com/api/v2/explain/")

(defn explain
  ([command]
   (client/get uri {:query-params {:q command}})))

(defn man-cmd
  {:doc "man <command> # explains a shell command"
   :yb/cat #{:util}}
  [{match :match}]
  (-> (explain match)
      :body
      (string/trim)
      (string/replace #"\n\n" "\n")))

(cmd-hook "man"
  #".+" man-cmd)

