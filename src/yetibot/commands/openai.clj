(ns yetibot.commands.openai
  (:require
   [clojure.spec.alpha :as s]
   [clj-http.client :as client]
   [taoensso.timbre :refer [info error]]
   [yetibot.core.hooks :refer [cmd-hook]]
   [yetibot.core.config :refer [get-config]]))

(s/def ::key string?)

(s/def ::config (s/keys :req-un [::key]))

(def config (:value (get-config ::config [:openai])))

(def model "gpt-4")

(defn openai-completions
  {:yb/cat #{:info :fun}}
  [msg]
  (client/post
   "https://api.openai.com/v1/chat/completions"
   {:as :json
    :content-type :json
    :form-params {:model model
                  :messages [{:role "user" :content msg}]}
    :throw-exceptions false
    :headers {:Authorization (str "Bearer " (:key config))}}))

(defn openai-cmd
  "openai <prompt> # prompt OpenAI completions API"
  [{msg :match}]
  (println "openai-cmd")
  (info "OpenAI msg" msg)
  (let [response (openai-completions msg)]
    (info "OpenAI response" response)
    (let [text (-> response :body :choices first :message :content)]
      (info "OpenAI text" text)
      {:result/data response
       :result/value text})))

(comment
  (openai-completions "why is python so slow?")
  (openai-completions "is chat gpt written in python?")
  (openai-completions "is chat gpt self loathing?"))

(cmd-hook #"openai"
  _ openai-cmd)
