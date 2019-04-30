(ns yetibot.commands.clojure
  (:require
    [clojure.string :as s]
    [clojail.core :refer [sandbox safe-read]]
    [yetibot.core.hooks :refer [cmd-hook]]
    [taoensso.timbre :refer [error debug info color-str]]
    [yetibot.core.util.http :refer [get-json map-to-query-string]]))

(defn clojure-cmd
  "clj <expression> # evaluate a clojure expression"
  {:yb/cat #{:util}}
  [{:keys [args data]}]
  (try
    ;; create a sandbox with data from pipe available
    (let [;; if `data` is a lazy seq it will throw an exception like:
          ;; clojure.lang.ArityException: Wrong number of args (21) passed to:
          ;; clojure.lang.PersistentHashMap
          ;; so convert it to a vector before serializing it into Clojail
          data (if (sequential? data) (vec data) data)
          sb (sandbox [] :init `(def ~'data ~data))
          result (sb (safe-read args))]
      {:result/data result
       :result/value (pr-str result)})
    (catch Throwable e
      (info "Clojail erroed" e)
      (throw e)
      e)))

(cmd-hook #"clj"
          #"\S*" clojure-cmd)

(comment

  (def sb (sandbox [] :timeout 5000))

  (def data {:foo [1 2 3]})

  (sb data)

  (sb (:foo data))

  (let [data {:foo [1]}
        args "1"]
    `(let [~'data ~data]
       (eval (safe-read ~args))))

)
