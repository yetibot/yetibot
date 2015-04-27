(ns yetibot.commands.github
  (:require
    [taoensso.timbre :refer [info warn error]]
    [yetibot.api.github :as gh]
    [clojure.string :as s]
    [yetibot.core.util.http :refer [get-json]]
    [yetibot.core.hooks :refer [cmd-hook]]
    [taoensso.timbre :refer [info]]))

(defn feed
  "gh feed <org-name> # list recent activity for <org-name>"
  [{[_ org-name] :match}] (gh/formatted-events org-name))

(defn repos
  "gh repos # list all known repos
   gh repos <org-name> # list repos under <org-name>"
  [{match :match}]
  (if (sequential? match)
    (let [[_ org-name] match]
      (map #(format "%s/%s" org-name (:name %)) (gh/repos org-name)))
    (mapcat
      (fn [[org-name repos]]
        (map #(format "%s/%s" org-name (:name %))))
      (gh/repos-by-org))))

(defn repos-urls
  "gh repos urls # list the ssh urls of all repos"
  [_] (map :ssh_url (gh/repos)))

(defn orgs
  "gh orgs # show configured orgs"
  [_] gh/org-names)

(defn tags
  "gh tags <org-name>/<repo> # list the tags for <org-name>/<repo>"
  [{[_ org-name repo] :match}]
  (map :name (gh/tags org-name repo)))

(defn branches
  "gh branches <repo> # list branches for <repo>"
  [{[_ repo] :match}]
  (map :name (gh/branches repo)))

(defn- fmt-status [st] ((juxt :status :body :created_on) st))

(defn status
  "gh status # show GitHub's current system status"
  [_] (fmt-status (get-json "https://status.github.com/api/last-message.json")))

(defn statuses
  "gh statuses # show all recent GitHub system status messages"
  [_] (interleave
        (map fmt-status (get-json "https://status.github.com/api/messages.json"))
        (repeat ["--"])))

(defn pull-requests
  "gh pr <org-name> # list open pull requests for <org-name>"
  [{[_ org-name] :match}]
  (let [prs (gh/search-pull-requests org-name "" {:state "open"})]
    (->> prs
         :items
         (map (fn [pr]
                (s/join " "
                        [(format "[%s]" (-> pr :user :login))
                         (:title pr)
                         (-> pr :pull_request :html_url)
                         ]))))))

(if gh/configured?
  (cmd-hook ["gh" #"^gh|github$"]
            #"feed\s+(\S+)" feed
            #"repos urls" repos-urls
            #"repos\s+(\S+)" repos
            #"repos" repos
            #"orgs" orgs
            #"statuses" statuses
            #"status$" status
            #"pr\s+(\S+)" pull-requests
            #"tags\s+(\S+)\/(\S+)" tags
            #"branches\s+(\S+)" branches)
  (info "GitHub is not configured"))
