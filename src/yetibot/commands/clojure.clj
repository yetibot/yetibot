(ns yetibot.commands.clojure
  (:require
    [yetibot.core.loader :refer [find-namespaces]]
    [clojure.string :as s]
    [clojail.core :refer [sandbox safe-read]]
    [clojail.testers :refer [secure-tester blacklist-nses blanket]]
    [yetibot.core.hooks :refer [cmd-hook]]
    [taoensso.timbre :refer [error debug info color-str]]
    [yetibot.core.util.http :refer [get-json map-to-query-string]]))

(def
  ^{:doc "Dynamic var to bind data into the Clojail sandbox"
    :dynamic true}
  *current-data* nil)

(def sandbox-tester
  (conj secure-tester
        ;; disallow poking at internal Yetibot stuff
        (blacklist-nses (find-namespaces #"yetibot\..+$"))))

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
          sb (binding [*current-data* data]
               (sandbox sandbox-tester
                        :init `(def ~'data
                                    yetibot.commands.clojure/*current-data*)))
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

  (let [args "(count data)"
        _ (prn (count data))
        sb (sandbox [] :init `(def ~'data ~data))]
    (sb (safe-read args)))

  ;;
  (def sample-data
    {:id 1234,
     :members
     '({:id 1001, :tid 2001}
       {:id 1002, :tid 2002}
       {:id 1003, :tid 2003}
       {:id 1004, :tid 2004}
       {:id 1005, :tid 2005}
       {:id 1006, :tid 2006}
       {:id 3001, :tid 4001}
       {:id 3002, :tid 4002}
       {:id 3003, :tid 4003}
       {:id 3004, :tid 4004}
       {:id 3005, :tid 4005}
       {:id 3006, :tid 4006})})

  (binding [*current-data* sample-data]
    (let [args "(:members data)"
          sb (sandbox [] :init `(def ~'data yetibot.commands.clojure/*current-data*))]
      (sb (safe-read args)))))
