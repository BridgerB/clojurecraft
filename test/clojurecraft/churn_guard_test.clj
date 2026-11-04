(ns clojurecraft.churn-guard-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojurecraft.churn-guard :as churn]
            [clojurecraft.fixtures :as fx]
            [clojurecraft.game :as game]
            [clojurecraft.intent :as intent]
            [clojurecraft.plan :as plan]))

;; A goal whose intent is chosen in a state its own end check already calls done: the CHURN class.
(defn next-fn
  "A planner that always answers the goal's own kind, aimed at one cell."
  [_ goal _]
  {:intent/kind (:goal/kind goal) :intent/target [1 64 1]})
(defmethod intent/run :churn-done [world _ _] (intent/done world))
(defmethod intent/run :churn-slow [world _ _] world)

(defn row
  [kind]
  {:goal/id :churn-test :goal/target? true :goal/priority 1 :goal/done? :never :goal/kind kind})

(def world
  (merge
   (game/init fx/opts)
   {:plan/status :active :plan/goals #{:churn-test} :player/pos [0.5 64.0 0.5] :time/now 1000}))

(deftest a-choice-over-in-its-first-tick-is-a-violation
  (let [v (churn/violation-with world [(row :churn-done)] next-fn)]
    (is (= :churn-done (:intent/kind v)))
    (is (= :done (:churn/status v)))
    (is (= :churn-test (:goal/id v)))))

(deftest a-choice-that-takes-time-is-not
  (is (nil? (churn/violation-with world [(row :churn-slow)] next-fn))))

(deftest an-instantaneous-kind-is-not
  (let [place-row (row :place)]
    (is (contains? churn/instantaneous-kinds :place))
    (is (nil? (churn/violation-with world [place-row] next-fn)))))

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

(defn instant-cycle
  "One start and first-tick done end of intent i in world w, at tick and ms now, with no
   packets sent on the ending tick."
  [w i tick now]
  (let [w (-> w
              (assoc :time/tick tick :time/now now)
              (plan/start-intent i)
              (assoc :bot/effects []))]
    (plan/finish-intent (assoc w :time/tick (inc tick))
                        (assoc (:plan/intent w) :intent/status :done))))

(defn cycles [w i n] (reduce (fn [w k] (instant-cycle w i k (* 50 k))) w (range n)))

(deftest a-walk-already-in-reach-counts-as-failed-the-third-instant-time
  ;; issue #704: a :walk with reach 4 to a cell already inside it, done at once every tick
  (let [i {:intent/kind :walk :intent/target [1 64 1]}
        two (cycles world i 2)
        three (instant-cycle two i 2 100)]
    (is (= 2 (get-in two [:plan/instant :instant/n])))
    (is (nil? (:plan/failed two)))
    (is (= :instant-churn (:intent/reason (:plan/failed three))))
    (is (some #(= :instant-churn (:reason %)) (:plan/failures three)))
    (is (contains? (:plan/blacklist three) [1 64 1]))
    (is (some #(re-find #"instant-churn :walk \[1 64 1\] x3" (str (:effect/message %)))
              (:bot/effects three)))))

(deftest a-tunnel-to-with-no-steps-counts-as-failed-the-third-instant-time
  ;; issues #704, #707: a :tunnel-to with no steps to take and reach 6, done at once every tick
  (let [three (cycles world {:intent/kind :tunnel-to :intent/target [5 64 5]} 3)]
    (is (= :instant-churn (:intent/reason (:plan/failed three))))
    (is (some #(= {:intent/kind :tunnel-to :intent/target [5 64 5]} (:choice %))
              (:plan/failures three)))))

(deftest a-retrace-target-inside-reach-counts-as-failed-the-third-instant-time
  ;; issue #717: the retrace target inside reach 1.5, 24,234 starts on one cell
  (let [three (cycles world {:intent/kind :walk :intent/target [0 64 0] :intent/for :retrace} 3)]
    (is (= :instant-churn (:intent/reason (:plan/failed three))))))

(deftest instant-ends-minutes-apart-are-not-in-a-row
  (let [i {:intent/kind :walk :intent/target [1 64 1]}
        w (reduce (fn [w k] (instant-cycle w i k (* 60000 k))) world (range 3))]
    (is (= 1 (get-in w [:plan/instant :instant/n])))
    (is (nil? (:plan/failed w)))))

(deftest a-fight-eat-and-dig-line-never-count-as-instant-churn
  (testing "a fight that swings, even one over in its first tick, and one that takes its ticks"
    (let [fight {:intent/kind :fight :intent/target 42}
          swung
          (let [w (-> world
                      (assoc :time/tick 0 :time/now 0)
                      (plan/start-intent fight))]
            (plan/finish-intent
             (assoc w :bot/effects [{:effect/kind :send :effect/packet {:packet/name :swing}}])
             (assoc (:plan/intent w) :intent/status :done)))
          long (reduce (fn [w k]
                         (let [w (-> w
                                     (assoc :time/tick (* 20 k) :time/now (* 1000 k))
                                     (plan/start-intent fight))]
                           (plan/finish-intent (assoc w
                                                      :time/tick (+ 20 (* 20 k))
                                                      :bot/effects
                                                      [{:effect/kind :send
                                                        :effect/packet {:packet/name :attack}}])
                                               (assoc (:plan/intent w) :intent/status :done))))
                       world
                       (range 5))]
      (is (nil? (:plan/instant swung)))
      (is (nil? (:plan/failed swung)))
      (is (nil? (:plan/instant long)))
      (is (nil? (:plan/failed long)))))
  (testing "eating is a one-action kind, over at once by design"
    (let [w (cycles world {:intent/kind :eat} 5)]
      (is (nil? (:plan/instant w)))
      (is (nil? (:plan/failed w)))))
  (testing "digging a line of blocks: each target new, each dig taking its ticks and swinging"
    (let [w (reduce (fn [w k]
                      (let [w (-> w
                                  (assoc :time/tick (* 10 k) :time/now (* 500 k))
                                  (plan/start-intent {:intent/kind :dig
                                                      :intent/target [(inc k) 64 1]}))]
                        (plan/finish-intent (assoc w
                                                   :time/tick (+ 10 (* 10 k))
                                                   :bot/effects
                                                   [{:effect/kind :send
                                                     :effect/packet {:packet/name :player-action}}])
                                            (assoc (:plan/intent w) :intent/status :done))))
                    world
                    (range 5))]
      (is (nil? (:plan/instant w)))
      (is (nil? (:plan/failed w))))))

(deftest a-different-or-later-start-is-not-a-repeat
  (let [i {:intent/kind :walk :intent/target [1 64 1] :intent/for :cover}
        ended (plan/finish-intent (assoc world :plan/intent i) (assoc i :intent/status :done))]
    (testing "another target"
      (is (zero? (plan/churn-count ended (assoc i :intent/target [2 64 2])))))
    (testing "a second later" (is (zero? (plan/churn-count (assoc ended :time/now 2000) i))))
    (testing "the very next tick" (is (= 1 (plan/churn-count (assoc ended :time/now 1050) i))))))
