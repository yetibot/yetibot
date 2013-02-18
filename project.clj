(defproject yetibot "1.0.0-SNAPSHOT"
            :description "A command line in your campfire."
            :profiles {:dev {:dependencies [[midje "1.5-beta1"]]}
                             :plugins [[lein-midje "3.0-beta1"]]}
            :dependencies [[org.clojure/clojure "1.4.0"],
                           ; TODO - kill this some day. We're only relying on it for
                           ; cond-let at this point.
                           [org.clojure/clojure-contrib "1.2.0"]
                           [org.clojars.adamwynne/http.async.client "0.4.1"]
                           [org.apache.commons/commons-lang3 "3.1"]
                           [robert/hooke "1.3.0"]
                           [clj-campfire "1.0.0"]
                           [clj-time "0.4.4"]

                           [org.clojure/data.json "0.1.2"]
                           [org.clojure/tools.namespace "0.2.2"]
                           [org.clojure/java.classpath "0.2.0"]
                           [org.clojure/core.cache "0.6.2"]
                           [org.clojure/tools.logging "0.2.3"]

                           [clj-logging-config "1.9.7"]
                           [log4j/log4j "1.2.16" :exclusions [javax.mail/mail
                                                               javax.jms/jms
                                                               com.sun.jdmk/jmxtools
                                                               com.sun.jmx/jmxri]]
                           [evaljs "0.1.2"]
                           [clj-ssh "0.4.0"]
                           [useful "0.8.3-alpha8"]
                           [clj-wordnik "0.1.0-alpha1"]
                           [tentacles "0.2.2"]
                           [clj-http "0.5.5"]
                           [org.clojure/data.xml "0.0.6"]
                           [org.clojure/data.zip "0.1.1"]
                           [clj-aws-s3 "0.3.2"]
                           [overtone/at-at "1.0.0"]
                           [com.draines/postal "1.9.0"]
                           [twitter-api "0.6.12"]
                           [inflections "0.7.3"]
                           [environ "0.3.0"]
                           [com.bigml/closchema "0.1.8"]
                           [org.clojure/java.jdbc "0.2.3"]
                           [mysql/mysql-connector-java "5.1.6"]
                           [cheshire "5.0.1"]
                           [incanter "1.4.0"]

                           [compojure "1.1.5"]
                           [hiccup "1.0.2"]
                           [lib-noir "0.3.4" :exclusions [[org.clojure/tools.namespace]]]

                           ; Use this fork until canonical clojure-mail has merged:
                           ; https://github.com/owainlewis/clojure-mail/pull/1
                           [org.clojars.petterik/clojure-mail "0.1.8"]
                           ;;; [clojure-mail "0.1.0-SNAPSHOT"]

                           ]
            :plugins [[lein-ring "0.8.2"]]
            :pedantic :warn
            :ring {:handler yetibot.webapp.server/app
                   :init yetibot.core/-main}
            :main yetibot.core)
