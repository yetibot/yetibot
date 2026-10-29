(ns yetibot.test.api.grok-tool-schemas
  (:require [clj-http.client :as client]
            [midje.sweet :refer [fact facts =>]]
            [yetibot.api.ai :as ai]
            [yetibot.models.ai-inference-tools :as inference]
            [yetibot.models.ai-wise-tools :as wise]))

(defn- complete-object? [schema]
  (and (= "object" (:type schema))
       (false? (:additionalProperties schema))
       (map? (:properties schema))
       (every? #(contains? (:properties schema) (keyword %))
               (:required schema))))

(defn- accepted-root? [schema]
  ;; xAI rejects a root union if any branch has no object type. Each branch
  ;; also needs the full shape: required fields cannot refer only to a parent.
  (and (complete-object? schema)
       (every? complete-object? (or (:oneOf schema) [schema]))))

(facts "native schemas survive the Grok Responses object-root contract"
  ;; Live diagnosis: the full catalog returned invalid_client_tool_schema for
  ;; yetibot_clef; removing the three new tools returned HTTP 200. This is an
  ;; offline regression for that observed contract, not a live xAI test.
  (doseq [[operation definition]
          (merge inference/definitions wise/definitions)]
    (let [captured (atom nil)
          provider {:name "grok" :url "https://api.x.ai/v1"
                    :api "responses" :key "synthetic-key"
                    :native-tools []}
          tool {:type "function"
                :function {:name (str "yetibot_" operation)
                           :description (:description definition)
                           :parameters (:inputSchema definition)}}]
      (with-redefs
        [client/post
         (fn [_ options]
           (let [tools (get-in options [:form-params :tools])]
             (reset! captured tools)
             (if (every? #(accepted-root? (:parameters %)) tools)
               {:status 200 :body {:status "completed"}}
               {:status 400
                :body {:code "invalid-argument"
                       :error (str "[invalid_client_tool_schema] "
                                   "tool parameter root must be an "
                                   "object type")}})))]
        (fact
          (ai/chat provider "grok-test"
                   [{:role "user" :content "Synthetic schema check."}]
                   {:tools [tool] :tool_choice "auto"})
          => {:status "completed"})
        (fact (mapv :name @captured) => [(str "yetibot_" operation)])
        (fact (mapv :type @captured) => ["function"])))))

(facts "object unions retain mutually exclusive native argument shapes"
  (let [branches (get-in inference/definitions
                         ["clef" :inputSchema :oneOf])]
    (mapv :required branches) => [["question"] ["questions"]]
    (mapv #(set (keys (:properties %))) branches)
    => [#{:input :images :question} #{:input :images :questions}])
  (let [branches (get-in wise/definitions ["wise" :inputSchema :oneOf])]
    (mapv #(get-in % [:properties :action :enum]) branches)
    => [["balance"] ["transactions"] ["summary"]]
    (mapv #(set (keys (:properties %))) branches)
    => [#{:action :account} #{:action :account :days :limit}
        #{:action :account :days}]))
