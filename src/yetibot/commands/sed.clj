(ns yetibot.commands.sed
  (:require
    [clojure.string :as s]
    [yetibot.core.hooks :refer [cmd-hook]]))

(defn sed-cmd
  "sed s/<search-pattern>/<replace-pattern> # replace <search-pattern> with <replace-pattern>
   sed s/<search-pattern/ # replace <search-pattern> with nothing"
  [{[_ sp rp str] :match}]
  (let [sp (re-pattern sp)]
    (s/replace str sp rp)))

(cmd-hook ["sed" #"^sed"]
          #"^s\/(.+)\/(\S*)\s+(.*)" sed-cmd)
