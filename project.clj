(defproject yetibot "0.1.19-SNAPSHOT"
  :description "A command line in your chat, where chat ∈ {irc,campfire}."
  :url "https://github.com/devth/yetibot"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :lein-release {:deploy-via :clojars}
  :profiles {:dev {:source-paths ["dev"]}
             :test {}
             :plugins [[lein-git-deps "0.0.1-SNAPSHOT"]]}
  :repl-options {:init-ns yetibot.core.repl
                 :welcome (println "Welcome to the YetiBot development repl!")}
  :jvm-opts ["-server" "-Xmx2G"]
  :dependencies [[org.clojure/clojure "1.5.1"],
                 [yetibot.core "0.1.7"]

                 ; apis
                 [tentacles "0.2.5"]
                 [twitter-api "0.7.5"]

                 ; s3
                 [clj-aws-s3 "0.3.2"]

                 ; utils
                 [useful "0.8.3-alpha8"]
                 [org.clojure/tools.cli "0.3.1"]

                 ; NLP
                 [clojure-opennlp "0.3.2"]

                 ; [incanter "1.4.0"]
                 ]
  :plugins [[lein-ring "0.8.2"]]
  :pedantic :warn
  :ring {:handler yetibot.webapp.server/app
         :init yetibot.core.init/-main}
  :main yetibot.core.init)
