(ns yetibot.commands.!
  (:require [yetibot.models.history :as h]
            [clojure.string :as s])
  (:use [yetibot.util :only [cmd-hook]]))

(defn- valid-cmd? [json]
  (let [body (:body json)]
    (when (re-find #"^\![^\!]" body) json)))

;;; Currently this just looks back through history for posts starting with !, which
;;; is an impl detail. Better would be for `handle-command` to store commands that it
;;; handled in a separate history structure.
(defn !-cmd
  "! # execute your last command"
  [user]
  (let [hist-for-user (h/items-for-user user)
        last-cmd (some valid-cmd? hist-for-user)]
    (prn "last command is" last-cmd)
    (if last-cmd
      (with-meta (yetibot.core/handle-text-message last-cmd) {:suppress true})
      (format "I couldn't find any command history for you, %s." (:name user)))))

(cmd-hook #"!"
          _ (!-cmd user))
