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
                    :intent/reason :rejected})
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
    (is (fortress/safe? (rock {[1 63 0] lava}) [0 64 0] [1 0 0])
        "lava in the floor is filled: the floor op places into it")
    (is (not (fortress/safe? (rock {[1 63 0] air [1 62 0] air [1 61 0] air}) [0 64 0] [1 0 0]))
        "never out over a drop: an air floor is not a tunnel")
    (is (not (fortress/safe? (rock {[1 64 0] (blocks/default-state :obsidian)}) [0 64 0] [1 0 0]))
        "never through obsidian")
    (is (not (fortress/safe? (rock {[1 64 0] nil}) [0 64 0] [1 0 0])) "never into the unknown")
    (testing "the tunnel goes round: up when lava is beside the level step"
      (is (= [1 1 0] (fortress/choose-move (rock {[1 64 1] lava}) [0 64 0] [4 64 0]))))))

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

(deftest a-tunnel-drops-an-unsafe-in-flight-step
  (let [from [0 64 0]
        move [1 0 0]
        ops (fortress/step-plan from move)
        tick {:event/kind :tick :event/now 0}]
    (doseq [op [(nth ops 2) (peek ops)]
            hazard [{[1 66 0] (blocks/default-state :gravel) [1 67 0] blocks/lava-base}
                    {[2 65 0] blocks/lava-base}]]
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
        w (rock {floor (blocks/default-state :soul_sand) [1 64 0] air [1 65 0] air})
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
            started (intent/run (assoc w :plan/intent i) i tick)
            out (intent/run started (:plan/intent started) tick)]
        (is (= :cobblestone (get-in started [:plan/intent :intent/op :build/item])))
        (is (= :not-held (get-in out [:plan/intent :intent/child :intent/reason])))
        (is (empty? (:bot/effects out)))))))

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
