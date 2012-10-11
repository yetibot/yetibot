(ns yetibot.commands.jenkins
  (:require [http.async.client :as client]
            [clojure.data.json :as json]
            [clojure.contrib.string :as s]
            [robert.hooke :as rh]
            [clj-time.core :as t]
            [clj-time.coerce :as c])
  (:use [yetibot.util :only (cmd-hook)]
        [yetibot.util.http :only (fetch get-json with-client)]))


(def base-uri (System/getenv "JENKINS_URI"))
(def auth {:user (System/getenv "JENKINS_USER")
           :password (System/getenv "JENKINS_API_KEY")
           :preemptive true})
(def default-job (System/getenv "JENKINS_DEFAULT_JOB"))

; helpers
(def job-names
  (memoize
    (fn []
      (with-client (str base-uri "api/json") client/GET auth
                   (client/await response)
                   (let [json (json/read-json (client/string response))]
                     (map (fn [item] (:name item)) (:jobs json)))))))

; API Calls
(defn status [job-name]
  (println (str "Running status with " job-name))
  (with-open [client (client/create-client)]
    (let [uri (str base-uri "job/" job-name "/lastBuild/api/json")
          response (client/GET client uri :auth auth)]
      (println (str "trying to fetch " uri))
      (client/await response)
      (let [unparsed (client/string response)]
        (println (str "found " unparsed))
        (json/read-json unparsed)))))

(defn job-status [job-name]
  "Sends job status info to chat. Sample output:
  SUCCESS at Wed Nov 16. Started by trevor.
  Currently building? false
  [Changeset]"
  (let [json (status job-name)]
    (println json)
    (if-let [building (str (:building json))] ; convert to string so a `false` doesn't give a false-negative
      (let [result (str (:result json))
                   changeset (s/join
                               \newline
                               (map (fn [i]
                                      (let [msg (:msg i)
                                            author (if (seq (:author i))
                                                     (:fullName (:author i)) ; git
                                                     (:user i))]; svn
                                        (str author ": " msg)))
                                    (:items (:changeSet json))))]
        [(:url json)
         (str
           (if result (str result " at "))
           (c/to-date (c/from-long (:timestamp json)))
           ". "
           (:shortDescription (first (:causes (first (:actions json)))))
           ".")
         (str "Currently building: " building)
         ""
         changeset]))))

(defn build
  "jen build <job-name>"
  [job-pattern]
  (let [job-pattern (re-pattern job-pattern)]
    (letfn [(match-job [pattern job] (when (re-find pattern job) job))]
      (if-let [job-name (some (partial match-job job-pattern) (job-names))]
        (let [uri (format "%sjob/%s/build" base-uri job-name)
                  response (fetch uri auth)]
          (str "I sent off a build for " job-name))
        (format "I couldn't match any jobs on  %s. Get it right next time." job-pattern)))))

(defn list-jobs
  ([] (job-names))
  ([n] (take n (job-names))))

(defn list-jobs-matching [match]
  (prn "list jobs matching " match)
  (s/grep (re-pattern match) (job-names)))

(defn build-default-cmd
  "jen build # build default job if configured"
  [] (if default-job
       (build default-job)
       "no default job configured"))

(defn status-cmd
  "
jen status <job-name>       # show Jenkins status for <job-name>
jen status                  # show Jenkins status for default job if configured"
  [& args]
  (if (empty? args)
    (do (prn "use default job" default-job) (job-status default-job))
    (job-status (first args))))

(defn list-cmd
  "
jen list                    # lists all jenkins jobs
jen list <n>                # lists first <n> jenkins jobs
jen list <string>           # lists jenkins jobs containing <string>"
  [& args]
  (if (empty? args)
    (list-jobs)
    (let [arg (first args)
          p-arg (read-string arg)]
      (if (number? p-arg)
        (list-jobs p-arg)
        (list-jobs-matching arg)))))

(cmd-hook #"jen"
          #"^build$" (build-default-cmd)
          #"^build\s(.+)" (build (second p))
          #"^status$" (status-cmd)
          #"^status\s(.+)" (status-cmd (second p))
          #"^list$" (list-cmd)
          #"^list\s(.+)" (list-cmd (second p)))
