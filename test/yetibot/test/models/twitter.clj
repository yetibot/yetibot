(ns yetibot.test.models.twitter
  (:require
    [midje.sweet :refer [fact =>]]
    [yetibot.models.twitter :refer :all]))

;; disabled until clj-http 3.0 issue is fixed
;; https://github.com/dakrone/clj-http/pull/327
#_(fact test-expanding-urls
  (expand-url "http://t.co/DGTAQQ0MQo") =>
    "http://25.media.tumblr.com/7908066b66b51c1b0c30135fd9824e8b/tumblr_mv6r5i7JZR1qa0eq0o1_400.gif")
