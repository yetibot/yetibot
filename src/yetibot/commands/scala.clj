(ns yetibot.commands.scala
  (:require
    [taoensso.timbre :refer [info warn error]]
    [yetibot.core.hooks :refer [cmd-hook]]
    [clj-http.client :as client]
    [clojure.string :as s]
    [cheshire.core :as json]
    [kvlt.core :as kvlt]
    [clojure.core.async :as async]))

(def endpoint "https://scastie.scala-lang.org/api/")

(def sbt-config-extra "scalacOptions ++= Seq(\"-deprecation\",  \"-encoding\", \"UTF-8\",  \"-feature\",  \"-unchecked\")")

(def scala-version "2.12.6")

(def platform "Jvm")

(defn make-body
  [scala-str]
  (json/generate-string {:_isWorksheetMode       true
                         :code                   scala-str
                         :target                 {:scalaVersion scala-version
                                                  :$type        platform}
                         :libraries              []
                         :librariesFromList      []
                         :sbtConfigExtra         sbt-config-extra
                         :sbtPluginsConfigExtra  ""
                         :isShowingInUserProfile false}))

(defn try-scala
  "Submits the Scala expression to the evaluation API and returns a
  Server-Sent Events core.async channel for retrieving the result."
  [expr]
  (info "scala:" expr)
  (as-> (str endpoint "run") v
        (client/post v {:content-type :json
                        :accept       :json
                        :body         (make-body expr)})
        (:body v)
        (json/parse-string v true)
        (:base64UUID v)
        (str endpoint "progress-sse/" v)
        (kvlt/event-source! v)))

(defn extract-success
  "Extracts success evaluation info from the SSE-message data."
  [{user-output :userOutput instrumentations :instrumentations}]
  (cond
    (seq user-output) (get-in user-output [:line])
    (seq instrumentations) (->> instrumentations
                                (map (fn [{{v :v className :className} :render}]
                                       (format "%s: %s" v className)))
                                (s/join "\n"))))

(defmulti format-result
          "Returns a dispatch-value based on the SSE data received from the evaluation API."
          (fn [{:keys [compilationInfos isTimeout isDone]}]
            (cond
              (true? isTimeout) :eval-timeout
              (seq compilationInfos) :eval-runtime-error
              (true? isDone) :is-done
              :else :eval-success)))

(defmethod format-result :eval-timeout
  [_]
  (str "The expression timed out"))

(defmethod format-result :eval-runtime-error
  [data]
  (->> data
       :compilationInfos
       (map :message)
       (s/join "\n")))

(defmethod format-result :is-done
  [_]
  :is-done)

(defmethod format-result :eval-success
  [data]
  (extract-success data))

(defn extract-result
  "Extracts the Scala evaluation result from the core.async channel provided
   as argument."
  [chan]
  (async/go-loop [results []]
    (let [result (as-> (async/<! chan) v
                       (json/parse-string (:data v) true)
                       (format-result v))]
      (if (= :is-done result)
        (do
          (async/close! chan)
          (->> results
               (remove nil?)
               (s/join "\n")))
        (recur (conj results result))))))

(defn scala-cmd
  "scala <expression> # evaluate a scala expression"
  {:yb/cat #{:repl}}
  [{expr :match}]
  (async/<!!
    (extract-result (try-scala expr))))

(cmd-hook #"scala"
          #".*" scala-cmd)
