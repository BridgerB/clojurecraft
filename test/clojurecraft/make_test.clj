(ns clojurecraft.make-test
  "The planner never asks for something the world cannot do: over generated inventories and
   every target in the goal table, the intent it picks is always executable from what is held
   and known (the essay's \"never selects a goal whose needs are unmet\")."
  (:require [clojure.edn]
            [clojure.spec.alpha]
            [clojure.test :refer [deftest is testing]]
            [clojure.test.check :as tc]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [clojurecraft.blocks :as blocks]
            [clojurecraft.fixtures :as fx]
            [clojurecraft.game :as game]
            [clojurecraft.intent :as intent]
            [clojurecraft.make :as make]
            [clojurecraft.memory :as memory]
            [clojurecraft.plan :as plan]
            [clojurecraft.recipe :as recipe]
            [clojurecraft.smelt :as smelt]
            [clojurecraft.spec]
            [clojurecraft.stone]
            [clojurecraft.swim :as swim]
            [clojurecraft.world :as world]))

(def pickaxe (first (filter #(= :pickaxe (:goal/id %)) plan/goals)))

(defn decide
  "The planner registry over the shipped goal table: the needs planner for item rows, the
   escape and wade planners for theirs (each row names its own under :goal/plan)."
  [w goal]
  (plan/next-intent w goal plan/goals))

(def held-gen
  (gen/hash-map :oak_log (gen/choose 0 3)
                :oak_planks (gen/choose 0 9)
                :stick (gen/choose 0 5)
                :crafting_table (gen/choose 0 1)
                :wooden_pickaxe (gen/frequency [[9 (gen/return 0)] [1 (gen/return 1)]])))

(defn world-holding
  "Standing on bare ground with no trees; optionally a remembered table one block away."
  [held table?]
  (let [inv (into {}
                  (keep-indexed (fn [i [item n]]
                                  (when (pos? n) [i {:item (recipe/item-id item) :count n}]))
                                held))]
    (cond-> (-> (game/init fx/opts)
                (assoc :bot/phase :play
                       :player/pos [5.5 64.0 5.5]
                       :player/loaded? true
                       :player/on-ground? true
                       :player/inventory inv
                       :world/chunks {[0 0] (world/column {})}))
      table? (memory/observe [7 64 5] memory/crafting-table))))

(defn executable?
  "Is intent i something the world can do right now?"
  [w i]
  (let [counts (recipe/counts (:player/inventory w))]
    (case (:intent/kind i)
      :craft
      (let [r (recipe/by-id (:intent/recipe i))]
        (and (= :inventory (:intent/window i))           ; a table window is never open here
             (recipe/fits? r 2)
             (some? (recipe/clicks (:player/inventory w) r 2))))
      :place (and (= :crafting_table (:intent/item i)) (pos? (get counts :crafting_table 0)))
      :open-container (some? (memory/remembered w (:intent/target i)))
      :stairs-down (pos? (get counts :wooden_pickaxe 0)) ; down to stone, with nothing remembered: only with a pickaxe
      :wade (some? (swim/entry w (intent/feet w))) ; into water the bot remembers, from a bank it can stand on
      nil (contains? #{:no-log :no-water :in-water} (:plan/wait i)) ; gathering with no tree known, wading with no water or from inside it: wait
      false)))

(deftest the-planner-only-picks-executable-intents
  (let [r (tc/quick-check 400
                          (prop/for-all [held held-gen table? gen/boolean goal
                                         (gen/elements plan/targets)]
                                        (let [w (world-holding held table?)
                                              i (decide w goal)
                                              counts (recipe/counts (:player/inventory w))]
                                          (cond (plan/done-by w goal) (nil? i)
                                                (nil? i) false
                                                :else
                                                (and (executable? w i)
                                                     ;; gather (wait :no-log) only when no craft could make progress
                                                     (or (not= :no-log (:plan/wait i))
                                                         (zero? (get counts :oak_log 0))))))))]
    (is (:pass? r) (pr-str (select-keys r [:fail :shrunk])))))

(deftest goals-are-data
  (testing "a recipe is a goal row in the essay's shape"
    (let [row (first (filter #(= :craft/wooden_pickaxe (:goal/id %)) make/craft-rows))]
      (is (= {:tag/planks 3 :item/stick 2 :block/crafting_table :near} (:goal/needs row)))
      (is (= {:item/wooden_pickaxe 1} (:goal/provides row)))
      (is (= :provided? (:goal/done? row)))
      (is (= :craft (:goal/act row)))))
  (testing "a 2x2 recipe needs no table"
    (is (= {:tag/planks 2}
           (:goal/needs (first (filter #(= :craft/stick (:goal/id %)) make/craft-rows))))))
  (testing "every row, hand-written or generated, satisfies ::goal"
    (is (every? #(clojure.spec.alpha/valid? :clojurecraft.spec/goal %)
                (concat plan/goals make/craft-rows))))
  (testing "the table is printable and reads back as the same value"
    (is (= plan/goals (clojure.edn/read-string (pr-str plan/goals)))))
  (testing
    "no item target needs a per-goal method: wood, kit and pickaxe all plan through the same registries;
            the escapes and the shore trial (issue #5) are the other rules, done by the planner's trap and its escapes"
    (is (every? #(= :provided? (:goal/done? %)) (filter :goal/provides plan/targets)))
    (is (every? #(contains? #{:not-trapped-in :escaped-water?} (:goal/done? %))
                (remove :goal/provides plan/targets)))
    (is (= #{:needs :escape :wade} (set (keys (dissoc (methods plan/next-intent) :default)))))))

(def kit (first (filter #(= :kit (:goal/id %)) plan/goals)))

(defn intent-for
  [held goal]
  (let [i (decide (world-holding
                   (merge {:oak_log 0 :oak_planks 0 :stick 0 :crafting_table 0 :wooden_pickaxe 0}
                          held)
                   false)
                  goal)]
    (or (:intent/recipe i) (:plan/wait i) (:intent/kind i))))

(deftest the-needs-planner-walks-the-recipes
  (is (= :no-log (intent-for {} kit)) "nothing held and no tree known: wait for a log")
  (is (= :oak_planks (intent-for {:oak_log 1} kit)))
  (is (= :crafting_table (intent-for {:oak_planks 4} kit)))
  (is (= :stick (intent-for {:crafting_table 1 :oak_planks 2} kit)))
  (is (= :no-log (intent-for {:crafting_table 1} kit)) "the table is kept; sticks need another log")
  (is (nil? (intent-for {:crafting_table 1 :stick 4} kit)))
  (is (= :stick
         (intent-for {:oak_planks 2 :crafting_table 1 :stick 0}
                     {:goal/id :sticks :goal/provides {:item/stick 4}}))
      "sticks from planks, never the bamboo stick"))

(deftest the-needs-planner-uses-the-species-it-holds
  (let [w (-> (world-holding {} false)
              (assoc :player/inventory {0 {:item (recipe/item-id :birch_log) :count 1}}))]
    (is (= :birch_planks (:intent/recipe (decide w kit))))))

(deftest an-unreachable-table-is-replaced-not-walked-at
  ;; recorded live 2026-10-10: a remembered table 23 blocks away, a walk to it stuck three times,
  ;; the plan failed :stuck while the bot held planks enough for a new table
  (let [w (-> (world-holding {:oak_planks 6 :stick 4} false)
              (memory/observe [20 64 5] memory/crafting-table))]
    (is (= {:intent/kind :walk :intent/target [20 64 5]} (decide w pickaxe))
        "first, the table it remembers")
    (testing "after the walk failed, the table no longer counts"
      (is (= {:plan/wait :no-log} (decide (assoc w :plan/blacklist #{[20 64 5]}) pickaxe))
          "six planks are one short of a new table (4) and the pickaxe (3): get a log first")
      (is (= :crafting_table
             (:intent/recipe (decide (-> w
                                         (assoc :plan/blacklist #{[20 64 5]})
                                         (assoc-in [:player/inventory 0 :count] 7))
                                     pickaxe)))
          "with seven, craft the new table"))))

(deftest the-needs-planner-plans-over-the-table-it-is-given
  (let [w (world-holding {} false)
        wood (first (filter #(= :wood (:goal/id %)) plan/goals))
        without-gathering (vec (remove #(= :gather-log (:goal/id %)) plan/goals))]
    (is (= {:plan/wait :no-log} (make/decide w wood plan/goals))
        "the shipped table gathers: no tree known yet")
    (is (= {:plan/wait :stuck} (make/decide w wood without-gathering))
        "a table with no way to get a log is stuck")))

(deftest a-need-already-being-resolved-is-a-dead-end
  ;; the recipes alone hold the iron cycle: ingots from a block of nine ingots, and from nine
  ;; nuggets cut from an ingot
  (is (make/cycle? [#{:iron_block} #{:iron_ingot}] #{:iron_ingot}))
  (is (not (make/cycle? [#{:iron_block}] #{:iron_ingot})))
  (is (= :stuck
         (make/next-row (world-holding {} false)
                        {:goal/provides {:item/iron_ingot 4}}
                        (filterv #(not= :mine (:goal/act %)) plan/goals)))
      "no iron and nothing to mine it with: stuck, after trying each way round once"))

(deftest a-need-keyed-by-an-item-set-is-counted-not-a-block
  (is (make/block-need? :block/furnace))
  (is (not (make/block-need? :item/coal)))
  (is (not (make/block-need? #{:dried_kelp_block :coal_block})) "a set has no namespace to ask")
  (is (= [{} :stuck]
         (make/resolve-need (world-holding {} false) plan/goals {} #{:diamond :emerald} 1 []))))

(defn with-furnace
  "w with a furnace remembered two blocks from the bot."
  [w]
  (memory/observe w [7 64 5] (blocks/default-state :furnace)))

(defn ingots [n] {:goal/id :ingots :goal/provides {:item/iron_ingot n} :goal/done? :provided?})

(deftest fuel-is-planned-before-a-smelt
  (let [row #(:goal/id
              (make/next-row (with-furnace (world-holding % false)) (ingots 2) plan/goals))]
    (is (= :smelt/iron_ingot_from_smelting_raw_iron (row {:raw_iron 2 :coal 1})))
    (is (= :smelt/iron_ingot_from_smelting_raw_iron (row {:raw_iron 2 :oak_planks 2}))
        "two planks burn 600 ticks, the 400 two ingots need")
    (is (= :craft/oak_planks (row {:raw_iron 2 :oak_planks 1 :oak_log 1}))
        "one plank is short: the log becomes planks to burn")
    (is (= :gather-log (row {:raw_iron 2})) "nothing to burn: wood, until coal can be mined")
    (is (= :craft/oak_planks (row {:raw_iron 2 :oak_log 1 :stone_pickaxe 1}))
        "a held log burns before coal is mined for")
    (is (= :mine-coal
           (:goal/id (make/next-row
                      (with-furnace (world-holding {:raw_iron 9 :oak_log 1 :stone_pickaxe 1} false))
                      (ingots 9)
                      plan/goals)))
        "nine ingots need more than a log's four planks: coal"))
  (testing "fuel is netted after the row's other needs and the target's earlier provides"
    (is (= [{:coal 0 :oak_planks 1} 0] (make/burn {:coal 1 :oak_planks 1} 1600)) "coal first")
    (is (= [{:oak_planks 0 :stick 1} 0] (make/burn {:oak_planks 2 :stick 1} 500))
        "whole planks: two for 500 ticks")))

(def fuel-held-gen
  (gen/hash-map :raw_iron (gen/choose 0 9)
                :coal (gen/choose 0 2)
                :oak_planks (gen/choose 0 9)
                :oak_log (gen/choose 0 3)
                :stick (gen/choose 0 5)))

(deftest the-planner-never-smelts-without-the-fuel-for-it
  ;; issue #6: steve's races stalled at three ingots when planks ran out mid-smelt; here a
  ;; smelt is chosen only when the netted inventory burns 200 ticks for every ingot asked
  (let [r (tc/quick-check 400
                          (prop/for-all [held fuel-held-gen n (gen/choose 1 8)]
                                        (let [w (with-furnace (world-holding held false))
                                              row (make/next-row w (ingots n) plan/goals)
                                              counts (recipe/counts (:player/inventory w))]
                                          (or (not= :smelt (:goal/act row))
                                              (and
                                               (>= (smelt/fuel-ticks counts :raw_iron) (* 200 n))
                                               (not= {:plan/wait :no-fuel} (plan/act w row)))))))]
    (is (:pass? r) (pr-str (select-keys r [:fail :shrunk])))))

(deftest iron-from-nothing-starts-with-a-log
  ;; ingots <- smelt raw iron <- :mine-iron, holding a stone pickaxe <- cobblestone <- :mine-stone,
  ;; holding a wooden pickaxe <- planks <- a log
  (is (= :gather-log
         (:goal/id (make/next-row (world-holding {} false)
                                  {:goal/provides {:item/iron_ingot 4}}
                                  plan/goals))))
  (is (= :mine-iron
         (:goal/id (make/next-row (with-furnace (world-holding {:stone_pickaxe 1 :coal 1} false))
                                  {:goal/provides {:item/iron_ingot 4}}
                                  plan/goals)))
      "with the pickaxe and the fuel: mine the ore"))

(deftest the-iron-kit-is-planned-in-order
  ;; the kit's provides are netted against one inventory: three ingots go to the bucket, one to
  ;; the flint and steel, so iron already spent is never mined again
  (let [kit (first (filter #(= :iron-kit (:goal/id %)) plan/goals))
        row (fn [held & {:keys [table? furnace?]}]
              (let [w (cond-> (world-holding held table?) furnace? with-furnace)
                    r (make/next-row w kit plan/goals)]
                (if (map? r) (:goal/id r) r)))]
    (is (= :mine-iron (row {:stone_pickaxe 1})) "the ore first, with the stone pickaxe")
    (is (= :craft/furnace (row {:stone_pickaxe 1 :raw_iron 4 :coal 1 :cobblestone 8} :table? true))
        "iron and fuel held: the furnace, from eight cobblestone")
    (is (= :smelt/iron_ingot_from_smelting_raw_iron
           (row {:stone_pickaxe 1 :raw_iron 4 :coal 1} :table? true :furnace? true)))
    (is (= :craft/flint_and_steel (row {:stone_pickaxe 1 :iron_ingot 4 :flint 1} :table? true))
        "the flint and steel first, while the ingots are all in hand")
    (is (= :flint-from-gravel (row {:stone_pickaxe 1 :iron_ingot 4 :gravel 1})))
    (is (= :dig-gravel (row {:stone_pickaxe 1 :iron_ingot 4})) "all four ingots held, still below")
    (is (= :gather-log (row {:iron_ingot 4}))
        "gravel may lie below: no pickaxe, and one is made first")
    (is (= :craft/bucket (row {:stone_pickaxe 1 :iron_ingot 3 :flint_and_steel 1} :table? true)))
    (is (= :fill-bucket (row {:stone_pickaxe 1 :bucket 1 :flint_and_steel 1}))
        "the bucket is iron spent")
    (is (nil? (row {:water_bucket 1 :flint_and_steel 1})) "the kit held: nothing to do")))
