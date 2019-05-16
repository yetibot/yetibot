(ns yetibot.commands.pagerduty
  (:require
   [clojure.string :as s :refer [join blank?]]
   [clojure.tools.cli :refer [parse-opts]]
   [clj-time.core :as time :refer [days ago]]
   [clj-time.format :as time.format]
   [schema.core :as sch]
   [pager-duty-api.core :refer [with-api-context set-api-context]]
   [pager-duty-api.api.schedules :as schedules]
   [pager-duty-api.api.teams :as teams]
   [pager-duty-api.api.users :as users]
   [yetibot.core.config :refer [get-config]]
   [clojure.data.json :as json]
   [taoensso.timbre :refer [debug info warn error]]
   [yetibot.core.hooks :refer [cmd-hook]]))

(defn config [] (get-config {:token sch/Str} [:pagerduty]))

(set-api-context
  {:debug false
   :auths {"api_key" (str "Token token=" (-> (config) :value :token))}})

(defn report-if-error
  "Checks the stauts of the HTTP response for 2xx, and if not, looks in the body
   for :errorMessages or :errors. To use this, make sure to use the
   `:throw-exceptions false`, `:content-type :json` and, `:coerce :always`
   options in the HTTP request."
  [req-fn succ-fn]
  (try
    (let [{:keys [body status] :as res} (req-fn)]
      (debug "pagerduty response" status (pr-str body))
      (succ-fn res))
    (catch Exception e
      (let [{:keys [status body] :as error} (ex-data e)
            json-body (try (json/read-str body :key-fn keyword)
                           (catch Exception e nil))]
        (info "pagerduty error" (pr-str e))
        {:result/error
         (str
          (-> json-body :error :message)
          ": "
          (->> json-body :error :errors (join " ")))}))))

(defn teams-cmd
  "pd teams # list PagerDuty teams
   pd teams <query> # list PagerDuty teams matching <query>"
  [{match :match}]
  (debug "teams-cmd" (pr-str match))
  (let [query (when (coll? match) (second match))]
    (debug "teams list" query)
    (report-if-error
     #(teams/teams-get {:query query})
     (fn [{:keys [teams] :as response}]
       (debug "teams response" (pr-str response))
       (if (seq teams)
         {:result/data teams
          :result/value (map :name teams)}
         {:result/error (str "Couldn't find teams"
                             (when-not (blank? query)
                               (str " for " query)))})))))

(defn teams-show-cmd
  "pd teams show <name> # list members of a team"
  [{[_ team-name] :match}]
  (debug "teams show" team-name)
  (report-if-error
   #(teams/teams-get {:query team-name})
   (fn [{[first-team] :teams :as response}]
     (debug "teams show response" (pr-str response))
     (debug "getting user details for team" (pr-str first-team))
     (if first-team
       (let [{:keys [users] :as user-response} (users/users-get {:team-ids [(:id first-team)]})]
         (debug "users for team response" (pr-str user-response))
         {:result/data users
          :result/value (map :name users)})
       {:result/error (str "Couldn't find team for `" team-name "`")}))))

(defn users-cmd
  "pd users # list PagerDuty users
   pd users <query> # list PagerDuty users matching <query>"
  [{match :match}]
  (let [query (when (coll? match) (second match))]
    (report-if-error
     #(users/users-get {:query query})
     (fn [{:keys [users] :as response}]
       (debug "users response" (pr-str response))
       (if (seq users)
         {:result/data users
          :result/value (map :name users)}
         {:result/error (str "No users found"
                             (when-not (blank? query)
                               (str " for " query)))})))))

(defn schedules-cmd
  "pd schedules # list schedules"
  [{match :match}]
  (let [query (when (coll? match) (second match))]
    (report-if-error
     #(schedules/schedules-get {:query query})
     (fn [{:keys [schedules] :as response}]
       (debug "schedules response" (pr-str response))
       (if (seq schedules)
         {:result/data schedules
          :result/value (map :name schedules)}
         {:result/error (str "No schedules found"
                             (when query (str " for `" query "`")))})))))

(defn format-date [date-string]
  (time.format/unparse
    (time.format/formatters :year-month-day)
    (time.format/parse date-string)))

(def schedule-show-opts
  [["-s" "--since YYYY-MM-DD" "Since"]
   ["-u" "--until YYYY-MM-DD" "Until"]])

(defn schedules-show-cmd
  "pd schedules show [-s, --since YYYY-MM-DD] [-u, --until YYYY-MM-DD] <name> # show oncall schedule for a given time period
   Note: <name> can be a partial match.

   If --since is omitted, it defaults to 7 days ago
   If --until is omitted, it defaults to now
   "
  [{[_ possible-opts-and-query] :match}]
  ;; find the schedule
  (let [{:keys [options arguments]} (parse-opts
                                     (s/split possible-opts-and-query #"\s")
                                     schedule-show-opts)
        query (s/join " " arguments)]
    (report-if-error
     #(schedules/schedules-get {:query query})
     (fn [{[first-schedule] :schedules}]
       (if first-schedule
         (let [since (or (:since options) (-> 7 days ago))
               until (or (:until options) (time/now))
               {:keys [schedule]} (schedules/schedules-id-get
                                   (:id first-schedule)
                                   {:until until :since since})
               rendered (-> schedule :final_schedulei
                            :rendered_schedule_entries)]
           {:result/data schedule
            :result/value
            (map (fn [{:keys [user start end]}]
                   (str (format-date start) " - " (format-date end) ": "
                        (:summary user)))
                 rendered)})
         {:result/error (str "Could not find a schedule for `" query "`")})))))

(cmd-hook {"pd" #"pd"
           "pagerduty" #"pagerduty"}
  #"teams\sshow\s+(.+)" teams-show-cmd
  #"teams$" teams-cmd
  #"teams\s+(.+)" teams-cmd
  #"users$" users-cmd
  #"users\s+(.+)" users-cmd
  #"schedules\sshow\s+(.+)$" schedules-show-cmd
  #"schedules\s+(.+)$" schedules-cmd
  #"schedules$" schedules-cmd)
