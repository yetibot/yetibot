(ns yetibot.commands.or
  (:require
    [clojure.string :refer [blank?]]
    [taoensso.timbre :refer [info warn error]]
    [yetibot.core.hooks :refer [cmd-hook]]))

(defn or-cmd
  "or <text> # if piped args passed in "
  {:yb/cat #{:util}}
  [{:keys [raw match]}]
  (info "or:" raw match)
  (if (or (nil? raw) (empty? raw))
    match
    raw))

(cmd-hook ["or" #"^or$"]
  _ or-cmd)
