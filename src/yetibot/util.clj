(ns yetibot.util
  (:require [http.async.client :as client]
            [clojure.contrib.string :as s]
            [yetibot.core :as core]
            [yetibot.help :as help]
            [robert.hooke :as rh]
            [clojure.data.json :as json])
  (:use [clojure.contrib.cond])
  (:import (java.net URL URLEncoder)))

; synchronous api call helpers
(defmacro with-client [uri verb-fn auth & body]
  `(with-open [~'client (client/create-client)]
     (let [~'response (~verb-fn ~'client ~uri :auth ~auth)]
       ~@body)))

(defn get-json 
  ([uri auth] (get-json uri client/GET auth))
  ([uri verb-fn auth ]
   (with-client uri verb-fn auth
                (client/await response)
                (json/read-json (client/string response)))))



; command hook
(defmacro cmd-hook [prefix & exprs]
  `(do
     (rh/add-hook
       #'core/handle-command
       (fn [~'callback ~'cmd ~'args]
         (if (re-find ~prefix (s/lower-case ~'cmd))
           (do
             (println (str "found " ~prefix ". args are:" ~'args))
             ; try matching the available sub-commands
             (cond-let [~'p]
                       ~@(map (fn [i#]
                                (if (instance? java.util.regex.Pattern i#)
                                  `(re-find ~i# ~'args)
                                  `~i#))
                              exprs)))
           (~'callback ~'cmd ~'args))))
     ; extract the meta from the commands and use it to build docs
     (help/add-docs ~prefix
                    (map
                      (fn [i#]
                        (if (list? i#)
                          (:doc (meta (resolve (first i#))))))
                      '~exprs))))



; query string helper
(defn map-to-query-string [m]
  (let [encode #(URLEncoder/encode (str %) "UTF-8")]
    (s/join "&" (map (fn [[k v]] (format "%s=%s" 
                                         (encode (name k)) (encode v))) m))))

