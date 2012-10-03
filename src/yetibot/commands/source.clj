(ns yetibot.commands.source
  (:require [clojure.repl :as r])
  (:use [yetibot.util :only (cmd-hook)]))

(defn lookup-source
  "source <fn> # lookup the source for <fn> in YetiBot's own source or deps"
  [f]
  (or (r/source-fn (symbol f))
      (format "Source not found for %s" f)))

(cmd-hook #"source"
          _ (lookup-source p))
