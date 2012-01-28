(ns yetibot.commands.jenkins
  (:require [http.async.client :as client]
            [clojure.data.json :as json]
            [clojure.contrib.string :as s]
            [robert.hooke :as rh]
            [clj-time.core :as t]
            [clj-time.coerce :as c])
  (:use [yetibot.util :only (cmd-hook with-client)]))


(def base-uri (System/getenv "JENKINS_URI"))
(def auth {:user (System/getenv "JENKINS_USER")
           :password (System/getenv "JENKINS_PASS")})


; helpers
(def job-names
  (memoize
    (fn []
      (with-client (str base-uri "api/json") #'client/GET auth
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
      (json/read-json (client/string response)))))

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


; TODO - it'd be cool to poll the status in the background 
; to see if/when the build started, or whether it's in the queue behind
; some other jobs
(defn build
  "jen build <job-name>"
  [job-name]
  (println (str "Build: " job-name))
  (with-open [client (client/create-client)]
    (let [uri (str base-uri "job/" job-name "/build")
              response (client/GET client uri :auth auth)]
      (client/await response)
      (str "I sent off a build for " job-name))))

(defn list-jobs
  ([] (list-jobs 20)) ; show 20 by default
  ([n] (if (nil? n) 
         (list-jobs)
         (do
           (println (str "List " n " jobs"))
           (take n (job-names))))))

(defn list-jobs-matching [match]
  (println (str "list jobs matching " match))
  (s/grep (re-pattern match) (job-names)))

(defn status-cmd
  "jen status <job-name>"
  [job-name]
  (job-status job-name))

(defn list-cmd 
  "
jen list                    # lists first 20 jenkins jobs
jen list <n>                # lists first <n> jenkins jobs
jen list <string>           # lists jenkins jobs containing <string>"
  [arg]
  (if (empty? arg)
    (list-jobs)
    (let [arg (read-string arg)]
      (if (number? arg)
        (list-jobs arg)
        (list-jobs-matching (name arg))))))

(cmd-hook #"jen"
          #"^build\s(.+)" (build (second p))
          #"^status\s(.+)" (status-cmd (second p))
          #"^list\s(.+)" (list-cmd (second p)))
