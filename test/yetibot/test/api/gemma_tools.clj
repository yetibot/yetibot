(ns yetibot.test.api.gemma-tools
  (:require [clojure.string :as string]
            [midje.sweet :refer [fact facts =>]]
            [yetibot.api.gemma-tools :as gemma]))

;; Verbatim user-visible screenshot regression: this was returned as an answer.
(def screenshot
  "<|tool_call>call:yetibot_ai{action:<|\"|>ask<|\"|>,model:<|\"|>@cf/deepseek-ai/deepseek-r1-distill-qwen-32b<|\"|>,prompt:<|\"|>What is the capital of France?<|\"|>,provider:<|\"|>cloudflare<|\"|>}<tool_call|>")

(defn envelope [arguments]
  (str "<|tool_call>call:fixture" arguments "<tool_call|>"))

(defn rejected? [text]
  (try (gemma/parse-calls text) false
    (catch Exception e
      (and (= gemma/error-message (.getMessage e))
           (= {:ai/error true} (ex-data e))
           (nil? (.getCause e))))))

(fact "the observed native protocol is decoded as bounded inert data"
  (gemma/parse-calls screenshot)
  => [{:name "yetibot_ai"
       :arguments {"action" "ask"
                   "model" "@cf/deepseek-ai/deepseek-r1-distill-qwen-32b"
                   "prompt" "What is the capital of France?"
                   "provider" "cloudflare"}}])

(fact "nested data, literal punctuation and multiple complete calls are supported"
  (gemma/parse-calls
    (str " \n" (envelope "{x:{yes:true,no:false,empty:null,n:-2.5e1},a:[1,{},[],<|\"|>a \"quote\" \\ slash, } [ : <|\"|>]}")
         "\n" (envelope "{}") " <|tool_response>\t"))
  => [{:name "fixture"
       :arguments {"x" {"yes" true "no" false "empty" nil "n" -25M}
                   "a" [1 {} [] "a \"quote\" \\ slash, } [ : "]}}
      {:name "fixture" :arguments {}}])

(fact "Unicode and numeric bounds preserve inert values without decoding escapes"
  (gemma/parse-calls (envelope "{text:<|\"|>Paris 🗼 \\u0041<|\"|>,n:1000000000000000}"))
  => [{:name "fixture" :arguments {"text" "Paris 🗼 \\u0041" "n" 1000000000000000}}])

(facts "no substring extraction, malformed control, readers or key ambiguity"
  (doseq [text [(str "Here is an example: " screenshot)
               (str "```\n" screenshot "\n```")
               (str "\"" screenshot "\"")
               (str screenshot " explanation")
               (str screenshot "<turn|>")
               (subs screenshot 0 (dec (count screenshot)))
               (envelope "{x:1,x:2}") (envelope "{x:{a:1,a:2}}")
               (envelope "{\"x\":1}") (envelope "{:x:1}")
               (envelope "{x:1,}") (envelope "{x:[1,]}")
               (envelope "{x:NaN}") (envelope "{x:Infinity}")
               (envelope "{x:01}") (envelope "{x:+1}")
               (envelope "{x:1e999999}") (envelope "{x:1000000000000001}")
               (envelope "{x:#=(spit \"/tmp/no\" \"no\")}")
               (envelope "{x:#{1 2}}") (envelope "[]")
               (envelope (str "{x:<|\"|>" (char 0xd800) "<|\"|>}"))
               (envelope (str "{x:<|\"|>" (char 0xdc00) "<|\"|>}"))
               (envelope (str "{x:<|\"|>" (char 0) "<|\"|>}"))
               (envelope "{x:<|\"|>unterminated}")
               (envelope "{x:<|\"|><|tool_call>bad<|\"|>}")
               (str "<|tool_call>call:a.b{}<tool_call|>")
               (str screenshot "<|tool_response>response:evil{}")]]
    (fact (rejected? text) => true)))

(facts "all parser resource limits fail closed with static errors"
  (doseq [text [(str screenshot (apply str (repeat 65536 " ")))
               (envelope (str "{x:<|\"|>" (apply str (repeat 16385 "a")) "<|\"|>}"))
               (envelope (str "{x:<|\"|>" (apply str (repeat 16384 "界")) "<|\"|>,y:<|\"|>"
                              (apply str (repeat 16384 "界")) "<|\"|>}"))
               (envelope (str "{x:" (apply str (repeat 17 "[")) "0"
                              (apply str (repeat 17 "]")) "}"))
               (envelope (str "{" (string/join "," (map #(str "k" % ":0") (range 129))) "}"))
               (envelope (str "{x:[" (string/join "," (repeat 4096 "0")) "]}"))
               (apply str (repeat 21 (envelope "{}")))]]
    (fact (rejected? text) => true)))
