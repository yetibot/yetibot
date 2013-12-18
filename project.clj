(defproject yetibot "0.1.0"
  :description "A command line in your chat, where chat ∈ {irc,campfire}."
  :profiles {:dev {:source-paths ["dev"]}
             :test {}
             :plugins [[lein-git-deps "0.0.1-SNAPSHOT"]]}
  :repl-options {:init-ns yetibot.core.repl
                 :welcome (println "Welcome to the YetiBot development repl!")}
  :jvm-opts ["-server" "-Xmx2G"]
  :dependencies [[org.clojure/clojure "1.5.1"],
                 [yetibot.core "0.1.0"]

                 ; apis
                 [tentacles "0.2.5"]
                 [twitter-api "0.7.4"]

                 ; s3
                 [clj-aws-s3 "0.3.2"]

                 ; utils
                 [useful "0.8.3-alpha8"]

                 ; [incanter "1.4.0"]
                 ]
  :plugins [[lein-ring "0.8.2"]]
  :pedantic :warn
  :ring {:handler yetibot.webapp.server/app
         :init yetibot.core.init/-main}
  :main yetibot.core.init)
