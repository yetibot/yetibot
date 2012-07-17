(ns yetibot.commands.ssh
  (:require [clojure.string :as s])
  (:use [clj-ssh.ssh]
        [yetibot.util]))


; Pull properties out of config
(def private-key-path (System/getenv "SSH_PRIVATE_KEY_PATH"))
(def username (System/getenv "SSH_USERNAME"))
(def servers (s/split (System/getenv "SSH_SERVERS") #","))
(def servers-by-name (into {} (for [k servers] [(keyword k) (System/getenv (str "SSH_" k))])))

(defn list-servers
  "ssh servers # list servers configured for ssh access"
  [] servers)

(defn run-command
  "ssh <server> <command> # run a command on <server>"
  [server-name command]
  (let [host ((keyword server-name) servers-by-name)]
    (println (str "Running " command " on " host))
    (let [agent (ssh-agent {:use-system-ssh-agent false})]
      (add-identity agent {:private-key-path private-key-path})
      (let [session
            (session agent host {:strict-host-key-checking :no :username username})]
        (with-connection session
                         (let [result (ssh session {:cmd command})]
                           (:out result)))))))

(cmd-hook #"ssh"
          #"^(\w+)\s(.+)" (run-command (nth p 1) (nth p 2))
          #"^servers" (list-servers))
