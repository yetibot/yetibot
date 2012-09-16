(ns yetibot.commands.curl
  (:require [clojure.java.shell :as shell]
            [clojure.contrib.string :as s])
  (:use [yetibot.util :only [cmd-hook]]))

(defn curl
  "curl <options> <url> # execute standard curl tool"
  [opts-and-url]
  (let [curl (partial shell/sh "curl")]
    (:out (apply curl (s/split #"\s" opts-and-url)))))

(cmd-hook #"curl"
          #".+" (curl p))
