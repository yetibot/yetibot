(ns yetibot.models.postal-code
  (:require
   [clojure.string :as str]))

;;
;; Postal Code validation, qualification, and cleanup
;;

(defn- us-cleanup
  [zip plus4]
  (if (nil? plus4)
    zip
    (str zip "-" plus4)))

(defn- nl-cleanup
  [d s]
  (str d " " (str/upper-case s)))

(defn gb-cleanup
  [a b c d]
  (->> (filter #(not (nil? %)) [a b c d])
       (str/join " ")
       str/upper-case))

(def postal-codes
  {"US" {:re #"(?x) (\d{5}) (?: [-+] (\d{4}) )?"
         :cleanup us-cleanup}

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

(defn- pc-chk-clean
  [s postal-codes]
  (reduce-kv (fn [_ cc {:keys [:re :cleanup]}]
            (when-let [[_ & groups] (re-matches re s)]
              (let [cleanup (or cleanup (partial str))]
                (reduced [cc (apply cleanup groups)]))))
          nil
          postal-codes))

(defn chk-postal-code
  "Check postal codes, optionally qualified by CC.  Returns a vector of
  ISO country code and the canonical format of the postal code."
  ([s]    (pc-chk-clean s postal-codes))
  ([s cc] (pc-chk-clean s {cc (get postal-codes cc)})))
