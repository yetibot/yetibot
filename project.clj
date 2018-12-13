(defproject yetibot "0.4.74-SNAPSHOT"
  :description "A command line in your chat, where chat ∈ {irc,slack}."
  :url "https://github.com/yetibot/yetibot"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :deploy-repositories [["releases" :clojars]]
  :jvm-opts ["-Djava.security.policy=.java.policy"]
  :profiles {;; optionally override this profile in profiles.clj to be merged
             ;; into dev profile
             :profiles/dev {}
             :dev [:profiles/dev
                   {:source-paths ["dev"]
                    :exclusions [org.clojure/tools.trace]
                    :plugins [[lein-midje "3.2.1"]]
                    :dependencies [[org.clojure/tools.trace "0.7.9"]
                                   [midje "1.9.4"]]}]
             :low-mem {:jvm-opts ^:replace ["-Xmx1g" "-server"]}
             :uberjar {:uberjar-name "yetibot.jar"
                       :jvm-opts ["-server"]
                       :aot :all}
             :test {:dependencies []}}
  :resource-paths ["resources"]
  :repl-options {:init-ns yetibot.core.repl
                 :timeout 120000
                 :welcome (println "Welcome to the yetibot development REPL!")}
  :dependencies [[org.clojure/clojure "1.9.0"]
                 [yetibot.core "0.4.62"]

                 ; apis
                 [twitter-api "1.8.0"]
                 [clj-aws-s3 "0.3.10" :exclusions [joda-time]]
                 [com.google.cloud/google-cloud-storage "1.49.0"]
                 [pager-duty-api "2.0"]

                 ; scraping
                 [org.jsoup/jsoup "1.11.3"]

                 ; utils
                 [org.flatland/useful "0.11.5"]
                 ; << string interpolation macro
                 [org.clojure/core.incubator "0.1.4"] 
                 ; graphql
                 [district0x/graphql-query "1.0.5"]

                 ; polling
                 [robert/bruce "0.8.0"]

                 [org.clojure/data.csv "0.1.4"]

                 ; emojis
                 [com.vdurmont/emoji-java "4.0.0"]

                 ; repls
                 [clojail "1.0.6"
                  ;; clojail hasn't been updated in a long time, so exclude its
                  ;; deps
                  :exclusions [org.clojure/clojure
                               org.flatland/useful
                               ;; Note: excluding bultitude disables clojail's
                               ;; `blanket` feature
                               bultitude
                               ]]
                 [bultitude "0.2.8"]

                 ;encoding
                 [org.clojure/data.codec "0.1.1"]]

  :plugins [[lein-exec "0.3.5"]
            [lein-environ "1.0.3"]
            [lein-cloverage "1.0.13"]
            [lein-ring "0.9.5"]
            [io.sarnowski/lein-docker "1.1.0"]]

  :aliases { "version" ["exec" "-ep" "(use 'yetibot.core.version)(print version)"]
             "test" ["midje"]}

  ;; :pedantic :ignore

  :docker {:image-name "yetibot/yetibot"}

  :main yetibot.core.init)
