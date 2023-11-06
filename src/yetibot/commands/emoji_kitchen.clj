(ns yetibot.commands.emoji-kitchen
  (:require
    [clj-http.client :as client]
    ))

(comment

  ;; fetch an emoji from emoji kitchen

  (defn emoji-kitchen
    "Fetch an emoji from emoji kitchen"
    [msg]
    (let [emoji-1 "🤠"
          emoji-2 "🤢"
          url "https://tenor.googleapis.com/v2/featured?key=AIzaSyACvEq5cnT7AcHpDdj64SE3TJZRhW-iHuo&client_key=emoji_kitchen_funbox&collection=emoji_kitchen_v6"]
      (client/get url
                  {:query-params {:q (str emoji-1 "_" emoji-2)}
                   :as :json})))

  )

(def url
  "https://www.gstatic.com/android/keyboard/emojikitchen/20230127/u1f31e/u1f31e_u1f428.png"
  )
