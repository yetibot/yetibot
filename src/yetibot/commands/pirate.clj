(ns yetibot.commands.pirate
  (:require
   [yetibot.core.hooks :refer [cmd-hook]]
   [clojure.java.io :as io]
   [clojure.edn :as edn]
   [clojure.string :as str]
   [clj-time.core :as t]))

;; TODO - Let's derive this from properties of the requesting user.  I
;; think this is pretty straightforward with Slack but I have to give
;; IRC some more thought.  TZ might be an input to how we sort
;; recommended locations, for Issue #740 - Weather API migration.
(def local-tz "America/New_York")

;; TODO - We'll use this in Issue #740, too.  Should probably move to
;; yetibot.core.util or similar...
(defn get-var
  [resource-file]
  (-> (io/resource resource-file)
      slurp
      edn/read-string))

(def dict (get-var "pirate/dict.edn"))
(def flavor (get-var "pirate/flavor.edn"))

(defn wrap-punctuation
  "Expects a fn, f, and returns a fn taking one arg: a string.  We strip
  trailing punctuation before calling the wrapped fn f, replacing on
  the return of f."
  [f]
  (fn [s]
    (let [[_ text punc] (re-matches #"(.*?)?([.!?,:]+)?" s)]
      (str (f text) punc))))

(defn wrap-capitalization
  "Expects a fn, f, and returns a fn taking one arg: a string.  We
  upper-case the first char of the return of the wrapped fn, f, if the
  string had an initial upper-case char."
  [f]
  (fn [s]
    (if (Character/isUpperCase (first s))
      (str/replace-first (f s) #"." str/upper-case)
      (f s))))

(defn sub-word
  [s]
  (get dict (str/lower-case s) s))

(defn to-pirate
  [s]
  (->> (str/split s #"\b")
       (map
        (-> sub-word
            wrap-punctuation
            wrap-capitalization))
       (apply str)))

;;
;; Add some extra flavor
;;
(defn probability
  "Return probability, by hour, for configured TZ."
  []
  (let [hour (-> (t/to-time-zone (t/now) (t/time-zone-for-id local-tz))
                 t/hour)]
    (nth (concat (repeat 8 0.95)
                 (repeat 8 0.25)
                 (repeat 8 0.75))
         hour)))

(defn suffix-flavor
  "Suffix random pirate flavor."
  [s]
  (let [flavor (rand-nth flavor)]
    (str/replace-first s
                       #"[.!?]*$"
                       #(format ", %s%s" flavor %))))

(def slur-re #"[alr](?![alr])")

(defn- mk-slur-map
  "Return randomly ordered v of true and nil.  The number of trues is a
  configurable percentage of n, plus some fuzz.  The balance of n are
  nils."
  [n]
  (let [perc 0.45
        fuzz (rand 0.5)
        min-t (* perc n)
        max-f (- n min-t)
        t (-> (* fuzz max-f) (+ min-t) Math/round)
        f (- n t)]
    (shuffle (concat (repeat t true)
                     (repeat f nil)))))

(defn- slurrable?
  "Return s if it's slurrable, nil if not."
  [s]
  (if (re-find slur-re s) s nil))

(defn- slur-word
  [s]
  (str/replace s
               slur-re
               (fn [c]
                 (apply str (repeat (rand-nth [2 3]) c)))))

(defn slurrr
  "I'm not drunk, you're drunk."
  [s]
  (let [words (str/split s #"\b")
        sm (mk-slur-map (count (filter slurrable? words)))]
    (loop [[word & tail] words, sm sm, accum []]
      (if (nil? word)
        (apply str accum)
        (if (slurrable? word)
          (if (nil? (first sm))
            (recur tail (rest sm) (conj accum word))
            (recur tail (rest sm) (conj accum (slur-word word))))
          (recur tail sm (conj accum word)))))))

(defn if-prob
  "Optionally apply fn f to string s, based on probability prob."
  [s f prob]
  (if (< (rand) prob)
    (f s)
    s))

(defn pirate-cmd
  "pirate <string> # translate string into proper pirate, yar <string>"
  {:yb/cat #{:info}}
  [{:keys [match]}]
  (let [prob (probability)]
    (-> (to-pirate match)
        (if-prob suffix-flavor prob)
        (if-prob slurrr prob))))

(cmd-hook #"pirate"
  #".+" pirate-cmd)
