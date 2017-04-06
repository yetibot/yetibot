(ns yetibot.test.commands.emoji
  (:require
    [clojure.test :refer :all]
    [yetibot.commands.emoji :refer :all]
    [schema.core :as s]
    ))

(deftest test-get-emojis
  (is (not (empty? (get-all-emojis))))
  (is (take 1 (get-all-emojis)))) ; is enumerable?

(def emoji-structure {:unicode s/Str
                      :aliases [s/Str]
                      (s/optional-key :description) s/Str
                      (s/optional-key :tags) [s/Str]})

(deftest test-parse-emojis
  (is (not (empty? (parse-all-emojis))))
  (is (s/validate emoji-structure
                  (first (parse-all-emojis)))))

(deftest test-all-tags
  (is (not (empty? (all-tags nil))))
  (is (= (sort (all-tags nil)) (all-tags nil))))

(def first-list-emoji (first (list-emojis {:match [nil nil]})))
(def set-flag-first-list-emoji (first (list-emojis {:match [nil 1]})))

(deftest test-list-emojis
  (are [emoji-string] (s/validate s/Str emoji-string)
       first-list-emoji
       set-flag-first-list-emoji)
  (is (< (count first-list-emoji) (count set-flag-first-list-emoji))))

(deftest test-filter-by-tag
  (are [input] (empty? (filter-by-tag input))
       nil
       []
       '()
       1)
  (is (s/validate emoji-structure (first (filter-by-tag "smile")))))

(deftest test-filter-by-description
  (is (s/validate emoji-structure (first (filter-by-description "smile")))))

(deftest test-filter-by-alias
  (is (s/validate emoji-structure (first (filter-by-alias "smile")))))

(def search-tag-ex (first (search-by-tag {:match [nil nil "smile"]})))
(def search-tag-flag-ex (first (search-by-tag {:match [nil 1 "smile"]})))

(deftest test-search-by-tag
  (are [emoji-string] (s/validate s/Str emoji-string)
       search-tag-ex
       search-tag-flag-ex)
  (is (< (count search-tag-ex) (count search-tag-flag-ex))))

(def search-ex (first (search {:match [nil nil "smile"]})))
(def search-flag-ex (first (search {:match [nil 1 "smile"]})))

(deftest test-search
  (are [emoji-string] (s/validate s/Str emoji-string)
       search-ex
       search-flag-ex)
  (is (< (count search-ex) (count search-flag-ex))))

(deftest test-search-by-alias
  (is (s/validate s/Str (first (search-by-alias {:match [nil "smile"]})))))
