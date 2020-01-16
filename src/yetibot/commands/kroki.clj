(ns yetibot.commands.kroki
  (:require
    [taoensso.timbre :refer [debug info warn error]]
    [clojure.string :as string]
    [clj-http.client :as client]
    [clj-http.util :refer [deflate utf8-bytes base64-encode url-encode]]
    [yetibot.core.hooks :refer [cmd-hook]]))

(defn encode-for-kroki
  "kroki wants the graph encoded a specific way when part of a URL.

   See docs at https://docs.kroki.io/kroki/setup/encode-diagram/"
  [graph-string]
  (-> graph-string
      utf8-bytes
      deflate
      base64-encode
      (string/replace "+" "-")
      (string/replace "/" "_")))


(comment

  (str "https://demo.kroki.io/graphviz/png/"
       (encode-for-kroki graphviz)
       ;; toss a format hint on the end for the chat client 😂
       "?.png")


  (encode)

  (defn encode-cmd
    "base64 encode <string>"
    [{[_ s] :match}]
    (try
      (String. (b64/encode (.getBytes s)))
      (catch Exception _ "Oops! Can't encode that.")))

  (defn decode-cmd
    "base64 decode <string>"
    [{[_ s] :match}]
    (try
      (String. (b64/decode (.getBytes s)))
      (catch Exception _ "Oops! Cant' decode that.")))

  )

(defn graphviz-cmd
  "kroki graphviz <dot> # generate a graphviz graph"
  [{:keys [raw] :as cmd}]
  (info "kroki" cmd)
  raw
  )

(cmd-hook #"kroki"
          #"graphviz\s+(.+)" graphviz-cmd)

(comment

  (def graphviz
    "
      digraph D {
        subgraph cluster_p {
          label = \"Kroki\";
          subgraph cluster_c1 {
            label = \"Server\";
            Filebeat;
            subgraph cluster_gc_1 {
              label = \"Docker/Server\";
              Java;
            }
            subgraph cluster_gc_2 {
              label = \"Docker/Mermaid\";
              \"Node.js\";
              \"Puppeteer\";
              \"Chrome\";
            }
          }
          subgraph cluster_c2 {
            label = \"CLI\";
            Golang;
          }
        }
      }
    ")

  )
