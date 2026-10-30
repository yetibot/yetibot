(ns yetibot.test.integration.cloudflare-models
  (:require [cheshire.core :as json]
            [clj-http.client :as http]
            [clojure.string :as string]
            [midje.sweet :refer [fact facts =>]]
            [yetibot.api.ai :as ai]
            [yetibot.api.cloudflare :as cf]
            [yetibot.commands.cf]
            [yetibot.core.commands.collections]
            [yetibot.models.ai-model-tools :as models]
            [yetibot.test.support.ai-execution :as execution])
  (:import [java.io ByteArrayInputStream]))

;; Reduced official public-docs record shape; not a live pricing quote.
(def records
  (json/parse-string (slurp "test/fixtures/cloudflare-models.json") true))
(def provider
  {:name "cloudflare" :key "fixture-key"
   :url (str "https://api.cloudflare.com/client/v4/accounts/"
             (apply str (repeat 32 "a")) "/ai/v1")})
(def settings {:providers [provider]})
(defn response [records]
  {:status 200
   :body (ByteArrayInputStream.
           (.getBytes (json/generate-string
                        {:success true :result records
                         :result_info {:total_pages 1}}) "UTF-8"))})
(defn fixture-catalog []
  (with-redefs [http/get (fn [& _] (response records))]
    (cf/catalog provider)))
(defn byte-count [value]
  (alength (.getBytes (json/generate-string value) "UTF-8")))

(facts "catalog metadata preserves provider values, units and evidence"
  (let [catalog (fixture-catalog)
        by-id (into {} (map (juxt :id identity) (:models catalog)))
        llama (by-id "@cf/meta/llama-3.1-8b-instruct-fp8")
        clef (by-id "@cf/cloudflare/clef-flash")
        future (by-id "@cf/vendor/future-model")]
    (count by-id) => 3
    (:catalog-id llama) => "fixture-catalog-id"
    (:description llama) => (:description (first records))
    (:task llama) => {:id "text-task" :name "Text Generation"}
    (:metadata-source llama) => "ai/models/search"
    (:created_at llama) => "2024-07-25 17:28:43.328"
    (:source llama) => 1
    (get-in llama [:limits :context_window])
    => {:value "32000" :unit "tokens" :source "properties.context_window"}
    (get-in llama [:schema-summary :token-parameters :max_tokens])
    => {:type "integer" :default 256
        :description "Maximum tokens to generate" :unit "tokens"
        :source "schema.input.max_tokens"}
    ;; A default is not an output limit. No inferred false or free pricing.
    (get-in llama [:limits :max_output_tokens]) => nil
    (:function-calling llama)
    => {:status :advertised :source "schema.output.tool_calls"}
    (:function-calling clef)
    => {:status :unknown :source "ai/models/search"}
    (:function-calling future)
    => {:status :reported :supported false
        :source "properties.function_calling"}
    (:deprecated future) => false
    (:deprecation_date future) => nil
    (contains? future :deprecation_date) => true
    (:interface clef) => :decision
    (:interface future) => :chat
    (->> (:properties llama) (filter #(= "price" (:property_id %)))
         first :value)
    => [{:unit "per M input tokens" :price 0.152 :currency "USD"}
        {:unit "per M output tokens" :price 0.287 :currency "USD"}]
    (:schema llama) => nil))

(fact "projection redacts before shortening and drops unknown remote fields"
  (let [record (assoc (first records)
                     :description (str (string/join (repeat 1995 "x"))
                                       "fixture-key suffix")
                     :headers {:authorization "private"}
                     :properties [{:property_id "unknown" :value "private"}
                                  {:property_id "price"
                                   :value [{:price 1 :unit "fixture unit"
                                            :authorization "private"
                                            :key "private"}]}])]
    (with-redefs [http/get (fn [& _] (response [record]))]
      (let [model (first (:models (cf/catalog provider)))]
        (string/includes? (:description model) "fixture") => false
        (string/includes? (:description model) "[reda") => true
        (:metadata-truncated model) => true
        (string/includes? (str model) "private") => false
        (:headers model) => nil
        (count (:properties model)) => 1
        (<= (byte-count model) 8000) => true))))

(fact "real cf models random data-show keeps the selected structured record"
  (let [catalog (fixture-catalog)
        selected (first (:models catalog))]
    (with-redefs [ai/config (constantly settings)
                  cf/catalog (constantly catalog)
                  clojure.core/rand-int (constantly 0)
                  ai/complete (fn [& _] (throw (AssertionError. "inference")))]
      (let [listing (execution/run-command execution/request "cf models")
            picked (execution/run-command execution/request
                     "cf models | random")
            shown (execution/run-command execution/request
                    "cf models | random | data show")]
        (count (:value listing)) => 3
        (:data-collection listing) => (:models catalog)
        (:data picked) => selected
        (:value picked) => (str (:id selected) " [Text Generation; decision]")
        (string/includes? (str (:value shown)) "Fixture decision model")
        => true
        (string/includes? (str (:value shown)) "context_window") => true))))

(fact "native pages keep prices and rich task data but redact credentials"
  (let [catalog (update (fixture-catalog) :models
                        (fn [entries]
                          (mapv #(assoc % :description "fixture-key details"
                                         :unknown "private remote field")
                                entries)))
        config {:servers []}]
    (execution/in-event execution/request "cf models"
      (fn [request]
        (with-redefs [ai/config (constantly settings)
                      cf/catalog (constantly catalog)]
          (let [page (:structuredContent
                       (models/call! config "cf" {"action" "models"}
                                     (:chat-source request)))
                llama (second (:models page))]
            (:description llama) => "[redacted] details"
            (get-in llama [:task :name]) => "Text Generation"
            (:unknown llama) => nil
            (get-in llama [:limits :context_window :value]) => "32000"
            (some #(= "price" (:property_id %)) (:properties llama)) => true
            (< (byte-count page) 12000) => true))))))

(fact "native model pages preserve metadata and byte-bounded cursor linkage"
  (let [catalog (fixture-catalog)
        rich (first (filter #(= :chat (:interface %)) (:models catalog)))
        ;; Multibyte data exercises byte, not just character, limits.
        oversized (merge rich
                         {:description (apply str (repeat 4000 "界"))
                          :task {:name (apply str (repeat 1800 "界"))}
                          :license (apply str (repeat 1800 "界"))})
        catalog (assoc catalog :models
                       (mapv #(assoc oversized :id (format "@cf/test/m%02d" %))
                             (range 8)))
        config {:servers [] :toolset {:name "test" :providers
                                     [{:name "cloudflare"}]}}]
    (execution/in-event execution/request "cf models"
      (fn [request]
        (with-redefs [ai/config (constantly settings)
                      cf/catalog (constantly catalog)]
          (loop [after nil seen []]
            (let [result (models/call!
                           config "cf"
                           (cond-> {"action" "models" "limit" 20}
                             after (assoc "after" after))
                           (:chat-source request))
                  page (:structuredContent result)
                  entries (:models page)
                  ids (mapv :id entries)
                  seen (into seen ids)]
              (fact (:isError result) => nil)
              (fact (< (byte-count page) 12000) => true)
              (fact (boolean (seq entries)) => true)
              (fact (every? :metadata-truncated entries) => true)
              (fact (get-in (first entries) [:function-calling :status])
                    => "advertised")
              (fact (get-in (first entries) [:limits :context_window :value])
                    => "32000")
              (if (:has_more page)
                (do (fact (:next_after page) => (last ids))
                    (recur (:next_after page) seen))
                (fact seen => (mapv :id (:models catalog)))))))))))

(fact "category browsing is compact and filtering keeps full model records"
  (let [extra [(assoc (first records) :id "image-record"
                     :name "@cf/test/image" :description "Fixture image"
                     :task {:id "image-task" :name "Text-to-Image"})
               (assoc (first records) :id "unknown-record"
                      :name "@cf/test/unknown" :task nil)]
        catalog (with-redefs [http/get
                              (fn [& _] (response (into records extra)))]
                  (cf/catalog provider))
        image (first (filter #(= "@cf/test/image" (:id %)) (:models catalog)))
        text-models (:models (fixture-catalog))
        paid (atom 0) posts (atom 0)]
    (with-redefs [ai/config (constantly settings)
                  cf/catalog (constantly catalog)
                  http/post (fn [& _]
                              (swap! posts inc)
                              (throw (AssertionError. "Unexpected POST")))
                  models/call! (fn [& _]
                                 (swap! paid inc)
                                 {:isError true})]
      (let [summary (execution/run-command execution/request
                      "cf models --categories")
            selected (execution/run-command execution/request
                       "cf models --category text-to-image")
            shown (execution/run-command execution/request
                    "cf models --category text-to-image | random | data show")
            unknown (execution/run-command execution/request
                      "cf models --category uncategorized")
            all (execution/run-command execution/request "cf models")
            text (execution/run-command execution/request
                   "cf models --category TEXT-GENERATION")
            llama (execution/run-command execution/request
                    (str "cf models --category text-generation"
                         " | grep llama | head | data show"))]
        (:value summary)
        => ["text-generation: 3 models (Text Generation)"
            "text-to-image: 1 model (Text-to-Image)"
            "uncategorized: 1 model (Uncategorized)"]
        (:data-collection summary)
        => [{:id "text-generation" :name "Text Generation" :count 3}
            {:id "text-to-image" :name "Text-to-Image" :count 1}
            {:id "uncategorized" :name "Uncategorized" :count 1}]
        (:data-collection selected) => [image]
        (:value selected) => ["@cf/test/image [Text-to-Image; model-specific]"]
        (string/includes? (str (:value shown)) "Fixture image") => true
        (string/includes? (str (:value shown)) "context_window") => true
        (mapv :id (:data-collection unknown)) => ["@cf/test/unknown"]
        (:data-collection all) => (:models catalog)
        (:data-collection text) => text-models
        (string/includes? (str (:value llama)) "Llama 3.1 8B quantized") => true
        (string/includes? (str (:value llama)) "Fixture decision model")
        => false
        @paid => 0
        @posts => 0))))

(fact "catalog options never fall through to paid inference"
  (let [catalog (fixture-catalog)
        config-reads (atom 0) catalog-reads (atom 0) paid (atom 0)
        posts (atom 0)]
    (with-redefs [ai/config (fn [] (swap! config-reads inc) settings)
                  cf/catalog (fn [_] (swap! catalog-reads inc) catalog)
                  http/post (fn [& _]
                              (swap! posts inc)
                              (throw (AssertionError. "Unexpected POST")))
                  models/call! (fn [& _]
                                 (swap! paid inc)
                                 {:isError true})]
      (doseq [options ["--category" "--wat" "--categories extra"
                       "--category text-generation --categories"
                       "--category --categories" "unexpected"]]
        (let [result (execution/run-command execution/request
                       (str "cf models " options))]
          (fact (get-in result [:data :isError]) => true
                (string/includes? (str (:value result)) "Usage: cf models")
                => true)))
      (fact @config-reads => 0
            @catalog-reads => 0
            @paid => 0)
      (let [result (execution/run-command execution/request
                     "cf models --category missing-private-value | count")]
        (fact (get-in result [:data :isError]) => true
              (string/includes? (str (:value result))
                                "Use cf models --categories") => true
              (string/includes? (str (:value result))
                                "missing-private-value") => false
              @catalog-reads => 1
              @paid => 0
              @posts => 0)))))
