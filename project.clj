(defproject yetibot "0.4.8-SNAPSHOT"
  :description "A command line in your chat, where chat ∈ {irc,slack}."
  :url "https://github.com/devth/yetibot"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
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
                 [yetibot.core "0.4.7"]

                 ; apis
                 [twitter-api "0.7.6"]
                 [clj-aws-s3 "0.3.10" :exclusions [joda-time]]

                 ; scraping
                 [org.jsoup/jsoup "1.10.1"]

                 ; utils
                 [org.flatland/useful "0.11.5"]
                 [json-path "0.3.0"]

                 ; polling
                 [robert/bruce "0.8.0"]
                 
                 ; emojis
                 [com.vdurmont/emoji-java "3.2.0"]]
  :plugins [[lein-exec "0.3.5"]
            [lein-environ "1.0.3"]
            [lein-cloverage "1.0.7-SNAPSHOT"]
            [lein-ring "0.9.5"]
            [io.sarnowski/lein-docker "1.1.0"]]

  :aliases
  {"version" ["exec" "-ep" "(use 'yetibot.core.version)(print version)"]}
  ;; :pedantic :ignore

  :docker {:image-name "devth/yetibot"}

  :main yetibot.core.init)
