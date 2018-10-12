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
    (let [[_ text punc] (re-matches #"^(.*?)?([\.!?,:]+)?$" s)]
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
  (let [s-lc (str/lower-case s)]
    (if (contains? dict s-lc)
      (get dict s-lc)
      s)))

(defn to-pirate
  [s]
  (->> (str/split s #"\s+")
       (map
        (-> sub-word
            wrap-punctuation
            wrap-capitalization))
       (str/join " ")))

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
  "Possibly suffix random pirate flavor, for given probability."
  [s prob]
  (if (< (rand) prob)
    (let [flavor (get flavor (-> (count flavor) rand int))]
      (str/replace-first s
                         #"[\.!?]*$"
                         #(format ", %s%s" flavor %)))
    s))

(defn slurrr
  "I'm not drunk, you're drunk."
  [s prob]
  (let [max-repeat 2]
    (if (< (rand) prob)
      (str/replace s
                   #"[alr]"
                   (fn [c]
                     (apply str (repeat (-> (rand max-repeat)
                                            int
                                            inc) c))))
      s)))

(defn pirate-cmd
  "pirate <string> # translate string into proper pirate, yar <string>"
  {:yb/cat #{:info}}
  [{:keys [match]}]
  (let [prob (probability)]
    (-> (to-pirate match)
        (suffix-flavor prob)
        (slurrr prob))))

(cmd-hook #"pirate"
  #".+" pirate-cmd)
