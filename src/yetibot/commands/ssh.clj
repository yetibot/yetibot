(ns yetibot.commands.ssh
  (:require
    [clojure.string :as s]
    [taoensso.timbre :refer [info warn error]]
    [clj-ssh.ssh :refer :all]
    [yetibot.core.hooks :refer [cmd-hook]]
    [yetibot.core.config :refer [config-for-ns conf-valid?]]))

(def ^:private config (config-for-ns))

(def ^:private servers-by-key
  (when (conf-valid?)
    (->>
      (:groups config)
      (mapcat
        (fn [group]
          (let [key-file (:key-file group)
                user (:user group)]
            (map
              (fn [[server-key host]]
                {server-key {:key-file key-file
                             :user user
                             :host host}})
              (:servers group)))))
      (reduce merge))))

(defn list-servers
  "ssh servers # list servers configured for ssh access"
  [_]
  (->> (:groups config)
       (mapcat :servers)
       keys
       (map name)
       sort))

(defn run-command
  "ssh <server> <command> # run a command on <server>"
  [{[_ server-name command] :match}]
  (if-let [config ((keyword server-name) servers-by-key)]
    (let [host (:host config)
          user (:user config)
          key-file (:key-file config)]
      (let [agent (ssh-agent {:use-system-ssh-agent false})]
        (add-identity agent {:private-key-path key-file})
        (let [session
              (session agent host {:strict-host-key-checking :no :username user})]
          (with-connection session
                           (let [result (ssh session {:cmd command})]
                             (or (:out result) (:error result)))))))
    (str "No servers found for " server-name)))

(if (conf-valid?)
  (cmd-hook #"ssh"
            #"^(\w+)\s(.+)" run-command
            #"^servers" list-servers)
  (info "SSH is not configured"))
