(ns yetibot.models.postal-code
  (:require
   [clojure.string :as str]))

;;
;; Postal Code validation, qualification, and cleanup
;;

(defn- nl-cleanup
  [d s]
  (str d " " (str/upper-case s)))

(defn gb-cleanup
  [a b c d]
  (->> (filter #(not (nil? %)) [a b c d])
       (str/join " ")
       str/upper-case))

(def postal-codes
  {"US" {:re #"(?x) (\d{5}) (?: [-+] \d{4} )?"}

   "RO" {:re #"(\d{6})"}

   "BR" {:re #"(\d{5}-\d{3})"}

   ;; https://en.wikipedia.org/wiki/Postal_codes_in_the_Netherlands
   "NL" {:re #"(?ix) (\d{4}) \s* ([a-rt-z][a-z] | s[bce-rt-z])"
         :cleanup nl-cleanup}
   
   ;; https://en.wikipedia.org/wiki/Postcodes_in_the_United_Kingdom#Validation
   "GB" {:re #"(?ix) (?: ([a-z][a-hj-y]?[0-9][a-z0-9]?) \s* ([0-9][a-z]{2}) |
                         (gir) \s* (0a{2}) )"
         :cleanup gb-cleanup}
   
   "AU" {:re #"(\d{4})"}

   "PH" {:re #"(\d{4})"}})

(defn find-postal-code
  [s postal-codes]
  (first (filter (fn [[_ {re :re}]]
                   (re-matches re s))
                 postal-codes)))

(defn- pc-chk-clean
  [s postal-codes]
  (when-let [[cc {:keys [re cleanup]}] (find-postal-code s postal-codes)]
    (let [[_ & groups] (re-matches re s)
          cleanup (or cleanup (partial str))]
      [cc (apply cleanup groups)])))

(defn chk-postal-code
  "Check postal codes, optionally qualified by CC.  Returns a vector of
  ISO country code and the canonical format of the postal code."
  ([s]    (pc-chk-clean s postal-codes))
  ([s cc] (pc-chk-clean s {cc (get postal-codes cc)})))
