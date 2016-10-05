(ns yetibot.api.google
  (:require
   [taoensso.timbre :refer [info warn]]
   [clojure.string :as s]
   [clojure.data.json :as json]
   [clj-http.client :as client]
   [yetibot.core.config :refer [conf-valid? config-for-ns]]))

(defonce api-url "https://www.googleapis.com/customsearch/v1?parameters")

(defonce messages
  {:error-message "Values has to be one of the following: "})

(defonce display-order {:normal [:title :link :snippet]
                        :image  [:title :snippet :link]})

(def config (config-for-ns))

(defn configured? []
  (let [config-keys   [:api-key :custom-search-engine-id]
        config-values (select-keys config config-keys)]
    (and (conf-valid? config)
       (= (count config-values) 2))))

(defn populate-options-from-config []
  (:options config))

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
  args refer to map of extra params to the search api
  order refers to the the way result is display (look
  at display-order var)"
  [q & {:keys [order args] :or {args {} order :normal}}]
  (info "Google search for: " q)
  (let [query-params  {:q q
                       :key (:api-key config)
                       :cx (:custom-search-engine-id config)}
        options {:query-params
                 (merge query-params args)}]
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

(defn image-search [q & {:keys [args] :or {args {}}}]
  (let [param {"searchType" "image"}]
    (search q :order :image
              :args (merge args param))))

(def accepted-keywords
  {:c2coff
    {:validation #"^0|1$"
     :error-message (str (:error-message messages) "0,1")
     :info-message "Enables or Disables Simplified and Traditional Chinese Search"
     :keyword "c2coff"},

   :imgcolortype
    {:validation #"^mono|gray|color$",
     :error-message (str (:error-message messages) "mono, gray, color")
     :info-message "Returns mono, gray or color images for image-search"
     :keyword "imgColorType"},

   :cr
    {:validation #"^country[A-Z]{2}(:?(:?\|\||&&)country[A-Z]{2})*$"
     :error-message
     "Examples of valid input are `countryAB' or `countryAB&&countryZN'"
     :info-message
     "This parameter restricts search to documents originating from a certain country"
     :keyword "cr"},

   :filter
     {:validation #"^0|1$"
      :error-message (str (:error-message messages) "0,1")
      :info-message "Turns off or on duplicate content filter"
      :keyword "filter"},

   :g1
    {:validation #"^[a-z]{2}$"
     :error-message "Example of correct input: uk"
     :info-message
     (str "Specifies the geolocation of end-user \n"
          "which would ideally result in location specific results")
     :keyword "g1"},

   :h1
    {:validation #"^[a-z]{2}|zh-Han[s|t]$"
     :error-message "Valid example is 'fr' for french"
     :info-message
     "Specifies the language for the search interface and improves search results."
     :keyword "h1"
    },

   :hq
    {:validation #".+"
     :error-message "Valid input is a non empty string"
     :info-message "Appends option value to query similar to an AND operator \n"
     :keyword "hq"
     },

   :daterestrict
    {:validation #"^(?:d|w|m|y)\d+$"
     :error-message
     (str "Valid values are of format (d|w|m|y)<number>\n"
          "d,w,m,y refers to days, weeks, months and years")
     :info-message
     "Restricts results to URLS based on date"
     :keyword "dateRestrict"
    },

   :excludeterms
    {:validation #".+"
     :error-message "Valid input is a non empty string"
     :info-message "Identifies a word/phrase that should'nt appear in results\n"
     :keyword "excludeTerms"
    },

   :exactterms
    {:validation #".+"
     :error-message "Valid input is a non empty string"
     :info-message "Identifies a word/phrase that should appear in results\n"
     :keyword "exactTerms"
    },

   :highrange
    {:validation #"^\d+$"
     :error-message "Valid input is a number."
     :info-message  "Specifies the ending valude for a search range"
     :keyword "highRange"
     },

   :lowrange
    {:validation #"^\d+$"
     :error-message "Valid input is a number."
     :info-message "Specifies the ending valude for a search range"
     :keyword "lowRange"
    },

   :googlehost
    {:validation #"^google\.(?:com|[a-z]{2})$"
     :error-message "Valid values include 'google.de', 'google.fr'"
     :info-message "Specifies local google domain to perform the search"
     :keyword "keyword"
     },

   :filetype
    {:validation #"^\.\S+$"
     :error-message "Valid values include '.pdf', '.html', '.clj' etc"
     :info-message "Restricts results to files of a specific extension"
     :keyword "fileType"
     },

   :imgdominantcolor
    {:validation #"^black|blue|brown|gray|green|pink|purple|teal|white|yellow$",
     :error-message (str (:error-message messages)
                         "black, blue, brown, gray, green, pink, purple, teal,"
                         "white, yellow")
     :info-message "Returns images of a specific dominant color"
     :keyword "imgDominantColor"},

   :imgtype
    {:validation ,#"^clipart|face|lineart|news|photo$"
     :error-message (str (:error-message messages)
                       "clipart, face, lineart, news, photo")
     :info-message "Returns images of a specific type"
     :keyword "imgType"},

   :imgsize
    {:validation ,#"^huge|icon|large|medium|small|xlarge|xxlarge$"
     :error-message (str (:error-message messages)
                         "huge, icon, large, medium, small,"
                         "xlarge, xxlarge")
     :info-message "Returns images of a specific size"
     :keyword "imgSize"},

   :linksite
    {:validation ,#".*"
     :error-message "Valid input is a website link"
     :info-message
     "Specifies all search results should contain a link to a specific site"
     :keyword "linkSite"},

   :safe
    {:validation ,#"^high|medium|off$"
     :error-message (str (:error-message messages) "high, medium, off")
     :info-message "Search Safety Level"
     :keyword "safe"},

   :relatedsite
    {:validation #".*"
     :error-message "Valid input is a website link"
     :info-message
     "Specifies all search results should be pages related to a url"
     :keyword "relatedSite"},

   :sitesearch
    {:validation #".*"
     :error-message "Valid input is a website link"
     :info-message
     "Specifies all search results should be pages from a given site"
     :keyword "siteSearch"},

   :sitesearchfilter
    {:validation #"^e|i$"
     :error-message  (str (:error-message messages) "e, i")
     :info-message
     "includes/excludes results from site in siteSearch param"
     :keyword "siteSearchFilter"},

   :rights
    {:validation #".*"
     :error-message (str "Supported values include: "
                         "cc_publicdomain, cc_attribute, cc_sharealike,"
                         "cc_noncommercial, cc_nonderived"
                         ", and combinations of these.")
     :info-message "Filter based on licensing"
     :keyword "rights"},

   :sort
    {:validation #".*"
     :error-message "Valid input is a nonempty string"
     :info-message "The sort expression to apply to filters"
     :keyword "sort"},

   :num
    {:validation #"^\d|10$"
     :error-message "Valid input is a number between 1 to 10"
     :info-message "Number of search results to return"
     :keyword "num"},

   :start
    {:validation #"^\d+$"
     :error-message "Valid input is a number"
     :info-message "The index of the first result to return"
     :keyword "start"},

   :orterms
    {:validation #".+"
     :error-message "Valid input is a non empty string"
     :info-message "Appends option value to query similar to an OR operator "
     :keyword "orTerms"
     }})
