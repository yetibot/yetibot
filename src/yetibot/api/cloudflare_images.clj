(ns yetibot.api.cloudflare-images
  "Fetch public images without credentials, redirects or DNS rebinding."
  (:require [clj-http.client :as http]
            [yetibot.api.cloudflare :as cf])
  (:import [java.io ByteArrayInputStream]
           [java.net InetAddress URI]
           [java.util Base64]
           [javax.imageio ImageIO]
           [javax.imageio.stream MemoryCacheImageInputStream]
           [org.apache.http.conn DnsResolver]
           [org.apache.http.config RegistryBuilder]
           [org.apache.http.conn.ssl SSLConnectionSocketFactory]
           [org.apache.http.impl.conn BasicHttpClientConnectionManager
            DefaultRoutePlanner DefaultSchemePortResolver]))

(def image-limit (* 4 1024 1024))

(defn public-address? [^InetAddress address]
  (let [b (mapv #(bit-and 255 %) (.getAddress address))
        [a second-byte] b]
    (and (not (or (.isAnyLocalAddress address) (.isLoopbackAddress address)
                  (.isLinkLocalAddress address) (.isSiteLocalAddress address)
                  (.isMulticastAddress address)))
         (if (= 4 (count b))
           (not (or (#{0 10 127} a) (>= a 224)
                    (and (= a 100) (<= 64 second-byte 127))
                    (and (= a 192) (= second-byte 0))
                    (and (= a 198) (<= 18 second-byte 19))
                    (= [198 51 100] (subvec b 0 3))
                    (= [203 0 113] (subvec b 0 3))))
           (and (<= 32 a 63)
                (not (= [32 2] (subvec b 0 2)))
                (not (and (= [32 1] (subvec b 0 2)) (< (nth b 2) 2)))
                (not (= [32 1 13 184] (subvec b 0 4)))
                (not (and (= [63 255] (subvec b 0 2))
                          (< (nth b 2) 16))))))))

(defn public-addresses [host]
  (let [addresses (InetAddress/getAllByName host)]
    (when-not (and (seq addresses) (every? public-address? addresses))
      (cf/fail! "Image URLs must resolve only to public Internet addresses."))
    addresses))

(defn- u8 [^bytes bytes i] (bit-and 255 (aget bytes i)))
(defn- le [bytes start length]
  (reduce + (map #(bit-shift-left (long (u8 bytes (+ start %))) (* 8 %))
                 (range length))))
(defn- signature? [^bytes bytes offset text]
  (and (<= (+ offset (count text)) (alength bytes))
       (= text (String. bytes (int offset) (int (count text)) "US-ASCII"))))

(defn- dimensions [^bytes bytes]
  (if (and (signature? bytes 0 "RIFF") (signature? bytes 8 "WEBP")
           (>= (alength bytes) 30)
           (= (+ 8 (le bytes 4 4)) (alength bytes)))
    (cond
      (signature? bytes 12 "VP8X")
      ["image/webp" (inc (le bytes 24 3)) (inc (le bytes 27 3))]
      (and (signature? bytes 12 "VP8L") (= 47 (u8 bytes 20)))
      (let [packed (le bytes 21 4)]
        ["image/webp" (inc (bit-and packed 16383))
         (inc (bit-and (bit-shift-right packed 14) 16383))])
      (and (signature? bytes 12 "VP8 ")
           (= [157 1 42] (mapv #(u8 bytes %) [23 24 25])))
      ["image/webp" (bit-and 16383 (le bytes 26 2))
       (bit-and 16383 (le bytes 28 2))]
      :else (cf/fail! "Invalid WebP image."))
    (with-open [stream (MemoryCacheImageInputStream.
                        (ByteArrayInputStream. bytes))]
      (let [readers (ImageIO/getImageReaders stream)]
        (when-not (.hasNext readers)
          (cf/fail! "Images must be PNG, JPEG or WebP."))
        (let [reader (.next readers)]
          (try
            (.setInput reader stream)
            [(case (.toLowerCase (.getFormatName reader))
               "png" "image/png" "jpeg" "image/jpeg" "jpg" "image/jpeg"
               (cf/fail! "Images must be PNG, JPEG or WebP."))
             (.getWidth reader 0) (.getHeight reader 0)]
            (finally (.dispose reader))))))))

(defn fetch-image [url]
  (try
    (let [uri (URI. url)
          host (.getHost uri)]
      (when-not (and (.equalsIgnoreCase "https" (.getScheme uri)) host
                     (nil? (.getUserInfo uri)) (nil? (.getFragment uri))
                     (<= (count url) 4096)
                     (#{-1 443} (.getPort uri)))
        (cf/fail! "Images require public HTTPS URLs without credentials."))
      (let [addresses (public-addresses host)
            resolver (reify DnsResolver
                       (resolve [_ requested-host]
                         (when-not (.equalsIgnoreCase host requested-host)
                           (cf/fail! "Unexpected image host."))
                         addresses))
            manager (BasicHttpClientConnectionManager.
                      (-> (RegistryBuilder/create)
                          (.register
                            "https"
                            (SSLConnectionSocketFactory/getSocketFactory))
                          .build)
                      nil nil resolver)]
        (try
          (let [response (http/get url
                                   {:as :stream :throw-exceptions false
                                    :save-request true
                                    :connection-manager manager
                                    :cookie-policy :none
                                    :http-builder-fns
                                    [(fn [builder _]
                                       (.setRoutePlanner
                                         builder
                                         (DefaultRoutePlanner.
                                           DefaultSchemePortResolver/INSTANCE))
                                       (.disableAuthCaching builder))]
                                    :conn-timeout 5000 :socket-timeout 5000
                                    :redirect-strategy :none
                                    :retry-handler (fn [& _] false)
                                    :decompress-body false
                                    :headers
                                    {"Accept"
                                     "image/png,image/jpeg,image/webp"}})]
            (cf/consume-response response
              (fn [stream]
                (when-not (= 200 (:status response))
                  (cf/fail!
                    "Image download failed; redirects are not followed."))
                (let [bytes (cf/read-bytes stream image-limit 30000)
                      [content-type width height] (dimensions bytes)]
                  (when-not (and (pos? width) (pos? height)
                                 (<= (* (long width) height) 16000000))
                    (cf/fail! "Each image must be at most 16 megapixels."))
                  {:bytes (alength bytes)
                   :image {:content_type content-type
                           :base64 (.encodeToString
                                     (Base64/getEncoder) bytes)}}))))
          (finally (.shutdown manager)))))
    (catch Exception e
      (if (:ai/error (ex-data e)) (throw e)
        (cf/fail! "Could not download or decode the image.")))))

(defn images [urls]
  (when (> (count urls) 4) (cf/fail! "At most four images are supported."))
  (loop [[url & more] urls result [] total 0]
    (if-not url result
      (let [{:keys [bytes image]} (fetch-image url)
            total (+ total bytes)]
        (when (> total (* 8 1024 1024))
          (cf/fail! "Images must total at most 8 MiB."))
        (recur more (conj result image) total)))))
