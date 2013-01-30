(ns yetibot.test.commands.order
  (:require [yetibot.commands.order :as order])
  (:use [clojure.test]
        [yetibot.core]))

(def user {:name "TestBot"})

; !order
(deftest start-taking-orders
         (order/start-taking-orders nil)
         (is
           (= (order/get-orders) {})
           "it should clear the orders list"))

; !order panang beef / 3 stars / brown rice
(deftest order-some-food
         (order/take-order {:match "panang beef" :user user})
         (is
           (=
             (order/show-order nil)
             {(:name user) "panang beef"})
           "it should have the panang beef I ordered"))

; order multiple items
(deftest order-multiple-items
         (order/start-taking-orders nil)
         (order/take-order {:user user :match "apple"})
         (order/take-order {:user user :match "orange"})
         (order/take-order {:user user :match "banana"})
         (is
           (=
             1
             (count (order/get-orders)))
           "it should replace the order for current user each time"))

; !order show
(deftest show-empty-order
         (order/start-taking-orders nil)
         (is
           (=
             order/empty-order-message
             (order/show-order nil))
           "it should report the empty order message if empty"))
