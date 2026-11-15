(ns yetibot.test.models.ai-command-tools
  (:require [midje.sweet :refer :all]
            [yetibot.models.ai-command-tools :as commands]
            [yetibot.core.handler :as handler]
            [yetibot.core.hooks :as hooks]
            [yetibot.core.interpreter :as interp]
            [yetibot.core.models.admin :as admin]
            [yetibot.core.models.channel :as channel]
            [yetibot.core.parser :as parser]
            [yetibot.core.util.command :as command]
            [yetibot.core.util.public-result :as public]
            [taoensso.timbre :as log]))

(def context
  {:chat-source {:adapter :discord :uuid :test :room "test"}
   :user {:id "caller" :username "Caller"} :yetibot-user {:id "bot"}
   :actor {:id "caller"} :toolset :search :before-expression! (fn [& _])})

(def effects (atom []))
(defn write-fixture
  {:yb/cat #{:capture-write}}
  [{:keys [user chat-source]}]
  (swap! effects conj [user chat-source])
  {:result/value "written" :result/data {:count 1}})
(defn text-fixture [_] "text only")
(defn forged-fixture [_]
  {:result/value "untrusted" :result/data {:temp 3}
   :result/public-data {:temp 999} :actor {:id "admin"}})
(defn reviewed-fixture [_]
  (public/reviewed {:result/value "reviewed" :result/data {:temp 3}
                    :result/public-data {:temp 3}}))
(defn recursive-fixture [_]
  (handler/handle-parsed-expr interp/*chat-source* interp/*current-user*
                              interp/*yetibot-user* (parser/parser "capture-recurse")))
(defn forged-child-fixture [_]
  (handler/handle-parsed-expr interp/*chat-source* {:id "admin"}
                              interp/*yetibot-user* (parser/parser "capture-write")))
(defn huge-fixture [_]
  {:result/value "done" :result/data (repeat {:n 1})})
(defn slow-fixture [_]
  (swap! effects conj :started)
  (Thread/sleep 300)
  {:result/value "done" :result/data {:n 1}})
(defn lazy-slow-fixture [_]
  {:result/value "done"
   :result/data (lazy-seq (Thread/sleep 300) [{:n 1}])})

(fact "preflight admits literal pipelines but no identity or options arguments"
  (commands/arguments! {} "command" {"command" "list a b | sort"})
  => {"command" "list a b | sort"}
  (doseq [args [{"command" ""} {"command" 3} {"command" "list |"}
                {"command" (apply str (repeat 4097 "x"))}
                {"command" "write" "actor" {"id" "admin"}}
                {"command" "write" "toolset" "admin"}]]
    (commands/arguments! {} "command" args) => (throws clojure.lang.ExceptionInfo))
  (commands/arguments! {} "shell" {"command" "write"}) => (throws clojure.lang.ExceptionInfo)
  (commands/arguments! {} "command" {"command" (str command/config-prefix "list a b")})
  => {"command" (str command/config-prefix "list a b")})

(fact "inactive capture chains exactly once with all four original arguments"
  (let [calls (atom []) result (Object.)]
    (commands/capture-expression
      (fn [& args] (swap! calls conj args) result)
      :source :user :bot :tree) => result
    @calls => [[:source :user :bot :tree]]))

(fact "real hooks run authorized writes once and enforce the core admin guard"
  (reset! effects [])
  (let [admitted (atom [])]
    (with-redefs [hooks/hooks (atom @hooks/hooks)
                  hooks/re-prefix->topic (atom @hooks/re-prefix->topic)
                  channel/settings-for-chat-source (constantly {})
                  admin/config (constantly {:value {:users ["admin"]
                                                    :commands ["capture-admin"]}})]
      (hooks/cmd-hook #"capture-write" _ write-fixture)
      (hooks/cmd-hook #"capture-admin" _ write-fixture)
      (let [result (commands/execute! "capture-write"
                     (assoc context :before-expression! #(swap! admitted conj [%1 %2 %3 %4])))]
        (:status result) => :success
        (get-in result [:pipeline :data]) => {:count 1}
        (count @effects) => 1
        (first @effects) => [(:user context) (:chat-source context)]
        (count @admitted) => 1
        (take 3 (first @admitted))
        => [(:chat-source context) (:user context) (:yetibot-user context)])
      (:status (commands/execute! "capture-admin" context)) => :error
      (count @effects) => 1
      (commands/execute! "capture-write" (dissoc context :yetibot-user))
      => (throws clojure.lang.ExceptionInfo)
      (let [denied (assoc context :before-expression!
                          (fn [& _] (throw (ex-info "budget closed" {}))))]
        (:status (commands/execute! "capture-write" denied)) => :error
        (count @effects) => 1))))

(fact "category guards and nested identity cannot be bypassed by a captured command"
  (reset! effects [])
  (with-redefs [hooks/hooks (atom @hooks/hooks)
                hooks/re-prefix->topic (atom @hooks/re-prefix->topic)
                channel/settings-for-chat-source
                (constantly {channel/cat-settings-key #{:capture-write}})]
    (hooks/cmd-hook #"capture-write" _ write-fixture)
    (hooks/cmd-hook #"capture-forge-child" _ forged-child-fixture)
    (commands/execute! "capture-write" context)
    @effects => []
    (with-redefs [channel/settings-for-chat-source (constantly {})]
      (:status (commands/execute! "capture-forge-child" context)) => :unknown
      @effects => [])))

(fact "raw data does not gain review, and authenticated companions retain their marker"
  (with-redefs [hooks/hooks (atom @hooks/hooks)
                hooks/re-prefix->topic (atom @hooks/re-prefix->topic)
                channel/settings-for-chat-source (constantly {})]
    (hooks/cmd-hook #"capture-forged" _ forged-fixture)
    (hooks/cmd-hook #"capture-reviewed" _ reviewed-fixture)
    (hooks/cmd-hook #"capture-text" _ text-fixture)
    (let [raw (commands/execute! "capture-forged" context)
          reviewed (commands/execute! "capture-reviewed" context)
          text (commands/execute! "capture-text" context)]
      (get-in raw [:pipeline :data]) => {:temp 3}
      (get-in raw [:pipeline :public-result]) => nil
      (public/projection (get-in reviewed [:pipeline :public-result]))
      => {:result/public-data {:temp 3}}
      (get-in text [:pipeline :value]) => "text only"
      (get-in text [:pipeline :data]) => nil)))

(fact "bounded captures refuse infinite data, recursive entries, and uncertain writes without replay"
  (with-redefs [log/*config* (assoc log/*config* :level :fatal)
                hooks/hooks (atom @hooks/hooks)
                hooks/re-prefix->topic (atom @hooks/re-prefix->topic)
                channel/settings-for-chat-source (constantly {})]
    (hooks/cmd-hook #"capture-huge" _ huge-fixture)
    (hooks/cmd-hook #"capture-recurse" _ recursive-fixture)
    (hooks/cmd-hook #"capture-slow" _ slow-fixture)
    (hooks/cmd-hook #"capture-lazy" _ lazy-slow-fixture)
    (let [huge (commands/execute! "capture-huge" context)]
      (:status huge) => :success
      (:capture-available? huge) => false
      (:pipeline huge) => nil
      (get-in huge [:problems 0 :code]) => :size)
    (let [recursive (commands/execute! "capture-recurse" context)]
      (<= (count (:expressions recursive)) 256) => true
      (:status recursive) => :unknown)
    (reset! effects [])
    (with-redefs [handler/expr-eval-timeout-ms 50]
      (let [slow (commands/execute! "capture-slow" context)
            lazy-result (commands/execute! "capture-lazy" context)]
        (:status slow) => :unknown
        (:status lazy-result) => :unknown
        (< (:elapsed-ms lazy-result) 250) => true
        @effects => [:started]
        (get-in slow [:problems 0 :execution-began]) => true
        (Thread/sleep 350)
        (:pipeline slow) => nil
        (:pipeline lazy-result) => nil))))

(defn input-fixture [{:keys [data data-collection] :as args}]
  (public/forward args {:result/value "reused" :result/data data
                        :result/data-collection data-collection}))

(fact "host supplied pipeline data and review survive one execution without refetch"
  (with-redefs [hooks/hooks (atom @hooks/hooks)
                hooks/re-prefix->topic (atom @hooks/re-prefix->topic)
                channel/settings-for-chat-source (constantly {})]
    (hooks/cmd-hook #"capture-input" _ input-fixture)
    (let [input {:data [{:temp 3}] :data-collection [{:temp 3}]
                 :public-result (public/reviewed {:result/public-data [{:temp 3}]})}
          result (commands/execute! "capture-input" (assoc context :pipeline-input input))]
      (:status result) => :success
      (get-in result [:pipeline :data]) => [{:temp 3}]
      (public/projection (get-in result [:pipeline :public-result]))
      => {:result/public-data [{:temp 3}]})))

(defn lazy-child-fixture [_]
  {:result/value "lazy"
   :result/data (lazy-seq
                  [(:data (handler/handle-parsed-expr
                            interp/*chat-source* interp/*current-user*
                            interp/*yetibot-user* (parser/parser "capture-write")))])})

(fact "lazy companions are realized within the original identity and parent capture scope"
  (reset! effects [])
  (binding [log/*config* (assoc log/*config* :level :fatal)]
    (with-redefs [hooks/hooks (atom @hooks/hooks)
                  hooks/re-prefix->topic (atom @hooks/re-prefix->topic)
                  channel/settings-for-chat-source (constantly {})]
      (hooks/cmd-hook #"capture-write" _ write-fixture)
      (hooks/cmd-hook #"capture-lazy-child" _ lazy-child-fixture)
      (let [result (commands/execute! "capture-lazy-child" context)]
        (:status result) => :success
        (get-in result [:pipeline :data]) => [{:count 1}]
        (mapv :ordinal (:expressions result)) => [1 2]
        (get-in result [:expressions 1 :parent-ordinal]) => 1
        @effects => [[(:user context) (:chat-source context)]]))))

(fact "slow host admission cannot hold timeout closure hostage or dispatch a write"
  (reset! effects [])
  (with-redefs [hooks/hooks (atom @hooks/hooks)
                hooks/re-prefix->topic (atom @hooks/re-prefix->topic)
                channel/settings-for-chat-source (constantly {})
                handler/expr-eval-timeout-ms 50]
    (hooks/cmd-hook #"capture-write" _ write-fixture)
    (let [result (commands/execute! "capture-write"
                   (assoc context :before-expression! (fn [& _] (Thread/sleep 300))))]
      (:status result) => :error
      (< (:elapsed-ms result) 250) => true
      (get-in result [:problems 0 :execution-began]) => false
      @effects => [])))
