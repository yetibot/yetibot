(ns yetibot.commands.tldr
  (:require
    [clj-http.client :as client]
    [clojure.string :as string]
    [yetibot.core.hooks :refer [cmd-hook]]
    [clojure.core.memoize :as memo]))

(def commands-uri "https://tldr.sh/assets/index.json")

(defn fetch-commands
  "Returns a list of all the tldr commands available"
  []
  (->> (client/get commands-uri {:as :json :throw-exceptions false})
       :body
       :commands
       (map :name)))

(def one-hour-msecs (* 60 60 1000))

(defonce tldr-commands
  (memo/ttl fetch-commands :ttl/threshold one-hour-msecs))

(defn tldr-search
  "Returns a string of all the tldr commands whose names contain <query>"
  [query]
  (let [query-regex (re-pattern (str ".*" query ".*"))]
    (->> (tldr-commands)
         (filter #(re-matches query-regex %))
         (string/join " "))))

(def page-uri "https://raw.githubusercontent.com/tldr-pages/tldr/master/pages/%s/%s.md")

(def all-platforms ["common" "linux" "osx" "sunos" "windows"])

(defn- tldr-page
  "Returns response for a tldr page if it exists, nil otherwise."
  [url]
  (let [response (client/get url {:throw-exceptions false})
        status (:status response)]
    (when (= 200 status)
      response)))

(defn tldr
  "Returns TLDR page markdown for a `command`."
  ([command]
   (tldr all-platforms command))
  ([platforms command] filter
   (if-some [response (->> platforms
                           (map #(format page-uri % command))
                           (some tldr-page))]
     (-> response
         :body
         (string/replace #"\n\n" "\n"))
     (let [search-results (tldr-search command)]
       (if-not (string/blank? search-results)
         (format "No exact matches found for '%s'.\nPartial matches: %s" command search-results)
         (format "No matches found for '%s'." command))))))

(defn tldr-random
  "Returns a random tldr command"
  [_]
  (-> (tldr-commands)
      (rand-nth)
      (tldr)))

(defn tldr-cmd
  {:doc "tldr <command> # get typical usages of a command"
   :yb/cat #{:util}}
  [{[_ command] :match}]
  (tldr command))

(defn tldr-platform-cmd
  "tldr <platform>/<command> # get typical usages of a command for a platform"
  [{[_ platform command] :match}]
  (tldr [platform "common"] command))

(cmd-hook "tldr"
  #"(linux|osx|sunos|windows)/(\S+).*" tldr-platform-cmd
  #"(\S+).*" tldr-cmd
  #"" tldr-random)

