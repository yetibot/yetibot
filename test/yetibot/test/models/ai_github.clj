(ns yetibot.test.models.ai-github
  (:require [cheshire.core :as json]
            [clj-http.client :as client]
            [clojure.string :as string]
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
(def config {:toolsets [t] :servers [{:name "reader" :tools read-tools
                                     :github-api-token "fake-ancestry-token"}
                                    {:name "writer" :tools write-tools}]})
(def proposal {:id "test" :status :executing :toolset t :base-sha sha
               :owner "owner" :repo "repo" :base "main" :title "Fix"
               :body "Approved" :files [{:path "src/a.clj" :content "new\n"}]
               :originals {"src/a.clj" "old\n"}})

;; Synthetic identities/content, with the exact MinimalCommit field shape from
;; github/github-mcp-server v2.0.3 (minimal_types.go). In particular: no parents.
(def minimal-commit (json/parse-string (slurp "test/fixtures/github_mcp_v2_0_3_commit.json") true))

(defn with-fixture [fault f]
  (let [calls (atom []) pushed (atom false)]
    (with-redefs
      [client/get (fn [url options]
                    (let [args {:url url :options options}]
                      (swap! calls conj ["REST ancestry" args])
                      (or (fault "REST ancestry" args)
                          {:status 200 :body {:sha (last (string/split url #"/"))
                                             :parents [{:sha sha}]}})))
       mcp/with-tools (fn [settings callback]
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
              "get_commit" (assoc minimal-commit :sha (if @pushed new-sha sha))
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

(facts "JSON text-block directories support complete snapshots and approved draft PRs"
  (doseq [envelope [identity #(hash-map :structuredContent %)]]
    (with-fixture (fn [& _] nil)
      (fn [_]
        (let [call-tool mcp/call-tool]
          (with-redefs [mcp/call-tool
                        (fn [session name args]
                          (let [result (call-tool session name args)]
                            (if (= "get_file_contents" name)
                              (envelope {:content [{:type "text"
                                                    :text (json/generate-string (:structuredContent result))}]})
                              result)))]
            (fact (github/snapshot! config t "owner" "repo" "main" ["src/a.clj" "src/new.clj"])
                  => {:base-sha sha :originals {"src/a.clj" "old\n" "src/new.clj" nil}}
                  (:status (github/execute! config proposal)) => :succeeded)))))))

(facts "JSON text-block directories still require complete unambiguous metadata"
  (doseq [listing [[{:path "src/a.clj"} {:path "src/a.clj"}]
                   [{:type "file"}]
                   (mapv #(hash-map :path (str "src/file" %)) (range 1000))]]
    (with-fixture (fn [name args]
                    (when (and (= "get_file_contents" name) (= "src" (get args "path")))
                      {:content [{:type "text" :text (json/generate-string listing)}]}))
      (fn [calls]
        (fact (try (github/snapshot! config t "owner" "repo" "main" ["src/a.clj"])
                   (catch Exception _ :rejected)) => :rejected
              (some #(contains? (set write-tools) (first %)) @calls) => nil)))))

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

(facts "ancestry uses authenticated fixed-host REST without inventing MCP parents"
  (contains? minimal-commit :parents) => false
  (with-fixture (fn [& _] nil)
    (fn [calls]
      (:status (github/execute! config proposal)) => :succeeded
      (let [reads (filter #(= "REST ancestry" (first %)) @calls)]
        (mapv #(get-in % [1 :url]) reads)
        => [(str "https://api.github.com/repos/owner/repo/git/commits/" sha)
            (str "https://api.github.com/repos/owner/repo/git/commits/" new-sha)]
        (doseq [[_ {:keys [options]}] reads]
          (fact (get-in options [:headers "Authorization"]) => "Bearer fake-ancestry-token"
                (:redirect-strategy options) => :none
                ((:retry-handler options) nil nil nil) => false
                (:socket-timeout options) => 30000))))))

(facts "missing credentials or failed ancestry preflight prevent all mutations"
  (with-fixture (fn [& _] nil)
    (fn [calls]
      (:status (github/execute! (update-in config [:servers 0] dissoc :github-api-token)
                                proposal)) => :failed
      @calls => []))
  (doseq [response [{:status 401 :body {:message "fake-ancestry-token"}}
                    {:status 302 :headers {"Location" "https://other.example/"}}
                    {:status 500}
                    {:status 200 :body {:sha sha}}
                    {:status 200 :body {:sha new-sha :parents []}}]]
    (with-fixture (fn [name _] (when (= "REST ancestry" name) response))
      (fn [calls]
        (let [result (github/execute! config proposal)]
          (fact (:status result) => :failed
                (some #(contains? (set write-tools) (first %)) @calls) => nil
                (count (filter #(= "REST ancestry" (first %)) @calls)) => 1
                (string/includes? (pr-str result) "fake-ancestry-token") => false))))))

(fact "automatic discovery requires credentials on the actual commit reader"
  (let [t (assoc t :servers [{:name "reader"} {:name "fallback"}])
        config (assoc config :toolsets [t]
                      :servers [{:name "reader"}
                                {:name "fallback"
                                 :github-api-token "fallback-token"}
                                {:name "writer" :tools write-tools}])
        connections (atom [])]
    (with-fixture (fn [& _] nil)
      (fn [calls]
        (with-redefs
          [mcp/with-tools
           (fn [settings f]
             (swap! connections conj (mapv :name (:servers settings)))
             (f [{:name "get_commit"
                  :session {:server (first (:servers settings))}}]))]
          (fact (:status (github/execute! config
                                         (assoc proposal :toolset t)))
                => :failed
                @connections => [["reader" "fallback"]]
                @calls => []))))))

(facts "missing, mismatched or merge ancestry stops after the push without a PR"
  (doseq [data [{:sha new-sha}
                {:sha new-sha :parents []}
                {:sha new-sha :parents [{:sha new-sha}]}
                {:sha new-sha :parents [{:sha sha} {:sha new-sha}]}
                {:sha sha :parents [{:sha sha}]}]]
    (with-fixture (fn [name args]
                    (when (and (= "REST ancestry" name)
                               (string/ends-with? (:url args) new-sha))
                      {:status 200 :body data}))
      (fn [calls]
        (let [result (github/execute! config proposal)]
          (fact (:status result) => :failed
                (:step result) => :verify-commit
                (:commit result) => new-sha
                (count (filter #(= "push_files" (first %)) @calls)) => 1
                (some #(= "create_pull_request" (first %)) @calls) => nil))))))

(fact "HTTP exception bodies and headers are never exposed"
  (with-fixture (fn [name _]
                  (when (= "REST ancestry" name)
                    (throw (ex-info "fake-ancestry-token" {:headers {"Authorization" "secret"}}))))
    (fn [calls]
      (let [result (github/execute! config proposal)]
        (:status result) => :failed
        (some #(contains? (set write-tools) (first %)) @calls) => nil
        (string/includes? (pr-str result) "fake-ancestry-token") => false))))

(facts "automatic requests use a stable branch and do not push when it already exists"
  (let [key (apply str (repeat 64 "d"))
        p (assoc proposal :execution :automatic :request-key key)
        branches (atom #{}) pushes (atom 0) outcomes (atom [])]
    (dotimes [_ 2]
      (with-fixture
        (fn [name args]
          (cond
            (= "create_branch" name)
            (if (contains? @branches (get args "branch"))
              {:isError true}
              (do (swap! branches conj (get args "branch")) nil))
            (= "push_files" name) (do (swap! pushes inc) nil)))
        (fn [_] (swap! outcomes conj (:status (github/execute! config p))))))
    (fact @outcomes => [:succeeded :unknown]
          @pushes => 1
          @branches => #{(str "yetibot/ai-request-" key)}))
  (with-fixture (fn [& _] nil)
    (fn [calls]
      (fact (:status (github/execute! config (assoc proposal :execution :automatic :request-key "untrusted")))
            => :failed
            @calls => []))))

(fact "a branch-creation race leaves the empty branch untouched"
  (let [read-count (atom 0)]
    (with-fixture (fn [name _]
                    (when (and (= name "get_commit") (= 2 (swap! read-count inc)))
                      {:structuredContent {:sha new-sha}}))
      (fn [calls]
        (:status (github/execute! config proposal)) => :failed
        (some #(= "push_files" (first %)) @calls) => nil))))
