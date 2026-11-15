(ns yetibot.test.api.cloudflare
  (:require [cheshire.core :as json]
            [clj-http.client :as http]
            [clojure.string :as string]
            [midje.sweet :refer [fact facts =>]]
            [yetibot.api.ai :as ai]
            [yetibot.api.mcp :as mcp]
            [yetibot.commands.ai]
            [yetibot.core.util.agent-execution :as origin]
            [yetibot.util.ai-execution :as execution]
            [yetibot.api.cloudflare :as cf]
            [yetibot.api.cloudflare-images :as images]
            [yetibot.test.support.cloudflare :as fixture])
  (:import [java.io ByteArrayInputStream]
           [java.net InetAddress]
           [org.apache.http.client.methods HttpGet]))

(defn stream-response [body]
  {:status 200
   :body (ByteArrayInputStream.
           (.getBytes (json/generate-string body) "UTF-8"))})
(defn error-message [f]
  (try (f) nil (catch Exception e (.getMessage e))))
(def account (apply str (repeat 32 "a")))
(def provider {:name "cloudflare"
               :url (str "https://api.cloudflare.com/client/v4/accounts/"
                         account "/ai/v1") :key "catalog-token"})
(def payload {:state "urgent" :questions
              {:decision {:type "noul" :instructions "Urgent?"}}})

(fact "Cloudflare discovery uses its catalog, not an assumed /models endpoint"
  (let [calls (atom [])]
    (with-redefs [http/get
                  (fn [url options]
                    (swap! calls conj [url options])
                    (stream-response
                      {:success true :result_info {:total_pages 2}
                       :result (if (= 1 (get-in options [:query-params :page]))
                                 [{:name "@cf/cloudflare/clef"
                                   :task {:name "Text Generation"}}]
                                 [{:name "@cf/cloudflare/clairic"}
                                  {:name "@cf/vendor/chat"}])}))]
      (let [models (try (ai/models provider) (catch Exception _ []))]
        models => ["@cf/cloudflare/clairic" "@cf/cloudflare/clef"
                   "@cf/vendor/chat"]
        (get-in (meta models) [:catalog :source]) => :cloudflare-workers-ai
        (string/includes? (str (get-in (meta models) [:catalog :coverage]))
                          "third-party") => true)
      (count @calls) => 2
      (ffirst @calls) => (str (cf/account-url account) "/ai/models/search")
      (get-in (first @calls) [1 :save-request]) => true
      (get-in (first @calls) [1 :headers "Authorization"])
      => "Bearer catalog-token")))

(fact "named gateways and discovered Workers AI chat models get routing headers"
  (let [calls (atom [])]
    (with-redefs [http/post
                  (fn [url options]
                    (swap! calls conj [url options])
                    {:status 200
                     :body {:choices [{:message {:content "hi"}}]}})]
      (:result/value (ai/complete provider "@cf/vendor/chat"
                                  [{:role "user" :content "hi"}])) => "hi"
      (get-in (last @calls) [1 :headers "cf-aig-gateway-id"]) => "default"
      (ai/complete (assoc provider :gateway-id "named") "openai/chat"
                   [{:role "user" :content "hi"}])
      (get-in (last @calls) [1 :headers "cf-aig-gateway-id"]) => "named")))

(facts "structured inference rejects malformed output and never leaks bodies"
  (doseq [result [{:model "other" :answers {:decision {:type "noul" :noul 0.9}}}
                  {:model "clef" :answers {}}
                  {:model "clef" :answers {:decision {:type "noul" :noul 2}}}
                  {:model "clef" :answers {:decision {:type "choice"
                                                     :choice "unknown"}}}]]
    (with-redefs [http/post (fn [& _] (stream-response
                                      {:success true :result result}))]
      (fact (error-message #(cf/infer! {:account-id account} "secret"
                                       "clef" payload))
            => "Cloudflare returned invalid structured inference results.")))
  (with-redefs [http/post (fn [& _]
                            {:status 401 :body (ByteArrayInputStream.
                                                (.getBytes "SECRET"))})]
    (error-message #(cf/infer! {:account-id account} "secret" "clef" payload))
    => "Cloudflare returned HTTP 401."))

(facts "image fetches require public addresses and never send credentials"
  (doseq [address ["127.0.0.1" "10.0.0.1" "172.16.0.1" "192.168.1.1"
                   "169.254.169.254" "100.64.0.1" "0.0.0.0" "224.0.0.1"
                   "198.51.100.1" "203.0.113.1"
                   "::1" "fc00::1" "fe80::1" "2002:a00:1::"
                   "2001:db8::1" "2001:20::1" "3fff::1"]]
    (fact (images/public-address? (InetAddress/getByName address)) => false))
  (let [requests (atom [])]
    (with-redefs [images/public-addresses
                  (fn [_] (into-array InetAddress
                                      [(InetAddress/getByName "8.8.8.8")]))
                  http/get (fn [url options]
                             (swap! requests conj [url options])
                             {:status 200
                              :body (ByteArrayInputStream. (fixture/png))})]
      (get-in (images/fetch-image "https://images.example/a.png")
              [:image :content_type]) => "image/png"
      (get-in (first @requests) [1 :headers "Authorization"]) => nil
      (get-in (first @requests) [1 :redirect-strategy]) => :none
      (get-in (first @requests) [1 :save-request]) => true
      (get-in (first @requests) [1 :cookie-policy]) => :none
      (count (get-in (first @requests) [1 :http-builder-fns])) => 1
      (some? (get-in (first @requests) [1 :connection-manager])) => true
      (doseq [url ["file:///tmp/a.png" "http://images.example/a.png"
                    "https://user:secret@images.example/a.png"
                    "https://images.example:8443/a.png"]]
        (fact (some? (error-message #(images/fetch-image url))) => true))
    (count @requests) => 1)
  (with-redefs [http/get (fn [& _] (throw (Exception. "must not connect")))]
    (error-message #(images/fetch-image "https://127.0.0.1/a.png"))
    => "Image URLs must resolve only to public Internet addresses."))

(facts "Clairic preserves every supported value type and nullable results"
  (doseq [[field value]
          [[{:type "text"} "Dana"]
           [{:type "number"} 42]
           [{:type "date"} "2026-10-23"]
           [{:type "time"} "12:30:00"]
           [{:type "datetime"} "2026-10-23T12:30:00"]
           [{:type "duration"} 30]
           [{:type "list" :items "number"} [1 2]]
           [{:type "list" :items "date"} ["2026-10-23"]]
           [{:type "estimate" :min 0 :max 100} 42]
           [{:type "estimate" :min "2026-01-01" :max "2026-12-31"}
            "2026-10-23"]
           [{:type "forecast" :min 0 :max 100} [42 43]]
           [{:type "text"} nil]]]
    (let [answer {:type (:type field) :value value}]
      (with-redefs [http/post
                    (fn [& _] (stream-response
                                {:success true :result
                                 {:model "clairic" :truncated false
                                  :answers {:x answer}}}))]
        (fact (:answers (cf/infer! {:account-id account} "secret" "clairic"
                                  {:state "input" :fields {:x field}}))
              => {:x answer})))))

(fact "bounded response cleanup aborts before stream close can drain the body"
  (let [request (HttpGet. "https://example.test/image.png")
        aborted-at-close (atom nil)
        stream (proxy [ByteArrayInputStream] [(byte-array 2)]
                 (close [] (reset! aborted-at-close (.isAborted request))))]
    (error-message
      #(cf/consume-response {:body stream :request {:http-req request}}
                            (fn [body] (cf/read-bytes body 1))))
    => "Cloudflare input or response exceeded its size limit."
    @aborted-at-close => true))

(facts "catalog pagination, response bytes and read deadlines stay bounded"
  (let [calls (atom 0)]
    (with-redefs [http/get
                  (fn [& _]
                    (swap! calls inc)
                    (stream-response
                      {:success true :result_info {:total_pages 100}
                       :result [{:name "@cf/vendor/chat"}]}))]
      (error-message #(cf/catalog provider))
      => "Cloudflare model catalog exceeded 20 pages."
      @calls => 20))
  (error-message #(cf/read-bytes (ByteArrayInputStream. (byte-array 2)) 1))
  => "Cloudflare input or response exceeded its size limit."
  (error-message #(cf/read-bytes (ByteArrayInputStream. (byte-array 1)) 2 0))
  => "Input or response exceeded its read deadline."
  (with-redefs [http/post (fn [& _]
                            (stream-response {:success false
                                              :errors [{:message "SECRET"}]}))]
    (error-message #(cf/infer! {:account-id account} "secret" "clef" payload))
    => "Cloudflare returned an unsuccessful response.")
  (with-redefs [http/post (fn [& _]
                            (stream-response
                              {:success true :result
                               {:model "clef" :answers
                                {:decision {:type "noul" :noul 0.9}}
                                :state_text "secret"}}))]
    (error-message #(cf/infer! {:account-id account} "secret" "clef" payload))
    => "Cloudflare returned sensitive output."))

(facts "image counts, byte bounds, content and redirects are enforced"
  (some? (error-message #(images/images (repeat 5 "https://x/a.png")))) => true
  (with-redefs [images/public-addresses
                (fn [_] (into-array InetAddress
                                    [(InetAddress/getByName "8.8.8.8")]))]
    (doseq [[status bytes] [[302 (fixture/png)] [200 (.getBytes "not an image")]
                            [200 (byte-array (inc images/image-limit))]]]
      (with-redefs [http/get (fn [& _]
                               {:status status
                                :body (ByteArrayInputStream. bytes)})]
        (fact (some? (error-message
                       #(images/fetch-image "https://images.example/a.png"))))
              => true)))))

(fact "explicit structured SDK source is finite and never grants host effects"
  (let [sent (atom 0) captured (atom nil)]
    (with-redefs [http/post
                  (fn [& _]
                    (swap! sent inc)
                    (reset! captured execution/*request*)
                    (fact (origin/capability? origin/*context*) => true
                          execution/*event* => nil
                          (:event execution/*request*) => nil
                          (execution/admissions execution/*request*)
                          => {:limit 20 :admitted 1 :reserved 0 :remaining 19}
                          (try (execution/require-effects!) false (catch Exception _ true)) => true)
                    (stream-response {:success true :result
                                      {:model "clef" :answers {:decision {:type "noul" :noul 0.9}}}}))]
      (binding [execution/*event* {:user {:id "forged"}}]
        (:model (cf/infer! {:account-id account} "secret" "clef" payload)) => "clef")
      @sent => 1
      (:closed? @(:budget @captured)) => true
      (mapv :state (:receipts @(:budget @captured))) => [:definitive])))

(fact "structured SDK inherits the official Context without reset, fallback or host grants"
  (let [sent (atom 0) context (atom nil) request (atom nil)
        infer #(cf/infer! {:account-id account} "secret" "clef" payload)]
    (with-redefs [http/post
                  (fn [& _]
                    (swap! sent inc)
                    (reset! context origin/*context*)
                    (reset! request execution/*request*)
                    (stream-response {:success true :result
                                      {:model "clef" :answers {:decision {:type "noul" :noul 0.9}}}}))]
      (origin/with-scope*
        (fn []
          (infer)
          (let [first-context @context
                inherited (fn [f]
                            ;; Losing the app binding must not mint another family.
                            (binding [execution/*request* nil origin/*context* first-context] (f)))]
            (doseq [f [#(cf/infer! {:account-id (apply str (repeat 32 "b"))} "secret" "clef" payload)
                       #(cf/infer! {:account-id account} "secret" "clairic"
                                   {:state "input" :fields {:x {:type "number"}}})
                       #(cf/infer! {:account-id account} "secret" "clef"
                                   (assoc-in payload [:questions :other] {:type "noul" :instructions "Other?"}))
                       #(ai/complete {:name "unavailable-planner" :url "https://planner.invalid/v1"}
                                     "chat" [])
                       #(origin/invoke! first-context :start "do work")
                       #(origin/invoke! first-context :execute-command {:match ["cmd" "echo derived"]})
                       #(origin/invoke! first-context :admit-command {:cmd "echo"})]]
              (some? (error-message #(inherited f))) => true)
            (with-redefs [mcp/config (constantly {:servers [] :toolsets
                                                 [{:name "restricted" :kind "search" :servers []
                                                   :providers [{:name "other" :models ["other"]}]}]
                                                 :default-toolset "restricted"})]
              (some? (error-message #(inherited infer))) => true)
            (with-redefs [ai/config (constantly {:providers [] :intent-router {:account-id account}})]
              (some? (error-message #(inherited infer))) => true)
            @sent => 1
            (dotimes [_ 19] (inherited infer))
            (identical? first-context @context) => true
            (some? (error-message #(inherited infer))) => true
            @sent => 20
            (:used @(:budget @request)) => 20
            (every? #(= :definitive (:state %)) (:receipts @(:budget @request))) => true)))
      (:closed? @(:budget @request)) => true
      (binding [execution/*request* nil origin/*context* @context]
        (some? (error-message infer)) => true)
      (origin/close! @context)
      (binding [execution/*request* nil origin/*context* @context]
        (some? (error-message infer)) => true)
      @sent => 20)))
