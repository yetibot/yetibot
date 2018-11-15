(ns yetibot.commands.karma.specs
  (:require [clojure.spec.alpha :as s]))

;; testing an idea
(defn mk-subns-alias!
  [sym]
  (alias sym (-> (ns-name *ns*) (str "." sym) symbol create-ns)))

;; Common specs we could relocatexo
(s/def ::name string?)
(s/def ::id string?)
(s/def ::user (s/keys :req-un [::name ::id]))

(s/def ::adapter #{:irc :slack})
(s/def ::chat-source (s/keys :req-un [::adapter]))

(s/def ::ctx (s/keys :req-un [::user ::chat-source]))

;; Karma-specific specs
(s/def ::user-id (s/and string? #(re-matches #"@?\w[-\w]*\w" %)))
(s/def ::action (s/and string? (s/or :positive #(= "++" %)
                                     :negative #(= "--" %))))
(s/def ::note string?)

;; run-time fn data validators

;; get-score
(mk-subns-alias! 'get-score)
(s/def ::get-score/match string?)
(s/def ::get-score-ctx
  (s/merge ::ctx (s/keys :req-un [::get-score/match])))


;; adjust-score
(mk-subns-alias! 'adjust-score)
(s/def ::adjust-score/match (s/and vector?
                                   (s/cat :user-id ::user-id
                                          :action ::action
                                          :note (s/? ::note))))
(s/def ::adjust-score-ctx
  (s/merge ::ctx (s/keys :req-un [::adjust-score/match])))
