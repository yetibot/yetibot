(ns yetibot.test.commands.code
  (:require
   [midje.sweet :refer [fact facts => anything throws =throws=>]]
   [midje.checkers :refer [contains]]
   [clojure.string :as string]
   [clj-http.client :as client]
   [yetibot.commands.code :as code]))

(facts "about parse-repo"
  (fact "splits owner/repo"
    (code/parse-repo "yetibot/core") => ["yetibot" "core"])

  (fact "trims whitespace"
    (code/parse-repo "  yetibot/core  ") => ["yetibot" "core"])

  (fact "uses default org for a bare repo name"
    (code/parse-repo "core") => ["yetibot" "core"]
    (provided (code/default-org) => "yetibot")))

(facts "about authed-clone-url"
  (fact "embeds the token for auth"
    (code/authed-clone-url "secret" "yetibot" "core") =>
    "https://x-access-token:secret@github.com/yetibot/core.git"))

(facts "about branch-name"
  (fact "is namespaced and unique-ish"
    (code/branch-name) => #(string/starts-with? % "yetibot/code-")))

(facts "about pr-title"
  (fact "capitalizes and collapses whitespace"
    (code/pr-title "increase   banana budget") => "Increase banana budget")

  (fact "truncates very long instructions"
    (count (code/pr-title (apply str (repeat 200 "x")))) => 72)

  (fact "marks truncated titles with an ellipsis"
    (string/ends-with? (code/pr-title (apply str (repeat 200 "x"))) "...") => true)

  (fact "handles blank instruction"
    (code/pr-title "   ") => "Yetibot change"))

(facts "about pr-body"
  (fact "includes the instruction and model"
    (code/pr-body "do a thing" "I did the thing") =>
    (contains "do a thing")
    (provided (code/model) => "gemini-2.5-pro"))

  (fact "embeds gemini output when present"
    (code/pr-body "x" "some output") => (contains "some output")
    (provided (code/model) => "gemini-2.5-pro")))

(facts "about redact"
  (fact "strips an embedded github token from a clone url"
    (code/redact "git clone https://x-access-token:supersecret@github.com/yetibot/core.git")
    => "git clone https://x-access-token:***@github.com/yetibot/core.git")

  (fact "leaves token-free strings untouched"
    (code/redact "nothing to redact here") => "nothing to redact here")

  (fact "tolerates nil"
    (code/redact nil) => nil))

(facts "about run-gemini"
  (fact "invokes the configured cli with model, yolo, and prompt, passing the API key in env"
    (code/run-gemini "/tmp/repo" "increase banana budget") => {:exit 0 :out "done" :err ""}
    (provided
     (code/cli-bin) => "gemini"
     (code/model) => "gemini-2.5-pro"
     (code/gemini-key) => "test-key"
     (code/check-sh {:dir "/tmp/repo" :env {"GEMINI_API_KEY" "test-key"}}
                    "gemini" "--model" "gemini-2.5-pro" "--yolo"
                    "--prompt" anything)
     => {:exit 0 :out "done" :err ""})))

(facts "about create-pull-request"
  (fact "returns the PR body on success and includes auth + payload"
    (code/create-pull-request "tok" "yetibot" "core"
                              {:title "T" :body "B" :head "h" :base "master"})
    => {:html_url "https://github.com/yetibot/core/pull/1"}
    (provided
     (client/post
      "https://api.github.com/repos/yetibot/core/pulls"
      anything)
     => {:status 201 :body {:html_url "https://github.com/yetibot/core/pull/1"}}))

  (fact "throws on a non-2xx response"
    (code/create-pull-request "tok" "yetibot" "core"
                              {:title "T" :body "B" :head "h" :base "master"})
    => (throws clojure.lang.ExceptionInfo)
    (provided
     (client/post anything anything)
     => {:status 422 :body {:message "Validation Failed"}})))

(facts "about code-cmd guards"
  (fact "complains when gemini is not configured"
    (code/code-cmd {:match ["code yetibot/core x" "yetibot/core" "x"]})
    => (contains "Gemini is not configured")
    (provided (code/gemini-key) => nil
              (code/github-token) => "tok"))

  (fact "complains when no github token is configured"
    (code/code-cmd {:match ["code yetibot/core x" "yetibot/core" "x"]})
    => (contains "No GitHub token configured")
    (provided (code/gemini-key) => "key"
              (code/github-token) => nil))

  (fact "reports the PR url on success"
    (code/code-cmd {:match ["code yetibot/core increase banana budget"
                            "yetibot/core" "increase banana budget"]})
    => (contains "https://github.com/yetibot/core/pull/7")
    (provided (code/gemini-key) => "key"
              (code/github-token) => "tok"
              (code/file-pr anything)
              => {:html_url "https://github.com/yetibot/core/pull/7"}))

  (fact "reports a friendly message when gemini makes no changes"
    (code/code-cmd {:match ["code yetibot/core nothing" "yetibot/core" "nothing"]})
    => (contains "didn't make any changes")
    (provided (code/gemini-key) => "key"
              (code/github-token) => "tok"
              (code/file-pr anything)
              =throws=> (ex-info "Gemini did not make any changes" {:type :no-changes}))))
