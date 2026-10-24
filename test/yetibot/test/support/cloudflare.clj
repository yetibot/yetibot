(ns yetibot.test.support.cloudflare
  (:import [java.io ByteArrayOutputStream]
           [java.awt.image BufferedImage]
           [javax.imageio ImageIO]))

(defn png []
  (let [output (ByteArrayOutputStream.)]
    (ImageIO/write (BufferedImage. 2 3 BufferedImage/TYPE_INT_RGB) "png" output)
    (.toByteArray output)))
