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

;; TODO move this and yetibot.commands.jira/success? to http util in
;; yetibot.core
(defn success? [status] (re-find #"^2" (or (str status) "2")))

(defn report-if-error
  "Given a response, try to detect if it's non-successful, and if so report the
   error; otherwise return nil"
  [response]
  (when (map? response)
    (when-let [{:keys [status body]} response]
      (when status ;; the response might not even containa status if it succeeded
        (when-not (success? status)
          {:result/error
           (format "%s - %s" status (:message body))})))))

(defn feed
  "gh feed <org-name> # list recent activity for <org-name>"
  [{[_ org-name] :match}]
  (let [response (gh/events org-name)]
    (or
      (report-if-error response)
      {:result/value (gh/fmt-events response)
       :result/data response})))

(defn repos
  "gh repos # list repos for the first configured org
   gh repos <org-name> # list repos under <org-name>"
  [{match :match}]
  (let [org-name (if (sequential? match)
                   (second match)
                   (first (gh/org-names)))
        repos-response (gh/repos org-name)]
    (info "repos response" (pr-str repos-response))
    (or
      (report-if-error repos-response)
      {:result/data repos-response
       :result/value
       (map
         (fn [{:keys [description html_url]
               repo-name :name}]
           (format "%s/%s - %s %s" org-name repo-name html_url
                   (or description "")))
         repos-response)})))

(defn orgs
  "gh orgs # show configured orgs"
  [_] (gh/org-names))

(defn tags
  "gh tags <org-name>/<repo> # list the tags for <org-name>/<repo>"
  [{[_ org-name repo] :match}]
  (let [response (gh/tags org-name repo)]
    (or (report-if-error response)
        {:result/data response
         :result/value (map :name response)})))

(defn branches
  "gh branches <org-name>/<repo> # list branches for <org-name>/<repo>"
  [{[_ org-name repo] :match}]
  (let [response (gh/branches org-name repo)]
    (or (report-if-error response)
        {:result/value (map :name response)
         :result/data response})))

(defn- fmt-status [{:keys [page status]}]
  (str (:updated_at page) ": " (:description status)))

(defn status
  "gh status # show GitHub's current system status"
  [_]
  (let [response (get-json "https://kctbh9vrtdwd.statuspage.io/api/v2/status.json")]
    {:result/value (fmt-status response)
     :result/data response}))

(defn incidents
  "gh incidents # show all recent GitHub system status messages"
  [_]
  (let [response
        (get-json "https://kctbh9vrtdwd.statuspage.io/api/v2/incidents.json")]
    {:result/data response
     :result/value
     (let [{:keys [incidents]} response]
       (map (fn [{incident-name :name
                  :keys [status incident_updates]}]
              (str
                (format "*[%s] %s*" status incident-name)
                \newline
                (s/join
                  \newline
                  (map
                    (fn [{:keys [display_at body]}]
                      (format "%s: %s" display_at body))
                    incident_updates))))
            incidents))}))

(defn pull-requests
  "gh pr <org-name> # list open pull requests for <org-name>"
  [{[_ org-name] :match}]
  (let [prs (gh/search-pull-requests org-name "" {:state "open"})]
    (or
      (report-if-error prs)
      {:result/data prs
       :result/value
       (->> prs
            :items
            (map (fn [pr]
                   (s/join
                     " "
                     (remove nil?
                             [(format "[%s]" (-> pr :user :login))
                              (when-let [a (:assignee pr)]
                                (format "[assignee: %s]" (:login a)))
                              (:title pr)
                              (-> pr :pull_request :html_url)])))))})))

(comment
  ;; TODO!
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
  )

(defn stats-cmd
  "gh stats <org>/<repo-name> # commits, additions, deletions"
  [{[_ org-name repo] :match}]
  ;; github might need some time to crunch the stats,
  ;; in which case the result will simply be polled
  (try-try-again
   {:decay 1.5 :sleep 2000 :tries 6 :return? :truthy?}
   (fn []
     (let [stats (gh/sum-stats org-name repo)]
       (cond
         (map? stats)
         (let [{:keys [a d c con]} stats]
           {:result/data stats
            :result/value
            (format
              "%s/%s: %s commits, %s additions, %s deletions, %s contributors"
              org-name repo c a d con)})

         rb/*last-try*
         {:result/error
          (format
            "Crunching the latest data for `%s/%s`, try again in a few moments 🐌"
            org-name repo)})))))

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
        {:result/data sorted-contribs
         :result/value
         (conj
           (map #(format "%s: %s commits, %s additions, %s deletions"
                         (:author %) (:c %) (:a %) (:d %))
                sorted-contribs)
           (format "Contributions on %s/%s since %s" org-name repo
                   (f/unparse date-formatter datetime)))})
      {:result/error (str n " is not a number")})))

(defn format-release
  "Displays information about a release"
  [release org-name repo]
  (if (nil? (:status release))
    (let [tag (:tag_name release)
          author (get-in release [:author :login])
          published-at (-> "YYYY-MM-dd'T'HH:mm:ssZ"
                           (f/formatter)
                           (f/parse (:published_at release)))
          body (:body release)]
      (format "%s/%s `%s` published on %s by %s\n %s"
              org-name repo tag
              (f/unparse date-hour-formatter published-at) author body))
    {:result/error
     (format "No release version info found for %s/%s" org-name repo)}))

(defn show-latest-release-info-cmd
  "gh releases show <org>/<repo-name> # retrieve info about the latest release on a Github repository"
  {:yb/cat #{:util :info}}
  [{[_ org-name repo] :match}]
  (let [release (gh/latest-releases org-name repo)]
    (or
      (report-if-error release)
      {:result/data release
       :result/value (format-release release org-name repo)})))

(defn show-release-info-by-tag-cmd
  "gh releases show <org>/<repo-name> <tag> # retrieve info about a specific release tag on a Github repository"
  {:yb/cat #{:util :info}}
  [{[_ org-name repo tag] :match}]
  (let [release (gh/release-by-tag org-name repo tag)]
    (or
      (report-if-error release)
      {:result/data release
       :result/value (format-release release org-name repo)})))

(defn list-releases-info-cmd
  "gh releases <org>/<repo-name> # list releases for a Github repository"
  {:yb/cat #{:util :info}}
  [{[_ org-name repo] :match}]
  (if-let [releases (gh/releases org-name repo)]
    (or
      (report-if-error releases)
      {:result/value
       (for [release releases]
         (let [tag (:tag_name release)
               author (get-in release [:author :login])
               published-at (when-let [published (:published_at release)]
                              (-> "YYYY-MM-dd'T'HH:mm:ssZ"
                                  (f/formatter)
                                  (f/parse (:published_at release))))]
           (format
             "%s/%s `%s` published on %s by %s"
             org-name repo tag
             (f/unparse date-hour-formatter published-at)
             author)))
       :result/data releases})
    {:result/error
     (format "No releases found on %s/%s" org-name repo)}))

(defn search-code-cmd
  "gh search <query> # search GitHub code for <query>"
  [{[_ query] :match}]
  (let [{items :items :as result} (gh/search-code query)]
    {:result/data result
     :result/collection-path [:items]
     :result/value (map :html_url items)}))

(defn search-repos-cmd
  "gh search repos <query> # search GitHub repos for <query>"
  [{[_ query] :match}]
  (let [{items :items :as result} (gh/search-repos query)]
    {:result/data result
     :result/collection-path [:items]
     :result/value (map :html_url items)}))

(defn search-topics-cmd
  "gh search topics <query> # search GitHub topics for <query>"
  [{[_ query] :match}]
  (let [{items :items :as result} (gh/search-topics query)]
    {:result/data result
     :result/collection-path [:items]
     :result/value (map (fn [topic]
                          (str
                           (when (:featured topic) "✅ ")
                           (:name topic)
                           (when-let [desc (:short_description topic)]
                             (str " - " desc))
                           " https://github.com/topics/" (:name topic)
                           ))
                        items)}))

(when (gh/configured?)
  (cmd-hook {"gh" #"gh"
             "github" #"github"}
    #"feed\s+(\S+)" feed
    #"^search\s+topics\s+(.+)" search-topics-cmd
    #"^search\s+repos\s+(.+)" search-repos-cmd
    #"^search\s+code\s+(.+)" search-code-cmd
    #"^search\s+(.+)" search-code-cmd ;; default search
    #"^repos\s+(\S+)" repos
    #"^repos" repos
    ;; TODO
    ;; #"notify\s+list" notify-list-cmd
    ;; #"notify\s+add\s+(\S+)" notify-add-cmd
    ;; #"notify\s+remove\s+(\S+)" notify-remove-cmd
    #"orgs" orgs
    #"incidents" incidents
    #"status$" status
    #"pr\s+(\S+)" pull-requests
    #"stats\s+(\S+)\/(\S+)" stats-cmd
    #"contributors\s+(\S+)\/(\S+)\s+since\s+(\d+)\s+(minutes*|hours*|days*|weeks*|months*)" contributors-since-cmd
    #"tags\s+(\S+)\/(\S+)" tags
    #"branches\s+(\S+)\/(\S+)" branches
    #"releases\s+show\s+(\S+)\/(\S+)\s+(\S+)" show-release-info-by-tag-cmd
    #"releases\s+show\s+(\S+)\/(\S+)" show-latest-release-info-cmd
    #"releases\s+(\S+)\/(\S+)" list-releases-info-cmd))
