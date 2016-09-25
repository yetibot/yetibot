(ns yetibot.api.google
  (:require
   [taoensso.timbre :refer [warn info]]
   [clojure.string :as s]
   [clojure.data.json :as json]
   [clj-http.client :as client]
   [yetibot.core.config :refer [conf-valid? config-for-ns]]))


(defonce api-url "https://www.googleapis.com/customsearch/v1?parameters")

(defonce options (atom {}))

(def accepted-keywords {})
(declare set-option-value)

(defonce messages
  {:error-message "Values has to be one of the following: ",
   :no-options-in-config-file  "No google options set in config file."
   :option-not-found "Option not found"})

(defonce display-order {:normal [:title :link :snippet]
                        :image  [:title :snippet :link]})

;; this is a defn because accepted keywords
;; is redenfined again towards the bottom
(defn valid-keywords-list []
  (keys accepted-keywords))

(def config (config-for-ns))

(defn configured? []
  (let [config-keys   [:api-key :custom-search-engine-id]
        config-values (select-keys config config-keys)]
    (and (conf-valid? config)
       (= (count config-values) 2))))

(defn populate-options-from-config []
  "This loads the custom-engine options set
  in the config file and load it into the options
  atom"
  (let [options (:options config)]
    (if-not (seq options)
      (info (:no-options-in-config-file messages))
      (reduce-kv (fn [_ k v]
                   (-> (s/trim k)
                       (s/lower-case)
                       (set-option-value v)
                       (info))) [] options))))

(defn- fetch-option-if-exists
  [option]
  (get accepted-keywords option))

(defn- validate-option-value
  [option value]
  (if-let [option_ (fetch-option-if-exists option)]
    (re-matches (:validation option_) value)
    :option-does-not-exist))

(defn set-option-value
  "This is responsible for checking if
  a key exists, if it does, it validates the
  value given"
  [option value]
  (condp = (validate-option-value option value)
    :option-does-not-exist nil
    nil (get-in accepted-keywords [option :error-message])
    (do (swap! options
               assoc (get-in accepted-keywords [option :keyword]) value)
        (str option " successfully set to " value))))

(defn get-info
  "Gets info about an option"
  [option]
  (:info-message (fetch-option-if-exists option)))

(defn state-of-set-options []
  "gives back contents of the options
  atom. If empty gives back nil"
  (if-not (seq @options)
    nil
    @options))

(defn reset-options []
  (reset! options {}))

(defn format-result
  "format a single query result"
  [result & {:keys [order] :or {order :normal}}]
  (let [values (display-order order)]
    (->> (select-keys result values)
         (vals)
         (remove nil?)
         (s/join "\n"))))

(defn format-results
  "map a vector of results to a vector
  of string representations of the results"
  [results & {:keys [order] :or {order :normal}}]
    (let [indexed (map-indexed #(vector (inc %1) %2) results)
          format  #(str (first %)
                        ". "
                        (format-result (second %) :order order)
                        "\n\n")]
        (map format indexed)))

(defn search
  "main search function,
  extra refers to map of extra params to the search api
  order refers to the the way result is display (look
  at display-order var)" [q & {:keys [extra order] :or {extra {} order :normal}}]
  (info "Google search for: " q)
  (let [query-params  {:q q
                       :key (:api-key config)
                       :cx (:custom-search-engine-id config)}
        options {:query-params
                 (merge query-params extra @options)}]
    (do
      (info options)
    (try
      (-> (client/get api-url options)
        (get :body)
        (json/read-json))
      (catch Exception e
        (warn "Google search returned a failure http status")
        (warn "Google: caught " e)
        nil)))))

(defn image-search [q]
  (let [param {"searchType" "image"}]
    (search q :extra param :order :image)))

(def accepted-keywords
  {"c2coff"
    {:validation #"^0|1$"
     :error-message (str (:error-message messages) "0,1")
     :info-message "Enables or Disables Simplified and Traditional Chinese Search"
     :keyword "c2coff"},

   "imgcolortype"
    {:validation #"^mono|gray|color$",
     :error-message (str (:error-message messages) "mono, gray, color")
     :info-message "Returns mono, gray or color images for image-search"
     :keyword "imgColorType"},

   "cr"
    {:validation #"^country[A-Z]{2}(:?(:?\|\||&&)country[A-Z]{2})*$"
     :error-message "Syntax Error: Syntax should be like `countryAB`
                    or `countryAB&&countryZN`' "
     :info-message "This paramater restrict dsearch to documents originating
                   from a certain country"
     :keyword "cr"},

   "filter"
     {:validation #"^0|1$"
      :error-message (str (:error-message messages) "0,1")
      :info-message "Turns off or on duplicate content filter"
      :keyword "filter"},

   "g1"
    {:validation #"^[a-z]{2}$"
     :error-message "Invalid country code \n
                     Example of correct code: uk"
     :info-message "Specifies the geolocation of end-user \n
                   which would ideally result in location specific results"
     :keyword "g1"},
    })

  ;; :g1 #"[a-z]{2}", :googlehost #"google\.(com|[a-z]{2})"
  ;; :imgDominantColor #"yellow|green|teal|blue|purple|pink|white|gray|black|brown",
  ;; :imgSize #"clipart|face|lineart|news|photo", :filter #"0|1"
  ;; :num #"\d|10", :safe #"high|medium|off", :searchType "image"
  ;; :sort #".*", :start #"\d{1,3}", :siteSearch #".*", :siteSearchFilter #"e|i",
  ;; :h1 #"[a-z]{2}|zh-Han[s|t]", :hq #".*",
  ;; })
