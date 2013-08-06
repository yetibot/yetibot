(ns yetibot.handler
  (:require
    [yetibot.util :refer [psuedo-format]]
    [yetibot.models.users :as users]
    [yetibot.campfire :as cf]
    [clojure.string :as s]
    [clojure.stacktrace :as st]))

(defn handle-command
  [cmd args user opts]
  "Receives parsed `cmd` prefix and `args` for commands to hook into. Typically
  `args` will be a string, but it might be a seq when handle-command is called
  from handle-piped-command. All commands hook this fn."
  (println (str "nothing handled command " cmd " with args " args))
  ; default to looking up a random result from google image search instead of
  ; complaining about not knowing stuff.
  (if (find-ns 'yetibot.commands.image-search)
    (handle-command "image" (str cmd " " args) user nil)
    (format "I don't know how to handle %s %s" cmd args)))

(defn parse-cmd-with-args
  [cmd-with-args]
  (let [[cmd args] (s/split cmd-with-args #"\s" 2)
        args (or args "")]
    [cmd args]))

(declare handle-expansion-pass)

(defn parse-and-handle-command
  "Optionally takes 2nd param `user` and 3rd param `opts`"
  [cmd-with-args & rest]
  (let [user (first rest)
        cmd-with-args (handle-expansion-pass cmd-with-args user)
        [cmd args] (parse-cmd-with-args cmd-with-args)
        ; use into on a vector of rest args so the nils don't get prepended
        rest-args (into (vec rest) (take (- 2 (count rest)) (repeat nil)))]
    (apply handle-command (list* cmd (str args) rest-args))))

(defn cmd-reader [& args]
  (parse-and-handle-command (s/join " " args)))

(defn to-coll-if-contains-newlines
  "This might be a bit hack-ish, but it lets us get out of explicitly supporting streams
  in every command that we want it."
  [s]
  (if (and (string? s) (re-find #"\n" s))
    (s/split s #"\n")
    s))

(defn handle-piped-command
  "Parse commands out of piped delimiters and pipe the results of one to the next.
   Whoa; this thing needs some refactoring o_O"
  [body user]
  ; Don't scrub body of *all* !s since we now have a ! command
  (let [cleaned-body (-> body
                         (s/replace #"\!(\w+)" (fn [[_ w]] w))
                         (s/replace #"\!\!" "!"))
        cmds (map s/trim (s/split cleaned-body #" \| "))]
    (prn "cleaned body is" cleaned-body)
    (prn "handle piped cmd " cmds)
    ; cmd-with-args is the unparsed string
    (let [res (reduce (fn [acc cmd-with-args]
                        (let [acc-cmd (str cmd-with-args " " acc)
                              ; split out the cmd and args
                              [cmd args] (parse-cmd-with-args cmd-with-args)
                              possible-coll-acc (to-coll-if-contains-newlines acc)]
                          ; TODO
                          ; acc could be a collection instead of a string. In that case we
                          ; could:
                          ; - take the first item and run the command with that
                          ;   (head)
                          ; - run handle-command for every item in the seq with the
                          ;   assumption that this is the last command in the pipe
                          ;   (xargs)
                          (if-let [acc-coll (or (and (coll? acc) acc)
                                                (and (coll? possible-coll-acc) possible-coll-acc))]
                            ; acc was a collection, so pass the acc as opts instead
                            ; of just concatting it to args.
                            ; This allows the collections commands to deal with them.
                            (parse-and-handle-command (str cmd " " args) user acc-coll)
                            ; otherwise concat args and acc as the new args. args are
                            ; likely empty anyway. (e.g. !urban random | image - the
                            ; args to !image are empty, and acc would be the result
                            ; of !urban random). Send args as opts in this case so
                            ; that regular cmd output can be parsed as opts.
                            (parse-and-handle-command (psuedo-format cmd-with-args acc) user acc))))
                      ""
                      cmds)]
      (println "reduced the answer down to" res)
      res)))

; expand backticks as late as possible
; complete foo | xargs echo %s\n`image %s`
(defn direct-cmd
  "Determine if this cmd is singular or piped and direct it accordingly"
  [body user]
  (cond
    ; piped commands contain pipes
    (re-find #" \| " body) (handle-piped-command body user)
    ; must be a single command
    true (parse-and-handle-command body user)))

; TODO: handle nested sub-expressions / backticks
(defn expand-backticks [body user]
  "Expands backticked sub-expressions (if any)"
  (if-let [ms (re-seq #"(`[^`]+`)+" body)]
    (reduce (fn [acc [_ backticked-cmd]]
              ; unescape the pipes and remove the backticks
              (let [cmd (s/replace (s/replace backticked-cmd #"\`" "") "\\|" "|")
                    cmd-result (direct-cmd cmd user)]
                (s/replace-first acc backticked-cmd cmd-result)))
            body
            ms)
    body))

(defn handle-expansion-pass [body user]
  (-> body (expand-backticks user)))

(defn strip-leading-! [body] (s/replace body #"^\!" ""))

(defn handle-text-message [json]
  "parse a `TextMessage` campfire event into a command and its args"
  (println "handle-text-message")
  (try
    (let [user (users/get-user (:user_id json))
          cmd? (re-find #"^\!" (:body json))
          body (-> (:body json) strip-leading-!)]
      (prn "user is" user)
      (when cmd? (cf/chat-data-structure (direct-cmd body user))))
      (catch Exception ex
        (println "Exception inside `handle-text-message`" ex)
        (st/print-stack-trace (st/root-cause ex) 24)
        (cf/send-message (str ":cop::cop: " ex " :cop::cop:")))))

(defn handle-campfire-event [json]
  (let [event-type (:type json)]
    (condp = event-type ; Handle the various types of messages
      "TextMessage" (handle-text-message json)
      "PasteMessage" (handle-text-message json)
      (println "Unhandled event type: " event-type))))
