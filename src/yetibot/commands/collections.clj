(ns yetibot.commands.collections
  (:require [clojure.string :as s]
            [yetibot.hooks :refer [cmd-hook]]
            [yetibot.campfire :refer [chat-data-structure]]
            [yetibot.util :refer [psuedo-format split-kvs-with ensure-items-collection]]))

; random
(defn random
  "random <list> # returns a random item where <list> is a comma-separated list of items.
   Can also be used to extract a random item when a collection is piped to random."
  [{items :opts}]
  (if (not (empty? items))
    (rand-nth (ensure-items-collection items))
    (str (rand 100000))))

(cmd-hook #"random"
          _ random)

(def head-tail-regex #"(\d+).+")

; head / tail helpers
(defn head-or-tail
  [single-fn multi-fn n items]
  (let [f (if (= 1 n) single-fn (partial multi-fn n))]
    (f (ensure-items-collection items))))

(def head (partial head-or-tail first take))

(def tail (partial head-or-tail last take-last))

; head
(defn head-1
  "head <list> # returns the first item from the <list>"
  [{items :opts}] (head 1 items))

(defn head-n
  "head <n> <list> # return the first <n> items from the <list>"
  [{[_ n] :match items :opts}]
  (head (read-string n) items))

(cmd-hook #"head"
          #"(\d+)" head-n
          _ head-1)

; tail
(defn tail-1
  "tail <list> # returns the last item from the <list>"
  [{items :opts}] (tail 1 items))

(defn tail-n
  "tail <n> <list> # returns the last <n> items from the <list>"
  [{[_ n] :match items :opts}]
  (tail (read-string n) items))

(cmd-hook #"tail"
          #"(\d+)" tail-n
          _ tail-1)

; xargs
; example usage: !users | xargs attack
(defn xargs
  "xargs <cmd> <list> # run <cmd> for every item in <list>; behavior is similar to xargs(1)'s xargs -n1"
  [{:keys [args opts user]}]
  (if (s/blank? args)
    opts
    (let [itms (ensure-items-collection opts)]
      (pmap (fn [item]
              (try
                (yetibot.core/parse-and-handle-command
                  (psuedo-format args item) user)
                (catch Exception ex
                  ex)))
            itms))))

(cmd-hook #"xargs"
          _ xargs)

; join
(defn join
  "join <list> # joins list with a single space or whatever character is given"
  [{match :match items :opts}]
  (let [join-char (str (when-not (= match :empty) match) " ")]
    (s/join join-char (ensure-items-collection items))))

(cmd-hook #"join"
          #"(?is).+" join
          _ join)

; split
(defn split
  "split <string> # split string by provided character"
  [{[_ split-str to-split] :match}]
  (let [split-by (re-pattern split-str)]
    (s/split to-split split-by)))

(cmd-hook #"split"
          #"(?is)^(\S+)\s+(.+)$" split)

; words
(defn words
  "words <string> # split <string> by spaces into a list"
  [{args :args}]
  (s/split args #" "))

(cmd-hook #"words"
          _ words)

; set
(defn set-cmd
  "set <list> # returns the set of distinct elements in <list>"
  [{items :opts}]
  (set (ensure-items-collection items)))

(cmd-hook #"set"
          _ set-cmd)

; list
(defn list-cmd
  "list <comma-delimited-items> # construct a list"
  [{:keys [args]}]
  (s/split args #","))

(cmd-hook #"list"
          _ list-cmd)


; count
(defn count-cmd
  "count <list> # count the number of items in <list>"
  [{items :opts}]
  (count (ensure-items-collection items)))

(cmd-hook #"count"
          _ count-cmd)

; sort
(defn sort-cmd
  "sort <list> # sort a list"
  [{items :opts}]
  (sort (ensure-items-collection items)))

(cmd-hook #"sort"
          _ sort-cmd)

; grep
(defn grep-cmd
  "grep <pattern> <list> # filters the items in a list by <pattern>"
  [{:keys [args opts]}]
  (let [pattern (re-pattern (str "(?i)" args))
        items (ensure-items-collection opts)]
    (filter #(re-find pattern %) items)))

(cmd-hook #"grep"
          _ grep-cmd)

; tee
(defn tee-cmd
  "tee <list> # output <list> to chat and return list (useful for pipes)"
  [{items :opts}]
  (chat-data-structure items)
  items)

(cmd-hook #"tee"
          _ tee-cmd)

; reverse
(defn reverse-cmd
  "reverse <list> # reverse the ordering of <list>"
  [{items :opts}]
  (reverse (ensure-items-collection items)))

(cmd-hook #"reverse"
          _ reverse-cmd)


; keys
(defn keys-cmd
  "keys <map> # return the keys from <map>"
  [{items :opts}]
  (if (map? items)
    (keys items)
    (split-kvs-with first items)))

(cmd-hook #"keys"
          _ keys-cmd)

; vals
(defn vals-cmd
  "vals <map> # return the vals from <map>"
  [{items :opts}]
  (if (map? items)
    (vals items)
    (split-kvs-with second items)))

(cmd-hook #"vals"
          _ vals-cmd)

; raw
(defn raw-cmd
  "raw <coll> # output a string representation of the raw collection"
  [{items :opts}]
  (pr-str items))

(cmd-hook #"raw"
          _ raw-cmd)
