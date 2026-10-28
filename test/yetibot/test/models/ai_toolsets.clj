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
                   (-> config
                       (assoc-in [:servers 0 :tools] ["get_commit"])
                       (assoc-in [:toolsets 0 :servers 0 :tools] ["get_commit"]))
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

(fact "name-only references inherit server restrictions or live discovery"
  (let [inherited (update-in config [:toolsets 0 :servers 0] dissoc :tools)
        selected (toolsets/select inherited nil)]
    (mapv :name (:servers selected)) => ["web"]
    (get-in selected [:servers 0 :tools]) => ["web_search" "other"]
    (let [automatic (update-in inherited [:servers 0] dissoc :tools)
          selected (toolsets/select automatic nil)]
      (mapv :name (:servers selected)) => ["web"]
      (contains? (first (:servers selected)) :tools) => false)
    (let [automatic (update-in config [:servers 0] dissoc :tools)]
      (get-in (toolsets/select automatic nil) [:servers 0 :tools])
      => ["web_search"])))

(fact "inherited names retain reserved GitHub validation"
  (let [inherited (update-in config [:toolsets 0 :servers 0] dissoc :tools)]
    (doseq [names [["push_files"] ["get_commit"]]]
      (fact (error? #(toolsets/select
                       (assoc-in inherited [:servers 0 :tools] names) nil))
            => true))
    (fact (error? #(toolsets/select
                     (assoc-in inherited [:toolsets 0 :servers 0 :tools] nil)
                     nil)) => true)))
