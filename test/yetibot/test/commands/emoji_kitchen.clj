(ns yetibot.test.commands.emoji-kitchen
  (:require
    [midje.sweet :refer [fact => =not=> truthy]]
    [midje.repl :refer [load-facts]]
    [yetibot.commands.emoji-kitchen :refer :all]
    [clojure.spec.alpha :as s]))

(fact test-slack-encoding
      (let [raw-event {:source_team "asdf"
                       :event_ts "1703202629.823999"
                       :channel "asdf"
                       :type "message"
                       :ts "asdf"
                       :team "asdf"
                       :client_msg_id "asdf"
                       :blocks [{:type "rich_text"
                                 :block_id "MXmgJ"
                                 :elements
                                 [{:type "rich_text_section"
                                   :elements [{:type "text"
                                               :text "!ek "}
                                              {:type "emoji"
                                               :name "magic_wand"
                                               :unicode "1fa84"}
                                              {:type "text"
                                               :text " "}
                                              {:type "emoji"
                                               :name "potato"
                                               :unicode "1f954"}]}]}]

                       :user_team "asdf"
                       :user "asdf"
                       :suppress_notification false
                       :text "!ek :magic_wand: :potato:"}]
        (extract-slack-emoji raw-event) => ["🪄" "🥔"]))

