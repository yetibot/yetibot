(ns yetibot.commands.!
  (:require
    [yetibot.core.models.history :as h]
    [yetibot.core.handler :refer [handle-unparsed-expr]]
    [clojure.string :as s]
    [yetibot.core.hooks :refer [cmd-hook]]))

(defn- valid-cmd? [json]
  (let [body (:body json)]
    (when (re-find #"^\![^\!]" body) json)))

(defn- clean-cmd [json] (s/replace (:body json) #"\!" ""))

(defn !-cmd
  "! # execute your last command"
  [{:keys [user] :as cmd-info}]
  (let [hist-for-user (reverse (h/items-for-user cmd-info))
        last-cmd (some valid-cmd? hist-for-user)]
    (if last-cmd
      @(future (-> last-cmd clean-cmd handle-unparsed-expr))
      (format "I couldn't find any command history for you, %s." (:name user)))))

(cmd-hook ["!" #"!"]
          _ !-cmd)
