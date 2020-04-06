(ns yetibot.commands.scrape
  (:require
   [yetibot.models.jsoup :refer [get-page get-elems get-attr]]
   [taoensso.timbre :refer [debug info warn error]]
   [yetibot.core.hooks :refer [cmd-hook]]))

(defn scrape [url selector attr]
  (remove nil?
          (for [e (-> url
                      get-page
                      (get-elems selector))]
            (get-attr e attr))))

(defn scrape-cmd
  "scrape <url> <selector-path> <attr> # scrape a url and select elements' text, html or attributes via jsoup. <attr> can be:
   - text - inner text of the element(s)
   - html - html of the element(s)
   - anything else - an attribute of the element(s)"
  {:yb/cat #{:util}}
  [{[_ url selector-and-attr] :match}]
  (let [[_ selector attr] (re-find
                            #"(.*)\s(\w+)$"
                            selector-and-attr)]
    (scrape url selector attr)))

(cmd-hook #"scrape"
  #"(\S+)(.*)" scrape-cmd)
