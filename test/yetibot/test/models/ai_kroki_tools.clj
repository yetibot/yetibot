(ns yetibot.test.models.ai-kroki-tools
  (:require [clojure.string :as string]
            [midje.sweet :refer [fact facts =>]]
            [yetibot.models.ai-kroki-tools :as kroki]))

(def source {"type" "mermaid" "source" "graph TD; A-->B"})

(defn rejection [args config]
  (try (kroki/arguments! config "kroki_render" args)
       (catch Exception e (ex-data e))))

(facts "literal Kroki validation preserves current accepted plugin requests"
  (doseq [args [source (assoc source "format" nil "options" nil)
               (assoc source "format" "svg" "options" {"layout" "tala" "scale" 2 "flag" true})
               (assoc source "source" (apply str (repeat 262144 "x")))]]
    (fact (kroki/arguments! {} "kroki_render" args) => args)))

(facts "invalid raw render arguments retain only authored field constraints"
  (doseq [[args field code]
          [[27 "*" :type]
           [(assoc source "source" (apply str (repeat 90000 "測"))) "source" :size]
           [(assoc source "options" {"PRIVATE@everyone\n" []}) "options.*" :format]
           [(assoc source "options" {"layout" (apply str (repeat 2049 "x"))}) "options.*" :size]
           [(assoc source "source" "   ") "source" :size]
           [(assoc source "options" (zipmap (map #(str "k" %) (range 33)) (repeat true))) "options" :size]]]
    (let [data (rejection args {})]
      (fact (get-in data [:ai/problem :field]) => field)
      (fact (get-in data [:ai/problem :code]) => code)
      (fact (get-in data [:ai/problem :execution-began]) => false)
      (when (= field "options")
        (fact (get-in data [:ai/problem :remedy]) => "Use at most 32 scalar diagram options."))
      (fact (boolean (re-find #"PRIVATE|@everyone|測" (pr-str data))) => false))))

(facts "configured credentials are blocked without reflecting values or source"
  (doseq [args [(assoc source "source" "graph TD; PRIVATE-CREDENTIAL-->B")
               (assoc source "options" {"PRIVATE-CREDENTIAL" true})
               (assoc source "options" {"layout" "PRIVATE-CREDENTIAL"})]]
    (let [data (rejection args {:secrets ["PRIVATE-CREDENTIAL"]})]
      (fact (:ai/error-code data) => :credential-input)
      (fact (get-in data [:ai/problem :code]) => :credential-input)
      (fact (get-in data [:ai/problem :execution-began]) => false)
      (fact (string/includes? (pr-str data) "PRIVATE-CREDENTIAL") => false))))
