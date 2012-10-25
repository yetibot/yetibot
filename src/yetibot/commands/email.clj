(ns yetibot.commands.email
  (:require [postal.core :as postal]
            [yetibot.util.format :as fmt])
  (:use [yetibot.util :only (cmd-hook env ensure-config)]))

(def default-subject "A friendly message from YetiBot")
(def success-message "Sent :email:")
(def error-message "Failed to send :poop:")

(def config {:host (:YETIBOT_EMAIL_HOST env)
             :user (:YETIBOT_EMAIL_USER env)
             :pass (:YETIBOT_EMAIL_PASS env)
             :from (:YETIBOT_EMAIL_FROM env)
             :bcc (:YETIBOT_EMAIL_BCC env)
             :ssl true})

(defn send-email
  "email <to> / <subject> / <body> # send an email
email <to> / <body> # send an email with a friendly default message"
  ([to subject body opts] (send-email to subject body opts (:bcc config)))
  ([to subject body opts bcc]
   (prn "send-email with " to subject body opts bcc)
   (let [res (postal/send-message
               (with-meta
                 {:from (:from config)
                 :to to
                 :bcc bcc
                 :subject subject
                 :body (str body
                            \newline
                            (when (and opts (not= opts body)) (first (fmt/format-data-structure opts))))}
                 config))]
     (if (= "messages sent" (:message res))
       success-message
       error-message))))

(ensure-config
  (cmd-hook #"email"
            #"(.+)\/(.+)\/(.*)" (send-email (nth p 1) (nth p 2) (nth p 3 "") opts)
            #"(.+)\/(.*)" (send-email (nth p 1) default-subject (nth p 2 "") opts)))
