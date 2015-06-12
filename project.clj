(defproject yetibot "0.1.51-SNAPSHOT"
  :description "A command line in your chat, where chat ∈ {irc,campfire}."
  :url "https://github.com/devth/yetibot"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :lein-release {:deploy-via :clojars}
  :signing {:gpg-key "C9764E34"}
  :deploy-repositories [["releases" :clojars]]
  :profiles {:dev {:source-paths ["dev"]}
             :test {}
             :plugins [[lein-git-deps "0.0.1-SNAPSHOT"]]}
  :repl-options {:init-ns yetibot.repl
                 :welcome (println "Welcome to the yetibot development REPL!")}
  :jvm-opts ["-server" "-Xmx2G"]
  :dependencies [[org.clojure/clojure "1.6.0"],
                 [yetibot.core "0.2.33"]

                 ; apis
                 [tentacles "0.3.0"]
                 [twitter-api "0.7.6"]

                 ; s3
                 [clj-aws-s3 "0.3.2"]

                 ; utils
                 [useful "0.8.3-alpha8"]
                 [org.clojure/tools.cli "0.3.1"]

                 ; NLP
                 [clojure-opennlp "0.3.2"]

                 ; [incanter "1.4.0"]

                 ; web
                 [selmer "0.8.2"]
                 [compojure "1.3.4"]
                 [prone "0.8.2"]
                 [hiccup "1.0.5"]
                 ; [lib-noir "0.9.9" :exclusions [[org.clojure/tools.namespace]]]
                 [org.clojure/tools.nrepl "0.2.10"]
                 [markdown-clj "0.9.66"]

                 ; web/ring
                 [ring/ring-json "0.3.1"]
                 [ring/ring-core "1.4.0-RC1"]
                 [ring/ring-jetty-adapter "1.4.0-RC1"]
                 ; [info.sunng/ring-jetty9-adapter "0.8.4"]
                 [ring/ring-defaults "0.1.5"]
                 [ring/ring-session-timeout "0.1.0"]
                 [metosin/ring-middleware-format "0.6.0"]
                 [metosin/ring-http-response "0.6.2"]

                 ]
  :plugins [[lein-ring "0.9.5"]]
  :pedantic :warn
  :ring {:handler yetibot.webapp.handler/app
         :init    yetibot.webapp.handler/init
         :destroy yetibot.webapp.handler/destroy
         :uberwar-name "yetibot.war"}
  :main yetibot.core.init)
