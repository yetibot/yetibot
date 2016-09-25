(ns yetibot.commands.google
  (:require
    [taoensso.timbre :refer [info]]
    [clojure.string :refer [join trim lower-case]]
    [yetibot.api.google :as api]
    [yetibot.core.hooks :refer [cmd-hook suppress]]))

(api/populate-options-from-config)

(def messages
  {:search "Google returned no results!!"
   :image (str "Google image search returned no results!! \n"
               ".. check your search engine settings")
   :google-died "API Credentials are invalid || Google died"
   :option-not-found "Option not found"
   :no-options-set "No options have been set."
   :reset "Options has been reset"
   })

(defn no-command-found
  [_]
  (str "Command does not exist."
       " Type in !help google for valid commands"))

(defn clean [str]
  (trim (lower-case str)))

(defn search
  "google search <query> # plain google search"
  [{[_ query] :match}]
  (let [results (api/search query)
        count (get-in results [:searchInformation
                               :totalResults])]
    (condp = count
      nil (:google-died messages)
      "0" (:search messages)
      (api/format-results (:items results)))))

(defn image-search
  "google image <query> # image search"
  [{[_ query] :match}]
  (let [results (api/image-search query)
        count (get-in results [:searchInformation
                               :totalResults])]
    (condp = count
      nil (:google-died messages)
      "0" (:image messages)
      (api/format-results (:items results) :order :image))))

(defn option-info
  "google option-info <query> #display info for option"
  [{[_ query] :match}]
  (if-let [info (api/get-info (clean query))]
    info
    (:option-not-found messages)))

(defn show-custom-keywords-list
  "google show options # show list of valid options"
  [_]
  (join ", " (api/valid-keywords-list)))

(defn set-option
  "google set <option> <value>"
  [{[_ option value] :match}]
  (if-let [message (api/set-option-value (clean option) value)]
    message
    (:option-not-found messages)))

(defn show-set-options
  "google show set options"
  [_]
  (if-let [options-set (api/state-of-set-options)]
    (->> options-set
         (map #(str " - " (first %) " has been set to " (second %)))
         (join "\n"))
    (:no-options-set messages)))

(defn reset-options
  "google reset options # current set options are vanquished"
  [_]
  (do
    (api/reset-options)
    (:reset messages)))

(if (api/configured?)
  (cmd-hook #"google"
            #"^search\s+(.+)" search
            #"^image\s+(.+)" image-search
            #"^option-info\s+(.+)" option-info
            #"^show\s+options$" show-custom-keywords-list
            #"^set\s+(\S+)\s+(\S+)" set-option
            #"^show\s+set\s+options$" show-set-options
            #"^reset\s+options$" reset-options
            #".+" no-command-found)
  (info "Google is not configured."))
