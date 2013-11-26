(ns yetibot.commands.jira
  (:require
    [yetibot.hooks :refer [cmd-hook]]
    [yetibot.api.jira :as api]))

(defn resolve-cmd
  "jira resolve <issue> <comment> # resolve an issue and set its resolution to fixed"
  [{[_ iss comment] :match user :user}]
  (let [comment (format "%s: %s" (:name user) comment)]
    (if-let [issue-data (api/get-issue iss)]
      (let [resolved? (api/resolve-issue iss comment)
            ; refetch the issue data now that it's resolved
            issue-data (api/get-issue iss)
            formatted (api/format-issue issue-data)]
        (if resolved?
          formatted
          (into [(str "Unable to resolve issue " iss)] formatted)))
      (str "Unable to find any issues for " iss))))

(cmd-hook #"jira"
          #"^resolve\s+([\w\-]+)\s+(.+)" resolve-cmd)
