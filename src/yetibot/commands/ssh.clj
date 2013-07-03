(ns yetibot.commands.ssh
  (:require [clojure.string :as s]
            [clj-ssh.ssh :refer :all]
            [yetibot.hooks :refer [cmd-hook]]
            [yetibot.util :refer [env]]))


; Build a map of servers from ENV-config like:
; {:host1 {:host "some-host"
;          :key-path "/path/to/key"
;          :user "admin"}}
(def server-groups (s/split (:SSH_GROUPS env) #","))

(defn extract-hostname [server-key]
  (let [n (name server-key)]
    (keyword (last (re-find #"SSH_(\w+)" n)))))

(defn find-servers
  "given a server group (prefix), returns a map of {:hostname host}"
  [prefix]
  (let [server-pattern (re-pattern (str "^SSH_" prefix))
        servers (select-keys env
                  (filter #(re-find server-pattern (name %)) (keys env)))]
    (zipmap (map extract-hostname (keys servers)) (vals servers))))

(defn config-server-group
  "returns fully configred list of servers for this group"
  [group]
  (let [servers (find-servers group)
        user ((keyword (str "SSH_USER_" group)) env)
        key-path ((keyword (str "SSH_PRIVATE_KEY_" group)) env)]
    (map (fn [[server-key host]]
           {server-key {:host host
                        :user user
                        :key-path key-path}})
           servers)))

(def servers
  (apply merge (flatten (map config-server-group server-groups))))

(defn list-servers
  "ssh servers # list servers configured for ssh access"
  [_] (sort (map name (keys servers))))

(defn run-command
  "ssh <server> <command> # run a command on <server>"
  [{[_ server-name command] :match}]
  (if-let [config ((keyword server-name) servers)]
    (let [host (:host config)
          user (:user config)
          key-path (:key-path config)]
      (println "Running" command "on" host)
      (let [agent (ssh-agent {:use-system-ssh-agent false})]
        (add-identity agent {:private-key-path key-path})
        (let [session
               (session agent host {:strict-host-key-checking :no :username user})]
          (with-connection session
                           (let [result (ssh session {:cmd command})]
                             (or (:out result) (:error result)))))))
    (str "No servers found for " server-name)))

(cmd-hook #"ssh"
          #"^(\w+)\s(.+)" run-command
          #"^servers" list-servers)
