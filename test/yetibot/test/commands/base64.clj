(ns yetibot.test.commands.base64
  (:require
    [midje.sweet :refer [fact => truthy]]
    [yetibot.commands.base64 :refer :all]
    [clojure.data.codec.base64 :as b64]))


(fact test-encode-no-padding
      (String. (b64/encode (into-bytes "any carnal pleasur"))) => "YW55IGNhcm5hbCBwbGVhc3Vy")

(fact test-encode-padding-1
      (String. (b64/encode (into-bytes "any carnal pleasu"))) => "YW55IGNhcm5hbCBwbGVhc3U=")

(fact test-encode-padding-2
      (String. (b64/encode (into-bytes "any carnal pleas"))) => "YW55IGNhcm5hbCBwbGVhcw==")

(fact test-decode-no-padding
      (String. (b64/decode (into-bytes "YW55IGNhcm5hbCBwbGVhc3Vy"))) => "any carnal pleasur")

(fact test-decode-padding-1
      (String. (b64/decode (into-bytes "YW55IGNhcm5hbCBwbGVhc3U="))) => "any carnal pleasu")

(fact test-decode-padding-2
      (String. (b64/decode (into-bytes "YW55IGNhcm5hbCBwbGVhcw=="))) => "any carnal pleas")