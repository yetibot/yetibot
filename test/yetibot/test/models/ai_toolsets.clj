(ns yetibot.test.models.ai-toolsets
  (:require [midje.sweet :refer [fact facts =>]]
            [yetibot.models.ai-toolsets :as toolsets]))

(def search {:name "search" :kind "search"
             :servers [{:name "web" :tools ["web_search"]}]})
(def config {:servers [{:name "web" :url "http://localhost/mcp"
                        :tools ["web_search" "other"]}
                       {:name "offline" :url "http://localhost/offline"
                        :tools ["read"]}]
             :toolsets [search] :default-toolset "search"})

(defn error? [f] (try (f) false (catch Exception _ true)))

(fact "selection narrows connections and tools without contacting other servers"
  (let [selected (toolsets/select config nil)]
    (get-in selected [:toolset :name]) => "search"
    (mapv :name (:servers selected)) => ["web"]
    (get-in selected [:servers 0 :tools]) => ["web_search"]))

(facts "configuration errors fail closed"
  (doseq [invalid [(assoc config :default-toolset "missing")
                   (assoc config :toolsets [search search])
                   (assoc-in config [:toolsets 0 :servers 0 :name] "missing")
                   (assoc-in config [:toolsets 0 :servers 0 :tools] ["missing"])
                   (assoc-in config [:toolsets 0 :servers 0 :tools] [])
                   (assoc-in config [:toolsets 0 :kind] "unknown")
                   (assoc-in config [:toolsets 0 :servers 0 :tools]
                             ["create_pull_request"])]]
    (fact (error? #(toolsets/select invalid nil)) => true)))

(facts "legacy search and absent config remain compatible, but writes are blocked"
  (toolsets/select nil nil) => nil
  (toolsets/select (dissoc config :toolsets :default-toolset) nil)
  => (dissoc config :toolsets :default-toolset)
  (error? #(toolsets/select nil "missing")) => true
  (error? #(toolsets/select
             {:servers [{:name "writer" :tools ["push_files"]}]} nil))
  => true)
