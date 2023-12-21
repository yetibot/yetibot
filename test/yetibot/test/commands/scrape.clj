(ns yetibot.test.commands.scrape
  (:require
    [midje.sweet :refer [fact =>]]
    [yetibot.commands.scrape :refer :all]))

(fact
 test-scrape
 (scrape
  "https://knowyourmeme.com/memes/doge" ".bodycopy p" "text") => not-empty)
