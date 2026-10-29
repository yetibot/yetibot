(ns yetibot.test.integration.ai-piped-input
  (:require [cheshire.core :as json]
            [clj-http.client :as http]
            [clojure.string :as string]
            [midje.sweet :refer [fact facts =>]]
            [yetibot.api.ai :as api]
            [yetibot.api.cloudflare :as cf]
            [yetibot.api.mcp :as mcp]
            [yetibot.commands.ai :as ai]
            [yetibot.commands.cf]
            [yetibot.commands.grok]
            [yetibot.core.commands.grok :as legacy]
            [yetibot.core.config :as config]
            [yetibot.core.commands.collections]
            [yetibot.core.commands.alias :as aliases]
            [yetibot.core.hooks :as hooks]
            [yetibot.core.models.history :as history]
            [yetibot.models.ai-context :as context]
            [yetibot.models.ai-input :as input]
            [yetibot.models.ai-native-tools :as native]
            [yetibot.models.ai-tools :as tools]
            [yetibot.test.support.ai-execution :as execution]))

(def provider
  {:name "cloudflare" :key "fixture-key" :model "parent"
   :url (str "https://api.cloudflare.com/client/v4/accounts/"
             (apply str (repeat 32 "a")) "/ai/v1")})
(def record
  {:id "@cf/fixture/model" :description "Full model description"
   :interface :chat :deprecated false :replacement nil
   :properties [{:property_id "price"
                 :value [{:price 0.125M :currency "USD"
                          :unit "per million input tokens"}]}]
   :limits {:context_window {:value "32000" :unit "tokens"}}})

(defn piped-data [messages]
  (some (fn [{:keys [role content]}]
          (when (= role "user")
            (let [value (try (json/parse-string content true)
                             (catch Exception _ nil))]
              (when (= "yetibot-pipeline" (:source value)) value))))
        messages))

(defn run-pipe [records command]
  (let [calls (atom [])]
    (with-redefs [api/config (constantly {:providers [provider]})
                  cf/catalog (constantly {:models records})
                  mcp/config (constantly {:servers []})
                  native/tools (fn [& _] [])
                  context/messages (fn [_ prompt]
                                     [{:role "user" :content prompt}])
                  clojure.core/rand-int (constantly 0)
                  api/complete (fn [p model messages & _]
                                 (swap! calls conj [p model messages])
                                 {:result/value "Formatted fixture"})]
      {:result (execution/run-command execution/request command)
       :calls @calls})))

(facts "real catalog random pipe gives AI the complete selected record"
  (doseq [flags ["--no-tools " ""]]
    (let [{:keys [calls result]}
          (run-pipe [record (assoc record :id "other")]
                    (str "cf models | random | ai --provider cloudflare "
                         flags "Format every returned field"))
          messages (nth (first calls) 2)
          input (piped-data messages)]
      (:value result) => "Formatted fixture"
      (count calls) => 1
      (:untrusted input) => true
      (:data input) => (json/parse-string (json/generate-string record) true)
      (:content (last messages)) => "Format every returned field")))

(fact "oversized hidden data fails before inference without core decoration"
  (let [{:keys [calls result]}
        (run-pipe [(assoc record :description (string/join (repeat 70000 "x")))]
                  (str "cf models | random | ai --provider cloudflare "
                       "--no-tools Format it"))]
    (count calls) => 0
    (:error result) => nil
    (:value result)
    => (str "Piped AI input is unsupported or too large. "
            "Select less data and try again.")))

(facts "aligned head selections and multiline data-show reach AI as data"
  (let [records [record (assoc record :id "second")
                 (assoc record :id "not-selected")]
        run (run-pipe records
                      "cf models | head 2 | ai --no-tools Format the records")
        data (:data (piped-data (get-in run [:calls 0 2])))]
    (mapv :id data) => [(:id record) "second"]
    (count data) => 2)
  (let [run (run-pipe [record]
                    "cf models | random | data show | ai --no-tools Format it")
        value (:data (piped-data (get-in run [:calls 0 2])))]
    ;; The data-show step must not lose the record or cause reader/eval.
    (string/includes? (str value) "Full model description") => true
    (string/includes? (str value) "context_window") => true))

(facts "pipe-only inputs cannot become provider flags or execution authority"
  (let [run (run-pipe [record]
                      (str "cf models | random | ai --provider cloudflare "
                           "--no-tools"))]
    (get-in run [:calls 0 0 :name]) => "cloudflare"
    (:data (piped-data (get-in run [:calls 0 2])))
    => (json/parse-string (json/generate-string record) true)
    (get-in run [:calls 0 2 2 :content])
    => "Respond to the supplied Yetibot input.")
  (let [run (run-pipe [(assoc record :description
                             (string/join (repeat 70000 "private")))]
                      (str "cf models | random | ai --no-tools Format | "
                           "ai --no-tools Must not run"))]
    (:calls run) => []
    (get-in run [:result :value]) => input/error-message))

(fact "an appended display cannot supply model/provider options"
  (with-redefs [context/messages (fn [_ prompt]
                                  [{:role "user" :content prompt}])]
    (let [raw "--provider unconfigured --model malicious"
          prepared (ai/prepare-prompt
                     {:match (str "--no-tools " raw) :raw raw :data raw}
                     nil ai/usage (constantly {:providers [provider]}))]
      (get-in prepared [:provider :name]) => "cloudflare"
      (:model prepared) => "parent"
      (:no-tools prepared) => true
      (:prompt prepared) => "Respond to the supplied Yetibot input.")))

(facts "selected key-file is redacted only after preparation, on both paths"
  (doseq [no-tools [true false]]
    (let [file (java.io.File/createTempFile "piped-key-" ".txt")
          secret "SELECTED-FILE-SENTINEL"
          p (-> provider (dissoc :key) (assoc :key-file (.getPath file)))
          state (atom nil) captured (atom nil)]
      (try
        (spit file secret)
        (with-redefs [api/config
                      (constantly {:providers
                                   [p {:name "unselected"
                                       :url "http://unselected.invalid/v1"
                                       :key-file "/must-not-be-opened"}]})
                      context/messages (fn [_ prompt]
                                         [{:role "user" :content prompt}])
                      mcp/config (constantly {:servers []})
                      native/tools (fn [& _] [])
                      api/complete (fn [_ _ messages & _]
                                     (reset! captured messages)
                                     {:result/value "safe"})]
          (let [actual-resolve api/resolve-provider]
            (with-redefs [api/resolve-provider
                          (fn [p] (swap! state (fnil conj []) (:name p))
                            (actual-resolve p))]
              (let [prepared
                    (ai/prepare-prompt
                      {:match (str "--provider cloudflare "
                                   (when no-tools "--no-tools ") "Format")
                       :raw "label" :data {:description secret}})]
                @state => nil
                (:result/value (ai/complete-prompt prepared)) => "safe"
                @state => ["cloudflare"]
                (string/includes? (str @captured) secret) => false
                (get-in (piped-data @captured) [:data :description])
                => "[redacted]"))))
        (finally (.delete file))))))

(fact "piped data does not move credential reads ahead of tool authorization"
  (let [reads (atom 0)
        t {:name "denied" :kind "search" :servers [] :native-tools ["cf"]
           :users ["someone-else"] :adapters ["slack"]
           :providers [{:name "cloudflare" :models ["parent"]}]}]
    (with-redefs [api/config (constantly {:providers [provider]})
                  mcp/config (constantly {:servers [] :toolsets [t]})
                  context/messages (fn [_ prompt]
                                     [{:role "user" :content prompt}])
                  api/resolve-provider (fn [_] (swap! reads inc)
                                         (throw (Exception. "Must not read")))
                  http/post (fn [& _] (throw (AssertionError. "No POST")))]
      (execution/in-event execution/request "ai --toolset denied Format"
        (fn [request]
          (:result/error
            (ai/prompt-cmd (assoc request :match "--toolset denied Format"
                                 :data record :raw "label")))
          => string?))
      @reads => 0)))

(fact "real Grok entrypoint sees supplied metadata, with its provider pinned"
  (let [p (assoc provider :name "grok") calls (atom [])]
    (with-redefs [api/config (constantly {:providers [provider p]})
                  config/get-config (constantly {:value {}})
                  cf/catalog (constantly {:models [record]})
                  mcp/config (constantly {:servers []})
                  legacy/discord? (constantly false)
                  context/messages (fn [_ prompt]
                                     [{:role "user" :content prompt}])
                  api/complete (fn [p _ messages & _]
                                 (swap! calls conj [p messages])
                                 {:result/value "formatted"})]
      (:value (execution/run-command
                execution/request
                "cf models | random | grok --no-tools Format it"))
      => "formatted\n\nSent via parent"
      (get-in @calls [0 0 :name]) => "grok"
      (get-in (piped-data (get-in @calls [0 1])) [:data :description])
      => (:description record))))

(fact "actual native runner sends the piped record over the HTTP boundary"
  (let [posts (atom [])
        t {:name "format" :kind "search" :servers []
           :native-tools ["cf" "util"] :users ["U1"] :adapters ["slack"]
           :providers [{:name "cloudflare" :models ["parent"]}]}]
    (with-redefs [api/config (constantly {:providers [provider]})
                  cf/catalog (constantly {:models [record]})
                  mcp/config (constantly {:servers [] :toolsets [t]})
                  context/messages (fn [_ prompt]
                                     [{:role "user" :content prompt}])
                  http/post (fn [_ options]
                              (swap! posts conj (:form-params options))
                              {:status 200
                               :body {:choices
                                      [{:message
                                        {:role "assistant"
                                         :content "Formatted all fields"}}]}})]
      (:value (execution/run-command
                execution/request "cf models | random | ai Format all fields"))
      => "Formatted all fields"
      (count @posts) => 1
      (:data (piped-data (:messages (first @posts))))
      => (json/parse-string (json/generate-string record) true)
      (set (map #(get-in % [:function :name]) (:tools (first @posts))))
      => #{"yetibot_cf" "yetibot_util"})))

(fact "tool-help pipes preserve the selected full schema, not its short row"
  (let [tool {:id "fixture/describe" :name "describe" :server "fixture"
              :description "Full tool description"
              :inputSchema {:type "object" :required ["input_json"]
                            :properties {:input_json {:type "string"}}}}]
    (with-redefs [tools/tool-catalog
                  (fn [& _] {:tools [tool (assoc tool :id "fixture/other")]})]
      (let [run (run-pipe []
                          "ai tools | head | ai --no-tools Explain the schema")
            data (:data (piped-data (get-in run [:calls 0 2])))]
        (:id data) => "fixture/describe"
        (get-in data [:inputSchema :required]) => ["input_json"]
        (get-in data [:inputSchema :properties :input_json :type])
        => "string"))))

(fact "a real in-memory alias expansion preserves selected contextual data"
  (try
    (hooks/cmd-hook-resolved
      #"piped-gemma-fixture" #".*"
      (#'aliases/build-alias-cmd-fn
        "ai --provider cloudflare --model parent --no-tools"))
    (with-redefs [history/add (fn [& _] nil)]
      (let [run (run-pipe [record]
                          "cf models | random | piped-gemma-fixture Format it")]
        (get-in run [:result :value]) => "Formatted fixture"
        (get-in (piped-data (get-in run [:calls 0 2])) [:data :description])
        => (:description record)))
    (finally (hooks/cmd-unhook "piped-gemma-fixture"))))
