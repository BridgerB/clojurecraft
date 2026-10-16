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
            [clojurecraft.build]
            [clojurecraft.fixtures :as fx]
            [clojurecraft.fortress]
            [clojurecraft.game :as game]
            [clojurecraft.intent :as intent]
            [clojurecraft.make :as make]
            [clojurecraft.memory :as memory]
            [clojurecraft.observe]
            [clojurecraft.plan :as plan]
            [clojurecraft.recipe :as recipe]
            [clojurecraft.smelt :as smelt]
            [clojurecraft.spec]
            [clojurecraft.stone]
            [clojurecraft.swim :as swim]
            [clojurecraft.terrain :as terrain]
            [clojurecraft.wood :as wood]
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
      :walk (intent/routable? w i) ; to stone in the floor beside the feet, which a route reaches
      :dig (some? (memory/remembered w (:intent/target i))) ; the placed table, taken along
      :wade (some? (swim/entry w (intent/feet w))) ; into water the bot remembers, from a bank it can stand on
      nil
      (contains? #{:no-log :no-water :in-water :no-site :other-dimension :no-mob :looking}
                 (:plan/wait i)) ; gathering with no tree known, wading with no water or from inside it, a build with no lava near, the fortress from the overworld, the entities trial before any health is known, the attack trial looking round: wait
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
            the escapes and the shore trial (issue #5) are the other rules, done by the planner's trap and its escapes,
            the meal, the armour and the fights (issue #259), done by the server's food, slots and mobs, and the
            entities trial (issue #258), done by a living entity's health the server sent"
    (is (every? #(= :provided? (:goal/done? %)) (filter :goal/provides plan/targets)))
    (is (every? #(contains? #{:not-trapped-in :escaped-water? :built? :at-fortress? :fed? :dressed?
                              :armoured? :health-seen? :safe? :cleared? :provisioned? :armed?}
                            (:goal/done? %))
                (remove :goal/provides plan/targets)))
    (is (= #{:needs :escape :wade :build :fortress :eat :wear :armour :watch :defend :attack :hunt
             :sword}
           (set (keys (dissoc (methods plan/next-intent) :default)))))))

(def kit (first (filter #(= :kit (:goal/id %)) plan/goals)))

(def portal-kit (first (filter #(= :portal-kit (:goal/id %)) plan/goals)))

(deftest the-portal-kit-is-reached-only-beside-the-water-bucket
  ;; race 38477665442 (issue #49): a chained run reached :portal-kit holding its only bucket
  ;; still empty and 192 cobblestone, filled that bucket at the pond, and the cast then found
  ;; no empty bucket for the lava. The kit counts the water bucket beside the empty one
  (is (not (plan/done-by (world-holding {:bucket 1 :cobblestone 192} false) portal-kit))
      "one bucket, still empty: the water would spend it")
  (is (not (plan/done-by (world-holding {:water_bucket 1 :cobblestone 192} false) portal-kit))
      "the water bucket alone: nothing to carry lava in")
  (is (plan/done-by (world-holding {:water_bucket 1 :bucket 1 :cobblestone 192} false) portal-kit)))

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

(deftest a-pickaxe-for-the-coal-is-made-from-what-is-carried
  ;; issue #36: race 38477665442 bot Clj_r20 at y 15 held raw iron, a wooden pickaxe, two sticks,
  ;; a table and cobblestone, and no coal. The ingots' fuel counted the sticks as fuel, so the
  ;; stone pickaxe they make was short of them, and the coal was dug with the last pickaxe until
  ;; it broke. Now the sticks are a pickaxe's, and the coal row keeps a spare.
  (let [held {:raw_iron 6 :wooden_pickaxe 1 :stick 2 :cobblestone 64 :crafting_table 1}
        world (fn [held table?]
                (cond-> (with-furnace (world-holding held false))
                  table? (memory/observe [3 64 5] memory/crafting-table))) ; the furnace is at [7 64 5]
        row (fn [held table?] (:goal/id (make/next-row (world held table?) (ingots 4) plan/goals)))]
    (is (= :place-table (row held false))
        "the table goes down for a stone pickaxe, not up for wood")
    (is (= :craft/stone_pickaxe (row held true)) "with the table near, the stone pickaxe is made")
    (is (= :mine-coal (row (assoc held :stone_pickaxe 1) true))
        "two pickaxes held, one and its spare: the coal is dug")))

(deftest fuel-is-planned-before-a-smelt
  (let [row #(:goal/id
              (make/next-row (with-furnace (world-holding % false)) (ingots 2) plan/goals))]
    (is (= :smelt/iron_ingot_from_smelting_raw_iron (row {:raw_iron 2 :coal 1})))
    (is (= :smelt/iron_ingot_from_smelting_raw_iron (row {:raw_iron 2 :oak_planks 2}))
        "two planks burn 600 ticks, the 400 two ingots need")
    (is (= :craft/oak_planks (row {:raw_iron 2 :oak_planks 1 :oak_log 1}))
        "one plank is short: the log becomes planks to burn")
    (is (= :gather-log (row {:raw_iron 2})) "nothing to burn: wood, until coal can be mined")
    (is (= :craft/oak_planks (row {:raw_iron 2 :oak_log 2 :stone_pickaxe 1}))
        "a held log burns before coal is mined for, one kept back for a new pickaxe")
    ;; a spare pickaxe held too: the coal row keeps one (issue #36), and these are about the fuel
    (is (= :mine-coal (row {:raw_iron 2 :oak_log 1 :stone_pickaxe 1 :wooden_pickaxe 1}))
        "the last log is kept back: coal")
    (is (= :mine-coal
           (:goal/id (make/next-row (with-furnace
                                     (world-holding
                                      {:raw_iron 9 :oak_log 1 :stone_pickaxe 1 :wooden_pickaxe 1}
                                      false))
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
    (is (= :craft/bucket (row {:stone_pickaxe 2 :iron_ingot 3 :flint_and_steel 1} :table? true)))
    (is (= :fill-bucket (row {:stone_pickaxe 2 :bucket 1 :flint_and_steel 1}))
        "the bucket is iron spent")
    (is (= :fill-bucket (row {:stone_pickaxe 1 :bucket 1 :flint_and_steel 1}))
        "one pickaxe and no wood: a spare is never worth a trip for wood (gym batch iron-a7)")
    (is (=
         :craft/stone_pickaxe
         (row {:stone_pickaxe 1 :bucket 1 :flint_and_steel 1 :stick 2 :cobblestone 3} :table? true))
        "a spare made from what is held and near")
    (is (= :mine-iron (row {:stone_pickaxe 1 :iron_ingot 2 :flint_and_steel 1} :table? true))
        "the third ingot's ore, not wood for a spare")
    (is (nil? (row {:water_bucket 1 :flint_and_steel 1})) "the kit held: nothing to do")))

(deftest a-last-stone-pickaxe-underground-goes-for-a-log
  ;; full run 068485c wore the last stone pickaxe out at y 16. The spare is gathered while one
  ;; still digs. The trunk is remembered above the near band and no route reaches it, so the
  ;; gather is a climb. On the open sky with no tree it is not.
  (let [col (world/column {[8 69 8] 1 [8 70 8] 136})
        w (-> (with-furnace (world-holding {:stone_pickaxe 1 :coal 1} false))
              (assoc :player/pos [5.5 50.0 5.5] :world/chunks {[0 0] col})
              (memory/remember-column [0 0] col))]
    (is (not (terrain/under-sky? w (intent/feet w))))
    (is (= :gather-log
           (:goal/id (make/next-row w {:goal/provides {:item/iron_ingot 4}} plan/goals))))))

(deftest a-climb-that-came-out-here-is-not-cut-again-for-a-spare
  ;; sim race 38806318231 (issue #161, 206 runs in 73 rounds, world R1/37): the spare pickaxe's
  ;; gather climbed from the iron mine to the surface, found no tree in reach there, the mine cut
  ;; stairs back down, and the gather climbed again, every twenty seconds for ninety minutes.
  ;; Once a climb came out near here a while ago, the spare's climb is not worth it; the ore is
  (let [col (world/column {[8 69 8] 1 [8 70 8] 136})
        w (-> (with-furnace (world-holding {:stone_pickaxe 1 :coal 1} false))
              (assoc :player/pos [5.5 50.0 5.5] :world/chunks {[0 0] col} :time/now 1000000)
              (memory/remember-column [0 0] col))
        row #(:goal/id (make/next-row (assoc w :plan/surfaced %)
                                      {:goal/provides {:item/iron_ingot 4}}
                                      plan/goals))]
    (is (= :mine-iron (row [[6 66 6] 990000])) "came out over here ten seconds ago: the ore")
    (is (= :gather-log (row [[6 66 6] 300000])) "came out long ago: the climb again")
    (is (= :gather-log (row [[60 66 60] 990000])) "came out far off: a climb from here")))

(deftest a-full-inventory-throws-junk-before-it-goes-for-a-log
  ;; sim race 38806318231 (issue #161, world R3/7): 36 slots full of cobblestone, stones, sand,
  ;; lapis and copper, three planks short of a table, the bot dug log after log and every collect
  ;; timed out :not-picked-up. A fetch with no slot free throws junk out first
  (let [w (world-holding {} false)
        full (assoc (into {}
                          (for [p (range 35)] [p {:item (recipe/item-id :cobblestone) :count 64}]))
                    35
                    {:item (recipe/item-id :sand) :count 6})
        gather {:goal/id :gather-log :goal/act :gather}]
    (is (= {:intent/kind :toss} (make/make-room (assoc w :player/inventory full) gather))
        "every slot full and junk held: throw it first")
    (is (nil? (make/make-room (assoc w :player/inventory (dissoc full 3)) gather)) "a slot free")
    (is (nil? (make/make-room (assoc w :player/inventory full)
                              {:goal/id :craft/stick :goal/act :craft}))
        "a craft fetches nothing: its own window makes room")))

(def sky-shaft
  "Air from a pocket at [2 50 8] up a stair to [8 56 8], that column open to the sky. A log at
   [14 70 14] is sealed in stone, past the near band, and no route reaches it."
  (merge (into {} (for [i (range 7) dy [0 1 2]] [[(+ 2 i) (+ 50 i dy) 8] blocks/air]))
         (into {} (for [y (range 59 64)] [[8 y 8] blocks/air]))
         (into {} (for [x (range 12 17) y (range 68 74) z (range 12 17)] [[x y z] 1]))
         {[14 70 14] 136}))

(deftest a-shaft-to-the-sky-is-not-a-spare
  ;; full run f277605 walked out of the iron mine for a spare pickaxe and cut straight back
  ;; down, for five hours: the walk reaches the sky, and from there no trunk could be walked to
  (let [col (world/column sky-shaft)
        w (-> (with-furnace (world-holding {:stone_pickaxe 1 :coal 1} false))
              (assoc :player/pos [2.5 50.0 8.5] :world/chunks {[0 0] col})
              (memory/remember-column [0 0] col))
        goal {:goal/provides {:item/iron_ingot 4}}]
    (is (not (terrain/under-sky? w (intent/feet w))))
    (is (= :opening (:intent/for (wood/gather-next w))))
    (is (= :mine-iron (:goal/id (make/next-row w goal plan/goals))))))

(deftest a-pickaxe-for-the-gravel-is-made-from-what-is-carried
  ;; issue #67: race 38477665442 bot Clj_r19 tunnelled for gravel at y 40 after the water, with
  ;; one pickaxe, two sticks and 245 cobblestone, its table left at the furnace, until both
  ;; pickaxes broke. The gravel row keeps a spare, as the coal row does.
  (let [kit (first (filter #(= :flint-and-steel (:goal/id %)) plan/goals))
        row (fn [held table?]
              (:goal/id (make/next-row (world-holding held table?) kit plan/goals)))]
    (is (= :dig-gravel (row {:stone_pickaxe 2 :iron_ingot 1} false))
        "a pickaxe and its spare held: the gravel is dug")
    (is (= :dig-gravel (row {:stone_pickaxe 1 :iron_ingot 1} false))
        "one pickaxe and no wood: no spare is worth a trip for wood")
    (is (= :place-table
           (row {:stone_pickaxe 1 :iron_ingot 1 :stick 2 :cobblestone 3 :crafting_table 1} false))
        "the table carried goes down for the spare")
    (is (= :craft/stone_pickaxe (row {:stone_pickaxe 1 :iron_ingot 1 :stick 2 :cobblestone 3} true))
        "with the table near, the spare is made before the gravel")))

(deftest the-table-goes-along-to-the-water
  ;; race 38477665442 bots Clj_r19, Clj_r23 and Clj_r06 crafted the bucket at the furnace, left
  ;; the table there and climbed out for the water: the pickaxe the climb wore out could not be
  ;; made again from the sticks and cobblestone they carried (issues #39 and #67)
  (let [kit (first (filter #(= :water-bucket (:goal/id %)) plan/goals))
        w (world-holding {:stone_pickaxe 2 :bucket 1} true)]
    (is (= :fill-bucket (:goal/id (make/next-row w kit plan/goals))))
    (is (= {:intent/kind :dig :intent/target [7 64 5] :intent/for :take-table}
           (make/decide w kit plan/goals))
        "the table within reach is dug up before the scoop goes out")
    (is (not= :dig
              (:intent/kind (make/decide
                             (world-holding {:stone_pickaxe 2 :bucket 1 :crafting_table 1} true)
                             kit
                             plan/goals)))
        "a table already held is not dug for")))

(defn worn
  "w with its first tool of item name n carrying damage: the server's :damage component."
  [w n damage]
  (let [id (recipe/item-id n)
        slot (some (fn [[s {:keys [item]}]] (when (= item id) s)) (:player/inventory w))]
    (assoc-in w [:player/inventory slot :components] {:damage damage})))

(deftest a-worn-pickaxe-is-no-spare
  ;; issue #39: race 38477665442 bot Clj_r22 tunnelled for coal at y 16 holding a stone pickaxe
  ;; and, as its spare, the wooden one that dug the first cobblestone, 33 digs left in it; both
  ;; broke in one tunnel with two sticks, a table and 173 cobblestone in hand
  (let [held {:raw_iron 4 :stone_pickaxe 1 :wooden_pickaxe 1 :stick 2 :cobblestone 64}
        with-table #(memory/observe % [3 64 5] memory/crafting-table) ; the furnace is at [7 64 5]
        w (with-table (with-furnace (world-holding held false)))]
    (is (= 59 (make/uses-left {:item (recipe/item-id :wooden_pickaxe) :count 1})) "fresh")
    (is (= 33
           (make/uses-left
            {:item (recipe/item-id :wooden_pickaxe) :count 1 :components {:damage 26}})))
    (is (nil? (make/uses-left {:item (recipe/item-id :cobblestone) :count 3})) "no tool")
    (is (= :mine-coal (:goal/id (make/next-row w (ingots 4) plan/goals)))
        "a fresh wooden pickaxe beside the stone one is the spare: the coal is dug")
    (is (= :craft/stone_pickaxe
           (:goal/id (make/next-row (worn w :wooden_pickaxe 26) (ingots 4) plan/goals)))
        "worn under spare-uses, it is no spare: a stone one is made from what is carried")
    (is (= :mine-coal
           (:goal/id (make/next-row (worn (with-table (with-furnace
                                                       (world-holding (dissoc held :stick) false)))
                                          :wooden_pickaxe
                                          26)
                                    (ingots 4)
                                    plan/goals)))
        "with nothing to make one from, the coal is dug all the same")))

(deftest sticks-for-two-more-pickaxes-are-gathered-before-the-iron
  ;; issue #39: bots Clj_r23 and Clj_r06 left the forest with three logs' worth, made three
  ;; stone pickaxes between the stairs down, the tunnels and the climb out for the water, and
  ;; broke the last on the climb. Four sticks go down with the spare pickaxe, gathered at the
  ;; trees while a walk to one can be started
  (let [col (world/column {[8 64 8] 136 [8 65 8] 136})
        with-tree (fn [held]
                    (-> (with-furnace (world-holding held false))
                        (memory/observe [3 64 5] memory/crafting-table) ; the furnace is at [7 64 5]
                        (assoc :world/chunks {[0 0] col})
                        (memory/remember-column [0 0] col)))
        row (fn [w] (:goal/id (make/next-row w (ingots 4) plan/goals)))]
    (is (= :gather-log (row (with-tree {:stone_pickaxe 2 :coal 1})))
        "a trunk in reach: the sticks' log, before the descent")
    (is (= :craft/stick (row (with-tree {:stone_pickaxe 2 :coal 1 :oak_planks 2})))
        "planks held: the sticks are made")
    (is (= :mine-iron (row (with-tree {:stone_pickaxe 2 :coal 1 :stick 4}))) "four held: the iron")
    (is (= :mine-iron (row (with-furnace (world-holding {:stone_pickaxe 2 :coal 1} false))))
        "no tree to walk to: no trip for the sticks")))

(deftest a-table-placed-where-a-redig-failed-is-near
  ;; issue #43: the planner's view of race 38477665442 bot Clj_r04 after its redig failed at
  ;; [2098 39 2037] and the table was placed there; with the cell blacklisted, the table was not
  ;; near and the spare pickaxe's row was :place-table again with no table to place
  (let [kit (first (filter #(= :portal-kit (:goal/id %)) plan/goals))
        held {:stone_pickaxe 1 :stick 2 :cobblestone 488 :coal 9 :flint_and_steel 1 :water_bucket 1}
        w (world-holding held true)]
    (is (= [7 64 5] (make/near w :crafting_table)))
    (is (= :craft/stone_pickaxe (:goal/id (make/next-row w kit plan/goals)))
        "the spare, from the table")
    (is (nil? (make/near (assoc w :plan/blacklist #{[7 64 5]}) :crafting_table))
        "a table the plan blacklisted (a walk to it failed) is not near: it is replaced")))

(deftest a-log-taken-after-a-climb-out-makes-the-next-climb-worth-it
  ;; the full sim run of world R4/30 seed 1 (issue #161's second round, 74fa27b against 2736e27):
  ;; it came out of the ground for its first logs near the landing and took them; two minutes on,
  ;; its stone pickaxe made at y 60, the spare's short climb back to those trees was declined, and
  ;; the run timed out at the water bucket. A log picked up ends the climb's mark
  (let [w (assoc (world-holding {} false) :plan/surfaced [[6 66 6] 990000])
        done #(plan/finish-intent w {:intent/kind :collect :intent/for % :intent/status :done})]
    (is (nil? (:plan/surfaced (done :log))) "a log picked up: the climb came out to trees")
    (is (= [[6 66 6] 990000] (:plan/surfaced (done :mine-iron))) "ore picked up: the mark kept")))
