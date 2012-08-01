(ns yetibot.test.commands.order
  (:require [yetibot.commands.order :as order])
  (:use [clojure.test]
        [yetibot.core]))

; !order
(deftest start-taking-orders
         (is
           (= (order/start-taking-orders) "Ok, go ahead"))
         (is
           (= (order/get-orders) {})
           "it should clear the orders list"))

; !order panang beef / 3 stars / brown rice
(deftest order-some-food
         (order/take-order "panang beef")
         (is
           (=
             (order/show-order)
             ["panang beef"])))

; order multiple items
(deftest order-multiple-items
         (order/start-taking-orders)
         (order/take-order "apple")
         (order/take-order "orange")
         (order/take-order "banana")
         (is
           (=
             3
             (count (order/get-orders)))))

; !order show
(deftest show-empty-order
         (order/start-taking-orders)
         (is
           (=
             order/empty-order-message
             (order/show-order))))


