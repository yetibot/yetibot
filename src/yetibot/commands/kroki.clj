(ns yetibot.commands.kroki
  (:require
    [taoensso.timbre :refer [debug info warn error]]
    [clojure.string :as string]
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

(defn graphviz-url
  [graphviz]
  (str "https://demo.kroki.io/graphviz/png/"
       (encode-for-kroki graphviz)))

(defn graphviz-cmd
  "kroki graphviz <dot> # generate a graphviz graph"
  [{[_ graphviz] :match :as cmd}]
  (info "kroki" cmd)
  (graphviz-url graphviz))

;; TODO
;; - consider supporting all formats
;; - multi line commands

(cmd-hook #"kroki"
          #"graphviz\s+(.+)" graphviz-cmd)

