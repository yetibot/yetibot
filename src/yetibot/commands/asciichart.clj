(ns yetibot.commands.asciichart
  (:require
    [clojure.string :refer [join]]
    [yetibot.hooks :refer [cmd-hook]]))

; TODO: smart padding and scaling
(defn chartify [x]
  (let [x (read-string x)]
    (format "%4d: %s" x (join (repeat x "*")))))

(defn asciichart-cmd
  [{items :opts}]
  (map chartify items))

(cmd-hook #"asciichart"
          _ asciichart-cmd)
