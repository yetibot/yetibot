(ns yetibot.commands.github
  (:require
    [taoensso.timbre :refer [info warn error]]
    [yetibot.api.github :as gh]
    [clojure.string :as s]
    [yetibot.core.util.http :refer [get-json]]
    [yetibot.core.hooks :refer [cmd-hook]]
    [taoensso.timbre :refer [info]]
    [inflections.core :refer [plural]]
    [clj-time [core :refer [ago minutes hours days weeks years months]]]
    [clj-time.coerce :as c]
    [clj-time.format :as f]
    [robert.bruce :refer [try-try-again] :as rb]))

(def date-formatter (f/formatters :date))
(def date-hour-formatter (f/formatter "MMM d, yyyy 'at' hh:mm"))

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
        (map #(format "%s/%s" org-name (:name %)) repos))
      (gh/repos-by-org))))

(defn repos-urls
  "gh repos urls # list the ssh urls of all repos"
  [{match :match}]
  (if (sequential? match)
    (let [[_ org-name] match]
      (map :ssh_url (gh/repos org-name)))
    (mapcat
      (fn [[org-name repos]]
        (map :ssh_url repos))
      (gh/repos-by-org))))

(defn orgs
  "gh orgs # show configured orgs"
  [_] (gh/org-names))

(defn tags
  "gh tags <org-name>/<repo> # list the tags for <org-name>/<repo>"
  [{[_ org-name repo] :match}]
  (map :name (gh/tags org-name repo)))

(defn branches
  "gh branches <org-name>/<repo> # list branches for <org-name>/<repo>"
  [{[_ org-name repo] :match}]
  (map :name (gh/branches org-name repo)))

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
                        (remove nil?
                                [(format "[%s]" (-> pr :user :login))
                                 (when-let [a (:assignee pr)]
                                   (format "[assignee: %s]" (:login a)))
                                 (:title pr)
                                 (-> pr :pull_request :html_url)])))))))

(defn notify-add-cmd
  "gh notify add <org>/<repo-name> # sets up notifications to this channel on pushes to <repo-name>"
  [{[_ repo] :match chat-source :chat-source}]
  "Not yet implemented")

(defn notify-list-cmd
  "gh notify list # lists repos which are configured to post to this channel on push"
  [{:keys [chat-source]}]
  "Not yet implemented")

(defn notify-remove-cmd
  "gh notify remove <org>/<repo-name> # removes notifications to this channel for <repo-name>"
  [{:keys [chat-source]}]
  "Not yet implemented")

(defn stats-cmd
  "gh stats <org>/<repo-name> # commits, additions, deletions"
  [{[_ org-name repo] :match}]
  ;; github might need some time to crunch the stats,
  ;; in which case the result will simply be polled
  (try-try-again
   {:decay 1.5 :sleep 2000 :tries 4 :return? :truthy?}
   (fn []
     (let [stats (gh/sum-stats org-name repo)]
       (cond (map? stats)
             (let [{:keys [a d c con]} stats]
               (format
                "%s/%s: %s commits, %s additions, %s deletions, %s contributors"
                org-name repo c a d con))

             rb/*last-try*
             (format
              "Crunching the latest data for `%s/%s`, try again in a few moments 🐌"
              org-name repo))))))

(defn contributors-since-cmd
  "gh contributors <org>/<repo-name> since <n> <minutes|hours|days|weeks|months> ago # list contributors in order of commits since a given time"
  {:yb/cat #{:util :info}}
  [{[_ org-name repo n unit] :match chat-source :chat-source}]
  (let [unit (plural unit) ; pluralize if singular
        unit-fn (ns-resolve 'clj-time.core (symbol unit))
        n (read-string n)]
    (if (number? n)
      (let [datetime (-> n unit-fn ago)
            ts (c/to-long datetime)
            sorted-contribs (gh/contributors-since-ts org-name repo ts)]
        (conj
          (map #(format "%s: %s commits, %s additions, %s deletions"
                        (:author %) (:c %) (:a %) (:d %))
               sorted-contribs)
          (format "Contributions on %s/%s since %s" org-name repo
                  (f/unparse date-formatter datetime))))
      (str n " is not a number"))))

(defn show-latest-release-info-cmd
  "gh releases show <org>/<repo-name> # retrieve info about the latest release on a Github repository"
  {:yb/cat #{:util :info}}
  [{[_ org-name repo] :match}]
  (let [latest-release (gh/latest-releases org-name repo)
        status (:status latest-release)]
    (if (nil? status)
      (let [tag (:tag_name latest-release)
            author (get-in latest-release [:author :login])
            published-at (:published_at (-> "YYYY-MM-dd'T'HH:mm:ssZ"
                                            (f/formatter)
                                            (f/parse (:published_at latest-release))))]
        (format "%s/%s latest version, tagged: %s, was published on %s by %s" org-name repo tag
                (f/unparse date-hour-formatter published-at) author))
      (format "No release version info found for %s/%s" org-name repo))))

(defn show-release-info-by-tag-cmd
  "gh releases show <org>/<repo-name> <tag> # retrieve info about a specific release tag on a Github repository"
  {:yb/cat #{:util :info}}
  [{[_ org-name repo tag] :match}]
  (let [tagged-release (gh/release-by-tag org-name repo tag)
        status (:status tagged-release)]
    (if (nil? status)
      (let [author (get-in tagged-release [:author :login])
            published-at (:published_at (-> "YYYY-MM-dd'T'HH:mm:ssZ"
                                            (f/formatter)
                                            (f/parse (:published_at tagged-release))))]
        (format "Release tag: %s from %s/%s, was published on %s by %s" tag org-name repo
                (f/unparse date-hour-formatter published-at) author))
      (format "No release version info found for tag %s on %s/%s" tag org-name repo))))

(defn list-releases-info-cmd
  "gh releases <org>/<repo-name> # list releases for a Github repository"
  {:yb/cat #{:util :info}}
  [{[_ org-name repo] :match}]
  (let [releases (gh/releases org-name repo)]
    (for [release releases]
      (let [tag (:tag_name release)
            author (get-in release [:author :login])
            published-at (:published_at (-> "YYYY-MM-dd'T'HH:mm:ssZ"
                                            (f/formatter)
                                            (f/parse (:published_at release))))]
        (format "Release version tagged: %s, from %s/%s, was published on %s by %s" tag org-name repo
                (f/unparse date-hour-formatter published-at) author)))))

(when (gh/configured?)
  (cmd-hook ["gh" #"^gh|github$"]
            #"feed\s+(\S+)" feed
            #"repos urls\s+(\S+)" repos-urls
            #"repos urls" repos-urls
            #"repos\s+(\S+)" repos
            #"repos" repos
            #"notify\s+list" notify-list-cmd
            #"notify\s+add\s+(\S+)" notify-add-cmd
            #"notify\s+remove\s+(\S+)" notify-remove-cmd
            #"orgs" orgs
            #"statuses" statuses
            #"status$" status
            #"pr\s+(\S+)" pull-requests
            #"stats\s+(\S+)\/(\S+)" stats-cmd
            #"contributors\s+(\S+)\/(\S+)\s+since\s+(\d+)\s+(minutes*|hours*|days*|weeks*|months*)" contributors-since-cmd
            #"tags\s+(\S+)\/(\S+)" tags
            #"branches\s+(\S+)\/(\S+)" branches
            #"releases\s+show\s+(\S+)\/(\S+)\s+(\S+)" show-release-info-by-tag-cmd
            #"releases\s+show\s+(\S+)\/(\S+)" show-latest-release-info-cmd
            #"releases\s+(\S+)\/(\S+)" list-releases-info-cmd))
