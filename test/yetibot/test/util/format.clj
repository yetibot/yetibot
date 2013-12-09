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
  (is (= "foo 2" (format-n "foo %1" [2])))
  (is (= (format-n "foo" [2 3 4])
         "foo"))
  (is (= (format-n "list %1 | head" [1])
         "list 1 | head")))

(deftest pseudo-format-n-test
  (let [args ["foo" "bar" "baz"]]
    (is (= (pseudo-format-n "all --> %s <-- there" args)
           "all --> foo bar baz <-- there"))
    (is (= (pseudo-format-n "just the second --> %2 <--" args)
           "just the second --> bar <--"))
    (is (= (pseudo-format-n "append to end -->" args)
           "append to end --> foo bar baz"))))

(deftest pseudo-format-n-with-rebound-prefix
  (binding [*subst-prefix* "\\$"]
    (is (= (pseudo-format-n "foo --> $2 <-- two" [1 2])
           "foo --> 2 <-- two")
        "It should work with a new prefix.")
    (is (= (pseudo-format-n "--> %s <--" [1 2])
           "--> %s <-- 1 2")
        "It shouldn't work with the old prefix after a new one is bound.")))
