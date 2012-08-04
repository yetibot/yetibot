(ns yetibot.test.models.help
  (:use [yetibot.models.help])
  (:use [clojure.test]))

(defn add-some-docs []
  (add-docs
    "git commit"
    '("-a # all"
      "--amend # amend previous commit"
      "-m <msg>"
      "--interactive")))

(deftest add-and-retrieve-docs
         (add-some-docs) ; setup
         (is
           (not (empty? (get-docs-for "git commit")))
           "retrieve previously added docs for 'git commit'"))
