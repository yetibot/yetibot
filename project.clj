(defproject yetibot "1.0.0-SNAPSHOT"
            :description "this is both a yeti and a bot."
            :dependencies [[org.clojure/clojure "1.3.0"],
                           [org.clojars.adamwynne/http.async.client "0.4.1"]
                           [org.apache.commons/commons-lang3 "3.1"]	
                           [robert/hooke "1.1.2"]
                           [clj-campfire "1.0.0"]
                           [org.clojure/data.json "0.1.1"]
                           [org.clojure/tools.namespace "0.1.0"]
                           [clj-time "0.3.2"]
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
                           [tentacles "0.2.0-beta1"]
                           ]
            :dev-dependencies [[clj-stacktrace "0.2.3"]]
            :main yetibot.core)
