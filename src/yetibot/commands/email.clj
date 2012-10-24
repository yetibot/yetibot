(ns yetibot.commands.email
  (:require [postal.core :as postal]
            [yetibot.util.format :as fmt])
  (:use [yetibot.util :only (cmd-hook env ensure-config)]))

(def default-subject "A friendly message from YetiBot")

(def config {:host (:YETIBOT_EMAIL_HOST env)
             :user (:YETIBOT_EMAIL_USER env)
             :pass (:YETIBOT_EMAIL_PASS env)
             :from (:YETIBOT_EMAIL_FROM env)
             :bcc (:YETIBOT_EMAIL_BCC env)
             :ssl true})

(defn send-email
  "email <to> / <subject> / <body> # send an email
email <to> / <body> # send an email with a friendly default message"
  [to subject body opts]
  (prn "send-email with " to subject body opts)
  (let [res (postal/send-message
              (with-meta
                {:from (:from config)
                 :to to
                 :subject subject
                 :body (str body (when opts (first (fmt/format-data-structure opts))))}
                config))]
    (:message res)))

(ensure-config
  (cmd-hook #"email"
            #"(.+)\/(.+)\/(.*)" (send-email (nth p 1) (nth p 2) (nth p 3 "") opts)
            #"(.+)\/(.*)" (send-email (nth p 1) default-subject (nth p 2 "") opts)))
