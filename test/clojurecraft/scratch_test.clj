(ns clojurecraft.scratch-test
  "An executor's scratch attributes (its `scratch` map) are private to its namespace: no other
   source namespace uses one, each has a spec, and none is also in the information model."
  (:require [clojure.java.io :as io]
            [clojure.spec.alpha :as s]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [clojurecraft.model :as model]
            [clojurecraft.spec]))

(defn source-files
  "Every .clj file under src/clojurecraft."
  []
  (filter #(str/ends-with? (.getName ^java.io.File %) ".clj")
          (file-seq (io/file "src/clojurecraft"))))

(defn ns-of
  "The namespace symbol a source file declares, from its path."
  [^java.io.File f]
  (-> (.getPath f)
      (str/replace #"^src/" "")
      (str/replace #"\.clj$" "")
      (str/replace "/" ".")
      (str/replace "_" "-")
      symbol))

(defn scratches
  "{namespace-symbol scratch-map} of every source namespace that declares a `scratch` var."
  []
  (into {}
        (for [f (source-files)
              :let [n (ns-of f)
                    _ (require n)
                    v (ns-resolve n 'scratch)]
              :when v]
          [n @v])))

(defn uses
  "The namespaces whose source mentions attribute a, or destructures its name from :intent/keys."
  [a]
  (let [mention (re-pattern (str (java.util.regex.Pattern/quote (str a)) "(?![a-z0-9?*!<>=-])"))
        destructured (re-pattern (str ":intent/keys\\s*\\[[^\\]]*(?<![a-z0-9?*!<>=-])"
                                      (java.util.regex.Pattern/quote (name a))
                                      "(?![a-z0-9?*!<>=-])"))]
    (for [f (source-files)
          :let [t (slurp f)]
          :when (or (re-find mention t) (re-find destructured t))]
      (ns-of f))))

(deftest scratch-attributes-are-private-specced-and-not-in-the-model
  (let [all (scratches)]
    (is (seq all) "the scan found executors that declare scratch")
    (doseq [[owner m] all
            a (keys m)]
      (is (= [owner] (vec (distinct (uses a)))) (str a " is used only by " owner))
      (is (s/get-spec a) (str a " has a spec"))
      (is (nil? (model/by-attribute a)) (str a " is not world information")))))
