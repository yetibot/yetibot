(ns yetibot.test.models.twitter
  (:require
    [clojure.test :refer :all]
    [yetibot.models.twitter :refer :all]))

(deftest test-expanding-urls
  (is
    (= (expand-url "http://t.co/DGTAQQ0MQo")
       "http://25.media.tumblr.com/7908066b66b51c1b0c30135fd9824e8b/tumblr_mv6r5i7JZR1qa0eq0o1_400.gif")))
