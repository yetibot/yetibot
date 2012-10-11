(ns yetibot.commands.opengrok
  (:require [clojure.string :as s]
            [clojure.data.xml :as xml]
            [clojure.zip :as zip])
  (:use [yetibot.util :only (cmd-hook env)]
        [yetibot.util.http :only (fetch)]
        [clojure.data.zip.xml :only (attr attr= text xml->)]))

(def config
  {:endpoint (:OPEN_GROK_URL env)
   :projects-params (if-let [projects (:OPEN_GROK_PROJECTS env)]
                      (s/join "&project=" (s/split projects #",")))})

(defn extract [html]
  (partition 3 (xml-> html :body
                     :div (attr= :id "page")
                     :div (attr= :id "results")
                     :table :tr :td text)))

(defn grok-cmd
  "grok <query> # search configured OpenGrok instance for <query>"
  [query]
  (let [uri (format "%s/search?q=%s&%s" (:endpoint config) query (:projects-params config))
        html (zip/xml-zip (xml/parse (java.io.StringReader. (fetch uri))))
        res (extract html)]
    res
    ))

(when (every? identity (vals config))
  (cmd-hook #"grok"
            _ (grok-cmd p)))
