(ns yetibot.test.commands.scrape
  (:require
    [midje.sweet :refer [fact =>]]
    [yetibot.commands.scrape :refer :all]))

(fact test-scrape
    (scrape "https://imgflip.com" ".base-img[src!='']" "src") => not-empty)
