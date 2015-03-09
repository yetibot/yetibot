(ns yetibot.models.jenkins
  (:require
    [yetibot.core.chat]
    [yetibot.core.util.http :refer [get-json fetch]]
    [clojure.string :as s]
    [clj-time.coerce :as c]
    [taoensso.timbre :refer [info warn error]]
    [yetibot.core.config :refer [get-config conf-valid? update-config remove-config]]
    [clojure.core.memoize :as memo]))

(defonce instance-root-data (atom {}))

(def config (partial get-config :yetibot :models :jenkins))
(defn configured? [] (conf-valid? (config)))
(def cache-ttl (get-config :yetibot :models :jenkins :cache-ttl))

; Helpers

(defn instances [] (-> (config) :instances))
(defn- auth-for [instance]
  {:user (:user instance)
   :password (:api-key instance)
   :preemptive true})

(defn setup-instance-pairs
  "If instance name key doesn't already exist, assoc it with its value set to
   a ttl-memoized function that fetches itself."
  []
  (doseq [[inst-name inst] (instances)]
    (when-not (inst-name @instance-root-data)
      (swap!
        instance-root-data
        assoc
        inst-name
        {:config inst
         :fetcher (-> (fn []
                        (let [uri (format "%s/api/json" (:uri inst))]
                          (get-json uri (auth-for inst))))
                      (memo/ttl :ttl/threshold cache-ttl))}))))

(defn prime-memos
  "Executes all the fetcher functions in parallel in order to prime their memo
   caches."
  []
  (setup-instance-pairs)
  (doall
    (pmap
      (fn [[inst-name inst]]
        (try
          (when-let [instance-info (inst-name @instance-root-data)]
            ((:fetcher instance-info)))
          (catch Exception e
            (warn "Unable to load info for Jenkins instance" inst-name e))))
      (instances))))

(defonce load-caches (prime-memos))

; Config writer

(defn add-instance [inst-name uri user api-key]
  (let [inst-key (keyword inst-name)]
    (update-config :yetibot :models :jenkins :instances inst-key
                   {:uri uri
                    :user user
                    :api-key api-key}))
  (prime-memos))

(defn remove-instance [inst-name]
  (let [inst-key (keyword inst-name)
        exists? (inst-key @instance-root-data)]
    (when exists?
      (remove-config :yetibot :models :jenkins :instances inst-key)
      (swap! instance-root-data dissoc inst-key)
      true)))

; Getters

(defn instance-data [inst-name]
  ((keyword inst-name) @instance-root-data))

(defn jobs-for-instance [inst-name]
  (let [id ((:fetcher (instance-data inst-name)))]
    (-> id :jobs)))

(defn jobs-to-info []
  (some->> (map (fn [[inst-key inst-info]]
                  (let [data ((:fetcher inst-info))]
                    (map (fn [job]
                           {(:name job) (merge {:job-name (:name job)} inst-info)})
                         (:jobs data))))
                @instance-root-data)
           flatten
           (reduce conj)))

(defn job-names []
  (if-not (empty? @instance-root-data)
    (keys (jobs-to-info))
    []))

(defn resolve-job-info [job-to-match]
  (let [p (re-pattern job-to-match)
        matches (filter (fn [[job-name inst-info]] (re-find p job-name))
                        (jobs-to-info))]
    (if (empty? matches)
      nil
      (first matches))))

(defn default-job []
  (when-let [[_ inst-info] (first (filter (fn [[inst-k inst-info]]
                                            (let [d (-> inst-info :config :default-job)]
                                              (not (s/blank? d))))
                                          @instance-root-data))]
    (resolve-job-info (-> inst-info :config :default-job))))

; API calls

(defn status [[job-name job-info]]
  (let [base-uri (-> job-info :config :uri)
        uri (str base-uri "job/" job-name "/lastBuild/api/json")]
    (prn "get status from uri" uri)
    (get-json uri (auth-for (:config job-info)))))

(defn job-status [[job-name job-info]]
  "Sends job status info to chat. Sample output:
   SUCCESS at Wed Nov 16. Started by trevor.
   Currently building? false
   [Changeset]"
  (try
    (let [json (status [job-name job-info])]
      (if-let [building (str (:building json))] ; convert to string so a `false` doesn't give a false-negative
        (let [result (str (:result json))
              changeset (s/join
                          \newline
                          (map (fn [i]
                                 (let [msg (:msg i)
                                       author (if (seq (:author i))
                                                (:fullName (:author i)) ; git
                                                (:user i))]; svn
                                   (str author ": " msg)))
                               (:items (:changeSet json))))]
          [(:url json)
           (str
             (if result (str result " at "))
             (c/to-date (c/from-long (:timestamp json)))
             ". "
             (:shortDescription (first (:causes (first (:actions json)))))
             ".")
           (str "Currently building: " building)
           ""
           changeset])))
    (catch Exception _
      (format "Couldn't fetch status for %s. Perhaps it has no build history?" job-name))))

(defn report-job-url [[job-name job-info]]
  ; Wait a few seconds before requesting the job url so that Jenkins has time to
  ; actually start the build. Otherwise, the previous build url will be reported
  ; instead of the latest.
  (Thread/sleep 8000)
  (let [json (status [job-name job-info])]
    (yetibot.core.chat/chat-data-structure (:url json))))

(defn build-job [[job-name job-info]]
  (let [base-uri (-> job-info :config :uri)
        uri (format "%sjob/%s/build" base-uri job-name)
        response (fetch uri (auth-for (:config job-info)))]
    (future (report-job-url [job-name job-info]))
    (str "Starting build on " job-name)))
