(ns clojurecraft.sim-test
  "The whole bot against the pure server model: handshake → spawn → chunk → find → walk → dig →
   drop → pickup → done, with no socket and no Java process."
  (:require [clojure.core.async :as async]
            [clojure.spec.alpha]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.test.check :as tc]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [clojurecraft.blocks :as blocks]
            [clojurecraft.fixtures :as fx]
            [clojurecraft.game :as game]
            [clojurecraft.gym :as gym]
            [clojurecraft.intent :as intent]
            [clojurecraft.inventory :as inventory]
            [clojurecraft.main :as main]
            [clojurecraft.make :as make]
            [clojurecraft.memory :as memory]
            [clojurecraft.path :as path]
            [clojurecraft.plan :as plan]
            [clojurecraft.recipe :as recipe]
            [clojurecraft.record :as record]
            [clojurecraft.scoop]
            [clojurecraft.sim :as sim]
            [clojurecraft.stairs]
            [clojurecraft.stone]
            [clojurecraft.swim]
            [clojurecraft.terrain :as terrain]
            [clojurecraft.trap :as trap]
            [clojurecraft.wood]
            [clojurecraft.world :as world]))

(use-fixtures :once fx/instrumented)

(def step (game/compose game/step plan/step))

(deftest collects-one-log-against-the-model
  (let [column (world/column-bytes {[6 64 0] 136 [6 65 0] 136 [6 66 0] 136 [6 67 0] 252})
        sim0 (sim/init {:column column :spawn [0.5 64.0 0.5] :keep-alive-every 3000})
        [w _] (sim/run step
                       (game/init fx/opts)
                       sim0
                       #(or (plan/done? %) (plan/failed? %))
                       60000
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
        [w sim] (sim/run step
                         (game/init fx/opts)
                         sim0
                         #(or (plan/done? %) (plan/failed? %))
                         120000
                         {:event/kind :go :go/goals [:kit]})]
    (is (plan/done? w) (pr-str (plan/summary w)))
    (is (= 1 (:crafting_table (held w))))
    (is (= 4 (:stick (held w))))
    (is (empty? (:window/grid w)) "nothing left in the grid")
    (is (nil? (:window/cursor w)))
    (is (= [] (:sim/violations sim)))
    (testing "the bot's inventory is the server's"
      (is (= (into {}
                   (for [[s it] (:sim/inv sim)
                         :when (<= 9 s 44)]
                     [(inventory/window->player-slot (inventory/layouts :inventory) s) it]))
             (:player/inventory w))))))

(def kit-items #{:oak_log :oak_planks :stick :crafting_table})

(defn kit-run
  [lag drop]
  (let [column (world/column-bytes {[6 64 0] 136 [6 65 0] 136 [6 66 0] 136 [6 67 0] 252})]
    (sim/run
     step
     (game/init fx/opts)
     (sim/init
      {:column column :spawn [0.5 64.0 0.5] :lag-ticks lag :drop-clicks (if drop #{drop} #{})})
     #(or (plan/done? %) (plan/failed? %))
     150000
     {:event/kind :go :go/goals [:kit]})))

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
                                (or (and (plan/done? w)
                                         (= 1 (:crafting_table (held w)))
                                         (<= 4 (:stick (held w) 0)))
                                    (empty? (:window/grid w))))]
                  :when (not ok)]
              {:lag lag
               :drop drop
               :status (:plan/status w)
               :reason (:plan/reason w)
               :held (held w)
               :grid (:window/grid w)
               :violations (:sim/violations sim)})]
    (is (empty? bad) (pr-str (vec bad)))))

(deftest a-lost-click-is-a-stale-window-never-a-blind-take
  (let [column (world/column-bytes {[6 64 0] 136 [6 65 0] 136 [6 66 0] 136 [6 67 0] 252})
        [w sim] (sim/run step
                         (game/init fx/opts)
                         (sim/init {:column column :spawn [0.5 64.0 0.5] :drop-clicks #{1}})
                         #(or (plan/done? %) (plan/failed? %))
                         120000
                         {:event/kind :go :go/goals [:kit]})]
    (is (plan/done? w) "it recovers: the failed craft reclaims the grid and tries again")
    (is (= [] (:sim/violations sim)))
    (is (= 4 (:stick (held w))))))

(deftest places-a-table-and-crafts-a-wooden-pickaxe
  (let [column (world/column-bytes
                {[6 64 0] 136 [6 65 0] 136 [6 66 0] 136 [6 67 0] 136 [6 68 0] 252})
        [w sim] (sim/run step
                         (game/init fx/opts)
                         (sim/init {:column column :spawn [0.5 64.0 0.5]})
                         #(or (plan/done? %) (plan/failed? %))
                         180000
                         {:event/kind :go :go/goals [:pickaxe]})]
    (is (plan/done? w) (pr-str (plan/summary w)))
    (is (= 1 (:wooden_pickaxe (held w))))
    (is (= 1 (count (:sim/placed sim))) "one table placed in the world")
    (is (nil? (:sim/window sim)) "the table window was closed")
    (is (nil? (:window/open w)))
    (is (= [] (:sim/violations sim)))
    (is (zero? (:plan/attempts w)))
    (testing "the table is remembered where the server put it"
      (let [[pos state] (first (:sim/placed sim))] (is (= state (memory/remembered w pos)))))))

(deftest a-low-canopy-is-cleared-to-reach-the-drop
  ;; the first CI failure: oak leaves one block above the ground between the bot and the trunk;
  ;; the drop lands under them, out of reach of a 1.8-tall player
  (let [canopy (into {} (for [x [3 4] z [0 1]] [[x 65 z] 252]))
        column (world/column-bytes (merge canopy
                                          {[5 64 0] 136 [5 65 0] 136 [5 66 0] 136 [5 67 0] 252}))
        [w sim] (sim/run step
                         (game/init fx/opts)
                         (sim/init {:column column :spawn [0.5 64.0 0.5]})
                         #(or (plan/done? %) (plan/failed? %))
                         60000
                         {:event/kind :go :go/goals [:wood]})]
    (is (plan/done? w) (pr-str (plan/summary w)))
    (is (= 1 (inventory/logs-held w)))
    (is (contains? (:sim/broken sim) [3 65 0]) "it broke the leaf in its way")
    (is (zero? (:plan/attempts w)) "no failed attempts")))

(deftest a-stale-click-gets-the-full-window
  (let [sim (-> (sim/init {:column (world/column-bytes {})
                           :spawn [0.5 64.0 0.5]
                           :inventory {10 {:item (recipe/item-id :oak_planks) :count 3}}})
                (assoc :sim/phase :play :sim/window {:id 1 :kind :table :grid {} :state-id 4})
                (sim/step {:sim/kind :packet
                           :sim/packet {:packet/name :container-click
                                        :window-id 1
                                        :state-id 2
                                        :slot 11
                                        :button 0
                                        :mode 0
                                        :changed []
                                        :cursor nil}}))
        [reply] (:sim/out sim)]
    (is (= :container-set-content (:packet/name reply))
        "a stale state id is answered with the whole window")
    (is (= 5 (:state-id reply)))
    (is (= {:item (recipe/item-id :oak_planks) :count 3} (:sim/cursor sim))
        "and the click was applied")))

(deftest a-pickaxe-from-held-planks-sticks-and-a-table
  ;; issue #2's end state: the table item and the ingredients already held, on bare ground
  (let [planks (recipe/item-id :oak_planks)
        stick (recipe/item-id :stick)
        table (recipe/item-id :crafting_table)
        [w sim] (sim/run step
                         (game/init fx/opts)
                         (sim/init {:column (world/column-bytes {})
                                    :spawn [0.5 64.0 0.5]
                                    :inventory {36 {:item planks :count 4}
                                                37 {:item stick :count 2}
                                                38 {:item table :count 1}}})
                         #(or (plan/done? %) (plan/failed? %))
                         60000
                         {:event/kind :go :go/goals [:pickaxe]})]
    (is (plan/done? w) (pr-str (plan/summary w)))
    (is (= 1 (:wooden_pickaxe (held w))))
    (is (< (:time/now w) 60000) "under 60 simulated seconds")
    (is (= 1 (count (:sim/placed sim))))
    (is (= [] (:sim/violations sim)))))

(deftest the-sim-flags-a-click-into-window-0-while-a-container-is-open
  (let [sim (-> (sim/init {:column (world/column-bytes {}) :spawn [0.5 64.0 0.5]})
                (assoc :sim/phase :play :sim/window {:id 1 :kind :table :grid {} :state-id 1})
                (sim/step {:sim/kind :packet
                           :sim/packet {:packet/name :container-click
                                        :window-id 0
                                        :state-id 1
                                        :slot 36
                                        :button 0
                                        :mode 0
                                        :changed []
                                        :cursor nil}}))]
    (is (= [[:click-inventory-while-open 0]] (:sim/violations sim)))
    (is (= [] (:sim/out sim)) "vanilla ignores it: no answer")))

(deftest an-early-finish-does-not-break-the-block
  (let [sim0 (assoc (sim/init {:column (world/column-bytes {[1 64 0] 1}) :spawn [0.5 64.0 0.5]})
                    :sim/phase :play
                    :sim/now 1000)
        sim
        (-> sim0
            (sim/step {:sim/kind :packet
                       :sim/packet
                       {:packet/name :player-action :status 0 :pos [1 64 0] :face 4 :sequence 1}})
            (sim/step {:sim/kind :tick :sim/now 2000})
            (sim/step {:sim/kind :packet
                       :sim/packet
                       {:packet/name :player-action :status 2 :pos [1 64 0] :face 4 :sequence 2}}))]
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
                    :sim/phase :play
                    :sim/now 1000
                    :sim/player-pos [0.5 64.0 0.5])]
    (-> sim0
        (sim/step {:sim/kind :packet :sim/packet {:packet/name :set-carried-item :slot 0}})
        (sim/step {:sim/kind :packet
                   :sim/packet
                   {:packet/name :player-action :status 0 :pos [1 64 0] :face 4 :sequence 1}})
        (sim/step {:sim/kind :tick :sim/now (+ 1000 wait-ms)})
        (sim/step {:sim/kind :packet
                   :sim/packet
                   {:packet/name :player-action :status 2 :pos [1 64 0] :face 4 :sequence 2}}))))

(deftest stone-by-hand-breaks-slowly-and-drops-nothing
  ;; the game's requiresCorrectToolForDrops: stone drops only to a pickaxe; by hand it breaks
  ;; at the no-harvest rate, 7500 ms
  (is (empty? (:sim/broken (dig-on-model stone-id nil 2300))) "not yet at 2300 ms")
  (let [sim (dig-on-model stone-id nil 7500)]
    (is (contains? (:sim/broken sim) [1 64 0]))
    (is (empty? (:sim/items sim)) "nothing dropped")))

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
  (let [sim0 (assoc (sim/init {:column (world/column-bytes {[1 64 0] stone-id})
                               :spawn [0.5 64.0 0.5]
                               :inventory {36 {:item wooden-pickaxe :count 1 :durability 1}}})
                    :sim/phase :play
                    :sim/now 1000
                    :sim/player-pos [0.5 64.0 0.5])
        sim
        (-> sim0
            (sim/step {:sim/kind :packet :sim/packet {:packet/name :set-carried-item :slot 0}})
            (sim/step {:sim/kind :packet
                       :sim/packet
                       {:packet/name :player-action :status 0 :pos [1 64 0] :face 4 :sequence 1}})
            (sim/step {:sim/kind :tick :sim/now 2200})
            (sim/step {:sim/kind :packet
                       :sim/packet
                       {:packet/name :player-action :status 2 :pos [1 64 0] :face 4 :sequence 2}}))]
    (is (contains? (:sim/broken sim) [1 64 0]))
    (is (nil? (get-in sim [:sim/inv 36])) "the last use broke it")
    (is (some #(and (= :container-set-slot (:packet/name %)) (= 36 (:slot %)) (nil? (:item %)))
              (:sim/out sim))
        "and the client is told the slot is empty")))

(deftest every-packet-the-bot-sends-fits-the-table
  ;; instrumentation checks what a reducer is given; this checks what the bot says, over a whole
  ;; pickaxe run (handshake, digs, clicks, placement, a table window): every packet it sends has
  ;; every field packet/specs lists, each in its wire range, or the writer could not encode it.
  (let [bad (atom [])
        checked (fn [w e]
                  (let [w (step w e)]
                    (doseq [p (sim/sends w)
                            :when (not (clojure.spec.alpha/valid? :clojurecraft.spec/packet p))]
                      (swap! bad conj p))
                    w))
        column (world/column-bytes
                {[6 64 0] 136 [6 65 0] 136 [6 66 0] 136 [6 67 0] 136 [6 68 0] 252})
        [w _] (sim/run checked
                       (game/init fx/opts)
                       (sim/init {:column column :spawn [0.5 64.0 0.5]})
                       #(or (plan/done? %) (plan/failed? %))
                       200000
                       {:event/kind :go :go/goals [:pickaxe]})]
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
  (gen/let [x (gen/choose 3 13)
            z (gen/choose 3 13)
            h (gen/choose 3 6)
            id (gen/elements log-species)
            mound (gen/elements [0 0 1 2])
            canopy? gen/boolean
            branch (gen/elements [nil [1 0] [-1 0] [0 1] [0 -1]])]
    {:x x :z z :h h :id id :mound mound :canopy? canopy? :branch (when (>= h 4) branch)}))

(def stone 1)

(defn tree-blocks
  "{[lx y lz] id} for one tree."
  [{:keys [x z h id mound canopy? branch]}]
  (let [base (+ 64 mound)
        [bx bz] (when branch [(+ x (first branch)) (+ z (second branch))])]
    (merge (into {}
                 (for [mx (range (dec x) (+ x 2))
                       mz (range (dec z) (+ z 2))
                       :when (and (<= 0 mx 15) (<= 0 mz 15))
                       y (range 64 base)]
                   [[mx y mz] stone]))
           (when canopy?
             (into {}
                   (for [dx (range -2 3)
                         dz (range -2 3)
                         :let [cx (+ x dx)
                               cz (+ z dz)
                               d (+ (abs dx) (abs dz))]
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
  (let [r (tc/quick-check (or (some-> (System/getenv "FOREST_TRIALS")
                                      Long/parseLong)
                              2000)
                          (prop/for-all
                           [trees forest-gen sx (gen/choose 0 1) sz (gen/choose 0 1)]
                           (let [sim0 (sim/init {:column (world/column-bytes (forest trees))
                                                 :spawn [(+ sx 0.5) 64.0 (+ sz 0.5)]})
                                 [w sim] (sim/run step
                                                  (game/init fx/opts)
                                                  sim0
                                                  #(or (plan/done? %) (plan/failed? %))
                                                  (:gym/timeout-ms (gym/gym "wood"))
                                                  {:event/kind :go :go/goals [:wood]})]
                             (and (if (some #(<= (:mound %) 1) trees)
                                    (and (plan/done? w) (= 1 (inventory/logs-held w)))
                                    (and (not (plan/done? w))
                                         (= :no-log (or (:plan/reason w) (:plan/waiting w)))
                                         (zero? (inventory/logs-held w))))
                                  (empty? (:sim/violations sim)))))
                          :seed
                          (or (some-> (System/getenv "FOREST_SEED")
                                      Long/parseLong)
                              (System/currentTimeMillis)))]
    (when-let [out (System/getenv "FOREST_OUT")] ; a fleet shard reads its verdict as data
      (spit out
            (pr-str {:pass? (boolean (:pass? r))
                     :num-tests (:num-tests r)
                     :seed (:seed r)
                     :smallest (get-in r [:shrunk :smallest])})))
    (is (:pass? r) (pr-str (select-keys r [:fail :shrunk :num-tests :seed])))))

;; ---------------------------------------------------------------- intentions as facts

(deftest what-the-bot-was-doing-is-a-query-as-of-any-moment
  (let [column (world/column-bytes {[6 64 0] 136 [6 65 0] 136 [6 66 0] 136 [6 67 0] 252})
        [w _] (sim/run step
                       (game/init fx/opts)
                       (sim/init {:column column :spawn [0.5 64.0 0.5]})
                       #(or (plan/done? %) (plan/failed? %))
                       60000
                       {:event/kind :go :go/goals [:wood]})
        story (memory/intentions w)
        started (filter #(= :started (:intention/event %)) story)]
    (is (plan/done? w))
    (testing
      "every intent that started has a fact for how it ended (done, or abandoned once the goal was met), in order"
      (is (= [:walk :dig :collect] (mapv :intention/kind started)))
      (is (= (mapv :intention/id started) (distinct (map :intention/id story))))
      (is (every? (fn [{:intention/keys [id]}]
                    (some #(and (= id (:intention/id %)) (#{:done :abandoned} (:intention/event %)))
                          story))
                  started)))
    (testing "as of each start, that intent is what the bot was doing; after the last end, nothing"
      (doseq [s started]
        (is (= [(:intention/id s) :started]
               ((juxt :intention/id :intention/event)
                (memory/intention-as-of w (:intention/at s))))))
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
        [w _] (sim/run step
                       (game/init fx/opts)
                       (sim/init {:column column :spawn [0.5 64.0 0.5]})
                       #(or (plan/done? %) (plan/failed? %))
                       60000
                       {:event/kind :go :go/goals [:wood]})
        acks (memory/answers w :ack)
        pickups (memory/answers w :pickup)]
    (is (plan/done? w))
    (is (= [(:stats/last-ack w)] (map :answer/sequence (take-last 1 acks)))
        "the dig's FINISH was acknowledged")
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
        column (world/column-bytes
                (merge ledge ceiling {[5 65 0] 136 [5 66 0] 136 [5 67 0] 136 [5 68 0] 252}))
        [w sim] (sim/run step
                         (game/init fx/opts)
                         (sim/init {:column column :spawn [0.5 64.0 0.5]})
                         #(or (plan/done? %) (plan/failed? %))
                         60000
                         {:event/kind :go :go/goals [:wood]})]
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
  (let [[w sim] (sim/run step
                         (game/init fx/opts)
                         (sim/init {:column (world/column-bytes bank) :spawn [1.5 64.0 0.5]})
                         #(or (plan/done? %) (plan/failed? %) (= :no-log (:plan/waiting %)))
                         60000
                         {:event/kind :go :go/goals [:wood]})]
    (is (not-any? (:sim/broken sim) [[2 68 0] [2 69 0]]) "no log dug whose drop it could not take")
    (is (pos? (get-in w [:plan/trunk-failures [2 0]] 0)) "the failure is counted against the trunk")
    (is (= :no-log (:plan/waiting w)) (pr-str (plan/summary w)))))

(deftest a-trunk-on-a-bank-is-taken-from-the-bank
  (let [platform (into {} (for [y (range 64 68) x [0 1] z [0 1]] [[x y z] 1]))
        [w _] (sim/run step
                       (game/init fx/opts)
                       (sim/init {:column (world/column-bytes (merge bank platform))
                                  :spawn [0.5 68.0 0.5]})
                       #(or (plan/done? %) (plan/failed? %))
                       60000
                       {:event/kind :go :go/goals [:wood]})]
    (is (plan/done? w) (pr-str (plan/summary w)))
    (is (= 1 (inventory/logs-held w)))))

(def cliff-and-pond
  "The only log stands on a plateau two blocks up (x 9..15), with a pond (water two deep) across
   the whole of x 4..6 but for a dry strip at z 15, and the plateau's edge is a two-block wall
   except for a one-block step at z 14: the straight line from the spawn ends in the pond or
   against the wall; the route goes along the strip and up the step."
  (merge (into {} (for [x (range 9 16) y [64 65] z (range 0 16)] [[x y z] 1])) ; the plateau
         (into {} (for [x (range 4 7) y [62 63] z (range 0 15)] [[x y z] 86])) ; the pond, two deep
         (into {} (for [x (range 4 7) y [64] z (range 0 15)] [[x y z] 86]))    ; its surface
         {[8 64 14] 1}                                                         ; the step
         {[12 66 2] 136 [12 67 2] 136 [12 68 2] 136 [12 69 2] 252}))                  ; the tree

(deftest a-log-across-a-cliff-and-a-pond-is-reached-by-a-route
  ;; issue #4: a pond between the bot and the trunk, and a two-block ledge, each ended in :stuck
  ;; before the pathfinder; now the walk plans a route and nothing is blacklisted
  (let [[w sim] (sim/run step
                         (game/init fx/opts)
                         (sim/init {:column (world/column-bytes cliff-and-pond)
                                    :spawn [1.5 64.0 1.5]})
                         #(or (plan/done? %) (plan/failed? %))
                         90000
                         {:event/kind :go :go/goals [:wood]})]
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
        c (sim/connect! (sim/init {:column column :spawn [0.5 64.0 0.5] :keep-alive-every 1000}))
        events (async/chan 16)
        world* (atom (game/init fx/opts))
        deadline (+ (System/currentTimeMillis) 30000)]
    (main/apply-event! world*
                       {:event/kind :start :start/host "sim" :start/port 0 :start/name "Clj_test"}
                       (:out c)
                       nil)
    (main/go-when-loaded! world* events {:event/kind :go :go/goals [:wood]})
    (let [w (main/run-loop!
             c
             events
             world*
             #(or (plan/done? %) (plan/failed? %) (> (System/currentTimeMillis) deadline))
             nil)]
      ((:close! c))
      (is (plan/done? w) (pr-str (plan/summary w)))
      (is (= 1 (inventory/logs-held w)))
      (is (pos? (:stats/keep-alives w))
          "the model's keep-alives arrived through the channel and were answered"))))

;; ---------------------------------------------------------------- stairs down, on the model

(defn staircase-run
  "Run a staircase intent of kind (:stairs-down, :stairs-up) alone against the model from a spawn
   over column blocks, the feet at feet-y, with the item in hotbar slot 0, until it ends or
   max-ms. Returns [world sim]."
  [kind blocks spawn-y item to-y max-ms]
  (let [column (world/column-bytes blocks)
        step
        (fn [w e]
          (let [w (game/step w e)
                i (:plan/intent w)]
            (cond (= :go (:event/kind e))
                  (assoc w
                         :plan/intent
                         {:intent/kind kind :intent/to-y to-y :intent/status :active})
                  (and (= :tick (:event/kind e)) (= :active (:intent/status i)) (:player/loaded? w))
                  (intent/run w i e)
                  :else w)))
        sim0 (sim/init {:column column
                        :spawn [8.5 (double spawn-y) 8.5]
                        :inventory (if item {36 {:item item :count 1}} {})})]
    (sim/run step
             (game/init fx/opts)
             sim0
             #(contains? #{:done :failed} (:intent/status (:plan/intent %)))
             max-ms)))

(defn stairs-run
  "A :stairs-down run (staircase-run)."
  [blocks spawn-y item to-y max-ms]
  (staircase-run :stairs-down blocks spawn-y item to-y max-ms))

(def deep-stone
  "Stone from y 64 up to y 75 everywhere: a hill to descend into."
  (into {} (for [x (range 16) y (range 64 76) z (range 16)] [[x y z] 1])))

(deftest a-stone-hill-is-descended-five-stairs-with-a-pickaxe
  (let [[w sim] (stairs-run deep-stone 76 (get blocks/items :wooden_pickaxe) 71 120000)]
    (is (= :done (:intent/status (:plan/intent w)))
        (pr-str (select-keys (:plan/intent w)
                             [:intent/status :intent/reason :intent/stage :intent/turns])))
    (is (<= (second (:player/pos w)) 71.5) "the feet are five stairs down")
    (is (every? #(not= [8 75 8] %) (:sim/broken sim)) "the block under the start was never dug")
    (is (empty? (:sim/violations sim)))))

(deftest lava-ahead-turns-the-stairs
  ;; lava in the floor two cells along +x: the first heading is refused, the next taken
  (let [lava (first (blocks/states-where (fn [[n]] (= n :lava))))
        blocks (assoc deep-stone [10 74 8] lava [10 73 8] lava)
        [w sim] (stairs-run blocks 76 (get blocks/items :wooden_pickaxe) 74 60000)]
    (is (= :done (:intent/status (:plan/intent w)))
        (pr-str (select-keys (:plan/intent w)
                             [:intent/status :intent/reason :intent/stage :intent/turns])))
    (is (not= [1 0] (:intent/dir (:plan/intent w))) "it turned away from +x")
    (is (not-any? #(= 10 (first %)) (:sim/broken sim)) "nothing was dug toward the lava")))

(deftest a-water-floor-ahead-refuses-the-stair
  (let [water 86
        blocks (-> deep-stone
                   (assoc [9 74 8] water)) ; the first stair's new feet on +x would rest on water
        [w _] (stairs-run blocks 76 (get blocks/items :wooden_pickaxe) 74 60000)]
    (is (= :done (:intent/status (:plan/intent w))))
    (is (not= [1 0] (:intent/dir (:plan/intent w))) "turned")))

(deftest ore-the-pickaxe-cannot-drop-turns-the-stairs
  ;; gym batch cobble-a2 run 2: four levels down the stair met iron ore, the child dig refused
  ;; :needs-tool (a wooden pickaxe drops nothing from it), and the stair failed with it, three
  ;; times in a second. An undroppable cell is a refusal like lava: turn
  (let [iron-ore (first (blocks/states-where (fn [[n]] (= n :iron_ore))))
        blocks (assoc deep-stone [9 75 8] iron-ore) ; the first stair's head cell on +x
        [w sim] (stairs-run blocks 76 (get blocks/items :wooden_pickaxe) 74 60000)]
    (is (= :done (:intent/status (:plan/intent w)))
        (pr-str (select-keys (:plan/intent w)
                             [:intent/status :intent/reason :intent/stage :intent/turns])))
    (is (not= [1 0] (:intent/dir (:plan/intent w))) "it turned away from the ore")
    (is (not (contains? (:sim/broken sim) [9 75 8])) "the ore was never dug")))

(deftest water-beside-a-stair-cell-turns-the-stairs
  ;; live iron-kit trial (local landing 2360,2360, 2026-10-11): at y 36 an aquifer lay beside the
  ;; next stair's cells on three sides; the child dig refused :unsafe (it would let the water in)
  ;; and the stair failed with it, three times in a moment. A cell the dig will not open is a
  ;; refusal like lava or an ore the pickaxe cannot drop: turn
  (let [blocks (assoc deep-stone [9 75 9] blocks/water-base) ; beside the first +x stair's new feet
        [w sim] (stairs-run blocks 76 (get blocks/items :wooden_pickaxe) 74 60000)]
    (is (= :done (:intent/status (:plan/intent w)))
        (pr-str (select-keys (:plan/intent w)
                             [:intent/status :intent/reason :intent/stage :intent/turns])))
    (is (not= [1 0] (:intent/dir (:plan/intent w))) "it turned away from the water")
    (is (not (contains? (:sim/broken sim) [9 75 8])) "the cell beside the water was never dug")))

(deftest water-under-every-stair-is-stepped-around
  ;; live iron-kit run 5 (local landing 3024,2360): an underground lake lay under the floor of
  ;; every stair the bot could cut, and the staircase failed :boxed three times in a moment. A
  ;; level step along a heading that allows one, then stairs again from there
  (let [blocks (reduce (fn [b [x z]] (assoc b [x 74 z] blocks/water-base))
                       deep-stone
                       [[9 8] [7 8] [8 9] [8 7]]) ; the floor under each first stair's new feet
        [w sim] (stairs-run blocks 76 (get blocks/items :wooden_pickaxe) 74 60000)]
    (is (= :done (:intent/status (:plan/intent w)))
        (pr-str (select-keys (:plan/intent w)
                             [:intent/status :intent/reason :intent/stage :intent/turns
                              :intent/level-steps])))
    (is (<= (second (:player/pos w)) 74.5) "two stairs down, past the water")
    (is (= 1 (:intent/level-steps (:plan/intent w))) "one level step around it")
    (is (not-any? #(contains? (:sim/broken sim) %) [[9 75 8] [7 75 8] [8 75 9] [8 75 7]])
        "no stair was cut over the water")
    (is (empty? (:sim/violations sim)))))

(def staircase
  "The deep-stone hill with a one-wide staircase cut down it along +x: feet cells [x (80 - x) 8]
   from x 4 (y 76, the top) to x 8 (y 72), each with its head cell."
  (reduce (fn [b x] (assoc b [x (- 80 x) 8] blocks/air [x (- 81 x) 8] blocks/air))
          deep-stone
          (range 4 9)))

(deftest a-table-and-a-furnace-placed-in-a-staircase-never-plug-it
  ;; gym batch iron-a1 run 2: at the bottom of its staircase the bot put the table on the stair
  ;; behind it (the way back up), then found no spot for the furnace and failed :no-spot
  (let [table (conj plan/goals
                    {:goal/id :both-near
                     :goal/priority 7
                     :goal/target? true
                     :goal/provides {:block/crafting_table :near :block/furnace :near}
                     :goal/done? :provided?})
        [w sim] (sim/run (game/compose game/step (partial plan/step-over table))
                         (game/init fx/opts)
                         (sim/init {:column (world/column-bytes staircase)
                                    :spawn [8.5 72.0 8.5]
                                    :inventory {36 {:item (get blocks/items :crafting_table)
                                                    :count 1}
                                                37 {:item (get blocks/items :furnace) :count 1}}})
                         #(or (plan/done? %) (plan/failed? %))
                         60000
                         {:event/kind :go :go/goals [:both-near]})
        stairs (set (for [x (range 4 9) dy [0 1]] [x (+ (- 80 x) dy) 8]))]
    (is (= :done (:plan/status w)) (pr-str (plan/summary w)))
    (is (= #{:crafting_table :furnace} (set (map blocks/name-of (vals (:sim/placed sim))))))
    (is (not-any? stairs (keys (:sim/placed sim))) "nothing placed on the staircase")
    (is (empty? (:sim/violations sim)))))

(deftest at-the-rows-depth-a-tunnel-finds-the-ore-its-walls-show
  ;; live iron-kit runs waited at the depth with nothing in sight until the plan failed: a
  ;; staircase shows little rock. Here the ore is sealed in the hill until a tunnel passes it
  (let [ore-row {:goal/id :mine-test-ore
                 :goal/priority 10
                 :goal/holds {:item/stone_pickaxe 1}
                 :goal/provides {:item/raw_iron 1}
                 :goal/act :mine
                 :goal/blocks [:iron_ore]
                 :goal/depth 74
                 :goal/done? :provided?}
        table (conj (filterv #(not= :mine-iron (:goal/id %)) plan/goals) ; this row in its place
                    ore-row
                    {:goal/id :ore-test
                     :goal/priority 7
                     :goal/target? true
                     :goal/provides {:item/raw_iron 1}
                     :goal/done? :provided?})
        [w sim] (sim/run (game/compose game/step (partial plan/step-over table))
                         (game/init fx/opts)
                         (sim/init
                          {:column (world/column-bytes
                                    (assoc deep-stone [13 74 9] (blocks/default-state :iron_ore)))
                           :spawn [8.5 76.0 8.5]
                           :inventory {36 {:item (get blocks/items :stone_pickaxe) :count 1}}})
                         #(or (plan/done? %) (plan/failed? %))
                         240000
                         {:event/kind :go :go/goals [:ore-test]})]
    (is (= :done (:plan/status w)) (pr-str (plan/summary w)))
    (is (= 1 (get (recipe/counts (:player/inventory w)) :raw_iron 0)))
    (is (some #(= :tunnel (:intention/kind %)) (memory/intentions w)) "a tunnel showed it")
    (is (empty? (:sim/violations sim)))))

(deftest boxed-in-by-lava-on-every-side-fails-boxed
  ;; lava under every first stair, and beside the feet on all four sides, so no level step is
  ;; allowed either (lava under the stairs alone is now stepped around)
  (let [lava (first (blocks/states-where (fn [[n]] (= n :lava))))
        blocks (reduce (fn [b [x z]] (assoc b [x 74 z] lava [x 76 z] lava))
                       deep-stone
                       [[9 8] [7 8] [8 9] [8 7]])
        [w sim] (stairs-run blocks 76 (get blocks/items :wooden_pickaxe) 70 60000)]
    (is (= :failed (:intent/status (:plan/intent w))))
    (is (= :boxed (:intent/reason (:plan/intent w))))
    (is (empty? (:sim/broken sim)) "nothing was dug at all")))

;; ---------------------------------------------------------------- cobblestone, on the model

(def forest-over-stone
  "A tree beside the spawn and stone from y 60 to 63 under a dirt floor at 64, with one face of
   stone exposed in a pit at x 12: the cobblestone goal either walks to the pit or cuts stairs."
  (merge (into {} (for [x (range 16) y (range 60 64) z (range 16)] [[x y z] 1])) ; stone
         (into {} (for [x (range 16) z (range 16)] [[x 64 z] 10])) ; dirt on top (10 = dirt)
         {[12 64 8] 0 [12 63 8] 0} ; a pit exposing stone at [12 62 8] and its walls
         {[3 65 2] 136 [3 66 2] 136 [3 67 2] 136 [3 68 2] 252}))                         ; a tree

(deftest three-cobblestone-with-a-wooden-pickaxe-and-it-is-kept
  (let [[w sim] (sim/run step
                         (game/init fx/opts)
                         (sim/init {:column (world/column-bytes forest-over-stone)
                                    :spawn [8.5 65.0 8.5]
                                    :inventory {36 {:item (get blocks/items :wooden_pickaxe)
                                                    :count 1}}})
                         #(or (plan/done? %) (plan/failed? %))
                         240000
                         {:event/kind :go :go/goals [:cobblestone]})]
    (is (plan/done? w) (pr-str (plan/summary w)))
    (is (>= (get (held w) :cobblestone 0) 3))
    (is (= 1 (get (held w) :wooden_pickaxe 0)) "the pickaxe is still held")
    (is (not-any? #(= :failed (:intention/event %)) (memory/intentions w))
        "every collect ends on its cobblestone, none times out")
    (is (< (:time/now w) 20000)
        "three cobblestone in well under the 30 s three collect timeouts used to cost")
    (is (empty? (:sim/violations sim)))))

(deftest a-stone-pickaxe-from-a-wooden-one-sticks-and-a-table
  (let [[w sim] (sim/run step
                         (game/init fx/opts)
                         (sim/init {:column (world/column-bytes forest-over-stone)
                                    :spawn [8.5 65.0 8.5]
                                    :inventory
                                    {36 {:item (get blocks/items :wooden_pickaxe) :count 1}
                                     37 {:item (get blocks/items :stick) :count 2}
                                     38 {:item (get blocks/items :crafting_table) :count 1}}})
                         #(or (plan/done? %) (plan/failed? %))
                         240000
                         {:event/kind :go :go/goals [:stone-pickaxe]})]
    (is (plan/done? w) (pr-str (plan/summary w)))
    (is (= 1 (get (held w) :stone_pickaxe 0)))
    (is (not-any? #(= :failed (:intention/event %)) (memory/intentions w))
        (pr-str (filter #(= :failed (:intention/event %)) (memory/intentions w))))
    (is (empty? (:sim/violations sim)))))

(deftest a-held-furnace-is-placed-and-remembered-near
  (let [table (conj plan/goals
                    {:goal/id :furnace-near
                     :goal/priority 7
                     :goal/target? true
                     :goal/provides {:block/furnace :near}
                     :goal/done? :provided?})
        [w sim] (sim/run (game/compose game/step (partial plan/step-over table))
                         (game/init fx/opts)
                         (sim/init {:column (world/column-bytes {})
                                    :spawn [8.5 64.0 8.5]
                                    :inventory {36 {:item (get blocks/items :furnace) :count 1}}})
                         #(or (plan/done? %) (plan/failed? %))
                         30000
                         {:event/kind :go :go/goals [:furnace-near]})]
    (is (plan/done? w) (pr-str (plan/summary w)))
    (is (some? (make/near w :furnace)) "the placed furnace is remembered near")
    (is (= [:furnace] (map blocks/name-of (vals (:sim/placed sim)))) "the server has it")
    (is (zero? (get (held w) :furnace 0)) "and it left the hand")
    (is (empty? (:sim/violations sim)))))

;; ---------------------------------------------------------------- the furnace, on the model

(def furnace-pos [2 64 0])
(defn item [n] (get blocks/items n))

(defn furnace-sim
  "A model in play with a placed furnace at furnace-pos and inv as window 0's slots."
  [inv]
  (-> (sim/init {:column (world/column-bytes {}) :spawn [0.5 64.0 0.5] :inventory inv})
      (assoc :sim/phase :play)
      (assoc-in [:sim/placed furnace-pos] (blocks/default-state :furnace))))

(defn client-sends
  "The model after the client sends pkt."
  [sim pkt]
  (sim/step sim {:sim/kind :packet :sim/packet pkt}))

(defn open-furnace
  "The model after the client right-clicks the furnace."
  [sim]
  (client-sends sim
                {:packet/name :use-item-on
                 :hand 0
                 :pos furnace-pos
                 :face 1
                 :cursor-x 0.5
                 :cursor-y 1.0
                 :cursor-z 0.5
                 :inside-block false
                 :world-border-hit false
                 :sequence 1}))

(defn click-in
  "The model after one click into its open window, at the window's current state id."
  [sim slot button mode]
  (client-sends sim
                {:packet/name :container-click
                 :window-id (get-in sim [:sim/window :id])
                 :state-id (get-in sim [:sim/window :state-id])
                 :slot slot
                 :button button
                 :mode mode
                 :changed []
                 :cursor nil}))

(defn game-ticks
  "The model after n game ticks, 50 ms apart."
  [sim n]
  (reduce (fn [sim k] (sim/step sim {:sim/kind :tick :sim/now (+ (:sim/now sim) (* 50 k))}))
          sim
          (repeat n 1)))

(defn furnace [sim] (get-in sim [:sim/furnaces furnace-pos]))

(defn loaded
  "A furnace opened with input in hotbar slot 0 and fuel in hotbar slot 1 (furnace window slots
   30 and 31), the fuel then the input laid in with left clicks."
  [input fuel]
  (-> (furnace-sim {36 input 37 fuel})
      open-furnace
      (click-in 31 0 0)
      (click-in 1 0 0)
      (click-in 30 0 0)
      (click-in 0 0 0)))

(deftest a-furnace-opens-with-its-slots-and-properties
  (let [sim (open-furnace (furnace-sim {36 {:item (item :raw_iron) :count 4}}))
        out (:sim/out sim)]
    (is (= [:block-changed-ack :open-screen :container-set-content :container-set-data
            :container-set-data :container-set-data :container-set-data]
           (mapv :packet/name out)))
    (is (= (blocks/menus :furnace) (:menu-type (second out))))
    (is (= 39 (count (:items (nth out 2)))) "three of its own, then the player's 36")
    (is (= {:item (item :raw_iron) :count 4} (get (:items (nth out 2)) 30)) "hotbar 0 at 30")
    (is (= #{0 1 2 3} (set (map :property (drop 3 out)))))))

(deftest four-raw-iron-cook-on-one-coal-in-200-ticks-each
  (let [sim (loaded {:item (item :raw_iron) :count 4} {:item (item :coal) :count 1})
        sim (assoc sim :sim/out [])]
    (is (= {0 {:item (item :raw_iron) :count 4} 1 {:item (item :coal) :count 1}}
           (:slots (furnace sim)))
        "four clicks put the fuel and the input in")
    (is (= 3 (get-in (furnace (game-ticks sim 799)) [:slots 2 :count])) "three at tick 799")
    (let [done (game-ticks sim 800)
          slot-2 (last (filter #(and (= :container-set-slot (:packet/name %)) (= 2 (:slot %)))
                               (:sim/out done)))]
      (is (= {2 {:item (item :iron_ingot) :count 4}} (:slots (furnace done)))
          "four ingots at tick 800, the coal burned, the input gone")
      (is (= {0 801 1 1600 2 0 3 200} (sim/furnace-data (furnace done)))
          "one coal lit at the first tick and burns on")
      (is (= {:item (item :iron_ingot) :count 4} (:item slot-2)) "the client was told")
      (let [taken (-> done
                      (click-in 2 0 1)
                      (client-sends {:packet/name :container-close
                                     :window-id (get-in done [:sim/window :id])}))]
        (is (= 4
               (:count (some #(when (= (item :iron_ingot) (:item %)) %) (vals (:sim/inv taken))))))
        (is (empty? (:slots (furnace taken))))
        (is (nil? (:sim/window taken)))
        (is (empty? (:sim/violations taken)))))))

(deftest a-plank-runs-dry-after-one-ingot-and-the-progress-unwinds
  (let [sim (loaded {:item (item :raw_iron) :count 3} {:item (item :oak_planks) :count 1})]
    (is (= 300 (blocks/fuel-by-name :oak_planks)))
    (let [at-300 (furnace (game-ticks sim 300))]
      (is (= 1 (get-in at-300 [:slots 2 :count])) "one ingot from 300 ticks of fuel")
      (is (= [1 100] [(:lit at-300) (:cook at-300)]) "the second halfway"))
    (is (= [0 98] ((juxt :lit :cook) (furnace (game-ticks sim 301))))
        "out: progress unwinds by two")
    (is (= 0 (:cook (furnace (game-ticks sim 350)))))
    (is (= {:item (item :raw_iron) :count 2} (get-in (furnace (game-ticks sim 350)) [:slots 0]))
        "the rest waits for fuel")))

(deftest a-furnace-cooks-with-its-window-closed
  (let [sim (-> (loaded {:item (item :raw_iron) :count 1} {:item (item :coal) :count 1})
                (client-sends {:packet/name :container-close :window-id 1})
                (game-ticks 200)
                (assoc :sim/out [])
                open-furnace)
        content (some #(when (= :container-set-content (:packet/name %)) %) (:sim/out sim))]
    (is (= {:item (item :iron_ingot) :count 1} (get (:items content) 2))
        "opened again, the output holds what cooked meanwhile")))

(deftest the-furnace-click-rules
  (let [f (inventory/layouts :furnace)
        ingot {:item (item :iron_ingot) :count 3}
        ore {:item (item :raw_iron) :count 2}
        coal {:item (item :coal) :count 1}]
    (is (= :left (sim/click-kind f {:slot 0 :button 0 :mode 0})) "slot 0 is the input, no result")
    (is (= {:view {} :cursor coal} (sim/click-view f {} coal {:slot 2 :button 0 :mode 0}))
        "nothing is put into the output")
    (is (= {:view {} :cursor ore} (sim/click-view f {} ore {:slot 1 :button 1 :mode 0}))
        "the fuel slot refuses what does not burn")
    (is (= {:view {1 coal} :cursor nil} (sim/click-view f {} coal {:slot 1 :button 0 :mode 0})))
    (is (= {:view {} :cursor {:item (item :iron_ingot) :count 5}}
           (sim/click-view f {2 ingot} (assoc ingot :count 2) {:slot 2 :button 0 :mode 0}))
        "a cursor of the output's item takes the output onto it")
    (is (= {:view {30 ore} :cursor nil} (sim/click-view f {30 ore} nil {:slot 1 :button 0 :mode 2}))
        "a hotbar swap cannot put ore into the fuel slot")
    (is (= {:view {3 ore} :cursor nil} (sim/click-view f {0 ore} nil {:slot 0 :button 0 :mode 1}))
        "a shift-click on the input moves it into the store")))

(defn ingots-target
  "A target of n iron ingots, over the shipped goal table."
  [n]
  (conj plan/goals
        {:goal/id :ingots-test
         :goal/priority 7
         :goal/target? true
         :goal/provides {:item/iron_ingot n}
         :goal/done? :provided?}))

(defn smelt-run
  "The bot with inv (window-0 slots) on bare ground, after n iron ingots, and the clicks the
   server loses."
  [inv n drop-clicks]
  (sim/run
   (game/compose game/step (partial plan/step-over (ingots-target n)))
   (game/init fx/opts)
   (sim/init
    {:column (world/column-bytes {}) :spawn [8.5 64.0 8.5] :inventory inv :drop-clicks drop-clicks})
   #(or (plan/done? %) (plan/failed? %))
   120000
   {:event/kind :go :go/goals [:ingots-test]}))

(def furnace-and-iron
  {36 {:item (item :furnace) :count 1}
   37 {:item (item :raw_iron) :count 4}
   38 {:item (item :coal) :count 1}})

(deftest four-raw-iron-and-a-coal-become-four-ingots-in-a-placed-furnace
  (let [[w sim] (smelt-run furnace-and-iron 4 #{})]
    (is (plan/done? w) (pr-str (plan/summary w)))
    (is (= {:iron_ingot 4} (held w)) "the coal burned, the iron cooked, the furnace was placed")
    (is (not-any? #(= :failed (:intention/event %)) (memory/intentions w)))
    (is (nil? (:window/open w)))
    (is (nil? (:sim/window sim)) "closed on both sides")
    (is (every? (comp empty? :slots) (vals (:sim/furnaces sim))) "nothing left in the furnace")
    (is (< (:time/now w) 60000) "800 ticks of cooking and the walking around them")
    (is (empty? (:sim/violations sim)))))

(deftest a-second-stack-of-fuel-is-loaded-when-the-first-burns-out
  ;; birch before oak in burn-order: one birch plank cooks one and a half, then the oak one
  (let [[w sim] (smelt-run {36 {:item (item :furnace) :count 1}
                            37 {:item (item :raw_iron) :count 2}
                            38 {:item (item :birch_planks) :count 1}
                            39 {:item (item :oak_planks) :count 1}}
                           2
                           #{})]
    (is (plan/done? w) (pr-str (plan/summary w)))
    (is (= {:iron_ingot 2} (held w)) "both planks burned, both iron cooked")
    (is (not-any? #(= :failed (:intention/event %)) (memory/intentions w)))
    (is (empty? (:sim/violations sim)))))

(deftest every-lost-smelt-click-is-recovered-from-the-servers-slots
  ;; clicks 0-4 are the smelt's: coal up, coal down, iron up, iron down, the take
  (doseq [n (range 6)]
    (let [[w sim] (smelt-run furnace-and-iron 4 #{n})]
      (is (plan/done? w) (str "lost click " n ": " (pr-str (plan/summary w))))
      (is (= {:iron_ingot 4} (held w)) (str "lost click " n))
      (is (empty? (:sim/violations sim)) (str "lost click " n)))))

(def ore-field
  "Coal and two iron ores standing on the stone floor around the spawn, each with a cell beside
   it at its own level."
  {[11 64 8] (blocks/default-state :iron_ore)
   [11 64 10] (blocks/default-state :iron_ore)
   [8 64 12] (blocks/default-state :coal_ore)})

(deftest coal-and-iron-are-mined-where-they-lie
  (let [table (conj plan/goals
                    {:goal/id :ore-test
                     :goal/priority 7
                     :goal/target? true
                     :goal/provides {:item/raw_iron 2 :item/coal 1}
                     :goal/done? :provided?})
        [w sim] (sim/run (game/compose game/step (partial plan/step-over table))
                         (game/init fx/opts)
                         (sim/init {:column (world/column-bytes ore-field)
                                    :spawn [8.5 64.0 8.5]
                                    :inventory {36 {:item (item :stone_pickaxe) :count 1}}})
                         #(or (plan/done? %) (plan/failed? %))
                         120000
                         {:event/kind :go :go/goals [:ore-test]})]
    (is (plan/done? w) (pr-str (plan/summary w)))
    (is (= {:raw_iron 2 :coal 1 :stone_pickaxe 1} (held w)))
    (is (= #{[11 64 8] [11 64 10] [8 64 12]} (:sim/broken sim)) "the three ores, nothing else")
    (is (not-any? #(= :failed (:intention/event %)) (memory/intentions w)))
    (is (empty? (:sim/violations sim)))))

(def ore-corridor
  "A tunnel two high at y 64-65 along x from 2 to 12 at z 8, in stone up to y 70, with iron ore
   in its wall at the head (x 5) and in its ceiling (x 9)."
  (merge (into {} (for [x (range 16) y (range 64 71) z (range 16)] [[x y z] 1]))
         (into {} (for [x (range 2 13) y [64 65]] [[x y 8] blocks/air]))
         {[5 65 9] (blocks/default-state :iron_ore) [9 66 8] (blocks/default-state :iron_ore)}))

(deftest ore-at-the-head-and-overhead-is-mined-from-the-tunnel
  ;; batch iron-a2 run 4 tunnelled over a hundred blocks at y 16 seeing only the walls at its feet
  (let [table (conj plan/goals
                    {:goal/id :ore-test
                     :goal/priority 7
                     :goal/target? true
                     :goal/provides {:item/raw_iron 2}
                     :goal/done? :provided?})
        [w sim] (sim/run (game/compose game/step (partial plan/step-over table))
                         (game/init fx/opts)
                         (sim/init {:column (world/column-bytes ore-corridor)
                                    :spawn [2.5 64.0 8.5]
                                    :inventory {36 {:item (item :stone_pickaxe) :count 1}}})
                         #(or (plan/done? %) (plan/failed? %))
                         120000
                         {:event/kind :go :go/goals [:ore-test]})]
    (is (plan/done? w) (pr-str (plan/summary w)))
    (is (= 2 (get (held w) :raw_iron 0)))
    (is (= #{[5 65 9] [9 66 8]} (:sim/broken sim)) "the two ores, nothing else")
    (is (not-any? #(= :failed (:intention/event %)) (memory/intentions w)))
    (is (empty? (:sim/violations sim)))))

;; ---------------------------------------------------------------- flint, by the loot table's luck

(def gravel-state (blocks/default-state :gravel))

(deftest gravel-drops-flint-when-its-roll-comes-under-a-tenth
  (let [sim0 (sim/init {:column (world/column-bytes {}) :spawn [0.5 64.0 0.5]})
        rolls (fn [seed n]
                (->> (assoc sim0 :sim/rng seed)
                     (iterate (comp first sim/roll))
                     (map (comp second sim/roll))
                     (take n)))]
    (is (= (rolls 7 20) (rolls 7 20)) "the same seed, the same luck")
    (is (not= (rolls 7 20) (rolls 8 20)))
    (is (every? #(and (<= 0.0 %) (< % 1.0)) (rolls 7 1000)))
    (is (< 70 (count (filter #(< % 0.1) (rolls 7 1000))) 130) "about one in ten under 0.1")
    (doseq [seed (range 200)]
      (let [s (assoc sim0 :sim/rng seed)
            [_ dropped] (sim/loot s gravel-state nil)]
        (is (= (if (< (second (sim/roll s)) 0.1) (item :flint) (item :gravel)) dropped)
            (str "seed " seed))))))

(defn flint-run
  "The bot holding one gravel on bare ground, after one flint, its luck from seed."
  [seed]
  (sim/run (game/compose game/step
                         (partial plan/step-over
                                  (conj plan/goals
                                        {:goal/id :flint-test
                                         :goal/priority 7
                                         :goal/target? true
                                         :goal/provides {:item/flint 1}
                                         :goal/done? :provided?})))
           (game/init fx/opts)
           (sim/init {:column (world/column-bytes {})
                      :spawn [8.5 64.0 8.5]
                      :inventory {36 {:item (item :gravel) :count 1}}
                      :seed seed})
           #(or (plan/done? %) (plan/failed? %))
           600000
           {:event/kind :go :go/goals [:flint-test]}))

(deftest one-gravel-is-placed-and-dug-again-until-it-drops-flint
  ;; the last redig is abandoned :goal-met the tick its flint is held, so count those started
  (let [seed 4
        [w sim] (flint-run seed)
        redigs (count (filter #(and (= :redig (:intention/kind %))
                                    (= :started (:intention/event %)))
                              (memory/intentions w)))
        rolls (->> (assoc (sim/init {:column (world/column-bytes {}) :spawn [0.5 64.0 0.5]})
                          :sim/rng
                          seed)
                   (iterate (comp first sim/roll))
                   (map (comp second sim/roll)))]
    (is (= :done (:plan/status w)) (pr-str (plan/summary w)))
    (is (= {:flint 1} (held w)) "the gravel became flint")
    (is (= (inc (count (take-while #(>= % 0.1) rolls))) redigs)
        "one place, dig and pick-up per roll, up to the first under a tenth")
    (is (= 5 redigs) "this seed rolls gravel four times first")
    (is (not-any? #(= :failed (:intention/event %)) (memory/intentions w)))
    (is (empty? (:sim/violations sim)))
    (is (= (:time/now w) (:time/now (first (flint-run seed)))) "the same seed, the same run")))

;; ---------------------------------------------------------------- gravel and sand fall

(deftest gravel-over-a-broken-block-falls-into-it
  ;; FallingBlock in the 26.1.2 jar: two ticks after the block under it changed, gravel with a
  ;; free cell under it falls, a column one block at a time, and lands where
  ;; FallingBlockEntity's gravity brings it
  (let [broken
        (-> (sim/init {:column (world/column-bytes
                                {[1 64 0] stone-id [1 65 0] gravel-state [1 66 0] gravel-state})
                       :spawn [0.5 64.0 0.5]
                       :inventory {36 {:item (item :wooden_pickaxe) :count 1}}})
            (assoc :sim/phase :play :sim/now 1000 :sim/player-pos [0.5 64.0 0.5])
            (client-sends {:packet/name :set-carried-item :slot 0})
            (client-sends {:packet/name :player-action :status 0 :pos [1 64 0] :face 4 :sequence 1})
            (sim/step {:sim/kind :tick :sim/now 2200})
            (client-sends
             {:packet/name :player-action :status 2 :pos [1 64 0] :face 4 :sequence 2}))
        at #(mapv (fn [y] (blocks/name-of (sim/block-at %1 [1 y 0]))) [64 65 66])]
    (is (= [:air :gravel :gravel] (at broken)) "the stone is gone")
    (is (= [:air :gravel :gravel] (at (game-ticks broken 1))) "nothing falls in the first tick")
    (is (= [:air :air :gravel] (at (game-ticks broken 2))) "the lower gravel is in the air")
    (is (= [:air :air :air] (at (game-ticks broken 4))) "the upper one two ticks after it")
    (is (= [:air :air :air] (at (game-ticks broken 7))) "both still falling")
    (is (= [:gravel :air :air] (at (game-ticks broken 8))) "one block down in seven ticks")
    (is (= [:gravel :gravel :air] (at (game-ticks broken 20)))
        "the upper one lands on the lower, not in its cell")
    (is (every? #{:gravel :air}
                (for [p (filter #(= :block-update (:packet/name %))
                                (:sim/out (game-ticks broken 20)))
                      :when (not= [1 64 0] (:pos p))]
                  (blocks/name-of (:state p))))
        "the client is told each cell it leaves and lands in")))

(def gravel-cliff
  "deep-stone with gravel four high on it from x 9 east: every stair cut east opens cells with
   gravel over them."
  (merge deep-stone
         (into {} (for [x (range 9 16) y (range 76 80) z (range 16)] [[x y z] gravel-state]))))

(deftest gravel-that-refills-a-stair-is-dug-again
  ;; live iron-kit run 7 (local landing 3, y 37-40): gravel over the staircase fell into the cells
  ;; it had opened; the walk onto the stair failed :stair-not-taken, then two digs found their
  ;; cell already emptied and failed :target-gone
  (let [[w sim] (stairs-run gravel-cliff 76 (get blocks/items :wooden_pickaxe) 74 180000)]
    (is (= :done (:intent/status (:plan/intent w)))
        (pr-str (select-keys (:plan/intent w)
                             [:intent/status :intent/reason :intent/stage :intent/turns])))
    (is (<= (second (:player/pos w)) 74.5) "two stairs down, through the gravel")
    (is (empty? (:sim/falling sim)) "nothing left in the air")
    (is (empty? (:sim/violations sim)))))

;; ---------------------------------------------------------------- out of the ground

(def pocket
  "deep-stone with a pocket two high at [8 66 8] to stand in, nine blocks under the hilltop."
  (assoc deep-stone [8 66 8] blocks/air [8 67 8] blocks/air))

(deftest stairs-up-cut-a-way-out-of-the-rock
  ;; batch iron-a2 runs 2 and 5 waited :no-water at y 18 and 24: the stairs they came down by had
  ;; lost a floor to an ore or a niche, and no route led back up
  (let [[w sim] (staircase-run :stairs-up pocket 66 (get blocks/items :wooden_pickaxe) 72 120000)]
    (is (= :done (:intent/status (:plan/intent w)))
        (pr-str (select-keys (:plan/intent w)
                             [:intent/status :intent/reason :intent/stage :intent/turns])))
    (is (<= 72.0 (second (:player/pos w))) "six stairs up")
    (is (:player/on-ground? w))
    (is (empty? (:sim/violations sim)))))

(deftest gravel-over-the-head-is-never-dug-under
  ;; a stair up opens the cell over the head first; gravel over that cell would fall through it
  ;; onto the bot, so every stair from here is refused and a level step leads out from under it
  (let [blocks (assoc pocket [8 69 8] gravel-state)
        [w sim] (staircase-run :stairs-up blocks 66 (get blocks/items :wooden_pickaxe) 70 120000)]
    (is (= :done (:intent/status (:plan/intent w)))
        (pr-str (select-keys (:plan/intent w)
                             [:intent/status :intent/reason :intent/stage :intent/level-steps])))
    (is (<= 70.0 (second (:player/pos w))))
    (is (not (contains? (:sim/broken sim) [8 68 8])) "the cell under the gravel was never opened")
    (is (= :gravel (blocks/name-of (sim/block-at sim [8 69 8]))) "the gravel never fell")
    (is (empty? (:sim/violations sim)))))

;; ---------------------------------------------------------------- a bucket of water

(def bank-pond
  "A pond of nine water sources sunk in the floor at x 11-13, z 7-9; the spawn is on the bank."
  (into {} (for [x (range 11 14) z (range 7 10)] [[x 63 z] blocks/water-base])))

(deftest an-empty-bucket-is-filled-at-a-pond-from-its-bank
  (let [[w sim] (sim/run (game/compose game/step
                                       (partial plan/step-over
                                                (conj plan/goals
                                                      {:goal/id :bucket-test
                                                       :goal/priority 7
                                                       :goal/target? true
                                                       :goal/provides {:item/water_bucket 1}
                                                       :goal/done? :provided?})))
                         (game/init fx/opts)
                         (sim/init {:column (world/column-bytes bank-pond)
                                    :spawn [5.5 64.0 8.5]
                                    :inventory {40 {:item (item :bucket) :count 1}}})
                         ;; a step into the pond ends the run, so done means never in it
                         #(or (plan/done? %) (plan/failed? %) (:player/in-water? %))
                         60000
                         {:event/kind :go :go/goals [:bucket-test]})]
    (is (= :done (:plan/status w)) (pr-str (plan/summary w)))
    (is (= {:water_bucket 1} (held w)) "the bucket in hotbar slot 4 was selected and filled")
    (is (= 1 (count (filter #(= blocks/air %) (vals (:sim/placed sim))))) "one source taken")
    (is (not (:player/in-water? w)) "never in the pond")
    (is (not-any? #(= :failed (:intention/event %)) (memory/intentions w)))
    (is (empty? (:sim/violations sim)))))

;; ---------------------------------------------------------------- the iron kit, end to end

(def kit-column
  "Everything the iron kit is made from, on one stone floor: two coal ores, four iron ores and a
   gravel standing on it, each with a cell beside it at its own level, and a pond of nine water
   sources sunk in it, open to the sky."
  (merge {[3 64 3] (blocks/default-state :coal_ore)
          [5 64 3] (blocks/default-state :coal_ore)
          [3 64 12] (blocks/default-state :iron_ore)
          [5 64 12] (blocks/default-state :iron_ore)
          [7 64 12] (blocks/default-state :iron_ore)
          [9 64 12] (blocks/default-state :iron_ore)
          [12 64 3] (blocks/default-state :gravel)}
         (into {} (for [x (range 11 14) z (range 10 13)] [[x 63 z] blocks/water-base]))))

(def kit-start
  "What the kit starts from: the stone pickaxe, a crafting table, the furnace's eight
   cobblestone and four logs."
  {36 {:item (item :stone_pickaxe) :count 1}
   37 {:item (item :crafting_table) :count 1}
   9 {:item (item :cobblestone) :count 8}
   10 {:item (item :oak_log) :count 4}})

(defn recording
  "step, with every event written to tap before it is applied and only the effects that event
   added written after (sim/run clears effects once a tick, after several events)."
  [step tap]
  (fn [w e]
    ((:write tap) e)
    (let [w' (step w e)]
      ((:effects tap) (subvec (:bot/effects w') (count (:bot/effects w))))
      w')))

(deftest the-iron-kit-from-a-stone-pickaxe-and-its-recording-replays
  (let [path (str (System/getProperty "java.io.tmpdir") "/clojurecraft-iron-kit.edn")
        tap (record/tap! path)
        [w sim] (sim/run (recording step tap)
                         (game/init fx/opts)
                         (sim/init {:column (world/column-bytes kit-column)
                                    :spawn [8.5 64.0 8.5]
                                    :inventory kit-start
                                    :seed 4})
                         #(or (plan/done? %) (plan/failed? %))
                         900000
                         {:event/kind :go :go/goals [:iron-kit]})]
    ((:close tap))
    (is (= :done (:plan/status w)) (pr-str (plan/summary w)))
    (is (= 1 (:water_bucket (held w))))
    (is (= 1 (:flint_and_steel (held w))))
    (is (not-any? #(= :failed (:intention/event %)) (memory/intentions w)))
    (is (empty? (:sim/violations sim)))
    (is (< (:time/now w) 600000) "under ten simulated minutes")
    (testing "the recording replays: every packet the bot sent, sent again, and the same world"
      (is (nil? (record/verify step (game/init fx/opts) path)))
      (let [replayed (record/replay step (game/init fx/opts) path)]
        (is (= (:player/inventory w) (:player/inventory replayed)))
        (is (= (:time/now w) (:time/now replayed)))
        (is (= (memory/intentions w) (memory/intentions replayed)))))))

(def walled-ledge
  "Gym batch push70 (landing 18648,18464): the nearest stone's only cell at its own level was
   walled in, so no route reached it, while a cell one block below it was reachable. Here: stone
   S at [10 65 8] on [10 64 8], its one standable side cell [11 65 8] closed in by two-high walls,
   and the open floor at y 64 west of it."
  (merge {[10 64 8] 1 [10 65 8] 1 [10 66 8] 1 [11 64 8] 1}
         (into {} (for [y [65 66] c [[12 y 8] [11 y 7] [11 y 9]]] [c 1]))))

(deftest a-stone-is-dug-from-wherever-its-drop-lands-in-reach
  (let [[w sim] (sim/run step
                         (game/init fx/opts)
                         (sim/init {:column (world/column-bytes walled-ledge)
                                    :spawn [5.5 64.0 8.5]
                                    :inventory {36 {:item (get blocks/items :wooden_pickaxe)
                                                    :count 1}}})
                         #(or (plan/done? %) (plan/failed? %))
                         240000
                         {:event/kind :go :go/goals [:cobblestone]})]
    (is (plan/done? w) (pr-str (plan/summary w)))
    (is (not-any? #(= :failed (:intention/event %)) (memory/intentions w))
        (pr-str (filter #(= :failed (:intention/event %)) (memory/intentions w))))
    (is (empty? (:sim/violations sim)))))

(deftest bare-handed-the-cobblestone-goal-never-digs-stone
  ;; with no pickaxe the needs planner plans for one (the tree beside the spawn gives it logs);
  ;; what must never happen is a hand dig of stone, which drops cobblestone at 2300 ms a block
  ;; but would cost a race its tool chain. The run ends when a pickaxe is in hand, or the plan
  ;; gives up (:no-log once the one tree is spent)
  (let [[w sim] (sim/run
                 step
                 (game/init fx/opts)
                 (sim/init {:column (world/column-bytes forest-over-stone) :spawn [8.5 65.0 8.5]})
                 #(or (plan/done? %) (plan/failed? %) (pos? (get (held %) :wooden_pickaxe 0)))
                 120000
                 {:event/kind :go :go/goals [:cobblestone]})]
    (is (not-any? #(= 1 (sim/block-at (assoc sim :sim/broken #{}) %)) (:sim/broken sim))
        "no stone was dug by hand")
    (is (or (pos? (get (held w) :wooden_pickaxe 0)) (plan/failed? w)) (pr-str (plan/summary w)))))

;; ---------------------------------------------------------------- death and respawn (issue #5)

(def well
  "A one-wide well of water seven deep at the spawn column, stone all round: a bot dropped in
   sinks to the bottom and its eye never clears the surface."
  (into {} (for [y (range 58 65)] [[0 y 0] 86])))

(deftest held-under-water-the-bot-drowns-and-respawns-with-no-intent
  ;; issue #5: before this the bot could not tell it was in water, set-health only recorded a
  ;; number, and nothing handled death; now the model drowns it and it asks to respawn
  (let [[w sim] (sim/run
                 step
                 (game/init fx/opts)
                 (sim/init
                  {:column (world/column-bytes well) :spawn [0.5 64.0 0.5] :respawn [2.5 64.0 0.5]})
                 #(and (pos? (:stats/deaths % 0)) (not (:player/dead? %)) (:player/on-ground? %))
                 40000
                 {:event/kind :go :go/goals []})]
    (is (= 1 (:stats/deaths w)) "died once")
    (is (not (:player/dead? w)) "and was respawned")
    (is (nil? (:plan/intent w)))
    (is (= game/max-air (:player/air w)) "lungs full after the respawn")
    (is (= 20.0 (:sim/health sim)))
    (is (= [:death] (map :answer/kind (memory/answers w :death))) "the death is a fact")
    (is (= 1 (:sim/respawns sim)) "the bot asked to respawn, once")
    (is (= 2.5 (first (:player/pos w))) "on the world spawn, beside the well")
    (is (empty? (:sim/violations sim)))))

;; ---------------------------------------------------------------- escaping water (issue #5)

(defn escape-run
  "Run the bot for :wood (a goal it can only wait on: there is no tree) from a spawn inside
   blocks, until a trap has been escaped, the plan fails, the bot dies or max-ms pass. Returns
   [world sim lowest-air]."
  [blocks spawn max-ms & [opts]]
  (let [low (atom game/max-air)
        watched (fn [w e]
                  (let [w (step w e)]
                    (swap! low min (:player/air w game/max-air))
                    w))
        [w sim] (sim/run watched
                         (game/init fx/opts)
                         (sim/init (merge {:column (world/column-bytes blocks) :spawn spawn} opts))
                         #(or (seq (:world/traps %)) (plan/failed? %) (pos? (:stats/deaths % 0)))
                         max-ms
                         {:event/kind :go :go/goals [:wood]})]
    [w sim @low]))

(def pond
  "A pond six by six and two deep (y 62-63 water, the floor at 61) in a plain at y 64; the
   nearest bank is three blocks from the spawn at its middle."
  (into {} (for [x (range 0 6) y [62 63] z (range 0 6)] [[x y z] blocks/water-base])))

(deftest dropped-in-a-pond-the-bot-swims-out-onto-the-bank
  ;; issue #5 acceptance: a bank three blocks away is reached within 10 s of sim time and the
  ;; air never falls below 200
  (let [[w sim low] (escape-run pond [2.5 62.0 2.5] 30000)]
    (is (= [:water] (map :trap/kind (:world/traps w))) (pr-str (plan/summary w)))
    (is (zero? (:stats/deaths w 0)))
    (is (nil? (:plan/trap w)))
    (is (trap/dry-land? w))
    (is (< (:time/now w) 10000) "out within 10 s")
    (is (>= low 200) (str "lowest air " low))
    (is (some #(= :swim-out (:intention/kind %)) (memory/intentions w)))
    (is (empty? (:sim/violations sim)))))

(def roofed-pool
  "ruststeve's water_roofed: a 5x5 pool six deep (y 23-28) under solid stone, the only air a
   pocket one block west of the pool and one down from its top (y 27-28 at x 0), with a floor."
  (merge (into {} (for [x (range 1 6) y (range 23 29) z (range 0 5)] [[x y z] blocks/water-base]))
         {[0 27 2] 0 [0 28 2] 0}))

(deftest a-roofed-pool-is-left-through-its-side-pocket
  ;; rust-race-003 drowned here: every escape move went up into stone while air was one block
  ;; west and one down
  (let [[w sim low] (escape-run roofed-pool [3.5 23.0 2.5] 90000)]
    (is (= [:water] (map :trap/kind (:world/traps w))) (pr-str (plan/summary w)))
    (is (zero? (:stats/deaths w 0)))
    (is (trap/dry-land? w))
    (is (= [0 27 2] (path/feet-cell (:player/pos w))) "standing in the pocket")
    (is (pos? low))
    (is (empty? (:sim/violations sim)))))

(def capped-well
  "A one-wide well of water four deep (y 60-63) in the ground under a stone cap at y 64, the
   sky above it: no bank in reach under the cap, the only way out is through it."
  (assoc (into {} (for [y (range 60 64)] [[0 y 0] blocks/water-base])) [0 64 0] 1))

(deftest a-capped-well-is-dug-out-through-its-cap-with-the-pickaxe
  ;; the eyes-in-water and off-ground penalties make the cap 25x slower than on land; without
  ;; them the siblings' capped-cave escape "dug" its cap forever
  (let [[w sim _] (escape-run capped-well
                              [0.5 60.0 0.5]
                              90000
                              {:inventory {36 {:item wooden-pickaxe :count 1 :durability 1}}})]
    (is (= [:water] (map :trap/kind (:world/traps w))) (pr-str (plan/summary w)))
    (is (zero? (:stats/deaths w 0)))
    (is (trap/dry-land? w))
    (is (>= (second (:player/pos w)) 64.0) "up on the surface")
    (is (= 0 (terrain/block-at w [0 64 0])) "the cap is gone")
    (is (empty? (:sim/violations sim)))))

(def shore
  "A plain at y 64 with a pond six by six and two deep eight blocks east of the spawn: the
   gym's shore trial, in the model."
  (into {} (for [x (range 8 14) y [62 63] z (range 0 6)] [[x y z] blocks/water-base])))

(deftest the-shore-goal-wades-in-and-comes-out
  ;; the :shore target: a wade into the nearest remembered pool until the eye is under, the
  ;; escape it provokes, then done, with RESULT saying :escaped
  (let [[w sim] (sim/run step
                         (game/init fx/opts)
                         (sim/init {:column (world/column-bytes shore) :spawn [2.5 64.0 2.5]})
                         #(or (plan/done? %) (plan/failed? %))
                         60000
                         {:event/kind :go :go/goals [:shore]})]
    (is (plan/done? w) (pr-str (plan/summary w)))
    (is (zero? (:stats/deaths w 0)))
    (is (trap/dry-land? w))
    (is (= [:water] (map :trap/kind (:world/traps w))))
    (is (= [:wade :swim-out]
           (map :intention/kind (filter #(= :started (:intention/event %)) (memory/intentions w)))))
    (is (true? (:escaped (main/result w "shore" true))))
    (is (main/ok? w "shore"))
    (is (empty? (:sim/violations sim)))))

(def beach
  "A dune sloping down one block a step from y 68 at x 0 to the sand at y 64 by x 4, a
   one-deep shelf at x 8-9 (water at y 63 only) and the sea three deep from x 10: the gym's
   beach landings in the model. The pool fact is mid-sea; the entry is the shelf's edge."
  (merge (into {} (for [x (range 0 4) y (range 64 (- 69 x)) z (range 0 6)] [[x y z] 1]))
         (into {} (for [x (range 8 10) z (range 0 6)] [[x 63 z] blocks/water-base]))
         (into {} (for [x (range 10 16) y [61 62 63] z (range 0 6)] [[x y z] blocks/water-base]))))

(deftest a-dune-over-a-shelving-beach-is-waded-from-and-escaped
  ;; gym batch shore-w1 (set W, 0/10): from a dune the sea was "more than 5 under the eye", a
  ;; mid-sea pool had no route, and a one-deep shelf never put the eye under
  (let [[w sim] (sim/run step
                         (game/init fx/opts)
                         (sim/init {:column (world/column-bytes beach) :spawn [0.5 69.0 2.5]})
                         #(or (plan/done? %) (plan/failed? %))
                         90000
                         {:event/kind :go :go/goals [:shore]})]
    (is (plan/done? w) (pr-str (plan/summary w)))
    (is (zero? (:stats/deaths w 0)))
    (is (trap/dry-land? w))
    (is (= [:water] (map :trap/kind (:world/traps w))))
    (is (empty? (:sim/violations sim)))))
