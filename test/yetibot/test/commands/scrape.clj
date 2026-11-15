(ns yetibot.test.commands.scrape
  (:require [midje.sweet :refer [fact =>]]
            [yetibot.commands.scrape :refer :all])
  (:import [com.sun.net.httpserver HttpServer HttpHandler]
           [java.net InetSocketAddress]))

(fact test-scrape
  (let [server (HttpServer/create (InetSocketAddress. "127.0.0.1" 0) 0)
        html "<html><div class='bodycopy'><p>Local doge fixture</p></div><p>Excluded text</p></html>"
        bytes (.getBytes html "UTF-8")]
    (try
      (.createContext server "/doge"
        (reify HttpHandler
          (handle [_ exchange]
            (try
              (.sendResponseHeaders exchange 200 (alength bytes))
              (with-open [output (.getResponseBody exchange)] (.write output bytes))
              (finally (.close exchange))))))
      (.start server)
      (let [result (vec (scrape (str "http://127.0.0.1:" (.getPort (.getAddress server)) "/doge")
                                ".bodycopy p" "text"))]
        result => not-empty
        result => ["Local doge fixture"])
      (finally (.stop server 0)))))
