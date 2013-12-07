(ns yetibot.test.util.format
  (:require [yetibot.util.format :refer :all]
            [clojure.test :refer :all]))


(def nested-list
  [["meme generator"
    "http://assets.diylol.com/hfs/de6/c7c/061/resized/mi-bok-meme-generator-i-look-both-ways-before-crossing-the-street-at-the-same-time-0dd824.jpg"]
   ["meme maker"
    "http://cdn9.staztic.com/app/a/128/128867/meme-maker-27-0-s-307x512.jpg"]
   ["meme creator"
    "http://img-ipad.lisisoft.com/img/2/9/2974-1-meme-creator-pro-caption-memes.jpg"]])

(def formatted-list (format-data-structure nested-list))

(deftest format-nested-list
  (let [[formatted flattened] formatted-list]
    (is
      (not-any? coll? flattened)
      "the flattened representation should not contain collections")))

(deftest format-n-test
  (is (= "foo 2" (format-n "foo %1" 2)))
  (is (= (format-n "foo" 2 3 4)))
  (format-n "list %1 | head" 1))
