(defproject yetibot "1.0.0-SNAPSHOT"
            :description "this is both a yeti and a bot."
            :dependencies [[org.clojure/clojure "1.3.0"],
                           [org.clojars.adamwynne/http.async.client "0.4.1"]
                           [robert/hooke "1.1.2"]
                           [clj-campfire "1.0.0"]
                           [org.clojure/data.json "0.1.1"]
                           [org.clojure/tools.namespace "0.1.0"]
                           [clj-time "0.3.2"]
                           [org.clojure/tools.cli "0.1.0"]]
            :dev-dependencies [[clj-stacktrace "0.2.3"]]
            :main yetibot.core)
