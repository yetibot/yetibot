(ns yetibot.commands.grok
  "Grok shortcut over shared AI configuration, policy and MCP execution."
  (:require [clojure.core.async :as async]
            [clojure.string :as string]
            [discljord.messaging :as discord]
            [yetibot.commands.ai :as ai]
            [yetibot.core.chat :as chat]
            ;; Require before registering our hook: the loader may encounter
            ;; core before or after this namespace, but require is idempotent.
            [yetibot.core.commands.grok :as legacy]
            [yetibot.core.config :refer [get-config]]
            [yetibot.core.hooks :refer [cmd-hook]]))

(def usage
  (string/replace ai/usage "Usage: ai" "Usage: grok"))

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

(defn grok-cmd
  "grok <prompt> # ask the grok provider, or the shared default provider
   grok --provider <name> --model <model> <prompt> # choose a provider/model
   grok --toolset <name> <prompt> # use an authorized shared MCP toolset
   grok --no-tools <prompt> # disable MCP and native search for this request"
  {:yb/cat #{:ai}}
  [{:keys [chat-source] :as args}]
  (if (= :not-found (:error (get-config map? [:ai])))
    ;; Existing core-only configuration remains usable. Invalid shared config
    ;; must fail closed rather than silently bypassing its toolset policy.
    (legacy/grok-cmd args)
    (ai/safely
      (fn []
        (let [prepared (ai/prepare-prompt args "grok" usage)
              event (:raw-event chat-source)
              channel-id (or (:channel-id event) chat/*target*)
              message-id (:id event)]
          (if (and (legacy/discord?) channel-id message-id)
            (in-discord-thread prepared channel-id message-id)
            (generate prepared)))))))

(cmd-hook #"grok"
  #"^agent\s+(?s)(.+)" legacy/grok-agent-cmd
  #"(?s)^.+$" grok-cmd)
