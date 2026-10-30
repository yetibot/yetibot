(ns yetibot.commands.cf
  "Cloudflare shortcut: catalog discovery and bounded text-only inference."
  (:require [clojure.string :as string]
            [yetibot.api.ai :as api]
            [yetibot.api.cloudflare :as cloudflare]
            [yetibot.api.mcp :as mcp]
            [yetibot.commands.ai :as ai]
            [yetibot.core.hooks :refer [cmd-hook]]
            [yetibot.models.ai-model-tools :as models]
            [yetibot.models.ai-toolsets :as toolsets])
  (:import [java.util Locale]))

(def usage "Usage: cf [--model <model>] [--toolset <name>] <prompt>")

(defn- failure [args unknown?]
  ;; Core decorates :result/error with the entire evaluated command. Return
  ;; only static human text and safe structured flags, never that error path.
  ;; Keep failure short-circuiting, especially after an uncertain paid POST.
  (when-let [skip (:skip-next-n args)]
    (reset! skip (count (:next-cmds args))))
  (let [message (if unknown?
                  (str "Cloudflare outcome could not be confirmed; "
                       "do not repeat this paid request.")
                  (str "Cloudflare request failed. Check options, access, "
                       "configuration and chat model eligibility."))]
    {:result/value message
     :result/data {:isError true :unknownOutcome (boolean unknown?)
                   :error message}}))

(defn- safely [args f]
  (try (f) (catch Exception _ (failure args false))))

(def catalog-usage
  "Usage: cf models [--categories | --category <category>]")

(defn- lower-case [text]
  (.toLowerCase ^String text Locale/ROOT))

(defn- catalog-options [text]
  (let [text (string/trim (or text ""))]
    (cond
      (string/blank? text) {}
      (= text "--categories") {:categories? true}
      :else (when-let [[_ category]
                       (re-matches #"--category\s+(\S+)" text)]
              (when-not (string/starts-with? category "--")
                {:category (lower-case category)})))))

(defn- category [model]
  (let [task (get-in model [:task :name])
        label (if (string? task) (string/trim task) "")
        id (-> (lower-case label)
               (string/replace #"[^\p{L}\p{N}]+" "-")
               (string/replace #"^-|-$" ""))]
    (if (string/blank? id)
      {:id "uncategorized" :name "Uncategorized"}
      {:id id :name label})))

(defn- categories [records]
  (->> records
       (group-by #(:id (category %)))
       (map (fn [[_ entries]]
              (assoc (category (first entries)) :count (count entries))))
       (sort-by :id)
       vec))

(defn- catalog-error [args message]
  (-> (failure args false)
      (assoc :result/value message)
      (assoc-in [:result/data :error] message)))

(defn- catalog-result [catalog]
  {:result/data catalog
   :result/collection-path [:models]
   :result/value
   (mapv #(str (:id %) " ["
               (get-in % [:task :name] "unknown task")
               "; " (name (:interface %)) "]")
         (:models catalog))})

(defn models-cmd
  "cf models # list the full Workers AI catalog with task/interface labels
   cf models --categories # list task categories and model counts
   cf models --category <category> # list models in a task category"
  {:yb/cat #{:ai}}
  [{[_ text] :match :as args}]
  (if-let [options (catalog-options text)]
    (safely args
      (fn []
        (let [provider (api/provider (api/config) "cloudflare")]
          (when-not (cloudflare/provider-account provider)
            (api/fail "Configure the Cloudflare Workers AI provider first."))
          (let [models (api/models provider)
                catalog (:catalog (meta models))
                records (:models catalog)]
            (cond
              (:categories? options)
              (let [summary (categories records)]
                {:result/data (assoc (dissoc catalog :models)
                                     :categories summary)
                 :result/collection-path [:categories]
                 :result/value
                 (mapv (fn [{:keys [id name count]}]
                         (str id ": " count " model"
                              (when (not= count 1) "s") " (" name ")"))
                       summary)})
              (:category options)
              (let [id (:category options)
                    selected (filterv #(= id (:id (category %))) records)]
                (if (seq selected)
                  (catalog-result (assoc catalog :category id
                                                :models selected))
                  (catalog-error args
                    (str "No models matched that category. "
                         "Use cf models --categories."))))
              :else (catalog-result catalog))))))
    (catalog-error args catalog-usage)))

(defn prompt-cmd
  "cf <prompt> # ask Cloudflare, text-only, no tools or implicit history
   cf --model <model> <prompt> # choose a catalogued chat model
   cf --toolset <name> <prompt> # enforce a configured provider/model policy"
  {:yb/cat #{:ai}}
  [{:keys [match chat-source] :as args}]
  (safely args
    (fn []
      (let [options (ai/parse-prompt match usage)
            _ (when (or (:provider options) (:max-tool-calls options))
                (api/fail usage))
            config (toolsets/select (mcp/config) (:toolset options))
            result (models/call!
                     config "cf"
                     (cond-> {"action" "ask" "prompt" (:prompt options)}
                       (:model options) (assoc "model" (:model options)))
                     chat-source)
            data (:structuredContent result)]
        (if (:isError result)
          (failure args (:unknownOutcome result))
          {:result/value (:text data) :result/data data})))))

(cmd-hook #"cf"
  #"(?s)^models(?:\s+(.*))?$" models-cmd
  #"(?s)^.+$" prompt-cmd)
