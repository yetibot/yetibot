(ns yetibot.commands.system-one
  "Standalone structured inference. Never invokes the automatic-PR router."
  (:require [cheshire.core :as json]
            [clojure.edn :as edn]
            [clojure.string :as string]
            [clojure.walk :as walk]
            [yetibot.api.clef :as clef]
            [yetibot.api.cloudflare :as cf]
            [yetibot.api.cloudflare-images :as images]
            [yetibot.core.config :as config]
            [yetibot.core.hooks :refer [cmd-hook]]
            [yetibot.core.util.image-input :as image-input]
            [yetibot.util.ai-config :as settings]
            [yetibot.util.ai-execution :as execution])
  (:import [java.io PushbackReader StringReader]))

(defn- schema! [text]
  (when (> (count text) 76384)
    (cf/fail! "Schema and inline input exceeded their size limits."))
  (try
    (let [quote (first text)
          end (when (#{\' \"} quote)
                (string/index-of text (str quote) 1))
          _ (when (and (#{\' \"} quote) (nil? end))
              (cf/fail!
                "Close the schema quote, or omit the surrounding quotes."))
          [form tail] (if end
                        [(subs text 1 end) (subs text (inc end))]
                        [text ""])]
      (with-open [reader (PushbackReader. (StringReader. form))]
        (let [value (edn/read {:readers {} :eof nil} reader)
              remaining (slurp reader)]
          (when (> (- (count form) (count remaining)) 16384)
            (cf/fail! "Schema is limited to 16384 characters."))
          [(json/parse-string (json/generate-string value) true)
           (string/trim (str remaining " " tail))])))
    (catch StackOverflowError _ (cf/fail! "Schema nesting is too deep."))
    (catch Exception e
      (if (:ai/error (ex-data e)) (throw e)
        (cf/fail! "Provide one valid EDN schema map.")))))

(defn- schema-valid? [model schema]
  (and (map? schema) (<= 1 (count schema) 64)
       (every?
         (fn [[id field]]
           (and (keyword? id) (nil? (namespace id))
                (re-matches (if (= model "clef") #"[A-Za-z0-9_.-]{1,100}"
                              #"[A-Za-z_][A-Za-z0-9_.-]{0,63}") (name id))
                (map? field)
                (if (= model "clef")
                  (and (or (and (string? (:instructions field))
                                (not (string/blank? (:instructions field))))
                           (and (coll? (:instructions field))
                                (seq (:instructions field))))
                       (case (:type field)
                         "noul" true
                         "choice" (and (map? (:criteria field))
                                       (<= 2 (count (:criteria field)) 255)
                                       (every? #(not (string/blank? (name %)))
                                               (keys (:criteria field))))
                         "score" (and (vector? (:criteria field))
                                      (<= 2 (count (:criteria field)) 10))
                         false))
                  (and (contains? #{"text" "number" "date" "time" "datetime"
                                    "duration" "list" "estimate" "forecast"}
                                  (:type field))
                       (or (not= "list" (:type field))
                           (contains? #{"text" "number" "date"} (:items field)))
                       (or (not (#{"estimate" "forecast"} (:type field)))
                           (and (contains? field :min)
                                (contains? field :max))))))) schema)))

(def ^:private clairic-usage
  (str "Usage: clairic count|rate|read|list <request> [image URL]. "
       "Pipe source text or attach an image. "
       "Smoke test: clairic demo. "
       "Advanced: clairic --fields '<EDN field map>' [text/image URL]."))

(defn- quick-fields [mode request]
  (case mode
    "count" {:count {:type "number" :integer true :instructions request}}
    "rate" {:rating {:type "estimate" :min 0 :max 10 :step 1
                     :instructions
                     (str "Give a subjective rating from 0 (not at all) to 10 "
                          "(extremely), using only the supplied input. "
                          request)}}
    "read" {:text {:type "text" :instructions request}}
    "list" {:items {:type "list" :items "text" :instructions request}}))

(defn- quick-request [text]
  (when (> (count text) 16384)
    (cf/fail! "Clairic requests are limited to 16384 characters."))
  (if (= "demo" (string/lower-case text))
    {:mode "count" :demo? true
     :question "Extract the balloon count stated in the supplied text."
     :state "Balloon count: 3."}
    (if-let [[_ action]
             (re-find #"(?is)^(count|how\s+many|rate|read|list)\s+\S" text)]
      {:mode (if (re-find #"(?i)^how" action)
               "count" (string/lower-case action))
       :question text}
      (cf/fail! clairic-usage))))

(defn- parse-input [model text]
  (let [flag (if (= model "clef") "--questions" "--fields")]
    (if (or (= text flag) (string/starts-with? text (str flag " ")))
      (let [[schema state] (schema! (string/trim (subs text (count flag))))
            _ (when-not (map? schema)
                (cf/fail!
                  "Provide an EDN schema map, not a single type or value."))
            schema (if (= model "clairic")
                     (into {} (map (fn [[k v]]
                                     [k (if (string? v) {:type v} v)]) schema))
                     schema)]
        (when-not (schema-valid? model schema)
          (cf/fail! "Invalid typed schema. See help clef or help clairic."))
        {:schema schema :state state})
      (if (= model "clef")
        (do
          (when (> (count text) 16384)
            (cf/fail! "Clef questions are limited to 16384 characters."))
          {:question text})
        (quick-request text)))))

(defn- arguments [text raw opts]
  ;; Primitive pipe values are appended by core; :raw retains the original.
  ;; Collection/multiline values instead arrive separately in :opts.
  (let [raw-text (when (and (some? raw) (not (coll? raw))) (str raw))]
    (string/trim
      (if (and (seq raw-text) (not (coll? opts)))
        (cond
          (= text raw-text) ""
          (string/ends-with? text (str " " raw-text))
          (subs text 0 (- (count text) (count raw-text) 1))
          :else text)
        text))))

(defn- prepare [model {:keys [raw opts chat-source] :as args}]
  (let [text (or (second (:match args)) "")
        parsed (parse-input model (arguments text raw opts))
        piped? (or (if (string? raw)
                     (not (string/blank? raw))
                     (some? raw))
                   (seq opts))
        input (cond (:demo? parsed) (:state parsed)
                    piped? (if (some? raw) raw opts)
                    :else (:state parsed))
        input (if (and (sequential? input) (every? string? input))
                (string/join "\n" input)
                input)
        text-input (cond (string? input) input
                         (nil? input) ""
                         :else (json/generate-string input))
        _ (when (> (count text-input) 60000)
            (cf/fail! "Inference input is limited to 60000 characters."))
        source (when-not (:demo? parsed)
                 {:raw-event (select-keys (:raw-event chat-source)
                                         [:attachments])})
        strings (if (string? input) [input]
                  (filter string? (tree-seq coll? seq input)))
        question (or (:question parsed) "")
        urls (vec (distinct
                    (concat (:image-urls (image-input/extract-images "" source))
                            (mapcat #(:image-urls
                                      (image-input/extract-images % {}))
                                    (conj (vec strings) question)))))
        _ (when (> (count urls) 4)
            (cf/fail! "At most four images are supported."))
        labels (map-indexed (fn [i url] [url (str "Image " (inc i))]) urls)
        label (fn [text]
                (reduce (fn [value [url reference]]
                          (string/replace value url reference))
                        text (sort-by (comp - count first) labels)))
        labelled (walk/postwalk #(if (string? %) (label %) %) input)
        state (cond
                (and (= model "clef") (coll? input)) labelled
                (string? input) (label text-input)
                (nil? input) ""
                :else (json/generate-string labelled))
        schema (or (:schema parsed)
                   (when-let [mode (:mode parsed)]
                     (quick-fields mode (label question)))
                   {:decision {:type "noul" :instructions (label question)}})]
    (when-not (and (schema-valid? model schema)
                   (or (seq urls) (seq state)))
      (cf/fail!
        "Pipe text/images or attach an image; provide a question/schema."))
    {:urls urls :mode (:mode parsed) :demo? (:demo? parsed)
     :payload {:state state
               (if (= model "clef") :questions :fields) schema}}))

(defn- preview [^String text]
  (if (> (.codePointCount text 0 (.length text)) 160)
    (str (subs text 0 (.offsetByCodePoints text 0 157)) "...")
    text))

(defn- display [result mode]
  (let [lines
        (mapv
          (fn [[id answer]]
            (str (name id) ": "
                 (cond
                   (= "noul" (:type answer))
                   (str "P(yes)=" (:noul answer))
                   (= "choice" (:type answer)) (:choice answer)
                   (= "score" (:type answer)) (:score answer)
                   :else (json/generate-string (:value answer)))
                 (when (and (= mode "rate") (number? (:value answer))) "/10")
                 (when (contains? answer :confidence)
                   (str " (confidence=" (:confidence answer) ")"))))
          (sort-by (comp name key) (:answers result)))
        previews (mapv preview lines)]
    (cond-> previews
      (not= lines previews)
      (conj "Long answers shortened; pipe data for complete results.")
      (:truncated result) (conj "Warning: Cloudflare truncated the input."))))

(defn- run [model args]
  (try
    (execution/sensitive!)
    (let [{:keys [urls payload mode demo?]} (prepare model args)
          {:keys [value error]} (config/get-config map? [:ai])
          config (:intent-router (settings/ai value))
          _ (when error
              (cf/fail! "Configure ai.intent-router account-id/key-file."))
          _ (cf/account-url (:account-id config))
          _ (cf/gateway-headers (:gateway-id config))
          token (try (clef/credential! config)
                  (catch Exception _
                    (cf/fail!
                      "Could not read the configured Cloudflare token.")))
          _ (when (string/includes? (json/generate-string [payload urls]) token)
              (cf/fail! "Inference input contains the configured credential."))
          media (images/images urls)
          payload (cond-> payload (seq media) (assoc :images media))
          result (cf/infer! config token model payload)
          result (cond-> result
                   mode (assoc :request {:mode (if demo? "demo" mode)
                                         :fields (:fields payload)}))]
      {:result/value (cond-> (display result mode)
                       demo?
                       (conj (if (= 3 (get-in result [:answers :count :value]))
                               (str "Demo passed: extracted the known "
                                    "balloon count of 3.")
                               "Demo did not match: expected 3 balloons."))
                       (and mode (seq urls)
                            (= 0 (get-in result [:usage :image_tokens])))
                       (conj "Warning: Clairic reported zero image tokens."))
       :result/data result})
    (catch StackOverflowError _
      {:result/error "Inference input nesting is too deep."})
    (catch Exception e
      {:result/error (if (:ai/error (ex-data e)) (.getMessage e)
                        (str "Structured inference failed. "
                             "Check input and configuration."))})))

(defn clef-cmd
  "clef <yes/no question> # classify piped text or images; returns P(yes)
   clef --questions '<EDN question map>' # use noul, choice or score questions
   echo service is down | clef Is this an outage?
   echo down | clef --questions '{:x {:type :noul :instructions \"Bad?\"}}'
   clef Is this a receipt? https://example.com/receipt.png
   # Images can also be attached. Results include raw data for `data <path>`.
   # Uses ai.intent-router account/token even when disabled. No PR actions."
  {:yb/cat #{:ai}}
  [args] (run "clef" args))

(defn clairic-cmd
  "clairic count <what> [image URL] # count things in piped text or images
   clairic how many <what> [image URL] # same as count
   clairic rate <quality> [image URL] # subjective 0-10 rating, just for fun
   clairic read <what> # extract text from piped source text
   clairic list <what> # extract a list from piped source text
   clairic demo # run a real inference smoke test on built-in text (expects 3)
   clairic count helicopters https://example.com/meme.png
   clairic rate how absurd this commute is https://example.com/meme.png
   echo red balloon, blue balloon | clairic count balloons
   echo callsign TRASH PANDA ONE | clairic read the callsign
   # No schema or second model needed. Image attachments work too.
   # Data: $.answers.count.value, $.answers.rating.value, $.request.fields.
   clairic --fields '<EDN field map>' [text/image URL] # advanced typed fields
   echo total 42 | clairic --fields '{:amount :number}'
   # Read/list extract source text, not invented captions or descriptions.
   # Images: public HTTPS PNG/JPEG/WebP, max 4, 4 MiB each, 8 MiB total."
  {:yb/cat #{:ai}}
  [args] (run "clairic" args))

(cmd-hook #"clef" #"(?s)(.*)" clef-cmd)
(cmd-hook #"clairic" #"(?s)(.*)" clairic-cmd)
