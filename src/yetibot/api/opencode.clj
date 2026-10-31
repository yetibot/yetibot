(ns yetibot.api.opencode
  "OpenCode Zen's catalog lives outside the OpenAI chat API base path."
  (:require [clojure.string :as string])
  (:import [java.net URI]))

(def api-key-placeholder "REPLACE_WITH_OPENCODE_ZEN_API_KEY")

(defn configured-key? [key]
  (and (string? key) (not (string/blank? key))
       (not= api-key-placeholder key)))

(defn provider? [{:keys [url]}]
  (try
    (let [uri (URI. url)]
      (and (= "https" (.getScheme uri))
           (= "opencode.ai" (.getHost uri))
           (#{-1 443} (.getPort uri))
           (not (.getUserInfo uri))
           (not (.getQuery uri))
           (not (.getFragment uri))
           (boolean
             (re-matches #"/(?:inference/openai|zen)/v1/?"
                         (.getPath uri)))))
    (catch Exception _ false)))

(defn catalog-provider [provider]
  ;; Never follow catalog-advertised URLs or redirect credentials to a new
  ;; origin. Legacy Zen URLs keep their existing /models endpoint.
  (if (provider? provider)
    (cond-> provider
      (not (configured-key? (:key provider))) (dissoc :key)
      (.startsWith (.getPath (URI. (:url provider))) "/inference/")
      (assoc :url "https://opencode.ai/inference/v1"))
    provider))
