(ns yetibot.models.mail
  (:require [overtone.at-at :refer [at mk-pool every stop show-schedule]]
            [clojure.string :as s]
            [inflections.core :refer [pluralize]]
            [yetibot.config :refer [config-for-ns conf-valid?]]
            [clojure-mail [core :refer :all]
                          [message :as msg]]))

(def config (config-for-ns))
(def configured? (conf-valid?))

(when (conf-valid?)
  (auth! (:user config) (:pass config)))

(def store (gen-store))
(def pool (mk-pool))
(def poll-interval (* 1000 60))
(def inbox "INBOX")

; reading helpers
(defn- clean-newlines [body]
  (s/replace body #"\r\n" "\n"))

(defn- plain-key [m] (first (filter #(re-find #"TEXT/PLAIN" %) (keys m))))
(defn- plain-body [m]
  (let [body (first (:body m))]
    (when body (clean-newlines (body (plain-key body))))))

(defn- read-mail [m] ((juxt :from :subject plain-body) m))

(defn fmt-messages [messages]
  (apply concat (interleave
                  (map read-mail messages)
                  (repeat ["--"]))))

(defn- fmt-you-have-mail [messages]
  (cons (format "You have mail! %s:\n" (pluralize (count messages) "new message"))
        (fmt-messages messages)))

(defn fetch-unread-mail []
  (let [messages (unread-messages inbox)]
    (when-not (empty? messages)
      (mark-all-read inbox)
      (fmt-you-have-mail messages))))

(defn fetch-and-announce-unread-mail []
  (let [formatted-mail (fetch-unread-mail)]
    (when formatted-mail
      (yetibot.chat/chat-data-structure formatted-mail))))

; poll for new messages
(defonce initial
  (future (every poll-interval fetch-and-announce-unread-mail pool
                 :desc "Fetch email"
                 :initial-delay 0)))
