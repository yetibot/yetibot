(ns yetibot.test.models.ai-github
  (:require [clojure.string :as string]
            [midje.sweet :refer [fact facts =>]]
            [yetibot.api.mcp :as mcp]
            [yetibot.models.ai-github :as github]
            [yetibot.test.models.ai-proposals :as proposals])
  (:import [java.util Base64]))

(def sha (apply str (repeat 40 "a")))
(def new-sha (apply str (repeat 40 "b")))
(def read-tools ["get_commit" "get_file_contents"])
(def write-tools ["create_branch" "push_files" "create_pull_request"])
(def t (assoc proposals/pr-toolset :name "github-pr"
              :servers [{:name "reader" :tools read-tools}]))
(def config {:toolsets [t] :servers [{:name "reader" :tools read-tools}
                                    {:name "writer" :tools write-tools}]})
(def proposal {:id "test" :status :executing :toolset t :base-sha sha
               :owner "owner" :repo "repo" :base "main" :title "Fix"
               :body "Approved" :files [{:path "src/a.clj" :content "new\n"}]
               :originals {"src/a.clj" "old\n"}})

(defn with-fixture [fault f]
  (let [calls (atom []) pushed (atom false)]
    (with-redefs
      [mcp/with-tools (fn [settings callback]
                        (callback (vec (for [server (:servers settings)
                                             name (:tools server)]
                                         {:name name :session {:server server}
                                          :inputSchema {:type "object"}}))))
       mcp/call-tool
       (fn [session name args]
         (swap! calls conj [name args])
         (if-let [result (fault name args)] result
           {:structuredContent
            (case name
              "get_commit" {:sha (if @pushed new-sha sha)
                            :parents [{:sha sha}]
                            :files [{:filename "src/a.clj"}]}
              "get_file_contents"
              (if (= "src" (get args "path"))
                [{:path "src/a.clj" :name "a.clj" :type "file" :size 4
                  :sha (if @pushed "3e757656cf36eca53338e520d134963a44f793f8"
                           "3367afdbbf91e638efe983616377c60477cc6612")}]
                {:type "file" :encoding "base64"
                 :content (.encodeToString (Base64/getEncoder)
                                           (.getBytes (if @pushed "new\n" "old\n") "UTF-8"))})
              "push_files" (do (reset! pushed true) {:sha new-sha})
              "create_branch" {:ref "branch"}
              "create_pull_request" {:html_url "https://github.com/owner/repo/pull/1"})}))]
      (f calls))))

(fact "source reads are pinned to a commit and decode complete text"
  (with-fixture (fn [& _] nil)
    (fn [calls]
      (github/snapshot! config t "owner" "repo" "main" ["src/a.clj"])
      => {:base-sha sha :originals {"src/a.clj" "old\n"}}
      (every? #(= sha (get (second %) "ref"))
              (filter #(= "get_file_contents" (first %)) @calls)) => true)))

(fact "approved writes use exact files and force a draft PR"
  (with-fixture (fn [& _] nil)
    (fn [calls]
      (let [result (github/execute! config proposal)
            mutations (filter #(contains? (set write-tools) (first %)) @calls)]
        (:status result) => :succeeded
        (:pr-url result) => "https://github.com/owner/repo/pull/1"
        (mapv first mutations) => write-tools
        (get (second (second mutations)) "files")
        => [{"path" "src/a.clj" "content" "new\n"}]
        (get (second (last mutations)) "draft") => true
        (string/starts-with? (:branch result) "yetibot/ai-") => true))))

(facts "stale state and unknown writes stop without retry"
  (with-fixture (fn [name _] (when (= "get_commit" name)
                              {:structuredContent {:sha new-sha}}))
    (fn [calls]
      (:status (github/execute! config proposal)) => :failed
      (some #(contains? (set write-tools) (first %)) @calls) => nil))
  (with-fixture (fn [name _] (when (= "push_files" name)
                              {:isError true :unknownOutcome true}))
    (fn [calls]
      (:status (github/execute! config proposal)) => :unknown
      (count (filter #(= "push_files" (first %)) @calls)) => 1
      (some #(= "create_pull_request" (first %)) @calls) => nil)))

(fact "same-length content corruption is rejected, not approved as complete text"
  (with-fixture (fn [name args]
                  (when (and (= "get_file_contents" name)
                             (= "src/a.clj" (get args "path")))
                    {:structuredContent {:encoding "base64" :content "YmFkCg=="}}))
    (fn [_]
      (try (github/snapshot! config t "owner" "repo" "main" ["src/a.clj"])
           (catch Exception _ :rejected)) => :rejected)))

(fact "a branch-creation race leaves the empty branch untouched"
  (let [read-count (atom 0)]
    (with-fixture (fn [name _]
                    (when (and (= name "get_commit") (= 2 (swap! read-count inc)))
                      {:structuredContent {:sha new-sha}}))
      (fn [calls]
        (:status (github/execute! config proposal)) => :failed
        (some #(= "push_files" (first %)) @calls) => nil))))
