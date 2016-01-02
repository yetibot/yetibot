(ns yetibot.commands.sleep
  (:require
    [taoensso.timbre :refer [info warn error]]
    [yetibot.core.hooks :refer [cmd-hook]]))

(def max-sleep 10000)

(defn sleep-cmd
  "sleep <n> # sleep for <n> milliseconds up to 10,000"
  {:yb/cat #{:util}}
  [{:keys [match]}]
  (let [wait (min max-sleep (read-string match))]
    (Thread/sleep wait)
    (format "Slept for %,d milliseconds" wait)))

(cmd-hook ["sleep" #"^sleep$"]
  #"\d+" sleep-cmd)
