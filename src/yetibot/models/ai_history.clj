(ns yetibot.models.ai-history
  "Bounded read-only queries. Scope is supplied only by the trusted host."
  (:require [cheshire.core :as json]
            [clojure.java.jdbc :as jdbc]
            [clojure.string :as string]
            [yetibot.core.db.util :as db]
            [yetibot.core.models.history :as history]))

(defn- query! [sql]
  (let [url (:url (db/config))
        ;; Retain java.jdbc's URI handling of user-info and driver options.
        connection (str url (if (string/includes? url "?") "&" "?")
                        "loginTimeout=2&socketTimeout=3")]
    (jdbc/with-db-connection [conn connection]
      (jdbc/with-db-transaction [tx conn {:read-only? true}]
        (jdbc/execute! tx ["SET LOCAL statement_timeout = '2000ms'"])
        (jdbc/query tx sql {:timeout 2
                            :identifiers #(string/replace % "_" "-")})))))

(defn- scoped-query [source args]
  (let [adapter (pr-str (:uuid source))
        room (:room source)
        table (db/qualified-table-name "history")
        query (get args "query")
        before (get args "before_id")]
    {:table table
     :clause
     (str " WHERE chat_source_adapter = ? AND chat_source_room = ?"
          (when query " AND position(lower(?) in lower(body)) > 0")
          (when before
            (str " AND (created_at, id) < (SELECT created_at, id FROM " table
                 " WHERE id = ? AND chat_source_adapter = ?"
                 " AND chat_source_room = ?)")))
     :params (cond-> [adapter room]
               query (conj query)
               before (into [before adapter room]))}))

(defn- page-result [rows selected limit]
  (let [more? (> (count rows) (count selected))
        messages (mapv #(update % :created-at str) (reverse selected))]
    {:untrusted true :scope "current-channel"
     :messages messages :has_more more?
     :next_before (when more? (:id (last selected)))
     :truncated (boolean (or more? (some :body-truncated selected)
                             (some :user-name-truncated selected)))
     :limit limit}))

(defn- page [source args]
  (let [{:keys [table clause params]} (scoped-query source args)
        limit (get args "limit" 10)
        rows (query!
               (into [(str "SELECT id, created_at, left(user_name, 100)"
                           " AS user_name, left(body, 1000) AS body,"
                           " coalesce(length(body) > 1000, false)"
                           " AS body_truncated,"
                           " coalesce(length(user_name) > 100, false)"
                           " AS user_name_truncated FROM " table clause
                           " ORDER BY created_at DESC, id DESC LIMIT ?")]
                     (conj params (inc limit))))]
    ;; Keep the newest prefix and its real cursor, even when JSON escaping
    ;; consumes the response budget. Never fetch full message bodies first.
    (loop [selected [] remaining (take limit rows)]
      (let [candidate (conj selected (first remaining))]
        (if (and (seq remaining)
                 (<= (count (json/generate-string
                              (page-result rows candidate limit))) 12000))
          (recur candidate (rest remaining))
          (page-result rows selected limit))))))

(defn read!
  "Arguments are validated for the whole provider batch before execution."
  [source operation args]
  (if-not (history/history-enabled)
    {:isError true :structuredContent {:error "History is disabled."}}
    (try
      {:structuredContent
       (if (= operation "history_count")
         (let [{:keys [table clause params]} (scoped-query source args)]
           {:untrusted true :scope "current-channel"
            :count (:count (first (query!
                                   (into [(str "SELECT count(*) AS count FROM "
                                               table clause)] params))))})
         (page source args))}
      (catch java.sql.SQLException _
        {:isError true
         :structuredContent {:error "History query failed or timed out."}}))))
