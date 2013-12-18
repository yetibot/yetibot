(ns yetibot.commands.wolfram
  (:require
    [clojure.string :as s]
    [clojure.xml :as xml]
    [taoensso.timbre :refer [info warn error]]
    [yetibot.core.config :refer [config-for-ns conf-valid?]]
    [yetibot.core.hooks :refer [cmd-hook]]))

(def config (config-for-ns))
(def app-id (:app-id config))
(def endpoint (str "http://api.wolframalpha.com/v2/query?appid=" app-id))

(defn parse-imgs-from-xml [xml]
  (let [xs (xml-seq xml)]
    (for [el xs :when (= :img (:tag el))] [(:alt (:attrs el)) (:src (:attrs el))])))

(defn search-wolfram
  "wolfram <query> # search for <query> on Wolfram Alpha"
  [{q :match}]
  (flatten
    (map #(str (second %) "&t=.jpg")
         (parse-imgs-from-xml
           (xml/parse (str endpoint "&input=" q))))))

(if (conf-valid?)
  (cmd-hook #"wolfram"
            #".*" search-wolfram)
  (info "Wolfram is not configured"))
