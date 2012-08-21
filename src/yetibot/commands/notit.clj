(ns yetibot.commands.notit
	(:require [yetibot.models.users :as users]
						[clojure.set])
  (:use [yetibot.util]))

(def empty-it-message "Nobody has registered as not-it yet.")
(def its (atom #{}))

(defn get-its [] @its)

(defn reset-its 
	"notit reset # resets the current not-it list"
	[] (reset! its #{}))

(defn show-its
  "notit show # show the current list of users registered as not-it"
  []
  (let [ni (get-its)]
    (if (empty? ni)
      empty-it-message
      ni)))

(defn get-real-users [] (remove #{"YetiBot"} (users/get-user-names)))

(defn register-not-it
	"notit # register a user as not-it"
	([user]
		(let [user-name (:name user)
					new-not-its (swap! its conj user-name)
					set-real-users (set (get-real-users))
					potential-its (clojure.set/difference set-real-users new-not-its)]
		(if (= (count potential-its) 1)
			(do 
				(reset-its)
				(str (first potential-its) " is it! Resetting not-its."))
			"Hurry up or you're going to be it!"
			))))

(cmd-hook #"notit"
          #"reset" (reset-its)
          #"show" (show-its)
          #"^$" (register-not-it user))
