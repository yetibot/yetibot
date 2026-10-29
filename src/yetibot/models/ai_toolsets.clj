(ns yetibot.models.ai-toolsets
  "Pure configuration selection. Connections never come from model output."
  (:require [yetibot.api.ai :as ai]))

(def github-reads
  #{"get_file_contents" "get_commit" "list_branches" "issue_read"
    "pull_request_read" "search_code" "search_issues" "search_pull_requests"})
(def github-writes
  #{"create_branch" "push_files" "create_pull_request" "merge_pull_request"
    "create_or_update_file" "delete_file" "delete_repository"
    "create_repository" "fork_repository" "update_pull_request"
    "issue_write" "add_issue_comment" "create_pull_request_review"
    "submit_pull_request_review"})

(defn strings? [xs]
  (and (vector? xs) (seq xs) (every? ai/nonblank-string? xs)
       (= (count xs) (count (distinct xs)))))

(def default-native-tools ["help" "history" "history_count"])
(def native-names
  (into (set default-native-tools) ["meme" "gi" "clef" "clairic" "wise"]))

(defn github? [toolset]
  (contains? #{"github-read" "github-pr"} (:kind toolset)))

(defn model-tool?
  "Reserved GitHub names keep their role policy, even during discovery."
  [toolset tool-name]
  (and (not (github-writes tool-name))
       (if (github? toolset)
         (boolean (github-reads tool-name))
         (not (github-reads tool-name)))))

(defn reference-server
  "Inherit a server's tools unless the reference supplies a narrower list."
  [servers ref]
  (when-let [server (get servers (:name ref))]
    (if (contains? ref :tools) (assoc server :tools (:tools ref)) server)))

(defn- can-provide? [server tool-name]
  (and server (or (not (contains? server :tools))
                  (some #{tool-name} (:tools server)))))

(defn- invalid! []
  (ai/fail "Invalid AI toolsets. Check names, references and access policy."))

(defn- named? [x]
  (and (ai/nonblank-string? x) (not (re-find #"\s" x))))

(defn- validate-toolset! [servers t]
  (when-not
    (and (named? (:name t))
         (contains? #{"search" "github-read" "github-pr"} (:kind t))
         (vector? (:servers t))
         (or (seq (:servers t)) (= "search" (:kind t)))
         (= (count (:servers t)) (count (distinct (map :name (:servers t))))))
    (invalid!))
  (doseq [ref (:servers t)]
    (let [server (get servers (:name ref))
          resolved (reference-server servers ref)]
      (when-not (and server
                     (or (not (contains? ref :tools)) (strings? (:tools ref)))
                     (or (not (contains? server :tools))
                         (strings? (:tools server)))
                     (or (not (contains? server :tools))
                         (every? (set (:tools server)) (:tools ref)))
                     (every? #(model-tool? t %) (:tools resolved)))
        (invalid!))))
  (when (contains? t :native-tools)
    (when-not (and (= "search" (:kind t)) (vector? (:native-tools t))
                   (or (empty? (:native-tools t))
                       (and (strings? (:native-tools t))
                            (every? native-names (:native-tools t))
                            (every? #(strings? (get t %))
                                    [:users :adapters])
                            (every? #{"slack" "discord"} (:adapters t))
                            (seq (:providers t)))))
      (invalid!)))
  (doseq [k [:users :adapters]]
    (when (and (contains? t k) (not (strings? (get t k)))) (invalid!)))
  (when (github? t)
    (when-not (and (every? #(strings? (get t %))
                          [:users :adapters :repositories])
                   (every? #(re-matches #"[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+" %)
                           (:repositories t))
                   (every? #{"slack" "discord"} (:adapters t)))
      (invalid!)))
  (when (contains? t :providers)
    (when-not (and (vector? (:providers t)) (seq (:providers t))
                   (every? #(and (named? (:name %)) (strings? (:models %)))
                           (:providers t)))
      (invalid!)))
  (when (= "github-pr" (:kind t))
    (let [writer (get servers (:write-server t))
          readers (map #(reference-server servers %) (:servers t))
          credentialed-reader?
          (some #(and (can-provide? % "get_commit")
                       (ai/nonblank-string? (:github-api-token %))) readers)]
      (when-not (and (seq (:providers t)) writer
                     (every? (fn [name]
                               (some #(can-provide? % name) readers))
                             ["get_commit" "get_file_contents"])
                     (every? #(can-provide? writer %)
                             ["create_branch" "push_files"
                              "create_pull_request"])
                     credentialed-reader?
                     (strings? (:base-branches t))
                     (strings? (:allowed-paths t))
                     (or (not (contains? t :denied-paths))
                         (strings? (:denied-paths t)))
                     (or (nil? (:branch-prefix t))
                         (re-matches #"[A-Za-z0-9/_-]+" (:branch-prefix t))))
        (invalid!)))))

(defn validate! [config]
  (when (or (contains? config :native-tools)
            (some #(= "yetibot" (:name %)) (:servers config)))
    (invalid!))
  (let [toolsets (:toolsets config)
        servers (into {} (map (juxt :name identity) (:servers config)))]
    (if (contains? config :toolsets)
      (do
        (when-not (and (vector? toolsets) (seq toolsets)
                       (= (count toolsets) (count (distinct (map :name toolsets))))
                       (or (nil? (:default-toolset config))
                           (some #(= (:default-toolset config) (:name %)) toolsets)))
          (invalid!))
        (doseq [t toolsets] (validate-toolset! servers t)))
      (when (or (:default-toolset config)
                (some #(or (github-writes %) (github-reads %))
                      (mapcat :tools (:servers config))))
        (invalid!))))
  config)

(defn select
  "Resolve a toolset to a narrowed MCP config, or retain legacy search config."
  [config toolset-name]
  (validate! config)
  (if (seq (:toolsets config))
    (let [selected (or toolset-name (:default-toolset config)
                       (:name (first (:toolsets config))))
          t (some #(when (= selected (:name %)) %) (:toolsets config))
          servers (into {} (map (juxt :name identity) (:servers config)))]
      (when-not t (ai/fail "Unknown AI toolset. Use ai toolsets to list available toolsets."))
      (assoc config :toolset t :redaction-servers (:servers config)
             :servers (mapv #(reference-server servers %) (:servers t))))
    (if toolset-name
      (ai/fail "No named AI toolsets configured.")
      config)))
