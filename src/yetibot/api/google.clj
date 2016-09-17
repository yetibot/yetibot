(ns yetibot.api.google
  (:require
   [taoensso.timbre :refer [warn info]]
   [clojure.string :as s]
   [clojure.data.json :as json]
   [clj-http.client :as client]
   [yetibot.core.config :refer [conf-valid? config-for-ns]]))

(def config (config-for-ns))

(defn configured? []
  (let [config-keys   [:api-key :custom-search-engine-id]
        config-values (select-keys config config-keys)]
    (and (conf-valid? config)
       (= (count config-values) 2))))

(defonce api-url "https://www.googleapis.com/customsearch/v1?parameters")

;; this has yet to be used somewhere
(defonce keywords (atom {}))

;; validation and documentation
;; notes : sort needs to be improved upon
;; this has yet to be used somwhere
(defonce accepted-keywords
  {:q #".*", :c2coff #"0|1", :imgColorType #"mono|gray|color",
   :g1 #"[a-z]{2}", :googlehost #"google\.(com|[a-z]{2})"
   :imgDominantColor #"yellow|green|teal|blue|purple|pink|white|gray|black|brown",
   :imgSize #"clipart|face|lineart|news|photo", :filter #"0|1"
   :num #"\d|10", :safe #"high|medium|off", :searchType "image"
   :sort #".*", :start #"\d{1,3}", :siteSearch #".*", :siteSearchFilter #"e|i",
   :h1 #"[a-z]{2}|zh-Han[s|t]", :hq #".*",
   })

(defonce display-order {:normal [:title :link :snippet]
                        :image  [:title :snippet :link]})

;; format a single query result
(defn format-result
  [result & {:keys [order] :or {order :normal}}]
  (let [values (display-order order)]
    (->> (select-keys result values)
         (vals)
         (remove nil?)
         (s/join "\n"))))

;; map a vector of results to a vector
;; of string representations of the results
(defn format-results
  [result-body & {:keys [order] :or {order :normal}}]
    (let [result  (:items result-body)
          indexed (map-indexed #(vector (inc %1) %2) result)
          format  #(str (first %)
                        ". "
                        (format-result (second %) :order order)
                        "\n\n")]
        (map format indexed)))

(defn search
  ;; main search function,
  ;; extra refers to map of extra params to the search api
  ;; order refers to the the way result is display (look
  ;; at display-order var)
  [q & {:keys [extra order]
        :or {extra {} order :normal}}]
  (info "Google search for: " q)
  (let [query-params  {:q q
                       :key (:api-key config)
                       :cx (:custom-search-engine-id config)}
        options {:query-params
                 (merge query-params extra)}]
    (try
      (-> (client/get api-url options)
        (get :body)
        (json/read-json))
      (catch Exception e
        (warn "Google search returned a failure http status")
        (warn "Google: caught " e)
        "API Credentials are invalid || Google died"))))

(defn image-search [q]
  (let [param (select-keys accepted-keywords [:searchType])]
    (search q :extra param :order :image)))
