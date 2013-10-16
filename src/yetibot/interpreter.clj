(ns yetibot.interpreter
  "Handles evaluation of a parse tree"
  (:require
    [taoensso.timbre :refer [info warn error]]
    [yetibot.util :refer [psuedo-format]]
    [yetibot.util.format :refer [to-coll-if-contains-newlines]]))

(def ^:dynamic *current-user*)
(def ^:dynamic *chat-source*)

(defn handle-cmd
  "Hooked entry point for all command handlers. If no handlers intercept, it falls
   back to image search when available."
  [cmd-with-args extra]
  (info "nothing handled" cmd-with-args)
  ; default to looking up a random result from google image search
  (if (find-ns 'yetibot.commands.image-search)
    (handle-cmd (str "image " cmd-with-args) extra)
    (format "I don't know how to handle %s" cmd-with-args)))

(defn pipe-cmds
  "Pipe acc into cmd-with-args by either appending or sending acc as an extra :opts"
  [acc cmd-with-args]
  (let [extra {:raw acc
               :user *current-user*
               :chat-source *chat-source*}
        possible-coll-acc (to-coll-if-contains-newlines acc)]
    ; if possible-coll-acc is a string, append acc to args. otherwise send
    ; possible-coll-acc as an extra :opts param and append nothing to cmd-with-args.
    (apply handle-cmd
      (if (coll? possible-coll-acc)
        [cmd-with-args (conj extra {:opts possible-coll-acc})]
        [(if (empty? acc) cmd-with-args (psuedo-format cmd-with-args acc))
         extra]))))

(defn handle-expr [& cmds]
  (reduce pipe-cmds "" cmds))
