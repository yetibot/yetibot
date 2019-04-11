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
    (let [sb (sandbox [] :init `(def ~'data ~data))
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

  (let [input-code "(reduce + (range 30))"]
    '(read-string #'input-code)
    )

  (let [data {:foo [1]}
        args "1"]
      `(let [~'data ~data]
         (eval (safe-read ~args))))

  (let [data {:foo [1]}
        args "data"
        sb (sandbox [] :init `(def ~'data ~data))]
    (sb (safe-read args)))

  )
