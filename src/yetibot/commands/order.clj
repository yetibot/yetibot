(ns yetibot.commands.order
  (:require
    [clojure.string :as s]
    [yetibot.core.hooks :refer [cmd-hook]]))

(def empty-order-message "You haven't ordered anything yet.")

(defonce orders (atom {}))

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

(defn remove-item
  "remove <user> # remove order for <user>"
  [{[_ name-key] :match}]
  (swap! orders dissoc name-key)
  (show-order nil))

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
          #"remove\s+(.+)" remove-item
          #"show" show-order
          #"for\s(.+):(.+)" order-for
          #".+" take-order
          _ show-order)
