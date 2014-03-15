(ns yetibot.commands.sed
  (:require
    [clojure.string :as s]
    [yetibot.core.hooks :refer [cmd-hook]]))

(defn sed-cmd
  "sed s/<search-pattern>/<replace-pattern> <string> # replace <search-pattern> with <replace-pattern> on piped contents
   sed s/<search-pattern/ <string> # replace <search-pattern> with nothing on piped contents"
  [{[_ sp rp] :match raw :raw}]
  (prn raw)
  (let [re-raw (re-pattern (str " " raw "$"))
        rp (s/replace rp re-raw "") ; clear out the appended args on rp - this is mainly to support spaces in rp
        sp (re-pattern sp)]
    (s/replace raw sp rp)))

(cmd-hook ["sed" #"^sed"]
          #"^s\/(.+)\/(.*)" sed-cmd)

