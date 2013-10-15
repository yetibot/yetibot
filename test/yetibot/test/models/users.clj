(ns yetibot.test.models.users
  (:require [yetibot.models.users :refer :all]
            [clojure.test :refer :all]))

(def chat-source "test")
(def user-id 0)
(def username "devth")

(defn setup-user [f]
  (add-user chat-source (create-user username {:id user-id}))
  (f)
  (remove-user chat-source user-id))

(use-fixtures :once setup-user)

(deftest create-and-add
  (is (= 1 (count (get-users chat-source))))
  (is (= username (:username (get-user chat-source user-id)))))

(deftest update
  (is (= nil (:age (get-user chat-source user-id))) "Age should not exist yet")
  (update-user chat-source user-id {:age 42})
  (is (= 42 (:age (get-user chat-source user-id))) "Age should be set to 42"))
