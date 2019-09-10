(ns yetibot.models.jenkins
  (:require
    [yetibot.core.chat :refer [chat-data-structure]]
    [yetibot.core.util.http :refer [get-json fetch]]
    [clojure.spec.alpha :as s]
    [clojure.string :as string]
    [clj-time.coerce :as c]
    [clj-http.client :as client]
    [taoensso.timbre :refer [info warn error]]
    [yetibot.core.config :refer [get-config]]
    [clojure.core.memoize :as memo]))

(s/def ::ttl string?)

(s/def ::cache (s/keys :req-un [::ttl]))

(s/def ::name string?)

(s/def ::uri string?)

(s/def ::job string?)

(s/def ::default (s/keys :req-un [::job]))

(s/def ::user string?)

(s/def ::apikey string?)

(s/def ::instance (s/keys :req-un [::name ::uri]
                          :opt-un [::default ::user ::apikey]))

(s/def ::instances (s/coll-of ::instance :kind vector?))

(s/def ::config (s/keys :req-un [::cache ::instances]))

;; periodically refreshed data about Jenkins instances
(defonce instance-root-data (atom {}))

(defn config [] (:value (get-config ::config [:jenkins])))

(defn cache-ttl [] (-> (config) :cache :ttl read-string))

; Helpers

(defn instances [] (:instances (config)))

(defn- auth-for [instance]
  (when (and (:user instance) (:apikey instance))
    {:user (:user instance)
     :password (:apikey instance)
     :preemptive true}))

(defn setup-instance-pairs!
  "If instance name key doesn't already exist, assoc it with its value set to
   a ttl-memoized function that fetches itself."
  []
  (doseq [{inst-name :name :as inst} (instances)]
    (info "setu" inst-name inst)
    (when-not (get @instance-root-data inst-name)
      (swap!
        instance-root-data
        assoc
        inst-name
        {:config inst
         :fetcher (-> (fn []
                        (let [uri (format "%s/api/json" (:uri inst))
                              auth (auth-for inst)]
                          (if auth
                            (get-json uri auth)
                            (get-json uri))))
                      (memo/ttl :ttl/threshold (cache-ttl)))}))))

(defn prime-memos!
  "Executes all the fetcher functions in parallel in order to prime their memo
   caches."
  []
  (setup-instance-pairs!)
  (doall
    (pmap
      (fn [{inst-name :name}]
        (try
          (info inst-name)
          (when-let [instance-info (get @instance-root-data inst-name)]
            ((:fetcher instance-info)))
          (catch Exception e
            (warn "Unable to load info for Jenkins instance" inst-name e))))
      (instances))))

(defonce load-caches (prime-memos!))

; Getters

(defn instance-data [inst-name]
  ((keyword inst-name) @instance-root-data))

(defn jobs-for-instance [inst-name]
  (let [id ((:fetcher (instance-data inst-name)))]
    (:jobs id)))

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
    (first matches)))

(defn default-job []
  (when-let [[_ inst-info] (first (filter (fn [[inst-k inst-info]]
                                            (let [d (-> inst-info :config :default-job)]
                                              (not (string/blank? d))))
                                          @instance-root-data))]
    (resolve-job-info (-> inst-info :config :default-job))))

; API calls

(defn status [[job-name job-info]]
  (let [base-uri (-> job-info :config :uri)
        uri (str base-uri "job/" job-name "/lastBuild/api/json")]
    (info "get status from uri" uri)
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
              changeset (string/join
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
    (chat-data-structure (:url json))))

(defn build-job [[job-name job-info]]
  (let [base-uri (-> job-info :config :uri)
        uri (format "%sjob/%s/build" base-uri job-name)
        auth (when-let [{user :user pass :password} (auth-for (:config job-info))]
               [user pass])
        response (client/post uri {:basic-auth auth})]
    (info "build job response" response)
    (future (report-job-url [job-name job-info]))
    (str "Starting build on " job-name)))
