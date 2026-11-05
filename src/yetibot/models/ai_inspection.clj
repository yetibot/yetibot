(ns yetibot.models.ai-inspection
  "Reviewed read-only AI inspection projections, never configuration dumps."
  (:require [clojure.walk :as walk]
            [yetibot.api.ai :as ai]
            [yetibot.api.mcp :as mcp]
            [yetibot.core.util.public-result :as public]))

(defn reviewed [result data]
  (let [providers (try (:providers (ai/config)) (catch Exception _ []))
        redactions (update (try (mcp/config) (catch Exception _ {}))
                           :secrets concat
                           (mapcat (juxt :key :key-file) providers))
        clean #(walk/postwalk
                 (fn [value]
                   (if (string? value) (mcp/metadata-redact value redactions)
                     value)) %)]
    (public/reviewed
      (cond-> (assoc (update result :result/value clean)
                      :result/public-data (clean data))
        (contains? result :result/data) (update :result/data clean)))))
