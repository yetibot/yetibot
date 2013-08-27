(ns yetibot.test.commands.alias
  (:require
    [yetibot.db]
    [clojure.test :refer :all]
    [yetibot.commands.alias :refer :all]))

(def user {:id 0})

(deftest test-add-alias
  (let [args  ["a = random \\| echo hi"
               "b = echo hi"
               "c = random \\| echo http://foo.com?bust=%s"]]
    (dorun (map #(add-alias {:user user :args %}) args))))


(def f (with-meta #(prn "foo") {:doc (str "foo" 2 2)}))
(meta f)
