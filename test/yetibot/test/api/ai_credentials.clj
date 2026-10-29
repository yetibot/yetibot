(ns yetibot.test.api.ai-credentials
  (:require [cheshire.core :as json]
            [clj-http.client :as http]
            [clojure.spec.alpha :as s]
            [clojure.string :as string]
            [dec :refer [explode]]
            [midje.sweet :refer [fact facts =>]]
            [yetibot.api.ai :as ai]
            [yetibot.util.ai-config :as settings])
  (:import [com.sun.net.httpserver HttpServer HttpHandler]
           [java.io ByteArrayInputStream]
           [java.net InetSocketAddress]))

(def provider {:name "test" :url "https://example.com/v1"})
(def messages [{:role "user" :content "hello"}])

(defn error [f]
  (try (f) nil (catch Exception e (.getMessage e))))

(facts "key-file authenticates selected GET and POST without passive reads"
  (let [file (java.io.File/createTempFile "ai-token" ".txt")
        calls (atom [])]
    (try
      (spit file "private-token\n")
      (let [p (assoc provider :key-file (.getAbsolutePath file))]
        (with-redefs [http/get
                      (fn [_ options]
                        (swap! calls conj (:headers options))
                        {:status 200 :body {:data [{:id "chat"}]}})
                      http/post
                      (fn [_ options]
                        (swap! calls conj (:headers options))
                        {:status 200
                         :body {:choices [{:message {:content "ok"}}]}})]
          (ai/models p) => ["chat"]
          (:result/value (ai/complete p "chat" messages)) => "ok"
          (mapv #(get % "Authorization") @calls)
          => ["Bearer private-token" "Bearer private-token"]))
      (finally (.delete file)))))

(facts "credential configuration is explicit and normalized"
  (s/valid? ::ai/provider (assoc provider :key-file "/tmp/token")) => true
  (s/valid? ::ai/provider (assoc provider :key-file "relative")) => false
  (s/valid? ::ai/provider
            (assoc provider :key "inline" :key-file "/tmp/token")) => false
  (get-in (settings/ai {:providers [(assoc provider :key {:file "/tmp/t"})]})
          [:providers 0 :key-file]) => "/tmp/t")

(facts "malformed nested keys must not become anonymous providers"
  (doseq [key [{} {:unexpected "token"} {:file nil}]]
    (fact (s/valid? ::ai/config {:providers [(assoc provider :key key)]})
          => false)))

(fact "exploded KEY_FILE normalizes idempotently without reading the file"
  (let [expanded (get-in
                   (explode
                     {:yetibot-ai-default-provider "local"
                      :yetibot-ai-providers-0-name "local"
                      :yetibot-ai-providers-0-url "http://localhost/v1"
                      :yetibot-ai-providers-0-key-file "/no/such/token"
                      :yetibot-ai-providers-1-name "other"
                      :yetibot-ai-providers-1-url "http://localhost/other"})
                   [:yetibot :ai])
        normalized (settings/ai expanded)]
    (s/valid? ::ai/config expanded) => true
    (settings/ai normalized) => normalized
    (get-in normalized [:providers 0 :key-file]) => "/no/such/token"
    (contains? (first (:providers normalized)) :key) => false
    (:default-provider normalized) => "local"
    (mapv :name (:providers normalized)) => ["local" "other"]))

(facts "unusable credential files fail closed with sanitized errors"
  (let [file (java.io.File/createTempFile "ai-token" ".txt")
        calls (atom 0)
        p (assoc provider :key-file (.getAbsolutePath file))]
    (try
      (with-redefs [http/post (fn [& _] (swap! calls inc))]
        (doseq [token ["" "   " "token\nHeader: secret"
                       (string/join (repeat 8193 "x"))]]
          (spit file token)
          (fact (error #(ai/complete p "chat" messages))
                => "AI provider credentials are unavailable or invalid."))
        (.delete file)
        (error #(ai/complete p "chat" messages))
        => "AI provider credentials are unavailable or invalid."
        @calls => 0)
      (finally (.delete file)))))

(facts "conflicts and non-regular/unreadable files fail before transport"
  (let [file (java.io.File/createTempFile "ai-private" ".txt")
        calls (atom 0)]
    (try
      (spit file "private-token")
      (with-redefs [http/get (fn [& _] (swap! calls inc))]
        (doseq [p [(assoc provider :key "inline"
                         :key-file (.getAbsolutePath file))
                   (assoc provider :key-file (.getParent file))
                   (assoc provider :key-file "relative")]]
          (fact (error #(ai/models p))
                => "AI provider credentials are unavailable or invalid."))
        (.setReadable file false false)
        (error #(ai/models
                  (assoc provider :key-file (.getAbsolutePath file))))
        => "AI provider credentials are unavailable or invalid."
        @calls => 0)
      (finally (.setReadable file true true) (.delete file)))))

(facts "bounded output options are validated and serialized, never dropped"
  (let [wire (atom [])]
    (with-redefs [http/post
                  (fn [_ options]
                    (swap! wire conj (:form-params options))
                    {:status 200 :body {}})]
      (ai/chat provider "chat" messages {:max-output-tokens 2048})
      (ai/chat (assoc provider :api "responses") "chat" messages
               {:max-output-tokens 120})
      (:max_completion_tokens (first @wire)) => 2048
      (:max_output_tokens (second @wire)) => 120
      (doseq [n [0 -1 2049 "2048" nil]]
        (fact (boolean
                (error #(ai/chat provider "chat" messages
                                 {:max-output-tokens n}))) => true))
      (count @wire) => 2)))

(facts "Cloudflare GET and POST resolve the same private credential"
  (let [file (java.io.File/createTempFile "cf-token" ".txt")
        calls (atom [])
        p (assoc provider :name "cloudflare"
                 :key-file (.getAbsolutePath file)
                 :url (str "https://api.cloudflare.com/client/v4/accounts/"
                           (string/join (repeat 32 "a")) "/ai/v1"))]
    (try
      (spit file "private-token")
      (with-redefs
        [http/get
         (fn [_ options]
           (swap! calls conj options)
           {:status 200
            :body (ByteArrayInputStream.
                    (.getBytes
                      (json/generate-string
                        {:success true :result
                         [{:name "@cf/vendor/chat"
                           :task {:name "Text Generation"}}
                          {:name "@cf/cloudflare/clef"
                           :task {:name "Text Generation"}}
                          {:name "@cf/cloudflare/clairic"
                           :task {:name "Text Generation"}}
                          {:name "@cf/vendor/image"
                           :task {:name "Text-to-Image"}}
                          {:name "@cf/vendor/audio"
                           :task {:name "Automatic Speech Recognition"}}
                          {:name "@cf/vendor/embed"
                           :task {:name "Text Embeddings"}}
                          {:name "@cf/vendor/unknown"}]}) "UTF-8"))})
         http/post
         (fn [_ options]
           (swap! calls conj options)
           {:status 200 :body {:choices [{:message {:content "answer"}}]}})]
        (let [models (ai/models p)
              interfaces (into {} (map (juxt :id :interface)
                                       (:models (:catalog (meta models)))))]
          (get interfaces "@cf/vendor/chat") => :chat
          (get interfaces "@cf/cloudflare/clef") => :decision
          (get interfaces "@cf/cloudflare/clairic") => :extraction
          (mapv interfaces ["@cf/vendor/image" "@cf/vendor/audio"
                             "@cf/vendor/embed" "@cf/vendor/unknown"])
          => [:model-specific :model-specific :model-specific :model-specific])
        (:result/value
          (ai/complete p "@cf/vendor/chat" messages
                       {:max-output-tokens 2048})) => "answer"
        (mapv #(get-in % [:headers "Authorization"]) @calls)
        => ["Bearer private-token" "Bearer private-token"]
        (get-in @calls [1 :form-params :max_tokens]) => 2048)
      (with-redefs [http/post
                    (fn [& _]
                      (throw (ex-info
                               (json/generate-string
                                 {:key "private-token"
                                  :path (.getAbsolutePath file)})
                               {:headers {"Authorization" "private-token"}})))]
        (error #(ai/complete p "chat" messages))
        => (str "Could not reach AI provider cloudflare"
                " or read its response."))
      (finally (.delete file)))))

(fact "output caps reach actual serialized HTTP JSON for both API dialects"
  (let [wire (atom [])
        server (HttpServer/create (InetSocketAddress. "127.0.0.1" 0) 0)]
    (.createContext server "/"
      (reify HttpHandler
        (handle [_ exchange]
          (swap! wire conj (json/parse-string
                             (slurp (.getRequestBody exchange)) true))
          (.set (.getResponseHeaders exchange) "Content-Type"
                "application/json")
          (.sendResponseHeaders exchange 200 2)
          (with-open [out (.getResponseBody exchange)]
            (.write out (.getBytes "{}" "UTF-8"))))))
    (.start server)
    (try
      (let [p (assoc provider :url
                     (str "http://127.0.0.1:"
                          (.getPort (.getAddress server)) "/v1"))]
        (ai/chat p "chat" messages {:max-output-tokens 2048})
        (ai/chat (assoc p :api "responses") "chat" messages
                 {:max-output-tokens 17})
        (:max_completion_tokens (first @wire)) => 2048
        (:max_output_tokens (second @wire)) => 17)
      (finally (.stop server 0)))))
