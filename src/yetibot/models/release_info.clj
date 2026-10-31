(ns yetibot.models.release-info
  "Public build identity and curated changes, frozen with the running code."
  (:require [cheshire.core :as json]
            [clj-http.client :as http]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as string])
  (:import [java.io ByteArrayOutputStream InputStream]
           [java.time LocalDate]
           [java.util Properties]))

(def latest-url "https://yetibot.com/releases/latest.json")
(def max-manifest-bytes (* 512 1024))

(defn- invalid! []
  (throw (ex-info "Invalid release information or options." {})))

(defn- text? [value maximum]
  (and (string? value) (<= 1 (count value) maximum)
       (not (string/blank? value)) (not (re-find #"\p{Cntrl}" value))))

(defn- date? [value]
  (and (string? value) (re-matches #"[0-9]{4}-[0-9]{2}-[0-9]{2}" value)
       (try (LocalDate/parse value) true (catch Exception _ false))))

(defn- build-entry [component version]
  (when (and (string? version)
             (re-matches #"[0-9][0-9A-Za-z._+-]{0,127}" version))
    (let [revision (second (re-matches
                            #"[0-9]{8}\.[0-9]{6}\.([0-9a-f]{7,40})"
                            version))]
      {:version version :revision revision
       :commit_url (when revision
                     (str "https://github.com/yetibot/"
                          (if (= component :app) "yetibot" "core")
                          "/commit/" revision))})))

(defn read-build-info
  "Read the actual Maven resources, not mutable project.clj or Git state."
  []
  (into {}
    (for [[component artifact] [[:app "yetibot"] [:core "core"]]]
      [component
       (try
         (when-let [resource (io/resource
                              (str "META-INF/maven/yetibot/" artifact
                                   "/pom.properties"))]
           (with-open [stream (io/input-stream resource)]
             (let [properties (doto (Properties.) (.load stream))]
               (build-entry component (.getProperty properties "version")))))
         (catch Exception _ nil))])))

(defn- texts? [values maximum]
  (and (vector? values) (<= (count values) 5)
       (every? #(text? % maximum) values)))

(defn- change? [change]
  (and (map? change)
       (= (set (keys change))
          #{:id :date :component :title :summary :commands :requires :links})
       (string? (:id change))
       (re-matches #"[a-z0-9][a-z0-9-]{0,79}" (:id change))
       (date? (:date change))
       (#{"app" "core"} (:component change))
       (text? (:title change) 120) (text? (:summary change) 800)
       (texts? (:commands change) 240) (texts? (:requires change) 240)
       (texts? (:links change) 200)
       (every? #(re-matches
                  #"https://github\.com/yetibot/(?:yetibot|core)/(?:pull/[0-9]+|commit/[0-9a-f]{7,40})"
                  %) (:links change))))

(defn validate-manifest!
  "The same small public schema is used for bundled and published manifests."
  [manifest]
  (when-not
    (and (map? manifest) (= 1 (:schema_version manifest))
         (= #{:schema_version :build :changes} (set (keys manifest)))
         (map? (:build manifest))
         (= #{:app :core} (set (keys (:build manifest))))
         (every? (fn [[component entry]]
                   (or (nil? entry)
                       (and (map? entry)
                            (= entry (build-entry component (:version entry))))))
                 (:build manifest))
         (vector? (:changes manifest)) (<= (count (:changes manifest)) 200)
         (every? change? (:changes manifest))
         (= (count (:changes manifest))
            (count (distinct (map :id (:changes manifest))))))
    (invalid!))
  manifest)

(defn assemble-manifest []
  (let [resource (io/resource "yetibot/release-info.edn")
        notes (when resource (edn/read-string (slurp resource :encoding "UTF-8")))
        actual (read-build-info)
        ;; Docker source images omit .git, so their newly generated Maven
        ;; version may be a placeholder. CI bundles the public release JSON.
        bundled (when-not (get-in actual [:app :revision])
                  (when-let [resource (io/resource "yetibot/release-info.json")]
                    (let [body (slurp resource :encoding "UTF-8")]
                      (when (> (count (.getBytes body "UTF-8"))
                               max-manifest-bytes) (invalid!))
                      (validate-manifest! (json/parse-string body true)))))
        build (if (and bundled (= (:changes bundled) (:changes notes)))
                (assoc actual :app (get-in bundled [:build :app]))
                actual)]
    (validate-manifest! (assoc notes :build build))))

;; Realize at namespace load, not the first query: a source updater may change
;; files while the previous JVM is still serving requests before its restart.
(defonce running (assemble-manifest))

(defn validate-options! [options]
  (when-not (and (map? options)
                 (every? #{"view" "since" "limit"} (keys options))
                 (or (not (contains? options "view"))
                     (#{"running" "latest"} (get options "view")))
                 (or (not (contains? options "since"))
                     (date? (get options "since")))
                 (or (not (contains? options "limit"))
                     (and (integer? (get options "limit"))
                          (<= 1 (get options "limit") 20))))
    (invalid!))
  options)

(defn- bounded-body [^InputStream stream]
  (let [buffer (byte-array 4096)
        output (ByteArrayOutputStream.)]
    (loop [total 0]
      (when (.isInterrupted (Thread/currentThread)) (invalid!))
      (let [n (.read stream buffer)]
        (if (neg? n)
          (.toString output "UTF-8")
          (do
            (when (> (+ total n) max-manifest-bytes) (invalid!))
            (.write output buffer 0 n)
            (recur (+ total n))))))))

(defn- published-manifest []
  (let [request
        (future
          (let [response (http/get latest-url
                           {:headers {"Accept" "application/json"}
                            :as :stream :throw-exceptions false
                            :follow-redirects false :redirect-strategy :none
                            :connection-timeout 1500 :socket-timeout 2000
                            :connection-request-timeout 1000
                            :retry-handler (fn [& _] false)})]
            (with-open [stream (:body response)]
              (when-not (and (= 200 (:status response))
                             (instance? InputStream stream)) (invalid!))
              (validate-manifest!
                (json/parse-string (bounded-body stream) true)))))]
    (try
      (let [result (deref request 5000 ::timeout)]
        (when (= result ::timeout) (invalid!))
        result)
      (catch Exception _
        (throw (ex-info "Published release information is unavailable."
                        {:release/unavailable true})))
      (finally (future-cancel request)))))

(defn- same-source? [published]
  (every?
    (fn [component]
      (let [a (get-in running [:build component :revision])
            b (get-in published [:build component :revision])]
        (and a b (or (string/starts-with? a b)
                     (string/starts-with? b a)))))
    [:app :core]))

(defn info [options]
  (validate-options! options)
  (let [latest? (= "latest" (get options "view"))
        manifest (if latest? (published-manifest) running)
        since (get options "since")
        changes (cond->> (:changes manifest)
                  since (filter #(not (neg? (compare (:date %) since)))))
        changes (vec (sort-by :date #(compare %2 %1) changes))
        selected (vec (take (get options "limit" 10) changes))
        result (cond->
                 (assoc manifest :changes selected
                        :view (if latest? "latest" "running")
                        :source (if latest? latest-url "bundled")
                        :total_changes (count changes)
                        :has_more (< (count selected) (count changes)))
                 latest? (assoc :running_build (:build running)
                                :same_source_revisions
                                (boolean (same-source? manifest))))]
    (loop [result result]
      (if (and (seq (:changes result))
               (> (count (json/generate-string result)) 10000))
        (recur (-> result (update :changes pop) (assoc :has_more true)))
        result))))

(defn format-version [build]
  (string/join "\n"
    (for [[key label] [[:app "Yetibot"] [:core "Core"]]
          :let [entry (get build key)]]
      (str label ": `" (or (:version entry) "unknown") "`"
           (when-let [url (:commit_url entry)]
             (str " ([source](" url "))"))))))

(defn format-changelog [result]
  (string/join "\n\n"
    (concat
      [(if (= "latest" (:view result))
         "Latest published features — not proof of what's installed."
         "Recent features included in this running build.")
       (format-version (:build result))]
      (when (= "latest" (:view result))
        [(str "Running build:\n" (format-version (:running_build result)))])
      (if (seq (:changes result))
        (for [{:keys [date title summary commands requires links]}
              (:changes result)]
          (string/join "\n"
            (concat [(str "**" title "** · " date) summary]
                    (when (seq commands)
                      [(str "Try: " (string/join " · "
                                       (map #(str "`!" % "`") commands)))])
                    (when (seq requires)
                      [(str "Requires: " (string/join "; " requires))])
                    links)))
        ["No matching changelog entries."])
      (when (:has_more result)
        [(str "Showing " (count (:changes result)) " of "
              (:total_changes result)
              " matching entries. Use --limit up to 20 for more.")]))))

(defn manifest-json []
  (str (json/generate-string running {:pretty true}) "\n"))

(defn -main [& args]
  (cond
    (empty? args)
    (println (or (get-in running [:build :app :version]) "unknown"))
    (= ["--json"] (vec args)) (print (manifest-json))
    (and (= 2 (count args)) (= "--write" (first args)))
    (let [file (io/file (second args))]
      (when-let [parent (.getParentFile file)] (.mkdirs parent))
      (spit file (manifest-json) :encoding "UTF-8"))
    :else (throw (ex-info "Usage: release-info [--json | --write FILE]" {}))))
