(ns yetibot.repl
  "Not used. Deprecated in favor of yetibot.core.repl. Remains as
   scratch."
  (:require
    [yetibot.core.init]
    [yetibot.core.repl :refer :all]
    [yetibot.core.webapp.handler :refer [app init destroy]]
    [yetibot.core.loader :refer [load-commands]]
    ; [ring.adapter.jetty :refer [run-jetty]]
    [ring.middleware.reload :as reload]
    [environ.core :refer [env]]

    [clojure.tools.namespace.find :as ns]
    [clojure.java.classpath :as cp]
    ))

(defn find-namespaces [pattern]
  (let [all-ns (ns/find-namespaces (cp/classpath))]
    (filter #(re-matches pattern (str %)) all-ns)))

(map find-namespaces yetibot-command-namespaces)

(find-namespaces yetibot-command-namespaces)


(let [m1 {:a 1}]
  (> a 0)
  (-> m1 :a))




(use 'yetibot.core.loader)


