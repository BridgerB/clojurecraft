(ns clojurecraft.verify-test
  "A fix's verdict (bb verify): what the judge, the suite and the checks said, read back, and the
   one last line a session acts on. Babashka runs these (bb test:bb)."
  (:require [clojure.test :refer [deftest is testing]]
            [clojurecraft.verify :as verify]))

(deftest the-judge-says-which-recur
  (is
   (=
    [{:path "test/issues/1/a.edn.gz" :recurs? true} {:path "test/issues/1/b.edn.gz" :recurs? false}]
    (verify/judge-verdict
     (str
      "noise\n"
      "JUDGE {:path \"test/issues/1/a.edn.gz\", :signature [:portal :no-stand], :moment {:t 3}, :window-ms 9, :recurs? true}\n"
      "STORY test/issues/1/a.edn.gz [1 :started :build nil nil]\n"
      "JUDGE {:path \"test/issues/1/b.edn.gz\", :signature [:portal :no-stand], :recurs? false}\n")))))

(deftest the-suite-says-what-failed
  (let
    [v
     (verify/test-verdict
      "Testing...\nFAIL in (x) (a.clj:3)\nexpected: 1\n  actual: 2\n3861 assertions, 1 failures, 0 errors.\n")]
    (is (= "3861 assertions, 1 failures, 0 errors." (:summary v)))
    (is (= "FAIL in (x) (a.clj:3)" (first (:failures v))))
    (is (not (verify/ok-test? v))))
  (is (verify/ok-test? {:summary "3861 assertions, 0 failures, 0 errors."}))
  (is (not (verify/ok-test? {:summary nil})) "a crashed suite is never ok"))

(deftest one-last-line
  (let [ok {:snapshots ["a"]
            :judged [{:path "a" :recurs? false}]
            :tests {:summary "10 assertions, 0 failures, 0 errors."}
            :check {:exit 0}}]
    (is (= "VERIFY READY" (last (verify/report ok))))
    (testing "what is left, named"
      (is (= "VERIFY NOT READY: 1 snapshot(s) still recur; bb check fails"
             (last (verify/report
                    (assoc ok :judged [{:path "a" :recurs? true}] :check {:exit 1 :out "x"})))))
      (is (= "VERIFY NOT READY: 1 snapshot(s) not judged (the judge crashed)"
             (last (verify/report (assoc ok :judged []))))))))
