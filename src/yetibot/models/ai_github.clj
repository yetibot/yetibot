(ns yetibot.models.ai-github
  "Host-owned GitHub MCP reads and the fixed, approved draft-PR sequence."
  (:require [cheshire.core :as json]
            [clojure.string :as string]
            [yetibot.api.ai :as ai]
            [yetibot.api.mcp :as mcp]
            [yetibot.models.ai-toolsets :as toolsets])
  (:import [java.util Arrays Base64 UUID]))

(defn- json-data [text]
  (when (string? text)
    (try (json/parse-string text true) (catch Exception _ nil))))

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

(defn- read-file! [tools owner repo sha path]
  (let [parent (string/join "/" (butlast (string/split path #"/")))
        args {"owner" owner "repo" repo "ref" sha}
        listing (data! (call! tools "get_file_contents" (assoc args "path" parent)))
        entries (if (vector? listing) listing (:entries listing))]
    (when-not (vector? entries)
      (ai/fail "GitHub directory metadata is incomplete."))
    (when-let [entry (some #(when (= path (:path %)) %) entries)]
      (when-not (and (= "file" (:type entry))
                     (not (:target entry)) (not (:submodule_git_url entry))
                     (number? (:size entry)) (<= 0 (:size entry) 20000))
        (ai/fail "Only complete small text files can be changed; no symlinks or submodules."))
      (let [text (file-text! (call! tools "get_file_contents" (assoc args "path" path)))]
        (when-not (= (:size entry) (alength (.getBytes ^String text "UTF-8")))
          (ai/fail "GitHub file content was incomplete."))
        text))))

(defn snapshot-with-tools! [tools owner repo base paths]
  (let [sha (:sha (commit! tools owner repo base))]
    {:base-sha sha
     :originals (into {} (map #(vector % (read-file! tools owner repo sha %)) paths))}))

(defn snapshot! [config t owner repo base paths]
  (let [selected (toolsets/select config (:name t))]
    (mcp/with-tools selected
      #(snapshot-with-tools! % owner repo base paths))))

(defn execute! [config p]
  (let [state (atom {:status :failed :step :validate})]
    (try
      (when-not (= :executing (:status p))
        (ai/fail "Proposal has not been claimed for execution."))
      (let [selected (toolsets/select config (get-in p [:toolset :name]))
            t (:toolset selected)
            writer (some #(when (= (:write-server t) (:name %)) %) (:servers config))
            {:keys [owner repo base base-sha files]} p
            branch (str (or (:branch-prefix t) "yetibot/ai-") (UUID/randomUUID))
            args {"owner" owner "repo" repo}]
        (when-not (= t (:toolset p))
          (ai/fail "Tool policy changed. Generate a new proposal."))
        (mcp/with-tools selected
          (fn [reads]
            (when-not (= base-sha (:sha (commit! reads owner repo base)))
              (ai/fail "Base branch changed. Generate a new proposal."))
            (mcp/with-tools (assoc config :servers [(assoc writer :tools
                                                          ["create_branch" "push_files" "create_pull_request"])])
              (fn [writes]
                (swap! state assoc :branch branch :step :create-branch)
                (call! writes "create_branch" (assoc args "branch" branch "from_branch" base))
                (swap! state assoc :step :verify-branch)
                (when-not (= base-sha (:sha (commit! reads owner repo branch)))
                  (ai/fail "Created branch does not match the approved base; no files pushed."))
                (swap! state assoc :step :push-files)
                (call! writes "push_files"
                       (assoc args "branch" branch "message" (:title p)
                              "files" (mapv #(hash-map "path" (:path %) "content" (:content %)) files)))
                (swap! state assoc :step :verify-commit)
                (let [commit (commit! reads owner repo branch)
                      expected (set (map :path (filter #(not= (:content %) (get (:originals p) (:path %))) files)))]
                  (swap! state assoc :commit (:sha commit))
                  (when-not (and (= [base-sha] (mapv :sha (:parents commit)))
                                 (= expected (set (map :filename (:files commit))))
                                 (every? #(= (:content %) (read-file! reads owner repo (:sha commit) (:path %))) files))
                    (ai/fail "Pushed commit differs from the approved manifest; no PR opened.")))
                (swap! state assoc :step :create-pr)
                (let [result (data! (call! writes "create_pull_request"
                                           (assoc args "head" branch "base" base
                                                  "draft" true "title" (:title p)
                                                  "body" (str (:body p)
                                                              "\n\nGenerated by Yetibot using an approved change manifest."
                                                              "\nNot locally tested. CI and human review are required."))))
                      url (:html_url result)]
                  (when-not (and (string? url)
                                 (string/starts-with? url (str "https://github.com/" owner "/" repo "/pull/"))
                                 (re-matches #"https://github\.com/[^/]+/[^/]+/pull/[0-9]+" url))
                    (throw (ex-info "PR result could not be verified." {:unknown-outcome true})))
                  (swap! state assoc :status :succeeded :pr-url url)))))))
      @state
      (catch Exception e
        (assoc @state :status (if (or (:unknown-outcome (ex-data e))
                                      (#{:create-branch :push-files :create-pr} (:step @state)))
                               :unknown :failed)
               :message "GitHub workflow stopped. Inspect known identifiers before any new attempt; no automatic retry.")))))
