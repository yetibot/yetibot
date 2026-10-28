(ns yetibot.models.ai-github
  "Host-owned GitHub MCP reads and the fixed, approved draft-PR sequence."
  (:require [clj-http.client :as client]
            [cheshire.core :as json]
            [clojure.string :as string]
            [yetibot.api.ai :as ai]
            [yetibot.api.mcp :as mcp]
            [yetibot.models.ai-toolsets :as toolsets])
  (:import [java.util Arrays Base64 UUID]
           [java.security MessageDigest]))

(defn- json-data [text]
  (when (string? text)
    (try (json/parse-string-strict text true) (catch Exception _ nil))))

(defn- data! [result]
  (let [structured (:structuredContent result)
        blocks (or (:content structured) (:content result))
        parsed (some #(when (= "text" (:type %))
                        (json-data (:text %))) blocks)
        value (if (and structured (not (vector? (:content structured))))
                structured parsed)]
    (or value (ai/fail "GitHub MCP returned an unsupported result shape."))))

(defn- call! [tools name args]
  (let [tool (some #(when (= name (:name %)) %) tools)]
    (when-not tool (ai/fail "A required GitHub MCP tool is not configured."))
    (let [result (mcp/call-tool (:session tool) name args)]
      (when (or (:isError result) (:unknownOutcome result))
        (throw (ex-info "GitHub MCP operation failed; no automatic retry."
                        {:ai/error true :unknown-outcome (boolean (:unknownOutcome result))})))
      result)))

(defn- commit! [tools owner repo ref]
  (let [data (data! (call! tools "get_commit"
                         {"owner" owner "repo" repo "sha" ref
                          "detail" "full_patch" "perPage" 100}))]
    (when-not (and (string? (:sha data))
                   (re-matches #"[0-9a-f]{40,64}" (:sha data)))
      (ai/fail "GitHub did not return a complete commit identity."))
    data))

(defn- file-text! [result]
  (let [data (try (data! result) (catch Exception _ nil))
        resource (some #(when (= "resource" (:type %)) (:resource %))
                       (or (get-in result [:structuredContent :content]) (:content result)))]
    (cond
      (and (= "base64" (:encoding data)) (string? (:content data)))
      (try
        (let [encoded (string/replace (:content data) #"\s" "")
              bytes (.decode (Base64/getDecoder) encoded)
              text (String. bytes "UTF-8")]
          (when-not (Arrays/equals bytes (.getBytes text "UTF-8"))
            (ai/fail "GitHub file is not UTF-8 text."))
          text)
        (catch Exception _ (ai/fail "GitHub file is not complete UTF-8 text.")))
      (and (= "utf-8" (:encoding data)) (string? (:content data))) (:content data)
      (string? (:text resource)) (:text resource)
      :else (ai/fail "GitHub file content is missing or unsupported."))))

(defn- blob-sha [text]
  (let [bytes (.getBytes ^String text "UTF-8")
        digest (MessageDigest/getInstance "SHA-1")]
    (.update digest (.getBytes (str "blob " (alength bytes) \u0000) "UTF-8"))
    (apply str (map #(format "%02x" (bit-and 255 %)) (.digest digest bytes)))))

(defn- read-file! [tools owner repo sha path]
  (let [parent (string/join "/" (butlast (string/split path #"/")))
        args {"owner" owner "repo" repo "ref" sha}
        listing (data! (call! tools "get_file_contents" (assoc args "path" parent)))
        entries (if (vector? listing) listing (:entries listing))]
    (when-not (and (vector? entries) (< (count entries) 1000)
                   (every? #(and (map? %) (string? (:path %))) entries)
                   (= (count entries) (count (distinct (map :path entries)))))
      (ai/fail "GitHub directory metadata is incomplete."))
    (when-let [entry (some #(when (= path (:path %)) %) entries)]
      (when-not (and (= "file" (:type entry))
                     (not (:target entry)) (not (:submodule_git_url entry))
                     (or (nil? (:mode entry)) (= "100644" (:mode entry))
                         (= "100755" (:mode entry)))
                     (string? (:sha entry))
                     (number? (:size entry)) (<= 0 (:size entry) 20000))
        (ai/fail "Only complete small text files can be changed; no symlinks or submodules."))
      (let [text (file-text! (call! tools "get_file_contents" (assoc args "path" path)))]
        (when-not (and (= (:size entry) (alength (.getBytes ^String text "UTF-8")))
                       (= (:sha entry) (blob-sha text)))
          (ai/fail "GitHub file content was incomplete or did not match its blob identity."))
        text))))

(defn snapshot-with-tools! [tools owner repo base paths]
  (let [sha (:sha (commit! tools owner repo base))]
    {:base-sha sha
     :originals (into {} (map #(vector % (read-file! tools owner repo sha %)) paths))}))

(defn snapshot! [config t owner repo base paths]
  (let [selected (toolsets/select config (:name t))]
    (mcp/with-tools selected
      #(snapshot-with-tools! % owner repo base paths))))

(def writer-tools ["create_branch" "push_files" "create_pull_request"])

(defn- ancestry!
  "The pinned MCP MinimalCommit omits parents. Read only this immutable Git
   object from GitHub's authenticated REST API; never reuse MCP transport auth."
  [token owner repo sha timeout-ms]
  (try
    (let [response (client/get
                     (str "https://api.github.com/repos/" owner "/" repo
                          "/git/commits/" sha)
                     {:as :json :throw-exceptions false
                      :conn-timeout timeout-ms :socket-timeout timeout-ms
                      :redirect-strategy :none :retry-handler (fn [& _] false)
                      :headers {"Accept" "application/vnd.github+json"
                                "X-GitHub-Api-Version" "2022-11-28"
                                "Authorization" (str "Bearer " token)}})
          data (:body response)]
      (when-not (and (= 200 (:status response)) (= sha (:sha data))
                     (vector? (:parents data))
                     (every? #(and (map? %) (string? (:sha %))
                                    (re-matches #"[0-9a-f]{40,64}" (:sha %)))
                             (:parents data)))
        (ai/fail "GitHub commit ancestry could not be verified."))
      (mapv :sha (:parents data)))
    (catch Exception _
      ;; HTTP exceptions can carry tokens, headers or private response bodies.
      (ai/fail "GitHub commit ancestry could not be verified."))))

(defn- verify-commit! [reads p branch state token timeout-ms]
  (let [{:keys [owner repo base-sha files originals]} p
        commit (commit! reads owner repo branch)
        changed (filter #(not= (:content %) (get originals (:path %))) files)
        expected (set (map :path changed))]
    (swap! state assoc :commit (:sha commit))
    (when-not
      (and (= [base-sha] (ancestry! token owner repo (:sha commit) timeout-ms))
           (= expected (set (map :filename (:files commit))))
           (every? #(= (:content %)
                       (read-file! reads owner repo (:sha commit) (:path %)))
                   files))
      (ai/fail "Pushed commit differs from approval; no PR opened."))))

(defn- write-approved! [reads writes p branch state token timeout-ms]
  (let [{:keys [owner repo base base-sha files title body]} p
        args {"owner" owner "repo" repo}]
    (swap! state assoc :branch branch :step :create-branch)
    (call! writes "create_branch"
           (assoc args "branch" branch "from_branch" base))
    (swap! state assoc :step :verify-branch)
    (when-not (= base-sha (:sha (commit! reads owner repo branch)))
      (ai/fail "Created branch has a different base; no files pushed."))
    (swap! state assoc :step :push-files)
    (call! writes "push_files"
           (assoc args "branch" branch "message" title
                  "files" (mapv #(hash-map "path" (:path %)
                                           "content" (:content %)) files)))
    (swap! state assoc :step :verify-commit)
    (verify-commit! reads p branch state token timeout-ms)
    (swap! state assoc :step :create-pr)
    (let [result (data! (call! writes "create_pull_request"
                               (assoc args "head" branch "base" base
                                      "draft" true "title" title "body" body)))
          url (:html_url result)
          prefix (str "https://github.com/" owner "/" repo "/pull/")]
      (when-not (and (string? url) (string/starts-with? url prefix)
                     (re-matches
                       #"https://github\.com/[^/]+/[^/]+/pull/[0-9]+" url))
        (throw (ex-info "PR result could not be verified."
                        {:unknown-outcome true})))
      (swap! state assoc :status :succeeded :pr-url url))))

(defn execute! [config p]
  (let [state (atom {:status :failed :step :validate})]
    (try
      (when-not (= :executing (:status p))
        (ai/fail "Proposal has not been claimed for execution."))
      (let [selected (toolsets/select config (get-in p [:toolset :name]))
            t (:toolset selected)
            writer (some #(when (= (:write-server t) (:name %)) %)
                         (:servers config))
            {:keys [owner repo base base-sha]} p
            branch (str (or (:branch-prefix t) "yetibot/ai-")
                        (if (= :automatic (:execution p))
                          (let [key (:request-key p)]
                            (when-not (and (string? key) (re-matches #"[a-f0-9]{64}" key))
                              (ai/fail "Automatic execution is missing its request identity."))
                            (str "request-" key))
                          (UUID/randomUUID)))
            write-config (assoc config :servers
                                [(assoc writer :tools writer-tools)])
            timeout-ms (mcp/number-option (:timeout-ms config) 30000)]
        (when-not (= t (:toolset p))
          (ai/fail "Tool policy changed. Generate a new proposal."))
        (mcp/with-tools selected
          (fn [reads]
            (let [commit-reader (first (filter #(= "get_commit" (:name %))
                                               reads))
                  token (get-in commit-reader
                                [:session :server :github-api-token])]
              (when-not (ai/nonblank-string? token)
                (ai/fail "Configure a GitHub API read token before PR writes."))
              (when-not (= base-sha (:sha (commit! reads owner repo base)))
                (ai/fail "Base branch changed. Generate a new proposal."))
              ;; Qualify API access before creating even an empty branch.
              (ancestry! token owner repo base-sha timeout-ms)
              (mcp/with-tools write-config
                #(write-approved! reads % p branch state token timeout-ms))))))
      @state
      (catch Exception e
        (assoc @state
               :status (if (or (:unknown-outcome (ex-data e))
                               (#{:create-branch :push-files :create-pr}
                                (:step @state)))
                         :unknown :failed)
               :message (str "GitHub workflow stopped. Inspect known identifiers "
                             "before any new attempt; no automatic retry."))))))
