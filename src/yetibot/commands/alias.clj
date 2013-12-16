(ns yetibot.commands.alias
  (:require
    [taoensso.timbre :refer [info warn error]]
    [clojure.string :as s]
    [yetibot.util.format :refer [pseudo-format-n *subst-prefix*]]
    [yetibot.util :refer [with-fresh-db]]
    [yetibot.handler :refer [handle-unparsed-expr]]
    [yetibot.util.format :refer [format-n]]
    [yetibot.models.help :as help]
    [yetibot.models.alias :as model]
    [yetibot.hooks :refer [cmd-hook cmd-unhook]]))

(defn- clean-alias-cmd
  "cmd should be a literal, so chop off the surrounding quotes"
  [cmd]
  (-> cmd s/trim (s/replace #"^\"([^\"]+)\"$" "$1")))

(def method-like-replacement-prefix "\\$")

(defn- build-alias-cmd-fn [cmd]
  (fn [{:keys [user args]}]
    (binding [*subst-prefix* method-like-replacement-prefix]
      (let [args (if (empty? args) [] (s/split args #" "))
            expr (pseudo-format-n cmd args)]
        (handle-unparsed-expr expr)))))

(defn- existing-alias [cmd-name] (model/find-first {:cmd-name cmd-name}))

(defn- cleaned-cmd-name [a-name]
  ; allow spaces in a-name, even though we just grab the first word to use as
  ; the actual cmd
  (-> a-name s/trim (s/split #" ") first))

(defn- wire-alias
  "Example input (use quotes to make it a literal so it doesn't get evaluated):
   i90 = \"random | echo http://images.wsdot.wa.gov/nw/090vc00508.jpg?nocache=%s&.jpg\"
   Alias args are also supported (all args inserted):
   alias grid = \"repeat 10 `repeat 10 $s | join`\"
   Use first arg only:
   alias sayhi = echo hi, $1"
  [{:keys [cmd-name cmd]}]
  (let [docstring (str "alias for " cmd)
        existing-alias (existing-alias cmd-name)
        cmd-fn (build-alias-cmd-fn cmd)]
    (cmd-hook [cmd-name (re-pattern (str "^" cmd-name "$"))]
              _ cmd-fn)
    ; manually add docs since the meta on cmd-fn is lost in cmd-hook
    (help/add-docs cmd-name [docstring])
    (if existing-alias
      (format "Replaced existing alias %s = %s" cmd-name (:cmd existing-alias))
      (format "%s alias created" cmd-name))))

(defn add-alias [{:keys [cmd-name cmd userid] :as alias-info}]
  (let [new-alias-map {:userid userid :cmd-name cmd-name :cmd cmd}]
    (if (existing-alias cmd-name)
      (model/update (:id existing-alias) new-alias-map)
      (model/create new-alias-map)))
  alias-info)

(defn load-aliases []
  (let [alias-cmds (model/find-all)]
    (dorun (map (comp wire-alias add-alias)) alias-cmds)))

(defn- built-in? [cmd]
  (let [as (model/find-all)]
    (and (not ((set (map :cmd-name as)) cmd))
         ((set (keys (help/get-docs))) cmd))))

(defn create-alias
  "alias <alias> = \"<cmd>\" # alias a cmd, where <cmd> is a normal command expression. Note the use of quotes, which treats the right-hand side as a literal allowing the use of pipes. Use $s as a placeholder for all args, or $n (where n is a 1-based index of which arg) as a placeholder for a specific arg."
  [{[_ a-name a-cmd] :match user :user}]
  (let [cmd-name (cleaned-cmd-name a-name)
        cmd (clean-alias-cmd a-cmd)]
    (if (built-in? cmd-name)
      (str "Can not alias existing built-in command " a-name)
      ((comp wire-alias add-alias) {:userid (:id user) :cmd-name cmd-name :cmd cmd}))))

(defn list-aliases
  "alias # show existing aliases"
  [_]
  (let [as (model/find-all)]
    (if (empty? as)
      "No aliases have been defined"
      (into {} (map (juxt :cmd-name :cmd) as)))))

(defn remove-alias
  "alias remove <alias> # remove alias by name"
  [{[_ cmd] :match}]
  (model/delete-all {:cmd-name cmd})
  (cmd-unhook cmd)
  (format "alias %s removed" cmd))

(defonce loader (with-fresh-db (future (load-aliases))))

(defn port-old-aliases []
  (let [as (model/find-all)
        new-as (reduce (fn [acc ent]
                         (if-let [ac (:alias-cmd ent)]
                           (let [[_ a-name a-cmd] (read-string ac)]
                             (conj acc (merge (select-keys ent [:userid])
                                              {:cmd-name (cleaned-cmd-name a-name)
                                               :cmd (clean-alias-cmd a-cmd)})))
                           acc))
                       [] as)]
    (info "Remap:" (pr-str new-as))
    (doall (map model/create new-as))))

(cmd-hook #"alias"
          #"^$" list-aliases
          #"remove\s+(\w+)" remove-alias
          #"([\S\s]+?)\s*\=\s*(.+)" create-alias)
