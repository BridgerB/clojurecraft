(ns clojurecraft.plan-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojurecraft.fixtures :as fx :refer [fold packets sent packet ticks]]
            [clojurecraft.game :as game]
            [clojurecraft.intent :as intent]
            [clojurecraft.memory :as memory]
            [clojurecraft.physics :as physics]
            [clojurecraft.plan :as plan]
            [clojurecraft.make]
            [clojurecraft.wood]
            [clojurecraft.world :as world]))

(use-fixtures :once fx/instrumented)

(def step (game/compose game/step plan/step))

(defn world-state
  "In play, loaded, standing at pos, with a 3-log trunk at [tx 64..66 0] under a leaf block,
   already seen (remembered)."
  ([pos] (world-state pos 3))
  ([pos tx]
   (let [col (world/column {[tx 64 0] 136 [tx 65 0] 136 [tx 66 0] 136 [tx 67 0] 252})]
     (-> (game/init fx/opts)
         (assoc :bot/phase :play :player/pos pos :player/loaded? true :player/on-ground? true)
         (assoc :world/chunks {[0 0] col})
         (memory/remember-column [0 0] col)))))

(defn run [w events] (fold step w events))
(def go {:event/kind :go :go/goals [:wood]})

(deftest memory-finds-the-bottom-of-the-trunk
  (let [w (world-state [0.5 64.0 0.5])]
    (is (= [3 64 0] (memory/nearest-log w (game/eye w) 48 #{})))
    (is (nil? (memory/nearest-log w (game/eye w) 48 #{[3 64 0]})) "blacklist")
    (is (= [3 65 0] (memory/nearest-log (memory/observe w [3 64 0] 0) (game/eye w) 48 #{}))
        "once the bottom is observed as air, the log above is the trunk bottom")
    (is (= 4 (intent/face-toward (game/eye w) [3 64 0])) "eye is west of the block, so its west face")))

(deftest face-is-the-side-nearest-the-eye
  (is (= 1 (intent/face-toward [3.5 70.0 0.5] [3 64 0])) "eye above → top")
  (is (= 2 (intent/face-toward [3.5 65.0 -5.0] [3 64 0])) "eye north → north face"))

(deftest dig-timeline-then-pickup
  (let [[w fx] (run (world-state [0.5 64.0 0.5]) (cons go (ticks 50 8000)))
        actions (filter #(= :player-action (:packet/name (second %))) (sent fx))
        [[t-start start] [t-finish finish]] actions
        swings (filter #(= :swing (:packet/name (second %))) (sent fx))]
    (is (= :collect (get-in w [:plan/intent :intent/kind])))
    (is (= [3 64 0] (get-in w [:plan/intent :intent/target])))
    (testing "START after settling, FINISH late enough never to abort the break"
      (is (= 0 (:status start)))
      (is (= 2 (:status finish)))
      (is (= [3 64 0] (:pos start)))
      (is (<= 500 t-start 2000))
      (is (>= (- t-finish t-start) (+ (* 3000 1.35) 200)))
      (is (= (:sequence finish) (inc (:sequence start)))))
    (testing "swings every 350 ms while digging"
      (is (<= 11 (count swings) 14)))
    (testing "the broken block is air locally and in memory"
      (is (= 0 (game/block-at w [3 64 0])))
      (is (= 0 (memory/remembered w [3 64 0]))))
    (testing "done once the inventory shows a log"
      (let [[w2 _] (run w [(packet {:packet/name :set-player-inventory :slot 0 :item {:item 134 :count 1}})
                           {:event/kind :tick :event/now 8050 :event/rand 0.5}])]
        (is (plan/done? w2))
        (is (= {} (:player/controls w2)))))))

(deftest walks-to-a-far-log
  (let [[w fx] (run (world-state [0.5 64.0 0.5] 13) (cons go (ticks 50 6000)))]
    (is (contains? #{:dig :collect} (get-in w [:plan/intent :intent/kind])))
    (is (<= (physics/distance (game/eye w) [13.5 64.5 0.5]) 4.0))
    (is (some #(= :move-player-pos-rot (:packet/name %)) (packets fx)))))

(deftest gives-up-without-logs
  (let [w (assoc (world-state [0.5 64.0 0.5]) :world/facts (memory/empty-facts))
        [w _] (run w (cons go (ticks 50 25000)))]
    (is (plan/failed? w))
    (is (= :no-log (:plan/reason w)))))

(deftest a-failed-intent-is-blacklisted-and-retried
  (let [w (world-state [0.5 64.0 0.5])
        w (assoc w :plan/status :active :plan/since 0 :plan/blacklist #{} :plan/attempts 0
                 :plan/intent {:intent/kind :walk :intent/target [13 64 0] :intent/status :active
                               :intent/started 0 :intent/best-tick 0 :intent/detours 4})
        [w _] (run w [{:event/kind :tick :event/now 50 :event/rand 0.5}])]
    (is (= {:intent/kind :walk :intent/target [3 64 0] :intent/for :log :intent/status :active :intent/id 1} (:plan/intent w))
        "re-planned in the same tick toward the next log")
    (is (= #{[13 64 0]} (:plan/blacklist w)))
    (is (= 1 (:plan/attempts w)))))

(deftest digs-the-log-at-feet-height
  (testing "standing level with the trunk base: the bottom log"
    (is (= [3 64 0] (clojurecraft.wood/trunk-target (world-state [0.5 64.0 0.5]) [3 64 0]))))
  (testing "standing two blocks above the base: the log at feet height, so the drop lands in reach"
    (is (= [3 66 0] (clojurecraft.wood/trunk-target (world-state [0.5 66.0 0.5]) [3 64 0]))))
  (testing "standing above the whole trunk: its top log"
    (is (= [3 66 0] (clojurecraft.wood/trunk-target (world-state [0.5 70.0 0.5]) [3 64 0])))))

(deftest a-success-ends-the-failure-streak
  (let [w (assoc (world-state [0.5 64.0 0.5]) :plan/status :active :plan/since 0 :plan/blacklist #{} :plan/attempts 2
                 :plan/goals #{:wood}
                 :plan/intent {:intent/kind :walk :intent/target [3 64 0] :intent/for :log :intent/status :active})
        [w _] (run w [{:event/kind :tick :event/now 50 :event/rand 0.5}])]
    (is (= 0 (:plan/attempts w)) "the walk succeeded, so two earlier failures no longer count")
    (is (= :dig (get-in w [:plan/intent :intent/kind])))))

(deftest a-go-with-a-landing-waits-until-the-bot-is-there
  (let [w (assoc (world-state [12.5 64.0 12.5]) :time/now 0)            ; same loaded chunk, 17 blocks away
        go-at (assoc go :go/at [0.5 64.0 0.5])
        [w _] (run w [go-at])]
    (is (= :landing (:plan/status w)) "the fixture's :go names a landing")
    (is (= [0.5 64.0 0.5] (:plan/go-at w)))
    (let [[w' _] (run w [{:event/kind :tick :event/now 50 :event/rand 0.5}])]
      (is (= :landing (:plan/status w')) "in a loaded chunk but far from the landing: no planning yet")
      (is (nil? (:plan/intent w'))))
    (let [[w' _] (run (assoc w :player/pos [0.5 64.0 0.5]) [{:event/kind :tick :event/now 50 :event/rand 0.5}])]
      (is (= :active (:plan/status w')) "at the landing with the chunk loaded: planning starts"))
    (let [[w' _] (run (assoc w :player/pos [0.5 64.0 200.5]) [{:event/kind :tick :event/now 50 :event/rand 0.5}])]
      (is (= :landing (:plan/status w')) "far away in an unloaded chunk: still waiting"))
    (let [[w' _] (run w [{:event/kind :tick :event/now (+ 50 plan/landing-timeout) :event/rand 0.5}])]
      (is (= [:failed :no-landing] [(:plan/status w') (:plan/reason w')]) "never arrived"))))

(deftest a-go-without-a-landing-starts-where-the-bot-stands
  (let [[w _] (run (world-state [0.5 64.0 0.5]) [go])]
    (is (= :active (:plan/status w)))))

(deftest until-names-come-from-the-goal-table
  (is (= [:wood] (plan/goals-for "wood")))
  (is (= [:kit] (plan/goals-for "table")))
  (is (= [:pickaxe] (plan/goals-for "pickaxe")))
  (is (nil? (plan/goals-for "play"))))
