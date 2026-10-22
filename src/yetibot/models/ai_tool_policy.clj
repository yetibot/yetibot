(ns yetibot.models.ai-tool-policy
  "Authorization uses adapter metadata, never the model's conversation."
  (:require [clojure.string :as string]
            [yetibot.api.ai :as ai]
            [yetibot.models.ai-toolsets :as toolsets]))

(defn actor [{:keys [chat-source user event-type]}]
  (let [{:keys [adapter uuid raw-event]} chat-source
        slack? (= :slack adapter)
        uid (if slack? (:user raw-event) (get-in raw-event [:author :id]))
        channel (if slack? (:channel raw-event) (:channel-id raw-event))
        message (if slack? (:ts raw-event) (:id raw-event))
        thread (if slack? (:thread_ts raw-event) (:channel-id raw-event))]
    (when (and (#{:slack :discord} adapter) uuid
               (every? ai/nonblank-string? [uid channel message])
               (not (:yetibot? user)) (not (:bot_id raw-event))
               (not (get-in raw-event [:author :bot]))
               (not (:subtype raw-event)) (not (:edited_timestamp raw-event))
               (not (:edited-timestamp raw-event))
               (or (nil? event-type) (= :message event-type)))
      {:adapter (name adapter) :connection (str uuid) :user uid
       :conversation (str uuid "/" channel) :thread (or thread channel)})))

(defn authorize!
  ([t actor] (authorize! t actor nil nil))
  ([t actor provider model]
   (doseq [[rule field] [[:users :user] [:conversations :conversation]
                         [:adapters :adapter]]]
     (when (and (contains? t rule)
                (not (some #{(get actor field)} (get t rule))))
       (ai/fail "You do not have access to this AI toolset here.")))
   (when (and provider (:providers t)
              (not (some #(and (= (:name provider) (:name %))
                               (some #{model} (:models %))) (:providers t))))
     (ai/fail "This provider/model is not allowed for the selected toolset."))
   t))

(defn visible? [t actor]
  (try (authorize! t actor) true (catch Exception _ false)))

(def allowed-arguments
  {"get_commit" #{"owner" "repo" "sha" "detail" "page" "perPage"}
   "get_file_contents" #{"owner" "repo" "path" "ref" "sha" "fields"}
   "list_branches" #{"owner" "repo" "page" "perPage"}
   "issue_read" #{"owner" "repo" "method" "issue_number" "page" "perPage"}
   "pull_request_read" #{"owner" "repo" "method" "pullNumber" "page" "perPage"}
   "search_code" #{"query" "sort" "order" "page" "perPage"}
   "search_issues" #{"query" "sort" "order" "page" "perPage"}
   "search_pull_requests" #{"query" "sort" "order" "page" "perPage"}})

(defn arguments! [t tool args]
  (if-not (toolsets/github? t)
    args
    (do
      (when-not (and (contains? toolsets/github-reads tool)
                     (every? (get allowed-arguments tool #{}) (keys args)))
        (ai/fail "This GitHub operation is not allowed."))
      (if (string/starts-with? tool "search_")
        (let [query (get args "query")]
          ;; Accept plain terms, not caller-controlled GitHub query syntax.
          (when-not (and (string? query)
                         (re-matches #"[\p{L}\p{N} _./-]{1,500}" query)
                         (not (re-find #"(?i)\b(?:OR|AND|NOT)\b" query)))
            (ai/fail "GitHub search accepts plain terms only; repository scope is set by policy."))
          (assoc args "query"
                 (str "(" query ") "
                      (string/join " " (map #(str "repo:" %) (:repositories t))))))
        (do
          (when-not (and (ai/nonblank-string? (get args "owner"))
                         (ai/nonblank-string? (get args "repo"))
                         (some #{(str (get args "owner") "/" (get args "repo"))}
                               (:repositories t)))
            (ai/fail "This repository is not allowed for the selected toolset."))
          (when (and (#{"issue_read" "pull_request_read"} tool)
                     (not (contains? #{"get" "get_comments" "get_sub_issues"
                                       "get_diff" "get_status" "get_files"
                                       "get_commits" "get_review_comments"
                                       "get_reviews"} (get args "method"))))
            (ai/fail "This GitHub read method is not allowed."))
          args)))))
