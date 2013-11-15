(defproject yetibot "0.1.0-SNAPSHOT"
  :description "A command line in your chat, where chat ∈ {irc,campfire}."
  :profiles {:dev {:source-paths ["dev"]
                   :dependencies [[midje "1.5-beta1"]]}
             :test {}
             :plugins [[lein-midje "3.0-beta1"]]}
  :resource-paths ["config"]
  :repl-options {:init-ns user
                 :welcome (println "Welcome to the YetiBot development repl!")}
  :dependencies [[org.clojure/clojure "1.5.0"],

                 ; TODO - kill this some day. We're only relying on it for
                 ; cond-let at this point.
                 [org.clojure/clojure-contrib "1.2.0"]
                 [http.async.client "0.5.2"]
                 ; [org.clojars.adamwynne/http.async.client "0.4.1" ]
                 [org.apache.commons/commons-lang3 "3.1"]
                 [robert/hooke "1.3.0"]
                 [clj-time "0.4.4"]

                 ; chat protocols
                 [clj-campfire "2.2.0"]
                 [irclj "0.5.0-alpha2"]

                 ; parsing
                 [instaparse "1.2.2"]
                 ; parser visualization - disable unless needed
                 ; [rhizome "0.1.9"]

                 ; logging
                 [com.taoensso/timbre "2.6.2"]

                 [org.clojure/data.json "0.1.2"]
                 [org.clojure/tools.namespace "0.2.2"]
                 [org.clojure/tools.trace "0.7.6"]
                 [org.clojure/java.classpath "0.2.0"]
                 [org.clojure/core.cache "0.6.2"]
                 [org.clojure/core.match "0.2.0-rc5"]
                 [org.clojure/data.xml "0.0.6"]
                 [org.clojure/data.zip "0.1.1"]
                 [org.clojure/java.jdbc "0.2.3"]

                 [evaljs "0.1.2"]
                 [clj-ssh "0.4.0"]

                 ; utils
                 [useful "0.8.3-alpha8"]
                 [rate-gate "1.3.1"]

                 [clj-wordnik "0.1.0-alpha1"]
                 [tentacles "0.2.5"]
                 [clj-http "0.5.5"]
                 [clj-aws-s3 "0.3.2"]
                 [overtone/at-at "1.0.0"]
                 [com.draines/postal "1.9.0"]
                 [twitter-api "0.7.4"]
                 [inflections "0.7.3"]
                 [environ "0.3.0"]
                 [com.bigml/closchema "0.1.8"]
                 [mysql/mysql-connector-java "5.1.6"]
                 [cheshire "5.0.1"]
                 ; [incanter "1.4.0"]

                 [compojure "1.1.5"]
                 [hiccup "1.0.2"]
                 [lib-noir "0.3.4" :exclusions [[org.clojure/tools.namespace]]]

                 ; Use this fork until canonical clojure-mail has merged:
                 ; https://github.com/owainlewis/clojure-mail/pull/1
                 [org.clojars.petterik/clojure-mail "0.1.8"]
                 ;;; [clojure-mail "0.1.0-SNAPSHOT"]

                 ; database
                 [com.datomic/datomic-free "0.8.3814"]
                 [datomico "0.2.0"]
                 ]
  :plugins [[lein-ring "0.8.2"]]
  :pedantic :warn
  :ring {:handler yetibot.webapp.server/app
         :init yetibot.core/-main}
  :main yetibot.core)
