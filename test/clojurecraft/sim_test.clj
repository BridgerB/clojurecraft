(ns clojurecraft.sim-test
  "The whole bot against the pure server model: handshake → spawn → chunk → find → walk → dig →
   drop → pickup → done, with no socket and no Java process."
  (:require [clojure.core.async :as a]
            [clojure.spec.alpha]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.test.check :as tc]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [clojurecraft.blocks :as blocks]
            [clojurecraft.fixtures :as fx]
            [clojurecraft.game :as game]
            [clojurecraft.intent :as intent]
            [clojurecraft.stairs]
            [clojurecraft.stone]
            [clojurecraft.gym :as gym]
            [clojurecraft.terrain :as terrain]
            [clojurecraft.inventory :as inventory]
            [clojurecraft.main :as main]
            [clojurecraft.make]
            [clojurecraft.memory :as memory]
            [clojurecraft.plan :as plan]
            [clojurecraft.recipe :as recipe]
            [clojurecraft.sim :as sim]
            [clojurecraft.wood]
            [clojurecraft.world :as world]))

(use-fixtures :once fx/instrumented)

(def step (game/compose game/step plan/step))

(deftest collects-one-log-against-the-model
  (let [column (world/column-bytes {[6 64 0] 136 [6 65 0] 136 [6 66 0] 136 [6 67 0] 252})
        sim0 (sim/init {:column column :spawn [0.5 64.0 0.5] :keep-alive-every 3000})
        [w _] (sim/run step (game/init fx/opts) sim0 #(or (plan/done? %) (plan/failed? %)) 60000
                       {:event/kind :go :go/goals [:wood]})]
    (is (plan/done? w) (pr-str (plan/summary w)))
    (is (= 1 (inventory/logs-held w)))
    (is (= :play (:bot/phase w)))
    (is (pos? (:stats/keep-alives w)) "the model's keep-alives were answered")
    (is (= 0 (terrain/block-at w [6 64 0])))))

(defn held [w] (recipe/counts (:player/inventory w)))

(deftest crafts-a-table-and-sticks-from-a-tree
  (let [column (world/column-bytes {[6 64 0] 136 [6 65 0] 136 [6 66 0] 136 [6 67 0] 252})
        sim0 (sim/init {:column column :spawn [0.5 64.0 0.5] :keep-alive-every 3000})
        [w sim] (sim/run step (game/init fx/opts) sim0 #(or (plan/done? %) (plan/failed? %)) 120000
                         {:event/kind :go :go/goals [:kit]})]
    (is (plan/done? w) (pr-str (plan/summary w)))
    (is (= 1 (:crafting_table (held w))))
    (is (= 4 (:stick (held w))))
    (is (empty? (:window/grid w)) "nothing left in the grid")
    (is (nil? (:window/cursor w)))
    (is (= [] (:sim/violations sim)))
    (testing "the bot's inventory is the server's"
      (is (= (into {} (for [[s it] (:sim/inv sim) :when (<= 9 s 44)] [(inventory/container->player-slot s) it]))
             (:player/inventory w))))))

(def kit-items #{:oak_log :oak_planks :stick :crafting_table})

(defn kit-run [lag drop]
  (let [column (world/column-bytes {[6 64 0] 136 [6 65 0] 136 [6 66 0] 136 [6 67 0] 252})]
    (sim/run step (game/init fx/opts)
             (sim/init {:column column :spawn [0.5 64.0 0.5] :lag-ticks lag :drop-clicks (if drop #{drop} #{})})
             #(or (plan/done? %) (plan/failed? %)) 150000 {:event/kind :go :go/goals [:kit]})))

(deftest no-blind-take-under-lag-or-a-dropped-click
  ;; every single lost click (the kit takes 17-23 clicks) at lags of 0, 3 and 10 ticks, plus no
  ;; loss: the server never sees a click on an empty result, nothing but the kit's own items is
  ;; ever crafted (no buttons, no pressure plates), and the run ends with the table and sticks
  ;; or, failing that, an empty grid. Enumerated, not sampled: disabling the grid reclaim fails
  ;; drops 5-8 (dirty grid) and 14 (a button), which a 12-sample property missed.
  (let [bad (for [lag [0 3 10]
                  drop (cons nil (range 0 28))
                  :let [[w sim] (kit-run lag drop)
                        items (set (keys (held w)))
                        ok (and (= [] (:sim/violations sim))
                                (every? kit-items items)
                                (or (and (plan/done? w) (= 1 (:crafting_table (held w))) (<= 4 (:stick (held w) 0)))
                                    (empty? (:window/grid w))))]
                  :when (not ok)]
              {:lag lag :drop drop :status (:plan/status w) :reason (:plan/reason w) :held (held w)
               :grid (:window/grid w) :violations (:sim/violations sim)})]
    (is (empty? bad) (pr-str (vec bad)))))

(deftest a-lost-click-is-a-stale-window-never-a-blind-take
  (let [column (world/column-bytes {[6 64 0] 136 [6 65 0] 136 [6 66 0] 136 [6 67 0] 252})
        [w sim] (sim/run step (game/init fx/opts)
                         (sim/init {:column column :spawn [0.5 64.0 0.5] :drop-clicks #{1}})
                         #(or (plan/done? %) (plan/failed? %)) 120000 {:event/kind :go :go/goals [:kit]})]
    (is (plan/done? w) "it recovers: the failed craft reclaims the grid and tries again")
    (is (= [] (:sim/violations sim)))
    (is (= 4 (:stick (held w))))))

(deftest places-a-table-and-crafts-a-wooden-pickaxe
  (let [column (world/column-bytes {[6 64 0] 136 [6 65 0] 136 [6 66 0] 136 [6 67 0] 136 [6 68 0] 252})
        [w sim] (sim/run step (game/init fx/opts) (sim/init {:column column :spawn [0.5 64.0 0.5]})
                         #(or (plan/done? %) (plan/failed? %)) 180000 {:event/kind :go :go/goals [:pickaxe]})]
    (is (plan/done? w) (pr-str (plan/summary w)))
    (is (= 1 (:wooden_pickaxe (held w))))
    (is (= 1 (count (:sim/placed sim))) "one table placed in the world")
    (is (nil? (:sim/window sim)) "the table window was closed")
    (is (nil? (:window/open w)))
    (is (= [] (:sim/violations sim)))
    (is (zero? (:plan/attempts w)))
    (testing "the table is remembered where the server put it"
      (let [[pos state] (first (:sim/placed sim))]
        (is (= state (memory/remembered w pos)))))))

(deftest a-low-canopy-is-cleared-to-reach-the-drop
  ;; the first CI failure: oak leaves one block above the ground between the bot and the trunk;
  ;; the drop lands under them, out of reach of a 1.8-tall player
  (let [canopy (into {} (for [x [3 4] z [0 1]] [[x 65 z] 252]))
        column (world/column-bytes (merge canopy {[5 64 0] 136 [5 65 0] 136 [5 66 0] 136 [5 67 0] 252}))
        [w sim] (sim/run step (game/init fx/opts) (sim/init {:column column :spawn [0.5 64.0 0.5]})
                         #(or (plan/done? %) (plan/failed? %)) 60000 {:event/kind :go :go/goals [:wood]})]
    (is (plan/done? w) (pr-str (plan/summary w)))
    (is (= 1 (inventory/logs-held w)))
    (is (contains? (:sim/broken sim) [3 65 0]) "it broke the leaf in its way")
    (is (zero? (:plan/attempts w)) "no failed attempts")))

(deftest a-stale-click-gets-the-full-window
  (let [sim (-> (sim/init {:column (world/column-bytes {}) :spawn [0.5 64.0 0.5]
                           :inventory {10 {:item (recipe/item-id :oak_planks) :count 3}}})
                (assoc :sim/phase :play :sim/window {:id 1 :grid {} :state-id 4})
                (sim/step {:sim/kind :packet :sim/packet {:packet/name :container-click :window-id 1 :state-id 2
                                                          :slot 11 :button 0 :mode 0 :changed [] :cursor nil}}))
        [reply] (:sim/out sim)]
    (is (= :container-set-content (:packet/name reply)) "a stale state id is answered with the whole window")
    (is (= 5 (:state-id reply)))
    (is (= {:item (recipe/item-id :oak_planks) :count 3} (:sim/cursor sim)) "and the click was applied")))

(deftest a-pickaxe-from-held-planks-sticks-and-a-table
  ;; issue #2's end state: the table item and the ingredients already held, on bare ground
  (let [planks (recipe/item-id :oak_planks) stick (recipe/item-id :stick) table (recipe/item-id :crafting_table)
        [w sim] (sim/run step (game/init fx/opts)
                         (sim/init {:column (world/column-bytes {}) :spawn [0.5 64.0 0.5]
                                    :inventory {36 {:item planks :count 4} 37 {:item stick :count 2} 38 {:item table :count 1}}})
                         #(or (plan/done? %) (plan/failed? %)) 60000 {:event/kind :go :go/goals [:pickaxe]})]
    (is (plan/done? w) (pr-str (plan/summary w)))
    (is (= 1 (:wooden_pickaxe (held w))))
    (is (< (:time/now w) 60000) "under 60 simulated seconds")
    (is (= 1 (count (:sim/placed sim))))
    (is (= [] (:sim/violations sim)))))

(deftest the-sim-flags-a-click-into-window-0-while-a-container-is-open
  (let [sim (-> (sim/init {:column (world/column-bytes {}) :spawn [0.5 64.0 0.5]})
                (assoc :sim/phase :play :sim/window {:id 1 :grid {} :state-id 1})
                (sim/step {:sim/kind :packet :sim/packet {:packet/name :container-click :window-id 0 :state-id 1
                                                          :slot 36 :button 0 :mode 0 :changed [] :cursor nil}}))]
    (is (= [[:click-inventory-while-open 0]] (:sim/violations sim)))
    (is (= [] (:sim/out sim)) "vanilla ignores it: no answer")))

(deftest an-early-finish-does-not-break-the-block
  (let [sim0 (assoc (sim/init {:column (world/column-bytes {[1 64 0] 1}) :spawn [0.5 64.0 0.5]}) :sim/phase :play :sim/now 1000)
        sim (-> sim0
                (sim/step {:sim/kind :packet :sim/packet {:packet/name :player-action :status 0 :pos [1 64 0] :face 4 :sequence 1}})
                (sim/step {:sim/kind :tick :sim/now 2000})
                (sim/step {:sim/kind :packet :sim/packet {:packet/name :player-action :status 2 :pos [1 64 0] :face 4 :sequence 2}}))]
    (is (empty? (:sim/broken sim)))
    (is (= [:block-update :block-changed-ack] (mapv :packet/name (:sim/out sim)))
        "the real server restores the client's view of the block, then acks")
    (is (= 1 (:state (first (:sim/out sim)))) "restored to stone")))

;; ---------------------------------------------------------------- digging with tools, on the model

(def wooden-pickaxe (get blocks/items :wooden_pickaxe))
(def stone-id 1)
(def iron-ore-id (first (blocks/states-where (fn [[n]] (= n :iron_ore)))))

(defn dig-on-model
  "Start a dig of [1 64 0] (block id) at sim time 1000 with the item in hotbar slot 0 held,
   FINISH after wait-ms; returns the sim."
  [id item wait-ms]
  (let [sim0 (assoc (sim/init {:column (world/column-bytes {[1 64 0] id})
                               :spawn [0.5 64.0 0.5]
                               :inventory (if item {36 {:item item :count 1}} {})})
                    :sim/phase :play :sim/now 1000 :sim/player-pos [0.5 64.0 0.5])]
    (-> sim0
        (sim/step {:sim/kind :packet :sim/packet {:packet/name :set-carried-item :slot 0}})
        (sim/step {:sim/kind :packet :sim/packet {:packet/name :player-action :status 0 :pos [1 64 0] :face 4 :sequence 1}})
        (sim/step {:sim/kind :tick :sim/now (+ 1000 wait-ms)})
        (sim/step {:sim/kind :packet :sim/packet {:packet/name :player-action :status 2 :pos [1 64 0] :face 4 :sequence 2}}))))

(deftest stone-by-hand-breaks-and-drops-cobblestone
  ;; the game's tags: stone needs no tier, so a hand harvests it, in 2300 ms
  (let [sim (dig-on-model stone-id nil 2300)]
    (is (contains? (:sim/broken sim) [1 64 0]))
    (is (= {:item (get blocks/items :cobblestone) :count 1} (:item (first (vals (:sim/items sim))))) "cobblestone, not stone")))

(deftest stone-with-a-wooden-pickaxe-breaks-sooner-and-wears-the-pickaxe
  ;; with the pickaxe held the block's time is 1150 ms, and the server breaks at 70% of it,
  ;; 805 ms: a FINISH at 700 ms is refused, one at 1000 ms (long before the hand's 2300) accepted
  (let [early (dig-on-model stone-id wooden-pickaxe 700)
        sim (dig-on-model stone-id wooden-pickaxe 1000)]
    (is (empty? (:sim/broken early)))
    (is (contains? (:sim/broken sim) [1 64 0]))
    (is (= 58 (get-in sim [:sim/inv 36 :durability])) "one use off 59")))

(deftest iron-ore-with-a-wooden-pickaxe-breaks-but-drops-nothing
  (let [sim (dig-on-model iron-ore-id wooden-pickaxe 7500)]
    (is (contains? (:sim/broken sim) [1 64 0]))
    (is (empty? (:sim/items sim)) "the wrong tier: no drop")))

(deftest a-worn-out-tool-leaves-its-slot
  (let [sim0 (assoc (sim/init {:column (world/column-bytes {[1 64 0] stone-id}) :spawn [0.5 64.0 0.5]
                               :inventory {36 {:item wooden-pickaxe :count 1 :durability 1}}})
                    :sim/phase :play :sim/now 1000 :sim/player-pos [0.5 64.0 0.5])
        sim (-> sim0
                (sim/step {:sim/kind :packet :sim/packet {:packet/name :set-carried-item :slot 0}})
                (sim/step {:sim/kind :packet :sim/packet {:packet/name :player-action :status 0 :pos [1 64 0] :face 4 :sequence 1}})
                (sim/step {:sim/kind :tick :sim/now 2200})
                (sim/step {:sim/kind :packet :sim/packet {:packet/name :player-action :status 2 :pos [1 64 0] :face 4 :sequence 2}}))]
    (is (contains? (:sim/broken sim) [1 64 0]))
    (is (nil? (get-in sim [:sim/inv 36])) "the last use broke it")
    (is (some #(and (= :container-set-slot (:packet/name %)) (= 36 (:slot %)) (nil? (:item %))) (:sim/out sim))
        "and the client is told the slot is empty")))

(deftest every-packet-the-bot-sends-fits-the-table
  ;; instrumentation checks what a reducer is given; this checks what the bot says, over a whole
  ;; pickaxe run (handshake, digs, clicks, placement, a table window): every packet it sends has
  ;; every field packet/specs lists, each in its wire range, or the writer could not encode it.
  (let [bad (atom [])
        checked (fn [w e] (let [w (step w e)]
                            (doseq [p (sim/sends w) :when (not (clojure.spec.alpha/valid? :clojurecraft.spec/packet p))]
                              (swap! bad conj p))
                            w))
        column (world/column-bytes {[6 64 0] 136 [6 65 0] 136 [6 66 0] 136 [6 67 0] 136 [6 68 0] 252})
        [w _] (sim/run checked (game/init fx/opts) (sim/init {:column column :spawn [0.5 64.0 0.5]})
                       #(or (plan/done? %) (plan/failed? %)) 200000 {:event/kind :go :go/goals [:pickaxe]})]
    (is (plan/done? w) (pr-str (plan/summary w)))
    (is (= [] @bad))))

;; ---------------------------------------------------------------- generated worlds

(def log-species "Axis-y log state ids: oak, spruce, birch." [137 140 143])
(def leaf-id 252)

(def tree-gen
  "One tree: a trunk of 3-6 logs of one species at a local (x, z) at least 3 blocks from the
   spawn columns, with a leaf block on top; maybe on a stone bank 1 or 2 high (the 3x3 square
   around the trunk, so a 2-high bank holds the drop out of reach from every side), maybe with low leaves one block above its base over every cell
   within two steps of the trunk, maybe (4 logs or more) with a branch: a log beside the trunk,
   two below its top, with air under it. A bank under a low canopy is the ledge a live run once
   failed on; a branch and a bank above the feet are what the fleet found at 21280,18720 (a 2-high
   bank puts the drop at the top of the pickup box)."
  (gen/let [x (gen/choose 3 13) z (gen/choose 3 13) h (gen/choose 3 6) id (gen/elements log-species)
            mound (gen/elements [0 0 1 2]) canopy? gen/boolean
            branch (gen/elements [nil [1 0] [-1 0] [0 1] [0 -1]])]
    {:x x :z z :h h :id id :mound mound :canopy? canopy? :branch (when (>= h 4) branch)}))

(def stone 1)

(defn tree-blocks
  "{[lx y lz] id} for one tree."
  [{:keys [x z h id mound canopy? branch]}]
  (let [base (+ 64 mound)
        [bx bz] (when branch [(+ x (first branch)) (+ z (second branch))])]
    (merge (into {} (for [mx (range (dec x) (+ x 2)) mz (range (dec z) (+ z 2)) :when (and (<= 0 mx 15) (<= 0 mz 15)) y (range 64 base)] [[mx y mz] stone]))
           (when canopy? (into {} (for [dx (range -2 3) dz (range -2 3)
                                        :let [cx (+ x dx) cz (+ z dz) d (+ (abs dx) (abs dz))]
                                        :when (and (<= 1 d 2) (<= 0 cx 15) (<= 0 cz 15))]
                                    [[cx (inc base) cz] leaf-id])))
           (into {} (for [y (range base (+ base h))] [[x y z] id]))
           (when (and branch (<= 0 bx 15) (<= 0 bz 15)) {[bx (+ base h -2) bz] id})
           {[x (+ base h) z] leaf-id})))

(defn apart?
  "Do the trees' 3x3 squares keep at least one clear block between them? Overlapping trees would
   merge (a bare trunk inside another's bank), and the property's oracle reads each tree alone."
  [trees]
  (every? (fn [[a b]] (>= (max (abs (- (:x a) (:x b))) (abs (- (:z a) (:z b)))) 4))
          (for [[i a] (map-indexed vector trees) b (drop (inc i) trees)] [a b])))

(def forest-gen "1-3 trees, apart." (gen/such-that apart? (gen/vector tree-gen 1 3) 100))

(defn forest
  "{[lx y lz] id} for trees on the stone floor (y 64 up); a later tree's blocks win."
  [trees]
  (apply merge (map tree-blocks trees)))

(deftest the-wood-goal-holds-in-generated-forests
  ;; the bot against the model in 2,000 worlds test.check builds (docs/hickey.md: "thousands of
  ;; generated worlds in the time one real run takes"): 1-3 trees of mixed species and heights
  ;; anywhere in the column, on mounds or flat ground, under low leaves or open sky, from a
  ;; varied spawn. Where any tree stands on a bank it can climb (0 or 1 high) it ends holding a
  ;; log; where every tree stands on a 2-high bank (the drop rests on the bank, beyond a pickup
  ;; from the ground; digging a step into the bank is not yet a thing it can do) it gives the
  ;; trees up (two tries a trunk) and waits or fails :no-log rather than looping. Either way the
  ;; sim never sees something a real server would punish, within the gym's own time for the goal. FOREST_TRIALS=n overrides the count, FOREST_SEED=s fixes the seed
  ;; (a fleet shard's), FOREST_OUT=f writes the verdict as EDN.
  (let [r (tc/quick-check
           (or (some-> (System/getenv "FOREST_TRIALS") Long/parseLong) 2000)
           (prop/for-all [trees forest-gen sx (gen/choose 0 1) sz (gen/choose 0 1)]
                         (let [sim0 (sim/init {:column (world/column-bytes (forest trees)) :spawn [(+ sx 0.5) 64.0 (+ sz 0.5)]})
                               [w sim] (sim/run step (game/init fx/opts) sim0 #(or (plan/done? %) (plan/failed? %))
                                                (:gym/timeout-ms (gym/gym "wood")) {:event/kind :go :go/goals [:wood]})]
                           (and (if (some #(<= (:mound %) 1) trees)
                                  (and (plan/done? w) (= 1 (inventory/logs-held w)))
                                  (and (not (plan/done? w)) (= :no-log (or (:plan/reason w) (:plan/waiting w))) (zero? (inventory/logs-held w))))
                                (empty? (:sim/violations sim)))))
           :seed (or (some-> (System/getenv "FOREST_SEED") Long/parseLong) (System/currentTimeMillis)))]
    (when-let [out (System/getenv "FOREST_OUT")]               ; a fleet shard reads its verdict as data
      (spit out (pr-str {:pass? (boolean (:pass? r)) :num-tests (:num-tests r) :seed (:seed r)
                         :smallest (get-in r [:shrunk :smallest])})))
    (is (:pass? r) (pr-str (select-keys r [:fail :shrunk :num-tests :seed])))))

;; ---------------------------------------------------------------- intentions as facts

(deftest what-the-bot-was-doing-is-a-query-as-of-any-moment
  (let [column (world/column-bytes {[6 64 0] 136 [6 65 0] 136 [6 66 0] 136 [6 67 0] 252})
        [w _] (sim/run step (game/init fx/opts) (sim/init {:column column :spawn [0.5 64.0 0.5]})
                       #(or (plan/done? %) (plan/failed? %)) 60000 {:event/kind :go :go/goals [:wood]})
        story (memory/intentions w)
        started (filter #(= :started (:intention/event %)) story)]
    (is (plan/done? w))
    (testing "every intent that started has a fact for how it ended (done, or abandoned once the goal was met), in order"
      (is (= [:walk :dig :collect] (mapv :intention/kind started)))
      (is (= (mapv :intention/id started) (distinct (map :intention/id story))))
      (is (every? (fn [{:intention/keys [id]}] (some #(and (= id (:intention/id %)) (#{:done :abandoned} (:intention/event %))) story))
                  started)))
    (testing "as of each start, that intent is what the bot was doing; after the last end, nothing"
      (doseq [s started]
        (is (= [(:intention/id s) :started] ((juxt :intention/id :intention/event) (memory/intention-as-of w (:intention/at s))))))
      (is (nil? (memory/intention-as-of w (:time/now w))))
      (is (nil? (memory/intention-as-of w -1)) "before anything started"))))

(deftest a-new-go-abandons-the-running-intent
  (let [w (-> (game/init fx/opts)
              (assoc :time/now 100 :plan/status :active)
              (plan/start-intent {:intent/kind :walk :intent/target [3 64 0]}))
        w (plan/step (assoc w :time/now 200) {:event/kind :go :go/goals [:kit]})]
    (is (= [:started :abandoned] (mapv :intention/event (memory/intentions w))))
    (is (= {:intention/kind :walk :intention/target [3 64 0]}
           (select-keys (memory/intention-as-of w 150) [:intention/kind :intention/target])))
    (is (nil? (memory/intention-as-of w 200)))))

(deftest the-servers-answers-are-facts
  (let [column (world/column-bytes {[6 64 0] 136 [6 65 0] 136 [6 66 0] 136 [6 67 0] 252})
        [w _] (sim/run step (game/init fx/opts) (sim/init {:column column :spawn [0.5 64.0 0.5]})
                       #(or (plan/done? %) (plan/failed? %)) 60000 {:event/kind :go :go/goals [:wood]})
        acks (memory/answers w :ack)
        pickups (memory/answers w :pickup)]
    (is (plan/done? w))
    (is (= [(:stats/last-ack w)] (map :answer/sequence (take-last 1 acks))) "the dig's FINISH was acknowledged")
    (is (= 1 (count pickups)))
    (is (= 1 (:answer/count (first pickups))))
    (testing "the pickup came after the dig was acknowledged"
      (is (<= (:answer/at (last acks)) (:answer/at (first pickups)))))))

(deftest a-drop-on-a-ledge-under-leaves-is-reached
  ;; recorded live 2026-10-10 and replayed: the drop lay one block up a ledge whose edge had
  ;; leaves at the height a jump needs, and over the bot too, so no jump rose a full block; the
  ;; bot jumped against them for 11 s, three times over
  (let [ledge (into {} (for [x [3 4 5 6] z [0 1]] [[x 64 z] 1]))
        ceiling (into {} (for [x [1 2 3 4] z [0 1]] [[x 66 z] 252]))
        column (world/column-bytes (merge ledge ceiling {[5 65 0] 136 [5 66 0] 136 [5 67 0] 136 [5 68 0] 252}))
        [w sim] (sim/run step (game/init fx/opts) (sim/init {:column column :spawn [0.5 64.0 0.5]})
                         #(or (plan/done? %) (plan/failed? %)) 60000 {:event/kind :go :go/goals [:wood]})]
    (is (plan/done? w) (pr-str (plan/summary w)))
    (is (= 1 (inventory/logs-held w)))
    (is (some (:sim/broken sim) (keys ceiling)) "it broke a leaf over the ledge")))

(def bank
  "A trunk on a stone bank four blocks high (x 2), the pit beside it walled in (z 2; the chunk's
   unloaded neighbours wall x -1 and z -1)."
  (merge (into {} (for [y (range 64 68) z [0 1 2]] [[2 y z] 1]))
         (into {} (for [y (range 64 68) x [0 1]] [[x y 2] 1]))
         {[2 68 0] 136 [2 69 0] 136 [2 70 0] 136 [2 71 0] 136 [2 72 0] 252}))

(deftest a-drop-out-of-reach-is-never-dug-for
  ;; recorded by the gym and the fleet at landing 21280,18720 (wood-a2 run 5, smoke2 run 5): the
  ;; bot stood in a pit, dug the base of a trunk on the bank four blocks above its feet, and the
  ;; drop came to rest on the bank, above a 1.8-tall player's pickup box. Reach is not enough: a
  ;; log walk arrives only where the drop will land within pickup height; a trunk it cannot
  ;; reach is given up whole.
  (let [[w sim] (sim/run step (game/init fx/opts) (sim/init {:column (world/column-bytes bank) :spawn [1.5 64.0 0.5]})
                         #(or (plan/done? %) (plan/failed? %) (= :no-log (:plan/waiting %))) 60000
                         {:event/kind :go :go/goals [:wood]})]
    (is (not-any? (:sim/broken sim) [[2 68 0] [2 69 0]]) "no log dug whose drop it could not take")
    (is (pos? (get-in w [:plan/trunk-failures [2 0]] 0)) "the failure is counted against the trunk")
    (is (= :no-log (:plan/waiting w)) (pr-str (plan/summary w)))))

(deftest a-trunk-on-a-bank-is-taken-from-the-bank
  (let [platform (into {} (for [y (range 64 68) x [0 1] z [0 1]] [[x y z] 1]))
        [w _] (sim/run step (game/init fx/opts)
                       (sim/init {:column (world/column-bytes (merge bank platform)) :spawn [0.5 68.0 0.5]})
                       #(or (plan/done? %) (plan/failed? %)) 60000 {:event/kind :go :go/goals [:wood]})]
    (is (plan/done? w) (pr-str (plan/summary w)))
    (is (= 1 (inventory/logs-held w)))))

(def cliff-and-pond
  "The only log stands on a plateau two blocks up (x 9..15), with a pond (water two deep) across
   the whole of x 4..6 but for a dry strip at z 15, and the plateau's edge is a two-block wall
   except for a one-block step at z 14: the straight line from the spawn ends in the pond or
   against the wall; the route goes along the strip and up the step."
  (merge (into {} (for [x (range 9 16) y [64 65] z (range 0 16)] [[x y z] 1]))        ; the plateau
         (into {} (for [x (range 4 7) y [62 63] z (range 0 15)] [[x y z] 86]))        ; the pond, two deep
         (into {} (for [x (range 4 7) y [64] z (range 0 15)] [[x y z] 86]))           ; its surface
         {[8 64 14] 1}                                                                ; the step
         {[12 66 2] 136 [12 67 2] 136 [12 68 2] 136 [12 69 2] 252}))                  ; the tree

(deftest a-log-across-a-cliff-and-a-pond-is-reached-by-a-route
  ;; issue #4: a pond between the bot and the trunk, and a two-block ledge, each ended in :stuck
  ;; before the pathfinder; now the walk plans a route and nothing is blacklisted
  (let [[w sim] (sim/run step (game/init fx/opts)
                         (sim/init {:column (world/column-bytes cliff-and-pond) :spawn [1.5 64.0 1.5]})
                         #(or (plan/done? %) (plan/failed? %)) 90000 {:event/kind :go :go/goals [:wood]})]
    (is (plan/done? w) (pr-str (plan/summary w)))
    (is (= 1 (inventory/logs-held w)))
    (is (zero? (:plan/attempts w)) "no intent failed")
    (is (empty? (:plan/blacklist w)))
    (is (not-any? #(= :stuck (:intention/reason %)) (memory/intentions w)) "never :stuck")
    (is (empty? (:sim/violations sim)))
    (is (>= (second (:player/pos w)) 66.0) "it climbed the plateau")))

;; ---------------------------------------------------------------- through a channel pair

(deftest main's-own-loop-against-the-model-through-channels
  ;; docs/hickey.md: "Bot and server model composed through a channel pair are a complete
  ;; simulation with no Java process." The loop here is main's: wall-clock ticks, effects
  ;; drained onto :out, packets read from :in, :go put on the events channel once loaded.
  (let [column (world/column-bytes {[6 64 0] 136 [6 65 0] 136 [6 66 0] 136 [6 67 0] 252})
        c (sim/connect (sim/init {:column column :spawn [0.5 64.0 0.5] :keep-alive-every 1000}))
        events (a/chan 16)
        world* (atom (game/init fx/opts))
        deadline (+ (System/currentTimeMillis) 30000)]
    (main/apply-event! world* {:event/kind :start :start/host "sim" :start/port 0 :start/name "Clj_test"} (:out c) nil)
    (main/go-when-loaded! world* events {:event/kind :go :go/goals [:wood]})
    (let [w (main/run-loop c events world* #(or (plan/done? %) (plan/failed? %) (> (System/currentTimeMillis) deadline)) nil)]
      ((:close! c))
      (is (plan/done? w) (pr-str (plan/summary w)))
      (is (= 1 (inventory/logs-held w)))
      (is (pos? (:stats/keep-alives w)) "the model's keep-alives arrived through the channel and were answered"))))

;; ---------------------------------------------------------------- stairs down, on the model

(defn stairs-run
  "Run a :stairs-down intent alone against the model from a spawn over column blocks, the
   feet at feet-y, with the item in hotbar slot 0, until it ends or max-ms. Returns [world sim]."
  [blocks spawn-y item to-y max-ms]
  (let [column (world/column-bytes blocks)
        step (fn [w e]
               (let [w (game/step w e)
                     i (:plan/intent w)]
                 (cond
                   (and (= :go (:event/kind e)))
                   (assoc w :plan/intent {:intent/kind :stairs-down :intent/to-y to-y :intent/status :active})
                   (and (= :tick (:event/kind e)) (= :active (:intent/status i)) (:player/loaded? w))
                   (intent/run w i e)
                   :else w)))
        sim0 (sim/init {:column column :spawn [8.5 (double spawn-y) 8.5]
                        :inventory (if item {36 {:item item :count 1}} {})})]
    (sim/run step (game/init fx/opts) sim0
             #(contains? #{:done :failed} (:intent/status (:plan/intent %))) max-ms)))

(def deep-stone
  "Stone from y 64 up to y 75 everywhere: a hill to descend into."
  (into {} (for [x (range 16) y (range 64 76) z (range 16)] [[x y z] 1])))

(deftest a-stone-hill-is-descended-five-stairs-with-a-pickaxe
  (let [[w sim] (stairs-run deep-stone 76 (get blocks/items :wooden_pickaxe) 71 120000)]
    (is (= :done (:intent/status (:plan/intent w))) (pr-str (select-keys (:plan/intent w) [:intent/status :intent/reason :intent/stage :intent/turns])))
    (is (<= (second (:player/pos w)) 71.5) "the feet are five stairs down")
    (is (every? #(not= [8 75 8] %) (:sim/broken sim)) "the block under the start was never dug")
    (is (empty? (:sim/violations sim)))))

(deftest lava-ahead-turns-the-stairs
  ;; lava in the floor two cells along +x: the first heading is refused, the next taken
  (let [lava (first (blocks/states-where (fn [[n]] (= n :lava))))
        blocks (assoc deep-stone [10 74 8] lava [10 73 8] lava)
        [w sim] (stairs-run blocks 76 (get blocks/items :wooden_pickaxe) 74 60000)]
    (is (= :done (:intent/status (:plan/intent w))) (pr-str (select-keys (:plan/intent w) [:intent/status :intent/reason :intent/stage :intent/turns])))
    (is (not= [1 0] (:intent/dir (:plan/intent w))) "it turned away from +x")
    (is (not-any? #(= 10 (first %)) (:sim/broken sim)) "nothing was dug toward the lava")))

(deftest a-water-floor-ahead-refuses-the-stair
  (let [water 86
        blocks (-> deep-stone (assoc [9 74 8] water))                       ; the first stair's new feet on +x would rest on water
        [w _] (stairs-run blocks 76 (get blocks/items :wooden_pickaxe) 74 60000)]
    (is (= :done (:intent/status (:plan/intent w))))
    (is (not= [1 0] (:intent/dir (:plan/intent w))) "turned")))

(deftest ore-the-pickaxe-cannot-drop-turns-the-stairs
  ;; gym batch cobble-a2 run 2: four levels down the stair met iron ore, the child dig refused
  ;; :needs-tool (a wooden pickaxe drops nothing from it), and the stair failed with it, three
  ;; times in a second. An undroppable cell is a refusal like lava: turn
  (let [iron-ore (first (blocks/states-where (fn [[n]] (= n :iron_ore))))
        blocks (assoc deep-stone [9 75 8] iron-ore)                              ; the first stair's head cell on +x
        [w sim] (stairs-run blocks 76 (get blocks/items :wooden_pickaxe) 74 60000)]
    (is (= :done (:intent/status (:plan/intent w))) (pr-str (select-keys (:plan/intent w) [:intent/status :intent/reason :intent/stage :intent/turns])))
    (is (not= [1 0] (:intent/dir (:plan/intent w))) "it turned away from the ore")
    (is (not (contains? (:sim/broken sim) [9 75 8])) "the ore was never dug")))

(deftest boxed-in-by-lava-on-every-side-fails-boxed
  (let [lava (first (blocks/states-where (fn [[n]] (= n :lava))))
        blocks (reduce (fn [b [x z]] (assoc b [x 74 z] lava)) deep-stone [[9 8] [7 8] [8 9] [8 7]])
        [w sim] (stairs-run blocks 76 (get blocks/items :wooden_pickaxe) 70 60000)]
    (is (= :failed (:intent/status (:plan/intent w))))
    (is (= :boxed (:intent/reason (:plan/intent w))))
    (is (empty? (:sim/broken sim)) "nothing was dug at all")))

;; ---------------------------------------------------------------- cobblestone, on the model

(def forest-over-stone
  "A tree beside the spawn and stone from y 60 to 63 under a dirt floor at 64, with one face of
   stone exposed in a pit at x 12: the cobblestone goal either walks to the pit or cuts stairs."
  (merge (into {} (for [x (range 16) y (range 60 64) z (range 16)] [[x y z] 1]))        ; stone
         (into {} (for [x (range 16) z (range 16)] [[x 64 z] 10]))                         ; dirt on top (10 = dirt)
         {[12 64 8] 0 [12 63 8] 0}                                                        ; a pit exposing stone at [12 62 8] and its walls
         {[3 65 2] 136 [3 66 2] 136 [3 67 2] 136 [3 68 2] 252}))                         ; a tree

(deftest three-cobblestone-with-a-wooden-pickaxe-and-it-is-kept
  (let [[w sim] (sim/run step (game/init fx/opts)
                         (sim/init {:column (world/column-bytes forest-over-stone) :spawn [8.5 65.0 8.5]
                                    :inventory {36 {:item (get blocks/items :wooden_pickaxe) :count 1}}})
                         #(or (plan/done? %) (plan/failed? %)) 240000 {:event/kind :go :go/goals [:cobblestone]})]
    (is (plan/done? w) (pr-str (plan/summary w)))
    (is (>= (get (held w) :cobblestone 0) 3))
    (is (= 1 (get (held w) :wooden_pickaxe 0)) "the pickaxe is still held")
    (is (empty? (:sim/violations sim)))))

(deftest bare-handed-the-cobblestone-goal-never-digs-stone
  ;; with no pickaxe the needs planner plans for one (the tree beside the spawn gives it logs);
  ;; what must never happen is a hand dig of stone, which drops cobblestone at 2300 ms a block
  ;; but would cost a race its tool chain. The run ends when a pickaxe is in hand, or the plan
  ;; gives up (:no-log once the one tree is spent)
  (let [[w sim] (sim/run step (game/init fx/opts)
                         (sim/init {:column (world/column-bytes forest-over-stone) :spawn [8.5 65.0 8.5]})
                         #(or (plan/done? %) (plan/failed? %) (pos? (get (held %) :wooden_pickaxe 0))) 120000
                         {:event/kind :go :go/goals [:cobblestone]})]
    (is (not-any? #(= 1 (sim/block-at (assoc sim :sim/broken #{}) %)) (:sim/broken sim)) "no stone was dug by hand")
    (is (or (pos? (get (held w) :wooden_pickaxe 0)) (plan/failed? w)) (pr-str (plan/summary w)))))
