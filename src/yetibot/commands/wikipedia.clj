(ns yetibot.commands.wikipedia
  (:require
    [clj-http.client :as client]
    [yetibot.core.util.http :refer [encode]]
    [yetibot.core.hooks :refer [cmd-hook]]))

(defn endpoint-search [q]
  (str "http://en.wikipedia.org/w/api.php?format=json&action=opensearch&search="
       (encode q)))

(defn endpoint-summary [title]
  (str
    "http://en.wikipedia.org/w/api.php?action=query&prop=extracts&explaintext=true&format=json&exintro=true&titles="
    (encode title)))

; TODO: fix "foo may refer too" - probably need more than just extracts
; (:body (json-get (endpoint-summary "Sherlock")))

(defn has-match? [res]
  (and (not (empty? res))
       (not (empty? (second res)))))

(defn- no-match [q] (str "No match for " q))

(defn json-get [uri] (client/get uri {:as :json}))

(defn summary-for-title [title]
  (let [res (:body (json-get (endpoint-summary title)))]
    (first (keep :extract (tree-seq map? vals res)))))

(defn search-cmd
  "wiki search <term> # search Wikipedia for titles"
  {:yb/cat #{:info}}
  [{[_ term] :match}]
  (let [res (:body (json-get (endpoint-search term)))]
    (if (has-match? res)
      (second res)
      (no-match term))))

(defn wiki-cmd
  "wiki <term> # look up Wikipedia summary for <term>"
  {:yb/cat #{:info}}
  [{:keys [match]}]
  (let [search-res (:body (json-get (endpoint-search match)))]
    (if (has-match? search-res)
      (do
        (prn (second search-res))
        (summary-for-title (first (second search-res))))
      (no-match match))))

(cmd-hook ["wiki" #"^wiki(pedia)*"]
          #"search\s+(.+)" search-cmd
          #".+" wiki-cmd)
