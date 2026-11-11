(ns clojurecraft.escape-fire-test
  "A fire block the body touches is every goal's to leave (:escape-fire): the states of the real
   fortress-start bots that burned collecting their crafting table for :keep-table."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojurecraft.blocks :as blocks]
            [clojurecraft.fixtures :as fx]
            [clojurecraft.game :as game]
            [clojurecraft.plan :as plan]
            [clojurecraft.recipe :as recipe]
            [clojurecraft.swim :as swim]
            [clojurecraft.world :as world]))

(use-fixtures :once fx/instrumented)

(def fire (blocks/default-state :fire))

(def row (plan/row-of plan/goals :escape-fire))

(defn standing
  "A bot in play with its feet at pos on the stone floor of one column topped by blocks, an iron
   sword and a pickaxe held."
  [pos blocks]
  (-> (game/init fx/opts)
      (assoc :bot/phase :play
             :player/pos pos
             :player/loaded? true
             :player/on-ground? true
             :player/health 20.0
             :world/chunks {[0 0] (world/column blocks)}
             :window/state-id 5
             :player/inventory
             {0 {:item (recipe/item-id :iron_sword) :count 1}
              1 {:item (recipe/item-id :iron_pickaxe) :count 1}})))

(def collect
  "The :keep-table pick-up the real bots ran while they burned."
  {:intent/kind :collect-drops
   :intent/target [6 64 8]
   :intent/for :take-table
   :intent/goal :keep-table-2
   :intent/status :active})

(deftest a-fire-at-the-feet-is-dug-out-before-the-table-is-collected
  ;; real Clj_r41 and eight more of one batch (botlogs 2026-11-09-21, 42681405721-r2-b5): landed
  ;; beside a fire, :collect-drops (table/collect-dropped) for the table two blocks off, 53 to 54
  ;; in_fire hits in 30 s from 20 to 0, its retreat walk :no-path, never a dig of the fire
  (let [w (standing [8.5 64.0 8.5] {[8 64 8] fire})]
    (is (not (plan/done-by w row)) "a fire in the feet's cell: the row is not done")
    (is (= {:intent/kind :dig :intent/target [8 64 8]} (plan/next-intent w row plan/goals))
        "the fire is dug out where the bot stands")
    (is (not (plan/held? plan/goals collect row)) "the table's pick-up does not hold against it")
    (testing "with no fire it is done and plans nothing"
      (let [clear (standing [8.5 64.0 8.5] {})]
        (is (plan/done-by clear row))
        (is (nil? (swim/fire-out clear)))))))

(deftest a-fire-the-box-reaches-in-the-next-cell-is-dug-out-too
  ;; real Clj_r18 (42682080380-r5-b4) and Clj_r08 (42683102733-r6-b1): a blaze's fireball lit the
  ;; ground by the table, the same :keep-table pick-up stood in it, 24 to 36 in_fire hits
  (let [w (standing [8.9 64.0 8.5] {[9 64 8] fire})]
    (is (not (plan/done-by w row)) "the box at x 8.9 reaches into the fire at x 9")
    (is (= {:intent/kind :dig :intent/target [9 64 8]} (plan/next-intent w row plan/goals)))))
