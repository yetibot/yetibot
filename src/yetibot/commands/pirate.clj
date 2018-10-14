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
    (let [[_ text punc] (re-matches #"^(.*?)?([.!?,:]+)?$" s)]
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

(def slurr-re #"[alr]")

(defn- mk-slurr-map
  "Return randomly ordered v of true and nil.  The number of trues is a
  configurable percentage of n, plus some fuzz.  The balance of n are
  nils."
  [n]
  (let [perc 0.7
        fuzz (rand 0.3)
        min-t (-> (* perc n) Math/round int)
        max-f (- n min-t)
        t (+ min-t (-> (* fuzz max-f) Math/round int))
        f (- n t)]
    (shuffle (concat (repeat t true)
                     (repeat f nil)))))

(defn- slurrable?
  "Return s if it's slurrable, nil if not."
  [s]
  (if (re-find slurr-re s) s nil))

(defn- slurr-word
  [s]
  (str/replace s
               slurr-re
               (fn [c]
                 (apply str (repeat (rand-nth [2 3]) c)))))

(defn slurrr
  "I'm not drunk, you're drunk."
  [s]
  (let [words (str/split s #"\b")
        cnt (count (filter slurrable? words))
        sm (mk-slurr-map cnt)]
    (loop [word (first words), tail (rest words), sm sm, accum []]
      (if (nil? word)
        (apply str accum)
        (if (slurrable? word)
          (if (nil? (first sm))
            (recur (first tail) (rest tail) (rest sm) (conj accum word))
            (recur (first tail) (rest tail) (rest sm) (conj accum (slurr-word word))))
          (recur (first tail) (rest tail) sm (conj accum word)))))))

(defn if-prob
  "Optionally apply fn f to string s, based on probability prob"
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
