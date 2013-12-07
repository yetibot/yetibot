(ns yetibot.commands.alias
  (:require
    [taoensso.timbre :refer [info warn error]]
    [clojure.string :as s]
    [yetibot.util :refer [with-fresh-db psuedo-format]]
    [yetibot.handler :refer [handle-unparsed-expr]]
    [yetibot.util.format :refer [format-n]]
    [yetibot.models.help :as help]
    [yetibot.models.alias :as model]
    [yetibot.hooks :refer [cmd-hook cmd-unhook]]))

(defonce aliases (atom {}))

(defn- clean-alias-cmd
  "cmd should be a literal, so chop off the surrounding quotes"
  [cmd]
  (-> cmd s/trim (s/replace #"^\"([^\"]+)\"$" "$1")))

(defn- build-alias-cmd-fn [cmd]
  (fn [{:keys [user args]}]
    (let [expr (apply (partial format-n cmd) (s/split args #" "))]
      (handle-unparsed-expr expr))))

(defn- wire-alias
  "Example input (use quotes to make it a literal so it doesn't get evaluated):
   i90 = \"random | echo http://images.wsdot.wa.gov/nw/090vc00508.jpg?nocache=%s&.jpg\"
   Note: alias args aren't supported yet:
   alias grid x = !repeat 10 `repeat 10 %s | join`"
  [{[_ a-name a-cmd] :match}]
  (let [a-name (s/trim a-name)
        a-cmd (clean-alias-cmd a-cmd)
        docstring (str "alias for " a-cmd)
        ; allow spaces in a-name, even though we just grab the first word
        cmd-name (first (s/split a-name #" "))
        existing-alias (@aliases cmd-name)
        cmd-fn (build-alias-cmd-fn a-cmd)]
    (swap! aliases assoc cmd-name a-cmd)
    (cmd-hook [cmd-name (re-pattern (str "^" cmd-name "$"))]
              _ cmd-fn)
    ; manually add docs since the meta on cmd-fn is lost in cmd-hook
    (help/add-docs cmd-name [docstring])
    (if existing-alias
      (format "Replaced existing alias %s = %s" cmd-name existing-alias)
      (format "%s alias created" a-name))))

(defn add-alias [{:keys [user match] :as cmd-map}]
  (model/create {:userid (:id user) :alias-cmd (prn-str match)})
  cmd-map)

(defn load-aliases []
  (let [alias-cmds (model/find-all)]
    (dorun (map (comp wire-alias
                      (partial hash-map :match)
                      read-string
                      :alias-cmd) alias-cmds))))

(defn- built-in? [cmd]
  (let [as @aliases]
    (and (not ((set (keys as)) cmd))
         ((set (keys (help/get-docs))) cmd))))

(defn create-alias
  "alias <alias> = \"<cmd>\" # alias a cmd, where <cmd> is a normal command expression. Note the use of quotes, which treats the right-hand side as a literal allowing the use of pipes."
  [{[_ a-name _] :match :as args}]
  (if (built-in? a-name)
    (str "Can not alias existing built-in command " a-name)
    ((comp wire-alias add-alias) args)))

(defn list-aliases
  "alias # show existing aliases"
  [_]
  (let [as @aliases]
    (if (empty? as)
      "No aliases have been defined"
      as)))

(defn remove-alias
  "alias remove <alias> # remove alias by name"
  [{[_ cmd] :match}]
  (swap! aliases dissoc cmd)
  (cmd-unhook cmd)
  (format "alias %s removed" cmd))

(defonce loader
  (with-fresh-db
    (future (load-aliases))))

(cmd-hook #"alias"
          #"^$" list-aliases
          #"remove\s+(\w+)" remove-alias
          #"([\S\s]+?)\s*\=\s*(.+)" create-alias)
