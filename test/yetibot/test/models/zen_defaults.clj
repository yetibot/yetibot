(ns yetibot.test.models.zen-defaults
  (:require [cheshire.core :as json]
            [clj-http.client :as http]
            [midje.sweet :refer [fact facts =>]]
            [yetibot.api.ai :as ai]
            [yetibot.api.opencode :as zen])
  (:import [java.io ByteArrayInputStream]))

(def provider {:name "zen" :url "https://opencode.ai/inference/openai/v1"
               :key "fixture-secret"})
(def free-model
  {:id "free-a" :cost {:text {:input {:price 0 :cache {:read 0 :write 0}}
                             :output {:price 0}}}})
(defn response [models]
  {:status 200 :body (ByteArrayInputStream.
                      (.getBytes (json/generate-string {:data models}) "UTF-8"))})

(fact "free means explicit zero input/output and no unknown or paid charges"
  (zen/free-model? free-model) => true
  (zen/free-model? (assoc-in free-model [:cost :text :input :cache]
                             {:read {:price 0}})) => true
  (doseq [model [(dissoc free-model :cost)
                 (assoc-in free-model [:cost :text :input :price] "0")
                 (assoc-in free-model [:cost :text :input :price] 0.01)
                 (update-in free-model [:cost :text :output] dissoc :price)
                 (assoc-in free-model [:cost :text :input :cache :write] 1)
                 (assoc-in free-model [:cost :text :input :cache :write]
                           {:price 1})
                 (assoc-in free-model [:cost :image] {:price 0})
                 (assoc-in free-model [:cost :text :output :unknown] 0)]]
    (fact (zen/free-model? model) => false)))

(fact "configured Zen models win without free discovery or silent rerouting"
  (let [configured (assoc provider :model "gpt-5.4-mini")]
    (with-redefs [http/get (fn [& _] (throw (AssertionError. "No catalog IO")))]
      (ai/default-model configured) => "gpt-5.4-mini"
      (try (ai/default-model configured (constantly false)) :unsafe
           (catch Exception _ :closed)) => :closed)))

(fact "defaults without a configured model follow fresh free offerings"
  (let [catalog (atom [free-model]) calls (atom 0)]
    (with-redefs [http/get (fn [url options]
                            (swap! calls inc)
                            (fact url => "https://opencode.ai/inference/v1/models")
                            (fact (:redirect-strategy options) => :none)
                            (response @catalog))]
      (ai/default-model provider) => "free-a"
      (reset! catalog [(assoc free-model :id "free-b")])
      (ai/default-model provider) => "free-b"
      @calls => 2
      (reset! catalog [(assoc-in free-model [:cost :text :output :price] 1)])
      (try (ai/default-model provider) :unsafe
           (catch Exception _ :closed)) => :closed)))

(fact "free runtime preferences still revalidate instead of becoming paid defaults"
  (let [catalog (atom [free-model])
        runtime (assoc provider :model "free-a" :ai/free-default true)]
    (with-redefs [http/get (fn [& _] (response @catalog))]
      (ai/default-model runtime) => "free-a"
      (reset! catalog [(assoc free-model :id "free-b")])
      (ai/default-model runtime) => "free-b"
      (reset! catalog [(assoc-in free-model [:cost :text :output :price] 1)])
      (try (ai/default-model runtime) :unsafe
           (catch Exception _ :closed)) => :closed)))

(fact "selection honors candidate policy and runtime preference stays bounded"
  (with-redefs [http/get (fn [& _]
                          (response [free-model (assoc free-model :id "free-b")]))]
    (ai/default-model provider #(= % "free-b")) => "free-b"
    (zen/set-default! provider "free-b" (constantly true))
    => {:provider "zen" :model "free-b" :runtime_only true :verified_free true}
    (ai/default-model provider) => "free-b"
    (try (zen/set-default! provider "paid-old-default" (constantly true))
         :unsafe (catch Exception _ :closed)) => :closed
    (try (ai/default-model provider (constantly false)) :unsafe
         (catch Exception _ :closed)) => :closed))

(fact "untrusted destinations and failed catalogs fail before inference"
  (let [gets (atom 0)]
    (with-redefs [http/get (fn [& _] (swap! gets inc)
                            {:status 403 :body (ByteArrayInputStream. (byte-array 0))})]
      (try (ai/default-model (assoc provider :url "https://evil.example/v1"))
           :unsafe (catch Exception _ :closed)) => :closed
      @gets => 0
      (try (ai/default-model provider) :unsafe
           (catch Exception e (.getMessage e)))
      => "Verified-free Zen models are unavailable. No paid fallback was used."
      @gets => 1)))

(fact "oversized or duplicate-identity catalogs cannot establish free eligibility"
  (doseq [body [(apply str (repeat 524289 "x"))
                (json/generate-string {:data [free-model free-model]})]]
    (with-redefs [http/get (fn [& _]
                            {:status 200 :body
                             (ByteArrayInputStream. (.getBytes body "UTF-8"))})]
      (fact (try (ai/default-model provider) :unsafe
                 (catch Exception _ :closed)) => :closed))))

(fact "non-Zen defaults do not discover or mutate anything"
  (with-redefs [http/get (fn [& _] (throw (AssertionError. "No IO")))]
    (ai/default-model {:name "local" :model "original"}) => "original"))
