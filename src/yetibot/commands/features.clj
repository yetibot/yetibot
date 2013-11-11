(ns yetibot.commands.features
  (:require [yetibot.api.github :as gh]
            [clojure.string :as s]
            [yetibot.config :refer [config-for-ns]]
            [useful.fn :as useful :refer [rate-limited]]
            [tentacles [issues :as is]]
            [yetibot.hooks :refer [obs-hook cmd-hook]]
            [yetibot.chat :refer (chat-data-structure)]))

(def rate-limit-ms 5000)

(def config (:github (config-for-ns)))

(def auth {:auth (:token config)
           :user (:user config)
           :repo "yetibot"})

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
      (is/create-issue (:user config) (:repo config) title (:auth config)))
    rate-limit-ms))

(defn listen-for-add-feature
  [event-json]
  (if-let [match (should-add-feature? (:body event-json))]
    (let [title (s/trim (second match))]
      (chat-data-structure
        (if-let [issue (post-issue title)]
          (format "Opened issue: %s" (:html_url issue))
          "I feel like you're trying to spam me; ignored")))))

(defn- issues-in-yetibot-repo
  [] (apply is/issues (map config [:user :repo :auth])))

(defn lookup-features
  "features # look up YetiBot's current list of features requests"
  [_]
  (map :title (issues-in-yetibot-repo)))

(when (every? identity config)
  (cmd-hook #"features"
            _ lookup-features)
  (obs-hook
    #{:message}
    listen-for-add-feature))
