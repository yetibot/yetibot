(ns yetibot.util
  (:require
   [clojure.data.codec.base64 :as b64]
   [clojure.string :as string]
   [jdk.security.KeyFactory :refer [*get-instance]]
   [jdk.security.Signature :as sig]
   [jdk.security.spec.PKCS8EncodedKeySpec :refer [->pkcs-8-encoded-key-spec]]
   [oauth.signature]
   [taoensso.timbre :refer [info warn error]])
  (:import [java.nio.charset StandardCharsets]))

;; oauth1 utils for jira oauth1 support

(defn rsa-sign [data-to-sign priv-key]
  (let [key-bytes (b64/decode (.getBytes priv-key))
        key-spec (->pkcs-8-encoded-key-spec key-bytes)
        key-factory (*get-instance "RSA")
        private-key (.generatePrivate key-factory key-spec)
        ;; _ (info "private-key" private-key)
        signer (sig/*get-instance "SHA1withRSA")
        data (.getBytes data-to-sign StandardCharsets/UTF_8)
        ;; _ (info "data bytes" (type data))
        ]
    (sig/init-sign signer private-key)
    (.update signer data)
    (b64/encode (sig/sign signer))))

(defn oauth1-credentials
  "Return authorization credentials needed for access to protected resources.
   The key-value pairs returned as a map will need to be added to the
   Authorization HTTP header or added as query parameters to the request.

   Modified from https://github.com/mattrepl/clj-oauth/blob/master/src/oauth/client.clj"
  ([consumer token verifier request-method request-uri & [request-params]]
   (let [unsigned-oauth-params
         (oauth.signature/oauth-params
          consumer
          (oauth.signature/rand-str 30)
          (oauth.signature/msecs->secs (System/currentTimeMillis))
          token
          verifier)
         unsigned-params (dissoc (merge request-params
                                        unsigned-oauth-params
                                        {:oauth_verifier verifier})
                                 :oauth_version)
         _ (info "unsigned-params" unsigned-params)
         data-to-sign (oauth.signature/base-string
                       (-> request-method
                           oauth.signature/as-str
                           string/upper-case)
                       request-uri
                       unsigned-params)
         _ (info "data-to-sign" data-to-sign)
         signature (String. (rsa-sign data-to-sign (:secret consumer)))]
     (assoc unsigned-oauth-params :oauth_signature signature))))
