(ns yetibot.test.models.ai-proposals
  (:require [midje.sweet :refer [fact facts =>]]
            [yetibot.models.ai-proposals :as proposals]
            [yetibot.test.models.ai-tool-policy :refer [actor toolset]]))

(def pr-toolset (assoc toolset :kind "github-pr" :base-branches ["main"]
                       :allowed-paths ["src/"] :write-server "writer"))
(def args {"owner" "owner" "repo" "repo" "base" "main"
           "title" "Fix" "body" "A focused change"
           "files" [{"path" "src/a.clj" "content" "new\n"}]})
(defn snapshot [& _]
  {:base-sha (apply str (repeat 40 "a")) :originals {"src/a.clj" "old\n"}})
(defn stage []
  (proposals/stage! pr-toolset actor {:name "local" :url "http://localhost/v1"}
                    "chat" args snapshot))
(defn error? [f] (try (f) false (catch Exception _ true)))

(fact "staging is read-only and preserves exact approved bytes"
  (let [p (stage)]
    (:status p) => :pending
    (:files p) => [{:path "src/a.clj" :content "new\n"}]
    (string? (:diff p)) => true
    (:id (proposals/review! (:id p) actor)) => (:id p)
    (error? #(proposals/review! (:id p) (assoc actor :user "other"))) => true
    (proposals/cancel! (:id p) actor) => :cancelled
    (error? #(proposals/claim! (:id p) actor)) => true))

(facts "invalid paths, content and no-op proposals are rejected"
  (doseq [files [[{"path" "../secret" "content" "x"}]
                 [{"path" "src/.env" "content" "x"}]
                 [{"path" "src/a.clj" "content" "old\n"}]
                 [{"path" "src/a.clj" "content" "x\u0000"}]
                 [{"path" "src/a.clj" "content" "x"}
                  {"path" "src/a.clj" "content" "y"}]
                 [{"path" "src/a.clj" "content" (apply str (repeat 8193 "x"))}]]]
    (fact (error? #(proposals/stage! pr-toolset actor {:name "local"} "chat"
                                     (assoc args "files" files) snapshot)) => true)))

(fact "expiry and atomic claims cannot be bypassed by repeated approval"
  (let [p (stage)]
    (with-redefs [proposals/now (constantly (+ (:expires-at p) 1))]
      (error? #(proposals/claim! (:id p) actor)) => true))
  (let [p (stage)
        attempts (doall (repeatedly 8 #(future
                                       (try (proposals/claim! (:id p) actor)
                                            (catch Exception _ nil)))))]
    (count (filter some? (map deref attempts))) => 1
    (:status (proposals/review! (:id p) actor)) => :executing))
