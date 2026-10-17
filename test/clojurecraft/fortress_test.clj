(ns clojurecraft.fortress-test
  "The tunnel to a fortress (issue #11): a step's plan as data, and the rule that no step opens a
   cell beside lava."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.test.check :as tc]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [clojurecraft.blocks :as blocks]
            [clojurecraft.build :as build]
            [clojurecraft.dimension :as dimension]
            [clojurecraft.fortress :as fortress]
            [clojurecraft.intent :as intent]
            [clojurecraft.memory :as memory]))

(def netherrack (blocks/default-state :netherrack))
(def air (blocks/default-state :air))

(deftest fortress-bricks-use-the-indexed-current-sightings
  ;; Issue #328: once the tunnel could run, scanning all sightings every tick exhausted the
  ;; judge's budget. The state-indexed query keeps the same dimension, height and recency rules.
  (let [brick (blocks/default-state :nether_bricks)
        w (reduce (fn [w [p state]] (memory/observe w p state))
                  {:world/facts (memory/empty-facts) :player/dimension dimension/nether :time/now 0}
                  [[[0 31 0] brick] [[0 32 0] brick] [[1 64 0] brick] [[1 64 0] air]
                   [[2 64 0] (blocks/default-state :nether_brick_fence)]
                   [[3 64 0] (blocks/default-state :nether_brick_stairs)]])
        w (-> w
              (assoc :player/dimension dimension/overworld)
              (memory/observe [4 64 0] brick)
              (assoc :player/dimension dimension/nether))]
    (with-redefs [memory/latest (fn [_] (throw (ex-info "Full sightings scan" {})))]
      (is (= #{[0 32 0] [2 64 0] [3 64 0]} (set (fortress/bricks w)))))))

(defn rock
  "A world of netherrack round [0 64 0] (x and z -4..4, y 60..70), the bot's two cells open, with
   overrides on top."
  [overrides]
  {:world/chunks {}
   :world/blocks
   (merge (into {} (for [x (range -4 5) y (range 60 71) z (range -4 5)] [[x y z] netherrack]))
          {[0 64 0] air [0 65 0] air}
          overrides)
   :player/pos [0.5 64.0 0.5]})

(deftest a-step-is-a-floor-the-cells-it-opens-and-standing-there
  (let [plan (fortress/step-plan [0 64 0] [1 0 0])]
    (is (= [:place :dig :dig :stand-in] (map :build/op plan)))
    (is (= [1 63 0] (:build/pos (first plan))) "the floor under the cell stepped into")
    (is (= [[1 65 0] [1 64 0]] (map :build/pos (filter #(= :dig (:build/op %)) plan))) "head first")
    (is (= [:feet [1 64 0]] (:build/done (peek plan))))
    (is (every? #(= [0 64 0] (:build/stand %)) (butlast plan)) "every op worked from the feet"))
  (testing "a step down opens the cell the head passes through; a step up the one over the head"
    (is (= [[1 65 0] [1 64 0] [1 63 0]] (fortress/opens [0 64 0] [1 -1 0])))
    (is (= [[0 66 0] [1 66 0] [1 65 0]] (fortress/opens [0 64 0] [1 1 0])))))

(deftest no-step-opens-a-cell-beside-lava
  (let [lava blocks/lava-base]
    (is (fortress/safe? (rock {}) [0 64 0] [1 0 0]) "solid rock all round")
    (is (not (fortress/safe? (rock {[2 64 0] lava}) [0 64 0] [1 0 0])) "lava beyond the cell")
    (is (not (fortress/safe? (rock {[1 66 0] lava}) [0 64 0] [1 0 0])) "lava over the head's cell")
    (is (not (fortress/safe? (rock {[1 64 0] lava}) [0 64 0] [1 0 0])) "lava in the cell itself")
    (is (fortress/safe? (rock {[1 63 0] lava}) [0 64 0] [1 0 0])
        "lava in the floor is filled: the floor op places into it")
    (is (not (fortress/safe? (rock {[1 63 0] air [1 62 0] air [1 61 0] air}) [0 64 0] [1 0 0]))
        "never out over a drop: an air floor is not a tunnel")
    (is (not (fortress/safe? (rock {[1 64 0] (blocks/default-state :obsidian)}) [0 64 0] [1 0 0]))
        "never through obsidian")
    (is (not (fortress/safe? (rock {[1 64 0] nil}) [0 64 0] [1 0 0])) "never into the unknown")
    (testing "the tunnel goes round: up when lava is beside the level step"
      (is (= [1 1 0] (fortress/choose-move (rock {[1 64 1] lava}) [0 64 0] [4 64 0]))))))

(deftest a-step-is-walked-into-with-no-build-of-its-own
  ;; build/approach-stand digs ground in the way of a :build's stand using its mold; a tunnel
  ;; step has no mold (no :intent/build), so the walk into the cell it opened is just a walk
  (let [w (rock {[1 64 0] air [1 65 0] air})
        op (peek (fortress/step-plan [0 64 0] [1 0 0]))]
    (is (= {:intent/kind :walk :intent/target [1 64 0] :intent/for :build}
           (build/op-child w {:intent/kind :tunnel-to :intent/target [9 64 0]} op)))))

(def gen-move
  (gen/elements (for [d [[1 0] [-1 0] [0 1] [0 -1]] dy [-1 0 1]] [(first d) dy (second d)])))
(def gen-feet (gen/tuple (gen/choose -1000 1000) (gen/choose 1 250) (gen/choose -1000 1000)))

(deftest a-step-never-places-where-the-body-stands-and-ends-where-it-moved
  (let [r (tc/quick-check 200
                          (prop/for-all
                           [feet gen-feet move gen-move]
                           (let [plan (fortress/step-plan feet move)
                                 to (mapv + feet move)
                                 placed (keep #(when (= :place (:build/op %)) (:build/pos %)) plan)
                                 body (set (concat (fortress/opens feet move)
                                                   [feet (update feet 1 inc)]))]
                             (and (not-any? body placed)
                                  (contains? body to)
                                  (contains? body (update to 1 inc))
                                  (= [:feet to] (:build/done (peek plan)))
                                  (= plan (fortress/step-plan feet move))))))]
    (is (:pass? r) (pr-str (select-keys r [:fail :shrunk])))))

(deftest moves-go-toward-the-target-first
  (is (= [1 0 0] (first (fortress/moves [0 64 0] [10 64 2]))) "the farther axis, level")
  (is (= [0 -1 1] (first (fortress/moves [0 64 0] [1 50 10]))) "down when far below")
  (is (= [0 1 -1] (first (fortress/moves [0 64 0] [0 80 -10]))) "up when far above")
  (is (seq (fortress/moves [0 64 0] [0 67 0]))
      "straight under the target: a way aside (issue #262)")
  (is (= [1 1 0] (first (fortress/moves [0 64 0] [0 67 0] 0))) "climbing at once when asked")
  (is (every? (fn [[dx _ dz]] (= 1 (+ (abs dx) (abs dz)))) (fortress/moves [0 64 0] [5 64 5]))
      "one block along one axis"))

(deftest no-step-opens-a-spawner
  ;; issue #262: the blaze rods come from a fortress's spawner; a tunnel to it stops beside it
  (let [w (rock {[1 64 0] (blocks/default-state :spawner)})]
    (is (fortress/safe? (rock {}) [0 64 0] [1 0 0]) "the same step through netherrack is safe")
    (is (not (fortress/safe? w [0 64 0] [1 0 0])) "the spawner is the cell stepped into")
    (is (contains? fortress/unbreakable :spawner))))

(deftest a-step-the-feet-left-is-chosen-again
  ;; gym blaze-fortress-h (run 38905665758) run 1: the tunnel into the spawner's room failed
  ;; :no-path twice, its step's walk back to where the step began behind the feet
  (is (not (fortress/off-step? [0 64 0] [0 64 0] [1 0 0])) "where it began")
  (is (not (fortress/off-step? [1 64 0] [0 64 0] [1 0 0])) "where it steps into")
  (is (not (fortress/off-step? [0 65 0] [0 64 0] [1 0 0])) "a jump on the way")
  (is (fortress/off-step? [-2 64 0] [0 64 0] [1 0 0]) "knocked two back")
  (is (fortress/lost-way? {:intent/from [0 64 0]
                           :intent/child {:intent/kind :walk
                                          :intent/target [0 64 0]
                                          :intent/status :failed
                                          :intent/reason :no-path}}))
  (is (not (fortress/lost-way? {:intent/from [0 64 0]
                                :intent/child {:intent/kind :walk
                                               :intent/target [1 64 0]
                                               :intent/status :failed
                                               :intent/reason :no-path}}))
      "the walk into the cell stepped into is the step itself")
  (let [w (assoc (rock {})
                 :bot/phase :play
                 :bot/effects []
                 :time/now 0
                 :time/tick 0
                 :player/pos [-1.5 64.0 0.5]
                 :plan/intent
                 {:intent/kind :tunnel-to
                  :intent/target [9 64 0]
                  :intent/steps 8
                  :intent/move [1 0 0]
                  :intent/from [1 64 0]
                  :intent/status :active})
        w (intent/run w (:plan/intent w) {:event/kind :tick :event/now 0})]
    (is (nil? (:intent/move (:plan/intent w))) "the step dropped, chosen again next tick")
    (is (= :active (:intent/status (:plan/intent w))))))

(deftest an-overworld-step-opens-no-cell-by-water
  ;; issue #265: the way to a stronghold's portal room runs through aquifers; a :tunnel-to with
  ;; :intent/dry? takes no step whose opened cells touch water, the Nether's rule (safe?) aside
  (let [w (rock {[1 65 1] blocks/water-base})]
    (is (fortress/safe? w [0 64 0] [1 0 0]) "the Nether's rule takes no account of water")
    (is (not (fortress/dry? w [0 64 0] [1 0 0])))
    (is (fortress/dry? w [0 64 0] [-1 0 0]))
    (is (= [1 0 0] (fortress/choose-move w [0 64 0] [4 64 0])))
    (is (not= [1 0 0] (fortress/choose-move w [0 64 0] [4 64 0] fortress/climb-past true)))))
