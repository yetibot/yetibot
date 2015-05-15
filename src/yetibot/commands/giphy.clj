(ns yetibot.commands.giphy
  (:require
    [taoensso.timbre :refer [info warn error]]
    [clojure.string :refer [split join trim]]
    [yetibot.core.hooks :refer [cmd-hook]]
    [yetibot.api.giphy :as api]))

(defn report-if-error [response success-fn]
  (let [{:keys [meta data]} response]
    (if (= (:status meta) 200)
      (if (empty? data)
        (do
          (info "empty response" response)
          "No results")
        (success-fn response))
      ;; error
      (:msg meta))))

(defn translate-cmd
  "giphy <query> # find a gif for <query> on giphy.com"
  [{[_ query] :match}]
  (info "translate" query)
  (report-if-error
    (api/translate query)
    (fn [res]
      (info "translate success" res)
      (-> res :data :images :original :url))))

(defn random-cmd
  "giphy random # find a random gif"
  [_]
  (report-if-error
    (api/random)
    (fn [res]
      (-> res :data :image_original_url))))

(defn trending-cmd
  "giphy trending # returns a random trending gif from the top 25"
  [_]
  (report-if-error
    (api/trending)
    (fn [res]
      (-> res :data rand-nth :images :original :url))))

(cmd-hook #"giphy"
  #"random" random-cmd
  #"trending" trending-cmd
  #"(.+)" translate-cmd)
