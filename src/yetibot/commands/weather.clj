(ns yetibot.commands.weather
  (:require
    [schema.core :as sch]
;;    [clojure.string :refer [join]]
    [clojure.string :as str]
    [yetibot.core.util.http :refer [get-json fetch encode map-to-query-string]]
    [taoensso.timbre :refer [info warn error]]
    [yetibot.core.config :refer [get-config]]
    [yetibot.core.hooks :refer [cmd-hook]]))

;; (def config (:value (get-config sch/Any [:weather :weatherbitio])))

;; (def api-key (:key config))
;; (def default-zip (-> config :default :zip))

;; (defn endpoint [repr q]
;;   (format "https://api.weatherbit.io/v2.0/%s?key=%s&units=I&%s" repr api-key q))

;; (defn- current [loc]
;;   (let [url (endpoint "current" (format "city=%s" (encode loc)))]
;;     (get-json url)))

;; (defn- error-response [c] (:error c))

;; (defn- format-current [c]
;;   (when (contains? c :data)
;;     (let [loc (-> c :data (get 0))]
;;       [(format "Current conditions for %s:" (:city_name loc))
;;        (format "%s, %s" (:temp loc) (-> loc :weather :description))
;;        (format "Feels like %s" (:app_temp loc))
;;        (format "Wind: %s from the %s" (:wind_spd loc) (:wind_cdir_full loc))
;;        (format "Precip: %s" (or (:precip loc) "none"))
;;        ])))

;; (defn weather-cmd
;;   "weather <location> # look up current weather for <location>"
;;   {:yb/cat #{:info}}
;;   [{:keys [match]}]
;;   (let [cs (current match)]
;;     (or
;;       (error-response cs)
;;       (format-current cs))))

;; (defn default-weather-cmd
;;   "weather # look up weather for default location"
;;   {:yb/cat #{:info}}
;;   [_] (weather-cmd {:match default-zip}))

;; (cmd-hook ["weather" #"^weather$"]
;;           #".+" weather-cmd
;;           _ default-weather-cmd)


;;
;; Postal Code processing
;;
;; Perhaps this should live elsewhere?
;;

;; Which Postal Codes to start with:
;; USA, Romania, Brazil, The Netherlands, Hong Kong
;; https://en.wikipedia.org/wiki/List_of_countries_by_English-speaking_population

(def postal-codes
  (vector
   {:cc "US" :re #"(?x) (\d{5}) (?:[-+]\d{4})?" :cleanup identity}
   {:cc "RO" :re #"(\d{6})" :cleanup str/upper-case}))

(defn chk-postal-code
  [s]
  (reduce (fn [match {:keys [:cc :re :cleanup]}]
            (when-let [[_ & groups] (re-matches re s)]
              (let [cleanup (or cleanup identity)]
                (reduced [cc (apply str groups)]))))
          {}
          postal-codes))

