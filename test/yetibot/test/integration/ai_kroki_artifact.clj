(ns yetibot.test.integration.ai-kroki-artifact
  (:require [clojure.java.io :as io]
            [clojure.string :as string]
            [midje.sweet :refer [fact =>]]
            [yetibot.models.ai-kroki-tools :as kroki])
  (:import [java.security DigestInputStream MessageDigest]))

(fact "the adopted Kroki dependency is the genuine published shared-client JAR"
  (let [resource (io/resource "yetibot_kroki/client.clj")
        ^java.net.JarURLConnection connection (.openConnection resource)
        jar-url (.getJarFileURL connection)
        digest (MessageDigest/getInstance "SHA-256")]
    (with-open [input (DigestInputStream. (io/input-stream jar-url) digest)]
      (io/copy input (java.io.OutputStream/nullOutputStream)))
    (.getProtocol resource) => "jar"
    (.getProtocol jar-url) => "file"
    (string/ends-with? (.getPath jar-url)
                      (str "/yetibot/kroki/20261111.164625.6559feb/"
                           "kroki-20261111.164625.6559feb.jar")) => true
    (string/join (map #(format "%02x" (bit-and 255 %)) (.digest digest)))
    => "5680fa44e3b76a8f896c1e818b526448024a2ab5611d2ab4688bf5d7275ae9fb"
    (kroki/available-tools) => ["kroki_types" "kroki_render"]))
