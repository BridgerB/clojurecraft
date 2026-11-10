(ns clojurecraft.table-test
  "The :keep-table row: the crafting table the bot placed is carried on once its crafting is done,
   so a pickaxe worn out far from it is made again on the spot."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojurecraft.blocks :as blocks]
            [clojurecraft.fixtures :as fx]
            [clojurecraft.game :as game]
            [clojurecraft.make :as make]
            [clojurecraft.memory :as memory]
            [clojurecraft.picks :as picks]
            [clojurecraft.plan :as plan]
            [clojurecraft.recipe :as recipe]
            [clojurecraft.table :as table]
            [clojurecraft.world :as world]))

(use-fixtures :once fx/instrumented)

(defn item "A stack of n of the item named k." [k n] {:item (recipe/item-id k) :count n})

(def table-id (blocks/default-state :crafting_table))

(def at [9 64 8])

(def row (first (filter #(= :keep-table-2 (:goal/id %)) plan/goals)))

(defn beside-table
  "A bot in a chained run beside a crafting table at [9 64 8] it placed (a :place of it ended
   :done there), holding inv, and a craft at that table ended a second ago."
  [inv]
  (-> (game/init fx/opts)
      (assoc :bot/phase :play
             :player/pos [8.5 64.0 8.5]
             :player/loaded? true
             :player/on-ground? true
             :player/inventory inv
             :plan/chain? true
             :plan/goals #{:keep-table-2}
             :time/now 2000
             :plan/last {:intent/kind :craft :intent/window :table :intent/id 4}
             :plan/ended {:intent/kind :craft :ended/at 1000}
             :world/chunks {[0 0] (world/column {at table-id})})
      (memory/observe at table-id)
      (memory/remember-intention {:intent/id 3 :intent/kind :place :intent/target at} :done)))

(def spent
  "What a stone pickaxe takes, still held: the sword row would craft with it at once."
  {0 (item :cobblestone 3) 1 (item :stick 2)})

(def crafted
  "What is left of a craft that took all it held: the pickaxe."
  {0 (item :stone_pickaxe 1)})

(deftest the-table-placed-is-dug-up-after-its-craft
  (let [w (beside-table crafted)]
    (is (table/own-table w))
    (is (not (plan/done-by w row)) "a placed table with nothing more to craft is not left")
    (is (= {:intent/kind :dig :intent/target at :intent/for :take-table}
           (plan/next-intent w row plan/goals)))))

(deftest the-drop-of-the-table-is-collected-from-the-dig
  (let [w (-> (beside-table crafted)
              (assoc :plan/last {:intent/kind :dig :intent/target at :intent/for :take-table})
              (assoc :world/chunks {[0 0] (world/column {})}))
        i (plan/next-intent w row plan/goals)]
    (is (= :collect (:intent/kind i)))
    (is (= :take-table (:intent/for i)))))

(deftest a-table-held-is-kept
  (let [w (beside-table (assoc crafted 2 (item :crafting_table 1)))]
    (is (nil? (plan/next-intent w row plan/goals)))
    (is (plan/done-by w row))))

(deftest a-table-the-bot-did-not-place-is-never-taken
  (let [w (-> (beside-table crafted)
              (assoc :world/facts (:world/facts (memory/observe (game/init fx/opts) at table-id))))]
    (is (nil? (table/own-table w)))
    (is (nil? (plan/next-intent w row plan/goals)))))

(deftest nothing-is-taken-but-right-after-a-craft
  (let [w (beside-table crafted)]
    (is (nil? (plan/next-intent (assoc w :time/now 20000) row plan/goals)) "long after")
    (is (nil? (plan/next-intent (assoc w :plan/last {:intent/kind :walk}) row plan/goals))
        "after something else")
    (is (nil? (plan/next-intent (dissoc w :plan/chain?) row plan/goals)) "not in a chained run")))

(deftest a-craft-queued-keeps-the-table-where-it-is
  (testing "a stone pickaxe is made at the table next"
    (let [w (assoc (beside-table spent) :plan/goals #{:keep-table-2 :stone-pickaxe})]
      (is (table/queued-craft? w))
      (is (nil? (plan/next-intent w row plan/goals)))))
  (testing "the sword row would craft with what is left"
    (let [w (beside-table spent)]
      (is (table/queued-craft? w))
      (is (nil? (plan/next-intent w row plan/goals))))))

(deftest a-pickaxe-is-made-on-the-spot-from-a-held-table
  (let [w (-> (game/init fx/opts)
              (assoc :bot/phase :play
                     :player/pos [8.5 64.0 8.5]
                     :player/loaded? true
                     :player/on-ground? true
                     :player/inventory (assoc spent 2 (item :crafting_table 1))
                     :plan/goals #{:stone-pickaxe}
                     :world/chunks {[0 0] (world/column {})}))
        goal (first (filter #(= :stone-pickaxe (:goal/id %)) plan/goals))
        i (make/decide w goal plan/goals)]
    (is (= :place (:intent/kind i)))
    (is (= :crafting_table (:intent/item i)))))

(deftest the-take-along-is-held-against-the-hunt
  ;; Issue #263: in all 14 no-pickaxe endings of the real stage-18 lanes on 4170b7d the pearl hunt
  ;; cut the take-along dig, the table stayed where it was, and the next pickaxe had none. The
  ;; :keep-table-2 row commits its dig, toss and pick-up: only an escape row cuts them.
  (let [hunt (first (filter #(= :ender-pearls (:goal/id %)) plan/goals))
        dig
        {:intent/kind :dig :intent/target at :intent/for :take-table :intent/goal :keep-table-2}]
    (is (= :keep-table-2 (plan/current-id plan/goals :keep-table)) "the old row stands for the new")
    (is (not (plan/live? (first (filter #(= :keep-table (:goal/id %)) plan/goals)))))
    (is (plan/held? plan/goals dig hunt) "the dig of the table runs on")
    (is (plan/held? plan/goals (assoc dig :intent/kind :collect) hunt)
        "and the pick-up of its drop")
    (is (not (plan/held? plan/goals (assoc dig :intent/goal :keep-table) hunt))
        "the old row's dig was cut, as on 4170b7d")))

(deftest a-full-bag-leaves-the-table-and-never-ends-the-plan
  ;; issue #263: nine of the 11 inventory-full endings of the real stage-18 lanes on 2026-11-09
  ;; were the spare-picks row's take-along, every slot full and nothing to throw: its
  ;; :inventory-full wait ended the plan 20 s after the third pickaxe was crafted
  (let [full (into {} (for [p (concat (range 9 36) (range 0 9))] [p (item :iron_sword 1)]))
        w (beside-table full)]
    (is (= {:plan/wait :inventory-full} (make/take-table-along w)) "the take-along can only wait")
    (is (nil? (picks/take-table-step w))
        "the row's step is none: the table stays, the plan goes on")
    (is (= :dig (:intent/kind (picks/take-table-step (beside-table crafted))))
        "with a slot free, the dig")))
