(ns clojurecraft.unstick-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojurecraft.fixtures :as fx]
            [clojurecraft.game :as game]
            [clojurecraft.pearls :as pearls]
            [clojurecraft.plan :as plan]
            [clojurecraft.spec]
            [clojurecraft.unstick]))

(use-fixtures :once fx/instrumented)

(defmethod plan/escape :test-rung [_ _] {:intent/kind :walk :intent/target [900 64 900]})

(defn standing
  "A world in play, the feet at [2466 74 2172] at time now, the planner's stay begun at since."
  [now since]
  (-> (game/init fx/opts)
      (assoc :bot/phase :play
             :player/pos [2466.5 74.0 2172.5]
             :player/loaded? true
             :player/on-ground? true
             :time/now now
             :plan/stay-at [2466 74 2172]
             :plan/stay-since since)))

(def storm-walk
  "Run 3 of gym plan run 40583923956: the walk that failed :no-path fifteen times in 0.6 s."
  {:intent/kind :walk :intent/target [2497 53 2134] :intent/for :shelter})

(def lookout {:intent/kind :lookout :intent/for :pearls})

(deftest a-failed-intent-is-not-proposed-again-from-the-same-feet
  (let [w (-> (standing 1000 0)
              (plan/fail-intent (assoc storm-walk :intent/reason :no-path)))]
    (is (plan/retry? w storm-walk))
    (is (not (plan/retry? w (assoc storm-walk :intent/target [1 1 1]))))
    (is (not (plan/retry? (assoc w :player/pos [2470.5 74.0 2172.5]) storm-walk))
        "from other feet it is a new try")
    (is (not (plan/retry? (assoc w :time/now (+ 1000 plan/retry-ms)) storm-walk))
        "and after retry-ms it may be tried again")))

(deftest a-retry-takes-an-escape-or-waits
  (let [failed (-> (standing 1000 0)
                   (plan/fail-intent (assoc storm-walk :intent/reason :no-path)))]
    (testing "with no rung giving a move the plan waits instead of retrying"
      (let [[w i] (plan/guard failed storm-walk)]
        (is (= {:plan/wait :retry} i))
        (is (= (:plan/escapes failed) (:plan/escapes w)))))
    (testing "with a rung, the escape runs in its place, marked, and counted"
      (with-redefs [plan/escape-ladder [:test-rung]]
        (let [[w i] (plan/guard failed storm-walk)]
          (is (= [900 64 900] (:intent/target i)))
          (is (:intent/escape? i))
          (is (= 1 (:plan/escapes w))))))
    (testing "an intent that did not fail passes through"
      (is (= [failed lookout] (plan/guard failed lookout))))))

(deftest twelve-failed-walks-do-not-end-the-plan
  (let [w (reduce (fn [w _] (plan/fail-intent w (assoc storm-walk :intent/reason :no-path)))
                  (assoc (standing 1000 0) :plan/status :active)
                  (range 5))]
    (is (= :active (:plan/status w)))
    (is (= 5 (:plan/unwalkable w))))
  (testing "an escape that fails counts against neither"
    (let [w (reduce
             (fn [w _]
               (plan/fail-intent w (assoc storm-walk :intent/reason :no-path :intent/escape? true)))
             (assoc (standing 1000 0) :plan/status :active)
             (range 20))]
      (is (= :active (:plan/status w)))
      (is (zero? (:plan/unwalkable w)))
      (is (zero? (:plan/attempts w))))))

(deftest a-boxed-descent-is-not-followed-by-a-lookout
  (with-redefs [plan/escape-ladder [:test-rung]]
    (let [boxed (assoc (standing 1000 999) :plan/boxed-at #{[2466 74 2172]})
          [w i] (plan/guard boxed lookout)]
      (is (= [900 64 900] (:intent/target i)))
      (is (:intent/escape? i))
      (is (= 1 (:plan/escapes w))))
    (testing "a lookout elsewhere is a lookout"
      (let [elsewhere (assoc (standing 1000 999) :plan/boxed-at #{[0 0 0]})]
        (is (= [elsewhere lookout] (plan/guard elsewhere lookout)))))))

(deftest a-wait-in-one-cell-for-minutes-moves-on
  (with-redefs [plan/escape-ladder [:test-rung]]
    (let [[_ i] (plan/guard (standing (+ 1000 plan/stuck-ms) 1000) lookout)]
      (is (:intent/escape? i)))
    (let [w (standing (+ 999 plan/stuck-ms) 1000)]
      (is (= [w lookout] (plan/guard w lookout)) "a minute short of it, the wait stays"))
    (let [w (standing (+ 1000 plan/stuck-ms) 1000)
          fight {:intent/kind :fight :intent/target 7}]
      (is (= [w fight] (plan/guard w fight)) "only a waiting kind is found stuck"))))

(deftest a-shelter-walk-asks-the-blacklist
  (let [w (assoc (standing 1000 0) :plan/blacklist #{[2497 53 2134]})]
    (is (nil? (pearls/shelter-walk w [2497 53 2134])))
    (is (nil? (pearls/shelter-walk w nil)))))

(deftest the-stay-follows-the-feet
  (let [w (plan/track-stay (assoc (standing 5000 0) :player/pos [2470.5 74.0 2172.5]))]
    (is (= [2470 74 2172] (:plan/stay-at w)))
    (is (= 5000 (:plan/stay-since w)))
    (is (= w (plan/track-stay w)))))
