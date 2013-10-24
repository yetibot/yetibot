(ns yetibot.loader
  (:require
    [taoensso.timbre :refer [info warn error]]
    [clojure.stacktrace :as st]
    [clojure.tools.namespace.find :as ns]
    [clojure.java.classpath :as cp]))

(defn find-namespaces [pattern]
  (let [all-ns (ns/find-namespaces (cp/classpath))]
    (filter #(re-matches pattern (str %)) all-ns)))

(def yetibot-command-namespaces
  [#"^yetibot\.commands.*" #"^plugins.*commands.*"])

(def yetibot-observer-namespaces
  [#"^yetibot\.observers.*" #"^plugins.*observers.*"])

(def yetibot-all-namespaces
  (merge
    (map last [yetibot-command-namespaces
               yetibot-observer-namespaces])
    ; with a negative lookahead assertion
    #"^yetibot\.(.(?!(core)))*"))

(defn load-ns [arg]
  (info "Loading" arg)
  (try (require arg :reload)
       (catch Exception e
         (warn "WARNING: problem requiring" arg "hook:" (.getMessage e))
         (st/print-stack-trace (st/root-cause e) 15))))

(defn find-and-load-namespaces
  "Find namespaces matching ns-patterns: a seq of regex patterns. Load the matching
   namespaces and return the seq of matched namespaces."
  [ns-patterns]
  (let [nss (flatten (map find-namespaces ns-patterns))]
    (dorun (map load-ns nss))
    nss))

(defn load-commands []
  (find-and-load-namespaces yetibot-command-namespaces))

(defn load-observers []
  (find-and-load-namespaces yetibot-observer-namespaces))

(defn load-commands-and-observers []
  (load-observers)
  (load-commands))

(defn reload-all-yetibot
  "Reloads all of YetiBot's namespaces, including plugins. Loading yetibot.core is
   temporarily disabled until we can figure out to unhook and rehook
   handle-campfire-event and handle-cmd"
  []
  ;;; (refresh))
  ;; only load commands and observers
  ;; until https://github.com/devth/yetibot/issues/75 is fixed
  ;;; (find-and-load-namespaces yetibot-all-namespaces))
  (load-commands-and-observers))
