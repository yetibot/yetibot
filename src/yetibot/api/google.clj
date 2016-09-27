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
   :option-not-found "Option not found"
   :extra-set "Can also be set via %s as an extra arg to search command"})

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
     :extra (format (:extra-set messages) "--c2c")
     :keyword "c2coff"},

   "imgcolortype"
    {:validation #"^mono|gray|color$",
     :error-message (str (:error-message messages) "mono, gray, color")
     :info-message "Returns mono, gray or color images for image-search"
     :extra (format (:extra-set messages) "-c, --color")
     :keyword "imgColorType"},

   "cr"
    {:validation #"^country[A-Z]{2}(:?(:?\|\||&&)country[A-Z]{2})*$"
     :error-message
     "Examples of valid input are `countryAB' or `countryAB&&countryZN'"
     :info-message
     "This parameter restricts search to documents originating from a certain country"
     :extra (format (:extra-set messages) "-C, --cr")
     :keyword "cr"},

   "filter"
     {:validation #"^0|1$"
      :error-message (str (:error-message messages) "0,1")
      :info-message "Turns off or on duplicate content filter"
      :extra (format (:extra-set messages) "-f, --filter")
      :keyword "filter"},

   "g1"
    {:validation #"^[a-z]{2}$"
     :error-message "Example of correct input: uk"
     :info-message
     (str "Specifies the geolocation of end-user \n"
          "which would ideally result in location specific results")
     :extra (format (:extra-set messages) "-g, --g1")
     :keyword "g1"},

   "h1"
    {:validation #"^[a-z]{2}|zh-Han[s|t]$"
     :error-message "Valid example is 'fr' for french"
     :info-message
     "Specifies the language for the search interface and improves search results."
     :extra (format (:extra-set messages) "--h1")
     :keyword "h1"
    },

   "hq"
    {:validation #".+"
     :error-message "Valid input is a non empty string"
     :info-message "Appends option value to query similar to an AND operator \n"
     :extra (format (:extra-set messages) "-A,--and")
     :keyword "hq"
     },

   "daterestrict"
    {:validation #"^(?:d|w|m|y)\d+$"
     :error-message
     (str "Valid values are of format (d|w|m|y)<number>\n"
          "d,w,m,y refers to days, weeks, months and years")
     :info-message
     "Restricts results to URLS based on date"
     :extra (format (:extra-set messages) "-d, --date")
     :keyword "dateRestrict"
    },

   "excludeterms"
    {:validation #".+"
     :error-message "Valid input is a non empty string"
     :info-message "Identifies a word/phrase that should'nt appear in results\n"
     :extra (format (:extra-set messages) "-x, --exclude")
     :keyword "excludeTerms"
    },

   "exactterms"
    {:validation #".+"
     :error-message "Valid input is a non empty string"
     :info-message "Identifies a word/phrase that should appear in results\n"
     :extra (format (:extra-set messages) "-e, --exact")
     :keyword "exactTerms"
    },

   "highrange"
    {:validation #"^\d+$"
     :error-message "Valid input is a number."
     :info-message  "Specifies the ending valude for a search range"
     :extra (format (:extra-set messages) "--hr")
     :keyword "highRange"
     },

   "lowrange"
    {:validation #"^\d+$"
     :error-message "Valid input is a number."
     :info-message "Specifies the ending valude for a search range"
     :extra (format (:extra-set messages) "--lr")
     :keyword "lowRange"
    },

   "googlehost"
    {:validation #"^google\.(?:com|[a-z]{2})$"
     :error-message "Valid values include 'google.de', 'google.fr'"
     :info-message "Specifies local google domain to perform the search"
     :extra (format (:extra-set messages) "--ghost")
     :keyword "keyword"
     },

   "filetype"
    {:validation #"^\.\S+$"
     :error-message "Valid values include '.pdf', '.html', '.clj' etc"
     :info-message "Restricts results to files of a specific extension"
     :extra (format (:extra-set messages) "-F, --ft")
     :keyword "fileType"
     },

   "imgdominantcolor"
    {:validation #"^black|blue|brown|gray|green|pink|purple|teal|white|yellow$",
     :error-message (str (:error-message messages)
                         "black, blue, brown, gray, green, pink, purple, teal,"
                         "white, yellow")
     :info-message "Returns images of a specific dominant color"
     :extra (format (:extra-set messages) "--dominantcolor")
     :keyword "imgDominantColor"},

   "imgtype"
    {:validation ,#"^clipart|face|lineart|news|photo$"
     :error-message (str (:error-message messages)
                       "clipart, face, lineart, news, photo")
     :info-message "Returns images of a specific type"
     :extra (format (:extra-set messages) "-t, --type")
     :keyword "imgType"},

   "imgsize"
    {:validation ,#"^huge|icon|large|medium|small|xlarge|xxlarge$"
     :error-message (str (:error-message messages)
                         "huge, icon, large, medium, small,"
                         "xlarge, xxlarge")
     :info-message "Returns images of a specific size"
     :extra (format (:extra-set messages) "-z, --size")
     :keyword "imgSize"},

   "linksite"
    {:validation ,#".*"
     :error-message "Valid input is a website link"
     :info-message
     "Specifies all search results should contain a link to a specific site"
     :extra (format (:extra-set messages) "-l, --link")
     :keyword "linkSite"},

   "safe"
    {:validation ,#"^high|medium|off$"
     :error-message (str (:error-message messages) "high, medium, off")
     :info-message "Search Safety Level"
     :extra (format (:extra-set messages) "-o, --safe")
     :keyword "safe"},

   "relatedsite"
    {:validation #".*"
     :error-message "Valid input is a website link"
     :info-message
     "Specifies all search results should be pages related to a url"
     :extra (format (:extra-set messages) "-r, --relsite")
     :keyword "relatedSite"},

   "sitesearch"
    {:validation #".*"
     :error-message "Valid input is a website link"
     :info-message
     "Specifies all search results should be pages from a given site"
     :extra (format (:extra-set messages) "-s, --site")
     :keyword "siteSearch"},

   "sitesearchfilter"
    {:validation #"^e|i$"
     :error-message  (str (:error-message messages) "e, i")
     :info-message
     "includes/excludes results from site in siteSearch param"
     :extra (format (:extra-set messages) "-t, --sitefilter")
     :keyword "siteSearchFilter"},

   "rights"
    {:validation #".*"
     :error-message (str "Supported values include: "
                         "cc_publicdomain, cc_attribute, cc_sharealike,"
                         "cc_noncommercial, cc_nonderived"
                         ", and combinations of these.")
     :info-message "Filter based on licensing"
     :extra (format (:extra-set messages) "-i, --license")
     :keyword "rights"},

   "sort"
    {:validation #".*"
     :error-message "Valid input is a nonempty string"
     :info-message "The sort expression to apply to filters"
     :extra (format (:extra-set messages) "-q, --sort")
     :keyword "sort"},

   "num"
    {:validation #"^\d|10$"
     :error-message "Valid input is a number between 1 to 10"
     :info-message "Number of search results to return"
     :extra (format (:extra-set messages) "-n, --num")
     :keyword "num"},

   "start"
    {:validation #"^\d+$"
     :error-message "Valid input is a number"
     :info-message "The index of the first result to return"
     :extra (format (:extra-set messages) "-y, --start")
     :keyword "start"},

   "orterms"
    {:validation #".+"
     :error-message "Valid input is a non empty string"
     :info-message "Appends option value to query similar to an OR operator "
     :extra (format (:extra-set messages) "-O,--or")
     :keyword "orTerms"
     }})
