(ns yetibot.repl
  (:require
    [yetibot.core.repl :refer :all]
    [yetibot.webapp.handler :refer [app init destroy]]
    [ring.adapter.jetty :refer [run-jetty]]
    [ring.middleware.reload :as reload]
    [environ.core :refer [env]])
  (:gen-class))

(defonce server (atom nil))

(defn parse-port [port]
  (Integer/parseInt (or port (env :port) "3000")))

(defn start-server [port]
  (init)
  (reset! server
          (run-jetty
            (if (env :dev) (reload/wrap-reload #'app) app)
            {:port port
             :join? false})))

(defn stop-server []
  (when @server
    (destroy)
    (.stop @server)
    (reset! server nil)))

(defn -main [& [port]]
  (let [port (parse-port port)]
    (.addShutdownHook (Runtime/getRuntime) (Thread. stop-server))
    (start-server port)))

