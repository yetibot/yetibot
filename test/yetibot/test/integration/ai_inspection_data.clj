(ns yetibot.test.integration.ai-inspection-data
  (:require [cheshire.core :as json]
            [clj-http.client :as http]
            [clojure.string :as str]
            [midje.sweet :refer [fact =>]]
            [yetibot.api.ai :as api]
            [yetibot.api.mcp :as mcp]
            [yetibot.commands.ai :as command]
            [yetibot.commands.zen :as zen-command]
            [yetibot.api.opencode :as zen]
            [yetibot.core.config :as config]
            [yetibot.core.models.admin :as admin]
            [yetibot.core.util.public-result :as public]
            [yetibot.models.ai-tools :as tools]
            [yetibot.test.support.ai-execution :as execution])
  (:import [java.io ByteArrayInputStream]))

(def settings
  {:default-provider "local"
   :providers [{:name "local" :url "http://localhost:11434/v1"
                :model "original" :key "PRIVATE-PROVIDER-KEY"}
               {:name "zen" :url "https://opencode.ai/inference/openai/v1"
                :model "configured" :api "responses"}]})

(defn projection [result]
  (:result/public-data (public/projection result)))

(defn isolated [f]
  (let [before @api/runtime-default get-config config/get-config]
    (try
      (reset! api/runtime-default nil)
      (with-redefs [config/get-config
                    (fn [spec path]
                      (if (= [:ai] path) {:value settings}
                        (get-config spec path)))
                    admin/user-is-admin? (constantly false)
                    api/complete (fn [& _]
                                   (throw (AssertionError. "No inference")))]
        (f))
      (finally (reset! api/runtime-default before)))))

(fact "trusted ordinary users can select paid Zen with a pricing comparison"
  (isolated
    (fn []
      (let [reads (atom 0)]
        (with-redefs [http/get
                      (fn [_ options]
                        (swap! reads inc)
                        (get-in options [:headers "Authorization"]) => nil
                        {:status 200
                         :body (ByteArrayInputStream.
                                 (.getBytes
                                   (json/generate-string
                                     {:data [{:id "paid" :cost
                                              {:text {:input {:price 0.75}
                                                      :output {:price 4.5}}}}
                                             {:id "cheaper" :cost
                                              {:text {:input {:price 0.25}
                                                      :output {:price 1.5}}}}]})
                                   "UTF-8"))})]
          (let [result (execution/run-command execution/request
                         "ai default zen paid")
                data (:data result)]
            (:provider data) => "zen"
            (:model data) => "paid"
            (get-in data [:previous :model]) => "original"
            (get-in data [:pricing :current :input]) => 0.75
            (get-in data [:pricing :current :output]) => 4.5
            (get-in data [:pricing :previous :status]) => "unavailable"
            (str/includes? (:value result) "USD / 1M text tokens") => true
            (:model (api/provider (api/config) nil)) => "paid"
            (:ai/free-default (api/provider (api/config) nil)) => nil
            (:provider (projection (:public-result result))) => "zen")
          (let [result (execution/run-command execution/request
                         "ai default zen cheaper")]
            (get-in result [:data :previous :model]) => "paid"
            (get-in result [:data :pricing :difference :input]) => -0.5M
            (get-in result [:data :pricing :difference :output]) => -3M)
          ;; One public catalog read per change, not one per compared model.
          @reads => 2)))))

(fact "missing prices never block a choice or become a zero-cost claim"
  (isolated
    (fn []
      (with-redefs [http/get (fn [& _] (throw (Exception. "PRIVATE-UPSTREAM")))]
        (let [result (execution/run-command execution/request
                       "ai default zen unknown")]
          (get-in result [:data :model]) => "unknown"
          (get-in result [:data :pricing :current :status]) => "unavailable"
          (get-in result [:data :pricing :difference]) => nil
          (str/includes? (:value result) "PRIVATE") => false)))))

(fact "inspection commands explicitly review only useful safe metadata"
  (isolated
    (fn []
      (with-redefs [mcp/config (constantly {:servers []})
                    api/models
                    (fn [_] ["one" "two"])]
        (let [providers (command/providers-cmd {})
              data (projection providers)]
          (:result/value providers) => ["local" "zen"]
          (mapv :name data) => ["local" "zen"]
          (mapv :default_model data) => ["original" "configured"]
          (mapv :interface data) => ["chat-completions" "responses"]
          (str/includes? (pr-str data) "PRIVATE") => false
          (str/includes? (pr-str data) "localhost") => false)
        (let [result (command/models-cmd {:match ["models" "local"]})]
          (get-in (projection result) ["local" :models])
          => [{:id "one"} {:id "two"}])
        (let [result (execution/run-command execution/request "ai default")]
          (:model (projection (:public-result result))) => "original")
        (let [result (command/toolsets-cmd {})]
          (some? (projection result)) => true)))))

(fact "server data keeps policy scope without connecting or exposing config"
  (isolated
    (fn []
      (let [cfg {:servers
                 [{:name "public" :url "https://PRIVATE.test/mcp"
                   :headers {"Authorization" "Bearer PRIVATE-MCP-KEY"}
                   :tools ["read"]}
                  {:name "private" :command "PRIVATE-COMMAND"}]
                 :toolsets
                 [{:name "open" :kind "search" :native-tools []
                   :servers [{:name "public"}]}
                  {:name "locked" :kind "search" :users ["U2"]
                   :native-tools [] :servers [{:name "private"}]}]}]
        (with-redefs [mcp/config (constantly cfg)
                      mcp/with-tools
                      (fn [& _] (throw (AssertionError. "No discovery")))]
          (execution/in-event execution/request "ai servers"
            (fn [request]
              (let [result (command/servers-cmd request)]
                (:result/value result) => ["public"]
                (projection result)
                => [{:name "public" :kind "mcp" :tool_names ["read"]
                     :selection "allowlist" :discovery "not-checked"}]
                (str/includes? (pr-str result) "PRIVATE") => false))))))))

(fact "Zen discovery reviews model IDs without inheriting raw catalog data"
  (with-redefs [api/config (constantly settings)
                api/models (constantly (with-meta ["free-a" "free-b"]
                              {:PRIVATE "not approved"}))]
    (let [result (zen-command/models-cmd {})]
      (:result/value result) => ["free-a" "free-b"]
      (projection result) => [{:id "free-a"} {:id "free-b"}]
      (:PRIVATE (meta (projection result))) => nil)))

(fact "catalog prices reject invalid or incomplete numbers instead of free"
  (with-redefs [http/get
                (fn [url options]
                  url => "https://opencode.ai/inference/v1/models"
                  (get-in options [:headers "Authorization"]) => nil
                  {:status 200
                   :body (ByteArrayInputStream.
                           (.getBytes
                             (json/generate-string
                               {:data [{:id "zero" :cost
                                        {:text {:input {:price 0}
                                                :output {:price 0}}}}
                                       {:id "negative" :cost
                                        {:text {:input {:price -1}
                                                :output {:price 0}}}}
                                       {:id "missing" :cost
                                        {:text {:input {:price 0}}}}
                                       {:id "string" :cost
                                        {:text {:input {:price "0"}
                                                :output {:price "0"}}}}]})
                             "UTF-8"))})]
    (let [rates (zen/model-prices)]
      (set (keys rates)) => #{"zero"}
      (select-keys (get rates "zero") [:input :output :currency :unit])
      => {:input 0 :output 0 :currency "USD" :unit "1M text tokens"})))

(fact "credential-valued metadata is redacted in text, raw and public views"
  (with-redefs [api/config
                (constantly (assoc-in settings [:providers 0 :model]
                                      "PRIVATE-PROVIDER-KEY"))
                api/models (constantly ["PRIVATE-PROVIDER-KEY"])
                mcp/config (constantly {:servers []})]
    (let [providers (command/providers-cmd {})
          models (command/models-cmd {:match ["models" "local"]})]
      (doseq [result [providers models]]
        (str/includes? (pr-str result) "PRIVATE-PROVIDER-KEY") => false)
      (get-in (projection providers) [0 :default_model]) => "[redacted]")))
