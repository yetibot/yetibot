(ns yetibot.commands.scala
  (:require
    [yetibot.core.hooks :refer [cmd-hook]]
    [clj-http.client :as client]
    [clojure.string :as s]
    [cheshire.core :as json]))

(def endpoint "http://www.scalakata.com/api/com/scalakata/Api/eval")

(defn make-body [scala-str]
  {:code
   (str
     "import com.scalakata._; @instrument class Playground {"
     scala-str
     "}")})

(defn try-scala
  [expr]
  (client/post
    endpoint
    {:body
     (json/generate-string {:request (json/generate-string (make-body expr))})
     :as :json}))

(defn extract-result [resp]
  (let [body (:body resp)]
    (cond
      ;; timeout
      (:timeout body) (str "The expression timed out")
      ;; runtime error(s)
      (seq (:runtimeError body)) (map :message (:runtimeError body))
      ;; compilation error(s)
      (seq (:complilationInfos body)) (mapcat
                                        (fn [[_ msgs]] (map :message msgs))
                                        (:complilationInfos body))
      ;; success
      :else (map (fn [[_ [_ {:keys [repr v]}]]] (or repr v))
                 (:instrumentation body)))))

(defn scala-cmd
  "scala <expression> # evaluate a scala expression"
  {:yb/cat #{:repl}}
  [{expr :match}]
  (extract-result (try-scala expr)))

(cmd-hook #"scala"
          #".*" scala-cmd)
