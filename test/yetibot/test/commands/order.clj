(ns yetibot.test.commands.order
  (:require
    [midje.sweet :refer [fact =>]]
    [yetibot.commands.order :as order]))

(def user {:name "TestBot"})

(fact should-clear-orders-list
  (order/start-taking-orders nil)
  (order/get-orders) => {})

(fact show-food-order
  (order/take-order {:match "panang beef" :user user})
  (order/show-order nil) => {(:name user) "panang beef"})

(fact allow-order-of-multiple-items
  (order/start-taking-orders nil)
  (order/take-order {:user user :match "apple"})
  (order/take-order {:user user :match "orange"})
  (order/take-order {:user user :match "banana"})
  (count (order/get-orders)) => 1)

(fact show-empty-order
  (order/start-taking-orders nil)
  (order/show-order nil) => order/empty-order-message)
