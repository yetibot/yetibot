(ns yetibot.commands.csv
  (:require [clj-http.client :as client]
            [clojure.data.csv :as csv]
            [clojure.string :as string]
            [taoensso.timbre :refer [info]]
            [yetibot.core.hooks :refer [cmd-hook]]))

(defn csv-data->maps [csv-data]
  (map zipmap
       (->> (first csv-data) ;; First row is the header
            (map keyword) ;; Drop if you want string keys instead
            repeat)
       (rest csv-data)))

(defn csv-cmd
  "csv <url> # parse CSV from <url>. Assumes first row columns are headers."
  [{[url] :match}]
  (info "csv" (pr-str url))
  (-> (client/get url)
      :body
      (clojure.string/replace  #"\uFEFF" "")
      csv/read-csv
      csv-data->maps))

(defn csv-parse-cmd
  "csv parse <csv>"
  [{[_ text] :match raw :raw opts :opts}]
  (info "csv-parse"
        {:text text
         :raw raw
         :opts opts
         :joined (string/join \newline opts)
         })
  (-> (or (and (not (string/blank? text)) text)
          (string/join \newline opts))
      csv/read-csv
      csv-data->maps))

(cmd-hook #"csv"
          #"parse\s*(.*)" csv-parse-cmd
          #"(.+)" csv-cmd)
