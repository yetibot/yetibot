(ns yetibot.commands.wikipedia
  (:require [yetibot.util.http :refer [get-json]]
            [yetibot.hooks :refer [cmd-hook]]))

(defn endpoint-search [q]
  (str "http://en.wikipedia.org/w/api.php?format=json&action=opensearch&search=" q))

(defn endpoint-summary [title]
  (str
    "http://en.wikipedia.org/w/api.php?action=query&prop=extracts&explaintext=true&format=json&exintro=true&titles="
    title))

(defn has-match? [res]
  (and (not (empty? res))
       (not (empty? (second res)))))

(defn- no-match [q]
  (str "No match for " q))

(defn summary-for-title [title]
  (let [res (get-json (endpoint-summary title))]
    (first (keep :extract (tree-seq map? vals res)))))

(defn wiki-cmd
  "wiki <term> # look up Wikipedia summary for <term>"
  [{:keys [match]}]
  (let [search-res (get-json (endpoint-search match))]
    (if (has-match? search-res)
      (summary-for-title (first (second search-res)))
      (no-match match))))

(cmd-hook ["wiki" #"^wiki(pedia)*"]
          #"\w+" wiki-cmd)
