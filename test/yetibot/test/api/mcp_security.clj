(ns yetibot.test.api.mcp-security
  (:require [clojure.java.io :as io]
            [clojure.java.shell :as shell]
            [clojure.string :as string]
            [midje.sweet :refer [fact =>]]))

;; JDK 24+ removed SecurityManager; exercise real protection on JDK 17.
;; A fresh subprocess prevents earlier tests from hiding callback restrictions.
(when (= 17 (.feature (Runtime/version)))
  (fact "MCP JSON/SSE still work after sandbox setup without disabling protection"
    (let [java (str (io/file (System/getProperty "java.home") "bin" "java"))
          policy (.getAbsolutePath (io/file ".java.policy"))
          {:keys [exit out err]}
          (shell/sh java (str "-Djava.security.policy=" policy)
                    "-cp" (System/getProperty "java.class.path")
                    "clojure.main" "test/fixtures/mcp_security.clj")]
      (when-not (zero? exit)
        (println "MCP SecurityManager regression failed:" (subs err 0 (min 4000 (count err)))))
      (fact
        exit => 0
        (string/includes? out "MCP_SECURITY_OK") => true))))
