(ns yetibot.test.commands.wolfram
  (:require
    [yetibot.commands.wolfram :refer :all]
    [midje.sweet :refer [facts fact =>]]
    [midje.checkers :refer [contains]]))

(let [subpod-template {:title ""
                       :img {:src "www.example.com/img.jpg"
                             :alt "example image"}
                       :plaintext "text"}
      subpod-with-text-1 (assoc subpod-template :plaintext "text1")
      subpod-with-text-2 (assoc subpod-template :plaintext "text2")
      subpod-without-text (assoc subpod-template :plaintext "")
      pod {:title "Title"
           :subpods [subpod-with-text-1
                     subpod-with-text-2
                     subpod-without-text]}] 
  (facts subpost-rendering
    (fact plaintext-if-present
      (plaintext-or-image subpod-with-text-1) => "text1"
      (plaintext-or-image subpod-without-text) => "www.example.com/img.jpg"))
  
  (facts render-data-string
    (fact all-data-is-present
      (data-string pod) => (and (contains "text1")
                                (contains "text2")
                                (contains "www.example.com/img.jpg")))
    (fact title-is-present
      (data-string pod) => (contains "Title"))))
