(ns yetibot.commands.code
  "Use Gemini to make a code change in a GitHub repo and open a pull request.

   Example:

     code yetibot/core increase banana budget

   This clones the repo using Yetibot's existing GitHub token, checks out and
   rebases on the latest trunk, runs the Gemini CLI to perform the requested
   change, then commits, pushes a branch, and opens a PR back to the repo."
  (:require
   [clojure.java.shell :as shell]
   [clojure.spec.alpha :as s]
   [clojure.string :as string]
   [clojure.data.json :as json]
   [clj-http.client :as client]
   [taoensso.timbre :refer [debug info warn error]]
   [yetibot.core.config :refer [get-config]]
   [yetibot.core.hooks :refer [cmd-hook]])
  (:import
   [java.nio.file Files]
   [java.nio.file.attribute FileAttribute]))

;; ---------------------------------------------------------------------------
;; Config
;;
;; Gemini config lives under [:gemini]. At minimum a :key (Gemini API key) is
;; required. Optionally override the model, the CLI binary, and the default org
;; used when a repo is given without an owner.
;;
;;   YB_GEMINI_KEY          - Gemini API key (required)
;;   YB_GEMINI_MODEL        - model to use (default below)
;;   YB_GEMINI_CLI          - path to the gemini CLI binary (default "gemini")
;;   YB_GEMINI_DEFAULT_ORG  - org used when repo given without an owner
;;
;; The GitHub token is reused from Yetibot's existing GitHub config, preferring
;; [:features :github :token] (YB_FEATURES_GITHUB_TOKEN) and falling back to the
;; yetibot/github plugin's [:github :token] (YB_GITHUB_TOKEN).
;; ---------------------------------------------------------------------------

(s/def ::key string?)
(s/def ::config (s/keys :req-un [::key]))

;; generic spec for fetching single string values out of arbitrary config paths
(s/def ::str string?)

(defn gemini-config [] (get-config ::config [:gemini]))

(defn- config-str [path]
  (:value (get-config ::str path)))

;; The strongest Gemini coding model. Overridable via YB_GEMINI_MODEL so it can
;; be bumped as newer models ship without a code change.
(def default-model "gemini-2.5-pro")

(defn gemini-key [] (config-str [:gemini :key]))
(defn model [] (or (config-str [:gemini :model]) default-model))
(defn cli-bin [] (or (config-str [:gemini :cli]) "gemini"))
(defn default-org [] (or (config-str [:gemini :default-org]) "yetibot"))

(defn github-token
  "Reuse Yetibot's existing GitHub token."
  []
  (or (config-str [:features :github :token])
      (config-str [:github :token])))

(defn configured?
  "The command is only available when both Gemini and a GitHub token are set."
  []
  (boolean (and (not (string/blank? (gemini-key)))
                (not (string/blank? (github-token))))))

;; ---------------------------------------------------------------------------
;; Shell helpers
;; ---------------------------------------------------------------------------

(defn sh*
  "Run a shell command, optionally in :dir with extra :env vars merged on top of
   the current environment. Returns the clojure.java.shell result map."
  [{:keys [dir env]} & args]
  (let [full-env (when (seq env)
                   (merge (into {} (System/getenv)) env))
        opts (cond-> []
               dir (conj :dir dir)
               full-env (conj :env full-env))
        result (apply shell/sh (concat args opts))]
    (debug "sh" (pr-str (vec args)) "exit" (:exit result))
    result))

(defn redact
  "Strip embedded credentials (e.g. the GitHub token in a clone URL) from a
   string so they never end up in chat output or logs."
  [s]
  (when s
    (-> s
        ;; redact the password in scheme://user:password@ URLs (e.g. the
        ;; GitHub token in https://x-access-token:TOKEN@github.com/...). This is
        ;; idempotent: re-running over an already-redacted "user:***@" is a noop.
        (string/replace #"(://[^:/@\s]+:)[^@\s]+(@)" "$1***$2")
        ;; redact bare userinfo tokens with no password, e.g. https://TOKEN@host
        (string/replace #"(://)[^:/@\s]+(@)" "$1***$2"))))

(defn check-sh
  "Like sh* but throws ex-info with captured (credential-redacted) output when
   the command fails."
  [ctx & args]
  (let [{:keys [exit out err] :as result} (apply sh* ctx args)]
    (when-not (zero? exit)
      (throw (ex-info (redact (format "command failed (exit %s): %s\n%s"
                                      exit (string/join " " args)
                                      (or (not-empty (string/trim (str err)))
                                          (string/trim (str out)))))
                      {:exit exit
                       :out (redact out)
                       :err (redact err)
                       :args (mapv redact args)})))
    result))

;; ---------------------------------------------------------------------------
;; Pure-ish helpers
;; ---------------------------------------------------------------------------

(defn parse-repo
  "Parse `owner/repo` or bare `repo` (using the default org) into [owner repo]."
  [repo-arg]
  (let [parts (-> repo-arg string/trim (string/split #"/"))]
    (if (>= (count parts) 2)
      [(first parts) (second parts)]
      [(default-org) (first parts)])))

(defn authed-clone-url
  "HTTPS clone URL that embeds the token for push/pull auth."
  [token owner repo]
  (format "https://x-access-token:%s@github.com/%s/%s.git" token owner repo))

(defn branch-name
  "A unique branch name for the change."
  []
  (str "yetibot/code-" (System/currentTimeMillis)))

(defn pr-title
  "Derive a concise PR title from the instruction."
  [instruction]
  (let [trimmed (string/trim instruction)
        one-line (-> trimmed (string/replace #"\s+" " "))
        capped (if (> (count one-line) 72)
                 (str (subs one-line 0 69) "...")
                 one-line)]
    (if (seq capped)
      (str (string/upper-case (subs capped 0 1)) (subs capped 1))
      "Yetibot change")))

(defn pr-body
  [instruction gemini-out]
  (str "Requested via Yetibot:\n\n> " (string/trim instruction) "\n\n"
       "This change was authored by the Gemini CLI (`" (model) "`) "
       "and opened automatically by Yetibot 🤖.\n\n"
       (when-not (string/blank? gemini-out)
         (str "<details><summary>Gemini output</summary>\n\n```\n"
              (string/trim gemini-out)
              "\n```\n\n</details>\n"))))

(defn- temp-dir []
  (.toFile (Files/createTempDirectory "yetibot-code" (make-array FileAttribute 0))))

(defn default-branch
  "Detect the repo's default branch (trunk) from origin/HEAD."
  [repo-dir]
  (let [{:keys [exit out]} (sh* {:dir repo-dir}
                                "git" "rev-parse" "--abbrev-ref" "origin/HEAD")]
    (if (zero? exit)
      (-> out string/trim (string/replace #"^origin/" ""))
      "master")))

(defn working-tree-dirty?
  "Whether Gemini actually changed anything in the repo."
  [repo-dir]
  (-> (sh* {:dir repo-dir} "git" "status" "--porcelain")
      :out
      string/blank?
      not))

;; ---------------------------------------------------------------------------
;; Gemini
;; ---------------------------------------------------------------------------

(defn build-gemini-prompt
  "Wrap the user's instruction with guidance so the CLI edits files directly."
  [instruction]
  (str "You are an automated coding agent working in a cloned git repository. "
       "Make the following change directly to the files in this repository, "
       "keeping the change minimal and focused. Do not commit, push, or open a "
       "pull request yourself — only edit files. When you are done, briefly "
       "summarize what you changed.\n\n"
       "Change to make:\n" (string/trim instruction)))

(defn run-gemini
  "Invoke the Gemini CLI in repo-dir to perform the coding instruction. Uses the
   best configured coding model and auto-approves tool calls so it can edit
   files non-interactively."
  [repo-dir instruction]
  (info "running gemini" (cli-bin) "model" (model))
  (check-sh {:dir repo-dir
             :env {"GEMINI_API_KEY" (gemini-key)}}
            (cli-bin)
            "--model" (model)
            "--yolo"
            "--prompt" (build-gemini-prompt instruction)))

;; ---------------------------------------------------------------------------
;; GitHub
;; ---------------------------------------------------------------------------

(defn create-pull-request
  "Open a PR via the GitHub REST API using the existing token."
  [token owner repo {:keys [title body head base]}]
  (let [{:keys [status body]}
        (client/post (format "https://api.github.com/repos/%s/%s/pulls" owner repo)
                     {:headers {"Authorization" (str "Bearer " token)
                                "Accept" "application/vnd.github+json"
                                "X-GitHub-Api-Version" "2022-11-28"}
                      :content-type :json
                      :as :json
                      :coerce :always
                      :throw-exceptions false
                      :body (json/write-str {:title title
                                             :body body
                                             :head head
                                             :base base})})]
    (debug "create-pull-request status" status)
    (if (<= 200 status 299)
      body
      (throw (ex-info (str "GitHub PR creation failed: "
                           (or (:message body) status))
                      {:status status :body body})))))

;; ---------------------------------------------------------------------------
;; Orchestration
;; ---------------------------------------------------------------------------

(defn file-pr
  "End to end: clone, rebase on latest trunk, run Gemini, commit, push, open PR.
   Returns the GitHub PR map (with :html_url) or throws."
  [{:keys [owner repo instruction token]}]
  (let [dir (temp-dir)
        repo-dir dir
        ctx {:dir repo-dir}
        ;; embed the token only in env-free git operations via the remote URL
        clone-url (authed-clone-url token owner repo)
        branch (branch-name)]
    (try
      (info "cloning" (format "%s/%s" owner repo) "into" (str dir))
      (check-sh {} "git" "clone" "--depth" "50" clone-url (str repo-dir))
      ;; identify Yetibot as the committer
      (check-sh ctx "git" "config" "user.name" "Yetibot")
      (check-sh ctx "git" "config" "user.email" "yetibot@yetibot.com")
      (let [trunk (default-branch repo-dir)]
        (info "trunk is" trunk)
        ;; make sure we're on the latest trunk before branching
        (check-sh ctx "git" "checkout" trunk)
        (check-sh ctx "git" "fetch" "origin" trunk)
        (check-sh ctx "git" "pull" "--rebase" "origin" trunk)
        (check-sh ctx "git" "checkout" "-b" branch)
        ;; let Gemini do the work
        (let [gemini-result (run-gemini repo-dir instruction)]
          (when-not (working-tree-dirty? repo-dir)
            (throw (ex-info "Gemini did not make any changes" {:type :no-changes})))
          (check-sh ctx "git" "add" "-A")
          (check-sh ctx "git" "commit" "-m" (pr-title instruction))
          (check-sh ctx "git" "push" "-u" "origin" branch)
          (create-pull-request token owner repo
                               {:title (pr-title instruction)
                                :body (pr-body instruction (:out gemini-result))
                                :head branch
                                :base trunk})))
      (finally
        ;; best-effort cleanup of the temp checkout
        (try (sh* {} "rm" "-rf" (str dir))
             (catch Exception e (warn "failed to clean up" (str dir) e)))))))

(defn code-cmd
  "code <owner/repo> <instruction> # use Gemini to make the change and open a PR"
  {:yb/cat #{:util}}
  [{[_ repo-arg instruction] :match}]
  (let [token (github-token)]
    (cond
      (string/blank? (gemini-key))
      "Gemini is not configured. Set the `:gemini :key` config (YB_GEMINI_KEY)."

      (string/blank? token)
      "No GitHub token configured. Set `:features :github :token` (YB_FEATURES_GITHUB_TOKEN)."

      :else
      (let [[owner repo] (parse-repo repo-arg)]
        (try
          (let [{:keys [html_url] :as pr}
                (file-pr {:owner owner
                          :repo repo
                          :instruction instruction
                          :token token})]
            (if html_url
              (format "Opened PR for %s/%s: %s" owner repo html_url)
              (str "Opened PR but got no URL back: " (pr-str pr))))
          (catch clojure.lang.ExceptionInfo e
            (error "code command failed" e)
            (if (= :no-changes (:type (ex-data e)))
              (format "Gemini didn't make any changes for %s/%s — try a more specific instruction."
                      owner repo)
              (str "Failed to open PR: " (.getMessage e))))
          (catch Exception e
            (error "code command failed" e)
            (str "Failed to open PR: " (.getMessage e))))))))

;; Only register the command when both Gemini and GitHub are configured, in
;; keeping with the convention used by other optionally-configured commands.
(when (configured?)
  (cmd-hook #"code"
            #"^(\S+)\s+(.+)$" code-cmd))
