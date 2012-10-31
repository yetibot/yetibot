(ns yetibot.commands.curl
  (:require [clojure.java.shell :as shell]
            [clojure.string :as s])
  (:use [yetibot.util :only [cmd-hook]]))

(defn curl
  "curl <options> <url> # execute standard curl tool"
  [opts-and-url]
  (let [curl (partial shell/sh "curl")]
    (:out (apply curl (s/split opts-and-url #"\s")))))

(cmd-hook #"curl"
          #".+" (curl p))
