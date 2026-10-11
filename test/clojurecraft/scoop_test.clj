(ns clojurecraft.scoop-test
  "Filling a bucket: which water the act walks to (never cave water), and the :scoop intent
   against a server that never answers."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojurecraft.blocks :as blocks]
            [clojurecraft.fixtures :as fx]
            [clojurecraft.game :as game]
            [clojurecraft.intent :as intent]
            [clojurecraft.recipe :as recipe]
            [clojurecraft.scoop :as scoop]
            [clojurecraft.world :as world]))

(use-fixtures :once fx/instrumented)

(def water blocks/water-base)

(def pond
  "Three water sources in the stone floor, x 10-12 at z 8, open to the sky."
  {[10 63 8] water [11 63 8] water [12 63 8] water})

(def cave-pool
  "Water at y 50 under the stone floor, with air over it: a cave pool, 14 under the eye."
  {[10 50 8] water [10 51 8] blocks/air [10 52 8] blocks/air})

(defn standing
  "On the floor at [x 64 z] over blocks, an empty bucket in hotbar slot 0 (held)."
  [blocks [x z]]
  (assoc (game/init fx/opts)
         :bot/phase :play
         :player/pos [x 64.0 z]
         :player/loaded? true
         :player/on-ground? true
         :player/inventory {0 {:item (recipe/item-id :bucket) :count 1}}
         :world/chunks {[0 0] (world/column blocks)}))

(deftest the-act-scoops-in-reach-walks-to-water-in-sight-and-refuses-cave-water
  (is (= {:intent/kind :scoop :intent/target [10 63 8]}
         (scoop/scoop-next (standing pond [8.5 8.5])))
      "in reach from the bank")
  (is (= {:intent/kind :walk :intent/target [10 63 8] :intent/for :scoop}
         (scoop/scoop-next (standing pond [3.5 8.5])))
      "in sight, out of reach: walk to it")
  (is (= {:plan/wait :no-water} (scoop/scoop-next (standing cave-pool [8.5 8.5])))
      "fourteen under the eye is cave water: never dived for")
  (is (= {:plan/wait :no-water}
         (scoop/scoop-next
          (assoc (standing pond [8.5 8.5]) :plan/blacklist #{[10 63 8] [11 63 8] [12 63 8]})))
      "a source that failed is not chosen again"))

(defn intent-step
  "The world reducer plus one run of the current intent per tick (no planner)."
  [w e]
  (let [w (game/step w e)]
    (if (and (= :tick (:event/kind e)) (:plan/intent w)) (intent/run w (:plan/intent w) e) w)))

(deftest a-scoop-the-server-never-answers-fails-no-scoop
  (let [w (assoc (standing pond [8.5 8.5])
                 :plan/intent
                 {:intent/kind :scoop :intent/target [10 63 8] :intent/status :active})
        [w fx] (fx/fold intent-step w (fx/ticks 50 12000))
        uses (filter #(= :use-item (:packet/name %)) (fx/packets fx))]
    (is (= :failed (:intent/status (:plan/intent w))))
    (is (= :no-scoop (:intent/reason (:plan/intent w))))
    (is (= scoop/max-tries (count uses)) "tried at the same source, then gave up")
    (testing "each scoop is aimed at the source by the look it carries"
      (is (every? #(< 30.0 (:x-rot %) 60.0) uses) "down toward the water")
      (is (apply < (map :sequence uses)) "a fresh sequence each"))))
