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
