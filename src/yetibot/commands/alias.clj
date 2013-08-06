(ns yetibot.commands.alias
  (:require
    [clojure.string :as s]
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
                 (fn [{:keys [user]}] (yetibot.handler/direct-cmd a-cmd user))
                 {:doc docstring}) ]
    (swap! aliases assoc a-name a-cmd)
    (cmd-hook [a-name (re-pattern (str "^" a-name "$"))]
              _ cmd-fn)
    (if existing-alias
      (format "Replaced existing alias %s = %s" a-cmd existing-alias)
      (format "%s alias created" a-name))))

(defn add-alias [{:keys [user match] :as cmd-map}]
  (model/create {:user-id (:id user) :alias-cmd (prn-str match)})
  cmd-map)

(defn load-aliases []
  (let [alias-cmds (model/find-all)]
    (map (comp wire-alias read-string :alias-cmd) alias-cmds)))

(def create-alias
  "alias <alias> = <cmd> # alias a cmd, where <cmd> is a normal command expression.
   Note that pipes must be escaped like \"\\|\" to prevent normal pipe evaluation."
  (comp wire-alias add-alias))

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
