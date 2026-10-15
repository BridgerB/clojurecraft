(ns clojurecraft.issues-test
  "The snapshots a fix's check judges (issue #77), kept under test/issues/<n>/ (bb issue-check
   fetch): only what can be held of them without playing one. Judging is not a test here: the
   pull request's check (issue.yml) judges the issues it names with `clojure -M:simrace --judge`,
   uninstrumented, on the base code (each moment must reproduce its failure) and on the PR's
   (none may), each over its window from a moment kept before the choice that failed
   (simrace/judged). Older issues are not judged again on every pull request: the races are the
   regression guard, and the filer reopens an issue whose failure comes back on newer code."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]))

(def root "test/issues")
(def max-file-bytes (* 100 1024 1024)) ; GitHub refuses a push with a file over 100 MiB
(def max-per-issue 2)                  ; snapshots an issue keeps: two moments, from two worlds

(defn issues
  "{issue-number [snapshot-file-name ...]} for every issue directory."
  []
  (into {}
        (for [^java.io.File d (or (.listFiles (io/file root)) [])
              :when (and (.isDirectory d) (re-matches #"\d+" (.getName d)))]
          [(parse-long (.getName d))
           (sort (filter #(str/ends-with? % ".edn.gz")
                         (map #(.getName ^java.io.File %) (.listFiles d))))])))

(defn too-big
  "Every file under dir larger than max-file-bytes: what GitHub would refuse to take."
  [dir]
  (map str
       (filter #(and (.isFile ^java.io.File %) (> (.length ^java.io.File %) max-file-bytes))
               (file-seq (io/file dir)))))

(deftest every-snapshot-fits-on-github (is (empty? (too-big root))))

(deftest an-issue-keeps-two-snapshots-at-most
  (is (empty? (filter #(< max-per-issue (count (val %))) (issues)))))
