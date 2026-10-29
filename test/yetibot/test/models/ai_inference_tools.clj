(ns yetibot.test.models.ai-inference-tools
  (:require [cheshire.core :as json]
            [clj-http.client :as http]
            [clojure.string :as string]
            [midje.sweet :refer [fact facts => throws contains]]
            [yetibot.api.clef :as clef]
            [yetibot.api.cloudflare :as cf]
            [yetibot.api.cloudflare-images :as images]
            [yetibot.core.config :as config]
            [yetibot.models.ai-inference-tools :as tools]
            [yetibot.test.support.cloudflare :as fixture]
            [yetibot.util.ai-execution :as execution])
  (:import [java.io ByteArrayInputStream File]
           [java.net InetAddress]))

(def event
  {:event-type :message :user {:id "U1"}
   :chat-source {:adapter :slack :uuid "workspace" :room "private"
                 :raw-event {:user "U1" :channel "C1" :ts "now"}}})
(def calls (atom []))
(def downloads (atom []))
(def basic {"question" "Is this an outage?" "input" "service is down"})
(def extraction {"fields" {"amount" {"type" "number"}}
                 "input" "total 42"})
(def account (apply str (repeat 32 "a")))
(defn response [result]
  {:status 200 :body (ByteArrayInputStream.
                      (.getBytes (json/generate-string
                                   {:success true :result result}) "UTF-8"))})
(defn answer [field]
  (case (:type field)
    "noul" {:type "noul" :noul 0.98}
    "choice" (let [ids (keys (:criteria field))]
               {:type "choice" :choice (name (first ids)) :confidence 0.5
                :probabilities (zipmap ids (repeat (/ 1.0 (count ids))))})
    "score" {:type "score" :score 0.5 :confidence 0.5
             :probabilities {:0 0.5 :1 0.5}}
    {:type (:type field) :value
     (case (:type field)
       "text" "receipt" "number" 42 "date" "2026-10-23"
       "time" "12:30:00" "datetime" "2026-10-23T12:30:00Z"
       "duration" 30 "list" ["receipt"] "estimate" 42
       "forecast" [42 43])}))
(defn infer-response [url opts]
  (let [body (json/parse-string (:body opts) true)]
    (swap! calls conj [url opts body])
    (response {:model (:model body) :truncated false
               :answers (into {} (map (fn [[id field]] [id (answer field)])
                                     (or (:questions body) (:fields body))))
               :usage {:input_tokens 10 :output_tokens 0}})))
(defn with-fixture [token f]
  (let [file (File/createTempFile "inference-fake-" ".key")
        settings {:enabled false :account-id account
                  :key-file (.getAbsolutePath file) :gateway-id "test"}]
    (try
      (spit file token)
      (reset! calls [])
      (reset! downloads [])
      (binding [execution/*event* event execution/*sensitive* (atom false)]
        (with-redefs
          [config/get-config (fn [& _] {:value {:intent-router settings}})
           http/post infer-response
           images/public-addresses
           (fn [_] (into-array InetAddress
                               [(InetAddress/getByName "8.8.8.8")]))
           http/get (fn [url opts]
                      (swap! downloads conj [url opts])
                      {:status 200 :body
                       (ByteArrayInputStream. (fixture/png))})]
          (f settings)))
      (finally (.delete file)))))
(defn invoke [operation args]
  (tools/call! {} operation args (:chat-source execution/*event*)))

(fact "discovery is passive, validates config, and ignores routing enabled"
  (with-fixture "fake-token"
    (fn [_]
      (with-redefs [clef/credential! (fn [& _] (throw (Exception. "read")))
                    http/post (fn [& _] (throw (Exception. "paid")))]
        (tools/available-tools) => ["clef" "clairic"])))
  (with-redefs [config/get-config (constantly {:value {}})]
    (tools/available-tools) => [])
  (with-redefs [config/get-config
                (constantly {:value {:intent-router
                                      {:account-id "bad" :key-file "/fake"}}})]
    (tools/available-tools) => []))

(facts "preflight validates every nested argument without side effects"
  (with-redefs [clef/credential! (fn [& _] (throw (Exception. "read")))
                images/images (fn [& _] (throw (Exception. "fetch")))
                cf/infer! (fn [& _] (throw (Exception. "paid")))]
    (tools/arguments! {} "clef" basic) => basic
    (doseq [args [(assoc basic "host" "https://evil.example")
                 (assoc basic "source" (:chat-source event))
                 (assoc basic "input" (apply str (repeat 60001 "x")))
                 (assoc basic "images" ["http://example.com/a.png"])
                 (assoc basic "images" ["https://u:p@example.com/a.png"])
                 (assoc basic "questions" {"x" {"type" "noul"}})
                 {"questions" {"x" {"type" "noul" "instructions" "Q"
                                       "secret" "no"}} "input" "text"}
                 {"questions" {} "input" "text"}]]
      (fact (tools/arguments! {} "clef" args) => (throws Exception)))
    (doseq [field [{"type" "boolean"}
                  {"type" "text" "description" "not supported"}
                  {"type" "list" "items" {"type" "text"}}
                  {"type" "estimate" "min" 0}
                  {"type" "number" "unit" {"key-file" "/secret"}}]]
      (fact (tools/arguments! {} "clairic"
              (assoc extraction "fields" {"x" field}))
            => (throws Exception)))))

(fact "all advertised Clairic types preserve typed values, no boolean"
  (with-fixture "fake-token"
    (fn [_]
      (doseq [field [{"type" "text"} {"type" "number" "unit" "USD"
                                     "decimals" 2}
                    {"type" "date"} {"type" "time"} {"type" "datetime"}
                    {"type" "duration"} {"type" "list" "items" "text"}
                    {"type" "estimate" "min" 0 "max" 100}
                    {"type" "forecast" "min" 0 "max" 100}]]
        (let [r (invoke "clairic" (assoc extraction "fields" {"x" field}))]
          (fact (:isError r) => nil)
          (fact (get-in r [:structuredContent :answers :x :type])
                => (get field "type"))))
      (fact (count @calls) => 9)
      (fact (get-in (first @calls) [2 :fields :x :type]) => "text"))))

(fact "Clef plain, choice, and score use direct paid API exactly once each"
  (with-fixture "fake-token"
    (fn [_]
      (get-in (invoke "clef" basic)
              [:structuredContent :answers :decision :noul]) => 0.98
      (doseq [field [{"type" "choice" "instructions" "Which team?"
                     "criteria" {"ops" "Outages" "sales" "Orders"}}
                    {"type" "score" "instructions" "Severity?"
                     "criteria" ["low" "high"]}]]
        (fact (:isError (invoke "clef" {"questions" {"x" field}
                                         "input" "outage"})) => nil))
      (count @calls) => 3
      (get-in (first @calls) [2 :state]) => "service is down"
      (get-in (first @calls) [1 :headers "cf-aig-gateway-id"]) => "test")))

(fact "escaped raw credentials are rejected in nested strings before fetch"
  (with-fixture "fake-\"token\\value"
    (fn [_]
      (doseq [args [(assoc basic "input" "fake-\"token\\value")
                   (assoc basic "question" "fake-\"token\\value")
                   {"questions" {"x" {"type" "choice"
                                        "instructions" "Which?"
                                        "criteria"
                                        {"a" "fake-\"token\\value"
                                         "b" "other"}}}
                    "input" "text"}]]
        (fact (:isError (invoke "clef" args)) => true))
      (fact @calls => [])
      (fact @downloads => [])
      (fact (tools/arguments!
              {:servers [{:headers {"Authorization" "Bearer mcp-\"x\\y"}}]}
              "clef" (assoc basic "input" "mcp-\"x\\y"))
            => (throws Exception)))))

(fact "forged and stale sources, bots, and absent logging context fail closed"
  (with-fixture "fake-token"
    (fn [_]
      (tools/call! {} "clef" basic
                   (assoc (:chat-source event) :room "other"))
      => (contains {:isError true})
      (binding [execution/*event* (assoc-in event [:user :id] "U2")]
        (:isError (invoke "clef" basic)) => true)
      (binding [execution/*event* (assoc-in event [:user :yetibot?] true)]
        (:isError (invoke "clef" basic)) => true)
      (binding [execution/*sensitive* nil]
        (:isError (invoke "clef" basic)) => true)
      @calls => [])))

(fact "trusted attachments and explicit URLs use guarded image transport"
  (with-fixture "fake-token"
    (fn [_]
      (binding [execution/*event*
                (assoc-in event [:chat-source :raw-event :attachments]
                          [{:url "https://images.example/receipt"
                            :content-type "image/png"}])]
        (:isError (invoke "clef" (dissoc basic "input"))) => nil
        (count @downloads) => 1
        (get-in (first @calls) [2 :images 0 :content_type]) => "image/png")
      (:isError (invoke "clairic"
                  (assoc extraction "images"
                         ["https://images.example/other.png"]))) => nil
      (count @calls) => 2
      (get-in (first @downloads) [1 :redirect-strategy]) => :none
      (get-in (first @downloads) [1 :headers "Authorization"]) => nil)))

(fact "failed inference is uncertain and sanitized, no automatic paid retry"
  (with-fixture "fake-token"
    (fn [_]
      (with-redefs [http/post (fn [& _]
                               (swap! calls conj :attempt)
                               (throw (ex-info "fake-token provider payload"
                                               {:request "secret"})))]
        (let [r (invoke "clef" basic)]
          (:isError r) => true
          (:unknownOutcome r) => true
          (string/includes? (str r) "fake-token") => false
          (count @calls) => 1)))))

(defn field-arguments [field]
  (assoc extraction "fields" {"x" field}))

(defn rejects-before-effects [field]
  (with-fixture "fake-token"
    (fn [_]
      (let [effects (atom [])
            args (assoc (field-arguments field)
                        "images" ["https://example.com/receipt.png"])]
        (with-redefs
          [clef/credential! (fn [_]
                              (swap! effects conj :credential) "fake-token")
           images/images (fn [_] (swap! effects conj :image) [])
           cf/infer! (fn [& _] (swap! effects conj :inference) {})]
          (fact (tools/arguments! {} "clairic" args) => (throws Exception))
          (let [r (invoke "clairic" args)]
            (fact (:isError r) => true)
            (fact (:unknownOutcome r) => nil))
          (fact @effects => []))))))

(fact "number decimals match the official 0 through 6 range"
  (with-fixture "fake-token"
    (fn [_]
      (doseq [n [0 6]]
        (let [args (field-arguments {"type" "number" "decimals" n})]
          (fact (tools/arguments! {} "clairic" args) => args)
          (fact (:isError (invoke "clairic" args)) => nil)
          (fact (get-in (last @calls) [2 :fields :x :decimals]) => n)))
      (fact (count @calls) => 2)))
  (rejects-before-effects {"type" "number" "decimals" 7}))

(fact "optional instructions reach the API for all nine Clairic types"
  (with-fixture "fake-token"
    (fn [_]
      (doseq [field [{"type" "text"} {"type" "number"}
                    {"type" "date"} {"type" "time"} {"type" "datetime"}
                    {"type" "duration"} {"type" "list" "items" "text"}
                    {"type" "estimate" "min" 0 "max" 100}
                    {"type" "forecast" "min" 0 "max" 100}]]
        (let [instructions "Extract only the amount from the receipt"
              args (field-arguments (assoc field "instructions" instructions))]
          (fact (tools/arguments! {} "clairic" args) => args)
          (fact (:isError (invoke "clairic" args)) => nil)
          (fact (get-in (last @calls) [2 :fields :x :instructions])
                => instructions)))
      (fact (count @calls) => 9)))
  (doseq [instructions ["" (apply str (repeat 2000 "x"))]]
    (let [args (field-arguments {"type" "text"
                                 "instructions" instructions})]
      (fact (tools/arguments! {} "clairic" args) => args)))
  (doseq [instructions [nil 42 {"host" "evil"}
                        (apply str (repeat 2001 "x"))]]
    (rejects-before-effects {"type" "text" "instructions" instructions}))
  (rejects-before-effects {"type" "text" "instructions" "Extract"
                           "description" "Still not supported"}))

(fact "field instructions cannot carry escaped Cloudflare or MCP credentials"
  (with-fixture "cf-\"token\\value"
    (fn [_]
      (let [args (field-arguments {"type" "text"
                                   "instructions" "cf-\"token\\value"})
            r (invoke "clairic"
                      (assoc args "images" ["https://example.com/a.png"]))]
        (:isError r) => true
        (:unknownOutcome r) => nil
        @downloads => []
        @calls => [])
      (let [args (field-arguments {"type" "date"
                                   "instructions" "mcp-\"x\\y"})]
        (tools/arguments! {:secrets ["mcp-\"x\\y"]} "clairic" args)
        => (throws Exception)))))

(fact "estimate and forecast string bounds are strict ordered ISO dates"
  (with-fixture "fake-token"
    (fn [_]
      (doseq [type ["estimate" "forecast"]
              bounds [["2024-02-29" "2024-03-01"] [0 100]]]
        (let [[minimum maximum] bounds
              args (field-arguments {"type" type
                                     "min" minimum "max" maximum})]
          (fact (tools/arguments! {} "clairic" args) => args)
          (with-redefs [answer (fn [_]
                                {:type type
                                 :value (if (= type "forecast")
                                          [minimum] minimum)})]
            (fact (:isError (invoke "clairic" args)) => nil))
          (fact (get-in (last @calls) [2 :fields :x :min]) => minimum)))
      (fact (count @calls) => 4)))
  (doseq [type ["estimate" "forecast"]
          [minimum maximum]
          [["alpha" "omega"] ["2026-02-29" "2026-03-01"]
           ["2024-02-30" "2024-03-01"] ["2026-01-01" "2026-13-01"]
           ["2026-1-01" "2026-12-01"] ["2026-01-01Z" "2026-12-01Z"]
           ["2026-01-01+01:00" "2026-12-01+01:00"]
           ["2026-01-01T00:00:00Z" "2026-12-01T00:00:00Z"]
           ["+02026-01-01" "+02026-12-01"]
           ["2026-01-01" "2026-01-01"] ["2026-12-01" "2026-01-01"]
           [0 "2026-01-01"] [0 0] [100 0] [0 Double/POSITIVE_INFINITY]]]
    (rejects-before-effects {"type" type "min" minimum "max" maximum})))

(fact "duration has enumerated units while number units remain free form"
  (with-fixture "fake-token"
    (fn [_]
      (doseq [unit ["seconds" "minutes" "hours" "days"
                    "weeks" "months" "years"]]
        (let [args (field-arguments {"type" "duration" "unit" unit})]
          (fact (tools/arguments! {} "clairic" args) => args)
          (fact (:isError (invoke "clairic" args)) => nil)
          (fact (get-in (last @calls) [2 :fields :x :unit]) => unit)))
      (let [args (field-arguments {"type" "number" "unit" "USD"})]
        (tools/arguments! {} "clairic" args) => args
        (:isError (invoke "clairic" args)) => nil)
      (count @calls) => 8))
  (doseq [unit ["USD" "second" "SECONDS" "fortnights"]]
    (rejects-before-effects {"type" "duration" "unit" unit})))

(fact "advertised field variants mirror the official bounded subset"
  (let [variants (get-in tools/definitions
                         ["clairic" :inputSchema :properties :fields
                          :additionalProperties :oneOf])
        by-type (into {} (map #(vector
                                (first (get-in % [:properties :type :enum])) %)
                             variants))]
    (count by-type) => 9
    (get-in by-type ["number" :properties :decimals :maximum]) => 6
    (get-in by-type ["number" :properties :unit :maxLength]) => 100
    (get-in by-type ["duration" :properties :unit :enum])
    => ["seconds" "minutes" "hours" "days" "weeks" "months" "years"]
    (doseq [variant variants]
      (fact (:additionalProperties variant) => false)
      (fact (get-in variant [:properties :instructions])
            => {:type "string" :maxLength 2000})
      (fact (some #{"instructions"} (:required variant)) => nil))
    (doseq [type ["estimate" "forecast"] bound [:min :max]]
      (let [date-schema (second (get-in by-type
                                        [type :properties bound :oneOf]))]
        (fact (:format date-schema) => "date")
        (fact (:pattern date-schema) => "^[0-9]{4}-[0-9]{2}-[0-9]{2}$")
        (fact (:maxLength date-schema) => 10)))))

(fact "schema and aggregate limits reject before any credentials or HTTP"
  (let [reads (atom 0)
        field {"type" "noul" "instructions" "Q"}
        schema (into {} (for [n (range 64)] [(str "f" n) field]))]
    (with-fixture "fake-token"
      (fn [_]
        (with-redefs [clef/credential! (fn [_] (swap! reads inc) "fake-token")]
          (tools/arguments! {} "clef" {"questions" schema "input" "x"})
          => {"questions" schema "input" "x"}
          (doseq [args [{"questions" (assoc schema "overflow" field)
                        "input" "x"}
                       (assoc basic "input" (apply str (repeat 12001 "x")))
                       {"question" (apply str (repeat 2000 "q"))
                        "input" (apply str (repeat 12000 "\\"))}
                       (assoc basic "images"
                              (vec (repeat 5 "https://example.com/a.png")))]]
            (fact (:isError (invoke "clef" args)) => true))
          (fact @reads => 0)
          (fact @calls => [])
          (fact @downloads => []))))))

(fact "MCP credentials and provider keys are never sent to Cloudflare"
  (with-fixture "fake-token"
    (fn [settings]
      (with-redefs [config/get-config
                    (constantly
                      {:value {:intent-router settings
                               :providers [{:key "provider-\"x\\y"}]}})]
        (:isError (invoke "clef"
                    (assoc basic "input" "provider-\"x\\y"))) => true)
      (:isError
        (tools/call!
          {:servers [{:env {"TOKEN" "mcp-\"x\\y"}}]}
          "clef" (assoc basic "input" "mcp-\"x\\y")
          (:chat-source execution/*event*))) => true
      @calls => [])))

(fact "raw escaped output credentials are redacted structurally, extras dropped"
  (with-fixture "cf-\"token\\value"
    (fn [_]
      (with-redefs
        [http/post
         (fn [& _]
           (swap! calls conj :attempt)
           (response
             {:model "clairic" :truncated true
              :request {"Authorization" "Bearer cf-\"token\\value"}
              :images [{:base64 "NEVER-RETURN"}]
              :answers {:note {:type "text"
                               :value "cf-\"token\\value mcp-\"x\\y"
                               :guess "NEVER-RETURN"
                               :raw_request "NEVER-RETURN"}}}))]
        (let [r (tools/call!
                  {:secrets ["mcp-\"x\\y"]} "clairic"
                  {"fields" {"note" {"type" "text"}} "input" "receipt"}
                  (:chat-source execution/*event*))]
          (:isError r) => nil
          (get-in r [:structuredContent :answers :note :value])
          => "[redacted] [redacted]"
          (get-in r [:structuredContent :truncated]) => true
          (string/includes? (str r) "NEVER-RETURN") => false
          (count @calls) => 1)))))

(fact "large valid typed results are shortened within the native reply cap"
  (with-fixture "fake-token"
    (fn [_]
      (let [fields (into {} (for [n (range 64)]
                             [(str "f" n) {"type" "text"}]))]
        (with-redefs [answer (fn [_] {:type "text"
                                      :value (apply str (repeat 800 "🙂\n"))})]
          (let [r (invoke "clairic" {"fields" fields "input" "receipt"})]
            (:isError r) => nil
            (get-in r [:structuredContent :output_truncated]) => true
            (count (get-in r [:structuredContent :answers])) => 64
            (< (count (json/generate-string r)) 20000) => true
            (every? #(= % (String. (.getBytes ^String % "UTF-8") "UTF-8"))
                    (map :value
                         (vals (get-in r [:structuredContent :answers]))))
            => true
            (count @calls) => 1))))))

(fact "all attachments are validated before fetching the first image"
  (with-fixture "fake-token"
    (fn [_]
      (doseq [urls [(mapv #(str "https://example.com/" % ".png") (range 5))
                    ["https://example.com/good.png"
                     "http://localhost/private.png"]]]
        (binding [execution/*event*
                  (assoc-in event [:chat-source :raw-event :attachments]
                            (mapv #(hash-map :url % :content-type "image/png")
                                  urls))]
          (let [r (invoke "clef" basic)]
            (fact (:isError r) => true)
            (fact (:unknownOutcome r) => nil))))
      @downloads => []
      @calls => [])))

(fact "existing SSRF, URL privacy and redirect guards precede inference"
  (with-fixture "fake-token"
    (fn [_]
      (doseq [url ["http://localhost/a.png"
                   "https://user:pass@example.com/a.png"
                   "https://example.com:8443/a.png"
                   "https://example.com/a.png#private"]]
        (fact (:isError (invoke "clef" (assoc basic "images" [url]))) => true))
      (with-redefs [images/public-addresses
                    (fn [host]
                      ;; Exercise the real existing public-address policy.
                      (let [address (InetAddress/getByName "127.0.0.1")]
                        (when-not (images/public-address? address)
                          (cf/fail! "private DNS"))
                        (into-array InetAddress [address])))]
        (let [r (invoke "clef"
                        (assoc basic "images" ["https://internal/a.png"]))]
          (:isError r) => true
          (:unknownOutcome r) => nil))
      @downloads => []
      (with-redefs [http/get (fn [& _]
                              {:status 302 :headers {"Location" "http://local"}
                               :body (ByteArrayInputStream. (byte-array 0))})]
        (:isError (invoke "clef"
                    (assoc basic "images" ["https://example.com/a.png"])))
        => true)
      @calls => [])))

(fact "per-image and aggregate byte bounds fail before paid inference"
  (with-fixture "fake-token"
    (fn [_]
      (with-redefs
        [http/get (fn [& _]
                    {:status 200 :body
                     (ByteArrayInputStream.
                       (byte-array (inc (* 4 1024 1024))))})]
        (let [r (invoke "clef"
                        (assoc basic "images" ["https://example.com/a.png"]))]
          (:isError r) => true
          (:unknownOutcome r) => nil))
      (with-redefs [images/fetch-image
                    (fn [_] {:bytes (* 4 1024 1024)
                             :image {:base64 "FAKE"
                                     :content_type "image/png"}})]
        (let [r (invoke "clef"
                        (assoc basic "images"
                               ["https://example.com/1.png"
                                "https://example.com/2.png"
                                "https://example.com/3.png"]))]
          (:isError r) => true
          (:unknownOutcome r) => nil))
      @calls => [])))

(fact "token read failures and invalid provider responses never leak details"
  (with-fixture "fake-token"
    (fn [_]
      (with-redefs [clef/credential!
                    (fn [_] (throw (ex-info "/private/key fake-token" {})))]
        (let [r (invoke "clef" basic)]
          (:isError r) => true
          (:unknownOutcome r) => nil
          (string/includes? (str r) "/private/key") => false))
      @calls => []
      (with-redefs [http/post (fn [& _]
                               (swap! calls conj :attempt)
                               (response {:model "clef"
                                          :raw "fake-token"
                                          :answers {:decision {:type "noul"
                                                               :noul 9}}}))]
        (let [r (invoke "clef" basic)]
          (:isError r) => true
          (:unknownOutcome r) => true
          (string/includes? (str r) "fake-token") => false
          (count @calls) => 1)))))
