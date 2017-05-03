(ns yetibot.test.commands.urban-thesaurus
  (:require
    [yetibot.commands.urban-thesaurus :refer :all]
    [clojure.test :refer :all]
    [yetibot.core.util.http :refer [fetch]]
    [clojure.data.json :as json]))

(deftest return-all-words-from-api-response
  (with-redefs-fn {#'fetch (fn [url] (json/write-str [{:word "test"} {:word "word"}]))}
    #(is
      (= (urbanthes-cmd {:match "programmer"}) ["test" "word"]))))

(deftest return-empty-list-for-unknown-word
  (with-redefs-fn {#'fetch (fn [url] (json/write-str []))}
    #(is
      (= (urbanthes-cmd {:match "abcdef"}) []))))
