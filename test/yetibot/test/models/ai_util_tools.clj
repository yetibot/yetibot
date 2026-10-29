(ns yetibot.test.models.ai-util-tools
  (:require [cheshire.core :as json]
            [clojure.string :as string]
            [midje.sweet :refer [fact facts =>]]
            [yetibot.api.ai :as ai]
            [yetibot.models.ai-util-tools :as util]
            [yetibot.test.support.ai-execution :as execution]))

(defn args [op value & [options]]
  (merge {"operation" op "input_json" (json/generate-string value)} options))

(defn error? [f]
  (try (f) false (catch Exception _ true)))

(defn result [op value & [options config]]
  (execution/in-event execution/request "ai utilities"
    (fn [request]
      (get-in (util/call! (or config {}) "util" (args op value options)
                          (:chat-source request))
              [:structuredContent :result]))))

(facts "deterministic JSON collection, string and data operators"
  (with-redefs [ai/config (constantly {})]
    (doseq [[op input options expected]
            [["head" [1 2 3] nil 1]
             ["tail" [1 2 3] nil 3]
             ["take" [1 2 3] {"count" 2} [1 2]]
             ["drop" [1 2 3] {"count" 2} [3]]
             ["rest" [1 2 3] nil [2 3]]
             ["droplast" [1 2 3] nil [1 2]]
             ["join" ["a" "b"] {"separator" ":"} "a:b"]
             ["split" "a.*b.*" {"separator" ".*"} ["a" "b" ""]]
             ["trim" " a \n" nil "a"]
             ["words" " a\t b " nil ["a" "b"]]
             ["unwords" ["a" "b"] nil "a b"]
             ["flatten" [[1 [2]] 3] nil [1 2 3]]
             ["letters" "a😀" nil ["a" "😀"]]
             ["unletters" ["a" "b"] nil "ab"]
             ["unique" [1 1 2] nil [1 2]]
             ["list" {"a" 1} nil [{"a" 1}]]
             ["count" [1 2] nil 2]
             ["sum" [1 2.5] nil 3.5M]
             ["sort" ["b" "a"] nil ["a" "b"]]
             ["sortnum" [3 1 2] nil [1 2 3]]
             ["grep" ["a.*" "abc"] {"query" ".*"} ["a.*"]]
             ["reverse" [1 2] nil [2 1]]
             ["range" [2 8 2] nil [2 4 6]]
             ["keys" {"b" 2 "a" 1} nil ["a" "b"]]
             ["vals" {"b" 2 "a" 1} nil [1 2]]
             ["get" {"a" [4]} {"path" ["a" 0]} 4]
             ["get" {"a" 1} {"path" ["missing"]} nil]
             ["range" [3 -1 -1] nil [3 2 1 0]]
             ["count" "a😀" nil 2]
             ["head" [] nil nil]
             ["random" [] nil nil]
             ["sum" [] nil 0]
             ["list" nil nil [nil]]
             ["droplast" [] nil []]]]
      (fact (result op input options) => expected))))

(fact "decimal data stays exact and extreme scales reject before arithmetic"
  (with-redefs [ai/config (constantly {})]
    (json/generate-string (result "sum" [0.1M 0.2M])) => "0.3"
    (doseq [text ["[1e-1000,1]" "[1e-2147483647,1]"]]
      (fact (error? #(util/arguments! {}
                        "util" {"operation" "sum" "input_json" text}))
            => true))))

(facts "random work is runtime only, bounded and preserves membership"
  (with-redefs [ai/config (constantly {})]
    (dotimes [_ 10]
      (fact (contains? #{1 2 3} (result "random" [1 2 3])) => true)
      (fact (sort (result "shuffle" [1 2 2 3])) => [1 2 2 3]))
    (with-redefs [clojure.core/rand-int
                  (fn [& _] (throw (AssertionError. "Preflight randomness")))
                  clojure.core/shuffle
                  (fn [& _] (throw (AssertionError. "Preflight shuffle")))]
      (doseq [op ["random" "shuffle"]]
        (fact (util/arguments! {} "util" (args op [1 2]))
              => (args op [1 2]))))))

(facts "strict, bounded JSON and operation contracts reject in preflight"
  (with-redefs [ai/config (constantly {})]
    (doseq [bad [{"operation" "head" "input_json" "[1] trailing"}
                 {"operation" "head" "input_json" "[NaN]"}
                 {"operation" "head" "input_json" "[1e999]"}
                 {"operation" "head" "input_json" "["}
                 {"operation" "head" "input_json" "{\"a\":1,\"a\":2}"}
                 (args "exec" "echo hi")
                 (args "head" [1] {"command" "ls"})
                 (args "head" [1] {"count" 1})
                 (args "take" [1] {"count" 129})
                 (args "take" [1] {"count" 1.5})
                 (args "split" "abc" {"separator" ""})
                 (args "sum" [1 "2"])
                 (args "sum" [1e308 1e308])
                 (args "sort" [1 "2"])
                 (args "get" {} {"path" []})
                 (args "get" {} {"path" [-1]})
                 (args "get" {} {"path" [128]})
                 (args "get" {} {"path" [true]})
                 (args "range" [0 1000000000])
                 (args "list" (vec (repeat 129 1)))
                 (args "list" (nth (iterate vector 0) 9))
                 (args "list" (vec (repeat 128 (vec (repeat 9 0)))))
                 (args "list" (string/join (repeat 12289 "x")))
                 (args "list" (string/join (repeat 4096 "😀")))
                 (args "letters" (string/join (repeat 129 "x")))
                 (args "split" (string/join (repeat 128 "x,"))
                       {"separator" ","})
                 (args "flatten" [(vec (range 128)) [128]])
                 (args "join" (vec (repeat 128 "x"))
                       {"separator" (string/join (repeat 200 "y"))})]]
      (fact (error? #(util/arguments! {} "util" bad)) => true))))

(facts "reader and command-looking strings are inert JSON data"
  (with-redefs [ai/config (constantly {})]
    (doseq [text ["#=(System/exit 0)" "$(touch /tmp/nope)"
                  "echo hi | xargs eval" "`rm -rf /`"]]
      (fact (result "trim" text) => text))))

(facts "credentials reject structurally, escaped copies and map keys"
  (with-redefs [ai/config (constantly {})]
    (let [config {:secrets ["private-token"]}]
      (doseq [value ["private-token" "private-\\u0074oken"
                     "\\u0070rivate-\\u0074oken"
                     {"private-token" "value"}
                     {"\\u0070rivate-\\u0074oken" "value"}
                     {"token" "anything"}]]
        (fact (error? #(util/arguments! config "util" (args "list" value)))
              => true))
      (fact (result "join" ["private-" "token"] {"separator" ""} config)
            => "[redacted]")
      (fact (result "unletters" ["private-" "token"] nil config)
            => "[redacted]")
      (fact (result "join" ["private-" "\\u0074oken"]
                    {"separator" ""} config) => "[redacted]")
      (fact (result "sum" [1200 34] nil {:secrets ["1234"]})
            => "[redacted]")
      (fact (error? #(util/arguments! {:secrets ["1234"]} "util"
                      (args "list" 1234))) => true)
      (fact (error? #(util/arguments! config "util"
                      (args "grep" ["ok"] {"query" "private-token"})))
            => true))
    (with-redefs [ai/config
                  (constantly {:providers [{:key "provider-secret"}]})]
      (fact (error? #(util/arguments! {} "util"
                      (args "trim" "provider-secret"))) => true))
    (let [config {:servers [{:headers {"Authorization" "Bearer header-secret"}}]
                  :redaction-servers [{:env {"TOKEN" "server-secret"}}]}]
      (doseq [secret ["header-secret" "server-secret"]]
        (fact (error? #(util/arguments! config "util" (args "trim" secret)))
              => true)))))

(facts "output keys and reconstructed secrets are protected before bounding"
  (let [config {:secrets ["private-token"]}
        project #'util/project!]
    (fact (get-in (project config {"private-token" "safe"})
                  [:structuredContent :result])
          => {"[redacted]" "safe"})
    (fact (get-in (project config {"token" "anything"})
                  [:structuredContent :result])
          => {"token" "[redacted]"})
    (fact (get-in (project {:secrets ["token"]} {"token" "anything"})
                  [:structuredContent :result])
          => {"[redacted]" "[redacted]"})
    (fact (get-in (project config (string/join (repeat 1200 "private-token")))
                  [:structuredContent :result])
          => (string/join (repeat 1200 "[redacted]")))))

(facts "calls require real provenance and never claim paid uncertainty"
  (with-redefs [ai/config (constantly {})]
    (let [reply (util/call! {} "util" (args "head" [1])
                          (:chat-source execution/request))]
      (fact (:isError reply) => true)
      (fact (true? (:unknownOutcome reply)) => false)
      (fact (string/includes? (json/generate-string reply) "[1]") => false))))
