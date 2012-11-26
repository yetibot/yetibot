(ns yetibot.commands.eval
  (:use [yetibot.hooks :only [cmd-hook]]
        [yetibot.util :only [env]]
        [clojure.string :only [split]]))

(def ^:private privs (set (map read-string (split (or (:YETIBOT_EVAL_PRIVS env) "") #","))))

(defn- user-is-allowed? [user]
  (boolean (some #{(:id user)} privs)))

(defn eval-cmd
  "eval <form> # evaluate the <form> data structure in YetiBot's context"
  [{:keys [args user]}]
  (if (user-is-allowed? user)
    (pr-str (eval (read-string args)))
    (format "You are not allowed, %s." (:name user))))

(cmd-hook #"eval"
          _ eval-cmd)
