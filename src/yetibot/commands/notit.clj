(ns yetibot.commands.notit
  (:require [yetibot.models.users :as users]
            [clojure.set])
  (:use [yetibot.hooks :only [cmd-hook]]))

; Users who have registered as not-it
(def not-its (atom #{}))

; Users who could potentially be it, i.e. all active human users
(def candidate-its (atom #{}))

(defn get-not-its [] @not-its)

(defn do-reset
  []
  (do
    (reset! not-its #{})
    (reset! candidate-its (set (map :name (users/get-active-humans))))))

(defn reset-its
  "notit reset # resets the current not-it list"
  [_]
  (do
    (do-reset)
    (format "%s: get ready, you could be it!" (clojure.string/join ", " @candidate-its ))))

(defn show-its
  "notit show # show the current list of users registered as not-it"
  [_]
  (let [ni (get-not-its)]
    (if (empty? ni)
      "Nobody has called not-it yet"
      ni)))

(defn register-not-it
  "notit # register a user as not-it"
  [{:keys [user]}]
  (let [user-name (:name user)]
    (if (contains? candidate-its user-name)
      (let [new-not-its (swap! not-its conj user-name)
            potential-its (clojure.set/difference candidate-its new-not-its)]
        (if (= (count potential-its) 1)
          (do
            (reset-its)
            (str (first potential-its) " is it! Resetting not-its."))
          (format "The following people need to hurry up: %s" (clojure.string/join ", " potential-its))))
      (str user-name ": you've been inactive for awhile so you're not eligible to be it."))))

; disabled until it gets fixed
; (cmd-hook #"notit"
;           #"reset" reset-its
;           #"show" show-its
;           #"^$" register-not-it)
