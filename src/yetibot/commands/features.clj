(ns yetibot.commands.features
  (:require
    [clj-http.client :as client]
    [schema.core :as sch]
    [clojure.string :as s]
    [tentacles [issues :as is] [core :as tc]]
    [flatland.useful.fn :as useful]
    [yetibot.api.github :as gh]
    [yetibot.core.chat :refer [chat-data-structure]]
    [yetibot.core.config :refer [get-config]]
    [yetibot.core.hooks :refer [obs-hook cmd-hook]]))

(def schema
  {:repo sch/Str
   :token sch/Str
   :user sch/Str})

(def rate-limit-ms 5000)

(defn config [] (:value (get-config schema [:features :github])))

(defn auth [] {:oauth-token (:token (config))})
(defn repo [] (or (:repo (config)) "yetibot"))
(defn endpoint [] "https://api.github.com/")

(defn should-add-feature?
  "Loop the regexes that think we should add a feature"
  [body]
  (some identity
        (map #(re-find % body)
             [#"(?i)^feature request:(.+)"
              #"(?i)^yetibot feature:(.+)"])))

; rate limited - can only accept 1 feature per 5 seconds
(def post-issue
  (useful/rate-limited
    (fn [title]
      (binding [tc/url (endpoint)]
        (is/create-issue (:user (config)) (repo) title (auth))))
    rate-limit-ms))

(defn format-issue-response
  [{:keys [html_url status body] :as issue}]
  (if html_url
    (format "Opened issue: %s" html_url)
    ;; error
    (:message body)))

(defn listen-for-add-feature
  [event-json]
  (when-let [match (should-add-feature? (:body event-json))]
    (let [title (s/trim (second match))]
      (-> (post-issue title)
          format-issue-response
          chat-data-structure))))

(defn- issues-in-yetibot-repo
  [] (apply is/issues (map (config) [:user :repo])))

(defn lookup-features
  "features # look up Yetibot's current list of feature requests"
  [_]
  (map (juxt :title :html_url) (issues-in-yetibot-repo)))

(defn request-feature-cmd
  "features request <text> # request a feature"
  [{[_ body] :match}]
  (format-issue-response (post-issue body)))

(when (every? identity (config))
  (cmd-hook ["features" #"features?"]
    #"request\s+(.+)" request-feature-cmd
    _ lookup-features)
  (obs-hook #{:message} #'listen-for-add-feature))
