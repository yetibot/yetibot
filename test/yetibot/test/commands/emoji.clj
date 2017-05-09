(ns yetibot.test.commands.emoji
  (:require
    [midje.sweet :refer [fact => =not=> truthy]]
    [yetibot.commands.emoji :refer :all]
    [schema.core :as s]))

(fact test-get-emojis
  (get-all-emojis) =not=> empty?
  (take 1 (get-all-emojis)) => truthy)

(def emoji-structure {:unicode s/Str
                      :aliases [s/Str]
                      (s/optional-key :description) s/Str
                      (s/optional-key :tags) [s/Str]})

(fact test-parse-emojis
  (parse-all-emojis) =not=> empty?
  (s/validate emoji-structure
              (first (parse-all-emojis))) => truthy)

(fact test-all-tags
  (all-tags nil) =not=> empty?
  (sort (all-tags nil)) => (all-tags nil))

(def first-list-emoji (first (list-emojis {:match [nil nil]})))
(def set-flag-first-list-emoji (first (list-emojis {:match [nil 1]})))

(fact test-list-emojis
  (let [validate (fn [emoji-string] (s/validate s/Str emoji-string))]
    (validate first-list-emoji) => truthy
    (validate set-flag-first-list-emoji) => truthy
    (< (count first-list-emoji) (count set-flag-first-list-emoji)) => true))

(fact test-filter-by-tag
  (let [validate (fn [input] (empty? (filter-by-tag input)))]
    (validate nil) => truthy
    (validate []) => truthy
    (validate '()) => truthy
    (validate 1) => truthy)
  (s/validate emoji-structure (first (filter-by-tag "smile"))) => truthy)

(fact test-filter-by-description
  (let [validate (fn [input] (empty? (filter-by-description input)))]
    (validate nil) => truthy
    (validate []) => truthy
    (validate '()) => truthy)
  (s/validate emoji-structure (first (filter-by-description "smile"))) => truthy)

(fact test-filter-by-alias
  (let [validate (fn [input] (empty? (filter-by-alias input)))]
    (validate nil) => truthy
    (validate []) => truthy
    (validate '()) => truthy
    (validate 1) => truthy)
  (s/validate emoji-structure (first (filter-by-alias "smile"))) => truthy)

(def search-tag-ex (first (search-by-tag {:match [nil nil "smile"]})))
(def search-tag-flag-ex (first (search-by-tag {:match [nil 1 "smile"]})))

(fact test-search-by-tag
  (let [validate (fn [emoji-string] (s/validate s/Str emoji-string))]
    (validate search-tag-ex) => truthy
    (validate search-tag-flag-ex) => truthy)
  (< (count search-tag-ex) (count search-tag-flag-ex)) => true)

(def search-ex (first (search {:match [nil nil "smile"]})))
(def search-flag-ex (first (search {:match [nil 1 "smile"]})))

(fact test-search
  (let [validate (fn [emoji-string] (s/validate s/Str emoji-string))]
    (validate search-ex) => truthy
    (validate search-flag-ex) => truthy)
  (< (count search-tag-ex) (count search-flag-ex)) => true)

(fact test-search-by-alias
  (s/validate s/Str (first (search-by-alias {:match [nil "smile"]}))) => truthy)
