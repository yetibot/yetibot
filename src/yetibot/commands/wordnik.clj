(ns yetibot.commands.wordnik
  (:require
    [wordnik.api.word :as word]
    [wordnik.api.words :as words]
    [taoensso.timbre :refer [info warn error]]
    [yetibot.hooks :refer [cmd-hook]]
    [yetibot.config :refer [config-for-ns conf-valid?]]
    [clojure.string :as s])
  (:use wordnik.core))

(def config (config-for-ns))
(def ^:private api-key (:api-key config))

(defmacro ^:private with-auth [& body]
  `(with-api-key api-key ~@body))

(defn- extract-definitions-text [ds]
  (map-indexed (fn [idx d] (str (inc idx) ". " (:text d)))
               ds))

(defn- format-defs [w]
  (conj (extract-definitions-text (:definitions w))
        (:word w)))

(defn define
  "wordnik define <word> # look up the definition for <word> on Wordnik"
  [{[_ w] :match}]
  (with-auth
    (let [ds (word/definitions (s/trim w))
          word (-> ds first :word)]
      (if word
        (conj (extract-definitions-text ds) word)
        (str "No defitions found for " w)))))

(defn random
  "wordnik random # look up a random word on Wordnik"
  [_] (with-auth (define (:word (words/random-word)))))

(defn wotd
  "wordnik wotd # look up the Word of the Day on Wordnik"
  [_] (with-auth (format-defs (words/wotd))))

(if (conf-valid?)
  (cmd-hook #"wordnik"
            #"define\s(\w+)" define
            #"random" random
            #"wotd" wotd)
  (info "Wordnik is not configured"))
