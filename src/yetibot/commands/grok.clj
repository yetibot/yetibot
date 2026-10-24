(ns yetibot.commands.grok
  "Grok shortcut over shared AI configuration, policy and MCP execution."
  (:require [clojure.core.async :as async]
            [clojure.string :as string]
            [clojure.walk :as walk]
            [discljord.messaging :as discord]
            [yetibot.api.ai :as api]
            [yetibot.api.ai-stream :as stream]
            [yetibot.api.mcp :as mcp]
            [yetibot.commands.ai :as ai]
            [yetibot.core.chat :as chat]
            ;; Require before registering our hook: the loader may encounter
            ;; core before or after this namespace, but require is idempotent.
            [yetibot.core.commands.grok :as legacy]
            [yetibot.core.config :refer [get-config]]
            [yetibot.core.hooks :refer [cmd-hook]]
            [yetibot.core.util.xai :as xai]
            [yetibot.util.ai-execution :as execution]))

(def usage
  (str "Usage: grok [--model <model>] "
       "[--toolset <name> | --no-tools] [--no-thread] <prompt>"))

(defn- generate [prepared]
  (let [result (ai/complete-prompt prepared)]
    (walk/postwalk
      #(if (string? %)
         (mcp/redact % {:secrets [(get-in prepared [:provider :key])]}) %)
      (-> result
          (update :result/value str "\n\nSent via " (:model prepared))
          (update :result/data assoc
                  :provider (get-in prepared [:provider :name])
                  :model (:model prepared))))))

(defn- discord-result [f]
  (try (deref (f) 30000 nil) (catch Exception _ nil)))

(defn- progress-text [{:keys [view activity]} tick]
  (stream/clip
    (str "*Grok — " (:phase view "Thinking") "* "
         (apply str (repeat (inc (mod tick 10)) "▰"))
         (when (seq activity)
           (str "\n" (string/join "\n" (map #(stream/clip % 80) activity))))
         (when (seq (:summary view))
           (str "\n\n*Thinking preview*\n" (stream/tail (:summary view) 400)))
         (when (seq (:text view))
           (str "\n\n*Draft answer*\n" (stream/tail (:text view) 1000))))
    1900))

(defn- start-progress! [conn thread]
  (let [state (atom {:active? true :view {} :activity []})
        _ (try
            (when-let [pending (discord/create-message!
                                 conn thread :content "*thinking* ▰"
                                 :allowed-mentions {:parse []})]
              (async/take!
                pending
                (fn [{:keys [id]}]
                  (when id
                    (locking state
                      (if (:active? @state)
                        (swap! state assoc :id id)
                        ;; Discljord may create a queued message after the
                        ;; answer. Its completion must still queue deletion.
                        (try (discord/delete-message! conn thread id)
                             (catch Exception _ nil))))))))
            (catch Exception _ nil))
        progress
        (future
          (loop [tick 1]
            (Thread/sleep 2000)
            (when (locking state
                    (when (:active? @state)
                      (when-let [id (:id @state)]
                        (discord-result
                          #(discord/edit-message!
                             conn thread id
                             :content (progress-text @state tick)
                             ;; Discljord 1.3.1 edits keep literal JSON keys.
                             :allowed_mentions {:parse []})))
                      true))
              (recur (inc tick)))))]
    {:update!
     (fn [event]
       (swap! state
              (fn [s]
                (if-not (:active? s) s
                  (let [phase (:phase event)
                        s (update s :view merge event)]
                    (if (and (string? phase)
                             (re-find #"^(Searching|Running|Finished|Unknown)"
                                      phase)
                             (not= phase (last (:activity s))))
                      (update s :activity
                              #(vec (take-last 3 (conj % phase)))) s))))))
     :stop!
     (fn []
       (locking state
         (swap! state assoc :active? false)
         (when-let [id (:id @state)]
           (discord-result #(discord/delete-message! conn thread id))))
       (future-cancel progress))}))

(defn- in-discord [prepared channel-id message-id]
  (let [thread (if (:no-thread prepared) channel-id
                (legacy/start-thread! channel-id message-id (:prompt prepared)))
        progress (start-progress! (legacy/rest-conn) thread)
        result (try
                 (ai/safely #(generate
                               (assoc prepared :on-progress
                                               (:update! progress))))
                 (finally ((:stop! progress))))]
    (binding [chat/*target* thread]
      (chat/chat-data-structure (or (:result/error result)
                                  (:result/value result))))
    (chat/suppress {})))

(defn- grok-config []
  (let [config (if (= :not-found (:error (get-config map? [:ai])))
                 {:providers []}
                 (api/config))
        key (:key xai/config)]
    (if (and (not-any? #(= "grok" (:name %)) (:providers config))
             (api/nonblank-string? key))
      (update config :providers conj
              {:name "grok" :url "https://api.x.ai/v1" :key key
               :model "grok-4.7" :api "responses"
               :native-tools ["web_search" "x_search"]})
      config)))

(defn grok-cmd
  "grok <prompt> # ask Grok (provider named grok or xai.key)
   grok --model <model> <prompt> # choose a model from the Grok provider
   grok --toolset <name> <prompt> # use an authorized shared MCP toolset
   grok --no-tools <prompt> # disable MCP and native search for this request
   grok --no-thread <prompt> # reply here without creating a Discord thread
   # Discord shows live thinking previews, tool status and draft answer text."
  {:yb/cat #{:ai}}
  [{:keys [chat-source] :as args}]
  (ai/safely
    (fn []
      (let [_ (when execution/*sensitive* (execution/sensitive!))
            prepared (ai/prepare-prompt args "grok" usage grok-config
                                        {"--no-thread" :no-thread})
            event (:raw-event chat-source)
            channel-id (or (:channel-id event) chat/*target*)
            message-id (:id event)]
        (if (and (legacy/discord?) channel-id message-id)
          (in-discord prepared channel-id message-id)
          (generate prepared))))))

(cmd-hook #"grok"
  #"(?s)^.+$" grok-cmd)
