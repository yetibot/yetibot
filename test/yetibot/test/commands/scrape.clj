(ns yetibot.test.commands.scrape
  (:require
    [clojure.test :refer :all]
    [yetibot.commands.scrape :refer :all]))

(deftest test-scrape
  (testing "Imgflip"
    (is (not-empty (scrape "https://imgflip.com" ".base-img[src!='']" "src")))))
