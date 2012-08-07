(ns yetibot.commands.order
  (:require [clojure.string :as s])
  (:use [yetibot.util]))

(def empty-order-message "You haven't ordered anything yet.")

(def orders (atom {}))

(defn get-orders [] @orders)

(defn reset-orders [] (reset! orders {}))

(defn start-taking-orders
  "order # reset the orders list and start collecting everyone's order"
  []
  (reset-orders)
  "Ok, go ahead")

(defn take-order
  "order <food> # add (or replace) your food for the current order"
  ([food] (take-order food nil))
  ([food user]
   ; use rand-int as key  placeholder until we can tell yetibot who issued the command
   (swap! orders conj {(:name user) food})
   "Got it."))

(defn show-order
  "order show # show the current order"
  []
  (let [os (get-orders)]
    (if (empty? os)
      empty-order-message
      os)))

(cmd-hook #"order"
          #"^$" (start-taking-orders)
          #"show" (show-order)
          #".+" (take-order p user))
