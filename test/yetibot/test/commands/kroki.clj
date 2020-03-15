(ns yetibot.test.commands.kroki
  (:require
   [clojure.data.json :as json]
   [midje.sweet :refer [fact =>]]
   [yetibot.commands.kroki :refer [encode-for-kroki]]))

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

(fact
 "graphviz is properly encoded for kroki"
 (encode-for-kroki graphviz)
 => "eJyFkMsKg0AMRfd-RZgPaKlb6Upp6ZNCP0DGMejUsTPEx6b4730IMjpSs0jghntuiAefSmVG3OQQwcuDX1VN0ktCNVWNFJthBaB4ggq2wE6kC8mCYeG4xMayWcY7UotkOQF2UmGCvLY1h5eJeEy0mJEWBdJ6Bg1w5C23lW4hxF8IuSCVXKaTFHbVKa4e1VS-NcZgjc5VLMxJl8jmL-v-vNWff2t4Poxge634MwscZD-__Q1cp3xv")

(comment
"
  graph { a -- b; b -- c; a -- c; d -- c; e -- c; e -- a; }
"

  (str "https://demo.kroki.io/graphviz/png/"
       (encode-for-kroki graphviz)
       ;; toss a format hint on the end for the chat client 😂
       "?.png")

  )
