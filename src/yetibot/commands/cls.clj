(ns yetibot.commands.cls
  (:use [yetibot.util]))

(def cls-image "http://f.cl.ly/items/1P2P0y0M0m0C413x0a1l/cls.jpg")

(defn clear-screen-cmd
  "cls # clear screen after your co-worker posts something inappropriate"
  [] cls-image)

(cmd-hook #"cls"
          #".*" (clear-screen-cmd))
