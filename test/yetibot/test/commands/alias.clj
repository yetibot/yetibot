(ns yetibot.test.commands.alias
  (:require
    [yetibot.db :as db]
    [yetibot.util :refer [with-fresh-db]]
    [clojure.test :refer :all]
    [clojure.string :refer [split]]
    [yetibot.commands.alias :refer :all]))

(def user {:id "foobar"})

(defn start-db [f]
  (db/start)
  (f))

(use-fixtures :once start-db)

(deftest split-alias-with-spaces
  (split "can we do stuff" #" " 2)
  (split "can" #" " 2)
  )

(deftest test-add-alias
  (with-fresh-db
    (let [args ["a = random \\| echo hi"
                "b = echo hi"
                "c = random \\| echo http://foo.com?bust=%s"]]
      (dorun (map #(add-alias {:user user :args %}) args)))))
