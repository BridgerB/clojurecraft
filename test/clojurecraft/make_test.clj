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
            [clojurecraft.fixtures :as fx]
            [clojurecraft.game :as game]
            [clojurecraft.make :as make]
            [clojurecraft.memory :as memory]
            [clojurecraft.plan :as plan]
            [clojurecraft.recipe :as recipe]
            [clojurecraft.spec]
            [clojurecraft.world :as world]))

(def pickaxe (first (filter #(= :pickaxe (:goal/id %)) plan/goals)))

(def held-gen
  (gen/hash-map :oak_log (gen/choose 0 3) :oak_planks (gen/choose 0 9) :stick (gen/choose 0 5)
                :crafting_table (gen/choose 0 1) :wooden_pickaxe (gen/frequency [[9 (gen/return 0)] [1 (gen/return 1)]])))

(defn world-holding
  "Standing on bare ground with no trees; optionally a remembered table one block away."
  [held table?]
  (let [inv (into {} (keep-indexed (fn [i [item n]] (when (pos? n) [i {:item (recipe/item-id item) :count n}])) held))]
    (cond-> (-> (game/init fx/opts)
                (assoc :bot/phase :play :player/pos [5.5 64.0 5.5] :player/loaded? true :player/on-ground? true
                       :player/inventory inv :world/chunks {[0 0] (world/column {})}))
      table? (memory/observe [7 64 5] memory/crafting-table))))

(defn executable?
  "Is intent i something the world can do right now?"
  [w i]
  (let [counts (recipe/counts (:player/inventory w))]
    (case (:intent/kind i)
      :craft (let [r (recipe/by-id (:intent/recipe i))]
               (and (= :inventory (:intent/window i))            ; a table window is never open here
                    (recipe/fits? r 2)
                    (some? (recipe/clicks (:player/inventory w) r 2))))
      :place (and (= :crafting_table (:intent/item i)) (pos? (get counts :crafting_table 0)))
      :open-container (some? (memory/remembered w (:intent/target i)))
      nil (= :no-log (:plan/wait i))                            ; gathering with no tree known: wait
      false)))

(deftest the-planner-only-picks-executable-intents
  (let [r (tc/quick-check
           400
           (prop/for-all [held held-gen table? gen/boolean goal (gen/elements plan/targets)]
                         (let [w (world-holding held table?)
                               i (make/decide w goal)
                               counts (recipe/counts (:player/inventory w))]
                           (cond
                             (plan/done-by w goal) (nil? i)
                             (nil? i) false
                             :else (and (executable? w i)
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
    (is (= {:tag/planks 2} (:goal/needs (first (filter #(= :craft/stick (:goal/id %)) make/craft-rows))))))
  (testing "every row, hand-written or generated, satisfies ::goal"
    (is (every? #(clojure.spec.alpha/valid? :clojurecraft.spec/goal %) (concat plan/goals make/craft-rows))))
  (testing "the table is printable and reads back as the same value"
    (is (= plan/goals (clojure.edn/read-string (pr-str plan/goals)))))
  (testing "no target needs a per-goal method: wood, kit and pickaxe all plan through the same registries"
    (is (every? #(= :provided? (:goal/done? %)) plan/targets))
    (is (= #{:needs} (set (keys (dissoc (methods plan/next-intent) :default)))))))

(def kit (first (filter #(= :kit (:goal/id %)) plan/goals)))

(defn intent-for [held goal]
  (let [i (make/decide (world-holding (merge {:oak_log 0 :oak_planks 0 :stick 0 :crafting_table 0 :wooden_pickaxe 0} held) false)
                       goal)]
    (or (:intent/recipe i) (:plan/wait i) (:intent/kind i))))

(deftest the-needs-planner-walks-the-recipes
  (is (= :no-log (intent-for {} kit)) "nothing held and no tree known: wait for a log")
  (is (= :oak_planks (intent-for {:oak_log 1} kit)))
  (is (= :crafting_table (intent-for {:oak_planks 4} kit)))
  (is (= :stick (intent-for {:crafting_table 1 :oak_planks 2} kit)))
  (is (= :no-log (intent-for {:crafting_table 1} kit)) "the table is kept; sticks need another log")
  (is (nil? (intent-for {:crafting_table 1 :stick 4} kit)))
  (is (= :stick (intent-for {:oak_planks 2 :crafting_table 1 :stick 0} {:goal/id :sticks :goal/provides {:item/stick 4}}))
      "sticks from planks, never the bamboo stick"))

(deftest the-needs-planner-uses-the-species-it-holds
  (let [w (-> (world-holding {} false)
              (assoc :player/inventory {0 {:item (recipe/item-id :birch_log) :count 1}}))]
    (is (= :birch_planks (:intent/recipe (make/decide w kit))))))

(deftest an-unreachable-table-is-replaced-not-walked-at
  ;; recorded live 2026-10-10: a remembered table 23 blocks away, a walk to it stuck three times,
  ;; the plan failed :stuck while the bot held planks enough for a new table
  (let [w (-> (world-holding {:oak_planks 6 :stick 4} false)
              (memory/observe [20 64 5] memory/crafting-table))]
    (is (= {:intent/kind :walk :intent/target [20 64 5]} (make/decide w pickaxe)) "first, the table it remembers")
    (testing "after the walk failed, the table no longer counts"
      (is (= {:plan/wait :no-log} (make/decide (assoc w :plan/blacklist #{[20 64 5]}) pickaxe))
          "six planks are one short of a new table (4) and the pickaxe (3): get a log first")
      (is (= :crafting_table (:intent/recipe (make/decide (-> w (assoc :plan/blacklist #{[20 64 5]})
                                                              (assoc-in [:player/inventory 0 :count] 7))
                                                          pickaxe)))
          "with seven, craft the new table"))))
