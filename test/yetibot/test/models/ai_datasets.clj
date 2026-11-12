(ns yetibot.test.models.ai-datasets
  (:require [cheshire.core :as json]
            [clojure.string :as string]
            [midje.sweet :refer :all]
            [yetibot.models.ai-datasets :as datasets]))

(def owner {:actor {:id "caller"} :chat-source {:adapter :discord :room "test"}})
(def envelope
  {:rows [{:city "A" :temp 2.5} {:city "B" :temp -1}]
   :fields [{:name "city" :path ["city"] :type :nominal}
            {:name "temp" :path ["temp"] :type :quantitative :unit "C"}]
   :source "weather" :captured-at "2026-11-11T10:00:00Z"
   :coverage {:status :unknown}})

(fact "datasets are complete snapshots scoped to a live owner and store"
  (let [store (datasets/create-store owner)
        other (datasets/create-store owner)
        descriptor (datasets/retain! store owner envelope)
        id (:dataset-id descriptor)]
    (string? id) => true
    (get-in descriptor [:coverage :row-count]) => 2
    (:rows (datasets/lookup! store owner id))
    => [{"city" "A" "temp" 2.5} {"city" "B" "temp" -1}]
    (datasets/lookup! other owner id) => (throws clojure.lang.ExceptionInfo)
    (datasets/lookup! store (assoc owner :actor {:id "other"}) id)
    => (throws clojure.lang.ExceptionInfo)
    (datasets/close! store owner) => nil
    (datasets/lookup! store owner id) => (throws clojure.lang.ExceptionInfo)
    (datasets/retain! store owner envelope) => (throws clojure.lang.ExceptionInfo)))

(fact "invalid snapshots fail atomically, not by silently taking a prefix"
  (let [store (datasets/create-store owner)
        id (:dataset-id (datasets/retain! store owner envelope))]
    (doseq [rows [[{:city "A" "city" "collision"}]
                  [{:temp Double/NaN}]
                  [{:temp (Object.)}]
                  (repeat {:temp 1})
                  (vec (repeat 10001 {:temp 1}))
                  [{:temp (apply str (repeat 2097153 "x"))}]]]
      (datasets/retain! store owner (assoc envelope :rows rows))
      => (throws clojure.lang.ExceptionInfo))
    (:rows (datasets/lookup! store owner id))
    => [{"city" "A" "temp" 2.5} {"city" "B" "temp" -1}]
    (get-in (datasets/describe store owner id) [:coverage :row-count]) => 2))

(fact "dataset count and aggregate bytes are finite with atomic refusal"
  (let [store (datasets/create-store owner)]
    (dotimes [_ 8] (datasets/retain! store owner envelope))
    (datasets/retain! store owner envelope) => (throws clojure.lang.ExceptionInfo))
  (let [store (datasets/create-store owner)
        large (assoc envelope :rows (vec (repeat 5 {:city (apply str (repeat 70000 "x")) :temp 1})))
        first-id (:dataset-id (datasets/retain! store owner large))]
    (dotimes [_ 4] (datasets/retain! store owner large))
    (datasets/retain! store owner large) => (throws clojure.lang.ExceptionInfo)
    (count (:rows (datasets/lookup! store owner first-id))) => 5))

(fact "row, field, depth, scalar and coverage limits do not silently sample"
  (let [store (datasets/create-store owner)
        many (assoc envelope :rows (vec (repeat 10000 {:city "A" :temp 1})))
        fields (mapv #(hash-map :name (str "f" %) :path [(str "f" %)] :type :quantitative) (range 64))
        row (into {} (map #(vector (str "f" %) %) (range 64)))]
    (get-in (datasets/retain! store owner many) [:coverage :row-count]) => 10000
    (count (:fields (datasets/retain! store owner (assoc envelope :fields fields :rows [row])))) => 64
    (doseq [bad [(assoc envelope :fields (conj fields {:name "extra" :path ["extra"] :type :nominal}))
                 (assoc envelope :rows [{:city (reduce (fn [x _] {"x" x}) "leaf" (range 13))}])
                 (assoc envelope :rows [{:city (repeat "infinite")}])
                 (assoc envelope :rows [{:temp Double/POSITIVE_INFINITY}])
                 (assoc envelope :rows [{:temp 1/3}])
                 (assoc envelope :rows [{:temp "2.5"}])
                 (assoc-in envelope [:fields 0 :name] (apply str (repeat 65 "x")))
                 (assoc-in envelope [:fields 0 :path] [(apply str (repeat 129 "x"))])
                 (assoc-in envelope [:coverage :status] :invented)
                 (assoc envelope :coverage {:status :complete :requested 5 :succeeded 4 :failed 1})
                 (assoc envelope :coverage {:status :complete :has-more true})
                 (assoc envelope :coverage {:status :complete :requested 5 :succeeded 4})
                 (assoc envelope :coverage {:status :unknown :requested 5 :succeeded 6})
                 (assoc envelope :coverage {:status :unknown :has-more "yes"})]]
      (datasets/retain! store owner bad) => (throws clojure.lang.ExceptionInfo))))

(fact "preview credentials are redacted, bounded and never mutate private observations"
  (let [store (datasets/create-store owner {:secrets ["PRIVATE-TOKEN"]})
        raw (assoc envelope :rows [{:city "PRIVATE-TOKEN" :temp 2.5 :password "secret"}])
        descriptor (datasets/retain! store owner raw)
        id (:dataset-id descriptor)]
    (string/includes? (json/generate-string descriptor) "PRIVATE-TOKEN") => false
    (get-in descriptor [:preview 0 "password"]) => "[redacted]"
    (get-in descriptor [:preview 0 "temp"]) => 2.5
    (get-in (datasets/lookup! store owner id) [:rows 0 "city"]) => "PRIVATE-TOKEN"
    (get-in (datasets/lookup! store owner id) [:rows 0 "temp"]) => 2.5)
  (let [store (datasets/create-store owner)
        raw (assoc envelope :rows [{:city (apply str (repeat 7000 "é")) :temp 1}])
        descriptor (datasets/retain! store owner raw)]
    (<= (alength (.getBytes (json/generate-string descriptor) "UTF-8")) 12288) => true
    (:preview-omitted? descriptor) => true
    (count (get-in (datasets/lookup! store owner (:dataset-id descriptor)) [:rows 0 "city"])) => 7000))

(fact "oversized metadata is refused instead of returning truncated JSON"
  (let [store (datasets/create-store owner)
        fields (mapv #(hash-map :name (str "f" %) :type :nominal
                               :path (vec (repeat 12 (apply str (repeat 128 "x"))))
                               :unit (apply str (repeat 128 "x"))) (range 64))]
    (datasets/retain! store owner (assoc envelope :fields fields))
    => (throws clojure.lang.ExceptionInfo)))

(fact "finance decimal semantics are explicit, not inferred from generic numeric strings"
  (let [store (datasets/create-store owner)
        finance {:rows [{:amount "1234567890.12345678" :currency "USD"}]
                 :fields [{:name "amount" :path ["amount"] :type :quantitative
                           :semantic :transaction-amount :unit-field "currency"}
                          {:name "currency" :path ["currency"] :type :nominal}]
                 :source "wise" :captured-at "2026-11-11T10:00:00Z"
                 :coverage {:status :complete :requested 1 :succeeded 1 :failed 0}}]
    (get-in (datasets/retain! store owner finance) [:coverage :status]) => :complete
    (datasets/retain! store owner (assoc finance :source "generic"))
    => (throws clojure.lang.ExceptionInfo)))

(fact "closing while a lazy snapshot is in flight cannot repopulate the store"
  (let [store (datasets/create-store owner)
        entered (promise) resume (promise)
        raw (assoc envelope :rows [{:city "A" :temp 1
                                   :extra (lazy-seq (deliver entered true)
                                                    @resume ["private"])}])
        task (future (try (datasets/retain! store owner raw) :retained
                          (catch clojure.lang.ExceptionInfo _ :refused)))]
    (deref entered 1000 :timeout) => true
    (datasets/close! store owner)
    (deliver resume true)
    (deref task 1000 :timeout) => :refused
    (datasets/retain! store owner envelope) => (throws clojure.lang.ExceptionInfo)))

(fact "namespaced sensitive key semantics match piped AI input for keyword and string keys"
  (doseq [key [:auth/token "auth/token" :auth/api-key "auth/api-key"]]
    (let [store (datasets/create-store owner)
          descriptor (datasets/retain! store owner (assoc envelope :rows [{:city "A" :temp 1 key 987654321}]))]
      (get-in descriptor [:preview 0 (if (keyword? key) (subs (str key) 1) key)]) => "[redacted]"
      (string/includes? (json/generate-string descriptor) "987654321") => false
      (get-in (datasets/lookup! store owner (:dataset-id descriptor))
              [:rows 0 (if (keyword? key) (subs (str key) 1) key)]) => 987654321)))
