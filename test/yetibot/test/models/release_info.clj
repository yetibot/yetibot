(ns yetibot.test.models.release-info
  (:require [clojure.java.io :as io]
            [clojure.string :as string]
            [midje.sweet :refer [fact facts => truthy]]
            [yetibot.models.release-info :as releases]))

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
