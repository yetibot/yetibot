(ns yetibot.test.commands.release
  (:require [clojure.string :as string]
            [midje.sweet :refer [fact =>]]
            [yetibot.commands.release :as command]
            [yetibot.models.release-info :as releases]))

(fact "version gives structured and human-readable app/core identities"
  (let [result (command/version-cmd {})]
    (get-in result [:result/data :build]) => (:build releases/running)
    (string/includes? (:result/value result) "Yetibot:") => true
    (string/includes? (:result/value result) "Core:") => true))

(fact "changelog defaults to the deployed build and has useful command examples"
  (let [result (command/changelog-cmd {:match ["" ""]})]
    (get-in result [:result/data :view]) => "running"
    (string/includes? (:result/value result) "included in this running build")
    => true
    (string/includes? (:result/value result) "!version") => true))

(fact "latest/date/limit are passed as bounded typed options"
  (let [seen (atom nil)]
    (with-redefs [releases/info
                  (fn [options] (reset! seen options)
                    (assoc releases/running :view "latest"
                           :running_build (:build releases/running)))]
      (let [result (command/changelog-cmd
                     {:match ["" "latest --since 2026-10-31 --limit 2"]})]
        @seen => {"view" "latest" "since" "2026-10-31" "limit" 2}
        (string/includes? (:result/value result)
                          "not proof of what's installed") => true))))

(fact "unknown or invalid flags never become network destinations"
  (doseq [options ["--url https://elsewhere.invalid" "surprise"
                   "--limit 0" "--limit NaN" "--since 2026-02-30"]]
    (let [skip (atom 0)]
      (with-redefs [releases/info
                    (fn [_] (throw (AssertionError. "No query")))]
        (let [result (command/changelog-cmd
                       {:match ["" options] :skip-next-n skip
                        :next-cmds [:a :b]})]
          (get-in result [:result/data :isError]) => true
          @skip => 2)))))
