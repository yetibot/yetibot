(ns yetibot.models.ai-media-tools
  "Bounded image capabilities; never a command interpreter."
  (:require [clojure.string :as string]
            [clojure.walk :as walk]
            [yetibot.api.ai :as ai]
            [yetibot.api.mcp :as mcp]
            [yetibot.core.commands.grokimage :as gi]
            [yetibot.core.util.image-input :as image-input]
            [yetibot.core.util.xai :as xai]
            [yetibot.models.imgflip :as imgflip]))

(defn available-tools []
  (cond-> []
    imgflip/configured? (conj "meme")
    (ai/nonblank-string? (:key xai/config)) (conj "gi")))

(defn- redaction-config [config]
  (update config :secrets concat
          (filter ai/nonblank-string?
                  [(:password imgflip/config) (:key xai/config)])))

(defn- invalid! []
  (ai/fail "Invalid arguments for native Yetibot media tool."))

(defn- text? [s maximum]
  (and (ai/nonblank-string? s) (<= (count s) maximum)))

(defn arguments! [config operation args]
  (when-not (map? args) (invalid!))
  (let [action (get args "action")
        allowed (case operation
                  "gi" #{"prompt"}
                  "meme" (case action
                           "search" #{"action" "query" "limit"}
                           "generate" #{"action" "template_id" "top" "bottom"}
                           (invalid!)))]
    (when-not (every? allowed (keys args)) (invalid!))
    (when-not
      (case operation
        "gi" (text? (get args "prompt") 2000)
        "meme"
        (case action
          "search" (and (text? (get args "query") 200)
                        (or (not (contains? args "limit"))
                            (let [n (get args "limit")]
                              (and (integer? n) (<= 1 n 20)))))
          "generate"
          (and (string? (get args "template_id"))
               (re-matches #"[0-9]{1,20}" (get args "template_id"))
               (every? #(and (string? %) (<= (count %) 1000))
                       [(get args "top") (get args "bottom")])
               (some ai/nonblank-string?
                     [(get args "top") (get args "bottom")]))))
      (invalid!)))
  (let [config (redaction-config config)]
    (when (some #(and (string? %) (not= % (mcp/redact % config)))
                (vals args))
      (ai/fail "Media arguments contain configured credentials.")))
  args)

(defn- property [maximum]
  {:type "string" :minLength 1 :maxLength maximum})

(def definitions
  {"meme"
   {:description
    (str "Find Imgflip templates or create a captioned meme when requested. "
         "Search with a literal query, then generate using a returned "
         "template_id "
         "and top/bottom captions (one may be empty). Captions are plain text. "
         "Generated images are publicly hosted; include the returned URL.")
    :inputSchema
    {:type "object" :additionalProperties false :required ["action"]
     :properties {:action {:type "string" :enum ["search" "generate"]}
                  :query (property 200)
                  :limit {:type "integer" :minimum 1 :maximum 20}
                  :template_id {:type "string" :pattern "^[0-9]{1,20}$"}
                  :top {:type "string" :maxLength 1000}
                  :bottom {:type "string" :maxLength 1000}}}}
   "gi"
   {:description
    (str "Generate a Grok image when the user requests an image. "
         "Uses paid xAI calls and the existing gi image pipeline. "
         "Current-message attachments and image URLs follow gi's editing "
         "rules. "
         "Images are hosted by Yetibot; include the exact returned URL.")
    :inputSchema
    {:type "object" :additionalProperties false :required ["prompt"]
     :properties {:prompt (property 2000)}}}})

(defn- failure [message uncertain?]
  (cond-> {:isError true :structuredContent {:error message}}
    uncertain? (assoc :unknownOutcome true)))

(defn- image-result [url provider]
  (let [url (if (and (string? url) (string/starts-with? url "//"))
              (str "https:" url) url)]
    (if (and (text? url 2048) (re-find #"^https?://" url))
      {:structuredContent {:url url :provider provider :untrusted true}}
      (failure "Image generation returned no usable image URL." true))))

(defn- search! [args]
  (let [query (get args "query")
        needle (string/lower-case query)
        matches (filter #(string/includes? (string/lower-case (:name % ""))
                                            needle)
                        (get-in (imgflip/memes) [:data :memes]))
        matches (if (seq matches) matches
                    (imgflip/search-via-scrape query 1))]
    {:structuredContent
     {:untrusted true
      :templates (->> matches
                      (filter :id)
                      (take (get args "limit" 10))
                      (mapv #(select-keys % [:id :name :url])))}}))

(defn- caption! [args source]
  (let [resolve #(image-input/resolve-mentions % (:raw-event source))
        result (imgflip/generate-meme (get args "template_id")
                                      (resolve (get args "top"))
                                      (resolve (get args "bottom")))]
    (if (:success result)
      (image-result (get-in result [:data :url]) "imgflip")
      (failure "Imgflip could not generate the meme." false))))

(defn call! [config operation args source]
  (let [config (redaction-config config)
        uncertain? (or (= "gi" operation)
                       (= "generate" (get args "action")))]
    (if-not (some #{operation} (available-tools))
      (failure (if (= "gi" operation)
                 "gi is not configured. Set xai.key."
                 "meme is not configured. Set imgflip username and password.")
               false)
      (try
        (let [result
              (case operation
                "meme" (if (= "search" (get args "action"))
                         (search! args) (caption! args source))
                "gi"
                ;; The existing core utility prints a debug prompt to *out*.
                ;; Do not let private chat-derived prompts bypass log filtering.
                (binding [*out* (java.io.StringWriter.)]
                  (let [result (gi/grokimage-cmd
                                 {:match (get args "prompt")
                                  :chat-source source})]
                    (if (:result/error result)
                      (failure "Grok image generation failed." true)
                      (image-result (get-in result [:result/data :url])
                                    "grok")))))]
          (walk/postwalk #(if (string? %) (mcp/redact % config) %) result))
        (catch Exception _
          ;; A timeout may follow successful generation. Never retry paid calls.
          (failure "The media tool failed; do not repeat this request."
                   uncertain?))))))
