(ns yetibot.test.commands.config
  (:require
    [yetibot.config :as config]
    [yetibot.commands.config :refer :all]
    [clojure.test :refer :all]))

(def test-config {:yetibot
                  {:quod {:libet "light"}
                   :foo {:bar "baz"}}})

(defn wrap [f]
  ; temporarily set a custom config to test under
  (def old-config (deref #'config/config))
  (reset! @#'config/config test-config)
  (f)
  (reset! @#'config/config old-config))

(use-fixtures :once wrap)

(deftest test-config-lookup
  (is (= "light" (lookup-config {:match "yetibot quod libet"})))
  (is (= "baz" (lookup-config {:match "foo bar"}))))
