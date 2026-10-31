(ns yetibot.test.models.release-info
  (:require [cheshire.core :as json]
            [clj-http.client :as http]
            [clojure.java.io :as io]
            [clojure.string :as string]
            [midje.sweet :refer [fact facts => truthy]]
            [yetibot.models.release-info :as releases])
  (:import [java.util.concurrent CountDownLatch TimeUnit]))

(defn resource [text]
  (java.io.ByteArrayInputStream. (.getBytes text "UTF-8")))

(def fixture
  {:schema_version 1
   :changes [{:id "release-discovery" :date "2026-10-31" :component "app"
              :title "Discover installed features"
              :summary "Read the running version and bundled changelog."
              :commands ["version" "changelog"]
              :requires [] :links []}]})

(defn fixture-resource [path]
  (case path
    "META-INF/maven/yetibot/yetibot/pom.properties"
    (resource "version=20261031.203010.6700557c\n")
    "META-INF/maven/yetibot/core/pom.properties"
    (resource "version=20261031.202949.2404ac0a\n")
    "yetibot/release-info.edn" (resource (pr-str fixture))
    nil))

(fact "app and core identities come from their actual classpath artifacts"
  (with-redefs [io/resource fixture-resource]
    (releases/read-build-info))
  => {:app {:version "20261031.203010.6700557c" :revision "6700557c"
            :commit_url "https://github.com/yetibot/yetibot/commit/6700557c"}
      :core {:version "20261031.202949.2404ac0a" :revision "2404ac0a"
             :commit_url "https://github.com/yetibot/core/commit/2404ac0a"}})

(fact "missing build metadata is explicitly unknown, never project.clj's _"
  (with-redefs [io/resource (constantly nil)]
    (releases/read-build-info))
  => {:app nil :core nil})

(fact "a placeholder does not become a fictitious release"
  (with-redefs [io/resource (fn [_] (resource "version=_\n"))]
    (releases/read-build-info))
  => {:app nil :core nil})

(fact "the bundled manifest binds curated notes to both build identities"
  (with-redefs [io/resource fixture-resource]
    (let [result (releases/assemble-manifest)]
      (:schema_version result) => 1
      (get-in result [:build :app :revision]) => "6700557c"
      (:changes result) => (:changes fixture))))

(fact "normal release queries do not reread mutable classpath files"
  (with-redefs [io/resource
                (fn [_] (throw (AssertionError. "No runtime reread")))]
    (let [result (releases/info {})]
      (:view result) => "running"
      (:source result) => "bundled"
      (seq (:changes result)) => truthy
      (string/includes? (releases/format-version (:build result)) "Core")
      => true)))

(facts "options fail statically rather than reinterpret arbitrary inputs"
  (doseq [options [{"view" "https://attacker.invalid"}
                   {"url" "https://attacker.invalid"}
                   {"limit" 0} {"limit" 21} {"limit" 1.0}
                   {"since" "2026-02-30"} {"since" "today"}]]
    (fact (try (releases/validate-options! options) false
               (catch Exception _ true)) => true)))

(fact "dates and limits select real notes without changing their data"
  (with-redefs [releases/running
                (assoc fixture :build {:app nil :core nil}
                       :changes (conj (:changes fixture)
                                  {:id "old" :date "2026-01-01"
                                   :component "core" :title "Old feature"
                                   :summary "Earlier functionality."
                                   :commands [] :requires [] :links []}))]
    (let [result (releases/info {"since" "2026-10-30" "limit" 1})]
      (:changes result) => (:changes fixture)
      (:total_changes result) => 1
      (:has_more result) => false)))

(def published (assoc fixture :build
                      {:app {:version "20261031.203010.6700557c"
                             :revision "6700557c"
                             :commit_url (str "https://github.com/yetibot/"
                                              "yetibot/commit/6700557c")}
                       :core nil}))

(fact "default queries are offline and never resolve provider credentials"
  (with-redefs [http/get (fn [& _] (throw (AssertionError. "No HTTP")))]
    (:source (releases/info {})) => "bundled"))

(fact "latest is a bounded credential-free GET, not an installed-release claim"
  (let [requests (atom [])]
    (with-redefs [http/get
                  (fn [url options]
                    (swap! requests conj [url options])
                    {:status 200 :body (resource (json/generate-string published))})]
      (let [result (releases/info {"view" "latest"})
            [url options] (first @requests)]
        (:view result) => "latest"
        (:build result) => (:build published)
        (:running_build result) => (:build releases/running)
        url => "https://yetibot.com/releases/latest.json"
        (:follow-redirects options) => false
        (:redirect-strategy options) => :none
        (:headers options) => {"Accept" "application/json"}
        (select-keys options [:basic-auth :oauth-token :bearer-token]) => {}
        (pos? (:socket-timeout options)) => true
        (count @requests) => 1))))

(facts "HTTP errors, redirects and malformed manifests are sanitized"
  (doseq [response [{:status 302 :body (resource "redirect-secret")}
                    {:status 500 :body (resource "server-secret")}
                    {:status 200 :body (resource "not-json-secret")}
                    {:status 200 :body (resource "{\"schema_version\":999}")}
                    {:status 200 :body
                     (resource (json/generate-string
                                 (assoc published :credential "private")))}]]
    (with-redefs [http/get (constantly response)]
      (fact (try (releases/info {"view" "latest"}) :unexpected
                 (catch Exception e (.getMessage e)))
            => "Published release information is unavailable."))))

(fact "oversized public bodies fail without parsing or exposing their data"
  (with-redefs [http/get
                (constantly {:status 200
                             :body (resource (apply str (repeat
                                               (inc releases/max-manifest-bytes)
                                               "x")))})]
    (try (releases/info {"view" "latest"}) :unexpected
         (catch Exception e (.getMessage e)))
    => "Published release information is unavailable."))

(fact "a stalled lookup has a caller deadline and does not block forever"
  (let [gate (CountDownLatch. 1)
        started (CountDownLatch. 1)]
    (with-redefs [http/get (fn [& _] (.countDown started) (.await gate)
                           (throw (Exception. "synthetic stalled request")))]
      (let [answer (future
                     (try (releases/info {"view" "latest"}) :unexpected
                          (catch Exception e (.getMessage e))))]
        (try
          (.await started 2 TimeUnit/SECONDS) => true
          (deref answer 10000 ::hung)
          => "Published release information is unavailable."
          (.getCount gate) => 1
          (finally (.countDown gate) (future-cancel answer)))))))

(fact "large note sets preserve complete entries within the result budget"
  (let [summary (apply str (repeat 800 "x"))
        changes (mapv #(assoc (first (:changes fixture))
                              :id (str "feature-" %) :summary summary)
                      (range 20))]
    (with-redefs [releases/running (assoc published :changes changes)]
      (let [result (releases/info {"limit" 20})]
        (:total_changes result) => 20
        (:has_more result) => true
        (<= (count (json/generate-string result)) 10000) => true
        (every? #(= summary (:summary %)) (:changes result)) => true))))

(fact "bad dates or redirecting fields are rejected before any request"
  (with-redefs [http/get (fn [& _] (throw (AssertionError. "No HTTP")))]
    (try (releases/info {"view" "latest" "url" "https://elsewhere.invalid"})
         false (catch Exception _ true)) => true))
