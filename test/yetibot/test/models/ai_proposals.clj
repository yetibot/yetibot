(ns yetibot.test.models.ai-proposals
  (:require [cheshire.core :as json]
            [clojure.string :as string]
            [midje.sweet :refer [fact facts =>]]
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
    (error? #(proposals/claim! (:id p) actor)) => true
    (string? (proposals/page! (:id p) actor 1)) => true
    (error? #(proposals/review! (:id p) (assoc actor :user "other"))) => true
    (error? #(proposals/review! (:id p) (assoc actor :thread "other"))) => true
    (proposals/cancel! (:id p) actor) => :cancelled
    (error? #(proposals/claim! (:id p) actor)) => true))

(facts "invalid paths, content and no-op proposals are rejected"
  (doseq [files [[{"path" "../secret" "content" "x"}]
                 [{"path" "src/.env" "content" "x"}]
                 [{"path" "src/a.clj" "content" "old\n"}]
                 [{"path" "src/a.clj" "content" "x\u0000"}]
                 [{"path" "src/a.clj"
                   "content" (String. (char-array [(char 0xd800)]))}]
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
        _ (proposals/page! (:id p) actor 1)
        attempts (doall (repeatedly 8 #(future
                                       (try (proposals/claim! (:id p) actor)
                                            (catch Exception _ nil)))))]
    (count (filter some? (map deref attempts))) => 1
    (:status (proposals/review! (:id p) actor)) => :executing))

(fact "review pages expose line-ending-only changes rather than hiding them"
  (let [p (proposals/stage! pr-toolset actor {:name "local"} "chat" args
                            (fn [& _] {:base-sha (apply str (repeat 40 "a"))
                                       :originals {"src/a.clj" "new\r\n"}}))]
    (boolean (re-find #"CRLF sequences: 1 -> 0" (:diff p))) => true))

(fact "cleanup between review and page update cannot recreate a partial proposal"
  (let [state (var-get (ns-resolve 'yetibot.models.ai-proposals 'proposals))
        saved @state
        p (stage)
        original-review proposals/review!]
    (try
      (with-redefs [proposals/review!
                    (fn [id actor]
                      (let [reviewed (original-review id actor)]
                        (with-redefs [proposals/now
                                      (constantly (inc (:expires-at reviewed)))]
                          (proposals/cleanup!))
                        reviewed))]
        (error? #(proposals/page! (:id p) actor 1)) => true)
      (contains? @state (:id p)) => false
      (error? proposals/cleanup!) => false
      (error? stage) => false
      (finally (reset! state saved)))))

(facts "required review text preserves exact newline positions and final blanks"
  (let [render (fn [content]
                 (:diff (proposals/stage!
                          pr-toolset actor {:name "local"} "chat"
                          (assoc args "files" [{"path" "src/a.clj"
                                                "content" content}])
                          (fn [& _] {:base-sha (apply str (repeat 40 "a"))
                                     :originals {"src/a.clj" "a\nb\n"}}))))]
    (= (render "a\r\nb\n") (render "a\nb\r\n")) => false
    (= (render "#!/bin/sh\r\n# comment\n")
       (render "#!/bin/sh\n# comment\r\n")) => false
    (string/includes? (string/lower-case (render "é\t\u202e\n"))
                      "\\u202e") => true
    (doseq [content ["a\r\nb\n" "a\nb\r\n" "a\r\nb\r" "a\n\n" "a" "é\t\u202e\n"]]
      (let [lines (->> (string/split-lines (render content))
                       (filter #(string/starts-with? % "+\""))
                       (map #(json/parse-string (subs % 1))))]
        (fact (apply str lines) => content)))))

(facts "standard SSH private-key basenames are never writable"
  (doseq [path ["src/id_ed25519" "src/id_ecdsa" "src/id_dsa"
                "src/nested/id_ed25519_sk" "src/ID_ECDSA" "src/id_xmss"]]
    (fact (boolean (proposals/path-allowed? pr-toolset path)) => false))
  (boolean (proposals/path-allowed? pr-toolset "src/identifier.clj")) => true)
