(ns yetibot.commands.features
  (:require
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

(defn config [] (:value (get-config schema [:yetibot :features :github])))

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

; rate limited - can only accept 1 feature per 10 seconds
(def post-issue
  (useful/rate-limited
    (fn [title]
      (binding [tc/url (endpoint)]
        (is/create-issue (:user (config)) (repo) title (auth))))
    rate-limit-ms))

(defn listen-for-add-feature
  [event-json]
  (if-let [match (should-add-feature? (:body event-json))]
    (let [title (s/trim (second match))]
      (chat-data-structure
        (if-let [issue (post-issue title)]
          (format "Opened issue: %s" (:html_url issue)))))
    "I feel like you're trying to spam me; ignored"))

(defn- issues-in-yetibot-repo
  [] (apply is/issues (map (config) [:user :repo :auth])))

(defn lookup-features
  "features # look up YetiBot's current list of features requests"
  [_]
  (map :title (issues-in-yetibot-repo)))

(when (every? identity (config))
  (cmd-hook #"features"
            _ lookup-features)
  (obs-hook
    #{:message}
    listen-for-add-feature))
