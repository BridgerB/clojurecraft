(ns clojurecraft.churn-guard-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojurecraft.churn-guard :as churn]
            [clojurecraft.fixtures :as fx]
            [clojurecraft.game :as game]
            [clojurecraft.intent :as intent]
            [clojurecraft.plan :as plan]))

;; A goal whose intent is chosen in a state its own end check already calls done: the CHURN class.
(defmethod plan/next-intent :churn-test
  [_ goal _]
  {:intent/kind (:goal/kind goal) :intent/target [1 64 1]})
(defmethod intent/run :churn-done [world _ _] (intent/done world))
(defmethod intent/run :churn-slow [world _ _] world)

(defn row
  [kind]
  {:goal/id :churn-test
   :goal/target? true
   :goal/priority 1
   :goal/done? :never
   :goal/plan :churn-test
   :goal/kind kind})

(def world
  (merge
   (game/init fx/opts)
   {:plan/status :active :plan/goals #{:churn-test} :player/pos [0.5 64.0 0.5] :time/now 1000}))

(deftest a-choice-over-in-its-first-tick-is-a-violation
  (let [v (churn/violation world [(row :churn-done)])]
    (is (= :churn-done (:intent/kind v)))
    (is (= :done (:churn/status v)))
    (is (= :churn-test (:goal/id v)))))

(deftest a-choice-that-takes-time-is-not (is (nil? (churn/violation world [(row :churn-slow)]))))

(deftest an-instantaneous-kind-is-not
  (let [place-row (row :place)]
    (is (contains? churn/instantaneous-kinds :place))
    (is (nil? (churn/violation world [place-row])))))

(deftest a-repeat-the-tick-after-it-ended-is-counted-and-logged
  (let [i {:intent/kind :walk :intent/target [1 64 1] :intent/for :cover}
        w (reduce (fn [w n]
                    (-> w
                        (assoc :time/now (+ 1000 (* 50 n)))
                        (plan/start-intent i)
                        (plan/finish-intent (assoc i :intent/status :done))))
                  world
                  (range 21))]
    (is (= 20 (get-in w [:plan/churn :churn/n])))
    (is (some #(re-find #"churn :walk \[1 64 1\] x20" (str (:effect/message %)))
              (:bot/effects w)))))

(deftest a-different-or-later-start-is-not-a-repeat
  (let [i {:intent/kind :walk :intent/target [1 64 1] :intent/for :cover}
        ended (plan/finish-intent (assoc world :plan/intent i) (assoc i :intent/status :done))]
    (testing "another target"
      (is (zero? (plan/churn-count ended (assoc i :intent/target [2 64 2])))))
    (testing "a second later" (is (zero? (plan/churn-count (assoc ended :time/now 2000) i))))
    (testing "the very next tick" (is (= 1 (plan/churn-count (assoc ended :time/now 1050) i))))))
