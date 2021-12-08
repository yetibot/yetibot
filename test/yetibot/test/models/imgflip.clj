(ns yetibot.test.models.imgflip
  (:require
    [midje.sweet :refer [fact => =not=>]]
    [yetibot.models.imgflip :refer :all]))

(def ms (memes))

(def not-nil? (complement nil?))

(when configured?

  (fact "it can parse ids from hrefs"
        (parse-id-from-href "/memetemplate/170703314/jocko-eyes") => "170703314"
        (parse-id-from-href "/meme/238477572/Jocko-Willink") => "238477572")

  (fact config-test
    config =not=> nil?)

  (fact memes-list
    (:success ms) => true
    (-> ms :data :memes count) => pos?)

  (fact search-memes-test
    (:name (first (search-memes "alien"))) => "Ancient Aliens")

  (search-memes "jocko")

  (fact (scrape-all-memes "icahn" 2) => not-empty)

  (fact generate-meme-test
    (let [m (generate-meme "61579" "foo" "bar")]
      (:success m) => true
      (:data m)) =not=> nil?)

  (fact generate-meme-by-query-test
    (:success (generate-meme-by-query "simply" "foo" "bar")) => true
    (:success (generate-meme-by-query "simply" "foo bar")) => true)

  (fact generate-meme-notfound-handling
    (let [m (generate-meme-by-query "notfound" "foo")]
      (:success m) => false
      (:error_message m) => "Couldn't find any memes for notfound")))
