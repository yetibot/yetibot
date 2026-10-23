(ns yetibot.test.api.clef
  (:require [cheshire.core :as json]
            [clj-http.client :as client]
            [clojure.string :as string]
            [midje.sweet :refer [facts fact =>]]
            [yetibot.api.clef :as clef]))

(def response
  {:status 200 :body {:success true :errors []
                     :result {:model "clef"
                              :answers {:intent {:type "choice" :choice "code_change" :confidence 0.8939
                                                 :probabilities {:code_change 0.98 :answer 0.01 :clarify 0.01}}
                                        :explicit_change {:type "noul" :noul 0.99}}}}})
(defn error? [f] (try (f) false (catch Exception _ true)))
(defn with-key [f]
  (let [file (java.io.File/createTempFile "yetibot-clef-test-" ".token")]
    (try (spit file "fixture-clef-secret\n")
         (f {:enabled true :account-id (apply str (repeat 32 "a")) :key-file (.getAbsolutePath file)
             :github-toolset "github-pr" :min-probability 0.95})
         (finally (.delete file)))))

(fact "Clef uses the documented fixed-host endpoint and sends only the request"
  (with-key
    (fn [config]
      (with-redefs [client/post
                    (fn [url options]
                      (let [payload (json/parse-string (:body options) true)]
                        (fact url => (str "https://api.cloudflare.com/client/v4/accounts/" (:account-id config)
                                         "/ai/run/@cf/cloudflare/clef")
                              (get-in options [:headers "Authorization"]) => "Bearer fixture-clef-secret"
                              (:redirect-strategy options) => :none
                              ((:retry-handler options) nil nil nil) => false
                              (:state payload) => "Fix owner/repo"
                              (:model payload) => "clef"
                              (set (keys (:questions payload))) => #{:intent :explicit_change}))
                      response)]
        (clef/classify! config "Fix owner/repo") => :code-change))))

(fact "documented payload and live response sample match the client contract"
  (clef/payload "Fix the parser bug in yetibot/yetibot and open a draft PR.")
  => (json/parse-string (slurp "doc/examples/clef-intent-request.json") true)
  (with-key
    (fn [config]
      (with-redefs [client/post (constantly {:status 200 :body (json/parse-string
                                                               (slurp "doc/examples/clef-intent-response.json") true)})]
        (clef/classify! config "Fix the parser bug in yetibot/yetibot and open a draft PR.") => :code-change))))

(facts "questions stay read-only and credentials and oversized requests are not sent"
  (with-key
    (fn [config]
      (with-redefs [client/post
                    (constantly (-> response
                                    (assoc-in [:body :result :answers :intent :choice] "answer")
                                    (assoc-in [:body :result :answers :intent :probabilities]
                                              {:code_change 0.01 :answer 0.98 :clarify 0.01})
                                    (assoc-in [:body :result :answers :explicit_change :noul] 0.01)))]
        (fact (clef/classify! config "Explain owner/repo") => :answer))
      (let [sent (atom 0)]
        (with-redefs [client/post (fn [& _] (swap! sent inc) response)]
          (doseq [text ["fixture-clef-secret" (apply str (repeat 4001 "a"))
                        (str "cfut_" (apply str (repeat 40 "x")))]]
            (fact (error? #(clef/classify! config text)) => true))
          (fact @sent => 0))))))

(facts "uncertain, inconsistent or malformed decisions never authorize changes"
  (with-key
    (fn [config]
      (doseq [r [(assoc-in response [:body :result :answers :explicit_change :noul] 0.4)
                 (assoc-in response [:body :result :answers :intent :probabilities]
                           {:code_change 0.8 :answer 0.1 :clarify 0.1})]]
        (with-redefs [client/post (constantly r)]
          (fact (clef/classify! config "Fix owner/repo") => :clarify)))
      (doseq [r [{:status 401 :body {:message "fixture-clef-secret"}}
                 (assoc-in response [:body :success] false)
                 (assoc-in response [:body :result :answers :intent :choice] "run_shell")
                 (assoc-in response [:body :result :answers :intent :probabilities :answer] 2)
                 (assoc-in response [:body :result :answers :intent :probabilities :code_change] 0.1)
                 (update-in response [:body :result :answers] dissoc :explicit_change)]]
        (with-redefs [client/post (constantly r)]
          (fact (error? #(clef/classify! config "Fix owner/repo")) => true)))
      (with-redefs [client/post (fn [& _] (throw (ex-info "fixture-clef-secret" response)))]
        (fact (try (clef/classify! config "Fix owner/repo")
                   (catch Exception e (string/includes? (.getMessage e) "fixture-clef-secret"))) => false)))))
