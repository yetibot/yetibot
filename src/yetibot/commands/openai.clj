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
  "Ask OpenAI for a response to a message."
  [msg]
  (let [response (openai-completions msg)]
    (info "OpenAI response" response)
    (let [choices (get-in response [:body :choices])
          text (get-in (first choices) [:text])]
      (info "OpenAI text" text)
      {:result/data response
       :result/value text})))


(comment
  (openai-completions "why is python so slow?")
  (openai-completions "is chat gpt written in python?")
  (openai-completions "is chat gpt self loathing?"))

(cmd-hook #"openai"
  _ openai-cmd)
