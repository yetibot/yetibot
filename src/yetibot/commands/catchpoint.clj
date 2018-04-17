(ns yetibot.commands.catchpoint
  (:require
    [yetibot.api.catchpoint :as api]
    [clojure.string :as s]
    [clojure.core.strint :refer [<<]]
    [yetibot.core.hooks :refer [cmd-hook]]))

(defn tests-cmd
  "catchpoint tests # Retrieve the list of all tests from Catchpoint"
  [_]
  (api/tests))

(defn matched-tests-cmd
  "catchpoint tests <pattern> # Retrieve tests with name matching <pattern>"
  [{[_ test-pattern] :match}]
  (api/resolve-tests test-pattern)
  )

(defn tests-show-cmd
  "catchpoint tests show <test-name-or-id> # Show details for test by name or ID"
  [{[_ test-id-or-match] :match}]
  (if-let [test-id (api/resolve-test-id test-id-or-match)]
    (let [test-data (-> test-id api/test-by-id :body)]
      {:result/data test-data
       :result/value (api/format-test-data test-data)})
    {:result/error (<< "Couldn't find a test matching ~{test-id-or-match}")}))

(defn performance-cmd
  "catchpoint performance <test-name-or-id> # Show performance data for test by name or ID"
  [{[_ test-id-or-match] :match}]

  )

(cmd-hook #"catchpoint"
  #"tests\s+show\s+(.+)" tests-show-cmd
  #"tests\s+(.+)" matched-tests-cmd
  #"tests$" tests-cmd
  #"performance\s+(.+)" performance-cmd)
