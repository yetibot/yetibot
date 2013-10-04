(ns yetibot.commands.alias
  (:require
    [clojure.string :as s]
    [yetibot.models.help :as help]
    [yetibot.models.alias :as model]
    [yetibot.hooks :refer [cmd-hook cmd-unhook]]))

(defonce aliases (atom {}))

(defn- wire-alias
  "Example input (notice pipe is escaped so top-level command parser doesn't expand
   the pipes):
   i90 = random \\| echo http://images.wsdot.wa.gov/nw/090vc00508.jpg?nocache=%s&.jpg
   Note: alias args aren't supported yet:
   alias grid x = !repeat 10 `repeat 10 #{x} | join`"
  [{[_ a-name a-cmd] :match}]
  (let [a-cmd (s/replace a-cmd "\\|" "|") ; unescape pipes
        docstring (str "alias for " a-cmd)
        existing-alias (@aliases a-name)
        cmd-fn (with-meta
                 (fn [{:keys [user]}] (yetibot.handler/handle-unparsed-expr a-cmd))
                 {:doc docstring}) ]
    (swap! aliases assoc a-name a-cmd)
    (cmd-hook [a-name (re-pattern (str "^" a-name "$"))]
              _ cmd-fn)
    ; manually add docs since the meta on cmd-fn is lost in cmd-hook
    (help/add-docs a-name [docstring])
    (if existing-alias
      (format "Replaced existing alias %s = %s" a-cmd existing-alias)
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
    (and
      (not ((set (keys as)) cmd))
      ((set (keys (help/get-docs))) cmd))))

(defn create-alias
  "alias <alias> = <cmd> # alias a cmd, where <cmd> is a normal command expression.
   Note that pipes must be escaped like \"\\|\" to prevent normal pipe evaluation."
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

(defonce loader (future (load-aliases)))

(cmd-hook #"alias"
          #"^$" list-aliases
          #"remove\s+(\w+)" remove-alias
          #"(\w+)\s+\=\s+(.+)" create-alias)
