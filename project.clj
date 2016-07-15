(defproject yetibot "1.0.0-RC2-SNAPSHOT"
  :description "A command line in your chat, where chat ∈ {irc,slack}."
  :url "https://github.com/devth/yetibot"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :lein-release {:deploy-via :clojars}
  :deploy-repositories [["releases" :clojars]]
  :profiles {:dev {:source-paths ["dev"]}
             :test {}
             :plugins [[lein-git-deps "0.0.1-SNAPSHOT"]]}
  :repl-options {:init-ns yetibot.core.repl
                 :welcome (println "Welcome to the yetibot development REPL!")}
  :jvm-opts ["-server" "-Xmx2G"]
  :dependencies [[org.clojure/clojure "1.7.0"],
                 [yetibot.core "1.0.0-RC6"]

                 ; apis
                 [twitter-api "0.7.6"]

                 ; s3
                 [clj-aws-s3 "0.3.10" :exclusions [joda-time]]

                 ; utils
                 [useful "0.8.3-alpha8"]
                 [org.clojure/tools.cli "0.3.1"]

                 ; NLP
                 [clojure-opennlp "0.3.2"]

                 ; [incanter "1.4.0"]

                 ]
  :plugins [[lein-exec "0.3.5"]
            [lein-cloverage "1.0.7-SNAPSHOT"]
            [lein-ring "0.9.5"]
            [io.sarnowski/lein-docker "1.1.0"]]

  :aliases
  {"version" ["exec" "-ep" "(use 'yetibot.core.version)(print version)"]}
  :pedantic :ignore

  :docker {:image-name "devth/yetibot"}

  :ring {:handler yetibot.webapp.handler/app
         :init    yetibot.webapp.handler/init
         :destroy yetibot.webapp.handler/destroy
         :uberwar-name "yetibot.war"}

  :main yetibot.core.init)
