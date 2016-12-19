(defproject yetibot "0.4.0-alpha6-SNAPSHOT"
  :description "A command line in your chat, where chat ∈ {irc,slack}."
  :url "https://github.com/devth/yetibot"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :lein-release {:deploy-via :clojars}
  :deploy-repositories [["releases" :clojars]]
  :profiles {:dev {:source-paths ["dev"]}
             :uberjar {:uberjar-name "yetibot.jar"
                       :aot :all}
             :test {}
             :plugins [[lein-git-deps "0.0.1-SNAPSHOT"]]}
  :repl-options {:init-ns yetibot.core.repl
                 :welcome (println "Welcome to the yetibot development REPL!")}
  :jvm-opts ["-server"]
  :dependencies [[org.clojure/clojure "1.8.0"]
                 [yetibot.core "0.4.0-alpha8"]

                 ; apis
                 [twitter-api "0.7.6"]
                 [clj-aws-s3 "0.3.10" :exclusions [joda-time]]

                 ; utils
                 [org.flatland/useful "0.11.5"]
                 [org.clojure/tools.cli "0.3.1"]

                 ;for polling
                 [robert/bruce "0.8.0"]
                 ]
  :plugins [[lein-exec "0.3.5"]
            [lein-environ "1.0.3"]
            [lein-cloverage "1.0.7-SNAPSHOT"]
            [lein-ring "0.9.5"]
            [io.sarnowski/lein-docker "1.1.0"]]

  :aliases
  {"version" ["exec" "-ep" "(use 'yetibot.core.version)(print version)"]}
  ;; :pedantic :ignore

  :docker {:image-name "devth/yetibot"}

  ;; :ring {:handler yetibot.webapp.handler/app
  ;;        :init    yetibot.webapp.handler/init
  ;;        :destroy yetibot.webapp.handler/destroy
  ;;        :uberwar-name "yetibot.war"}

  :main yetibot.core.init)
