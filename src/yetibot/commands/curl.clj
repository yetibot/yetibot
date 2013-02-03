(ns yetibot.commands.curl
  (:require [clojure.java.shell :as shell]
            [clojure.string :as s])
  (:use [yetibot.hooks :only [cmd-hook]]))

(defn curl
  "curl <options> <url> # execute standard curl tool"
  {:test #(curl {:args "-I http://www.google.com"})}
  [{opts-and-url :args}]
  (let [curl (partial shell/sh "curl")]
    (:out (apply curl (s/split opts-and-url #"\s")))))

(cmd-hook #"curl"
          #".+" curl)
