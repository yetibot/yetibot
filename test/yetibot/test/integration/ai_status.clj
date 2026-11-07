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
                       (str "/yetibot/core/20261107.050013.f0ab0e4/"
                            "core-20261107.050013.f0ab0e4.jar")) => true
    (.getEntryName connection) => "yetibot/core/adapters/discord.clj"
    (string/join (map #(format "%02x" (bit-and 255 %)) (.digest digest)))
    => "99dffde0ddfaec18e881874afc7dbad4482267a9306bf9c5c145db93a6c4e749"))

(fact "root AI delivers typed-only V2 status through genuine core and SDK"
  (proof/typed-status! :ai))

(fact "Grok separates typed-only V2 status from immediate and final answers"
  (proof/typed-status! :grok))
