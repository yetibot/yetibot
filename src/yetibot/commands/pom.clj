(ns yetibot.commands.pom
  (:require [clojure.string :as s]
            [yetibot.api.github :as gh]
            [clojure.data.xml :as xml]
            [clojure.zip :as zip])
  (:use [yetibot.hooks :only [cmd-hook]]
        [clojure.data.zip.xml :only [attr text xml->]]))

(def tree-art "└──") ;;; "├── "

(defn parse-pom
  "Returns xml-zip'd pom"
  [pom]
  (zip/xml-zip (xml/parse (java.io.StringReader. pom))))

(defn poms-by-path
  "Return a map of path keys and xml-zip'd content values for all found poms in a given repo"
  [repo & [opts]]
  (let [tree-paths (gh/find-paths (gh/tree repo opts) #"pom.xml")]
    (zipmap (map :path tree-paths)
            (->> tree-paths
              (map #(gh/raw repo (:path %) opts))
              (map :body)
              (map parse-pom)))))

(defn sort-pom-list
  "Sort a map of poms by how many slashes appear in its key"
  [poms]
  (sort-by (fn [[k, v]] (count (filter #(= \/ %) k))) poms))

(defn extract-from-pom
  "Looks up poms for a given repo, and passes it to an extractor fn"
  [repo extractor & [opts]]
  (let [poms (-> repo
               (poms-by-path opts)
               sort-pom-list)]
    (extractor poms)))

(defn indent-n
  [n] (s/join "" (repeat (dec n) "    ")))

; expects a tree with first item as key, 2nd and value, and third as children
; ["pom.xml" "2.2.2" [["somedep" "2092"] ["dep2" "12312"]]]
(defn format-tree-data
  ([tree] (format-tree-data tree 1))
  ([tree lvl]
   (let [[k v children] tree]
     (str
       (indent-n lvl) tree-art " " k ": " v
       (when (seq children)
         (str \newline
              (s/join \newline
                      (map #(format-tree-data % (inc lvl)) children))))))))

(defn extract-version
  "A pom can either have its own version or it can specify a parent pom. Look for the
  version first, and if it's blank look for the parent"
  [poms]
  (for [[path, pom] poms]
    (let [version (xml-> pom :version text)
          version-str (if (seq version)
                        (first version)
                        (format "parent/%s"
                                (first (xml-> pom :parent :version text))))]
      [path version-str])))

(defn pom-dep-node
  [pom node]
  (xml-> pom :dependencies :dependency node text))

(defn deps-as-tree-nodes
  [pom]
  (let [dep-fn (partial pom-dep-node pom)
        [group-ids artifact-ids versions] (map dep-fn [:groupId :artifactId :version])
        deps (map vector group-ids artifact-ids versions)]
    (map (fn [[gid aid v]] [(format "%s/%s" gid aid) v]) deps)))

(defn extract-version-and-deps
  [poms]
  (let [with-versions (extract-version poms)
        version-and-pom (map conj with-versions (vals poms))]
    (for [[path version-str pom] version-and-pom]
      (let [deps (deps-as-tree-nodes pom)]
        [path version-str deps]))))

(defn formatted-tree
  [repo fn & [opts]]
  (str repo \newline
       (s/join
         \newline
         (map format-tree-data
              (extract-from-pom repo fn opts)))))

;;; example:
;;; com.foo.api
;;; └── pom.xml: 7.1.7-SNAPSHOT
;;; └── api-client/pom.xml: parent/7.1.7-SNAPSHOT
;;; └── api-ws/pom.xml: parent/7.1.7-SNAPSHOT
(defn poms-for-repo
  "
poms <repo> # extract versions from poms in <repo>
poms <repo> <branch> # extract versions from poms in <repo> for <branch>"
  [{[_ repo branch] :match}]
  (formatted-tree repo extract-version (when branch {:branch branch})))

;;; example:
;;; com.foo.api
;;; └── pom.xml: 7.1.7-SNAPSHOT
;;;     └── com.twitter/util-collection: 5.3.10
(defn deps-for-repo
  "
poms deps <repo> # show pom versions along with its dependencies in <repo>
poms deps <repo> <branch> # show pom versions along with its dependencies in <repo> for <branch>"
  [{[_ repo branch] :match}]
  (formatted-tree repo extract-version-and-deps (when branch {:branch branch})))

(cmd-hook #"poms"
          #"^deps\s+(\S+)$" deps-for-repo
          #"^deps\s+(\S+)\s+(\S+)" deps-for-repo
          #"^(\S+)$" poms-for-repo
          #"(\S+)\s+(\S+)$" poms-for-repo)
