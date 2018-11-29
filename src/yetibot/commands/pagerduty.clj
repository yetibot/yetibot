(ns yetibot.commands.pagerduty
  (:require
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
  {:auths {"api_key" (str "Token token=" (-> (config) :value :token))}})

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

(cmd-hook ["pagerduty" #"^pd|pagerduty$"]
  #"teams$" teams-cmd
  #"teams\s+(\S+)" teams-cmd
  #"users$" users-cmd
  #"users\s+(\S+)" users-cmd
  )
