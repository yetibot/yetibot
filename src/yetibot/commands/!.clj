(ns yetibot.commands.!
  (:require [yetibot.models.history :as h]
            [clojure.string :as s])
  (:use [yetibot.hooks :only [cmd-hook]]))

(defn- valid-cmd? [json]
  (let [body (:body json)]
    (when (re-find #"^\![^\!]" body) json)))

(defn- clean-cmd [json] (s/replace (:body json) #"\!" ""))

(defn !-cmd
  "! # execute your last command"
  [{:keys [user]}]
  (let [hist-for-user (reverse (h/items-for-user user))
        last-cmd (some valid-cmd? hist-for-user)]
    (prn "last command is" last-cmd)
    (if last-cmd
      (yetibot.handler/handle-unparsed-expr (clean-cmd last-cmd) user)
      (format "I couldn't find any command history for you, %s." (:name user)))))

(cmd-hook ["!" #"!"]
          _ !-cmd)
