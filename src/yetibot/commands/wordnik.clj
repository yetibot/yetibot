(ns yetibot.commands.wordnik
  (:require [wordnik.api.word :as word]
            [wordnik.api.words :as words]
            [clojure.string :as s])
  (:use wordnik.core
        [yetibot.util :only (cmd-hook)]))

(def ^:private api-key (System/getenv "WORDNIK_API_KEY"))

(defn- extract-definitions-text [ds]
  (map-indexed (fn [idx d] (str (inc idx) ". " (:text d)))
               ds))

(defn- format-defs [w]
  (conj (extract-definitions-text (:definitions w))
        (:word w)))

(defn define
  "wordnik define <word> # look up the definition for <word> on Wordnik"
  [w]
  (with-api-key
    api-key
    (extract-definitions-text (word/definitions (s/trim w)))))

(defn wotd
  "wordnik wotd # look up the Word of the Day on Wordnik"
  []
  (with-api-key api-key
                (format-defs (words/wotd))))

(cmd-hook #"wordnik"
          #"define\s(\w+)" (define (second p))
          #"wotd" (wotd))
