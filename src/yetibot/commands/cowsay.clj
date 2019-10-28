(ns yetibot.commands.cowsay
  (:require
   [yetibot.core.hooks :refer [cmd-hook]])
  (:import
   [com.github.ricksbrown.cowsay Cowloader]
   [com.github.ricksbrown.cowsay Cowsay]))

(def default-cow "cow")

(defn cowsay-list
  {:doc "cowsay -l # return list of available cows"
   :yb/cat #{:fun}}
  [_]
  (vec (Cowloader/listAllCowfiles)))

(defn cowsay-cmd
  {:doc "cowsay -f <cow> <text> # outputs text with cowsay using specified <cow>"
   :yb/cat #{:fun}}
  [{[_ cow text] :match}]
  (Cowsay/say (into-array ["-f" cow text])))

(defn cowsay-default-cmd
  {:doc "cowsay <text> # outputs text with cowsay"
   :yb/cat #{:fun}}
  [{match :match}]
  (Cowsay/say (into-array ["-f" default-cow match])))

(cmd-hook "cowsay"
          #"-l" cowsay-list
          #"-f\s+(.+)\s+(.+)" cowsay-cmd
          #".+" cowsay-default-cmd)
