(ns yetibot.test.commands.system-one
  (:require [cheshire.core :as json]
            [clj-http.client :as http]
            [clojure.string :as string]
            [midje.sweet :refer [fact facts =>]]
            [yetibot.commands.system-one]
            [yetibot.commands.json]
            [yetibot.api.clef :as clef]
            [yetibot.api.cloudflare-images :as images]
            [yetibot.test.support.cloudflare :as fixture]
            [yetibot.test.support.mcp :as support]
            [yetibot.core.config :as config]
            [yetibot.core.commands.collections]
            [yetibot.core.commands.echo]
            [yetibot.test.support.ai-execution :as chat])
  (:import [java.io ByteArrayInputStream]
           [java.net InetAddress]))

(def settings
  {:enabled false :account-id (apply str (repeat 32 "a"))
   :key-file (.getAbsolutePath (java.io.File/createTempFile "cf-test-" ".key"))
   :gateway-id "test"})
(def original-config config/get-config)
(defn command-config [spec path]
  (if (= path [:ai])
    {:value {:intent-router settings}}
    (original-config spec path)))
(def calls (atom []))
(defn sample-answer [field]
  (case (:type field)
    "noul" {:type "noul" :noul 0.98}
    "choice" (let [options (keys (:criteria field))
                   choice (first options)]
               {:type "choice" :choice (name choice) :confidence 0.5
                :probabilities (zipmap options (repeat 0.5))})
    "score" {:type "score" :score 0.5 :confidence 0.5
             :probabilities {:0 0.5 :1 0.5} :legend {:0 "low" :1 "high"}}
    {:type (:type field) :value 42 :confidence 0.99}))

(defn reply [url options]
  (let [body (json/parse-string (:body options) true)
        clef? (= "clef" (:model body))]
    (swap! calls conj [url options body])
    {:status 200
     :body (ByteArrayInputStream.
             (.getBytes
               (json/generate-string
                 {:success true :errors []
                  :result {:model (:model body) :truncated false
                           :answers (into {}
                                          (map (fn [[id field]]
                                                 [id (sample-answer field)])
                                               (if clef? (:questions body)
                                                 (:fields body))))
                           :usage {:input_tokens 10 :output_tokens 0}}})
               "UTF-8"))}))

(facts "standalone inference uses piped input, not the question or schema"
  (spit (:key-file settings) "fixture-token\n")
  (reset! calls [])
  (with-redefs [config/get-config command-config
                http/post reply]
    (let [r (chat/run-command chat/request
                            "echo service is down | clef Is this an outage?")]
      (get-in r [:data :answers :decision :noul]) => 0.98
      (string/includes? (str (:value r)) "0.98") => true
      (get-in (first @calls) [2 :state]) => "service is down"
      (get-in (first @calls) [2 :questions :decision :instructions])
      => "Is this an outage?"
      (get-in (first @calls) [1 :headers "cf-aig-gateway-id"]) => "test")
    (let [r (chat/run-command
              chat/request
              "echo total 42 | clairic --fields '{:amount :number}'")]
      (:error r) => nil
      (get-in r [:data :answers :amount :value]) => 42
      (get-in (last @calls) [2 :state]) => "total 42"
      (get-in (last @calls) [2 :fields :amount :type]) => "number")
    (let [r (chat/run-command
              chat/request
              (str "echo total 42 | clairic --fields '{:amount :number}'"
                   " | data $.answers.amount.value"))]
      (:data r) => 42))
  (.delete (java.io.File. (:key-file settings))))

(fact "real HTTP transport preserves Cloudflare envelopes and parsed schemas"
  (let [received (atom [])
        real-post http/post]
    (spit (:key-file settings) "fixture-token")
    (support/with-http-server
      (fn [request]
        (swap! received conj request)
        {:body {:success true :errors []
                :result {:model "clairic" :truncated false
                         :answers {:amount {:type "number" :value nil
                                            :p_null 1 :confidence 0.4}}
                         :usage {:input_tokens 4 :output_tokens 0}}}})
      (fn [base]
        (with-redefs
          [config/get-config command-config
           http/post (fn [url options]
                       (fact url => (str "https://api.cloudflare.com/client/v4/"
                                         "accounts/" (:account-id settings)
                                         "/ai/run/@cf/cloudflare/clairic"))
                       (real-post (str base "/infer") options))]
          (let [r (chat/run-command
                    chat/request
                    "echo no amount | clairic --fields '{:amount :number}'")]
            (:error r) => nil
            (get-in r [:data :answers :amount :value]) => nil
            (get-in r [:data :answers :amount :p_null]) => 1
            (string/includes? (str (:value r)) "null") => true))))
    (get-in (first @received) [:body :fields :amount :type]) => "number"
    (get-in (first @received) [:headers "authorization"])
    => "Bearer fixture-token"
    (.delete (java.io.File. (:key-file settings)))))

(fact "answer previews do not split Unicode surrogate pairs"
  (let [text (str (apply str (repeat 149 "x")) "🙂"
                  (apply str (repeat 400 "x")))]
    (spit (:key-file settings) "fixture-token")
    (with-redefs [config/get-config command-config http/post reply
                  sample-answer (fn [_] {:type "text" :value text})]
      (let [r (chat/run-command
                chat/request
                "echo report | clairic --fields '{:note :text}'")]
        (:error r) => nil
        (get-in r [:data :answers :note :value]) => text
        (every? #(= % (String. (.getBytes ^String % "UTF-8") "UTF-8"))
                (:value r)) => true))
    (.delete (java.io.File. (:key-file settings)))))

(fact "readable answers are bounded without losing data or null metadata"
  (let [text (apply str (repeat 9000 "x"))]
    (spit (:key-file settings) "fixture-token")
    (with-redefs
      [config/get-config command-config
       http/post (fn [& _]
                   {:status 200
                    :body (ByteArrayInputStream.
                            (.getBytes
                              (json/generate-string
                                {:success true :result
                                 {:model "clairic" :truncated true
                                  :answers {:note {:type "text" :value text}}}})
                              "UTF-8"))})]
      (let [r (chat/run-command
                chat/request
                "echo report | clairic --fields '{:note :text}'")]
        (:error r) => nil
        (get-in r [:data :answers :note :value]) => text
        (< (count (str (:value r))) 500) => true
        (string/includes? (str (:value r)) "Long answers shortened") => true
        (string/includes? (str (:value r)) "truncated the input") => true))
    (.delete (java.io.File. (:key-file settings)))))

(facts "invalid inputs cannot reach Cloudflare or evaluate EDN"
  (reset! calls [])
  (with-redefs [config/get-config command-config
                http/post reply]
    (doseq [command ["clef Is this urgent?"
                     "echo hi | clairic --fields '{}'"
                     "echo hi | clairic --fields '#=(+ 1 2)'"
                     "echo hi | clef --questions '{:x {:type :execute}}'"]]
      (fact (some? (:error (chat/run-command chat/request command))) => true))
    @calls => []))

(fact "piped structured, multiline and boolean values do not become questions"
  (spit (:key-file settings) "fixture-token")
  (with-redefs [config/get-config command-config http/post reply]
    (doseq [[command state]
            [["json parse [\"one\",\"two\"] | clef Are both present?"
              "one\ntwo"]
             ["json parse {\"total\":42} | clef Are both present?" {:total 42}]
             ["json parse false | clef Are both present?" "false"]]]
      (let [r (chat/run-command chat/request command)]
        (:error r) => nil
        (get-in (last @calls) [2 :state]) => state
        (get-in (last @calls) [2 :questions :decision :instructions])
        => "Are both present?")))
  (.delete (java.io.File. (:key-file settings))))

(facts "typed questions, direct extraction and images work through real hooks"
  (spit (:key-file settings) "fixture-token\n")
  (reset! calls [])
  (let [downloads (atom [])
        attachment (assoc-in chat/request [:chat-source :raw-event :attachments]
                             [{:url "https://images.example/receipt.png"
                               :content-type "image/png"}])]
    (with-redefs [config/get-config command-config
                  clef/classify! (fn [& _] (throw (Exception. "no auto-PRs")))
                  images/public-addresses
                  (fn [_] (into-array InetAddress
                                      [(InetAddress/getByName "8.8.8.8")]))
                  http/get (fn [url opts]
                             (swap! downloads conj [url opts])
                             {:status 200
                              :body (ByteArrayInputStream. (fixture/png))})
                  http/post reply]
      (let [r (chat/run-command
                chat/request
                (str "echo urgent | clef --questions "
                     "'{:team {:type :choice :instructions \"Which?\" "
                     ":criteria {:ops \"Outages\" :sales \"Plans\"}}}'"))]
        (:error r) => nil
        (get-in r [:data :answers :team :type]) => "choice"
        (get-in (last @calls) [2 :state]) => "urgent")
      (let [r (chat/run-command
                chat/request
                (str "echo urgent | clef --questions "
                     "'{:severity {:type :score :instructions \"How bad?\" "
                     ":criteria [\"low\" \"high\"]}}'"))]
        (get-in r [:data :answers :severity :score]) => 0.5)
      (let [r (chat/run-command
                chat/request
                "clairic --fields '{:amount :number}' total 42")]
        (get-in r [:data :answers :amount :value]) => 42
        (get-in (last @calls) [2 :state]) => "total 42")
      (:error (chat/run-command attachment "clef Is this a receipt?")) => nil
      (get-in (last @calls) [2 :images 0 :content_type]) => "image/png"
      (some? (get-in (last @calls) [2 :images 0 :base64])) => true
      (:error (chat/run-command
                chat/request
                (str "echo https://images.example/receipt.png"
                     " | clairic --fields '{:amount :number}'"))) => nil
      (count @downloads) => 2
      (:error (chat/run-command
                attachment
                (str "clef Is https://images.example/other.png newer than "
                     "https://images.example/receipt.png today?"))) => nil
      (count (get-in (last @calls) [2 :images])) => 2
      (get-in (last @calls) [2 :questions :decision :instructions])
      => "Is Image 2 newer than Image 1 today?"
      (:error (chat/run-command
                chat/request
                (str "json parse {\"image\":"
                     "\"https://images.example/receipt.png?x=1&y=2\"}"
                     " | clef Is this a receipt?"))) => nil
      (get-in (last @calls) [2 :state]) => {:image "Image 1"}
      (first (last @downloads))
      => "https://images.example/receipt.png?x=1&y=2"
      (every? #(nil? (get-in % [1 :headers "Authorization"])) @downloads)
      => true
      (:error (chat/run-command
                chat/request "echo fixture-token | clef Is this a token?"))
      => (midje.sweet/contains "configured credential")))
  (.delete (java.io.File. (:key-file settings))))

(defn quick-answer [field]
  (case (:type field)
    "number" {:type "number" :value 8 :confidence 0.9}
    "estimate" {:type "estimate" :value 8 :median 8 :p10 6 :p90 10}
    "text" {:type "text" :value "TRASH PANDA ONE" :confidence 0.95}
    "list" {:type "list" :value ["Raccoon" "Goose"] :confidence 0.9}))

(fact
  "schema-free requests build typed fields without another model or provider"
  (spit (:key-file settings) "fixture-token")
  (reset! calls [])
  (with-redefs [config/get-config command-config
                http/post reply sample-answer quick-answer]
    (doseq [[request id expected]
            [["count helicopters" :count
              {:type "number" :integer true :instructions "count helicopters"}]
             ["how many helicopters" :count
              {:type "number" :integer true
               :instructions "how many helicopters"}]
             ["HOW MANY helicopters" :count
              {:type "number" :integer true
               :instructions "HOW MANY helicopters"}]
             ["read the pilot callsign" :text
              {:type "text" :instructions "read the pilot callsign"}]
             ["list the pilots" :items
              {:type "list" :items "text" :instructions "list the pilots"}]]]
      (let [r (chat/run-command
                chat/request (str "echo source text | clairic " request))]
        (:error r) => nil
        (get-in (last @calls) [2 :state]) => "source text"
        (get-in (last @calls) [2 :fields]) => {id expected}
        (get-in r [:data :request :fields]) => {id expected}))
    (let [r (chat/run-command
              chat/request
              (str "echo eight helicopters | clairic rate "
                   "how absurd this commute is"))
          field (get-in (last @calls) [2 :fields :rating])]
      (:error r) => nil
      (select-keys field [:type :min :max :step])
      => {:type "estimate" :min 0 :max 10 :step 1}
      (string/includes? (:instructions field)
                        "rate how absurd this commute is") => true
      (string/includes? (str (:value r)) "rating: 8/10") => true
      (get-in r [:data :answers :rating :p10]) => 6)
    (let [r (chat/run-command
              chat/request
              (str "echo eight helicopters | clairic count helicopters"
                   " | data $.answers.count.value"))]
      (:data r) => 8))
  (.delete (java.io.File. (:key-file settings))))

(fact "quick image requests preserve signed URLs and attachment input"
  (spit (:key-file settings) "fixture-token")
  (reset! calls [])
  (let [url "https://images.example/helicopters.png?ex=123&hm=signed&"
        downloads (atom [])
        request (assoc-in chat/request [:chat-source :raw-event :attachments]
                          [{:url url :content-type "image/png"}])]
    (with-redefs [config/get-config command-config
                  http/post reply sample-answer quick-answer
                  images/public-addresses
                  (fn [_] (into-array InetAddress
                                      [(InetAddress/getByName "8.8.8.8")]))
                  http/get (fn [url _]
                             (swap! downloads conj url)
                             {:status 200
                              :body (ByteArrayInputStream. (fixture/png))})]
      (doseq [[context command id]
              [[chat/request (str "clairic count helicopters " url) :count]
               [chat/request (str "echo " url " | clairic count helicopters")
                :count]
               [request "clairic count helicopters" :count]
               [chat/request
                (str "clairic rate how absurd this commute is " url) :rating]]]
        (let [r (chat/run-command context command)]
          (:error r) => nil
          (get-in r [:data :answers id :value]) => 8
          (get-in (last @calls) [2 :images 0 :content_type]) => "image/png"
          (count (get-in (last @calls) [2 :images])) => 1
          (string/includes? (str (:value r)) "zero image tokens") => false))
      @downloads => [url url url url]
      (get-in (first @calls) [2 :state]) => ""
      (get-in (first @calls) [2 :fields :count :instructions])
      => "count helicopters Image 1"))
  (.delete (java.io.File. (:key-file settings))))

(fact "built-in demo checks a known answer without requiring input or a schema"
  (spit (:key-file settings) "fixture-token")
  (reset! calls [])
  (let [attachment (assoc-in chat/request [:chat-source :raw-event :attachments]
                             [{:url "https://images.example/unrelated.png"
                               :content-type "image/png"}])]
    (with-redefs [config/get-config command-config http/post reply
                  images/images (fn [urls]
                                  (fact urls => []) [])
                  sample-answer (fn [_] {:type "number" :value 3})]
      (doseq [command ["clairic demo" "echo unrelated input | clairic demo"]]
        (let [r (chat/run-command attachment command)]
          (:error r) => nil
          (get-in r [:data :answers :count :value]) => 3
          (string/includes? (str (:value r)) "Demo passed") => true
          (get-in r [:data :request :mode]) => "demo"
          (get-in (last @calls) [2 :state]) => "Balloon count: 3.")))
    (doseq [value [7 nil]]
      (with-redefs [config/get-config command-config http/post reply
                    sample-answer (fn [_] {:type "number" :value value})]
        (let [r (chat/run-command chat/request "clairic demo")]
          (string/includes? (str (:value r)) "Demo passed") => false
          (string/includes? (str (:value r)) "expected 3") => true))))
  (.delete (java.io.File. (:key-file settings))))

(fact "image smoke tests warn when the response reports zero image tokens"
  (spit (:key-file settings) "fixture-token")
  (with-redefs [config/get-config command-config
                sample-answer quick-answer
                images/images (constantly [{:content_type "image/png"
                                            :base64 "AA=="}])
                http/post
                (fn [url opts]
                  (let [response (reply url opts)
                        body (json/parse-string (slurp (:body response)) true)
                        body (assoc-in body [:result :usage :image_tokens] 0)]
                    (assoc response :body
                           (ByteArrayInputStream.
                             (.getBytes (json/generate-string body)
                                        "UTF-8")))))]
    (let [r (chat/run-command
              chat/request
              (str "clairic count helicopters "
                   "https://images.example/meme.png"))]
      (:error r) => nil
      (string/includes? (str (:value r)) "zero image tokens") => true
      (get-in r [:data :usage :image_tokens]) => 0))
  (.delete (java.io.File. (:key-file settings))))

(fact "quick requests retain source and credential boundaries"
  (spit (:key-file settings) "fixture-token")
  (reset! calls [])
  (with-redefs [config/get-config command-config http/post reply
                sample-answer quick-answer]
    (let [r (chat/run-command
              chat/request
              "json parse false | clairic rate how surprising this is")]
      (:error r) => nil
      (get-in (last @calls) [2 :state]) => "false")
    (reset! calls [])
    (doseq [command ["echo source | clairic count fixture-token"
                     (str "echo source | clairic count "
                          (string/join (repeat 16384 "x")))]]
      (some? (:error (chat/run-command chat/request command))) => true)
    @calls => [])
  (.delete (java.io.File. (:key-file settings))))

(fact "quick requests without source input fail before inference"
  (reset! calls [])
  (with-redefs [config/get-config command-config http/post reply]
    (doseq [command ["clairic count helicopters" "clairic rate chaos"
                     "clairic read the callsign" "clairic list the pilots"
                     "echo state | clairic count"
                     "echo state | clairic --unknown"]]
      (some? (:error (chat/run-command chat/request command))) => true)
    @calls => []))

(fact
  "malformed schemas produce actionable errors instead of generic failures"
  (with-redefs [config/get-config command-config http/post reply]
    (doseq [schema ["'{:x :number}" ":number" "42" "[]"]]
      (let [r (chat/run-command chat/request
                                (str "echo source | clairic --fields " schema))]
        (some? (:error r)) => true
        (string/includes? (str (:error r))
                          "Structured inference failed") => false))))
