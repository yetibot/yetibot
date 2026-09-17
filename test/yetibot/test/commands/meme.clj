(ns yetibot.test.commands.meme
  (:require
   [midje.sweet :refer [facts fact => provided anything]]
   [yetibot.commands.meme :refer :all]
   [yetibot.models.imgflip :as model]))

(facts "about meme command and resolving discord mentions"
       (fact "generate-cmd resolves discord mentions"
             (generate-cmd {:match [nil "drake" "Hello <@123456789>" "and <@!987654321>"]
                            :chat-source {:raw-event {:mentions [{:id "123456789" :username "alice"}
                                                                 {:id "987654321" :username "bob"}]}}})
             => anything
             (provided
               (model/generate-meme-by-query "drake" "Hello @alice" "and @bob") => {:success true :data {:url "http://example.com/meme.png"}}))

       (fact "generate-auto-split-cmd resolves discord mentions"
             (generate-auto-split-cmd {:match [nil "drake" "Hello <@123456789>"]
                                       :chat-source {:raw-event {:mentions [{:id "123456789" :username "alice"}]}}})
             => anything
             (provided
               (model/generate-meme-by-query "drake" "Hello @alice") => {:success true :data {:url "http://example.com/meme.png"}}))

       (fact "rand-generate-cmd resolves discord mentions"
             (rand-generate-cmd {:match [nil "Hello <@123456789>" "and <@!987654321>"]
                                 :chat-source {:raw-event {:mentions [{:id "123456789" :username "alice"}
                                                                      {:id "987654321" :username "bob"}]}}})
             => anything
             (provided
               (model/rand-meme) => "drake"
               (model/generate-meme-by-query "drake" "Hello @alice" "and @bob") => {:success true :data {:url "http://example.com/meme.png"}}))

       (fact "rand-generate-auto-split-cmd resolves discord mentions"
             (rand-generate-auto-split-cmd {:match ["Hello <@123456789>" nil]
                                            :chat-source {:raw-event {:mentions [{:id "123456789" :username "alice"}]}}})
             => anything
             (provided
               (model/rand-meme) => "drake"
               (model/generate-meme-by-query "drake" "Hello @alice") => {:success true :data {:url "http://example.com/meme.png"}}))
       )
