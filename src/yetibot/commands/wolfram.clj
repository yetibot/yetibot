(ns yetibot.commands.wolfram
  (:require
    [clojure.spec.alpha :as s]
    [yetibot.core.util.http :refer [encode]]
    [clojure.xml :as xml]
    [taoensso.timbre :refer [info warn error]]
    [yetibot.core.config :refer [get-config]]
    [yetibot.core.hooks :refer [cmd-hook]]))

(s/def ::appid string?)

(s/def ::config (s/keys :req-un [::appid]))

(def config (:value (get-config ::config [:wolfram])))
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

(cmd-hook #"wolfram"
  #".*" search-wolfram)
