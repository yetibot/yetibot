(ns yetibot.commands.release
  (:require [clojure.string :as string]
            [clojure.tools.cli :refer [parse-opts]]
            [yetibot.core.hooks :refer [cmd-hook]]
            [yetibot.models.release-info :as releases]))

(def usage
  (str "Usage: changelog [latest] [--since YYYY-MM-DD] [--limit 1..20]\n"
       "Default: features included in this running build.\n"
       "latest: published release notes, not proof of deployment."))

(defn version-cmd
  "version # show running app and core versions with source links"
  {:yb/cat #{:util}}
  [_]
  {:result/value (releases/format-version (:build releases/running))
   :result/data (select-keys releases/running [:schema_version :build])})

(defn- options! [text]
  (let [tokens (if (string/blank? text) []
                  (string/split (string/trim text) #"\s+"))
        {:keys [options arguments errors]}
        (parse-opts tokens
          [[nil "--since DATE" "Changes since an inclusive ISO date"]
           [nil "--limit N" "Maximum changes, 1..20"
            :parse-fn #(Integer/parseInt %)]])
        options (cond-> {}
                  (seq arguments) (assoc "view" "latest")
                  (:since options) (assoc "since" (:since options))
                  (:limit options) (assoc "limit" (:limit options)))]
    (when (or (seq errors)
              (not (or (empty? arguments) (= ["latest"] arguments))))
      (throw (ex-info usage {})))
    (releases/validate-options! options)))

(defn- failure [args text]
  (when-let [skip (:skip-next-n args)]
    (reset! skip (count (:next-cmds args))))
  {:result/value text :result/data {:isError true :error text}})

(defn changelog-cmd
  "changelog # show recent features included in the running build
   changelog --since YYYY-MM-DD --limit N # filter and bound the notes
   changelog latest # read published notes, separate from what's installed"
  {:yb/cat #{:util}}
  [{[_ text] :match :as args}]
  (try
    (let [result (releases/info (options! text))]
      {:result/value (releases/format-changelog result)
       :result/data result :result/collection-path [:changes]})
    (catch Exception e
      (failure args (if (:release/unavailable (ex-data e))
                      (str "Published release information is unavailable. "
                           "Use changelog for this running build's notes.")
                      usage)))))

(cmd-hook #"version" #"^\s*$" version-cmd)
(cmd-hook #"changelog" #"(?s)^(.*)$" changelog-cmd)
