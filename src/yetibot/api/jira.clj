(ns yetibot.api.jira
  (:require
    [clj-http.client :as client]
    [yetibot.config :refer [config-for-ns conf-valid?]]
    [yetibot.util.http :refer [get-json fetch]]))

(def config (config-for-ns))
(def configured? (conf-valid?))

(def base-uri (str "https://" (:domain config)))
(def ^:private api-uri (str base-uri "/rest/api/latest"))
(def ^:private auth (map config [:user :password]))
(def ^:private client-opts {:as :json :basic-auth auth :insecure? true})

(defn endpoint [& fmt-with-args]
  (str api-uri (apply format fmt-with-args)))

(defn get-transitions [i]
  (client/get (endpoint "/issue/%s/transitions?transitionId" i)
              client-opts))

(def ^:private find-resolve (partial filter #(= "Resolve Issue" (:name %))))

(defn- transition-issue [i transition-id comment]
  (let [params {:update {:comment [{:add {:body comment}}]}
                :fields {:resolution {:name "Fixed"}}
                :transition transition-id}]
    (client/post
      (endpoint "/issue/%s/transitions?transitionId" i)
      (merge client-opts
             {:form-params params :content-type :json}))))

(defn resolve-issue
  "Transition an issue to the resolved state. If it is unable to make that
   transition nil will be returned"
  [i comment]
  (let [ts (:body (get-transitions i))
        resolve-t (find-resolve (:transitions ts))]
    (when-let [t (first resolve-t)]
      (transition-issue i (:id t) comment))))

(defn get-issue
  "Fetch json for a given JIRA"
  [i]
  (let [uri (endpoint "/issue/%s" i)]
    (:body (client/get uri client-opts))))
