(ns yetibot.commands.google
  (:require
    [taoensso.timbre :refer [info]]
    [clojure.string :refer [join trim lower-case split]]
    [clojure.tools.cli :refer [parse-opts]]
    [yetibot.api.google :as api]
    [yetibot.core.hooks :refer [cmd-hook suppress]]))

(defonce options (atom {}))

(defonce cli-args
  {:c2coff       [nil "--c2c CO"],      :imgcolortype ["-c" "--color COL"],
   :cr           ["-C" "--cr CR"],      :filter       ["-f" "--filter XF"],
   :g1           ["-g" "--g1 G1"],      :h1           [nil "--h1 H1"],
   :hq           ["-A" "--and AND"],    :daterestrict ["-d" "--date DA"],
   :excludeterms ["-x" "--exclude EX"], :exactterms   ["-e" "--exact EXAC"],
   :highrange    [nil "--hr HR"],       :lowrange     [nil "--lr LR"],
   :googlehost   [nil "--ghost GHT"],   :filetype     ["-F" " --ft FT"],
   :imgtype      ["-t" "--type TY"],    :imgsize      ["-z"  "--size SIZE"],
   :linksite     ["-l"  "--link LK"],   :safe         ["-o" "--safe SAFE"],
   :relatedsite  ["-r" "--relsite REL"],:sitesearch   ["-s" "--site SITE"],
   :rights       ["-i" "--license LI"], :sort         ["-q" "--sort SORT"],
   :num          ["-n" "--num NUM"],    :start        ["-y" "--start STA"],
   :imgdominantcolor [nil "--dominantcolor DOM"],
   :sitesearchfilter ["-v" "--sitefilter FIL"],
   :orterms          ["-O" "--or OR"],
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
   :additional-set-method "Can also be set via following flags: "
   })

(defn command-not-found [_]
  (:com-not-found messages))

(defn keyword->apikeyword
  [option]
  (get-in api/accepted-keywords [option :keyword]))

(defn state-of-set-options []
  "gives back contents of the options
  atom. If empty gives back nil"
  (if-not (seq @options)
    nil
    @options))

(defn fetch-option-if-exists
  [option]
  (get api/accepted-keywords option))

(defn validate-option-value
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
    nil (get-in api/accepted-keywords [option :error-message])
    (do (swap! options
               assoc (keyword->apikeyword option) value)
        (str option " successfully set to " value))))

(defn get-option-cli-info
  [option]
  (if-let [cli-arg (get cli-args option)]
    (->> (remove nil? cli-arg)
         (join ", "))))

(defn get-info
  "Gets info about an option.
  Also includes valid input info"
  [option]
  (if-let [value (fetch-option-if-exists option)]
    (join "\n"
          [(:info-message value)
           (:error-message value)
           (str (:additional-set-method messages)
                " "
                (get-option-cli-info option))])))

(defn convert-keys-into-google-keywords
  [obj]
  (reduce-kv (fn [m k v]
               (assoc m (keyword->apikeyword k) v)) {} obj))

(defn options-processor [string]
  (let [output (parse-opts (map trim (split string #" ")) built-cli-options)]
    (if-let [error-messages (:errors output)]
      {:errors error-messages}
      {:query (join " " (:arguments output))
       :args (convert-keys-into-google-keywords (:options output))})))

(defn common-messaging-interface
  [status message & {:keys [results]
                     :or {results nil}}]
  {:status status
   :message message
   :results results})

(defn search-function-input-validation
  [query]
  (let [proc-query (options-processor query)
        messagef common-messaging-interface]
    (cond
      (:errors proc-query) (messagef :failure (join "\n" (:errors proc-query)))
      (empty? (:query proc-query))
                           (messagef :failure (:empty-query messages))
      :else
      (messagef :success nil :results proc-query))))

(defn common-search-function
  [query & {:keys [sfunction type]
   :or {sfunction api/search type :normal}}]
  (let [validation-results (search-function-input-validation query)]
    (condp = (:status validation-results)
      :failure validation-results
      (let [messagef common-messaging-interface
            proc-query (:results validation-results)
            args (:args proc-query)
            results (sfunction (:query proc-query)
                               :args (merge @options args))
            count (get-in results [:searchInformation
                                   :totalResults])]
        (condp = count
          nil (messagef :failure (:google-died messages))
          "0" (if (= type :image)
                (messagef :failure (:image messages))
                (messagef :failure (:search messages)))
          (messagef :success nil :results results))))))

(defn load-options-from-file-into-atom
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

(defn search
  "google search <query> # plain google search"
  [{[_ query] :match}]
  (let [results (common-search-function query)]
    (if (= :failure (:status results))
      (:message results)
      (api/format-results (get-in results [:results :items])))))

(defn image-search
  "google image <query> # image search"
  [{[_ query] :match}]
  (let [results (common-search-function query
                                        :sfunction api/image-search
                                        :type :image)]
    (if (= :failure (:status results))
      (:message results)
      (api/format-results (get-in results [:results :items]) :order :image))))

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
  (suppress (reset! options copy-of-valid-file-options)))

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
