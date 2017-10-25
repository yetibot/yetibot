(ns yetibot.test.commands.base64
  (:require
    [midje.sweet :refer [fact => truthy]]
    [yetibot.commands.base64 :refer :all]))
(fact test-encode-no-padding
      (encode "any carnal pleasur") => "YW55IGNhcm5hbCBwbGVhc3Vy")

(fact test-encode-padding-1
      (encode "any carnal pleasu") => "YW55IGNhcm5hbCBwbGVhc3U=")

(fact test-encode-padding-2
      (encode "any carnal pleas") => "YW55IGNhcm5hbCBwbGVhcw==")

(fact test-encode-long-text-with-newlines
      (encode "Man is distinguished, not only by his reason, but by this
      singular passion from\nother animals, which is a lust of the mind.")
      => "TWFuIGlzIGRpc3Rpbmd1aXNoZWQsIG5vdCBvbmx5IGJ5IGhpcyByZWFzb24sIGJ1dCBieSB0aGlzIAogICAgICBzaW5ndWxhciBwYXNzaW9uIGZyb20Kb3RoZXIgYW5pbWFscywgd2hpY2ggaXMgYSBsdXN0IG9mIHRoZSBtaW5kLg==")

(fact test-decode-no-padding
      (decode "YW55IGNhcm5hbCBwbGVhc3Vy") => "any carnal pleasur")

;padding ignored on decode
(fact test-decode-padding-1
      (decode "YW55IGNhcm5hbCBwbGVhc3U") => "any carnal pleasu")

(fact test-decode-padding-2
      (decode "YW55IGNhcm5hbCBwbGVhcw") => "any carnal pleas")

(fact test-decode-long-text-with-newlines
      (decode "TWFuIGlzIGRpc3Rpbmd1aXNoZWQsIG5vdCBvbmx5IGJ5IGhpcyByZWFzb24sIGJ1dCBieSB0aGlzIAogICAgICBzaW5ndWxhciBwYXNzaW9uIGZyb20Kb3RoZXIgYW5pbWFscywgd2hpY2ggaXMgYSBsdXN0IG9mIHRoZSBtaW5kLg==")
      => "Man is distinguished, not only by his reason, but by this \n      singular passion from\nother animals, which is a lust of the mind.")