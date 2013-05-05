(ns yetibot.commands.alias
  (:require [yetibot.hooks :refer [cmd-hook]]))

(defn- wire-alias
<<<<<<< HEAD
  "example input:
   alias i90 = echo random | http://images.wsdot.wa.gov/nw/090vc00508.jpg?nocache=%s&.jpg
   Note: this isn't supported yet:
   alias grid x = !repeat 10 `repeat 10 #{x} | join`"
  [a]
  (let [[_ a-name a-args a-cmd]  (re-find #"(\w+)\s+(.*)\=\s+(.+)" a)]
    (cmd-hook (re-pattern a-name)
              _ (fn [{:keys [user]}]
                  (yetibot.core/parse-and-handle-command a-cmd user)))
    (format "%s alias created" a-name)))

(def create-alias
  "alias <cmd> # alias a cmd, where <cmd> is a regular command expression"
  (comp wire-alias :args))
=======
  ([] )
  ([a]))

; !eval (and (yetibot.hooks/cmd-hook #"i90" _ (fn [_] (str
; "http://images.wsdot.wa.gov/nw/090vc00508.jpg?nocache=" (rand 1000) "&.jpg")))
; "hooked i90") !eval (and (yetibot.hooks/cmd-hook #"ian" _ (fn [_] (str
; "http://www.emissiontestwa.com/e/ImageHandler.ashx?StationID=7&nocache=" (rand
; 1000) "&.jpg"))) "hooked ian")

(defn create-alias [_]
  ; save new alias
  )

; Aliases can be defined like this:
; alias i90 = http://images.wsdot.wa.gov/nw/090vc00508.jpg?nocache=(rand 1000)&.jpg
; alias grid x = !repeat 10 `repeat 10 #{x} | join`
;
; - Any expresssion inside matched parens will be evaluated. If the evaluation fails,
; the literal sexp will be left in the string.
; - An alias that begins with ! will be run as a command
>>>>>>> 13bbcf1... WIP

(cmd-hook #"alias"
          _ create-alias)
