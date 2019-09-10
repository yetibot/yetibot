(ns yetibot.test.commands.emoji
  (:require
    [midje.sweet :refer [fact => =not=> truthy]]
    [midje.repl :refer [load-facts]]
    [yetibot.commands.emoji :refer :all]
    [clojure.spec.alpha :as s]))

(defn run-tests []
  (load-facts *ns*))

(fact test-get-emojis
  (get-all-emojis) =not=> empty?
  (take 1 (get-all-emojis)) => truthy)

(s/def ::unicode string?)

(s/def ::aliases (s/coll-of string?))

(s/def ::description string?)

(s/def ::tags (s/coll-of string?))

(s/def ::emoji-structure (s/keys :req-un [::unicode ::aliases]
                                 :opt-un [::description ::tags]))

(fact test-parse-emojis
  (parse-all-emojis) =not=> empty?
  (s/valid? ::emoji-structure
            (first (parse-all-emojis))) => true)

(fact test-all-tags
  (all-tags nil) =not=> empty?
  (sort (all-tags nil)) => (all-tags nil))

(def first-list-emoji (first (list-emojis {:match [nil nil]})))
(def set-flag-first-list-emoji (first (list-emojis {:match [nil 1]})))

(fact test-list-emojis
  (let [validate (fn [emoji-string] (s/valid? string? emoji-string))]
    (validate first-list-emoji) => true
    (validate set-flag-first-list-emoji) => true
    (< (count first-list-emoji) (count set-flag-first-list-emoji)) => true))

(fact test-filter-by-tag
  (let [validate (fn [input] (empty? (filter-by-tag input)))]
    (validate nil) => truthy
    (validate []) => truthy
    (validate '()) => truthy
    (validate 1) => truthy)
  (s/valid? ::emoji-structure (first (filter-by-tag "smile"))) => true)

(fact test-filter-by-description
  (let [validate (fn [input] (empty? (filter-by-description input)))]
    (validate nil) => truthy
    (validate []) => truthy
    (validate '()) => truthy)
  (s/valid? ::emoji-structure (first (filter-by-description "smile"))) => true)

(fact test-filter-by-alias
  (let [validate (fn [input] (empty? (filter-by-alias input)))]
    (validate nil) => truthy
    (validate []) => truthy
    (validate '()) => truthy
    (validate 1) => truthy)
  (s/valid? ::emoji-structure (first (filter-by-alias "smile"))) => true)

(def search-tag-ex (first (search-by-tag {:match [nil nil "smile"]})))
(def search-tag-flag-ex (first (search-by-tag {:match [nil 1 "smile"]})))

(fact test-search-by-tag
  (let [validate (fn [emoji-string] (s/valid? string? emoji-string))]
    (validate search-tag-ex) => truthy
    (validate search-tag-flag-ex) => truthy)
  (< (count search-tag-ex) (count search-tag-flag-ex)) => true)

(def search-ex (first (search {:match [nil nil "smile"]})))
(def search-flag-ex (first (search {:match [nil 1 "smile"]})))

(fact test-search
  (let [validate (fn [emoji-string] (s/valid? string? emoji-string))]
    (validate search-ex) => truthy
    (validate search-flag-ex) => truthy)
  (< (count search-tag-ex) (count search-flag-ex)) => true)

(fact test-search-by-alias
  (s/valid? string? (first (search-by-alias {:match [nil "smile"]}))) => true)
