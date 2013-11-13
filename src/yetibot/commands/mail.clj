(ns yetibot.commands.mail
  (:require
    [postal.core :as postal]
    [yetibot.util.format :as fmt]
    [clojure.string :as s]
    [taoensso.timbre :refer [info warn error]]
    [yetibot.models.mail :as model :refer [fetch-unread-mail]]
    [yetibot.config :refer [config-for-ns]]
    [yetibot.hooks :refer [cmd-hook]]))

(def default-subject "A friendly message from YetiBot")
(def success-message "Sent :email:")
(def error-message "Failed to send :poop:")
(def no-messages "No new messages. :soon:")

(def config (merge model/config {:ssl true}))

(defn encode-images [content]
  (s/replace content #"(\S+)(.jpg|.png|.gif)" #(format "<img src='%s'>" (first %))))

(defn encode-newlines [content]
  (s/replace content #"\n" (fn [n] "<br>\n")))

(defn build-body
  "Take body and optional `opts` data structure. Send as HTML emails if jpgs or gifs are detected."
  [body opts]
  (let [content (str (when (not (empty? body)) (str body \newline))
                     (when (and opts (not= opts body))
                       (first (fmt/format-data-structure opts))))]
    [{:type "text/html; charset=utf-8" :content (-> content encode-images encode-newlines)}]))

(defn send-mail
  ([to subject body opts] (send-mail to subject body opts (:bcc config)))
  ([to subject body opts bcc]
   (info "send-mail with " to subject body opts bcc)
   (let [res (postal/send-message
               (with-meta
                 {:from (:from config)
                  :to to
                  :bcc (s/split bcc #",")
                  :subject subject
                  :body (build-body body opts)}
                 config))]
     (if (= "messages sent" (:message res))
       success-message
       error-message))))

(defn send-body-and-subject
  "mail <to> / <subject> / <body> # send an email"
  [{[_ to subject body] :match opts :opts}]
  (send-mail to subject body opts))

(defn send-piped-and-body
  "mail <to> / <body> # mail with <body> and any piped content"
  [{[_ to body] :match opts :opts}]
  (send-mail to default-subject body opts))

(defn send-piped
  "mail <to> # mail with piped content"
  [{[_ to] :match opts :opts}]
  (send-mail to default-subject "" opts))

(defn fetch-cmd
  "mail fetch # fetch unread messages from YetiBot's mail"
  [_] (or (fetch-unread-mail) no-messages))

(if model/configured?
  (cmd-hook #"mail"
            #"fetch" fetch-cmd
            #"(.+) \/ (.+) \/ (.*)" send-body-and-subject
            #"(\S+@\S+) \/ (.+)" send-piped-and-body
            ;;; #"(.+) \/" (send-mail(nth p 1) default-subject "" opts)
            #"(\S+@\S+)( \/)?" send-piped)) ; just the email address
