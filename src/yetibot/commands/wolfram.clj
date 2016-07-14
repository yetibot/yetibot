(ns yetibot.commands.wolfram
  (:require
    [schema.core :as sch]
    [yetibot.core.util.http :refer [encode]]
    [clojure.string :as s]
    [clojure.xml :as xml]
    [taoensso.timbre :refer [info warn error]]
    [yetibot.core.config :refer [get-config]]
    [yetibot.core.hooks :refer [cmd-hook]]))

(def config (:value (get-config {:appid sch/Str} [:yetibot :wolfram])))
(def app-id (:appid config))
(def endpoint (str "http://api.wolframalpha.com/v2/query?appid=" app-id))

(defn parse-imgs-from-xml [xml]
  (let [xs (xml-seq xml)]
    (for [el xs :when (= :img (:tag el))] [(:alt (:attrs el)) (:src (:attrs el))])))

(defn search-wolfram
  "wolfram <query> # search for <query> on Wolfram Alpha"
  {:yb/cat #{:info :img}}
  [{q :match}]
  (flatten
    (map #(str (second %) "&t=.jpg")
         (parse-imgs-from-xml
           (xml/parse (str endpoint "&input=" (encode q)))))))

(if (conf-valid?)
  (cmd-hook #"wolfram"
            #".*" search-wolfram)
  (info "Wolfram is not configured"))
