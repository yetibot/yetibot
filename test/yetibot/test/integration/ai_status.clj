(ns yetibot.test.integration.ai-status
  (:require [clojure.java.io :as io]
            [clojure.string :as string]
            [midje.sweet :refer [fact =>]]
            [yetibot.test.support.ai-status :as proof]))

(fact "runtime status API is loaded from the genuine published core JAR"
  (let [resource (io/resource "yetibot/core/adapters/discord.clj")
        ^java.net.JarURLConnection connection (.openConnection resource)
        jar-url (.getJarFileURL connection)
        digest (java.security.MessageDigest/getInstance "SHA-256")]
    (with-open [input (io/input-stream resource)]
      (let [buffer (byte-array 8192)]
        (loop []
          (let [n (.read input buffer)]
            (when (pos? n)
              (.update digest buffer 0 n)
              (recur))))))
    (.getProtocol resource) => "jar"
    (.getProtocol jar-url) => "file"
    (string/ends-with? (.getPath jar-url)
                       (str "/yetibot/core/20261031.161922.207e98a/"
                            "core-20261031.161922.207e98a.jar")) => true
    (.getEntryName connection) => "yetibot/core/adapters/discord.clj"
    (string/join (map #(format "%02x" (bit-and 255 %)) (.digest digest)))
    => "144edcabf80067908ef301151a5e9d05e11e33c98cb372e1645ce10bd168c5c4"))

(fact "root AI delivers typed-only V2 status through genuine core and SDK"
  (proof/typed-status! :ai))

(fact "Grok separates typed-only V2 status from immediate and final answers"
  (proof/typed-status! :grok))
