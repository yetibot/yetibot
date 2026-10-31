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
                       (str "/yetibot/core/20261031.201727.2404ac0/"
                            "core-20261031.201727.2404ac0.jar")) => true
    (.getEntryName connection) => "yetibot/core/adapters/discord.clj"
    (string/join (map #(format "%02x" (bit-and 255 %)) (.digest digest)))
    => "32555342a1ec07e234c6b7b37d7f0df59bc900b97c5a6c1f84f158c16d0406d7"))

(fact "root AI delivers typed-only V2 status through genuine core and SDK"
  (proof/typed-status! :ai))

(fact "Grok separates typed-only V2 status from immediate and final answers"
  (proof/typed-status! :grok))
