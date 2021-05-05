(defproject yetibot "_"
  :description "A command line in your chat, where chat ∈ {irc,slack}."
  :url "https://github.com/yetibot/yetibot"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}

  :deploy-repositories
  [["releases" {:url "https://clojars.org/repo"
                :username :env/clojars_username
                :password :env/clojars_password
                :sign-releases false}]]

  :jvm-opts ["-Djava.security.policy=.java.policy"]
  :profiles {;; optionally override this profile in profiles.clj to be merged
             ;; into dev profile
             :profiles/dev {}
             :dev [:profiles/dev
                   {:source-paths ["dev"]
                    ;; :exclusions [org.clojure/tools.trace]
                    :plugins [[lein-midje "3.2.1"]
                              [lein-update-dependency "0.1.2"]]
                    :dependencies [[lilactown/punk-adapter-jvm "0.0.10"]
                                   [lambdaisland/kaocha-midje "0.0-5"
                                    :exclusions [midje/midje]]
                                   [org.clojure/tools.trace "0.7.9"]
                                   [midje "1.9.9"]]}]
             :low-mem {:jvm-opts ^:replace ["-Xmx1g" "-server"]}
             :docker {:jvm-opts ["-Djava.security.policy=/usr/src/app/.java.policy"]}
             :uberjar {:uberjar-name "yetibot.jar"
                       :jvm-opts ["-server"]
                       :aot :all}

             :deploy {:deploy-repositories
                      [["releases" {:url "https://clojars.org/repo"
                                    :username :env/clojars_username
                                    :password :env/clojars_password
                                    :sign-releases false}]]}

             :test {:dependencies []}}
  :resource-paths ["resources"]
  :repl-options {:init-ns yetibot.core.repl
                 :timeout 120000
                 :prompt (fn [ns] (str "\u001B[35m[\u001B[34m" ns
                                       "\u001B[35m] \u001B[37mλ:\u001B[m "))
                 :welcome
                 (do
                   (println)
                   (println
                    (str
                     "\u001B[37m"
                     "  Welcome to the Yetibot dev REPL!"
                     \newline
                     "  Use \u001B[35m(\u001B[34mhelp\u001B[35m) "
                     "\u001B[37mto see available commands."
                     \newline
                     \newline
                     "\u001B[35m    λλλ"
                     "\u001B[m"))
                   (println))}

  :dependencies [[org.clojure/clojure "1.10.0"]
                 [yetibot/core "20210505.163750.254da5c"]

                 ; apis
                 [twitter-api "1.8.0"]
                 [clj-aws-s3 "0.3.10" :exclusions [joda-time]]
                 [com.google.cloud/google-cloud-storage "1.100.0"]
                 [pager-duty-api "2.0"]
                 [clj-oauth "1.5.5"]
                 [clojure-interop/java.security "1.0.5"]

                 ; TODO remove this and use data.json instead
                 [cheshire "5.9.0"]

                 ; scraping
                 [org.jsoup/jsoup "1.12.1"]

                 ; utils
                 [org.flatland/useful "0.11.6"
                  ;; depends on 0.7.2 but we want 1.3.2
                  :exclusions [org.clojure/tools.reader]]
                 ; << string interpolation macro
                 [org.clojure/core.incubator "0.1.4"]
                 ; graphql
                 [district0x/graphql-query "1.0.5"]

                 ; polling
                 [robert/bruce "0.8.0"]

                 [org.clojure/data.csv "0.1.4"]

                 ; emojis
                 [com.vdurmont/emoji-java "5.1.1"]

                 ; repls
                 [juji/clojail "1.0.9"]

                 ;encoding
                 [org.clojure/data.codec "0.1.1"]

                 ;; sse
                 [io.nervous/kvlt "0.1.4"]
                 ;; overwrite kvlt's outdated version of aleph
                 [aleph "0.4.6"]

                 ;cowsay
                 [com.github.ricksbrown/cowsay "1.1.0" :classifier "lib"]

                 ;aws
                 [com.cognitect.aws/api "0.8.391"]
                 [com.cognitect.aws/endpoints "1.1.11.670"]
                 [com.cognitect.aws/iam "746.2.533.0"]
                 [com.cognitect.aws/ec2 "770.2.568.0"]
                 [com.cognitect.aws/s3 "762.2.561.0"]]

  :plugins [[lein-inferv "20201028.232949.b461fd0"]
            [lein-pprint "1.3.2"]
            [lein-exec "0.3.7"]
            [lein-environ "1.1.0"]
            [lein-cloverage "1.0.13"]
            [lein-ring "0.12.4"]
            [io.sarnowski/lein-docker "1.1.0"]]

  :aliases {"version" ["exec" "-ep" "(use 'yetibot.core.version)(print version)"]
            "test" ["midje"]}

  ;; :pedantic :ignore

  :docker {:image-name "yetibot/yetibot"}

  :main yetibot.core.init)
