(ns yetibot.commands.curl
  (:require
    [clojure.java.shell :as shell]
    [clojure.string :as s]
    [yetibot.core.hooks :refer [cmd-hook]]))

(defn curl
  "curl <options> <url> # execute standard curl tool"
  {:test (fn []
           (let [calls (atom [])
                 response {:exit 0 :out "HTTP/1.1 200 OK\r\n\r\n" :err ""}
                 output (with-redefs [shell/sh (fn [& args]
                                                (swap! calls conj (vec args))
                                                response)]
                          (curl {:args "-I https://fixture.invalid"}))]
             (assert (= [["curl" "-I" "https://fixture.invalid"]] @calls))
             (assert (= (:out response) output))))}
  [{opts-and-url :args}]
  (let [curl (partial shell/sh "curl")]
    (:out (apply curl (s/split opts-and-url #"\s")))))

(cmd-hook #"curl"
          #".+" curl)
