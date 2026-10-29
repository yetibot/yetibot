(ns yetibot.util.ai-config
  "Normalize exploded environment keys without changing core config."
  (:require [clojure.string :as string]))

(defn- lift-fields [value fields]
  (if-not (map? value) value
    (reduce (fn [m field]
              (let [path (mapv keyword (string/split (name field) #"-"))
                    nested (get-in m path ::absent)]
                (if (or (contains? m field) (= ::absent nested)) m
                  (assoc m field nested)))) value fields)))

(defn- flatten-options [value separator]
  (letfn [(entries [prefix m]
            (mapcat (fn [[key value]]
                      (let [part (if (keyword? key)
                                   ;; Flat EDN keys historically used name verbatim.
                                   ;; Only nested exploded paths need reconstruction.
                                   (if (and (empty? prefix) (not (map? value)))
                                     (name key)
                                     (-> (name key) string/upper-case
                                         (string/replace "-" separator)))
                                   key)
                            path (conj prefix part)]
                        (if (map? value) (entries path value)
                          [[(string/join separator path) value]]))) m))]
    (if (map? value) (into {} (entries [] value)) value)))

(defn mcp [value]
  (let [value (lift-fields value
                           [:default-toolset :max-tool-calls :timeout-ms])]
    (cond-> value
      (vector? (:servers value))
      (update :servers
              (fn [servers]
                (mapv #(if (map? %)
                         (cond-> (lift-fields % [:github-api-token])
                           (contains? % :env) (update :env flatten-options "_")
                           (contains? % :headers)
                           (update :headers flatten-options "-"))
                         %)
                      servers)))
      (vector? (:toolsets value))
      (update :toolsets
              #(mapv (fn [t]
                       (lift-fields t [:native-tools :base-branches
                                       :allowed-paths :denied-paths
                                       :write-server :branch-prefix])) %)))))

(defn ai [value]
  (let [value (lift-fields value [:default-provider :intent-router])]
    (cond-> value
      (map? (:intent-router value))
      (update :intent-router lift-fields [:account-id :key-file :min-probability
                                         :github-toolset :gateway-id])
      (vector? (:providers value))
      (update :providers
              #(mapv (fn [p]
                       (cond-> (lift-fields p [:tool-calling :tool-choice
                                              :native-tools :max-turns
                                              :gateway-id :key-file])
                         (map? (:key p)) (dissoc :key))) %)))))
