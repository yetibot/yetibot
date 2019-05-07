(ns yetibot.commands.pagerduty
  (:require
    [clojure.tools.cli :refer [parse-opts]]
    [clj-time.core :as time :refer [days ago]]
    [clj-time.format :as time.format]
    [schema.core :as sch]
    [clojure.string :as s]
    [pager-duty-api.core :refer [with-api-context set-api-context]]
    [pager-duty-api.api.schedules :as schedules]
    [pager-duty-api.api.teams :as teams]
    [pager-duty-api.api.users :as users]
    [yetibot.core.config :refer [get-config]]
    [taoensso.timbre :refer [info warn error]]
    [yetibot.core.hooks :refer [cmd-hook]]))

(defn config [] (get-config {:token sch/Str} [:pagerduty]))

(set-api-context
  {:debug false
   :auths {"api_key" (str "Token token=" (-> (config) :value :token))}})

(defn teams-cmd
  "pd teams # list PagerDuty teams
   pd teams <query> # list PagerDuty teams matching <query>"
  [{match :match}]
  (let [query (when (coll? match) (second match))
        {:keys [teams] :as response} (teams/teams-get {:query query})]
    (if teams
      {:result/data teams
       :result/value (map :name teams)}
      {:result/error (str "Error fetching teams: " (pr-str response))})))

(defn teams-show-cmd
  "pd teams show <name> # list members of a team"
  [{[_ team-name] :match}]
  (let [{[first-team] :teams} (teams/teams-get {:query team-name})
        _ (info (:id first-team))]
    (if first-team
      (let [{:keys [users]} (users/users-get {:team-ids [(:id first-team)]})]
        {:result/data users
         :result/value (map :name users)})
      {:result/error (str "Couldn't find team for `" team-name "`")})))

(defn users-cmd
  "pd users # list PagerDuty users
   pd users <query> # list PagerDuty users matching <query>"
  [{match :match}]
  (let [query (when (coll? match) (second match))
        {:keys [users] :as response} (users/users-get {:query query})]
    (if users
      {:result/data users
       :result/value (map :name users)}
      {:result/error (str "Error fetching users: " (pr-str response))})))

(defn schedules-cmd
  "pd schedules # list schedules"
  [{match :match}]
  (let [query (when (coll? match) (second match))
        {:keys [schedules]} (schedules/schedules-get {:query query})]
    (if (seq schedules)
      {:result/data schedules
       :result/value (map :name schedules)}
      {:result/error (str "No schedules found"
                          (when query " for `" query "`"))})))

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
        query (s/join " " arguments)
        {[first-schedule] :schedules} (schedules/schedules-get {:query query})]
    (if first-schedule
      (let [since (or (:since options) (-> 7 days ago))
            until (or (:until options) (time/now))
            {:keys [schedule]} (schedules/schedules-id-get
                                 (:id first-schedule)
                                 {:until until :since since})
            rendered (-> schedule :final_schedule :rendered_schedule_entries)]
        {:result/data schedule
         :result/value
         (map (fn [{:keys [user start end]}]
                (str (format-date start) " - " (format-date end) ": "
                     (:summary user)))
              rendered)})
      {:result/error (str "Could not find a schedule for `" query "`")})))

(cmd-hook #{"pd" #"pd"
            "pagerduty" #"pagerduty"}
  #"teams\sshow\s+(\S+)" teams-show-cmd
  #"teams$" teams-cmd
  #"teams\s+(\S+)" teams-cmd
  #"users$" users-cmd
  #"users\s+(\S+)" users-cmd
  #"schedules\sshow\s+(\S.+)$" schedules-show-cmd
  #"schedules\s+(\S.+)$" schedules-cmd
  #"schedules$" schedules-cmd)
