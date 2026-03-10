(ns yetibot.util.gemini
  "Shared utilities for interacting with the Google Gemini API."
  (:require [clj-http.client :as client]
            [clojure.data.json :as json]
            [clojure.spec.alpha :as s]
            [clojure.string :as string]
            [taoensso.timbre :refer [info warn error]]
            [yetibot.core.config :refer [get-config]]
            [yetibot.db.image-budget :as image-budget])
  (:import [java.time YearMonth]
           [java.time.format DateTimeFormatter]))

(s/def ::key string?)

(s/def ::config (s/keys :req-un [::key]))

(def config (:value (get-config ::config [:gemini :api])))

(def default-model "gemini-2.5-flash-image")

(defn gemini-model []
  (or (:model config) default-model))

(defn configured? []
  (some? config))

;; -- Monthly budget throttling --

(def ^:private default-cost-per-image
  "Default estimated cost per generated image in USD (Gemini 2.5 Flash pricing)."
  0.039)

(def ^:private default-monthly-budget
  "Default monthly budget in USD for image generation."
  5.00)

(defn- cost-per-image []
  (or (:cost-per-image config) default-cost-per-image))

(defn- monthly-budget []
  (or (:monthly-budget config) default-monthly-budget))

(defn- max-images-per-month []
  (long (Math/floor (/ (monthly-budget) (cost-per-image)))))

(defn- current-month []
  (.format (YearMonth/now) (DateTimeFormatter/ofPattern "yyyy-MM")))

(defonce ^:private usage-tracker (atom {:month (current-month) :count 0}))

(defonce ^:private db-loaded? (atom false))

(defn- load-from-db!
  "Load the current month's image count from the database on first access.
   Falls back to in-memory only if the database is unavailable."
  []
  (when-not @db-loaded?
    (try
      (let [month (current-month)
            rows (image-budget/query {:where/map {:month month}})]
        (when-let [row (first rows)]
          (reset! usage-tracker {:month month :count (:image-count row)})
          (info "gemini: loaded image budget from db -"
                (:image-count row) "images for" month))
        (reset! db-loaded? true))
      (catch Exception e
        (warn "gemini: could not load image budget from db, using in-memory only:"
              (.getMessage e))))))

(defn- persist-to-db!
  "Save the current month's usage count to the database."
  [month cnt]
  (try
    (let [existing (first (image-budget/query {:where/map {:month month}}))]
      (if existing
        (image-budget/update-where {:month month} {:image-count cnt})
        (image-budget/create {:month month :image-count cnt})))
    (catch Exception e
      (warn "gemini: could not persist image budget to db:" (.getMessage e)))))

(defn- reset-if-new-month!
  "Reset the usage counter if we've rolled into a new month."
  []
  (load-from-db!)
  (let [month (current-month)]
    (swap! usage-tracker
           (fn [tracker]
             (if (= (:month tracker) month)
               tracker
               (do (info "gemini: resetting monthly image budget counter for" month)
                   {:month month :count 0}))))))

(defn budget-status
  "Return the current monthly budget status for image generation."
  []
  (reset-if-new-month!)
  (let [{:keys [count]} @usage-tracker
        max-imgs (max-images-per-month)
        spent (* count (cost-per-image))
        remaining (- (monthly-budget) spent)]
    {:images-generated count
     :max-images max-imgs
     :spent (double spent)
     :budget (monthly-budget)
     :remaining (double remaining)
     :month (:month @usage-tracker)}))

(defn- check-budget!
  "Throw if the monthly image generation budget has been exhausted."
  []
  (reset-if-new-month!)
  (let [{:keys [count]} @usage-tracker
        max-imgs (max-images-per-month)]
    (when (>= count max-imgs)
      (let [spent (* count (cost-per-image))]
        (throw (ex-info
                (format "Monthly image budget exhausted: %d/%d images ($%.2f/$%.2f). Resets next month."
                        count max-imgs spent (monthly-budget))
                {:type :budget-exceeded
                 :count count
                 :max max-imgs}))))))

(defn- record-image-generated!
  "Record that an image was successfully generated. Persists to db."
  []
  (let [{:keys [count month]} (swap! usage-tracker update :count inc)
        max-imgs (max-images-per-month)]
    (persist-to-db! month count)
    (info (format "gemini: image generated (%d/%d this month, $%.2f/$%.2f)"
                  count max-imgs
                  (* count (cost-per-image))
                  (monthly-budget)))))

(defn- extract-image
  "Extract the first image part from the Gemini API response."
  [response-body]
  (let [parts (get-in response-body [:candidates 0 :content :parts])]
    (some (fn [part]
            (when-let [inline-data (:inlineData part)]
              {:data (:data inline-data)
               :mime-type (:mimeType inline-data)}))
          parts)))

(defn- extract-api-error
  "Extract a human-readable error message from a Gemini API error response body."
  [body]
  (try
    (let [parsed (if (string? body)
                   (json/read-str body :key-fn keyword)
                   body)
          error-obj (:error parsed)
          message (:message error-obj)
          status (:status error-obj)
          code (:code error-obj)]
      (if message
        (str (when code (str "(" code ") "))
             (when status (str status ": "))
             message)
        (str parsed)))
    (catch Exception _
      (if (string? body) body (str body)))))

(defn- extract-block-reason
  "Extract block/filter reason from a Gemini API response that returned no image."
  [response-body]
  (let [block-reason (get-in response-body [:promptFeedback :blockReason])
        finish-reason (get-in response-body [:candidates 0 :finishReason])
        safety-ratings (or (get-in response-body [:promptFeedback :safetyRatings])
                           (get-in response-body [:candidates 0 :safetyRatings]))
        flagged (when safety-ratings
                  (->> safety-ratings
                       (filter #(#{"HIGH" "MEDIUM"} (:probability %)))
                       (map :category)))]
    (cond
      block-reason
      (str "Prompt blocked by Gemini: " block-reason
           (when (seq flagged)
             (str " (flagged categories: " (string/join ", " flagged) ")")))

      (and finish-reason (not= finish-reason "STOP"))
      (str "Generation stopped: " finish-reason
           (when (seq flagged)
             (str " (flagged categories: " (string/join ", " flagged) ")")))

      :else nil)))

(defn generate-image
  "Call the Gemini API to generate an image from a text prompt.
   Accepts an optional system-instruction string for guiding generation style.
   Checks monthly budget before making the API call and records usage on success."
  ([prompt] (generate-image prompt nil))
  ([prompt system-instruction]
   (check-budget!)
   (let [api-key (:key config)
         model (gemini-model)
         url (format
              "https://generativelanguage.googleapis.com/v1beta/models/%s:generateContent?key=%s"
              model api-key)
         body (cond-> {:contents [{:parts [{:text prompt}]}]
                       :generationConfig {:responseModalities ["TEXT" "IMAGE"]}}
                system-instruction
                (assoc :systemInstruction
                       {:parts [{:text system-instruction}]}))
         response (client/post url
                               {:content-type :json
                                :body (json/write-str body)
                                :as :json
                                :throw-exceptions false})
         status (:status response)]
     (when-not (<= 200 status 299)
       (let [error-msg (extract-api-error (:body response))]
         (error "gemini: API error" status "-" error-msg)
         (throw (ex-info (str "Gemini API error: " error-msg)
                         {:type :gemini-api-error
                          :status status}))))
     (if-let [image (extract-image (:body response))]
       (do (record-image-generated!)
           image)
       (let [reason (extract-block-reason (:body response))]
         (throw (ex-info (or reason
                              "No image was generated. Try a different prompt.")
                         {:type :no-image-generated
                          :response-body (:body response)})))))))

(defn yetibot-base-url []
  (or (:value (get-config string? [:url]))
      (:value (get-config string? [:endpoint]))
      "http://localhost:3003"))
