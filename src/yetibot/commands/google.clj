(ns yetibot.commands.google
  (:require
    [taoensso.timbre :refer [info]]
    [clojure.string :refer [join trim lower-case split]]
    [clojure.tools.cli :refer [parse-opts]]
    [yetibot.api.google :as api]
    [yetibot.core.hooks :refer [cmd-hook suppress]]))

(defonce options (atom {}))

(defonce cli-args
  {:c2coff       [nil "--c2c"],     :imgcolortype ["-c" "--color"],
   :cr           ["-C" "--cr"],     :filter       ["-f" "--filter"],
   :g1           ["-g" "--g1"],     :h1           [nil "--h1"],
   :hq           ["-A" "--and"],    :daterestrict ["-d" "--date"],
   :excludeterms ["-x" "--exclude"],:exactterms   ["-e" "--exact"],
   :highrange    [nil "--hr"],      :lowrange     [nil "--lr"],
   :googlehost   [nil "--ghost"],   :filetype     ["-F" " --ft"],
   :imgtype      ["-t" "--type"],   :imgsize      ["-z"  "--size"],
   :linksite     ["-l"  "--link"],  :safe         ["-o" "--safe"],
   :relatedsite  ["-r" "--relsite"],:sitesearch   ["-s" "--site"],
   :rights       ["-i" "--license"],:sort         ["-q" "--sort"],
   :num          ["-n" " --num"],   :start        ["-y" "--start"],
   :imgdominantcolor [nil "--dominantcolor"],
   :sitesearchfilter ["-t" "--sitefilter"],
   :orterms          ["-O" "--or"],
  })

(defonce valid-keywords-list
  (keys cli-args))

(defonce built-cli-options
  (reduce-kv
   (fn [m k v]
     (conj m (conj (k cli-args) (:info-message v)
                   :id k
                   :validate [#(re-matches (:validation v) %)
                              (:error-message v)])))
   [] api/accepted-keywords))

(def messages
  {:search "Google returned no results!!"
   :image (str "Google image search returned no results!! \n"
               ".. check your search engine settings")
   :google-died "API Credentials are invalid || Google died"
   :option-not-found "Option not found"
   :no-options-set "No options have been set."
   :reset "Options has been reset"
   :empty-query "Query cannot be left empty"
   :invalid-args "Check options given"
   :com-not-found  (str "Command does not exist."
                        " Type in !help google for valid commands")
   :no-options-in-config-file  "No google options set in config file."
   })

(defn command-not-found [_]
  (:com-not-found messages))

(defn- state-of-set-options []
  "gives back contents of the options
  atom. If empty gives back nil"
  (if-not (seq @options)
    nil
    @options))

(defn- fetch-option-if-exists
  [option]
  (get api/accepted-keywords option))

(defn- validate-option-value
  [option value]
  (if-let [option_ (fetch-option-if-exists option)]
    (re-matches (:validation option_) value)
    :option-does-not-exist))

(defn- set-option-value
  "This is responsible for checking if
  a key exists, if it does, it validates the
  value given"
  [option value]
  (condp = (validate-option-value option value)
    :option-does-not-exist nil
    nil (get-in api/accepted-keywords [option :error-message])
    (do (swap! options
               assoc option value)
        (str option " successfully set to " value))))

(defn get-info
  "Gets info about an option"
  [option]
  (if-let [value (fetch-option-if-exists option)]
    (:info-message value)))

(defn convert-keys-into-google-keywords
  [obj]
  (reduce-kv (fn [m k v]
               (assoc m
                      (get-in api/accepted-keywords [k :keyword]) v)) {}))

(defn options-processor [string]
  (let [output (parse-opts string built-cli-options)]
    (if-let [error-messages (:errors output)]
      {:errors error-messages}
      {:query (join " " (:arguments output))
       :args (convert-keys-into-google-keywords (:options output))})))

(defn common-search-function
  [query & {:keys [order sfunction]
   :or {order :normal sfunction api/search}}]
  (let [proc-query (options-processor query)]
    (cond
      (empty? (:query proc-query)) (:empty-query messages)
      (:errors proc-query) (join "\n" (:errors proc-query))
      :else
      (let [args (:args proc-query)
            results (sfunction (:query proc-query)
                               :args (merge @options args))
            count (get-in results [:searchInformation
                                   :totalResults])]
        (condp = count
          nil (:google-died messages)
          "0" (:search messages)
          (api/format-results (:items results) :order order))))))

(defn search
  "google search <query> # plain google search"
  [{[_ query] :match}]
  ()
  (common-search-function query))

(defn image-search
  "google image <query> # image search"
  [{[_ query] :match}]
  (common-search-function query
                          :sfunction api/image-search
                          :order :image))

(defn- load-options-from-file-into-atom
  []
  (let [options  (api/populate-options-from-config)]
    (if (empty? options)
      (info (:no-options-in-config-file messages))
      (doall (map (fn [[k v]]
                    (if-let [result (set-option-value k v)]
                      (info result)
                      (info k ":" (:option-not-found messages))))
                  options)))))

(load-options-from-file-into-atom)
(defonce copy-of-valid-file-options
  @options)

;; stay
(defn option-info
  "google option-info <query> #display info for option"
  [{[_ query] :match}]
  (if-let [info (get-info (keyword query))]
    info
    (:option-not-found messages)))

(defn show-custom-keywords-list
  "google show options # show list of valid options"
  [_]
  (join ", " (map name valid-keywords-list)))

(defn set-option
  "google set <option> <value>"
  [{[_ option value] :match}]
  (if-let [message (set-option-value (keyword option) value)]
    message
    (:option-not-found messages)))

(defn show-set-options
  "google show set options"
  [_]
  (if-let [options-set (state-of-set-options)]
    (->> options-set
         (map #(str " - " (first %) " has been set to " (second %)))
         (join "\n"))
    (:no-options-set messages)))

(defn reset-options
  "google reset options # current set options are vanquished"
  [_]
  (do
    (reset! options {})
    (:reset messages)))

(defn restore-options
  "google restore options # restore from file config"
  [_]
  (reset! options copy-of-valid-file-options))

(if (api/configured?)
  (cmd-hook #"google"
            #"^search\s+(.+)" search
            #"^image\s+(.+)" image-search
            #"^option-info\s+(.+)" option-info
            #"^show\s+options$" show-custom-keywords-list
            #"^set\s+(\S+)\s+(\S+)" set-option
            #"^show\s+set\s+options$" show-set-options
            #"^reset\s+options$" reset-options
            #"^restore\s+options$" restore-options
            #".+" command-not-found)
  (info "Google is not configured."))
