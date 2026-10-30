(ns yetibot.test.api.cloudflare-metadata
  (:require [clojure.string :as string]
            [midje.sweet :refer [fact =>]]
            [yetibot.api.cloudflare :as cf]))

(fact "one metadata record compiles credential patterns once, not per string"
  (let [compile-pattern re-pattern
        compilations (atom 0)
        result (with-redefs [clojure.core/re-pattern
                             (fn [text]
                               (swap! compilations inc)
                               (compile-pattern text))]
                 (cf/model-metadata
                   {:id "fixture-model" :description "fixture/secret"
                    :properties {:summary "fixture\\/secr\\u0065t"
                                 :example "public"}}
                   {:secrets ["fixture/secret"]}))]
    result => {:id "fixture-model" :description "[redacted]"
               :properties {:summary "[redacted]" :example "public"}}
    ;; One credential pattern and the existing sensitive-field pattern.
    @compilations => 2))

(fact "oversized required metadata terminates with a safe static failure"
  (let [sentinel "HOSTILE-METADATA-SENTINEL"
        text (string/join (repeat 2000 "界"))
        hostile {:id text :metadata-source text
                 :function-calling {:status text :source sentinel}}
        work (future
               (try
                 (cf/model-metadata hostile {:secrets [sentinel]})
                 (catch Exception e
                   {:message (.getMessage e) :data (ex-data e)})))]
    (try
      (let [result (deref work 2000 ::timeout)]
        (not= ::timeout result) => true
        result => {:message
                   "Cloudflare model metadata exceeded its size limit."
                   :data {:ai/error true}}
        (string/includes? (pr-str result) sentinel) => false)
      (finally (future-cancel work)))))
