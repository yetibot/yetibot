(ns yetibot.commands.grok
  "Grok shortcut over shared AI configuration, policy and MCP execution."
  (:require [clojure.core.async :as async]
            [discljord.messaging :as discord]
            [yetibot.api.ai :as api]
            [yetibot.commands.ai :as ai]
            [yetibot.core.chat :as chat]
            ;; Require before registering our hook: the loader may encounter
            ;; core before or after this namespace, but require is idempotent.
            [yetibot.core.commands.grok :as legacy]
            [yetibot.core.config :refer [get-config]]
            [yetibot.core.hooks :refer [cmd-hook]]
            [yetibot.core.util.xai :as xai]))

(def usage
  (str "Usage: grok [--model <model>] "
       "[--toolset <name> | --no-tools] <prompt>"))

(defn- generate [prepared]
  (let [result (ai/complete-prompt prepared)]
    (-> result
        (update :result/value str "\n\nSent via " (:model prepared))
        (update :result/data assoc
                :provider (get-in prepared [:provider :name])
                :model (:model prepared)))))

(defn- discord-result [f]
  (try (deref (f) 30000 nil) (catch Exception _ nil)))

(defn- start-progress! [conn thread]
  (let [state (atom {:active? true})
        _ (try
            (when-let [pending (discord/create-message!
                                 conn thread :content "*thinking* ▰")]
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
                             :content (str "*thinking* "
                                           (apply str (repeat
                                                        (inc (mod tick 10))
                                                        "▰"))))))
                      true))
              (recur (inc tick)))))]
    (fn []
      (locking state
        (swap! state assoc :active? false)
        (when-let [id (:id @state)]
          (discord-result #(discord/delete-message! conn thread id))))
      (future-cancel progress))))

(defn- in-discord-thread [prepared channel-id message-id]
  (let [thread (legacy/start-thread! channel-id message-id (:prompt prepared))
        stop-progress! (start-progress! (legacy/rest-conn) thread)
        result (try
                 (ai/safely #(generate prepared))
                 (finally (stop-progress!)))]
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
   grok --no-tools <prompt> # disable MCP and native search for this request"
  {:yb/cat #{:ai}}
  [{:keys [chat-source] :as args}]
  (ai/safely
    (fn []
      (let [prepared (ai/prepare-prompt args "grok" usage grok-config)
            event (:raw-event chat-source)
            channel-id (or (:channel-id event) chat/*target*)
            message-id (:id event)]
        (if (and (legacy/discord?) channel-id message-id)
          (in-discord-thread prepared channel-id message-id)
          (generate prepared))))))

(cmd-hook #"grok"
  #"(?s)^.+$" grok-cmd)
