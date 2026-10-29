(ns yetibot.test.models.ai-provider-catalog
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [cheshire.core :as json]
            [clj-http.client :as http]
            [yetibot.api.ai :as ai]
            [yetibot.commands.ai :as commands]
            [yetibot.models.ai-context :as context]
            [yetibot.models.ai-provider-catalog :as catalog]
            [yetibot.models.ai-mcp-catalog :as mcp-catalog]
            [yetibot.observers.ai-mcp-catalog :as observer]
            [yetibot.core.loader :as loader]))

(def providers [{:name "one" :url "https://one.invalid/v1"
                 :key-file "/not-a-real-secret/one" :model "one-default"}
                {:name "two" :url "https://two.invalid/v1"
                 :key-file "/not-a-real-secret/two" :model "two-default"}])
(def config {:providers providers :default-provider "two"})
(def model "@cf/meta/llama-3.1-8b-instruct-fp8")
(defn complete [ids] (with-meta (vec ids) {:complete? true}))
(defn stream-body [body]
  (java.io.ByteArrayInputStream.
    (.getBytes (json/generate-string body) "UTF-8")))
(defn fixture []
  (let [clock (atom 0) jobs (atom []) current (atom config)]
    {:clock clock :jobs jobs :current current
     :cache (catalog/new-cache #(deref clock) #(swap! jobs conj %)
                               #(deref current))}))
(defn drain! [jobs]
  (let [pending @jobs]
    (reset! jobs [])
    (doseq [job pending] (job))))
(defn catalogs [provider]
  (complete (if (= "one" (:name provider)) [model "shared"] ["shared" "two-only"])))
(defn infer [cache cfg id]
  (catalog/infer-provider cache cfg id))
(defn safe-infer [cache cfg id]
  (commands/safely #(infer cache cfg id)))
(defn prepare [text]
  (commands/prepare-prompt {:match text}))

;; Fail loudly if any test accidentally leaves the offline boundary.
(use-fixtures :each
  (fn [f]
    (with-redefs [http/get (fn [& _] (throw (AssertionError. "Unexpected HTTP GET")))
                  http/post (fn [& _] (throw (AssertionError. "Paid POST forbidden")))
                  ai/config (constantly config)
                  ai/resolve-provider (fn [_] (throw (AssertionError. "Unexpected credential read")))
                  context/messages (fn [_ prompt] [{:role "user" :content prompt}])]
      (try (f) (finally (catalog/stop!))))))

(deftest unique-exact-id-and-collisions
  (let [{:keys [cache jobs]} (fixture)]
    (with-redefs [ai/models catalogs catalog/shared-cache cache]
      (is (:result/error (safe-infer cache config model)))
      (catalog/warm! cache)
      (catalog/warm! cache)
      (is (= 2 (count @jobs)))
      (drain! jobs)
      (is (= "one" (infer cache config model)))
      (is (= "one" (get-in (prepare (str "--model " model " Say hello.")) [:provider :name])))
      (is (re-find #"ambiguous" (:result/error (safe-infer cache config "shared"))))
      (doseq [id ["llama" "@cf/meta/llama" "two" "https://evil.invalid/models"]]
        (is (re-find #"No provider advertises" (:result/error (safe-infer cache config id)))))
      (is (empty? @jobs)))))

(deftest explicit-forced-default-and-sole-provider-never-consult-catalog
  (with-redefs [catalog/infer-provider (fn [& _] (throw (AssertionError. "Catalog lookup")))
                ai/models (fn [& _] (throw (AssertionError. "Discovery")))]
    (is (= "two" (get-in (prepare "hello") [:provider :name])))
    (is (= "two-default" (:model (prepare "hello"))))
    (is (= "two" (get-in (prepare (str "--provider two --model " model " hi"))
                                [:provider :name])))
    (is (= "one" (get-in (commands/prepare-prompt
                            {:match (str "--model " model " hi")}
                            "one" commands/usage)
                          [:provider :name])))
    (is (thrown? clojure.lang.ExceptionInfo
                 (commands/prepare-prompt {:match "--provider two hi"}
                                          "one" commands/usage)))
    (with-redefs [ai/config (constantly {:providers [(first providers)]})]
      (is (= "unadvertised" (:model (prepare "--model unadvertised hi")))))))

(deftest prompt-errors-send-zero-inference-and-success-keeps-selected-request
  (let [{:keys [cache jobs]} (fixture) requests (atom [])]
    (with-redefs [catalog/shared-cache cache ai/models catalogs
                  commands/complete-prompt (fn [request]
                                             (swap! requests conj
                                                    [(:name (:provider request))
                                                     (:model request)])
                                             :completed)]
      (doseq [text [(str "--model " model " hi") "--model shared hi"]]
        (is (:result/error (commands/prompt-cmd {:match text}))))
      (is (empty? @requests))
      (catalog/warm! cache)
      (drain! jobs)
      (doseq [text ["--model shared hi" "--model nonexistent hi"]]
        (is (:result/error (commands/prompt-cmd {:match text}))))
      (is (empty? @requests))
      (is (= :completed (commands/prompt-cmd {:match (str "--model " model " hi")})))
      (is (= [["one" model]] @requests)))))

(deftest unknown-failed-incomplete-and-expired-fail-closed
  (let [{:keys [cache jobs clock]} (fixture) calls (atom 0)]
    (with-redefs [ai/models (fn [p]
                             (swap! calls inc)
                             (if (= "one" (:name p)) (complete [model])
                               (throw (Exception. "private-error"))))]
      (catalog/warm! cache)
      (drain! jobs)
      (is (:result/error (safe-infer cache config model)))
      (catalog/warm! cache)
      (is (empty? @jobs))
      (is (= 2 @calls))
      (is (not (.contains (pr-str @(:state cache)) "private-error"))))
    (reset! clock 10001)
    (with-redefs [ai/models (fn [p] (if (= "one" (:name p)) (complete [model]) ["other"]))]
      (catalog/warm! cache)
      (drain! jobs)
      (is (:result/error (safe-infer cache config model))))
    (reset! clock 30001)
    (with-redefs [ai/models catalogs]
      (catalog/warm! cache)
      (drain! jobs)
      (is (= "one" (infer cache config model)))
      (reset! clock (+ 30001 catalog/freshness-ms))
      (is (:result/error (safe-infer cache config model))))))

(deftest evicted-or-stopped-jobs-cannot-repopulate-state
  (let [{:keys [cache jobs]} (fixture) discovered (atom 0)]
    (with-redefs [ai/models (fn [_] (swap! discovered inc) (complete [model]))]
      (catalog/warm! cache)
      (reset! (:state cache) {})
      (drain! jobs)
      (is (zero? @discovered))
      (is (empty? @(:state cache))))))

(deftest configuration-fingerprints-and-queued-work-use-current-config
  (let [{:keys [cache jobs current]} (fixture) calls (atom [])]
    (with-redefs [ai/models (fn [p] (swap! calls conj (:name p)) (catalogs p))]
      (catalog/warm! cache)
      ;; Removing providers before queued work runs prevents their discovery.
      (reset! current {:providers []})
      (drain! jobs)
      (is (empty? @calls))
      (reset! current config)
      (reset! (:state cache) {})
      (catalog/warm! cache)
      (drain! jobs)
      (doseq [[field value] [[:url "https://changed.invalid/v1"]
                             [:key-file "/new-reference"] [:key "new-inline"]
                             [:gateway-id "new"] [:name "renamed"]
                             [:model "changed-default"] [:api "responses"]
                             [:tool-calling false]]]
        (is (:result/error
              (safe-infer cache (assoc-in config [:providers 1 field] value) model))))
      (let [changed (assoc-in config [:providers 1 :url] "https://changed.invalid/v1")]
        (reset! current changed)
        (catalog/warm! cache)
        (is (:result/error (safe-infer cache changed model)))
        (drain! jobs)
        (is (= "one" (infer cache changed model))))
      (reset! current {:providers []})
      (catalog/warm! cache)
      (is (empty? @(:state cache))))))

(deftest metadata-requests-only-and-selected-credential-boundary
  (let [{:keys [cache jobs]} (fixture) reads (atom []) gets (atom [])]
    (with-redefs [catalog/shared-cache cache
                  ai/resolve-provider (fn [p]
                                        (swap! reads conj (:key-file p))
                                        (-> p (dissoc :key-file) (assoc :key "fake-token")))
                  http/get (fn [url opts]
                             (swap! gets conj url)
                             (is (= "Bearer fake-token" (get-in opts [:headers "Authorization"])))
                             (is (= :none (:redirect-strategy opts)))
                             {:status 200 :body {:data [{:id (if (.contains url "one.invalid") model "other")}]}})]
      ;; Neither config, prepare, nor a cold inference attempt reads files/HTTP.
      (prepare "hello")
      (prepare (str "--provider one --model " model " hello"))
      (is (:result/error (commands/safely #(prepare (str "--model " model " hello")))))
      (is (empty? @reads))
      (is (empty? @gets))
      (catalog/warm! cache)
      (is (empty? @reads))
      (drain! jobs)
      (is (= (set (map :key-file providers)) (set @reads)))
      (is (= #{"https://one.invalid/v1/models" "https://two.invalid/v1/models"} (set @gets)))
      (is (= "one" (infer cache config model)))
      (let [state (pr-str @(:state cache))]
        (doseq [secret ["fake-token" "key-file" "Authorization" "https://" "not-a-real-secret"]]
          (is (not (.contains state secret)))))
      (prepare (str "--model " model " hello"))
      (is (= 2 (count @reads)))
      (is (= 2 (count @gets))))))

(deftest paginated-and-unknown-envelopes-do-not-establish-completeness
  (with-redefs [ai/resolve-provider identity]
    (doseq [pagination [{:has_more true} {:has_more false}
                       {:next "page-2"} {:pagination {}} {:paging {:next 2}}
                       {:total 2} {:unknown "unrecognized envelope"}
                       {:links {:next "https://evil.invalid"}}]]
      (with-redefs [http/get (fn [& _] {:status 200 :body (merge {:data [{:id model}]} pagination)})]
        (is (false? (:complete? (meta (ai/models (first providers))))))))))

(deftest cloudflare-rich-paginated-catalog-and-incomplete-pagination
  (let [cf {:name "cloudflare"
            :url (str "https://api.cloudflare.com/client/v4/accounts/"
                      "0123456789abcdef0123456789abcdef/ai/v1")}
        cfg {:providers [cf (second providers)]}
        current (atom cfg) jobs (atom []) gets (atom [])
        cache (catalog/new-cache (constantly 0) #(swap! jobs conj %)
                                 #(deref current))
        batch (into [{:name model}]
                    (map #(hash-map :name (str "@cf/vendor/model" %)) (range 99)))]
    (with-redefs [ai/resolve-provider identity
                  http/get (fn [url opts]
                             (swap! gets conj url)
                             (if (.contains url "ai/models/search")
                               (let [page (get-in opts [:query-params :page])]
                                 {:status 200
                                  :body (stream-body
                                          {:success true
                                           :result (if (= 1 page) batch [{:name "@cf/vendor/last"}])
                                           :result_info {:page page :per_page 100
                                                         :total_pages 2 :total_count 101}})})
                               {:status 200 :body {:object "list" :data []}}))]
      (catalog/warm! cache)
      (drain! jobs)
      (is (= "cloudflare" (infer cache cfg model)))
      (is (= 3 (count @gets)))
      (is (every? #(or (.endsWith % "/ai/models/search")
                       (.endsWith % "/models")) @gets)))
    (doseq [info [{:page 1 :per_page 100 :total_count 2}
                  {:total_pages "2"} {:page 1 :per_page 1}
                  {:total_pages 0} {:unknown "pagination"}]]
      (reset! (:state cache) {})
      (with-redefs [ai/resolve-provider identity
                    http/get (fn [url _]
                               {:status 200
                                :body (if (.contains url "ai/models/search")
                                        (stream-body {:success true :result [{:name model}]
                                                      :result_info info})
                                        {:data []})})]
        (catalog/warm! cache)
        (drain! jobs)
        (is (:result/error (safe-infer cache cfg model)))))))

(deftest bounds-cleanup-and-rejected-submission
  (let [{:keys [cache jobs current]} (fixture)]
    (doseq [ids [(vec (repeat (inc catalog/max-ids) "m"))
                 [(apply str (repeat (inc catalog/max-id-bytes) \x))]
                 (vec (repeat 1000 (apply str (repeat 300 \x))))]]
      (reset! (:state cache) {})
      (with-redefs [ai/models (constantly (complete ids))]
        (catalog/warm! cache)
        (drain! jobs)
        (is (:result/error (safe-infer cache config model)))))
    (reset! current {:providers (mapv #(assoc (first providers) :name (str %))
                                     (range (inc catalog/max-entries)))})
    (catalog/warm! cache)
    (is (empty? @jobs))
    (is (empty? @(:state cache))))
  (let [cache (catalog/new-cache (constantly 0)
                                 (fn [_] (throw (Exception. "private")))
                                 (constantly config))]
    (catalog/warm! cache)
    (is (:result/error (safe-infer cache config model)))
    (is (every? (comp not :refreshing) (vals @(:state cache))))))

(def ^:dynamic *request-marker* nil)

(deftest import-inert-startup-nonblocking-and-shutdown
  (is (nil? @catalog/runtime))
  (let [entered (promise) release (promise) marker (promise)
        worker-marker (promise)
        cache (catalog/new-cache #(quot (System/nanoTime) 1000000)
                                 ;; Lifecycle workers submit via shared submit.
                                 (:submit catalog/shared-cache)
                                 (fn []
                                   (deliver marker *request-marker*)
                                   (deliver entered true)
                                   @release
                                   config))]
    (with-redefs [catalog/shared-cache cache
                  ai/models (fn [provider]
                              (deliver worker-marker *request-marker*)
                              (catalogs provider))]
      (try
        (binding [*request-marker* :must-not-inherit]
          (is (nil? (catalog/start!))))
        (is (= true (deref entered 1000 :timeout)))
        (is (nil? (deref marker 1000 :timeout)))
        (let [runtime @catalog/runtime]
          (catalog/start!)
          (is (identical? runtime @catalog/runtime))
          (deliver release true)
          (is (nil? (deref worker-marker 1000 :timeout)))
          (catalog/stop!)
          (is (.isShutdown (:workers runtime)))
          (is (.isShutdown (:timer runtime)))
          (is (nil? @catalog/runtime))
          (is (empty? @(:state cache))))
        (finally (deliver release true) (catalog/stop!))))))

(deftest lifecycle-hook-starts-and-stops-both-metadata-services
  (let [calls (atom [])]
    (with-redefs [catalog/start! #(swap! calls conj :models-start)
                  catalog/stop! #(swap! calls conj :models-stop)
                  mcp-catalog/start! #(swap! calls conj :mcp-start)
                  mcp-catalog/stop! #(swap! calls conj :mcp-stop)
                  loader/find-and-load-namespaces (constantly :loaded)]
      (is (= :loaded (loader/load-commands)))
      (is (= :stopped (observer/before-stop (constantly :stopped))))
      (is (= [:mcp-start :models-start :models-stop :mcp-stop] @calls)))))
