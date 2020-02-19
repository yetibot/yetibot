(ns yetibot.test.commands.csv
  (:require
   [clojure.data.csv :as csv]
   [midje.sweet :refer [fact =>]]
   [yetibot.commands.csv :refer [csv-cmd csv-data->maps csv-parse-cmd]]))

(def sample-csv
  "foo,bar,baz
A,1,x
B,2,y
C,3,z")

(fact
 "csv parsing results in maps with columns as keyword keys"
 (-> sample-csv
     csv/read-csv
     csv-data->maps) => [{:foo "A", :bar "1", :baz "x"}
                         {:foo "B", :bar "2", :baz "y"}
                         {:foo "C", :bar "3", :baz "z"}])

(fact
 "csv-parse-cmd can parse from a string"
 (csv-parse-cmd
  {:match [nil sample-csv]}) => [{:foo "A", :bar "1", :baz "x"}
                                 {:foo "B", :bar "2", :baz "y"}
                                 {:foo "C", :bar "3", :baz "z"}])

(comment
  (csv-cmd
   {:match
    ["https://docs.google.com/spreadsheets/d/1JIp3AjmPIA7T2aJsXPDaz7KxyxJLJGbgkR6r_dpvu_E/export?format=csv"]})
  )

