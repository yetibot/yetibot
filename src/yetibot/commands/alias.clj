(ns yetibot.commands.alias
  (:require
    [clojure.string :as s]
    [yetibot.models.alias :as model]
    [yetibot.hooks :refer [cmd-hook]]))

(defn- wire-alias
  "Example input (notice pipe is escaped so top-level command parser doesn't expand
   the pipes):
   i90 = random \\| http://images.wsdot.wa.gov/nw/090vc00508.jpg?nocache=%s&.jpg

   Note: this isn't supported yet:
   alias grid x = !repeat 10 `repeat 10 #{x} | join`"
  [a]
  (let [a (s/replace a "\\|" "|") ; unescape pipes
        [_ a-name a-args a-cmd]  (re-find #"(\w+)\s+(.*)\=\s+(.+)" a)]
    (prn "cmd is " a-cmd)
    (cmd-hook (re-pattern a-name)
              _ (fn [{:keys [user]}]
                  (prn "run cmd" a-cmd)
                  (yetibot.core/direct-cmd a-cmd user)))
    (format "%s alias created" a-name)))

(defn- add-alias [{:keys [user args] :as cmd-map}]
  (model/create {:user-id (:id user)
                 :alias-cmd args})
  cmd-map)

(defn- load-aliases []
  (let [alias-cmds (model/find-all)]
    (prn "loading aliases")
    (prn alias-cmds)
    (map (comp wire-alias :alias-cmd) alias-cmds)))


(def create-alias
  "alias <alias> = <cmd> # alias a cmd, where <cmd> is a normal command expression"
  (comp wire-alias :args add-alias))

(future (load-aliases))

(cmd-hook #"alias"
          _ create-alias)
