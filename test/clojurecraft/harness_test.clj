(ns clojurecraft.harness-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojurecraft.harness :as harness]
            [clojurecraft.rcon :as rcon]))

(def passed "Test passed")
(def failed "Test failed")

(deftest a-landing-is-judged-by-the-servers-answers
  (testing "the first passing check names the reason"
    (is (= :water (harness/bad-landing [[:water passed] [:tree failed]])))
    (is (= :tree (harness/bad-landing [[:water failed] [:tree passed] [:buried passed]]))))
  (testing "no check passed: the landing will do"
    (is (nil? (harness/bad-landing [[:water failed] [:tree failed] [:buried failed]])))
    (is (nil? (harness/bad-landing [[:water nil]])) "no reply is not a pass")))

(deftest every-check-is-an-execute-test-with-a-reason
  (is (every? (fn [[reason test]]
                (and (keyword? reason) (re-find #"^(if|unless) block ~ ~-?\d? ~ " test)))
              harness/checks))
  (is (= #{:water :lava :tree :buried} (set (map first harness/checks)))))

(deftest a-position-is-read-from-the-servers-reply
  (is (= [-8.5 63.0 -133.5]
         (harness/parse-pos "Clj_wood has the following entity data: [-8.5d, 63.0d, -133.5d]")))
  (is (= [1.0E-4 64.0 2.5]
         (harness/parse-pos "x has the following entity data: [1.0E-4d, 64.0d, 2.5d]")))
  (is (nil? (harness/parse-pos "No entity was found"))))

(deftest the-go-event-is-the-only-message
  (is (= {:event/kind :go :go/goals [:wood] :go/at [0.5 64.0 0.5]}
         (harness/go-event "wood" [0.5 64.0 0.5])))
  (is (= {:event/kind :go :go/goals [:pickaxe]} (harness/go-event "pickaxe" nil))
      "a failed landing still starts the run"))

(deftest landing-offsets-start-at-the-located-point
  (is (= [0 0] (first harness/landing-offsets)))
  (is (= 17 (count harness/landing-offsets)))
  (is (apply distinct? harness/landing-offsets)))

(deftest a-forceload-is-released-even-when-the-work-throws
  (let [sent (atom [])]
    (with-redefs [rcon/command (fn [_ s] (swap! sent conj s) "")]
      (is (= :done (harness/with-forceload {:next-id (atom 0)} [1 2] (constantly :done))))
      (is (thrown?
           clojure.lang.ExceptionInfo
           (harness/with-forceload {:next-id (atom 0)} [0 0 31 31] #(throw (ex-info "boom" {}))))))
    (is (= ["forceload add 1 2" "forceload remove 1 2" "forceload add 0 0 31 31"
            "forceload remove 0 0 31 31"]
           @sent))))
