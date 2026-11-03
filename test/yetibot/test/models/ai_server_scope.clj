(ns yetibot.test.models.ai-server-scope
  (:require [midje.sweet :refer [fact =>]]
            [yetibot.api.ai :as ai]
            [yetibot.api.mcp :as mcp]
            [yetibot.models.ai-tools :as tools]
            [yetibot.models.ai-toolsets :as toolsets]))

(def config
  {:default-toolset "search"
   :servers [{:name "web" :url "https://web.test/mcp" :tools ["search"]}
             {:name "alpaca" :command "alpaca-mcp-server"
              :adapters ["discord"] :tools ["get_stock_latest_trade"]}
             {:name "private" :url "https://private.test/mcp"
              :tools ["private_read"]}]
   :toolsets [{:name "search" :kind "search"
               :servers [{:name "web"} {:name "alpaca"}]}
              {:name "markets" :kind "search" :adapters ["discord"]
               :native-tools [] :servers [{:name "alpaca"}]}
              {:name "private" :kind "search" :users ["owner"]
               :adapters ["discord"] :servers [{:name "private"}]}]})

(fact "default catalog filters adapter-only servers before connection"
  (doseq [[actor expected]
          [[{:adapter "discord" :user "reader"} ["web" "alpaca"]]
           [{:adapter "slack" :user "reader"} ["web"]]
           [nil ["web"]]]]
    (let [connected (atom nil)]
      (with-redefs [mcp/config (constantly config)
                    mcp/with-tools (fn [cfg f]
                                     (reset! connected cfg)
                                     (f []))
                    ai/chat (fn [& _]
                              {:choices [{:message {:role "assistant"
                                                    :content "No tool needed."}}]})]
        (:result/value
          (tools/complete {:name "local" :url "http://localhost/v1"}
                          "chat" [{:role "user" :content "Hello"}]
                          {:actor actor})) => "No tool needed."
        (mapv :name (:servers @connected)) => expected
        ;; Credentials from excluded connections still participate in redaction.
        (mapv :name (:redaction-servers @connected))
        => ["web" "alpaca" "private"]))))

(fact "discovery has the same adapter boundary and retains private-tool policy"
  (with-redefs [mcp/config (constantly config)]
    (tools/server-names {:adapter "discord" :user "reader"})
    => ["web" "alpaca"]
    (tools/server-names {:adapter "slack" :user "reader"}) => ["web"]
    (tools/server-names nil) => ["web"]
    (tools/server-names {:adapter "discord" :user "owner"})
    => ["web" "alpaca" "private"]))

(fact "explicit markets still narrows to Alpaca without native tools"
  (let [selected (toolsets/select config "markets")]
    (mapv :name (:servers selected)) => ["alpaca"]
    (get-in selected [:toolset :native-tools]) => []))

(fact "malformed server adapter rules fail closed"
  (doseq [adapters [nil [] ["irc"] "discord" ["discord" "discord"]]]
    (try
      (toolsets/validate! (assoc-in config [:servers 1 :adapters] adapters))
      false
      (catch Exception _ true)) => true))
