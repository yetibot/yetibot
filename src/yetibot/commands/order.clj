(ns yetibot.commands.order
  (:require [clojure.string :as s])
  (:use [yetibot.hooks :only [cmd-hook]]))

(def empty-order-message "You haven't ordered anything yet.")

(def orders (atom {}))

(defn get-orders [] @orders)

(defn reset-orders [] (reset! orders {}))

(defn start-taking-orders
  "order reset # reset the orders list"
  [_]
  (reset-orders)
  "Orders reset")

(defn show-order
  "order show # show the current order"
  [_]
  (let [os (get-orders)]
    (if (empty? os)
      empty-order-message
      os)))

(defn take-order
  "order <food> # add (or replace) your food for the current order"
  [{:keys [match user]}]
  (swap! orders conj {(:name user) match})
  (show-order nil))

(defn order-for
  "order for <person>: <food> # order <food> for someone other than yourself"
  [{[_ person food] :match}]
  (take-order {:match food :user {:name person}}))

(cmd-hook #"order"
          #"reset" start-taking-orders
          #"show" show-order
          #"for\s(.+):(.+)" order-for
          #".+" take-order)
