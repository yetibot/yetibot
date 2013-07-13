(ns yetibot.commands.alias
  (:require
    [clojure.string :as s]
    [yetibot.models.alias :as model]
    [yetibot.hooks :refer [cmd-hook]]))

(defn- wire-alias
  "Example input (notice pipe is escaped so top-level command parser doesn't expand
   the pipes):
   i90 = random \\| echo http://images.wsdot.wa.gov/nw/090vc00508.jpg?nocache=%s&.jpg

   Note: this isn't supported yet:
   alias grid x = !repeat 10 `repeat 10 #{x} | join`"
  [a]
  (prn "unparsed alias is " a)
  (let [a (s/replace a "\\|" "|") ; unescape pipes
        [_ a-name a-cmd]  (re-find #"(\w+)\s+\=\s+(.+)" a)
        docstring (str "alias for " a-cmd)]
    (prn a-name a-cmd)
    (def cmd-fn
      (with-meta
        (fn [{:keys [user]}] (yetibot.core/direct-cmd a-cmd user))
        {:doc docstring}))
    (cmd-hook [a-name (re-pattern (str "^" a-name "$"))]
              _ cmd-fn)
    (format "%s alias created" a-name)))

(defn add-alias [{:keys [user args] :as cmd-map}]
  (model/create {:user-id (:id user) :alias-cmd args})
  cmd-map)

(defn load-aliases []
  (let [alias-cmds (model/find-all)]
    (prn "loading aliases")
    (prn alias-cmds)
    (map (comp wire-alias :alias-cmd) alias-cmds)))


(def create-alias
  "alias <alias> = <cmd> # alias a cmd, where <cmd> is a normal command expression.
   Note that pipes must be escaped like \"\\|\" to prevent normal pipe evaluation."
  (comp wire-alias :args add-alias))

(future (load-aliases))

(cmd-hook #"alias"
          _ create-alias)
