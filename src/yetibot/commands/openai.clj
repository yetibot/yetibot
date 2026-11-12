(ns yetibot.commands.openai
  (:require
   [clojure.spec.alpha :as s]
   [clj-http.client :as client]
   [yetibot.core.hooks :refer [cmd-hook]]
   [yetibot.core.config :refer [get-config]]
   [yetibot.core.util.ai-usage :as usage]
   [yetibot.models.ai-tool-policy :as policy]
   [yetibot.util.ai-execution :as execution]
   [yetibot.util.ai-limits :as limits]))

(s/def ::key string?)

(s/def ::config (s/keys :req-un [::key]))

(def config (:value (get-config ::config [:openai])))

(def model "gpt-4")

(defn openai-completions
  {:yb/cat #{:info :fun}}
  [messages]
  (execution/with-source*
   {:provider {:name "openai" :url "https://api.openai.com/v1" :key (:key config)
                :continuation-transport "openai-text"}
    :model model :no-tools true :single-hop? true}
   (fn []
  (when execution/*command-capture*
    (execution/charge!)
    (policy/authorize! (:toolset-policy execution/*request*)
                       (policy/actor execution/*event*) {:name "openai"} model))
  (let [cap (when execution/*request*
              (:max-output-tokens (limits/effective {})))
        form (cond-> {:model model :temperature 0.9 :messages messages}
               cap (assoc :max_tokens cap))]
    (usage/capture!
      {:provider "openai" :model model :operation "text"
       :endpoint "https://api.openai.com/v1/chat/completions"
       :output-cap cap :secrets [(:key config)]}
      #(client/post
         "https://api.openai.com/v1/chat/completions"
         {:as :json :content-type :json :form-params form
          :throw-exceptions false :redirect-strategy :none
          :retry-handler (fn [& _] false)
          :headers {:Authorization (str "Bearer " (:key config))}})
      usage/response-metrics)))))

;; maintain per-channel context
(defonce context (atom {}))

(def initial-context {:role "system"
                      :content "You are a helpful assistant."})

(comment

  @context
  (reset! context {})

  (conj [initial-context] {:role "user"})

  (openai-completions "why is python so slow?")
  (let [room "abc"
        uuid "my-slack"
        context-key (str uuid "/" room)
        channel-context (get @context context-key [initial-context])
        user-content {:role "user"
                      :content "Who won the world series in 2020?"}
        response-content {:role "assistant"
                          :content "The Los Angeles Dodgers won the World Series in 2020."}
        new-context (conj channel-context  user-content response-content)]
    (swap! context assoc context-key new-context)))

(defn openai-cmd
  "openai <prompt> # prompt OpenAI completions API"
  [{chat-source :chat-source msg :match}]
  (try
    (let [{:keys [uuid room]} chat-source
          context-key (str uuid "/" room)
          channel-context (get @context context-key [initial-context])
          user-content {:role "user" :content msg}
          response (openai-completions (conj channel-context user-content))
          response-content (-> response :body :choices first :message)
          text (:content response-content)]
      (swap! context assoc context-key (conj channel-context
                                             user-content response-content))
      {:result/data response
       :result/value text})
    (catch Exception e
      (if-not execution/*command-capture* (throw e)
        {:result/value "OpenAI child request did not complete; do not retry automatically."
         :result/data {:isError true}}))))

(defn reset-cmd
  "openai reset # reset the OpenAI context for this channel"
  [{chat-source :chat-source msg :match}]
  (let [{:keys [uuid room]} chat-source
        context-key (str uuid "/" room)]
    (swap! context assoc context-key [initial-context])
    {:result/data context
     :result/value (str "OpenAI context for " room " channel reset")}))

(comment
  (openai-completions "why is python so slow?")
  (openai-completions "is chat gpt written in python?")
  (openai-completions "is chat gpt self loathing?"))

; {"role": "system", "content": "You are a helpful assistant."},
; {"role": "user", "content": "Who won the world series in 2020?"},
; {"role": "assistant", "content": "The Los Angeles Dodgers won the World Series in 2020."},
; {"role": "user", "content": "Where was it played?"}

(cmd-hook #"openai"
  #"reset" reset-cmd
  ; in the future we could allow setting the context
  ; #"context\s+(.+)" context-cmd
  _ openai-cmd)
