(ns clojurecraft.evidence-test
  "The evidence an issue names (bb churn, bb failures, bb issue-check): a reproduced run's
   snapshot goes to the hour's release under a name no other lane or run has, a run whose upload
   failed is not filed, the comment's line names the release, and fetch reads that line and the
   older artifact one back. And the order a real batch folds its recordings in. Babashka runs
   these (bb test:bb)."
  (:require [clojure.test :refer [deftest is testing]]
            [clojurecraft.churn :as churn]
            [clojurecraft.failures :as failures]
            [clojurecraft.issue-check :as check]))

(def moment {:why :intent-failed :t 0 :stop-t 62000 :window-ms 122000})

(deftest an-hour-a-release
  (is (= "evidence-2026-10-16-23" (churn/evidence-tag "2026-10-16T23:08:07.123Z")))
  (is (= "evidence-2026-10-17-00" (churn/evidence-tag "2026-10-17T00:00:00Z"))))

(deftest an-asset-is-the-run-and-the-file
  (is (= "38962774798-real-8-s1-Clj_r27.edn.gz"
         (churn/asset-name 38962774798
                           {:run/snapshot "data/race/snapshots/real-8-s1-Clj_r27.edn.gz"})))
  (is (= "7-R4-35-2.edn.gz"
         (churn/asset-name 7 {:run/snapshot "data/simrace/snapshots/R4-35-2.edn.gz"}))))

(deftest only-an-uploaded-snapshot-is-filed
  (let [up {:run/recurs? true :run/snapshot "d/snapshots/R1-2-1.edn.gz"}
        lost {:run/recurs? true :run/snapshot "d/snapshots/R1-3-1.edn.gz"}
        gap {:run/snapshot "d/snapshots/R1-4-1.edn.gz"}
        runs [up lost gap]
        after (churn/evidenced runs 9 "evidence-2026-10-16-23" #{"9-R1-2-1.edn.gz"})]
    (is (= (assoc up :run/release "evidence-2026-10-16-23" :run/asset "9-R1-2-1.edn.gz")
           (first after)))
    (is (not (:run/recurs? (second after))) "its upload failed: nothing may name it")
    (is (= gap (nth after 2)) "a run that did not recur is left as it was")
    (is (= [(first after)] (failures/reproduced after)))
    (is (= "evidence: 1 snapshots in release evidence-2026-10-16-23; not uploaded (not filed) 1"
           (churn/evidence-text runs after "evidence-2026-10-16-23")))))

(deftest the-comment-names-the-release-and-fetch-reads-it-back
  (let [released {:run/snapshot "d/snapshots/R4-35-2.edn.gz"
                  :run/release "evidence-2026-10-16-23"
                  :run/asset "9-R4-35-2.edn.gz"
                  :run/moment moment}
        old
        {:run/snapshot "d/snapshots/R1-7-1.edn.gz" :run/artifact "simrace-abc-6" :run/moment moment}
        text (str
              "- "
              (failures/snapshot-note released)
              "; or ...\n- "
              (failures/snapshot-note old)
              "\n- snapshot `R2-1-1.edn.gz` in artifact `simrace-abc-1`; an older one, no moment")]
    (is (= (str
            "snapshot `R4-35-2.edn.gz` as `9-R4-35-2.edn.gz` in release `evidence-2026-10-16-23`"
            " (moment 1:02 before the stop, :intent-failed, judged over 2:02)")
           (failures/snapshot-note released)))
    (is (= [{:file "R4-35-2.edn.gz"
             :moment? true
             :release "evidence-2026-10-16-23"
             :asset "9-R4-35-2.edn.gz"}
            {:file "R1-7-1.edn.gz" :moment? true :artifact "simrace-abc-6"}]
           (check/kept-snapshots text)))
    (is (= 3 (count (check/sources text))) "the one with no moment is read, never taken")
    (is (nil? (failures/snapshot-note {:run/recurs? true})))))

(deftest small-recordings-and-every-signature-fold-first
  (let [run (fn [bot sig bytes]
              {:run/bot bot :run/dir "s1" :run/signature sig :run/bytes bytes :reason (second sig)})
        runs [(run "a" [:ingots :boxed] 900) (run "b" [:ingots :boxed] 100)
              (run "c" [:ingots :boxed] 50) (run "d" [:portal :no-stand] 700)
              (run "e" [:portal :no-stand] 300) (run "f" [:fortress :no-path] 2000)]]
    (testing "two a signature, the smallest; every first before any second, smallest first"
      (is (= ["c" "e" "f" "b" "d"] (map :run/bot (churn/convertible runs)))))))
