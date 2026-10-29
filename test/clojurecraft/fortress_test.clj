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
            [clojurecraft.game :as game]
            [clojurecraft.intent :as intent]
            [clojurecraft.memory :as memory]
            [clojurecraft.path :as path]
            [clojurecraft.plan :as plan]
            [clojurecraft.recipe :as recipe]
            [clojurecraft.terrain :as terrain]))

(def netherrack (blocks/default-state :netherrack))
(def air (blocks/default-state :air))

(deftest a-reached-nether-entry-does-not-strand-an-unfinished-fortress
  (let [w {:world/facts (memory/empty-facts)
           :player/pos [0.5 64.0 0.5]
           :time/now 0
           :plan/goals #{:nether :fortress}
           :plan/chain? true
           :plan/reached #{:nether}
           :player/dimension dimension/overworld}
        table (filter #(#{:nether :fortress :return-nether} (:goal/id %)) plan/goals)
        return-goal (first (filter #(= :return-nether (:goal/id %)) table))]
    (is (= :return-nether (:goal/id (plan/choose w table))))
    (is (= :nether (:goal/build return-goal)) "reuse the portal build and its supply planner")
    (is (= #{:nether} (:plan/reached (plan/reach w table))) "milestones remain historical")
    (is (= :fortress (:goal/id (plan/choose (assoc w :player/dimension dimension/nether) table))))
    (is (nil? (plan/choose (assoc w :plan/reached #{:nether :fortress}) table)))
    (is (nil? (plan/choose (assoc w :plan/goals #{:nether}) table)))
    (is (nil? (plan/choose (dissoc (assoc w :plan/goals #{:fortress}) :plan/reached) table))
        "a standalone trial does not build a portal")))

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
    (is (= #{[0 32 0] [2 64 0] [3 64 0]} (set (fortress/bricks w))))))

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

(deftest a-fortress-tunnel-makes-a-pickaxe-from-held-supplies
  (let [goal (first (filter #(= :fortress (:goal/id %)) plan/goals))
        w (assoc (rock {})
                 :world/facts (memory/empty-facts)
                 :player/dimension dimension/nether
                 :player/inventory
                 {0 {:item (recipe/item-id :stick) :count 2}
                  1 {:item (recipe/item-id :cobblestone) :count 3}
                  2 {:item (recipe/item-id :crafting_table) :count 1}})
        next #(plan/next-intent % goal plan/goals)]
    (is (= :place (:intent/kind (next w))) "place the table before tunneling without a tool")
    (is (= :tunnel-to
           (:intent/kind (next (assoc-in w
                                [:player/inventory 3]
                                {:item (recipe/item-id :stone_pickaxe) :count 1})))))
    (is (= :tunnel-to (:intent/kind (next (assoc w :player/inventory {}))))
        "no supplies: keep clearing the way by hand, not a hunt for overworld wood")
    (is (= {:plan/wait :other-dimension} (next (assoc w :player/dimension dimension/overworld))))))

(deftest a-fortress-tunnel-takes-its-placed-table-along
  ;; probe 39782256061 (issue #338, R3/29 seed 2): the pickaxe made in the Nether's tunnel left the
  ;; table standing, and the next, with sticks and 937 cobblestone in hand, had no table to be made at
  (let [goal (first (filter #(= :fortress (:goal/id %)) plan/goals))
        pick {:item (recipe/item-id :stone_pickaxe) :count 1}
        w (memory/observe (assoc (merge (game/init {:host "sim" :port 0 :name "Clj_test"})
                                        (rock {}))
                                 :world/facts (memory/empty-facts)
                                 :time/now 0
                                 :player/dimension dimension/nether
                                 :player/inventory {0 pick})
                          [1 64 0]
                          (blocks/default-state :crafting_table))
        next #(plan/next-intent % goal plan/goals)]
    (is (= :take-table (:intent/for (next w)))
        "a pickaxe made, the placed table within reach is dug")
    (is (= :tunnel-to
           (:intent/kind (next (assoc-in w
                                [:player/inventory 1]
                                {:item (recipe/item-id :crafting_table) :count 1}))))
        "a table held already: on with the tunnel")))

(deftest a-tunnel-replans-when-its-pickaxe-breaks
  (let [w (assoc (rock {})
                 :world/facts (memory/empty-facts)
                 :bot/phase :play
                 :bot/effects []
                 :time/now 0
                 :time/tick 0
                 :plan/attempts 2
                 :player/controls {:control/forward? true}
                 :player/inventory
                 {0 {:item (recipe/item-id :stick) :count 2}
                  1 {:item (recipe/item-id :cobblestone) :count 3}
                  2 {:item (recipe/item-id :crafting_table) :count 1}})
        op (build/next-op w (fortress/step-plan [0 64 0] [1 0 0]))
        i {:intent/kind :tunnel-to
           :intent/target [48 64 0]
           :intent/steps 32
           :intent/status :active
           :intent/from [0 64 0]
           :intent/move [1 0 0]
           :intent/op op}
        tick {:event/kind :tick :event/now 0}]
    (doseq [child [nil
                   {:intent/kind :dig
                    :intent/target (:build/pos op)
                    :intent/status :failed
                    :intent/reason :no-pickaxe}]]
      (let [i (cond-> i child (assoc :intent/child child))
            out (intent/run (assoc w :plan/intent i) i tick)
            finished (plan/finish-intent out (:plan/intent out))]
        (is (intent/done? (:plan/intent out)))
        (is (nil? (get-in out [:plan/intent :intent/reason])))
        (is (= {} (:player/controls out)))
        (is (zero? (:plan/attempts finished)))))
    (let [i (assoc i
                   :intent/child
                   {:intent/kind :dig
                    :intent/target (:build/pos op)
                    :intent/status :failed
                    :intent/reason :rejected}
                   :intent/rejections 2) ; the third dig the server would not do (stairs/max-rejections)
          out (intent/run (assoc w :plan/intent i) i tick)]
      (is (intent/failed? (:plan/intent out)))
      (is (= :rejected (get-in out [:plan/intent :intent/reason]))))))

(deftest a-tunnel-with-no-replacement-clears-slow-rock-by-hand
  (let [cell [1 65 0]
        w (assoc (rock {cell (blocks/default-state :nether_quartz_ore)})
                 :world/facts (memory/empty-facts)
                 :bot/phase :play
                 :bot/effects []
                 :time/now 0
                 :time/tick 0
                 :player/on-ground? true
                 :player/vel [0.0 0.0 0.0]
                 :player/inventory {})
        op (build/next-op w (fortress/step-plan [0 64 0] [1 0 0]))
        i {:intent/kind :tunnel-to
           :intent/target [48 64 0]
           :intent/steps 32
           :intent/status :active
           :intent/from [0 64 0]
           :intent/move [1 0 0]
           :intent/op op}
        out (intent/run (assoc w :plan/intent i) i {:event/kind :tick :event/now 0})]
    (is (> (intent/dig-time (terrain/block-at w cell)) build/hand-dig-limit))
    (is (= :active (get-in out [:plan/intent :intent/status])))
    (is (= :dig (get-in out [:plan/intent :intent/child :intent/kind])))
    (is (= cell (get-in out [:plan/intent :intent/child :intent/target])))
    (is (nil? (get-in out [:plan/intent :intent/reason])))))

(deftest a-tunnel-buried-in-gravel-digs-itself-out
  ;; Issue #338's R1-33-1: gravel fell into the feet's and head's cells mid-step; the stand-in's
  ;; walk had no route from inside a block and was asked again every tick for fifteen minutes.
  (let [gravel (blocks/default-state :gravel)
        from [0 64 0]
        move [-1 1 0]
        tick {:event/kind :tick :event/now 0}
        i {:intent/kind :tunnel-to
           :intent/target [-48 70 0]
           :intent/steps 32
           :intent/status :active
           :intent/from from
           :intent/move move
           :intent/op (peek (fortress/step-plan from move))}
        run (fn [overrides]
              (let [w (assoc (rock (merge {[-1 65 0] air [-1 66 0] air [0 66 0] air} overrides))
                             :world/facts (memory/empty-facts)
                             :bot/phase :play
                             :bot/effects []
                             :time/now 0
                             :time/tick 0
                             :player/on-ground? true
                             :player/vel [0.0 0.0 0.0]
                             :player/inventory {})]
                ;; two ticks: the first takes the dig as the op, the second starts its child
                (:plan/intent (nth (iterate #(intent/run % (:plan/intent %) tick)
                                            (assoc w :plan/intent i))
                                   2))))]
    (testing "the head's cell first, then the feet's"
      (let [out (run {[0 64 0] gravel [0 65 0] gravel})]
        (is (= :active (:intent/status out)))
        (is (= [0 65 0] (get-in out [:intent/op :build/pos])))
        (is (= :dig (get-in out [:intent/child :intent/kind])))
        (is (= [0 65 0] (get-in out [:intent/child :intent/target]))))
      (is (= [0 64 0] (get-in (run {[0 64 0] gravel}) [:intent/child :intent/target]))))
    (is (nil? (fortress/buried (rock {}) from)) "open body cells: the step goes on")
    (is (= move (:intent/move (run {[0 64 0] gravel})))
        "the step is kept for when the body is free")))

(deftest a-step-is-a-floor-the-cells-it-opens-and-standing-there
  (let [plan (fortress/step-plan [0 64 0] [1 0 0])]
    (is (= [:dig :place :dig :dig :stand-in] (map :build/op plan)))
    (is (= [1 63 0] (:build/pos (first plan))) "the floor under the cell stepped into")
    (is (= [[1 63 0] [1 65 0] [1 64 0]] (map :build/pos (filter #(= :dig (:build/op %)) plan)))
        "floor repaired before opening the body cells head first")
    (is (= [:landed-centred [1 64 0]] (:build/done (peek plan))))
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
    (is (fortress/safe? (assoc (rock {[1 63 0] lava})
                               :player/inventory
                               {0 {:item (recipe/item-id :cobblestone) :count 1}})
                        [0 64 0]
                        [1 0 0])
        "lava in the floor is filled: the floor op places into it")
    (is (not (fortress/safe? (rock {[1 63 0] air [1 62 0] air [1 61 0] air}) [0 64 0] [1 0 0]))
        "never out over a drop: an air floor is not a tunnel")
    (is (not (fortress/safe? (rock {[1 64 0] (blocks/default-state :obsidian)}) [0 64 0] [1 0 0]))
        "never through obsidian")
    (is (not (fortress/safe? (rock {[1 64 0] nil}) [0 64 0] [1 0 0])) "never into the unknown")
    (testing "the tunnel goes round: up when lava is beside the level step"
      (is (= [1 1 0] (fortress/choose-move (rock {[1 64 1] lava}) [0 64 0] [4 64 0]))))))

(deftest the-dry-tunnel-floors-its-way-across-a-cave
  ;; gym batch stronghold-room-b run 3: down at the frame's height in a cave, every step toward
  ;; the frame over air; the overworld's dry tunnel lays the floor, the Nether's never does
  (let [cave (into {} (for [x (range 1 5) y (range 60 67) z (range -4 5)] [[x y z] air]))
        held {0 {:item (recipe/item-id :cobblestone) :count 16}}
        w (assoc (rock cave) :player/inventory held)]
    (is (fortress/bridge? w [0 64 0] [1 0 0]) "air under the next cell, held by the feet's floor")
    (is (= [1 0 0] (fortress/next-move w [0 64 0] [48 64 0] fortress/climb-past true #{})))
    (is (not= [1 0 0] (fortress/next-move w [0 64 0] [48 64 0] fortress/climb-past false #{}))
        "the fortress's tunnel never bridges")
    (is (not (fortress/bridge? (rock cave) [0 64 0] [1 0 0])) "nothing to lay it from")
    (is (not (fortress/bridge? (assoc-in w [:world/blocks [1 60 0]] blocks/water-base)
                               [0 64 0]
                               [1 0 0]))
        "water under it: a causeway, not a cave's floor")
    (is (not (fortress/bridge? w [0 64 0] [1 -1 0])) "only a level step")))

(deftest no-tunnel-step-drops-a-fluid-bearing-bed
  (doseq [kind [:gravel :sand]
          height [1 3]]
    (let [bed (blocks/default-state kind)
          w (rock (merge (into {} (for [y (range 66 (+ 66 height))] [[1 y 0] bed]))
                         {[1 (+ 66 height) 0] blocks/lava-base}))]
      (is (not (fortress/safe? w [0 64 0] [1 0 0])) "fluid over a falling roof")
      (is (fortress/safe? (assoc-in w [:world/blocks [1 66 0]] netherrack) [0 64 0] [1 0 0])
          "a solid roof seals the fluid")))
  (let [w (rock {[2 65 0] (blocks/default-state :gravel) [2 64 0] blocks/lava-base})]
    (is (not (fortress/safe? w [0 64 0] [1 1 0]))
        "updating a neighbouring gravel bed over lava drops it too")))

(deftest no-tunnel-step-drops-a-bed-beside-fluid
  ;; Issue #339, Sim race 39113082582 R3/33: lava beside the falling roof, not above it,
  ;; followed the gravel into the tunnel until no dry footing remained.
  (doseq [kind [:gravel :sand]
          height [1 3 intent/falling-reach]
          [dx dz] [[1 0] [-1 0] [0 1] [0 -1]]
          fluid [blocks/lava-base blocks/water-base]]
    (let [bed (blocks/default-state kind)
          w (rock (merge (into {} (for [y (range 66 (+ 66 height))] [[1 y 0] bed]))
                         {[1 (+ 66 height) 0] netherrack}))
          flooded (assoc-in w [:world/blocks [(inc dx) (+ 65 height) dz]] fluid)]
      (is (fortress/safe? w [0 64 0] [1 0 0]) "a dry falling roof is still safe")
      (is (intent/exposes-fluid? flooded [1 65 0]) "a dig sees fluid beside the falling stack")
      (is (not (fortress/safe? flooded [0 64 0] [1 0 0])) "the step leaves the bed sealed")
      (is (fortress/safe? (assoc-in flooded [:world/blocks [1 66 0]] netherrack) [0 64 0] [1 0 0])
          "a non-falling roof keeps the fluid sealed"))))

(deftest a-tunnel-drops-an-unsafe-in-flight-step
  (let [from [0 64 0]
        move [1 0 0]
        ops (fortress/step-plan from move)
        tick {:event/kind :tick :event/now 0}]
    (doseq [op [(nth ops 2) (peek ops)]
            hazard [{[1 66 0] (blocks/default-state :gravel) [1 67 0] blocks/lava-base}
                    {[1 66 0] (blocks/default-state :gravel)
                     [1 67 0] (blocks/default-state :gravel)
                     [2 67 0] blocks/lava-base} {[2 65 0] blocks/lava-base}]]
      (let [enter? (= :stand-in (:build/op op))
            w (assoc (rock (merge (when enter? {[1 64 0] air [1 65 0] air}) hazard))
                     :bot/phase :play
                     :bot/effects []
                     :time/now 0
                     :time/tick 0
                     :player/controls {:control/forward? true})
            i {:intent/kind :tunnel-to
               :intent/target [10 64 0]
               :intent/steps 32
               :intent/status :active
               :intent/from from
               :intent/move move
               :intent/op op
               :intent/child {:intent/kind (if enter? :walk :dig)
                              :intent/target (if enter? [1 64 0] [1 65 0])
                              :intent/status :active}}
            out (intent/run (assoc w :plan/intent i) i tick)
            replanned (intent/run out (:plan/intent out) tick)]
        (is (= :active (get-in out [:plan/intent :intent/status])))
        (is (= {} (:player/controls out)))
        (is (every? #(not (contains? (:plan/intent out) %)) fortress/step-keys))
        (is (not= move (get-in replanned [:plan/intent :intent/move])))
        (is (fortress/safe? replanned from (get-in replanned [:plan/intent :intent/move])))))))

(deftest a-step-is-walked-into-with-no-build-of-its-own
  ;; build/approach-stand digs ground in the way of a :build's stand using its mold; a tunnel
  ;; step has no mold (no :intent/build), so the walk into the cell it opened is just a walk
  (let [w (rock {[1 64 0] air [1 65 0] air})
        op (peek (fortress/step-plan [0 64 0] [1 0 0]))]
    (is (= {:intent/kind :walk :intent/target [1 64 0] :intent/for :build}
           (build/op-child w {:intent/kind :tunnel-to :intent/target [9 64 0]} op)))))

(deftest a-tunnel-step-lands-in-the-middle-before-counting-it
  (let [from [0 64 0]
        move [1 -1 0]
        to [1 63 0]
        op (peek (fortress/step-plan from move))
        i {:intent/kind :tunnel-to
           :intent/target [10 63 0]
           :intent/steps 32
           :intent/status :active
           :intent/from from
           :intent/move move
           :intent/op (assoc op :build/done [:feet to])
           :intent/child {:intent/kind :walk :intent/target to :intent/for :build}}
        w (assoc (rock {[1 63 0] air [1 64 0] air [1 65 0] air})
                 :bot/phase :play
                 :bot/effects []
                 :time/now 0
                 :time/tick 0
                 :plan/intent i
                 :player/controls {:control/forward? true})
        run-at (fn [pos grounded?]
                 (intent/run (assoc w :player/pos pos :player/on-ground? grounded?)
                             i
                             {:event/kind :tick :event/now 0}))]
    (doseq [[pos grounded?] [[[1.05 63.0 0.5] true] [[1.5 63.4 0.5] false]]]
      (let [out (run-at pos grounded?)]
        (is (= op (get-in out [:plan/intent :intent/op])))
        (is (nil? (get-in out [:plan/intent :intent/taken])))
        (is (nil? (get-in out [:plan/intent :intent/child])))
        (is (not (build/satisfied? out op)))))
    (let [out (run-at [1.5 63.0 0.5] true)]
      (is (= 1 (get-in out [:plan/intent :intent/taken])))
      (is (nil? (get-in out [:plan/intent :intent/move])))
      (is (nil? (get-in out [:plan/intent :intent/child])))
      (is (= {} (:player/controls out))))))

(deftest a-tunnel-replaces-a-solid-floor-the-route-cannot-walk
  (let [floor [1 63 0]
        w (assoc (rock {floor (blocks/default-state :soul_sand) [1 64 0] air [1 65 0] air})
                 :player/inventory
                 {0 {:item (recipe/item-id :cobblestone) :count 8}})
        ops (fortress/step-plan [0 64 0] [1 0 0])
        cleared (assoc-in w [:world/blocks floor] air)
        filled (assoc-in w [:world/blocks floor] netherrack)]
    (is (fortress/safe? w [0 64 0] [1 0 0]))
    (is (not (path/standable? w [1 64 0])))
    (is (= :floor-clear (:build/step (build/next-op w ops))))
    (is (= floor (:build/pos (build/next-op w ops))))
    (is (= :place (:build/op (build/next-op cleared ops))))
    (is (= :stand-in (:build/op (build/next-op filled ops))))
    (is (= :found
           (:path/status (path/plan filled [0 64 0] {:goal/kind :block :goal/pos [1 64 0]}))))
    (testing "an in-flight failed entry walk is dropped for the newly required floor repair"
      (let [i {:intent/kind :tunnel-to
               :intent/target [10 64 0]
               :intent/steps 32
               :intent/status :active
               :intent/from [0 64 0]
               :intent/move [1 0 0]
               :intent/op (peek ops)
               :intent/child {:intent/kind :walk
                              :intent/target [1 64 0]
                              :intent/status :failed
                              :intent/reason :no-path}}
            out (intent/run
                 (assoc w :bot/phase :play :bot/effects [] :time/now 0 :time/tick 0 :plan/intent i)
                 i
                 {:event/kind :tick :event/now 0})]
        (is (= :active (get-in out [:plan/intent :intent/status])))
        (is (= :floor-clear (get-in out [:plan/intent :intent/op :build/step])))
        (is (nil? (get-in out [:plan/intent :intent/child])))
        (doseq [[p state] [[[1 62 0] blocks/lava-base] [floor (blocks/default-state :spawner)]]]
          (let [unsafe (intent/run (assoc-in (assoc out :plan/intent i) [:world/blocks p] state)
                                   i
                                   {:event/kind :tick :event/now 0})]
            (is (= :active (get-in unsafe [:plan/intent :intent/status])))
            (is (nil? (get-in unsafe [:plan/intent :intent/move])))
            (is (nil? (get-in unsafe [:plan/intent :intent/child])))))))
    (testing "repair never opens fluid or a protected floor"
      (doseq [fluid [blocks/lava-base blocks/water-base]
              p [[1 62 0] [2 63 0]]]
        (is (not (fortress/safe? (assoc-in w [:world/blocks p] fluid) [0 64 0] [1 0 0]))))
      (is (not (fortress/safe? (assoc-in w [:world/blocks floor] (blocks/default-state :spawner))
                               [0 64 0]
                               [1 0 0]))))))

(deftest a-tunnel-floors-with-carried-stone-after-cobblestone-is-spent
  ;; Issue #410: R1/34 and R2/39 spent their cobblestone with safe filler still carried.
  (let [w (assoc (rock {[1 63 0] blocks/lava-base})
                 :bot/phase :play
                 :bot/effects []
                 :time/now 0
                 :time/tick 0
                 :player/held-slot 0)
        op (build/next-op w (fortress/step-plan [0 64 0] [1 0 0]))
        i {:intent/kind :tunnel-to
           :intent/target [10 64 0]
           :intent/steps 32
           :intent/status :active
           :intent/from [0 64 0]
           :intent/move [1 0 0]}
        tick {:event/kind :tick :event/now 0}]
    (doseq [item [:cobblestone :cobbled_deepslate :andesite :diorite :granite :tuff :dirt]]
      (let [w (assoc w :player/inventory {0 {:item (recipe/item-id item) :count 1}})
            started (intent/run (assoc w :plan/intent i) i tick)
            out (intent/run started (:plan/intent started) tick)]
        (is (= item (get-in started [:plan/intent :intent/op :build/item])))
        (is (= item (get-in out [:plan/intent :intent/child :intent/item])))
        (is (= :active (get-in out [:plan/intent :intent/status])))))
    (testing "an in-flight placement of the exhausted material is replaced, even after failure"
      (doseq [status [:active :failed]]
        (let [i (assoc i
                       :intent/op op
                       :intent/child
                       {:intent/kind :place
                        :intent/item :cobblestone
                        :intent/cell [1 63 0]
                        :intent/status status
                        :intent/reason :not-held})
              w (assoc w :player/inventory {0 {:item (recipe/item-id :cobbled_deepslate) :count 1}})
              out (intent/run (assoc w :plan/intent i) i tick)]
          (is (= :active (get-in out [:plan/intent :intent/status])))
          (is (= :cobbled_deepslate (get-in out [:plan/intent :intent/op :build/item])))
          (is (nil? (get-in out [:plan/intent :intent/child]))))))
    (testing "falling blocks are not floor supplies"
      (let [w (assoc w
                     :player/inventory
                     {0 {:item (recipe/item-id :gravel) :count 64}
                      1 {:item (recipe/item-id :sand) :count 64}})
            out (intent/run (assoc w :plan/intent i) i tick)]
        (is (= :active (get-in out [:plan/intent :intent/status])) "given up, not failed")
        (is (nil? (get-in out [:plan/intent :intent/move])) "the step is dropped")
        (is (contains? (get-in out [:plan/intent :intent/tunnel-visited]) [1 64 0]))
        (is (nil? (get-in out [:plan/intent :intent/child])) "never placed, so never :not-held")
        (is (empty? (:bot/effects out)))))))

(deftest a-tunnel-resumes-a-reachable-entry-after-a-no-path
  (let [from [0 64 0]
        move [-1 1 0]
        to [-1 65 0]
        w (assoc (rock {[0 63 0] (blocks/default-state :soul_sand)
                        [0 66 0] air
                        [-1 65 0] air
                        [-1 66 0] air})
                 :bot/phase :play
                 :bot/effects []
                 :time/now 0
                 :time/tick 0
                 :player/controls {:control/forward? true})
        i {:intent/kind :tunnel-to
           :intent/target [-10 80 0]
           :intent/steps 32
           :intent/status :active
           :intent/from from
           :intent/move move
           :intent/op (peek (fortress/step-plan from move))
           :intent/child {:intent/kind :walk
                          :intent/for :build
                          :intent/target to
                          :intent/status :failed
                          :intent/reason :no-path
                          :intent/route :none
                          :intent/waypoints []}}
        tick {:event/kind :tick :event/now 0}
        out (intent/run (assoc w :plan/intent i) i tick)
        restarted (intent/run out (:plan/intent out) tick)
        walking (intent/run restarted (:plan/intent restarted) tick)]
    (is (= :active (get-in out [:plan/intent :intent/status])))
    (is (= {} (:player/controls out)))
    (is (nil? (get-in out [:plan/intent :intent/child])))
    (is (= move (get-in out [:plan/intent :intent/move])))
    (is (= :walk (get-in restarted [:plan/intent :intent/child :intent/kind])))
    (is (= :found (get-in walking [:plan/intent :intent/child :intent/route])))
    (testing "a route that could not be followed still exhausts its attempts"
      (let [i (assoc-in i [:intent/child :intent/route] :found)
            out (intent/run (assoc w :plan/intent i) i tick)]
        (is (intent/failed? (:plan/intent out)))
        (is (= :no-path (get-in out [:plan/intent :intent/reason])))))
    (testing "an entry that still has no route does not retry forever"
      (let [blocked (assoc-in w [:world/blocks [0 63 0]] air)
            out (intent/run (assoc blocked :plan/intent i) i tick)]
        (is (intent/failed? (:plan/intent out)))
        (is (= :no-path (get-in out [:plan/intent :intent/reason])))))))

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
                                  (= [:landed-centred to] (:build/done (peek plan)))
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

(deftest a-blocked-tunnel-can-retreat
  (let [wall (blocks/default-state :obsidian)
        w (rock (into {} (for [[x z] [[1 0] [0 1] [0 -1]] y (range 63 67)] [[x y z] wall])))]
    (is (= [-1 0 0] (fortress/choose-move w [0 64 0] [10 64 0])))
    (is (= [-1 0 0] (fortress/next-move w [0 64 0] [10 64 0] 3 false #{})))))

(deftest a-portal-is-walked-through-not-dug
  (let [portal (blocks/default-state :nether_portal)
        w (rock {[1 64 0] portal [1 65 0] portal})]
    (is (fortress/safe? w [0 64 0] [1 0 0]))
    (is (= :stand-in (:build/op (build/next-op w (fortress/step-plan [0 64 0] [1 0 0])))))))

(deftest a-tunnel-turns-onto-ground-instead-of-pacing-in-the-portal
  (let [obsidian (blocks/default-state :obsidian)
        portal (blocks/default-state :nether_portal)
        w (rock (merge (into {} (for [x (range -4 5) y (range 60 71) z (range -4 5)] [[x y z] air]))
                       {[0 63 0] obsidian
                        [-1 63 0] obsidian
                        [-1 64 0] portal
                        [-1 65 0] portal
                        [-2 63 1] netherrack}))
        w (assoc w
                 :player/pos [-0.5 64.0 0.5]
                 :bot/phase :play
                 :bot/effects []
                 :time/now 0
                 :time/tick 0)
        feet [-1 64 0]
        visited #{[0 64 0]}
        move (fortress/next-move w feet [10 64 0] 3 false visited)
        floor (fortress/plus (fortress/plus feet move) [0 -1 0])
        ops (fortress/step-plan feet move)
        i {:intent/kind :tunnel-to
           :intent/target [10 64 0]
           :intent/steps 32
           :intent/status :active
           :intent/tunnel-visited visited}
        started (intent/run (assoc w :plan/intent i :player/controls {:control/forward? true})
                            i
                            {:event/kind :tick :event/now 0})]
    (is (= [0 0 1] move) "a fresh turn before returning to the other portal cell")
    (is (not (fortress/safe? w feet move)) "a missing floor is still not safe to enter")
    (is (= :place (:build/op (build/next-op w ops))) "lay the floor before walking")
    (is (= {} (:player/controls started)) "stop before working an unfinished floor")
    (is (= (conj visited feet) (get-in started [:plan/intent :intent/tunnel-visited])))
    (is (= move (get-in started [:plan/intent :intent/move])))
    (is (fortress/safe? (assoc-in w [:world/blocks floor] netherrack) feet move))
    (is (= [1 0 0]
           (fortress/next-move (assoc-in w [:world/blocks [-1 63 0]] air)
                               feet
                               [10 64 0]
                               3
                               false
                               visited))
        "a floored turn starts only from solid footing")
    (is (= [1 0 0]
           (fortress/next-move (assoc-in w [:world/blocks [-2 63 1]] air)
                               feet
                               [10 64 0]
                               3
                               false
                               visited))
        "no floor extending away from ground into a drop")
    (is (not= move
              (fortress/next-move (assoc-in w [:world/blocks [-1 64 2]] blocks/water-base)
                                  feet
                                  [10 64 0]
                                  3
                                  true
                                  visited))
        "an overworld turn still refuses water beside the body")
    (is (= [1 0 0]
           (fortress/next-move
            (update w
                    :world/blocks
                    merge
                    (into {} (for [x (range -4 5) z (range -4 5)] [[x 62 z] blocks/lava-base])))
            feet
            [10 64 0]
            3
            false
            visited))
        "retreat rather than laying a turn over lava")))

(defn ledge
  "Issue #338's R3-6-1 as a world: a cavern (x and z -4..4, y 60..70 air) over lava at y 59, two
   blocks of a ledge at y 63 under the feet at [0 64 0] and the cell east of them, every way
   round them air, with overrides on top."
  [overrides]
  (rock (merge (into {} (for [x (range -4 5) y (range 60 71) z (range -4 5)] [[x y z] air]))
               (into {} (for [x (range -4 5) z (range -4 5)] [[x 59 z] blocks/lava-base]))
               {[0 63 0] netherrack [1 63 0] netherrack}
               overrides)))

(deftest a-tunnel-climbs-out-of-a-dead-end-on-a-block-laid-beside-the-feet
  ;; Issue #338's R3-6-1: two blocks of a ledge seven over the lava sea, every way round them
  ;; air; the tunnel paced the two cells, 32 steps a tunnel, for four hours.
  (let [w (assoc (ledge {}) :player/inventory {0 {:item (recipe/item-id :cobblestone) :count 64}})
        feet [0 64 0]
        target [10 64 0]
        visited #{[0 64 0] [1 64 0]}
        move (fortress/next-move w feet target 3 false visited)
        floor [1 64 0]]
    (is (= [1 1 0] move) "up onto a block laid on the ledge beside the feet")
    (is (fortress/climb-out? w feet move false))
    (is (not (fortress/safe? w feet move)) "not before the block is laid")
    (is (fortress/safe? (assoc-in w [:world/blocks floor] netherrack) feet move))
    (is (= :place (:build/op (build/next-op w (fortress/step-plan feet move))))
        "the step lays it first")
    (is (= [1 0 0] (fortress/next-move (dissoc w :player/inventory) feet target 3 false visited))
        "nothing to lay: the retreat as before")
    ;; Issue #537's R1-20-1: only soul sand carried; laid beside the feet it is dug out
    ;; again as no walk floor, so the climb was offered and given up for ever.
    (is (not (fortress/climb-out?
              (assoc w :player/inventory {0 {:item (recipe/item-id :soul_sand) :count 64}})
              feet
              move
              false))
        "soul sand laid is no floor to climb onto")
    (is (nil?
         (fortress/next-move (assoc-in w [:world/blocks [1 63 0]] air) feet target 3 false visited))
        "a block needs a face to lay against")
    (is
     (not
      (fortress/climb-out? (assoc-in w [:world/blocks [1 66 0]] blocks/lava-base) feet move false))
     "never beside lava, laid or not")))

(deftest a-tunnel-pacing-two-cells-is-blocked
  ;; Issue #338's R3-6-1 with no filler carried: the only safe step from either ledge cell is
  ;; back into the other, and each was counted as a step taken.
  (let [w (assoc (ledge {})
                 :bot/phase :play
                 :bot/effects []
                 :time/now 0
                 :time/tick 0
                 :player/on-ground? true
                 :player/vel [0.0 0.0 0.0])
        tick {:event/kind :tick :event/now 0}
        i {:intent/kind :tunnel-to
           :intent/target [10 64 0]
           :intent/steps 32
           :intent/status :active
           :intent/tunnel-visited #{[0 64 0] [1 64 0]}}
        run (fn [i] (:plan/intent (intent/run (assoc w :plan/intent i) i tick)))]
    (is (fortress/retreat? [0 64 0] [1 0 0] #{[1 64 0]}))
    (is (not (fortress/retreat? [0 64 0] [1 0 0] #{[0 64 0]})))
    (testing "one retreat is allowed"
      (let [out (run i)]
        (is (= [1 0 0] (:intent/move out)))
        (is (= 1 (:intent/retreats out)))
        (is (= :active (:intent/status out)))))
    (testing "a second in a row is a pace: blocked, not a step taken"
      (let [out (run (assoc i :intent/retreats 1))]
        (is (= :failed (:intent/status out)))
        (is (= :blocked (:intent/reason out)))
        (is (nil? (:intent/move out)))))
    (testing "a fresh step resets the count"
      (let [out (run (assoc i :intent/retreats 1 :intent/tunnel-visited #{}))]
        (is (= [1 0 0] (:intent/move out)))
        (is (= 0 (:intent/retreats out)))))))

(deftest a-tunnel-steps-back-along-its-trail-from-a-cell-no-fresh-step-leaves
  ;; issue #502's R3-5-2 and R4-11-1: pockets whose every fresh step was refused, one retreat,
  ;; a pace and :blocked, with the branch a few cells back never tried
  (let [wall (blocks/default-state :obsidian)
        w (assoc (rock (into {} (for [[x z] [[1 0] [0 1] [0 -1]] y (range 63 67)] [[x y z] wall])))
                 :bot/phase :play
                 :bot/effects []
                 :time/now 0
                 :time/tick 0
                 :player/on-ground? true
                 :player/vel [0.0 0.0 0.0])
        target [10 64 0]
        trail {:trail/target target :trail/cells [[-1 63 0] [-1 65 0] [-1 64 0]] :trail/dead #{}}
        tick {:event/kind :tick :event/now 0}
        i {:intent/kind :tunnel-to
           :intent/target target
           :intent/steps 32
           :intent/status :active
           :intent/retreats 1}
        run (fn [world & [i']]
              (let [i (merge i i')] (intent/run (assoc world :plan/intent i) i tick)))]
    (is (= [-1 0 0] (fortress/step-back (assoc w :fortress/trail trail) [0 64 0] trail false))
        "the last cell of the trail is one step away")
    (is (nil? (fortress/step-back w [0 64 0] (assoc trail :trail/cells [[-3 64 0]]) false))
        "a cell not a step away is no way back")
    (is (nil? (fortress/step-back w [0 64 0] (assoc trail :trail/cells []) false))
        "an empty trail has none")
    (testing "no fresh step: a step back, not a second retreat in a row"
      (let [out (run (assoc w :fortress/trail trail))
            kept (:fortress/trail out)]
        (is (= :active (get-in out [:plan/intent :intent/status])))
        (is (= [-1 0 0] (get-in out [:plan/intent :intent/move])))
        (is (= 0 (get-in out [:plan/intent :intent/retreats])) "a step back is no pace")
        (is (= [[-1 63 0] [-1 65 0]] (:trail/cells kept)) "its last cell is dropped")
        (is (= #{[0 64 0]} (:trail/dead kept)) "the cell left leads nowhere")))
    (testing "the same cell with no trail is the pace of before"
      (let [out (run w {:intent/tunnel-visited #{[-1 63 0] [-1 64 0] [-1 65 0]}})]
        (is (= :failed (get-in out [:plan/intent :intent/status])))
        (is (= :blocked (get-in out [:plan/intent :intent/reason])))))
    (testing "a fresh step keeps the cell it began from"
      (let [out (run (assoc (rock {})
                            :bot/phase :play
                            :bot/effects []
                            :time/now 0
                            :time/tick 0
                            :player/on-ground? true
                            :player/vel [0.0 0.0 0.0]))]
        (is (= [[0 64 0]] (get-in out [:fortress/trail :trail/cells])))
        (is (= target (get-in out [:fortress/trail :trail/target])))))))

(deftest a-tunnel-waits-in-the-air-for-the-ground-before-it-fails
  ;; issue #502's R4-31-1: a bot knocked off a ledge started three tunnels in the air and each
  ;; failed :blocked within a tick, which failed the plan
  (let [w (assoc (ledge {})
                 :bot/phase :play
                 :bot/effects []
                 :time/now 0
                 :time/tick 0
                 :player/on-ground? false
                 :player/vel [0.0 -0.3 0.0])
        tick {:event/kind :tick :event/now 0}
        i {:intent/kind :tunnel-to
           :intent/target [10 64 0]
           :intent/steps 32
           :intent/status :active
           :intent/retreats 1
           :intent/tunnel-visited #{[0 64 0] [1 64 0]}}
        run (fn [i w] (:plan/intent (intent/run (assoc w :plan/intent i) i tick)))]
    (let [out (run i w)]
      (is (= :active (:intent/status out)) "in the air: no verdict yet")
      (is (= 1 (:intent/airborne out))))
    (is (= :blocked (:intent/reason (run (assoc i :intent/airborne fortress/air-grace) w)))
        "still in the air after air-grace ticks: blocked")
    (is (= :blocked (:intent/reason (run i (assoc w :player/on-ground? true))))
        "on the ground the verdict is immediate")))

(deftest a-floor-to-replace-needs-a-filler-carried
  ;; Issue #410's R3-38-1: a soul sand floor was dug with no filler left to lay, and the place
  ;; failed :not-held three times. Soul soil, basalt and blackstone are fillers too.
  (let [floor [1 63 0]
        w (rock {floor (blocks/default-state :soul_sand) [1 64 0] air [1 65 0] air})
        carry (fn [item] (assoc w :player/inventory {0 {:item (recipe/item-id item) :count 4}}))]
    (is (not (fortress/safe? w [0 64 0] [1 0 0])) "nothing to replace it with")
    (is (not (fortress/safe? (carry :gravel) [0 64 0] [1 0 0])) "gravel falls out of a floor")
    ;; Issue #537's R1-20-1: soul sand is a filler, but laid in a floor it stands as no
    ;; walk floor, so the step dug it out and laid it again for the whole window.
    (is (nil? (fortress/walk-filler (carry :soul_sand) floor)) "soul sand laid is no walk floor")
    (is (= :cobblestone (fortress/walk-filler (carry :cobblestone) floor)))
    (is (not (fortress/safe? (carry :soul_sand) [0 64 0] [1 0 0]))
        "a floor only soul sand could replace is not replaced")
    (doseq [item [:cobblestone :soul_soil :basalt :blackstone]]
      (is (fortress/safe? (carry item) [0 64 0] [1 0 0]) (str item)))
    (is (not (fortress/safe? (assoc-in w [:world/blocks floor] blocks/lava-base) [0 64 0] [1 0 0]))
        "no lava floor filled with nothing")
    (is (fortress/safe? (assoc-in (carry :cobblestone) [:world/blocks floor] blocks/lava-base)
                        [0 64 0]
                        [1 0 0]))
    (is (fortress/safe? (rock {}) [0 64 0] [1 0 0]) "a floor it walks needs none")))

(deftest a-tunnel-perched-on-an-edge-steps-back-onto-its-footing
  ;; Issue #284's R1-32-2 and R3-17-1: the centre half a block past a ledge, the cell under the
  ;; feet air, and every walk to the next step's stand failed :no-path.
  (let [w (assoc (ledge {[0 63 0] air}) :player/inventory {})
        feet [0 64 0]
        tick {:event/kind :tick :event/now 0}
        i {:intent/kind :tunnel-to
           :intent/target [10 64 0]
           :intent/steps 32
           :intent/status :active
           :intent/retreats 1
           :intent/tunnel-visited #{[1 64 0]}}
        out (:plan/intent (intent/run (assoc w
                                             :bot/phase :play
                                             :bot/effects []
                                             :time/now 0
                                             :time/tick 0
                                             :player/on-ground? true
                                             :plan/intent i)
                                      i
                                      tick))]
    (is (fortress/perched? w feet))
    (is (not (fortress/perched? (ledge {}) feet)))
    (is (= [1 0 0] (fortress/recentre w feet)))
    (is (nil? (fortress/recentre (ledge {}) feet)) "on its footing already")
    (is (nil? (fortress/recentre (ledge {[0 63 0] air [1 63 0] air}) feet)) "no footing beside")
    (is (= [1 0 0] (:intent/move out)))
    (is (= 0 (:intent/retreats out)) "stepping back onto the footing is no pace")
    (is (= :active (:intent/status out)))))

(deftest a-stand-ringed-with-lava-is-sealed-before-the-step
  ;; Issue #286's real-5: a portal cast into a lava lake, lava beside the cells of every step.
  ;; A block placed into lava replaces it, so the lava beside the step is capped first.
  (let [lava [1 65 1]
        w (assoc (rock {lava blocks/lava-base [1 64 0] air [1 65 0] air})
                 :player/inventory
                 {0 {:item (recipe/item-id :cobblestone) :count 8}})
        feet [0 64 0]
        move [1 0 0]
        bare (dissoc w :player/inventory)
        sealed (assoc-in w [:world/blocks lava] (blocks/default-state :cobblestone))]
    (is (= [lava] (fortress/lava-beside w feet move)))
    (is (not (fortress/safe? w feet move)))
    (is (fortress/sealed? w feet move))
    (is (not (fortress/sealed? bare feet move)) "nothing to cap it with")
    (is (not (fortress/sealed? (assoc-in w [:world/blocks [1 65 0]] blocks/lava-base) feet move))
        "a cell it opens is lava")
    (is (= [:place] (mapv :build/op (fortress/seal-ops w feet move))))
    (is (= lava
           (:build/pos (build/next-op w
                                      (into (fortress/seal-ops w feet move)
                                            (fortress/step-plan feet move))))))
    (is (empty? (fortress/seal-ops sealed feet move)))
    (is (fortress/safe? sealed feet move) "once capped the step is plain safe")
    (is (not= :seal
              (:build/step (build/next-op sealed
                                          (into (fortress/seal-ops sealed feet move)
                                                (fortress/step-plan feet move))))))))

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

(deftest a-tunnel-step-whose-op-fails-rejected-marks-cell-visited-and-continues
  ;; when a child placement in a tunnel step is rejected by the server, the step is given
  ;; up and marked visited so the tunnel tries another move instead of failing the plan :rejected
  (let [op (assoc (build/place-op [1 63 0] [0 64 0] nil :floor) :build/item :cobblestone)
        w (assoc (rock {[1 63 0] air})
                 :world/facts (memory/empty-facts)
                 :bot/phase :play
                 :bot/effects []
                 :time/now 0
                 :time/tick 0
                 :player/pos [0.5 64.0 0.5]
                 :player/on-ground? true
                 :player/vel [0.0 0.0 0.0]
                 :player/inventory {0 {:item (recipe/item-id :cobblestone) :count 64}}
                 :plan/intent
                 {:intent/kind :tunnel-to
                  :intent/target [10 64 0]
                  :intent/steps 8
                  :intent/move [1 0 0]
                  :intent/from [0 64 0]
                  :intent/op op
                  :intent/child {:intent/kind :place
                                 :intent/cell [1 63 0]
                                 :intent/status :failed
                                 :intent/reason :rejected}
                  :intent/status :active})
        w' (intent/run w (:plan/intent w) {:event/kind :tick :event/now 0})
        i' (:plan/intent w')]
    (is (= :active (:intent/status i')) "tunnel remains active")
    (is (nil? (:intent/reason i')) "no failure reason left")
    (is (nil? (:intent/move i')) "move dropped to choose another")
    (is (contains? (:intent/tunnel-visited i') [1 64 0]) "cell marked visited")))

(deftest an-overworld-step-opens-no-cell-by-water
  ;; issue #265: the way to a stronghold's portal room runs through aquifers; a :tunnel-to with
  ;; :intent/dry? takes no step whose opened cells touch water, the Nether's rule (safe?) aside
  (let [w (rock {[1 65 1] blocks/water-base})]
    (is (fortress/safe? w [0 64 0] [1 0 0]) "the Nether's rule takes no account of water")
    (is (not (fortress/dry? w [0 64 0] [1 0 0])))
    (is (fortress/dry? w [0 64 0] [-1 0 0]))
    (is (= [1 0 0] (fortress/choose-move w [0 64 0] [4 64 0])))
    (is (not= [1 0 0] (fortress/choose-move w [0 64 0] [4 64 0] fortress/climb-past true)))))

(defn field
  "A world of netherrack for x from x0 to x1 and z -20..20 (y 60..70), the bot's two cells at
   [0 64 0] open: the Nether with an edge at x1, where the model's captured region ends."
  [x0 x1]
  {:world/chunks {}
   :world/blocks
   (merge (into {}
                (for [x (range x0 (inc x1)) y (range 60 71) z (range -20 21)] [[x y z] netherrack]))
          {[0 64 0] air [0 65 0] air})
   :player/pos [0.5 64.0 0.5]
   :player/dimension dimension/nether})

(def east-leg {:search/from [0 64 0] :search/heading [1 0] :search/leg 0 :search/tunnels 0})

(deftest the-spiral-turns-right-and-its-legs-grow
  ;; issue #338: a tunnel that aimed 48 blocks east for ever went 630 blocks along the bedrock
  ;; past a fortress 150 blocks north; the legs now sweep strips a spacing apart
  (is (= [[1 0] [0 1] [-1 0] [0 -1] [1 0]] (take 5 (iterate fortress/turn-right [1 0]))))
  (is (= [160 160 320 320 480] (map fortress/leg-length (range 5))))
  (is (= [480 64 0]
         (fortress/leg-target {:search/from [0 64 0] :search/heading [1 0] :search/leg 200}))
      "no leg's target farther than leg-max from where it began")
  (is (= [160 64 0] (fortress/leg-target east-leg)))
  (is (= [0 64 -320]
         (fortress/leg-target {:search/from [0 64 0] :search/heading [0 -1] :search/leg 2}))))

(deftest a-leg-keeps-to-the-heights-over-the-lava-sea-and-under-the-roof
  (is (= 52 (fortress/explore-y [0 2 0])) "a tunnel in the bedrock climbs")
  (is (= 64 (fortress/explore-y [0 64 0])))
  (is (= 90 (fortress/explore-y [0 120 0])) "and one in the roof comes down")
  (is (= [160 52 0] (fortress/leg-target (assoc east-leg :search/from [0 7 0])))))

(deftest the-first-leg-goes-east-and-the-edge-of-the-world-turns-it
  (let [w (field -20 40)]
    (is (= east-leg (fortress/exploring w)) "east when it can go")
    (is (= [0 1] (:search/heading (fortress/exploring (field -20 1))))
        "the world ends two blocks east: south")
    (is (nil? (fortress/exploring (assoc w :world/blocks {[0 64 0] air [0 65 0] air})))
        "no heading has a way to go")))

(deftest a-leg-goes-on-until-its-end-then-the-spiral-turns
  (let [w (assoc (field -20 40) :fortress/search east-leg)
        ending {:search/from [-160 64 0] :search/heading [1 0] :search/leg 0 :search/tunnels 3}]
    (is (= (assoc east-leg :search/tunnels 1 :search/best 160.0 :search/stalled 0)
           (fortress/exploring w))
        "one more tunnel on the leg, its distance to the end kept")
    (is (not (fortress/leg-over? w east-leg)))
    (is (fortress/leg-over? w ending) "its end within reach of the feet")
    (is (= {:search/from [0 64 0] :search/heading [0 1] :search/leg 1 :search/tunnels 0}
           (fortress/exploring (assoc w :fortress/search ending)))
        "a quarter turn right from the feet, the next leg")
    (is (fortress/leg-over? w (assoc east-leg :search/tunnels 13))
        "more tunnels than a leg of 160 asks: a wall it cannot go round")
    (is (fortress/leg-over? w (assoc east-leg :search/from [-700 64 0]))
        "far from where it began: another trip's")
    (is (fortress/leg-over? (field -20 1) east-leg) "the world ending ahead")))

(defn cavern
  "The field with no block south of z 0: a cavern's open air, as far as the leg can see."
  []
  (update (field -20 40) :world/blocks #(into {} (remove (fn [[[_ _ z] _]] (pos? z)) %))))

(deftest a-leg-given-up-untravelled-turns-at-the-same-length
  ;; Issues #527 and #537: legs stalled at caverns grew the spiral's count untravelled, and
  ;; the search's target ended 16,896 blocks away; only a travelled leg grows the next one.
  (let [w
        (assoc (field -20 40) :fortress/search (assoc east-leg :search/tunnels 3 :search/stalled 3))
        next (fortress/exploring w)]
    (is (fortress/leg-over? w (:fortress/search w)) "stalled: over")
    (is (not (fortress/leg-done? w (:fortress/search w))) "but not travelled")
    (is (= 0 (:search/leg next)) "the next leg keeps the length")
    (is (= [0 1] (:search/heading next)) "and turns")
    (is (= 1
           (:search/leg (fortress/exploring (assoc (field -20 40)
                                                   :fortress/search
                                                   {:search/from [-160 64 0]
                                                    :search/heading [1 0]
                                                    :search/leg 0
                                                    :search/tunnels 3}))))
        "a travelled leg still grows the spiral")
    (is (= east-leg
           (fortress/exploring (assoc (field -20 40)
                                      :fortress/search
                                      (assoc east-leg :search/leg 200 :search/tunnels 900))))
        "a leg grown untravelled past leg-max starts again along its heading")))

(deftest a-leg-looks-for-rock-ahead
  ;; issue #338, sim world R2/12: a leg east ran into a cavern sixty blocks across and its rim
  (let [w (field -20 40)]
    (is (= fortress/rock-look (fortress/rock-run w [0 64 0] [1 0])) "rock all the way ahead")
    (is (= 20 (fortress/rock-run w [0 64 0] [-1 0])) "the world ends after 20 cells")
    (is (zero? (fortress/rock-run (cavern) [0 64 0] [0 1])) "open air ahead is no rock")
    (is (= 20 (fortress/rock-run (cavern) [0 64 0] [0 -1])))))

(deftest a-leg-that-ended-turns-to-the-rock-it-can-dig
  (is (= [[0 1] [0 -1] [-1 0] [1 0]] (fortress/turns-by-rock (field -20 40) [0 64 0] [1 0]))
      "right first when the rock is equal")
  (is (= [[0 -1] [0 1] [-1 0] [1 0]] (fortress/turns-by-rock (cavern) [0 64 0] [1 0]))
      "left first when the cavern lies to the right")
  (is (= [0 -1]
         (:search/heading
          (fortress/exploring
           (assoc (cavern) :fortress/search (assoc east-leg :search/tunnels 3 :search/stalled 3)))))
      "the next leg goes north, away from the open air"))

(deftest a-leg-that-gains-no-ground-is-given-up
  ;; issue #338: the tunnel stayed inside x 507-534 for an hour, 130 digs for 13 blocks
  (let [w (field -20 40)]
    (is (= {:search/best 160.0 :search/stalled 0}
           (select-keys (fortress/leg-progress w east-leg) [:search/best :search/stalled]))
        "the first look sets the distance")
    (is (= 1 (:search/stalled (fortress/leg-progress w (assoc east-leg :search/best 160.0))))
        "no nearer: stalled")
    (is (= 1 (:search/stalled (fortress/leg-progress w (assoc east-leg :search/best 165.0))))
        "five blocks nearer is under stall-gain")
    (is (= {:search/best 160.0 :search/stalled 0}
           (select-keys
            (fortress/leg-progress w (assoc east-leg :search/best 170.0 :search/stalled 2))
            [:search/best :search/stalled]))
        "ten blocks nearer is progress and clears the count")
    (is (not (fortress/leg-over? w (assoc east-leg :search/stalled 2))))
    (is (fortress/leg-over? w (assoc east-leg :search/stalled 3)) "three tunnels in a row")
    (is (= 3
           (:search/stalled
            (fortress/exploring
             (assoc w
                    :fortress/search
                    (assoc east-leg :search/tunnels 2 :search/best 160.0 :search/stalled 2)))))
        "the look that brings no ground is the third")))

(deftest an-exploring-tunnel-keeps-its-leg-for-the-planner
  (let [goal (first (filter #(= :fortress (:goal/id %)) plan/goals))
        w (assoc (field -20 40)
                 :world/facts (memory/empty-facts)
                 :bot/phase :play
                 :bot/effects []
                 :time/now 0
                 :time/tick 0
                 :player/inventory {})
        i (plan/next-intent w goal plan/goals)
        out (intent/run (assoc w :plan/intent i) i {:event/kind :tick :event/now 0})]
    (is (= :tunnel-to (:intent/kind i)))
    (is (= east-leg (:intent/search i)))
    (is (= [160 64 0] (:intent/target i)))
    (is (= east-leg (:fortress/search out)) "the executor keeps the leg the planner chose")
    (is (= (assoc east-leg :search/tunnels 1 :search/best 160.0 :search/stalled 0)
           (:intent/search (plan/next-intent out goal plan/goals)))
        "and the next look goes on along it")))

(defn void-east
  "Rock at x -3..0 (y 60..70), open air east of it down to the ground at y 40 (z -3..3): the Nether's
   cavern with a thirty block drop, the bot at [0 64 0] with 64 cobblestone and full health."
  []
  {:world/chunks {}
   :world/blocks
   (merge (into {} (for [x (range -3 1) y (range 60 71) z (range -3 4)] [[x y z] netherrack]))
          (into {} (for [x (range 1 13) y (range 41 71) z (range -3 4)] [[x y z] air]))
          (into {} (for [x (range 1 13) z (range -3 4)] [[x 40 z] netherrack]))
          {[0 64 0] air [0 65 0] air})
   :player/pos [0.5 64.0 0.5]
   :player/health 20.0
   :player/inventory {0 {:item (recipe/item-id :cobblestone) :count 64}}})

(deftest a-leg-that-stalled-bridges-a-cavern-over-ground
  ;; issue #338, sim world R2/12: a cavern 60 blocks across and 30 deep over basalt
  (let [w (void-east)]
    (is (not (fortress/safe? w [0 64 0] [1 0 0])) "an air floor is no step")
    (is (fortress/ground-below? w [1 63 0]))
    (is (not (fortress/ground-below? (update w :world/blocks dissoc [1 40 0]) [1 63 0]))
        "the world ending under it is no ground")
    (is (fortress/bridge-over-void? w [0 64 0] [1 0 0]))
    (is (not (fortress/bridge-over-void? (assoc w :player/health 10.0) [0 64 0] [1 0 0]))
        "never hurt")
    (is (not (fortress/bridge-over-void? (assoc w :player/inventory {}) [0 64 0] [1 0 0]))
        "never short of blocks")
    (is (not (fortress/bridge-over-void?
              (assoc w :player/inventory {0 {:item (recipe/item-id :cobblestone) :count 15}})
              [0 64 0]
              [1 0 0]))
        "a reserve of sixteen")
    (let [lava (first (blocks/states-of :lava))
          over-lava (assoc-in (assoc w :player/health 20.0) [:world/blocks [1 50 0]] lava)]
      (is (fortress/bridge-over-void? over-lava [0 64 0] [1 0 0]) "over lava at full health")
      (is (not (fortress/bridge-over-void? (assoc over-lava :player/health 16.0) [0 64 0] [1 0 0]))
          "never over lava hurt")
      (is (not (fortress/bridge-over-void?
                (assoc-in over-lava [:world/blocks [1 50 0]] (first (blocks/states-of :water)))
                [0 64 0]
                [1 0 0]))
          "never over water"))
    (is (not (fortress/on-bridge? w)) "on rock")
    (is (fortress/on-bridge? (-> w
                                 (assoc-in [:world/blocks [0 63 0]] netherrack)
                                 (assoc-in [:world/blocks [0 62 0]] air)))
        "on a block over air")
    (is (not= [1 0 0] (fortress/next-move w [0 64 0] [8 64 0] 3 false #{}))
        "a leg that has not stalled goes round")
    (is (= [1 0 0] (fortress/next-move w [0 64 0] [8 64 0] 3 false #{} true))
        "a stalled leg lays the floor and crosses")))

(defn bridge-stand
  "One cobblestone block at [0 63 0] over ground at y 40, air all round it: the end of a
   bridge over a cavern, with 64 cobblestone and full health."
  []
  {:world/chunks {}
   :world/blocks (merge (into {}
                              (for [x (range -6 7) y (range 41 71) z (range -6 7)] [[x y z] air]))
                        (into {} (for [x (range -6 7) z (range -6 7)] [[x 40 z] netherrack]))
                        {[0 63 0] (blocks/default-state :cobblestone)})
   :player/pos [0.5 64.0 0.5]
   :player/health 20.0
   :player/dimension dimension/nether
   :player/inventory {0 {:item (recipe/item-id :cobblestone) :count 64}}})

(deftest a-new-leg-may-begin-with-a-bridge-step
  ;; issue #573, R4-38-1: a leg ended on its bridge a few blocks short of the bricks, no
  ;; safe? step left any heading, and the search waited :no-way
  (let [w (bridge-stand)]
    (is (fortress/on-bridge? w))
    (is (nil? (fortress/choose-move w [0 64 0] [160 64 0])) "no plain step off the bridge")
    (is (= [1 0] (:search/heading (fortress/exploring w))) "the next leg bridges east")))

(deftest a-pickaxe-is-not-made-on-a-bridge
  ;; issue #559 again, R2-44-2 and R3-2-1: the pickaxe broke over a diagonal bridge, every
  ;; cell for the table had a drop past it, and the place failed :no-spot three times
  (let [goal (first (filter #(= :fortress (:goal/id %)) plan/goals))
        w (assoc (bridge-stand)
                 :world/facts (memory/empty-facts)
                 :player/inventory
                 {0 {:item (recipe/item-id :stick) :count 2}
                  1 {:item (recipe/item-id :cobblestone) :count 64}
                  2 {:item (recipe/item-id :crafting_table) :count 1}})]
    (is (= :tunnel-to (:intent/kind (plan/next-intent w goal plan/goals)))
        "the tunnel goes on by hand to ground, and the pickaxe is made there")
    (is (not (fortress/ground-near? w)) "no ground within two cells of the bridge's end")
    (is (fortress/ground-near? (rock {})) "rock under a walk floor is ground")))

(deftest a-pickaxe-broken-on-a-bridge-walks-back-to-ground
  ;; issue #559, R3-2-1: on a ledge over a cavern with the rock ahead undiggable by hand,
  ;; the tunnel yields for a pickaxe and is asked again for ever; the way back is the
  ;; tunnel's own trail to the ground it began on
  (let [goal (first (filter #(= :fortress (:goal/id %)) plan/goals))
        w (assoc (bridge-stand)
                 :world/facts (memory/empty-facts)
                 :fortress/trail {:trail/target [160 64 0] :trail/cells [[-3 64 0] [-2 64 0]]}
                 :player/inventory
                 {0 {:item (recipe/item-id :stick) :count 2}
                  1 {:item (recipe/item-id :cobblestone) :count 64}
                  2 {:item (recipe/item-id :crafting_table) :count 1}})]
    (is (= [-3 64 0] (fortress/back-target w [160 64 0])) "the oldest cell of the trail")
    (is (= [-3 64 0]
           (fortress/back-target (assoc w :fortress/trail {:trail/target [-3 64 0] :trail/cells []})
                                 [160 64 0]))
        "a walk back already running keeps its target")
    (is (nil? (fortress/back-target
               (assoc w :fortress/trail {:trail/target [160 64 0] :trail/cells [[0 64 0]]})
               [160 64 0]))
        "already there: no walk back")
    (let [next (plan/next-intent w goal plan/goals)]
      (is (= :tunnel-to (:intent/kind next)))
      (is (= [-3 64 0] (:intent/target next)) "back along the bridge before any table"))))

(deftest a-tunnel-op-is-worked-from-the-feet-on-soul-sand
  ;; issue #328, R3-36-2: at the edge of its cell on a soul sand floor, no stand that
  ;; reached the step's floor place was standable, and the tunnel failed :no-stand
  (let [w (assoc (rock {[0 63 0] (blocks/default-state :soul_sand)}) :player/pos [0.0 64.0 0.5])]
    (is (nil? (build/approach-stand w
                                    {:intent/kind :tunnel-to}
                                    {:build/op :place :build/stand [0 64 0] :build/pos [1 63 0]}))
        "no child: the op is worked from the feet themselves")))

(deftest a-step-over-a-floor-with-nothing-to-lay-is-given-up-not-failed-not-held
  ;; Issue #410's R2-17-1 and R2-12-1: the floor of a step was to be placed and no block was
  ;; held, the :cobblestone it fell back to failed :not-held three times.
  (let [feet [0 64 0]
        move [1 0 0]
        w (assoc (rock {[1 63 0] air [1 64 0] air [1 65 0] air})
                 :bot/phase :play
                 :bot/effects []
                 :time/now 0
                 :time/tick 0)
        placing (build/next-op w (fortress/step-plan feet move))
        i {:intent/kind :tunnel-to
           :intent/target [10 64 0]
           :intent/steps 32
           :intent/status :active
           :intent/from feet
           :intent/move move
           :intent/op placing}
        tick {:event/kind :tick :event/now 0}
        out (intent/run (assoc w :plan/intent i) i tick)
        laid (intent/run (assoc w
                                :plan/intent i
                                :player/inventory
                                {0 {:item (recipe/item-id :cobblestone) :count 4}})
                         i
                         tick)]
    (is (= :place (:build/op placing)))
    (is (= :active (get-in out [:plan/intent :intent/status])))
    (is (nil? (get-in out [:plan/intent :intent/move])) "the step is dropped")
    (is (contains? (get-in out [:plan/intent :intent/tunnel-visited]) [1 64 0])
        "its cell counts as visited, so it is not chosen again")
    (is (= move (get-in laid [:plan/intent :intent/move])) "with a block held the step goes on")))

(deftest a-floor-is-laid-from-bricks-when-nothing-else-is-held
  (let [w (rock {[1 63 0] (blocks/default-state :soul_sand) [1 64 0] air [1 65 0] air})
        carry (fn [item] (assoc w :player/inventory {0 {:item (recipe/item-id item) :count 4}}))]
    (doseq [item [:polished_blackstone_bricks :cracked_polished_blackstone_bricks]]
      (is (fortress/safe? (carry item) [0 64 0] [1 0 0]) (str item)))
    ;; Soul sand stays a filler for a seal, but laid in a floor it stands as no walk
    ;; floor, so the step dug it out and laid it again for ever (issue #537, R1-20-1).
    (is (not (fortress/safe? (carry :soul_sand) [0 64 0] [1 0 0]))
        "soul sand laid in a floor is no replacement")
    (is (not (fortress/safe? (carry :gravel) [0 64 0] [1 0 0]))
        "gravel still falls out of a floor")))

(deftest a-target-far-overhead-is-climbed-to-before-it-is-gone-round
  ;; Issues #538 and #338: bricks 37 and 47 blocks over the feet and a few aside, the first
  ;; direction's level or down step beat a step up in the next, and the tunnel zigzagged down to
  ;; the bedrock.
  (let [ms (fortress/moves [0 18 0] [4 55 -3])
        ups (take-while #(= 1 (second %)) ms)]
    (is (= 4 (count ups)) "every direction's step up comes first")
    (is (every? #(<= (second %) 0) (drop (count ups) ms)) "then level, then down"))
  (is (= [1 0 0] (first (fortress/moves [0 64 0] [60 66 50])))
      "a far way aside, within climb-past of level, keeps its direction")
  (is (= [1 1 0] (first (fortress/moves [0 64 0] [60 70 50])))
      "a far way aside and six up still climbs in that direction"))

(deftest ground-is-looked-for-the-depth-of-the-lava-sea-and-more
  ;; Issues #538 and #338: R2-2-1 stood on a floor 80 over a sea whose lava began 49 under it, and
  ;; found no ground to bridge to within 48 blocks.
  (let [cavern (fn [floor-y]
                 {:world/chunks {}
                  :world/blocks (merge (into {} (for [y (range 0 120)] [[0 y 0] air]))
                                       {[0 floor-y 0] netherrack})})]
    (is (fortress/ground-below? (cavern 20) [0 80 0]) "60 under the floor")
    (is (fortress/ground-below? (cavern 5) [0 100 0]) "95 under it")
    (is (not (fortress/ground-below? (cavern 2) [0 100 0])) "98 under it: not looked for")))

(deftest a-brick-overhead-is-climbed-as-a-shaft
  ;; Issue #338, R2-6-2: y 9 under a brick at y 56, no pickaxe, a stair of three digs a level
  ;; by hand ended at y 43. A shaft is one dig a level.
  (let [w (assoc (rock {})
                 :player/on-ground? true
                 :player/inventory {0 {:item (recipe/item-id :cobblestone) :count 64}}
                 :bot/phase :play
                 :bot/effects []
                 :time/now 0
                 :time/tick 0)
        feet [0 64 0]
        tick {:event/kind :tick :event/now 0}
        i {:intent/kind :tunnel-to :intent/target [1 80 0] :intent/steps 32 :intent/status :active}
        out (intent/run (assoc w :plan/intent i) i tick)]
    (is (fortress/overhead? [981 9 481] [983 56 480]) "R2-6-2's brick")
    (is (not (fortress/overhead? [918 65 384] [984 72 455])) "R2-22-2's brick is across a cavern")
    (is (fortress/shaft-ready? w feet))
    (is (= :shaft (get-in out [:plan/intent :intent/stage])))
    (is (= [0 66 0] (get-in out [:plan/intent :intent/child :intent/target])))
    (is (get-in out [:plan/intent :intent/child :intent/drop-ok?])
        "basalt by hand, the drop unneeded")
    (is (nil? (fortress/shaft-tick w feet [1 80 0] (assoc i :intent/no-shaft :unsafe) tick))
        "a shaft that failed is not begun again")))

(deftest a-target-above-is-climbed-before-the-stair-down
  ;; Issue #526, R2-29-1 and R2-36-1: the level tread beside the feet was already stood in, so
  ;; the only fresh safe step was down the cobble stair onto the obsidian pad.
  (let [cobble (blocks/default-state :cobblestone)
        obsidian (blocks/default-state :obsidian)
        box (into {} (for [x (range -3 4) y (range 58 71) z (range -3 4)] [[x y z] air]))
        w {:world/chunks {}
           :world/blocks (merge box {[0 63 0] obsidian [-1 63 0] cobble [-1 62 0] obsidian})
           :player/pos [0.5 64.0 0.5]
           :player/inventory {0 {:item (recipe/item-id :cobblestone) :count 64}}}
        feet [0 64 0]
        down [-1 -1 0]
        climb [-1 1 0]
        stood #{[-1 64 0]}]
    (is (fortress/safe? w feet down) "the stair down is safe")
    (is (fortress/safe? w feet [-1 0 0]) "the tread beside is safe too, once stood in")
    (is (not (fortress/safe? w feet climb)) "the climb is not safe until its block is laid")
    (is (fortress/climb-out? w feet climb false))
    (is (= climb (fortress/next-move w feet [-40 80 0] 3 false stood))
        "a target above is climbed, not ridden down the stair")
    (is (= climb (fortress/next-move w feet [-40 80 0] 3 false (conj stood [-1 65 0])))
        "the cell above, already stood in on the way down, is still climbed")
    (is (fortress/gaining? feet climb [-40 80 0]))
    (is (not (fortress/gaining? feet down [-40 80 0])))
    (is (= down (fortress/next-move w feet [-40 50 0] 3 false stood))
        "a target below still takes the stair down")))
