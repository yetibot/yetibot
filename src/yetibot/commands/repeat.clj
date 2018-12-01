(ns yetibot.commands.repeat
  (:require
    [taoensso.timbre :refer [info warn error]]
    [yetibot.core.handler :refer [record-and-run-raw]]
    [yetibot.core.interpreter :refer [handle-cmd]]
    [yetibot.core.hooks :refer [cmd-hook]]))

(def max-repeat 10)

(defn repeat-cmd
  "repeat <n> <cmd> # repeat <cmd> <n> times"
  {:yb/cat #{:util}}
  [{[_ n cmd] :match user :user opts :opts chat-source :chat-source}]
  (let [n (read-string n)]
    (when (> n max-repeat)
      (yetibot.core.chat/chat-data-structure
        (format "LOL %s 🐴🐴 You can only repeat %s times 😇"
                (:name user)
                max-repeat)))
    (info "repeat-cmd" {:n n :cmd cmd})
    (let [n (min max-repeat n)
          results (repeatedly
                    n
                    ;; We should use record-and-run-raw here, but that doesn't
                    ;; allow us to pre-populate :opts, which is important for
                    ;; use cases like:
                    ;;
                    ;; !range 10 | repeat 5 random
                    ;;
                    ;; I wonder if a parse tree could express pre-populated args
                    ;; somehow 🤔
                    #(handle-cmd cmd {:chat-source chat-source
                                      :user user :opts opts}))]
      ;; flatten out the results
      (info (pr-str results))
      (map (fn [{:result/keys [value error] :as arg}]
             ;; some commands return {:result/value :result/data} structures
             ;; others return an error like {:result/error}
             ;; others just return a plain value
             ;; so look for all 3 forms
             (or error value arg))
           results))))

;; #(record-and-run-raw
;;    cmd
;;    user yetibot-user
;;    {:record-yetibot-response? false})

(cmd-hook #"repeat"
          #"(\d+)\s(.+)" repeat-cmd)

