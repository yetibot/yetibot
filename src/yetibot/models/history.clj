(ns yetibot.models.history
  (:require [yetibot.models.users :as u]
            [datomico.core :as dc]
            [datomico.db :refer [q]]
            [datomico.action :refer [all where raw-where]]))

;;;; schema

(def model-namespace :history)

(def schema (dc/build-schema model-namespace
                             [[:user-id :string]
                              [:body :string]]))

(dc/create-model-fns model-namespace)

;;;; read

(defn- history
  "retrieve all history and sort it by transaction instant"
  [] (->>
       (q '[:find ?user-id ?body ?txInstant
            :where
            [?tx :db/txInstant ?txInstant]
            [?i :history/user-id ?user-id ?tx]
            [?i :history/body ?body ?tx]])
       (sort-by (fn [[_ _ inst]] inst))))

(defn items-with-user [chat-source]
  "Retrieve a map of user to chat body"
  (let [hist (history)]
    (for [[user-id body] hist]
      {:user (u/get-user chat-source user-id) :body body})))

(defn fmt-items-with-user [chat-source]
  "Format map of user to chat body as a string"
  (for [m (items-with-user chat-source)]
    (str (-> m :user :name) ": " (:body m))))

(defn items-for-user [{:keys [chat-source user]}]
  (filter #(= (-> % :user :id) (:id user)) (items-with-user chat-source)))

;;;; write

(defn add [{:keys [user-id body] :as history-item}]
  (create history-item))
