(ns yetibot.test.models.ai-server-scope
  (:require [midje.sweet :refer [fact =>]]
            [yetibot.api.ai :as ai]
            [yetibot.api.mcp :as mcp]
            [yetibot.models.ai-tools :as tools]
            [yetibot.models.ai-toolsets :as toolsets]
            [yetibot.test.support.ai-execution :as execution]
            [yetibot.models.ai-tool-policy :as policy]))

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

(def provider {:name "local" :url "http://localhost/v1" :model "chat"})

(fact "default catalog filters adapter-only servers before connection"
  (doseq [[kind expected] [[:discord ["web" "alpaca"]] [:slack ["web"]]]]
    (let [request (-> execution/request
                      (assoc :user {:id "reader" :username "reader"})
                      (assoc-in [:chat-source :adapter] kind)
                      (assoc-in [:chat-source :raw-event :user] "reader")
                      (assoc-in [:chat-source :raw-event :author] {:id "reader"})
                      (assoc-in [:chat-source :raw-event :channel-id] "C1")
                      (assoc-in [:chat-source :raw-event :id] "message"))
          connected (atom nil)]
      (with-redefs [mcp/config (constantly config)
                    ai/config (constantly {:providers [provider]})
                    mcp/with-tools (fn [cfg f] (reset! connected cfg) (f []))
                    ai/chat (fn [& _] {:choices [{:message {:role "assistant"
                                                           :content "No tool needed."}}]})]
        (execution/in-registered-event request "ai hello"
          (fn [event]
            (:result/value (tools/complete provider "chat" [] {:actor (policy/actor event)}))
            => "No tool needed."))
        (mapv :name (:servers @connected)) => expected
        (mapv :name (:redaction-servers @connected)) => ["web" "alpaca" "private"]))))

(fact "public visibility is not a nil or copied actor execution grant"
  (let [connections (atom 0)]
    (with-redefs [mcp/config (constantly config)
                  ai/config (constantly {:providers [provider]})
                  mcp/with-tools (fn [& _] (swap! connections inc))]
      (doseq [actor [nil {:adapter "discord" :user "reader"}]]
        (try (tools/complete provider "chat" [] {:actor actor}) false
             (catch Exception _ true)) => true)
      @connections => 0)))

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
