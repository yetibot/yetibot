(ns yetibot.models.ai-proposals
  "Bounded, ephemeral approvals. This namespace performs no GitHub writes."
  (:require [cheshire.core :as json]
            [clojure.string :as string]
            [clojure.walk :as walk]
            [taoensso.timbre :refer [info]]
            [yetibot.api.ai :as ai]
            [yetibot.models.ai-tool-policy :as policy])
  (:import [java.security MessageDigest]
           [java.util UUID]))

(def ttl-ms 600000)
(defonce ^:private proposals (atom {}))
(defn now [] (System/currentTimeMillis))

(defn path-allowed? [t path]
  (and (string? path) (<= 1 (count path) 512)
       (re-matches #"[A-Za-z0-9_./-]+" path)
       (not (string/starts-with? path "/"))
       (every? #(not (contains? #{"" "." ".." ".git" ".env"} %))
               (string/split path #"/" -1))
       (not (re-find #"(?i)(?:^|/)(?:\.env[^/]*|credentials[^/]*|secrets?[^/]*|id_rsa[^/]*)(?:/|$)|\.(?:pem|key)$" path))
       (not (string/starts-with? path ".github/workflows/"))
       (some #(string/starts-with? path %) (:allowed-paths t))
       (not-any? #(string/starts-with? path %) (:denied-paths t))))

(defn- utf8-size [s] (alength (.getBytes ^String s "UTF-8")))
(defn- text? [s] (and (string? s) (not (re-find #"[\x00-\x08\x0b\x0c\x0e-\x1f]" s))))

(defn- fingerprint [p]
  (let [manifest (select-keys p [:actor :provider :model :toolset :owner :repo
                                :base :base-sha :files :originals :title :body])
        canonical (walk/postwalk #(if (map? %) (into (sorted-map) %) %) manifest)
        bytes (.digest (MessageDigest/getInstance "SHA-256")
                       (.getBytes (json/generate-string canonical) "UTF-8"))]
    (apply str (map #(format "%02x" (bit-and 255 %)) bytes))))

(defn- audit! [p]
  (info "ai-proposal" (select-keys p [:id :actor :provider :model :owner :repo
                                     :hash :status :branch :commit :pr-url])))

(defn- diff-file [path before after]
  (let [old-lines (if (nil? before) [] (string/split-lines before))
        new-lines (string/split-lines after)
        lines (fn [prefix xs text]
                (str (string/join "\n" (map #(str prefix %) xs))
                     (when (seq xs) "\n")
                     (when (and (seq xs) (not (string/ends-with? text "\n")))
                       "\\ No newline at end of file\n")))]
    (str "--- " (if (nil? before) "/dev/null" (str "a/" path)) "\n"
         "+++ b/" path "\n@@ -1," (count old-lines) " +1,"
         (count new-lines) " @@\n"
         (lines "-" old-lines before) (lines "+" new-lines after))))

(defn stage! [t actor provider model args snapshot-fn]
  (policy/authorize! t actor provider model)
  (let [{:strs [owner repo base title body files]} args
        files (mapv #(hash-map :path (get % "path") :content (get % "content"))
                    (when (vector? files) files))]
    (when-not (and (= "github-pr" (:kind t)) actor
                   (every? #{"owner" "repo" "base" "title" "body" "files"} (keys args))
                   (some #{(str owner "/" repo)} (:repositories t))
                   (some #{base} (:base-branches t))
                   (ai/nonblank-string? title) (<= (count title) 120)
                   (text? body) (<= (count body) 2000)
                   (<= 1 (count files) 5)
                   (= (count files) (count (distinct (map :path files))))
                   (every? #(and (path-allowed? t (:path %)) (text? (:content %))) files)
                   (<= (reduce + (map #(utf8-size (:content %)) files)) 8192)
                   (<= (count (json/generate-string args)) 16384))
      (ai/fail "Invalid change proposal. Check repository, paths, text and size limits."))
    (let [{:keys [base-sha originals]} (snapshot-fn owner repo base (mapv :path files))
          p {:id (str (UUID/randomUUID)) :actor actor
             :provider (select-keys provider [:name :url]) :model model :toolset t
             :owner owner :repo repo :base base :base-sha base-sha
             :title title :body body :files files :originals originals
             :expires-at (+ (now) ttl-ms) :status :pending}]
      (when-not (and (string? base-sha) (re-matches #"[0-9a-f]{40,64}" base-sha)
                     (every? #(and (contains? originals (:path %))
                                   (or (nil? (get originals (:path %)))
                                       (and (text? (get originals (:path %)))
                                            (<= (utf8-size (get originals (:path %))) 20000)))) files)
                     (some #(not= (:content %) (get originals (:path %))) files))
        (ai/fail "Could not obtain complete source text, or the proposal makes no changes."))
      (let [p (assoc p :hash (fingerprint p)
                     :diff (apply str (map #(diff-file (:path %)
                                                     (get originals (:path %))
                                                     (:content %)) files)))]
        (swap! proposals
               (fn [state]
                 (let [state (into {} (filter #(> (:expires-at (val %)) (now)) state))]
                   (when (>= (count state) 128)
                     (ai/fail "Too many active proposals. Try again after they expire."))
                   (assoc state (:id p) p))))
        (audit! p)
        p))))

(defn review! [id actor]
  (let [p (get @proposals id)]
    (when-not (and p actor (= actor (:actor p)))
      (ai/fail "Proposal is unavailable in this user/conversation context."))
    (when (<= (:expires-at p) (now)) (ai/fail "Proposal expired. Generate a new proposal."))
    (when-not (= (:hash p) (fingerprint p))
      (ai/fail "Proposal integrity check failed."))
    p))

(def page-size 2400)

(defn page-count [p]
  (max 1 (long (Math/ceil (/ (count (:diff p)) (double page-size))))))

(defn preview [p]
  (str "Proposal " (:id p) " (" (name (:status p)) ") for "
       (:owner p) "/" (:repo p) " against " (:base p) " @ " (:base-sha p)
       "\nTitle: " (:title p) "\nBody: " (:body p)
       "\nFiles: " (string/join ", " (map :path (:files p)))
       "\nHash: " (:hash p) "\nExpires: " (:expires-at p)
       "\nNo GitHub writes have been authorized by this preview."
       "\nReview ALL " (page-count p) " diff pages: ai proposal " (:id p) " <page>"
       "\nThen: ai approve " (:id p) " (or ai cancel " (:id p) ")"))

(defn page! [id actor page]
  (locking proposals
    (let [p (review! id actor)
          pages (page-count p)]
      (when-not (and (integer? page) (<= 1 page pages))
        (ai/fail "Invalid proposal page number."))
      (swap! proposals update-in [id :reviewed-pages] (fnil conj #{}) page)
      (str "Proposal " id " diff page " page "/" pages "\n"
           (subs (:diff p) (* (dec page) page-size)
                 (min (count (:diff p)) (* page page-size)))))))

(defn- transition! [id actor target]
  ;; Lock serializes the read/check/transition, not any network operations.
  (locking proposals
    (let [p (review! id actor)]
      (when-not (= :pending (:status p))
        (ai/fail "Proposal is no longer pending; it cannot be approved again."))
      (when (and (= :executing target)
                 (not= (set (range 1 (inc (page-count p)))) (:reviewed-pages p)))
        (ai/fail "Review every diff page with ai proposal before approval."))
      (let [next (assoc p :status target)]
        (swap! proposals assoc id next)
        (audit! next)
        next))))

(defn claim! [id actor] (transition! id actor :executing))
(defn cancel! [id actor]
  (:status (transition! id actor :cancelled)))

(defn finish! [id outcome]
  (let [p (get (swap! proposals update id
                     #(when % (merge % (select-keys outcome
                                                    [:status :branch :commit :pr-url])))) id)]
    (when p (audit! p))
    p))
