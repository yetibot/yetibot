(ns yetibot.commands.mail
  (:require [postal.core :as postal]
            [yetibot.util.format :as fmt]
            [clojure.string :as s])
  (:use [yetibot.util :only [env ensure-config]]
        [yetibot.hooks :only [cmd-hook]]))

(def default-subject "A friendly message from YetiBot")
(def success-message "Sent :email:")
(def error-message "Failed to send :poop:")

(def config {:host (:YETIBOT_EMAIL_HOST env)
             :user (:YETIBOT_EMAIL_USER env)
             :pass (:YETIBOT_EMAIL_PASS env)
             :from (:YETIBOT_EMAIL_FROM env)
             :bcc (:YETIBOT_EMAIL_BCC env)
             :ssl true})

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
    [{:type "text/html" :content (-> content encode-images encode-newlines)}]))

(defn send-mail
  ([to subject body opts] (send-mail to subject body opts (:bcc config)))
  ([to subject body opts bcc]
   (prn "send-mail with " to subject body opts bcc)
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

(ensure-config
  (cmd-hook #"mail"
            #"(.+) \/ (.+) \/ (.*)" send-body-and-subject
            #"(\S+@\S+) \/ (.+)" send-piped-and-body
            ;;; #"(.+) \/" (send-mail(nth p 1) default-subject "" opts)
            #"(\S+@\S+)( \/)?" send-piped)) ; just the email address
