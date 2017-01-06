(ns yetibot.test.models.imgflip
  (:require
    [clojure.test :refer :all]
    [yetibot.models.imgflip :refer :all]))

(def ms (memes))

(def not-nil? (complement nil?))

(when configured?
  (deftest config-test
    (is (not (nil? config))))

  (deftest memes-list
    (is (:success ms))
    (is (pos? (-> ms :data :memes count))))

  (deftest search-memes-test
    (is (= "Ancient Aliens" (:name (first (search-memes "alien"))))))

  (deftest generate-meme-test
    (let [m (generate-meme "61579" "foo" "bar")]
      (is (:success m))
      (is (not-nil? (:data m)))))

  (deftest generate-meme-by-query-test
    (is (:success (generate-meme-by-query "simply" "foo" "bar")))
    (is (:success (generate-meme-by-query "simply" "foo bar"))))

  (deftest generate-meme-notfound-handling
    (let [m (generate-meme-by-query "notfound" "foo")]
      (is (not (:success m)) "it should not be successful")
      (is (:error_message m) "it should have an error message"))))
