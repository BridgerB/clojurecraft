(ns clojurecraft.sim-test
  "The whole bot against the pure server model: handshake → spawn → chunk → find → walk → dig →
   drop → pickup → done, with no socket and no Java process."
  (:require [clojure.core.async :as async]
            [clojure.spec.alpha]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.test.check :as tc]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [clojurecraft.blaze :as blaze]
            [clojurecraft.blocks :as blocks]
            [clojurecraft.build :as build]
            [clojurecraft.chunk :as chunk]
            [clojurecraft.dimension :as dimension]
            [clojurecraft.entity :as entity]
            [clojurecraft.eyes]
            [clojurecraft.fixtures :as fx]
            [clojurecraft.fortress :as fortress]
            [clojurecraft.game :as game]
            [clojurecraft.intent :as intent]
            [clojurecraft.inventory :as inventory]
            [clojurecraft.main :as main]
            [clojurecraft.make :as make]
            [clojurecraft.memory :as memory]
            [clojurecraft.mob :as mob]
            [clojurecraft.packet :as packet]
            [clojurecraft.path :as path]
            [clojurecraft.physics :as physics]
            [clojurecraft.plan :as plan]
            [clojurecraft.recipe :as recipe]
            [clojurecraft.record :as record]
            [clojurecraft.scoop :as scoop]
            [clojurecraft.sim :as sim]
            [clojurecraft.stairs :as stairs]
            [clojurecraft.stone]
            [clojurecraft.swim]
            [clojurecraft.terrain :as terrain]
            [clojurecraft.trap :as trap]
            [clojurecraft.wood]
            [clojurecraft.world :as world]))

(use-fixtures :once fx/instrumented)

(def step (game/compose game/step plan/step))

(defn wire
  "A named click (inventory/left-click and the rest) as the container-click packet's fields."
  [{:click/keys [slot button mode]}]
  {:slot slot :button button :mode mode})

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

(deftest a-lost-craft-click-is-resynced-never-a-blind-take
  (let [column (world/column-bytes {[6 64 0] 136 [6 65 0] 136 [6 66 0] 136 [6 67 0] 252})
        [w sim] (sim/run step
                         (game/init fx/opts)
                         (sim/init {:column column :spawn [0.5 64.0 0.5] :drop-clicks #{1}})
                         #(or (plan/done? %) (plan/failed? %))
                         120000
                         {:event/kind :go :go/goals [:kit]})]
    (is (plan/done? w) "it recovers: the craft resyncs, reclaims the grid and lays it again")
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
                           :inventory {[:main 1] {:item (recipe/item-id :oak_planks) :count 3}}})
                (assoc :sim/phase :play :sim/window {:id 1 :kind :table :grid {} :state-id 4})
                (sim/step
                 {:sim/kind :packet
                  :sim/packet
                  (merge
                   {:packet/name :container-click :window-id 1 :state-id 2 :changed [] :cursor nil}
                   (wire (inventory/left-click (inventory/slot-in :table [:main 1]))))}))
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
                                    :inventory {[:hotbar 0] {:item planks :count 4}
                                                [:hotbar 1] {:item stick :count 2}
                                                [:hotbar 2] {:item table :count 1}}})
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
                (sim/step
                 {:sim/kind :packet
                  :sim/packet
                  (merge
                   {:packet/name :container-click :window-id 0 :state-id 1 :changed [] :cursor nil}
                   (wire (inventory/left-click (inventory/slot [:hotbar 0]))))}))]
    (is (= [[:click-inventory-while-open 0]] (:sim/violations sim)))
    (is (= [] (:sim/out sim)) "vanilla ignores it: no answer")))

(deftest an-early-finish-breaks-the-block-only-by-the-servers-clock
  ;; ServerPlayerGameMode's delayed destroy: a FINISH before 70% of the block's time is acked
  ;; alone, the block is kept, and the server breaks it when its own timer completes
  (let [sim0 (assoc (sim/init {:column (world/column-bytes {[1 64 0] 1}) :spawn [0.5 64.0 0.5]})
                    :sim/phase :play
                    :sim/now 1000)
        sim (-> sim0
                (sim/step {:sim/kind :packet
                           :sim/packet {:packet/name :player-action
                                        :status (packet/value :dig :start-destroy)
                                        :pos [1 64 0]
                                        :face (packet/value :face :west)
                                        :sequence 1}})
                (sim/step {:sim/kind :tick :sim/now 2000})
                (sim/step {:sim/kind :packet
                           :sim/packet {:packet/name :player-action
                                        :status (packet/value :dig :stop-destroy)
                                        :pos [1 64 0]
                                        :face (packet/value :face :west)
                                        :sequence 2}}))]
    (is (empty? (:sim/broken sim)))
    (is (= [:block-changed-ack] (mapv :packet/name (:sim/out sim)))
        "the real server acks and keeps breaking by its own clock")
    (let [[sim _] (sim/drain sim)
          later (sim/step sim {:sim/kind :tick :sim/now (+ 1000 (sim/full-ms sim 1 nil))})]
      (is (= #{[1 64 0]} (:sim/broken later)) "broken once the block's whole time has passed")
      (is (= :block-update (:packet/name (first (:sim/out later)))))
      (is (= 0 (:state (first (:sim/out later))))))))

(deftest a-niche-is-placed-into-only-once-the-slow-server-has-broken-it
  ;; race 38477665442 bots Clj_r01 (runner 4) and Clj_r07 (runner 5): the niche's FINISH was
  ;; acked at once and the stone broken two seconds later; the furnace went in a second after
  ;; the ack, the server answered with the stone, three times, :rejected
  (let [pit (merge (into {} (for [x (range 16) y (range 64 80) z (range 16)] [[x y z] 1]))
                   {[8 72 8] 0 [8 73 8] 0})
        table (conj plan/goals
                    {:goal/id :furnace-near
                     :goal/priority 7
                     :goal/target? true
                     :goal/provides {:block/furnace :near}
                     :goal/done? :provided?})
        [w sim] (sim/run (game/compose game/step (partial plan/step-over table))
                         (game/init fx/opts)
                         (sim/init {:column (world/column-bytes pit)
                                    :spawn [8.5 72.0 8.5]
                                    :slow-breaks 2.0
                                    :inventory
                                    {[:hotbar 0] {:item (get blocks/items :furnace) :count 1}
                                     [:hotbar 1] {:item (get blocks/items :wooden_pickaxe)
                                                  :count 1}}})
                         #(or (plan/done? %) (plan/failed? %))
                         60000
                         {:event/kind :go :go/goals [:furnace-near]})]
    (is (plan/done? w) (pr-str (plan/summary w)))
    (is (= [:furnace] (map blocks/name-of (vals (:sim/placed sim)))) "in the niche")
    (is (zero? (:plan/attempts w)) "no placement refused")
    (is (empty? (:sim/violations sim)))))

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
                               :inventory (if item {[:hotbar 0] {:item item :count 1}} {})})
                    :sim/phase :play
                    :sim/now 1000
                    :sim/player-pos [0.5 64.0 0.5])]
    (-> sim0
        (sim/step {:sim/kind :packet :sim/packet {:packet/name :set-carried-item :slot 0}})
        (sim/step {:sim/kind :packet
                   :sim/packet {:packet/name :player-action
                                :status (packet/value :dig :start-destroy)
                                :pos [1 64 0]
                                :face (packet/value :face :west)
                                :sequence 1}})
        (sim/step {:sim/kind :tick :sim/now (+ 1000 wait-ms)})
        (sim/step {:sim/kind :packet
                   :sim/packet {:packet/name :player-action
                                :status (packet/value :dig :stop-destroy)
                                :pos [1 64 0]
                                :face (packet/value :face :west)
                                :sequence 2}}))))

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
    (is (= 58 (get-in sim [:sim/inv (inventory/slot [:hotbar 0]) :durability])) "one use off 59")))

(deftest iron-ore-with-a-wooden-pickaxe-breaks-but-drops-nothing
  (let [sim (dig-on-model iron-ore-id wooden-pickaxe 7500)]
    (is (contains? (:sim/broken sim) [1 64 0]))
    (is (empty? (:sim/items sim)) "the wrong tier: no drop")))

(deftest tools-never-stack-and-wear-one-at-a-time
  ;; issue #36: the model stacked every item to 64, so a spare pickaxe joined the one in use and
  ;; the dig that broke one emptied the slot of both
  (let [slots (map inventory/slot [[:hotbar 0] [:hotbar 1] [:hotbar 2]])
        [view left] (sim/insert {} slots {:item wooden-pickaxe :count 2})]
    (is (= 1 (sim/stack-size wooden-pickaxe)))
    (is (= 64 (sim/stack-size (get blocks/items :cobblestone))))
    (is (nil? left))
    (is (= [1 1] (keep #(:count (get view %)) slots)) "two pickaxes, two slots")
    (let [worn (sim/wear (assoc-in view [(first slots) :durability] 1) (first slots))]
      (is (nil? (get worn (first slots))) "the one worn out is gone")
      (is (= {:item wooden-pickaxe :count 1} (get worn (second slots))) "the spare is not"))))

(deftest a-worn-out-tool-leaves-its-slot
  (let [sim0 (assoc (sim/init {:column (world/column-bytes {[1 64 0] stone-id})
                               :spawn [0.5 64.0 0.5]
                               :inventory {[:hotbar 0]
                                           {:item wooden-pickaxe :count 1 :durability 1}}})
                    :sim/phase :play
                    :sim/now 1000
                    :sim/player-pos [0.5 64.0 0.5])
        sim (-> sim0
                (sim/step {:sim/kind :packet :sim/packet {:packet/name :set-carried-item :slot 0}})
                (sim/step {:sim/kind :packet
                           :sim/packet {:packet/name :player-action
                                        :status (packet/value :dig :start-destroy)
                                        :pos [1 64 0]
                                        :face (packet/value :face :west)
                                        :sequence 1}})
                (sim/step {:sim/kind :tick :sim/now 2200})
                (sim/step {:sim/kind :packet
                           :sim/packet {:packet/name :player-action
                                        :status (packet/value :dig :stop-destroy)
                                        :pos [1 64 0]
                                        :face (packet/value :face :west)
                                        :sequence 2}}))]
    (is (contains? (:sim/broken sim) [1 64 0]))
    (is (nil? (get-in sim [:sim/inv (inventory/slot [:hotbar 0])])) "the last use broke it")
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
   failed on; a branch and a bank above the feet are what a plan found at 21280,18720 (a 2-high
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

(def wood-ms 150000) ; simulated ms a forest run may take: the wood gym row's old cap, kept here when
                     ; the gym's trials went to the job's length (one forest property runs many)

(deftest the-wood-goal-holds-in-generated-forests
  ;; the bot against the model in 2,000 worlds test.check builds (docs/RICH-HICKEY.md: "thousands of
  ;; generated worlds in the time one real run takes"): 1-3 trees of mixed species and heights
  ;; anywhere in the column, on mounds or flat ground, under low leaves or open sky, from a
  ;; varied spawn. Where any tree stands on a bank it can climb (0 or 1 high) it ends holding a
  ;; log. A 2-high bank may still be given up with :no-log when no safe climb reaches it, or
  ;; taken when the gather's climb toward a remembered trunk reaches the bank (issue #87). Either
  ;; sim never sees something a real server would punish, within the gym's own time for the goal. FOREST_TRIALS=n overrides the count, FOREST_SEED=s fixes the seed
  ;; (a plan's shard), FOREST_OUT=f writes the verdict as EDN.
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
                                                  wood-ms
                                                  {:event/kind :go :go/goals [:wood]})]
                             (and (if (some #(<= (:mound %) 1) trees)
                                    (and (plan/done? w) (= 1 (inventory/logs-held w)))
                                    (or (and (plan/done? w) (= 1 (inventory/logs-held w)))
                                        (and (not (plan/done? w))
                                             (= :no-log (or (:plan/reason w) (:plan/waiting w)))
                                             (zero? (inventory/logs-held w)))))
                                  (empty? (:sim/violations sim)))))
                          :seed
                          (or (some-> (System/getenv "FOREST_SEED")
                                      Long/parseLong)
                              (System/currentTimeMillis)))]
    (when-let [out (System/getenv "FOREST_OUT")] ; a plan's shard reads its verdict as data
      (spit out
            (pr-str {:pass? (boolean (:pass? r))
                     :num-tests (:num-tests r)
                     :seed (:seed r)
                     :smallest (get-in r [:shrunk :smallest])})))
    (is (:pass? r) (pr-str (select-keys r [:fail :shrunk :num-tests :seed])))))

(deftest a-trunk-on-a-two-high-mound-is-not-dug-from-its-foot
  ;; the forest property at FOREST_SEED 388575908371, shrunk: the trunk on the two-high mound was
  ;; dug from the ground three cells off, its drop rested on the mound top out of the pickup box,
  ;; the collect timed out and a second log was dug for the first. Banks the bot climbs stand
  ;; beside it; it ends holding one log, from one of them
  (let [trees [{:x 12 :z 5 :h 3 :id 137 :mound 1 :canopy? false :branch nil}
               {:x 8 :z 6 :h 3 :id 137 :mound 0 :canopy? true :branch nil}
               {:x 4 :z 6 :h 3 :id 137 :mound 2 :canopy? false :branch nil}]
        [w sim] (sim/run step
                         (game/init fx/opts)
                         (sim/init {:column (world/column-bytes (forest trees))
                                    :spawn [0.5 64.0 0.5]})
                         #(or (plan/done? %) (plan/failed? %))
                         wood-ms
                         {:event/kind :go :go/goals [:wood]})]
    (is (plan/done? w) (pr-str (plan/summary w)))
    (is (= 1 (inventory/logs-held w)) "one log, no second dug for a drop left on the mound")
    (is (not-any? #(= [:failed :not-picked-up] [(:intention/event %) (:intention/reason %)])
                  (memory/intentions w))
        "no collect timed out")
    (is (empty? (:sim/violations sim)))))

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

(def canopy-pocket
  "A pocket one deep under an oak canopy (race 38477665442 runner 10 Clj_r16, issue #63): walls
   two high round the feet at [8 64 8], the step north open at body height but leaves at head
   height over it and over the feet, and a trunk on open ground six blocks north."
  (merge
   (into
    {}
    (for [dx [-1 0 1] dz [-1 0 1] y [64 65] :when (not= [dx dz] [0 0])] [[(+ 8 dx) y (+ 8 dz)] 1]))
   {[8 65 7] blocks/air}
   (into {} (for [[x z] [[8 7] [8 8] [7 7] [9 7] [7 8] [9 8] [8 6]]] [[x 66 z] 252]))
   {[8 64 2] 136 [8 65 2] 136 [8 66 2] 136 [8 67 2] 136 [8 68 2] 252}))

(deftest a-log-is-gathered-from-a-pocket-under-a-canopy
  ;; issue #63: in the pocket every jump out meets leaves, so no route reaches the trunk; the
  ;; bot cuts stairs up through the leaves and the wall, then walks to the trunk, with no walk
  ;; failed :no-path on the way
  (let [[w sim] (sim/run step
                         (game/init fx/opts)
                         (sim/init {:column (world/column-bytes canopy-pocket)
                                    :spawn [8.5 64.0 8.5]})
                         #(or (plan/done? %) (plan/failed? %))
                         120000
                         {:event/kind :go :go/goals [:wood]})]
    (is (plan/done? w) (pr-str (plan/summary w)))
    (is (= 1 (inventory/logs-held w)))
    (is (zero? (:plan/unwalkable w)) "no walk failed :no-path")
    (is (zero? (:plan/attempts w)) "no failed attempts")
    (is (some (:sim/broken sim) [[8 66 8] [8 66 7]]) "it cut up through the leaves")))

(def bank
  "A trunk on a stone bank four blocks high (x 2), the pit beside it walled in (z 2; the chunk's
   unloaded neighbours wall x -1 and z -1)."
  (merge (into {} (for [y (range 64 68) z [0 1 2]] [[2 y z] 1]))
         (into {} (for [y (range 64 68) x [0 1]] [[x y 2] 1]))
         {[2 68 0] 136 [2 69 0] 136 [2 70 0] 136 [2 71 0] 136 [2 72 0] 252}))

(deftest a-drop-out-of-reach-is-never-dug-for
  ;; recorded by the gym and a plan at landing 21280,18720 (wood-a2 run 5, smoke2 run 5): the
  ;; bot stood in a pit, dug the base of a trunk on the bank four blocks above its feet, and the
  ;; drop came to rest on the bank, above a 1.8-tall player's pickup box. Reach is not enough: a
  ;; log walk arrives only where the drop will land within pickup height. Since #75 a trunk no
  ;; route reaches is never walked at (wood/near-walk): it is passed over, with no failure to
  ;; count. Since #56 (PR #101) and #102 (PR #114) a pit under the sky is not out of the ground:
  ;; with no trunk a route reaches the bot cuts stairs up out of it first, and only from there,
  ;; where a route may reach the trunk, walks at it.
  (let [[w sim] (sim/run step
                         (game/init fx/opts)
                         (sim/init {:column (world/column-bytes bank) :spawn [1.5 64.0 0.5]})
                         #(or (plan/done? %) (plan/failed? %) (= :no-log (:plan/waiting %)))
                         60000
                         {:event/kind :go :go/goals [:wood]})
        intentions (memory/intentions w)
        at-trunk? (fn [i]
                    (and (= :walk (:intention/kind i))
                         (= [2 0] (let [[x _ z] (:intention/target i)] [x z]))))
        climbed (some #(when (and (= :stairs-up (:intention/kind %)) (= :done (:intention/event %)))
                         (:intention/at %))
                      intentions)]
    (is (not-any? (:sim/broken sim) [[2 68 0] [2 69 0]]) "no log dug whose drop it could not take")
    (is (= :stairs-up (:intention/kind (first intentions))) "out of the pit first")
    (is (some? climbed) "the climb out is done")
    (is (every? #(>= (:intention/at %) climbed) (filter at-trunk? intentions))
        "the trunk is walked at only from above the pit, never from it")
    (is (or (plan/done? w) (= :no-log (:plan/waiting w))) (pr-str (plan/summary w)))))

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
  ;; docs/RICH-HICKEY.md: "Bot and server model composed through a channel pair are a complete
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

(defn intent-run-holding
  "Run intent it alone against the model from a spawn at [8.5 spawn-y 8.5] over column blocks,
   holding inventory ({[:hotbar n] {:item id :count n}}), until it ends or max-ms, with the
   model's other options (sim/init's fault knobs) when given. Returns [world sim]."
  ([it blocks spawn-y inventory max-ms] (intent-run-holding it blocks spawn-y inventory max-ms {}))
  ([it blocks spawn-y inventory max-ms sim-opts]
   (let [column (world/column-bytes blocks)
         step (fn [w e]
                (let [w (game/step w e)
                      i (:plan/intent w)]
                  (cond (= :go (:event/kind e))
                        (assoc w :plan/intent (assoc it :intent/status :active))
                        (and (= :tick (:event/kind e))
                             (= :active (:intent/status i))
                             (:player/loaded? w))
                        (intent/run w i e)
                        :else w)))
         sim0 (sim/init (merge
                         {:column column :spawn [8.5 (double spawn-y) 8.5] :inventory inventory}
                         sim-opts))]
     (sim/run step
              (game/init fx/opts)
              sim0
              #(contains? #{:done :failed} (:intent/status (:plan/intent %)))
              max-ms))))

(defn intent-run
  "intent-run-holding with one item in hotbar slot 0, or nothing."
  [it blocks spawn-y item max-ms]
  (intent-run-holding it blocks spawn-y (if item {[:hotbar 0] {:item item :count 1}} {}) max-ms))

(defn staircase-run
  "Run a staircase intent of kind (:stairs-down, :stairs-up) toward to-y (intent-run)."
  [kind blocks spawn-y item to-y max-ms]
  (intent-run {:intent/kind kind :intent/to-y to-y} blocks spawn-y item max-ms))

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

(def cave-lip
  "One block of stone at [8 75 8] over a cave whose floor is at y 65, nothing else above the
   floor: what a staircase finds when it breaks through a cave's ceiling and stands on the
   ceiling's last block."
  (into {}
        (for [x (range 16)
              y (range 64 76)
              z (range 16)
              :when (or (< y 66) (= [x y z] [8 75 8]))]
          [[x y z] 1])))

(deftest a-cave-under-the-stairs-is-cast-through-from-carried-cobblestone
  ;; race 38477665442 runner 8: Clj_r18, Clj_r04, Clj_r23, Clj_r03 and Clj_r02 each stood on
  ;; the lip of a cave their staircase had broken into, 7 to 284 cobblestone in hand, every stair
  ;; refused for the drop under it, and nothing else could be cut from the lip: the plan waited
  ;; :boxed out (issue #37). A missing floor is laid first, and the stair is then cut as any other
  (let [pickaxe {:item (get blocks/items :wooden_pickaxe) :count 1}
        cobblestone {:item (get blocks/items :cobblestone) :count 26} ; ten to lay, sixteen kept for the climb back out of the open air (stairs/max-void-reserve)
        [w sim] (intent-run-holding {:intent/kind :stairs-down :intent/to-y 71}
                                    cave-lip
                                    76
                                    {[:hotbar 0] pickaxe [:hotbar 1] cobblestone}
                                    120000)]
    (is (= :done (:intent/status (:plan/intent w)))
        (pr-str (select-keys (:plan/intent w)
                             [:intent/status :intent/reason :intent/stage :intent/turns])))
    (is (= 71 (second (intent/feet w))) "the feet are five stairs down, and no further")
    (is (= 10 (count (:sim/placed sim))) "a holder and a floor laid a stair, five stairs")
    (is (empty? (:sim/broken sim)) "nothing dug: the cave is open")
    (is (= 10
           (- 26
              (:count (some #(when (= (:item cobblestone) (:item %)) %)
                            (vals (:player/inventory w))))))
        "from the cobblestone carried")
    (is (every? #(not= [8 75 8] %) (:sim/broken sim)) "the lip was never dug")
    (is (empty? (:sim/violations sim)))
    (testing "with nothing to lay a floor from, the lip is a box as before"
      (let [[w _] (intent-run-holding {:intent/kind :stairs-down :intent/to-y 71}
                                      cave-lip
                                      76
                                      {[:hotbar 0] pickaxe}
                                      120000)]
        (is (= [:failed :boxed]
               [(:intent/status (:plan/intent w)) (:intent/reason (:plan/intent w))]))))))

(deftest a-staircase-started-in-the-air-waits-for-the-ground
  ;; race 38477665442, landing 2361,111,2361 (issue #65): a walk to stone stepped off a cliff,
  ;; and the staircase the mine started on the way down was planned from the air, refused four
  ;; ways and failed :boxed three times before the body landed. Ten blocks over the hill
  (let [[w sim] (stairs-run deep-stone 86 (get blocks/items :wooden_pickaxe) 71 120000)]
    (is (= :done (:intent/status (:plan/intent w)))
        (pr-str (select-keys (:plan/intent w)
                             [:intent/status :intent/reason :intent/stage :intent/turns])))
    (is (= [1 0] (:intent/dir (:plan/intent w))) "never refused: planned once on the ground")
    (is (<= (second (:player/pos w)) 71.5) "the feet are five stairs down")
    (is (contains? (:sim/broken sim) [9 75 8]) "the first stair cut from the hilltop")
    (is (empty? (:sim/violations sim)))))

(deftest a-model-from-before-the-breaks-counter-counts-from-zero
  ;; the issue check plays snapshots on whose model was saved before :sim/breaks existed
  ;; (sim race 38515210080 at 7239df5, test/issues/37 and 74): the counter is accretion, so
  ;; such a model counts its FINISHes from zero instead of throwing on nil
  (let [sim (-> (dig-on-model 1 wooden-pickaxe 2000)
                (dissoc :sim/breaks))
        sim (sim/step sim
                      {:sim/kind :packet
                       :sim/packet {:packet/name :player-action
                                    :status (packet/value :dig :stop-destroy)
                                    :pos [1 64 0]
                                    :face (packet/value :face :west)
                                    :sequence 3}})]
    (is (= 1 (:sim/breaks sim)))))

(deftest a-stair-the-server-did-not-cut-is-dug-again
  ;; race 38477665442, runner 9 (issues #59, #66): with 25 bots on one server, an early FINISH
  ;; was queued behind another and dropped, its ack coming with the block still standing; the
  ;; staircase believed the ack, walked into rock the server still had, and was put back by it
  ;; ten times a second until the step timed out, :stair-not-taken three times. The model
  ;; drops the second break (the second stair's head cell; the first stair has one cell of
  ;; rock, under the hilltop): the ack alone, the block left standing
  (let [[w sim]
        (intent-run-holding {:intent/kind :stairs-down :intent/to-y 71}
                            deep-stone
                            76 {[:hotbar 0] {:item (get blocks/items :wooden_pickaxe) :count 1}}
                            120000 {:drop-breaks #{1}})]
    (is (= :done (:intent/status (:plan/intent w)))
        (pr-str (select-keys (:plan/intent w)
                             [:intent/status :intent/reason :intent/stage :intent/turns])))
    (is (<= (second (:player/pos w)) 71.5) "the feet are five stairs down")
    (is (= 13 (:sim/breaks sim)) "twelve cells of rock, one of them asked for twice")
    (is (contains? (:sim/broken sim) [10 75 8]) "the dropped cell was dug again")
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

(deftest snow-in-the-stair-is-cleared-by-hand
  ;; race 38477665442, runners 2, 6 and 10: every bot landed in snow failed :no-shovel on its
  ;; first stair, a wooden pickaxe in hand, a snow block among the stair's cells (issues #42
  ;; and #72). Snow drops only to a shovel, but the staircase wants the cell open, not the drop
  (let [snow (blocks/default-state :snow_block)
        blocks (assoc deep-stone [9 75 8] snow [9 74 8] snow) ; the first +x stair's new feet, and the next stair's head
        [w sim] (stairs-run blocks 76 (get blocks/items :wooden_pickaxe) 74 60000)]
    (is (= :done (:intent/status (:plan/intent w)))
        (pr-str (select-keys (:plan/intent w)
                             [:intent/status :intent/reason :intent/stage :intent/turns])))
    (is (= [1 0] (:intent/dir (:plan/intent w))) "straight on through the snow, no turn")
    (is (contains? (:sim/broken sim) [9 75 8]) "the snow dug, by hand")
    (is (empty? (:sim/violations sim)))))

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

(deftest water-beside-every-descent-is-sealed-before-digging
  (let [terrain (reduce (fn [b [x z]] (assoc b [x 75 z] blocks/water-base))
                        deep-stone
                        [[10 8] [6 8] [8 10] [8 6]])
        [w sim] (intent-run-holding {:intent/kind :stairs-down :intent/to-y 75}
                                    terrain
                                    76
                                    {[:hotbar 0] {:item (blocks/items :wooden_pickaxe) :count 1}
                                     [:hotbar 1] {:item (blocks/items :dirt)
                                                  :count (inc stairs/lay-reserve)}}
                                    60000)]
    (is (= :done (:intent/status (:plan/intent w))))
    (is (= [1 0] (:intent/dir (:plan/intent w))) "sealed, not turned away")
    (is (contains? (:sim/placed sim) [10 75 8]) "the source was sealed")
    (is (contains? (:sim/broken sim) [9 75 8]) "then the adjoining stone was dug")
    (is (not (:player/in-water? w)))
    (is (empty? (:sim/violations sim)))))

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
                                    :inventory
                                    {[:hotbar 0] {:item (get blocks/items :crafting_table) :count 1}
                                     [:hotbar 1] {:item (get blocks/items :furnace) :count 1}}})
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
                         (sim/init {:column
                                    (world/column-bytes
                                     (assoc deep-stone [13 74 9] (blocks/default-state :iron_ore)))
                                    :spawn [8.5 76.0 8.5]
                                    ;; six bread: the early forage (issue #504) is for a bot
                                    ;; with nothing to eat, and this row is the tunnel
                                    :inventory
                                    {[:hotbar 0] {:item (get blocks/items :stone_pickaxe) :count 1}
                                     [:hotbar 1] {:item (get blocks/items :bread) :count 6}}})
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
                                    :inventory {[:hotbar 0] {:item (get blocks/items
                                                                        :wooden_pickaxe)
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
                                    {[:hotbar 0] {:item (get blocks/items :wooden_pickaxe) :count 1}
                                     [:hotbar 1] {:item (get blocks/items :stick) :count 2}
                                     [:hotbar 2] {:item (get blocks/items :crafting_table)
                                                  :count 1}}})
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
                                    :inventory {[:hotbar 0] {:item (get blocks/items :furnace)
                                                             :count 1}}})
                         #(or (plan/done? %) (plan/failed? %))
                         30000
                         {:event/kind :go :go/goals [:furnace-near]})]
    (is (plan/done? w) (pr-str (plan/summary w)))
    (is (some? (make/near w :furnace)) "the placed furnace is remembered near")
    (is (= [:furnace] (map blocks/name-of (vals (:sim/placed sim)))) "the server has it")
    (is (zero? (get (held w) :furnace 0)) "and it left the hand")
    (is (empty? (:sim/violations sim)))))


(deftest a-furnace-is-placed-in-a-niche-cut-with-the-stowed-pickaxe
  ;; sim race 38477665389 world R1/43 (issue #94, 11 runs): the furnace at the end of its iron
  ;; tunnel at y 16, the table behind it in the passage, two stone pickaxes held, and :no-spot
  ;; three times. The hotbar was full of what mining picked up, so equipping the furnace swapped
  ;; the pickaxe into the main inventory, and a niche asked of the hotbar alone was refused as
  ;; a block the tools cannot drop. A stowed pickaxe counts, and is put in hand before the dig
  (let [tunnel (merge (into {} (for [x (range 16) y (range 64 70) z (range 16)] [[x y z] 1]))
                      (into {} (for [x (range 2 9) y [64 65]] [[x y 8] 0]))
                      {[7 64 8] (blocks/default-state :crafting_table)})
        junk {:item (get blocks/items :dirt) :count 1}
        inv (assoc (into {} (for [h (range 1 inventory/hotbar-slots)] [[:hotbar h] junk]))
                   [:hotbar 0]
                   {:item (get blocks/items :furnace) :count 1}
                   [:main 0]
                   {:item (get blocks/items :stone_pickaxe) :count 1})
        table (conj plan/goals
                    {:goal/id :furnace-near
                     :goal/priority 7
                     :goal/target? true
                     :goal/provides {:block/furnace :near}
                     :goal/done? :provided?})
        [w sim] (sim/run (game/compose game/step (partial plan/step-over table))
                         (game/init fx/opts)
                         (sim/init {:column (world/column-bytes tunnel)
                                    :spawn [8.5 64.0 8.5]
                                    :inventory inv})
                         #(or (plan/done? %) (plan/failed? %))
                         60000
                         {:event/kind :go :go/goals [:furnace-near]})
        placed (some (fn [[pos id]] (when (= :furnace (blocks/name-of id)) pos)) (:sim/placed sim))]
    (is (plan/done? w) (pr-str (plan/summary w)))
    (is (contains? #{[8 64 7] [8 64 9] [8 65 7] [8 65 9] [9 64 8] [9 65 8]} placed)
        "in a niche cut in a wall of the tunnel")
    (is (not-any? #(= :failed (:intention/event %)) (memory/intentions w)))
    (is (contains? (:sim/broken sim) placed) "the niche was dug, which only the pickaxe drops")
    (is (empty? (:sim/violations sim)))))

(deftest a-table-is-never-placed-into-a-flower
  ;; race 38477665442 bot Clj_r23: every spot the bot tried held wildflowers, which the server
  ;; keeps (a flower bed is not replaceable), and the table was refused three times
  (let [flowers (into {}
                      (for [dx [-1 0 1]
                            dz [-1 0 1]
                            :when (not= [dx dz] [0 0])]
                        [[(+ 8 dx) 64 (+ 8 dz)] (blocks/default-state :wildflowers)]))
        table (conj plan/goals
                    {:goal/id :table-near
                     :goal/priority 7
                     :goal/target? true
                     :goal/provides {:block/crafting_table :near}
                     :goal/done? :provided?})
        [w sim] (sim/run (game/compose game/step (partial plan/step-over table))
                         (game/init fx/opts)
                         (sim/init {:column (world/column-bytes flowers)
                                    :spawn [8.5 64.0 8.5]
                                    :inventory {[:hotbar 0] {:item (get blocks/items
                                                                        :crafting_table)
                                                             :count 1}}})
                         #(or (plan/done? %) (plan/failed? %))
                         30000
                         {:event/kind :go :go/goals [:table-near]})]
    (is (plan/done? w) (pr-str (plan/summary w)))
    (is (= [:crafting_table] (map blocks/name-of (vals (:sim/placed sim)))))
    (is (not-any? flowers (keys (:sim/placed sim))) "beyond the flowers, never into one")
    (is (zero? (:plan/attempts w)) "no placement refused")
    (testing "the model refuses a placement into a flower, as the server does"
      (let [sim (-> (sim/init {:column (world/column-bytes flowers)
                               :spawn [8.5 64.0 8.5]
                               :inventory {[:hotbar 0] {:item (get blocks/items :crafting_table)
                                                        :count 1}}})
                    (assoc :sim/phase :play :sim/player-pos [8.5 64.0 8.5])
                    (sim/step {:sim/kind :packet
                               :sim/packet {:packet/name :use-item-on
                                            :hand 0
                                            :pos [9 63 8]
                                            :face (packet/value :face :up)
                                            :cursor-x 0.5
                                            :cursor-y 1.0
                                            :cursor-z 0.5
                                            :inside-block false
                                            :world-border-hit false
                                            :sequence 1}}))]
        (is (empty? (:sim/placed sim)))
        (is
         (= [{:packet/name :block-update :pos [9 64 8] :state (blocks/default-state :wildflowers)}
             {:packet/name :block-changed-ack :sequence 1}]
            (:sim/out sim)))))))

(deftest a-block-placed-into-water-takes-the-waters-place
  ;; sim race 38515210080 (issue #44, 198 runs; the #74 moments at R1/34 reach it): the first
  ;; op of the portal's pad placed cobblestone into a water cell at the pond's edge from the
  ;; shore. The game's replaceable tag lists water and lava beside the grasses, so a real
  ;; server takes the block and the water is gone; the model refused it three times
  (let [water (first blocks/water-states)
        pond (-> (into {} (for [x (range 16) z (range 16)] [[x 63 z] 1]))
                 (assoc [9 64 8] water)) ; the pond floor at y 63 is stone, clicked through the water
        use-on (fn [pos face]
                 {:packet/name :use-item-on
                  :hand 0
                  :pos pos
                  :face (packet/value :face face)
                  :cursor-x 0.5
                  :cursor-y 1.0
                  :cursor-z 0.5
                  :inside-block false
                  :world-border-hit false
                  :sequence 1})
        standing (fn [blocks]
                   (-> (sim/init {:column (world/column-bytes blocks)
                                  :spawn [8.5 64.0 8.5]
                                  :inventory {[:hotbar 0] {:item (get blocks/items :cobblestone)
                                                           :count 2}}})
                       (assoc :sim/phase :play :sim/player-pos [7.5 64.0 8.5])))
        ;; the shore's top face, east of the feet: the destination [9 64 8] is the pond's edge
        into-water (sim/step (standing pond) {:sim/kind :packet :sim/packet (use-on [9 63 8] :up)})
        ;; a flower on the shore instead: refused as before
        into-flower (sim/step (standing (assoc pond [9 64 8] (blocks/default-state :wildflowers)))
                              {:sim/kind :packet :sim/packet (use-on [9 63 8] :up)})]
    (is (blocks/placeable-into? water))
    (is (not (blocks/placeable-into? (blocks/default-state :wildflowers))))
    (is (= {[9 64 8] (blocks/default-state :cobblestone)} (:sim/placed into-water))
        "the cobblestone took the water's place")
    (is (= {:packet/name :block-update :pos [9 64 8] :state (blocks/default-state :cobblestone)}
           (first (:sim/out into-water))))
    (is (= 1 (get-in into-water [:sim/inv (sim/held-slot into-water) :count])) "one taken")
    (is (empty? (:sim/placed into-flower)))))

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

;; ---------------------------------------------------------------- the portal (issue #8)

(def frame
  "A portal frame along x at z 8 with its corner origin [4 64 8]: ten obsidian round an interior
   [5..6, 65..67], on the stone floor."
  (into {}
        (for [[dx dy] [[1 0] [2 0] [0 1] [3 1] [0 2] [3 2] [0 3] [3 3] [1 4] [2 4]]]
          [[(+ 4 dx) (+ 64 dy) 8] (blocks/default-state :obsidian)])))

(defn lighter-sim
  "A model in play over blocks, the player at [5.5 64 10.5] holding flint and steel."
  [blocks]
  (-> (sim/init {:column (world/column-bytes blocks)
                 :spawn [5.5 64.0 10.5]
                 :inventory {[:hotbar 0] {:item (recipe/item-id :flint_and_steel) :count 1}}})
      (assoc :sim/phase :play :sim/player-pos [5.5 64.0 10.5])))

(defn strike
  "The model after the player uses its held item on the top face of the block at pos."
  [sim pos]
  (client-sends sim
                {:packet/name :use-item-on
                 :hand 0
                 :pos pos
                 :face (packet/value :face :up)
                 :cursor-x 0.5
                 :cursor-y 1.0
                 :cursor-z 0.5
                 :inside-block false
                 :world-border-hit false
                 :sequence 9}))

(deftest flint-and-steel-in-a-complete-frame-lights-the-portal
  (let [sim (strike (lighter-sim frame) [5 64 8])]
    (is (every? #(= :nether_portal (blocks/name-of (sim/block-at sim %)))
                (for [x [5 6] y [65 66 67]] [x y 8])))
    (is (= :fire (blocks/name-of (sim/block-at (strike (lighter-sim {}) [5 63 8]) [5 64 8])))
        "no frame: a fire, no portal")))

(deftest standing-in-the-portal-four-seconds-goes-to-the-nether
  (let [lit (-> (strike (lighter-sim frame) [5 64 8])
                (assoc :sim/player-pos [5.5 65.0 8.5] :sim/out []))
        at (fn [sim ms] (sim/step sim {:sim/kind :tick :sim/now ms}))
        early (reduce at lit (range 1000 4950 50))
        later (reduce at early (range 4950 5200 50))]
    (is (not-any? #(= :respawn (:packet/name %)) (:sim/out early)) "not before four seconds")
    (is (= "minecraft:the_nether" (:sim/dimension later)))
    (is (some #(= "minecraft:the_nether" (:dimension %)) (:sim/out later)))))

(deftest a-nether-death-allows-another-portal-crossing
  (let [lit (sim/stream (strike (lighter-sim frame) [5 64 8]))
        dead (assoc lit :sim/health 0.0 :sim/dimension dimension/nether :sim/out [])
        alive (sim/on-packet dead
                             {:packet/name :client-command
                              :action (packet/value :client-command :perform-respawn)})
        in-portal (assoc alive :sim/player-pos [5.5 65.0 8.5])
        entered (sim/through-portal (sim/through-portal in-portal 0) sim/portal-ms)]
    (is (= dimension/overworld (:sim/dimension alive)))
    (is (some #(= :level-chunk-with-light (:packet/name %)) (:sim/out alive))
        "the client discarded the other dimension's chunks")
    (is (= dimension/nether (:sim/dimension entered)))
    (is (= 1 (:sim/respawns entered)))))

(deftest a-death-where-the-spawn-is-kept-respawns-there-with-the-inventory
  ;; a race that starts at a fortress pins the spawn in the Nether (spawnpoint) and keeps
  ;; inventories: a death costs time and the kit, not the dimension
  (let [pick {:item (recipe/item-id :stone_pickaxe) :count 1}
        sim (sim/init {:columns {[0 0] (world/column-bytes {})}
                       :spawn [0.5 64.0 0.5]
                       :dimension dimension/nether
                       :respawn-in dimension/nether
                       :inventory {[:hotbar 0] pick}})
        dead (assoc sim :sim/health 0.0 :sim/out [])
        alive (sim/on-packet dead
                             {:packet/name :client-command
                              :action (packet/value :client-command :perform-respawn)})]
    (is (= dimension/nether (:sim/dimension alive)) "still in the Nether")
    (is (some #(= dimension/nether (:dimension %)) (:sim/out alive)) "told it is the Nether")
    (is (= 1 (:sim/respawns alive)))
    (is (= pick (get-in alive [:sim/inv (inventory/slot [:hotbar 0])])) "the kit kept")))

(deftest streamed-chunks-include-the-blocks-the-server-now-holds
  ;; Sim race 38999010135 (issue #328): resending the original column after the portal erased
  ;; the client's dug room, so its feet were in deepslate and the tunnel failed :no-stand.
  (let [column (world/column-bytes {})
        broken [1 63 1]
        placed [2 64 1]
        replaced [3 63 1]
        cobble (blocks/default-state :cobblestone)
        s (-> (sim/init
               {:columns {[0 0] column [1 0] column} :spawn [0.5 64.0 0.5] :view-distance 0})
              (assoc :sim/broken #{broken replaced [17 63 1]}
                     :sim/placed {placed cobble replaced cobble [18 64 1] cobble}))
        s (sim/stream s)
        updates (filter #(= :block-update (:packet/name %)) (:sim/out s))
        w (reduce game/on-packet (assoc (game/init fx/opts) :bot/phase :play) (:sim/out s))]
    (is (= {broken blocks/air placed cobble replaced cobble}
           (into {} (map (juxt :pos :state)) updates))
        "only the fresh column's changes, with a placement winning over an earlier break")
    (doseq [p [broken placed replaced]] (is (= (sim/block-at s p) (terrain/block-at w p))))
    (is (= :chunk-batch-finished (:packet/name (last (:sim/out s)))))
    (testing "a column that leaves view and comes back still carries its changes"
      (let [away (sim/stream (assoc s :sim/player-pos [16.5 64.0 0.5] :sim/out []))
            back (sim/stream (assoc away :sim/player-pos [0.5 64.0 0.5] :sim/out []))
            w (reduce game/on-packet w (concat (:sim/out away) (:sim/out back)))]
        (doseq [p [broken placed replaced]] (is (= (sim/block-at back p) (terrain/block-at w p))))))
    (testing "a column already held sends nothing again"
      (is (empty? (:sim/out (sim/stream (assoc s :sim/out []))))))))

(deftest a-portal-transition-does-not-put-the-client-back-in-undug-rock
  (let [feet [1 62 1]
        head [1 63 1]
        s (-> (sim/init {:column (world/column-bytes {}) :spawn [1.27 62.0 1.7]})
              (assoc :sim/player-pos [1.27 62.0 1.7]
                     :sim/portal-since 0
                     :sim/placed {feet (blocks/default-state :nether_portal)}
                     :sim/broken #{head}))
        s (sim/through-portal s sim/portal-ms)
        w (reduce game/on-packet (assoc (game/init fx/opts) :bot/phase :play) (:sim/out s))
        op (second (fortress/step-plan feet [1 0 0]))]
    (is (= dimension/nether (:player/dimension w)))
    (is (= (sim/block-at s feet) (terrain/block-at w feet)))
    (is (= blocks/air (terrain/block-at w head)))
    (is (path/standable? w feet))
    (is (= :walk (:intent/kind (build/op-child w {:intent/kind :tunnel-to} op)))
        "the off-centre feet can walk to their stand instead of failing :no-stand")))

;; ---------------------------------------------------------------- building the portal (issue #8)

(def build-origin
  "Where the frame's corner goes in the one-column arena: the site choose-site picks beside the
   lava pit, cut into the stone six under its surface facing east, the lava's stand at the surface
   (access 6, issue #124)."
  [7 58 12])
(def build-facing "The side of the frame the bot works from at build-origin." :east)
(def build-access "The lava's height over the room's floor at build-origin." 6)
(defn at-build
  "The cell [dx dy dz] from build-origin facing build-facing."
  [d]
  (build/cell build-origin build-facing d))

(def kit
  "What the gym gives a cast: buckets of lava and water, an empty one, cobblestone, flint and
   steel, and the stone pickaxe the rows hold."
  {[:hotbar 0] {:item (recipe/item-id :lava_bucket) :count 1}
   [:hotbar 1] {:item (recipe/item-id :water_bucket) :count 1}
   [:hotbar 2] {:item (recipe/item-id :bucket) :count 1}
   [:hotbar 3] {:item (recipe/item-id :cobblestone) :count 64}
   [:hotbar 4] {:item (recipe/item-id :flint_and_steel) :count 1}
   [:hotbar 5] {:item (recipe/item-id :cobblestone) :count 64}
   [:hotbar 6] {:item (recipe/item-id :cobblestone) :count 64}
   [:hotbar 8] {:item (recipe/item-id :stone_pickaxe) :count 1}})

(defn build-run
  "Run a :build of kind from build-origin facing south over blocks, from spawn with inv, until it
   ends or max-ms. Returns [world sim]."
  [kind blocks spawn inv max-ms]
  (let [it {:intent/kind :build
            :intent/build kind
            :intent/origin build-origin
            :intent/facing build-facing
            :intent/access build-access}
        step
        (fn [w e]
          (let [w (game/step w e)
                i (:plan/intent w)]
            (cond (= :go (:event/kind e)) (assoc w :plan/intent (assoc it :intent/status :active))
                  (and (= :tick (:event/kind e)) (= :active (:intent/status i)) (:player/loaded? w))
                  (intent/run w i e)
                  :else w)))]
    (sim/run step
             (game/init fx/opts)
             (sim/init {:column (world/column-bytes blocks) :spawn spawn :inventory inv})
             #(contains? #{:done :failed} (:intent/status (:plan/intent %)))
             max-ms)))

(def lava-pit
  "A trench of lava sources set into the floor east of the footprint, one wide so every source
   has ground beside it to refill from (a scoop takes a source for good)."
  (into {} (for [z (range 0 12)] [[15 63 z] blocks/lava-base])))

(deftest one-cell-is-cast-from-lava-and-water
  (let [[w sim] (build-run :cast-one lava-pit [9.5 64.0 10.5] kit 600000)]
    (is (= :done (:intent/status (:plan/intent w)))
        (pr-str (select-keys (:plan/intent w) [:intent/status :intent/reason :intent/op])))
    (is (= :obsidian (blocks/name-of (sim/block-at sim (at-build [1 0 0]))))
        "the first cell, obsidian")
    (is (= (recipe/item-id :water_bucket)
           (some #(when (= (recipe/item-id :water_bucket) (:item %)) (:item %))
                 (vals (:sim/inv sim))))
        "the water scooped back")
    (is (empty? (:sim/violations sim)))))

(deftest the-frame-is-cast-lit-and-stepped-through
  (let [[w sim] (build-run :nether lava-pit [9.5 64.0 10.5] kit 1800000)
        frame (for [[dx dy] build/frame-cells] (at-build [dx dy 0]))]
    (is (= :done (:intent/status (:plan/intent w)))
        (pr-str (select-keys (:plan/intent w) [:intent/status :intent/reason :intent/op])))
    (is (every? #(= :obsidian (blocks/name-of (sim/block-at sim %))) frame) "ten obsidian")
    (is (= 6
           (count (filter #(= :nether_portal (blocks/name-of (sim/block-at sim %)))
                          (for [[dx dy] build/interior] (at-build [dx dy 0]))))))
    (is (= "minecraft:the_nether" (:player/dimension w)))
    (is (empty? (:sim/violations sim)))))

(deftest a-cast-six-tenths-done-casts-only-the-other-four
  ;; the build reads the world, not a record of its progress: six cells already obsidian are
  ;; skipped, so four lava pours are needed, one from the kit and three from the trench
  (let [seeded (into {}
                     (for [[dx dy] (take 6 build/frame-cells)]
                       [(at-build [dx dy 0]) (blocks/default-state :obsidian)]))
        [w sim] (build-run :portal (merge lava-pit seeded) [9.5 64.0 10.5] kit 1800000)]
    (is (= :done (:intent/status (:plan/intent w)))
        (pr-str (select-keys (:plan/intent w) [:intent/status :intent/reason :intent/op])))
    (is (= 9 (count (filter #(= blocks/lava-base (sim/block-at sim %)) (keys lava-pit))))
        "three lava scooped: four cells cast, not ten")
    (is (empty? (:sim/violations sim)))))

;; ---------------------------------------------------------------- pours (issue #8)

(def cup
  "One cell of ruststeve's mold at [5 64 5] in a stone floor: the cup, stone round it (north,
   east, west, and the stand to the south at [5 64 6]), the bowl over it at [5 65 5] walled north,
   east and west, the player on the stand with its feet at [5 65 6]."
  (into {} (for [c [[5 64 4] [4 64 5] [6 64 5] [5 64 6] [5 65 4] [4 65 5] [6 65 5]]] [c 1])))

(defn pour-sim
  "A model in play over cup, the player standing on the stand, holding item in the first hotbar
   place, with the cells in extra placed."
  [item extra]
  (-> (sim/init {:column (world/column-bytes cup)
                 :spawn [5.5 65.0 6.5]
                 :inventory {[:hotbar 0] {:item (recipe/item-id item) :count 1}}})
      (assoc :sim/phase :play :sim/player-pos [5.5 65.0 6.5])
      (update :sim/placed merge extra)))

(defn use-toward
  "The model after the player uses its held item looking at point from its standing eye."
  [sim point]
  (let [[yaw pitch] (physics/look-at [5.5 (+ 65.0 physics/eye-height) 6.5] point)]
    (client-sends sim {:packet/name :use-item :hand 0 :sequence 7 :y-rot yaw :x-rot pitch})))

(deftest a-lava-pour-aimed-at-the-cups-north-wall-fills-the-cup
  ;; ruststeve's aim: the north wall's inner face, low; the ray goes through the bowl and the cup
  (let [sim (use-toward (pour-sim :lava_bucket {}) [5.5 64.3 4.98])]
    (is (= blocks/lava-base (sim/block-at sim [5 64 5])))
    (is (= (recipe/item-id :bucket) (get-in sim [:sim/inv (inventory/slot [:hotbar 0]) :item])))))

(deftest water-poured-into-the-bowl-turns-the-lava-under-it-to-obsidian
  (let [sim (use-toward (pour-sim :water_bucket {[5 64 5] blocks/lava-base}) [5.5 65.5 4.98])]
    (is (= :obsidian (blocks/name-of (sim/block-at sim [5 64 5]))))
    (is (= blocks/water-base (sim/block-at sim [5 65 5])) "the water stays in the bowl")))

(deftest water-aimed-at-the-floor-goes-through-the-lava-and-replaces-it
  ;; a filled bucket's ray ignores fluids: ruststeve's floor aim replaced its lava source
  (let [sim (use-toward (pour-sim :water_bucket {[5 64 5] blocks/lava-base}) [5.5 64.0 5.5])]
    (is (= blocks/water-base (sim/block-at sim [5 64 5])))
    (is (not= :obsidian (blocks/name-of (sim/block-at sim [5 64 5]))))))

(deftest an-empty-bucket-scoops-lava
  (let [sim (use-toward (pour-sim :bucket {[5 64 5] blocks/lava-base}) [5.5 64.85 5.5])]
    (is (= blocks/air (sim/block-at sim [5 64 5])))
    (is (= (recipe/item-id :lava_bucket)
           (get-in sim [:sim/inv (inventory/slot [:hotbar 0]) :item])))))

(defn open-furnace
  "The model after the client right-clicks the furnace."
  [sim]
  (client-sends sim
                {:packet/name :use-item-on
                 :hand (packet/value :hand :main-hand)
                 :pos furnace-pos
                 :face (packet/value :face :up)
                 :cursor-x 0.5
                 :cursor-y 1.0
                 :cursor-z 0.5
                 :inside-block false
                 :world-border-hit false
                 :sequence 1}))

(defn click-in
  "The model after one named click c into its open window, at the window's current state id."
  [sim c]
  (client-sends sim
                (merge {:packet/name :container-click
                        :window-id (get-in sim [:sim/window :id])
                        :state-id (get-in sim [:sim/window :state-id])
                        :changed []
                        :cursor nil}
                       (wire c))))

(defn game-ticks
  "The model after n game ticks, 50 ms apart."
  [sim n]
  (reduce (fn [sim k]
            (sim/step sim {:sim/kind :tick :sim/now (+ (:sim/now sim) (* physics/tick-ms k))}))
          sim
          (repeat n 1)))

(defn furnace [sim] (get-in sim [:sim/furnaces furnace-pos]))

(defn in-furnace
  "The furnace window's slot that shows place (inventory/slot-in)."
  [place]
  (inventory/slot-in :furnace place))

(defn loaded
  "A furnace opened with input in hotbar slot 0 and fuel in hotbar slot 1, the fuel then the
   input laid in with left clicks."
  [input fuel]
  (-> (furnace-sim {[:hotbar 0] input [:hotbar 1] fuel})
      open-furnace
      (click-in (inventory/left-click (in-furnace [:hotbar 1])))
      (click-in (inventory/left-click (in-furnace [:fuel])))
      (click-in (inventory/left-click (in-furnace [:hotbar 0])))
      (click-in (inventory/left-click (in-furnace [:input])))))

(deftest a-furnace-opens-with-its-slots-and-properties
  (let [sim (open-furnace (furnace-sim {[:hotbar 0] {:item (item :raw_iron) :count 4}}))
        out (:sim/out sim)]
    (is (= [:block-changed-ack :open-screen :container-set-content :container-set-data
            :container-set-data :container-set-data :container-set-data]
           (mapv :packet/name out)))
    (is (= (blocks/menus :furnace) (:menu-type (second out))))
    (is (= 39 (count (:items (nth out 2)))) "three of its own, then the player's 36")
    (is (= {:item (item :raw_iron) :count 4} (get (:items (nth out 2)) (in-furnace [:hotbar 0]))))
    (is (= #{0 1 2 3} (set (map :property (drop 3 out)))))))

(deftest four-raw-iron-cook-on-one-coal-in-200-ticks-each
  (let [sim (loaded {:item (item :raw_iron) :count 4} {:item (item :coal) :count 1})
        sim (assoc sim :sim/out [])]
    (is (= {(in-furnace [:input]) {:item (item :raw_iron) :count 4}
            (in-furnace [:fuel]) {:item (item :coal) :count 1}}
           (:slots (furnace sim)))
        "four clicks put the fuel and the input in")
    (is (= 3 (get-in (furnace (game-ticks sim 799)) [:slots (in-furnace [:output]) :count]))
        "three at tick 799")
    (let [done (game-ticks sim 800)
          output (last (filter #(and (= :container-set-slot (:packet/name %))
                                     (= (in-furnace [:output]) (:slot %)))
                               (:sim/out done)))]
      (is (= {(in-furnace [:output]) {:item (item :iron_ingot) :count 4}} (:slots (furnace done)))
          "four ingots at tick 800, the coal burned, the input gone")
      (is (= {:lit 801 :lit-total 1600 :cook 0 :cook-total 200}
             (select-keys (furnace done) [:lit :lit-total :cook :cook-total]))
          "one coal lit at the first tick and burns on")
      (is (= {:item (item :iron_ingot) :count 4} (:item output)) "the client was told")
      (let [taken (-> done
                      (click-in (inventory/shift-click (in-furnace [:output])))
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
      (is (= 1 (get-in at-300 [:slots (in-furnace [:output]) :count]))
          "one ingot from 300 ticks of fuel")
      (is (= [1 100] [(:lit at-300) (:cook at-300)]) "the second halfway"))
    (is (= [0 98] ((juxt :lit :cook) (furnace (game-ticks sim 301))))
        "out: progress unwinds by two")
    (is (= 0 (:cook (furnace (game-ticks sim 350)))))
    (is (= {:item (item :raw_iron) :count 2}
           (get-in (furnace (game-ticks sim 350)) [:slots (in-furnace [:input])]))
        "the rest waits for fuel")))

(deftest a-furnace-cooks-with-its-window-closed
  (let [sim (-> (loaded {:item (item :raw_iron) :count 1} {:item (item :coal) :count 1})
                (client-sends {:packet/name :container-close :window-id 1})
                (game-ticks 200)
                (assoc :sim/out [])
                open-furnace)
        content (some #(when (= :container-set-content (:packet/name %)) %) (:sim/out sim))]
    (is (= {:item (item :iron_ingot) :count 1} (get (:items content) (in-furnace [:output])))
        "opened again, the output holds what cooked meanwhile")))

(deftest the-furnace-click-rules
  (let [f (inventory/layouts :furnace)
        ingot {:item (item :iron_ingot) :count 3}
        ore {:item (item :raw_iron) :count 2}
        coal {:item (item :coal) :count 1}]
    (is (= :left (sim/click-kind f (wire (inventory/left-click (in-furnace [:input])))))
        "the input is no result")
    (is (= {:view {} :cursor coal}
           (sim/click-view f {} coal (wire (inventory/left-click (in-furnace [:output])))))
        "nothing is put into the output")
    (is (= {:view {} :cursor ore}
           (sim/click-view f {} ore (wire (inventory/right-click (in-furnace [:fuel])))))
        "the fuel slot refuses what does not burn")
    (is (= {:view {(in-furnace [:fuel]) coal} :cursor nil}
           (sim/click-view f {} coal (wire (inventory/left-click (in-furnace [:fuel]))))))
    (is (= {:view {} :cursor {:item (item :iron_ingot) :count 5}}
           (sim/click-view f
                           {(in-furnace [:output]) ingot}
                           (assoc ingot :count 2)
                           (wire (inventory/left-click (in-furnace [:output])))))
        "a cursor of the output's item takes the output onto it")
    (is (= {:view {(in-furnace [:hotbar 0]) ore} :cursor nil}
           (sim/click-view f
                           {(in-furnace [:hotbar 0]) ore}
                           nil
                           (wire (inventory/swap-click (in-furnace [:fuel]) 0))))
        "a hotbar swap cannot put ore into the fuel slot")
    (is (= {:view {(in-furnace [:main 0]) ore} :cursor nil}
           (sim/click-view f
                           {(in-furnace [:input]) ore}
                           nil
                           (wire (inventory/shift-click (in-furnace [:input])))))
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
  {[:hotbar 0] {:item (item :furnace) :count 1}
   [:hotbar 1] {:item (item :raw_iron) :count 4}
   [:hotbar 2] {:item (item :coal) :count 1}})

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
  (let [[w sim] (smelt-run {[:hotbar 0] {:item (item :furnace) :count 1}
                            [:hotbar 1] {:item (item :raw_iron) :count 2}
                            [:hotbar 2] {:item (item :birch_planks) :count 1}
                            [:hotbar 3] {:item (item :oak_planks) :count 1}}
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
                                    :inventory {[:hotbar 0] {:item (item :stone_pickaxe)
                                                             :count 1}}})
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
                                    :inventory {[:hotbar 0] {:item (item :stone_pickaxe)
                                                             :count 1}}})
                         #(or (plan/done? %) (plan/failed? %))
                         120000
                         {:event/kind :go :go/goals [:ore-test]})]
    (is (plan/done? w) (pr-str (plan/summary w)))
    (is (= 2 (get (held w) :raw_iron 0)))
    (is (= #{[5 65 9] [9 66 8]} (:sim/broken sim)) "the two ores, nothing else")
    (is (not-any? #(= :failed (:intention/event %)) (memory/intentions w)))
    (is (empty? (:sim/violations sim)))))

(def gravel-floor
  "The tunnel of ore-corridor with no ore, and gravel in its floor at x 9, stone under it."
  (merge (into {} (for [x (range 16) y (range 64 71) z (range 16)] [[x y z] 1]))
         (into {} (for [x (range 2 13) y [64 65]] [[x y 8] blocks/air]))
         {[9 63 8] (blocks/default-state :gravel)}))

(deftest gravel-in-the-tunnel-floor-is-dug-and-picked-up-from-its-hole
  ;; live run 14 tunnelled for gravel at y 18 over five gravel blocks in its own floor
  (let [table (conj plan/goals
                    {:goal/id :gravel-test
                     :goal/priority 7
                     :goal/target? true
                     :goal/provides {:item/gravel 1}
                     :goal/done? :provided?})
        [w sim] (sim/run (game/compose game/step (partial plan/step-over table))
                         (game/init fx/opts)
                         (sim/init {:column (world/column-bytes gravel-floor)
                                    :spawn [2.5 64.0 8.5]
                                    :inventory {[:hotbar 0] {:item (item :stone_pickaxe) :count 1}}
                                    :seed 4}) ; gravel drops gravel on the first dig
                         #(or (plan/done? %) (plan/failed? %))
                         60000
                         {:event/kind :go :go/goals [:gravel-test]})]
    (is (plan/done? w) (pr-str (plan/summary w)))
    (is (= #{[9 63 8]} (:sim/broken sim)) "the gravel, nothing else")
    (is (not-any? #(= :failed (:intention/event %)) (memory/intentions w)))
    (is (empty? (:sim/violations sim)))))

(deftest ore-is-mined-with-a-pickaxe-from-the-main-inventory
  ;; the mine act puts a stowed pickaxe in hand before it walks to the ore (iron ore by hand drops
  ;; nothing, and the dig chooses from the hotbar)
  (let [table (conj plan/goals
                    {:goal/id :ore-test
                     :goal/priority 7
                     :goal/target? true
                     :goal/provides {:item/raw_iron 1}
                     :goal/done? :provided?})
        junk {:item (item :dirt) :count 1}
        inv (assoc (into {} (for [h (range inventory/hotbar-slots)] [[:hotbar h] junk]))
                   [:main 0]
                   {:item (item :stone_pickaxe) :count 1})
        [w sim] (sim/run (game/compose game/step (partial plan/step-over table))
                         (game/init fx/opts)
                         (sim/init {:column (world/column-bytes ore-field)
                                    :spawn [8.5 64.0 8.5]
                                    :inventory inv})
                         #(or (plan/done? %) (plan/failed? %))
                         120000
                         {:event/kind :go :go/goals [:ore-test]})]
    (is (plan/done? w) (pr-str (plan/summary w)))
    (is (= 1 (get (held w) :raw_iron 0)))
    (is (some #(= :equip (:intention/kind %)) (memory/intentions w)) "put in hand first")
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
                      :inventory {[:hotbar 0] {:item (item :gravel) :count 1}}
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

(def pond-edge
  "A pond's edge the tick after a scoop (sim race 38515210080, world R4/41, issue #95): a bank
   whose top is y 64 west of x 8 (its feet level 65), the pond three deep (y 62-64) from x 9
   east and along the rest of column x 8, and the cell the bucket was filled from, [8 64 8],
   air over the two of water left under it. A body there floats with its feet at 62, two under
   the bank's feet level: no bank in the impulse's band, every cell round the feet water."
  (merge (into {} (for [x (range 8) z (range 16)] [[x 64 z] 1]))
         (into {} (for [x (range 8 16) y [62 63 64] z (range 16)] [[x y z] blocks/water-base]))
         {[8 64 8] blocks/air}))

(deftest a-redig-whose-place-found-no-spot-afloat-gets-ashore-and-places
  ;; issue #95's snapshots, one tick before the stop: the redig's child place already failed
  ;; :no-spot afloat, the plan one failure from its end; the redig takes a retreat instead
  (let [failed {:intent/kind :place
                :intent/item :gravel
                :intent/passage-ok? true
                :intent/status :failed
                :intent/stage :spot
                :intent/reason :no-spot}
        [w sim] (intent-run-holding {:intent/kind :redig
                                     :intent/item :gravel
                                     :intent/drops #{(item :gravel) (item :flint)}
                                     :intent/child failed}
                                    pond-edge
                                    62
                                    {[:hotbar 0] {:item (item :gravel) :count 1}
                                     [:hotbar 1] {:item (item :stone_pickaxe) :count 1}}
                                    180000)]
    (is (= :done (:intent/status (:plan/intent w)))
        (pr-str (select-keys (:plan/intent w) [:intent/status :intent/reason :intent/stage])))
    (is (not (:player/in-water? w)) "ashore")
    (is (= 1 (+ (get (held w) :gravel 0) (get (held w) :flint 0))) "the gravel dug and picked up")
    (is (zero? (:stats/deaths w 0)))
    (is (empty? (:sim/violations sim)))))

(deftest a-redig-afloat-at-a-ponds-edge-gets-ashore-before-it-places
  ;; issue #95 (15 sim runs of R4/41, four real bots): the bucket filled, the bot was afloat in
  ;; the column its source left and the gravel to dig again for flint found no spot, three
  ;; times in half a second, before the trap could preempt; the act now gets ashore first
  (let [sim0 (sim/init {:column (world/column-bytes pond-edge) :spawn [8.5 62.0 8.5]})
        lucky (first (filter #(< (second (sim/roll (assoc sim0 :sim/rng %))) 0.1) (range 100)))
        [w sim] (sim/run (game/compose game/step
                                       (partial plan/step-over
                                                (conj plan/goals
                                                      {:goal/id :flint-test
                                                       :goal/priority 7
                                                       :goal/target? true
                                                       :goal/provides {:item/flint 1}
                                                       :goal/done? :provided?})))
                         (game/init fx/opts)
                         (sim/init {:column (world/column-bytes pond-edge)
                                    :spawn [8.5 62.0 8.5]
                                    :inventory {[:hotbar 0] {:item (item :gravel) :count 1}
                                                [:hotbar 1] {:item (item :stone_pickaxe) :count 1}}
                                    :seed lucky})
                         #(or (plan/done? %) (plan/failed? %))
                         180000
                         {:event/kind :go :go/goals [:flint-test]})
        started (map :intention/kind
                     (filter #(= :started (:intention/event %)) (memory/intentions w)))]
    (is (= :done (:plan/status w)) (pr-str (plan/summary w)))
    (is (= :swim-out (first started)) (pr-str started))
    (is (not-any? #(= :failed (:intention/event %)) (memory/intentions w))
        (pr-str (filter #(= :failed (:intention/event %)) (memory/intentions w))))
    (is (= 1 (get (held w) :flint 0)) "the gravel became flint")
    (is (not (:player/in-water? w)) "ashore")
    (is (zero? (:stats/deaths w 0)))
    (is (empty? (:sim/violations sim)))))

;; ---------------------------------------------------------------- gravel and sand fall

(deftest gravel-over-a-broken-block-falls-into-it
  ;; FallingBlock in the 26.1.2 jar: two ticks after the block under it changed, gravel with a
  ;; free cell under it falls, a column one block at a time, and lands where
  ;; FallingBlockEntity's gravity brings it
  (let [broken (-> (sim/init {:column (world/column-bytes {[1 64 0] stone-id
                                                           [1 65 0] gravel-state
                                                           [1 66 0] gravel-state})
                              :spawn [0.5 64.0 0.5]
                              :inventory {[:hotbar 0] {:item (item :wooden_pickaxe) :count 1}}})
                   (assoc :sim/phase :play :sim/now 1000 :sim/player-pos [0.5 64.0 0.5])
                   (client-sends {:packet/name :set-carried-item :slot 0})
                   (client-sends {:packet/name :player-action
                                  :status (packet/value :dig :start-destroy)
                                  :pos [1 64 0]
                                  :face (packet/value :face :west)
                                  :sequence 1})
                   (sim/step {:sim/kind :tick :sim/now 2200})
                   (client-sends {:packet/name :player-action
                                  :status (packet/value :dig :stop-destroy)
                                  :pos [1 64 0]
                                  :face (packet/value :face :west)
                                  :sequence 2}))
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

(deftest a-climb-out-with-no-pickaxe-is-a-shaft-of-one-dig-a-level
  ;; sim snapshots R2-36-1 and R3-13-1 (issue #135) and R2-45-1 (issue #178): a pickaxe worn out
  ;; deep in the rock, every block of a stair up dug by hand, 30 s a level and a hundred levels
  ;; to the sky. With a filler carried the climb digs the cell over the head and lays a block
  ;; under the feet, a level a dig
  (let [cobblestone {:item (get blocks/items :cobblestone) :count 32}
        [w sim]
        (intent-run-holding
         {:intent/kind :stairs-up :intent/to-y 72 :intent/for :surface :intent/to-height? true}
         pocket
         66
         {[:hotbar 0] cobblestone}
         600000)]
    (is (= :done (:intent/status (:plan/intent w)))
        (pr-str (select-keys (:plan/intent w)
                             [:intent/status :intent/reason :intent/stage :intent/no-shaft])))
    (is (<= 72.0 (second (:player/pos w))) "six levels up")
    (is (not (:intent/no-shaft (:plan/intent w))) "the shaft was never given up")
    (is (every? (fn [[x _ z]] (and (= 8 x) (= 8 z))) (:sim/broken sim))
        "only the column over the head was dug")
    (is (empty? (:sim/violations sim)))))

(deftest a-stair-up-saved-above-its-launch-floor-recovers
  (let [up {:intent/kind :stairs-up
            :intent/to-y 72
            :intent/to-height? true
            :intent/stage :step
            :intent/dir [1 0]
            :intent/cells (stairs/up-cells [8 67 8] [1 0])
            :intent/started-y 67.0
            :intent/started-tick 0}
        [w sim] (intent-run up pocket 66 (get blocks/items :wooden_pickaxe) 120000)]
    (is (= :done (:intent/status (:plan/intent w)))
        (pr-str (select-keys (:plan/intent w) [:intent/status :intent/reason :intent/stage])))
    (is (<= 72.0 (second (:player/pos w))))
    (is (:player/on-ground? w))
    (is (empty? (:sim/violations sim)))))

(deftest a-tunnel-goes-round-what-refuses-it
  ;; live run 8, refused east at y 16, turned and tunnelled back west beside its own tunnel; batch
  ;; iron-a2 run 4 walked its tunnel back and forth for eight minutes. Lava two cells ahead: the
  ;; tunnel steps aside, keeps its heading, and passes it
  (let [lava (first (blocks/states-where (fn [[n]] (= n :lava))))
        blocks (assoc pocket [11 66 8] lava [11 67 8] lava)
        [w sim] (intent-run {:intent/kind :tunnel :intent/dir [1 0] :intent/length 8}
                            blocks
                            66
                            (get blocks/items :wooden_pickaxe)
                            120000)]
    (is (= :done (:intent/status (:plan/intent w)))
        (pr-str (select-keys (:plan/intent w) [:intent/status :intent/reason :intent/side])))
    (is (<= 12.0 (first (:player/pos w))) "past the lava, still heading east")
    (is (= [1 0] (:intent/dir (:plan/intent w))))
    (is (not-any? #(< (first %) 8) (:sim/broken sim)) "nothing dug back west")
    (is (= lava (sim/block-at sim [11 66 8])))
    (is (empty? (:sim/violations sim)))))

(deftest a-tunnel-goes-round-ore-its-pickaxe-cannot-drop
  ;; gym batch iron-a9 run 4's tunnel at y 2 met ore a stone pickaxe cannot drop: the dig failed
  ;; :needs-tool and so did the tunnel, three times in a moment
  (let [gold (blocks/default-state :gold_ore)
        blocks (assoc pocket [11 66 8] gold [11 67 8] gold)
        [w sim] (intent-run {:intent/kind :tunnel :intent/dir [1 0] :intent/length 8}
                            blocks
                            66
                            (get blocks/items :stone_pickaxe)
                            120000)]
    (is (= :done (:intent/status (:plan/intent w)))
        (pr-str (select-keys (:plan/intent w) [:intent/status :intent/reason :intent/side])))
    (is (= gold (sim/block-at sim [11 66 8])) "the ore left where it is")
    (is (empty? (:sim/violations sim)))))

(deftest a-collect-stops-at-a-ledge
  ;; gym batch iron-a9 run 3 dug gravel for flint at a ravine's edge at y -7: the collect walked
  ;; after the drop and the bot fell from a high place. It stops, and the drop is left: it ends
  ;; done with nothing, not failed, since three such drops in a row failed the plan :ledge (sim
  ;; race 38477665389, issue #93, 45 runs at one landing)
  (let [ravine (into {} (for [y (range 50 64) z (range 16)] [[10 y z] blocks/air]))
        [w sim] (intent-run {:intent/kind :collect
                             :intent/target [10 63 8]
                             :intent/drops #{(get blocks/items :gravel)}}
                            ravine
                            64
                            (get blocks/items :stone_pickaxe)
                            20000)]
    (is (= :done (:intent/status (:plan/intent w))) (pr-str (:plan/intent w)))
    (is (<= 64.0 (second (:player/pos w))) "still on the ground beside the ravine")
    (is (zero? (get (held w) :gravel 0)) "nothing picked up: the drop is left")
    (is (empty? (:sim/violations sim)))))

(deftest a-tunnel-with-no-pickaxe-stops-for-one
  ;; batch iron-a2 runs dug stone by hand when the pickaxe broke, 7500 ms a block for nothing,
  ;; until a step timed out; with no pickaxe the tunnel stops at once and the planner makes one
  (let [[w sim]
        (intent-run {:intent/kind :tunnel :intent/dir [1 0] :intent/length 4} pocket 66 nil 30000)]
    (is (= :failed (:intent/status (:plan/intent w))))
    (is (= :no-pickaxe (:intent/reason (:plan/intent w))))
    (is (empty? (:sim/broken sim)) "nothing dug by hand")
    (is (< (:time/now w) 2000))))

(deftest stairs-down-to-trees-with-no-pickaxe-are-cut-by-hand
  (doseq [purpose [:trees :lava nil]]
    (let [i (cond-> {:intent/kind :stairs-down :intent/to-y 71} purpose (assoc :intent/for purpose))
          [w sim] (intent-run i deep-stone 76 nil 180000)]
      (cond (= :trees purpose)
            (do (is (= :done (:intent/status (:plan/intent w))))
                (is (<= (second (:player/pos w)) 71.5))
                (is (seq (:sim/broken sim))))
            :else
            (do (is (= :no-pickaxe (:intent/reason (:plan/intent w))))
                (is (empty? (:sim/broken sim)))))
      (is (empty? (:sim/violations sim))))))

(deftest stairs-up-with-no-pickaxe-are-cut-by-hand
  ;; race 38477665442 Clj_r21 (issue #40): the pickaxe broke at y 60 in the iron mine, the stairs
  ;; up stopped :no-pickaxe, the sticks for another were in the trees up top, and twelve walks
  ;; to them failed :no-path. The way out is cut by hand, 7500 ms a block of stone
  (let [[w sim] (staircase-run :stairs-up pocket 66 nil 72 600000)]
    (is (= :done (:intent/status (:plan/intent w)))
        (pr-str (select-keys (:plan/intent w)
                             [:intent/status :intent/reason :intent/stage :intent/turns])))
    (is (<= 72.0 (second (:player/pos w))) "six stairs up, by hand")
    (is (seq (:sim/broken sim)) "stone dug by hand")
    (is (empty? (:sim/violations sim)))))

(deftest stairs-up-walk-out-from-under-water
  ;; live run 9 cut stairs up from y 22 to y 51 and met water over every stair; its level steps went
  ;; round a square of four cells until it failed :boxed. Water seven wide over the pocket: the
  ;; level steps keep their way until a stair up is allowed, then climb
  (let [blocks (merge pocket
                      (into {} (for [x (range 5 12) z (range 5 12)] [[x 69 z] blocks/water-base])))
        [w sim] (staircase-run :stairs-up blocks 66 (get blocks/items :wooden_pickaxe) 72 120000)]
    (is (= :done (:intent/status (:plan/intent w)))
        (pr-str (select-keys (:plan/intent w)
                             [:intent/status :intent/reason :intent/stage :intent/level-steps])))
    (is (<= 72.0 (second (:player/pos w))))
    (is (not-any? #(= :water
                      (some-> (sim/block-at sim %)
                              blocks/fluid
                              :fluid/kind))
                  (for [x (range 4 13) y [67 68] z (range 4 13)] [x y z]))
        "no water came down")
    (is (empty? (:sim/violations sim)))))

(def lake-over-the-pocket
  "pocket under a lake: from x 7 east, sand at y 69 and water over it to the hilltop; dry stone
   west of it."
  (merge pocket
         (into {} (for [x (range 7 16) z (range 16)] [[x 69 z] (blocks/default-state :sand)]))
         (into {} (for [x (range 7 16) y (range 70 76) z (range 16)] [[x y z] blocks/water-base]))))

(deftest stairs-up-under-a-lake-step-toward-the-shore
  ;; gym batch iron-a7 run 1 climbed to y 44 under a lake: sand, then water to y 63, over every
  ;; stair; its level steps went 32 east under the lake and failed :boxed. The shore is west
  (let [[w sim] (staircase-run :stairs-up lake-over-the-pocket
                               66 (get blocks/items :stone_pickaxe)
                               72 120000)]
    (is (= :done (:intent/status (:plan/intent w)))
        (pr-str (select-keys (:plan/intent w)
                             [:intent/status :intent/reason :intent/stage :intent/level-steps])))
    (is (<= 72.0 (second (:player/pos w))))
    (is (not-any? #(<= 9 (first %)) (:sim/broken sim)) "nothing dug east, under the lake")
    (is (empty? (:sim/violations sim)))))

(def aquifer-over-the-pocket
  "The pocket under an aquifer: stone over the head to y 68, water at y 69 and 70 from x 0 to
   x 9 across the hill, and stone from y 71 to the hilltop over the water, so the top of every
   column is dry ground. East of x 9 the rock is dry."
  (merge pocket (into {} (for [x (range 10) y [69 70] z (range 16)] [[x y z] blocks/water-base]))))

(deftest stairs-up-under-an-aquifer-step-out-from-under-it
  ;; issue #61 (R3 world 6, 104 sim runs a round): a climb for water from y 37 stood under an
  ;; aquifer whose floor was two blocks over its head; every stair up was refused :unsafe for
  ;; the water over the cell it opened, and the level steps, the top of every column dry, went
  ;; north under the aquifer until :boxed. The sheet ended three columns south. Here it ends two
  ;; columns east, and north, south and west it runs to the edge of the loaded world
  (let [[w sim] (staircase-run :stairs-up aquifer-over-the-pocket
                               66 (get blocks/items :stone_pickaxe)
                               70 120000)]
    (is (= :done (:intent/status (:plan/intent w)))
        (pr-str (select-keys (:plan/intent w)
                             [:intent/status :intent/reason :intent/stage :intent/level-steps])))
    (is (<= 70.0 (second (:player/pos w))))
    (is (every? (fn [[x _ z]] (and (<= 8 x) (= 8 z))) (:sim/broken sim))
        "nothing dug north, south or west, under the aquifer")
    (is (not-any? #(= :water
                      (some-> (sim/block-at sim %)
                              blocks/fluid
                              :fluid/kind))
                  (for [x (range 16) y (range 64 69) z (range 16)] [x y z]))
        "no water came down")
    (is (empty? (:sim/violations sim)))))

(def cave-under-the-roof
  "deep-stone with a cave two high at y 66 and 67 across the hill: the bot stands on its floor
   at y 65, and no stair up from it has its step."
  (merge deep-stone (into {} (for [x (range 16) y [66 67] z (range 16)] [[x y z] blocks/air]))))

(deftest stairs-up-lay-their-step-across-a-cave
  ;; issue #61: the same climb walked thirty level steps along its own mine, a floor laid under
  ;; each, since every stair up from the mine had no step under its new feet, and was :boxed at
  ;; the level-step limit. A stair up lays its step as a stair down lays its floor
  (let [pickaxe {:item (get blocks/items :stone_pickaxe) :count 1}
        cobblestone {:item (get blocks/items :cobblestone) :count 16}
        [w sim] (intent-run-holding {:intent/kind :stairs-up :intent/to-y 72}
                                    cave-under-the-roof
                                    66
                                    {[:hotbar 0] pickaxe [:hotbar 1] cobblestone}
                                    120000)]
    (is (= :done (:intent/status (:plan/intent w)))
        (pr-str (select-keys (:plan/intent w)
                             [:intent/status :intent/reason :intent/stage :intent/level-steps])))
    (is (<= 72.0 (second (:player/pos w))))
    (is (<= 2 (count (:sim/placed sim))) "the steps across the cave were laid")
    (is (< (:intent/level-steps (:plan/intent w) 0) 4) "climbed, not walked")
    (is (empty? (:sim/violations sim)))))

(deftest stairs-up-build-supported-steps-from-a-ledge
  (let [[w sim] (intent-run-holding
                 {:intent/kind :stairs-up :intent/to-y 72 :intent/to-height? true}
                 {[8 65 8] 1}
                 66
                 {[:hotbar 0] {:item (item :cobblestone) :count 16}}
                 30000)]
    (is (= :done (:intent/status (:plan/intent w))) (pr-str (:plan/intent w)))
    (is (<= 72.0 (second (:player/pos w))))
    (is (:player/on-ground? w))
    (is (= 12 (count (:sim/placed sim))) "a holder and a step for each rise")
    (is (empty? (:sim/broken sim)) "no ducking down through its own steps")
    (is (empty? (:sim/violations sim)))))

(def aquifer-round-the-pocket
  "pocket with a water source beside every cell the first stair up each way would open: at
   y 67 and 68, two blocks out from the shaft along each axis."
  (reduce (fn [b [x z]] (assoc b [x 67 z] blocks/water-base [x 68 z] blocks/water-base))
          pocket
          [[10 8] [6 8] [8 10] [8 6]]))

(deftest stairs-up-through-an-aquifer-are-sealed-with-cobblestone
  ;; race 38477665442 runner 8 bot Clj_r01 (issue #54): underground with its bucket, water
  ;; beside every stair cell of the climb out, each child dig refused :unsafe, the staircase
  ;; :boxed twice, and twelve walks to the water beside it failed :no-path. The water beside a
  ;; stair is sealed with carried cobblestone first, then the stair is cut as any other
  (let [[w sim] (intent-run-holding {:intent/kind :stairs-up :intent/to-y 72}
                                    aquifer-round-the-pocket
                                    66
                                    {[:hotbar 0] {:item (item :stone_pickaxe) :count 1}
                                     [:hotbar 1] {:item (item :cobblestone) :count 64}}
                                    120000)]
    (is (= :done (:intent/status (:plan/intent w)))
        (pr-str (select-keys (:plan/intent w)
                             [:intent/status :intent/reason :intent/stage :intent/turns
                              :intent/level-steps])))
    (is (<= 72.0 (second (:player/pos w))) "six stairs up, out of the pocket")
    (is (not (:player/in-water? w)) "no water came down the shaft")
    (is (empty? (:sim/violations sim)))))

(deftest the-table-is-taken-along-when-the-bot-goes-out
  ;; gym batch iron-a7 run 1 crafted three tables on its way down, a log each, and ran out of wood
  ;; for a pickaxe; a table left behind is a log spent on the next one
  (let [table (conj plan/goals
                    {:goal/id :table-test
                     :goal/priority 7
                     :goal/target? true
                     :goal/provides {:item/cobblestone 2}
                     :goal/done? :provided?})
        [w sim] (sim/run (game/compose game/step (partial plan/step-over table))
                         (game/init fx/opts)
                         (sim/init {:column (world/column-bytes deep-stone)
                                    :spawn [8.5 76.0 8.5]
                                    :inventory {[:hotbar 0] {:item (item :crafting_table) :count 1}
                                                [:hotbar 1] {:item (item :oak_planks) :count 3}
                                                [:hotbar 2] {:item (item :stick) :count 2}}})
                         #(or (plan/done? %) (plan/failed? %))
                         120000
                         {:event/kind :go :go/goals [:table-test]})]
    (is (plan/done? w) (pr-str (plan/summary w)))
    (is (= 1 (get (held w) :crafting_table 0)) "the table placed for the pickaxe, carried on")
    (is (not-any? #(= :failed (:intention/event %)) (memory/intentions w)))
    (is (empty? (:sim/violations sim)))))

(deftest a-pickaxe-in-the-main-inventory-is-put-in-hand
  ;; gym batch iron-a3 runs 1 and 3 held a stone pickaxe in the main inventory (the hotbar was full
  ;; of what mining picked up) and their stairs stopped :no-pickaxe; the dig chooses from the
  ;; hotbar, so the pickaxe is swapped into the hand first
  (let [junk {:item (item :dirt) :count 1}
        inv (assoc (into {} (for [h (range inventory/hotbar-slots)] [[:hotbar h] junk]))
                   [:main 0]
                   {:item (item :stone_pickaxe) :count 1})
        [w sim]
        (sim/run
         (fn [w e]
           (let [w (game/step w e)
                 i (:plan/intent w)]
             (cond (= :go (:event/kind e))
                   (assoc w
                          :plan/intent
                          {:intent/kind :stairs-down :intent/to-y 74 :intent/status :active})
                   (and (= :tick (:event/kind e))
                        (= :active (:intent/status i))
                        (:player/loaded? w))
                   (intent/run w i e)
                   :else w)))
         (game/init fx/opts)
         (sim/init {:column (world/column-bytes deep-stone) :spawn [8.5 76.0 8.5] :inventory inv})
         #(contains? #{:done :failed} (:intent/status (:plan/intent %)))
         60000)]
    (is (= :done (:intent/status (:plan/intent w)))
        (pr-str (select-keys (:plan/intent w) [:intent/status :intent/reason :intent/stage])))
    (is (= (item :stone_pickaxe) (get-in w [:player/inventory (:player/held-slot w) :item]))
        "the pickaxe is in the hand")
    (is (< (:time/now w) 20000) "dug with it, not by hand")
    (is (empty? (:sim/violations sim)))))

(deftest stairs-up-end-on-the-ground-not-in-a-shaft
  ;; live run 11's stairs up stopped under the open sky two below the ground, in a shaft one wide,
  ;; and no route led out to water. A shaft open to the sky from the pocket to the hilltop: the
  ;; stairs go on until a way on needs no digging
  (let [shaft (merge pocket (into {} (for [y (range 68 76)] [[8 y 8] blocks/air])))
        [w sim] (staircase-run :stairs-up shaft 66 (get blocks/items :wooden_pickaxe) 90 120000)]
    (is (= :done (:intent/status (:plan/intent w)))
        (pr-str (select-keys (:plan/intent w) [:intent/status :intent/reason :intent/stage])))
    (is (<= 75.0 (second (:player/pos w))) "up to the hilltop, out of the shaft")
    (is (empty? (:sim/violations sim)))))

(def open-pit
  "A plateau at y 70 with a pit three wide down to y 64 at its middle, open to the sky: a pit
   with a wall every way (stairs/pit-rim), which the stairs up climb out of."
  (into {}
        (for [x (range 16)
              y (range 64 70)
              z (range 16)
              :when (not (and (<= 7 x 9) (<= 7 z 9)))]
          [[x y z] 1])))

(deftest a-climb-from-a-pit-ends-at-its-height-not-at-the-open-sky
  ;; issue #60: the floor of a ravine under the sky was out? the tick the stairs started, so a
  ;; climb for water says :intent/to-height? and ends at its height alone. Since issues #102 and
  ;; #103 a pit three wide is no longer out (stairs/pit-rim: a wall every way), and a plain climb
  ;; leaves it too
  (let [pickaxe (get blocks/items :wooden_pickaxe)
        [plain _] (staircase-run :stairs-up open-pit 64 pickaxe 70 120000)
        [w sim] (intent-run {:intent/kind :stairs-up :intent/to-y 70 :intent/to-height? true}
                            open-pit
                            64
                            pickaxe
                            120000)]
    (is (= :done (:intent/status (:plan/intent plain))))
    (is (<= 70 (second (intent/feet plain))) "a plain climb leaves the pit: its floor is not out")
    (is (= :done (:intent/status (:plan/intent w)))
        (pr-str (select-keys (:plan/intent w) [:intent/status :intent/reason :intent/stage])))
    (is (<= 70 (second (intent/feet w))) "to the plateau")
    (is (empty? (:sim/violations sim)))))

(def quarry
  "A quarry two wide and two deep in the stone, open to the sky, as the stone mine digs round its
   feet: the floor at y 62 under [8 8] and [9 8], and a trunk on the ground four blocks east."
  {[8 62 8] blocks/air
   [8 63 8] blocks/air
   [9 62 8] blocks/air
   [9 63 8] blocks/air
   [12 64 8] 136
   [12 65 8] 136
   [12 66 8] 252})

(deftest a-log-is-gathered-out-of-a-quarry
  ;; race 38515209959 (issues #102 and #103, 30 real bots) and the sim race in worlds R3/36 and
  ;; R3/32: at the bottom of the quarry the stone mine dug round its feet, the sky over it and
  ;; a level step across its floor, the bot cut nothing and waited :no-log for the table's log
  ;; with the trees seven blocks off. The quarry is a pit: stairs are cut up out of it by hand,
  ;; then the trunk is walked to, with no walk failed :no-path on the way
  (let [[w sim] (sim/run step
                         (game/init fx/opts)
                         (sim/init {:column (world/column-bytes quarry) :spawn [8.5 62.0 8.5]})
                         #(or (plan/done? %) (plan/failed? %))
                         120000
                         {:event/kind :go :go/goals [:wood]})]
    (is (plan/done? w) (pr-str (plan/summary w)))
    (is (= 1 (inventory/logs-held w)))
    (is (zero? (:plan/unwalkable w)) "no walk failed :no-path")
    (is (zero? (:plan/attempts w)) "no failed attempts")
    (is (some (:sim/broken sim) [[8 63 7] [8 63 9] [7 63 8] [9 63 7] [9 63 9] [10 63 8]])
        "it cut a stair up through the quarry's wall")
    (is (empty? (:sim/violations sim)))))

(deftest a-tunnel-never-cuts-under-gravel-under-water
  ;; gym batch iron-a4 run 1 tunnelled under a riverbed: the gravel over a cell it cut fell into
  ;; it and the river followed. Gravel with water on it over the first cell ahead: refused
  (let [blocks (assoc pocket [9 68 8] gravel-state [9 69 8] blocks/water-base)
        [w sim] (intent-run {:intent/kind :tunnel :intent/dir [1 0] :intent/length 4}
                            blocks
                            66
                            (get blocks/items :wooden_pickaxe)
                            60000)]
    (is (= :done (:intent/status (:plan/intent w)))
        (pr-str (select-keys (:plan/intent w) [:intent/status :intent/reason :intent/side])))
    (is (not (contains? (:sim/broken sim) [9 67 8])) "the cell under the gravel never cut")
    (is (= :gravel (blocks/name-of (sim/block-at sim [9 68 8]))))
    (is (empty? (:sim/violations sim)))))

(deftest a-retreat-out-of-water-ends-on-dry-land
  ;; gym batch iron-a6 run 3 stood in its flooded tunnel with every tunnel and stair refused; a
  ;; swim-out for a retreat, unlike an escape's, ends itself once the feet are on dry land
  (let [[w sim] (intent-run {:intent/kind :swim-out :intent/for :retreat}
                            {[8 63 8] blocks/water-base}
                            63
                            nil
                            60000)]
    (is (= :done (:intent/status (:plan/intent w)))
        (pr-str (select-keys (:plan/intent w) [:intent/status :intent/reason :intent/bank])))
    (is (not (:player/in-water? w)))
    (is (:player/on-ground? w))
    (is (empty? (:sim/violations sim)))))

(def scooped-shore
  "A pond's edge after a scoop (sim race 38477665389, world R2/23, issue #92): a bank whose top
   is y 64 west of x 8 (its feet level 65), the pond two deep (y 63-64) from x 8 east, and the
   cell the bucket was filled from, [8 64 8], air over the one of water left under it. A body
   there stands in water one deep with its eye in air: no trap, and the bank two over its feet,
   beyond the impulse's band."
  (merge (into {} (for [x (range 8) z (range 16)] [[x 64 z] 1]))
         (into {} (for [x (range 8 16) y [63 64] z (range 16)] [[x y z] blocks/water-base]))
         {[8 64 8] blocks/air}))

(deftest a-retreat-from-a-scooped-ponds-edge-notches-the-bank
  ;; issue #92 (48 sim runs of R2/23 at 1d31d21): the bucket filled from the pond's edge, the
  ;; mine for the portal's cobblestone had nothing to cut from the water and retreated by a
  ;; swim-out, which found no bank in its band (the shore two over the feet), no cap, and held
  ;; jump until it timed out, three times, :no-escape. The notch (issue #73) cuts the bank
  (let [[w sim] (intent-run {:intent/kind :swim-out :intent/for :retreat}
                            scooped-shore
                            63
                            (item :stone_pickaxe)
                            120000)]
    (is (= :done (:intent/status (:plan/intent w)))
        (pr-str (select-keys (:plan/intent w) [:intent/status :intent/reason :intent/bank])))
    (is (not (:player/in-water? w)))
    (is (:player/on-ground? w))
    (is (= 0 (terrain/block-at w [7 64 8])) "the bank is notched")
    (is (<= (first (intent/feet w)) 7) "on the bank")
    (is (zero? (:stats/deaths w 0)))
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

(deftest a-climb-steps-out-from-under-a-bed-of-gravel-not-along-it
  ;; race 38477665442 bot Clj_r04 (issue #43): under a bed of gravel at y 42 every stair up was
  ;; refused from each cell in turn, and the climb stepped thirty cells along the bed, two
  ;; blocks dug a step, until its last pickaxe broke. A bed over the pocket east and west: the
  ;; first level step goes north or south, where the next cell is not under gravel, and climbs
  (let [blocks (merge pocket (into {} (for [x (range 16)] [[x 69 8] gravel-state])))
        [w sim] (staircase-run :stairs-up blocks 66 (get blocks/items :wooden_pickaxe) 70 120000)]
    (is (= :done (:intent/status (:plan/intent w)))
        (pr-str (select-keys (:plan/intent w)
                             [:intent/status :intent/reason :intent/stage :intent/level-steps])))
    (is (<= 70.0 (second (:player/pos w))))
    (is (<= (:intent/level-steps (:plan/intent w) 0) 2)
        "out from under the bed in a step, not along it")
    (is (not-any? (fn [[x _ z]] (and (= 8 z) (not= 8 x))) (:sim/broken sim))
        "no cell along the bed was opened")
    (is (empty? (:sim/violations sim)))))

;; ---------------------------------------------------------------- a bucket of water

(def bank-pond
  "A pond of nine water sources sunk in the floor at x 11-13, z 7-9; the spawn is on the bank."
  (into {} (for [x (range 11 14) z (range 7 10)] [[x 63 z] blocks/water-base])))

(deftest the-models-bucket-ray-stops-at-a-plant
  ;; ClipContext.Block.OUTLINE: the game's ray for a bucket stops at the first block with an
  ;; outline, sugar cane on the bank as much as stone (issue #83)
  (let [cane (blocks/default-state :sugar_cane)
        pos [9.5 64.0 8.5]
        eye [9.5 65.62 8.5]
        look (physics/look-at eye (scoop/aim-point [11 63 8]))
        model
        (fn [blocks]
          (assoc (sim/init {:column (world/column-bytes blocks) :spawn pos}) :sim/player-pos pos))]
    (is (= [11 63 8] (sim/scooped (model bank-pond) look)) "the near source, from the bank")
    (is (nil? (sim/scooped (model (assoc bank-pond [10 64 8] cane)) look)) "through cane: nothing")
    (is (nil? (sim/scooped (model (assoc bank-pond [11 64 8] cane)) look))
        "cane over it: nothing")))

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
                                    :inventory {[:hotbar 0] {:item (item :wooden_pickaxe) :count 1}
                                                [:hotbar 1] {:item (item :wooden_pickaxe) :count 1}
                                                [:hotbar 4] {:item (item :bucket) :count 1}}})
                         ;; a step into the pond ends the run, so done means never in it
                         #(or (plan/done? %) (plan/failed? %) (:player/in-water? %))
                         60000
                         {:event/kind :go :go/goals [:bucket-test]})]
    (is (= :done (:plan/status w)) (pr-str (plan/summary w)))
    (is (= {:water_bucket 1 :wooden_pickaxe 2} (held w))
        "the bucket in hotbar slot 4 was selected and filled")
    (is (= 1 (count (filter #(= blocks/air %) (vals (:sim/placed sim))))) "one source taken")
    (is (not (:player/in-water? w)) "never in the pond")
    (is (not-any? #(= :failed (:intention/event %)) (memory/intentions w)))
    (is (empty? (:sim/violations sim)))))

(deftest from-a-pit-below-the-water-the-bot-climbs-to-the-bank
  ;; live run 10 came up its stairs into a pit two below a pond's surface: the water was in reach
  ;; but behind the pit's wall, no walk was taken to water in reach, and it waited :no-water
  (let [pit {[11 63 8] blocks/water-base ; one source, in reach of the pit
             [9 62 8] blocks/air
             [9 63 8] blocks/air
             [8 63 8] blocks/air}
        [w sim] (sim/run (game/compose game/step
                                       (partial plan/step-over
                                                (conj plan/goals
                                                      {:goal/id :bucket-test
                                                       :goal/priority 7
                                                       :goal/target? true
                                                       :goal/provides {:item/water_bucket 1}
                                                       :goal/done? :provided?})))
                         (game/init fx/opts)
                         (sim/init {:column (world/column-bytes pit)
                                    :spawn [9.5 62.0 8.5]
                                    :inventory {[:hotbar 0] {:item (item :wooden_pickaxe) :count 1}
                                                [:hotbar 1] {:item (item :wooden_pickaxe) :count 1}
                                                [:hotbar 4] {:item (item :bucket) :count 1}}})
                         #(or (plan/done? %) (plan/failed? %) (:player/in-water? %))
                         60000
                         {:event/kind :go :go/goals [:bucket-test]})]
    (is (= :done (:plan/status w)) (pr-str (plan/summary w)))
    (is (= 1 (:water_bucket (held w))))
    (is (not (:player/in-water? w)))
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
   cobblestone and eight logs (twenty-four sticks are three of them)."
  {[:hotbar 0] {:item (item :stone_pickaxe) :count 1}
   [:hotbar 1] {:item (item :crafting_table) :count 1}
   [:main 0] {:item (item :cobblestone) :count 8}
   [:main 1] {:item (item :oak_log) :count 8}})

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
                                    :inventory {[:hotbar 0] {:item (get blocks/items
                                                                        :wooden_pickaxe)
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

(deftest a-respawned-player-can-equip-after-dying-with-a-furnace-open
  ;; issue #323, sim race 39004332220 R2/2: the old furnace menu survived the respawn, so the
  ;; model ignored every window-0 swap even though the bot correctly forgot the open menu
  (let [pickaxe {:item (item :stone_pickaxe) :count 1}
        pos [1 64 0]
        furnace {:slots {0 {:item (item :raw_iron) :count 3}}}
        dead (-> (sim/init {:column (world/column-bytes {})
                            :spawn [0.5 64.0 0.5]
                            :inventory {[:main 0] pickaxe}})
                 (assoc :sim/phase :play
                        :sim/health 0.0
                        :sim/window {:id 10 :kind :furnace :state-id 7 :pos pos}
                        :sim/furnaces {pos furnace}))
        respawn {:packet/name :client-command
                 :action (packet/value :client-command :perform-respawn)}
        alive (sim/on-packet dead respawn)
        equipped (sim/on-packet alive
                                (merge {:packet/name :container-click
                                        :window-id 0
                                        :state-id (:sim/state-id alive)
                                        :changed []
                                        :cursor nil}
                                       (wire (inventory/swap-click (inventory/slot [:main 0]) 0))))]
    (is (nil? (:sim/window alive)) "the old life has no menu in the new one")
    (is (= (:sim/inv dead) (:sim/inv alive)) "keep_inventory still holds")
    (is (= furnace (get-in alive [:sim/furnaces pos])) "the furnace keeps its input")
    (is (= pickaxe (get-in equipped [:sim/inv (inventory/slot [:hotbar 0])])))
    (is (empty? (:sim/violations equipped)) "window 0 accepts the swap")
    (is (= dead (sim/on-packet dead (assoc respawn :action 1))) "another command is not a respawn")
    (let [living (assoc dead :sim/health sim/max-health)]
      (is (= living (sim/on-packet living respawn)) "a living player's menu stays open"))))

(deftest a-respawned-bot-is-told-the-mobs-about-it-again
  ;; issue #62, sim race 38900840841: the client forgets every entity at a respawn and the model
  ;; never told it the mobs again, so a bot that died on a stair never saw the zombie that
  ;; followed it down and died there again and again
  (let [[w _] (sim/run
               step
               (game/init fx/opts)
               (sim/init {:column (world/column-bytes well)
                          :spawn [0.5 64.0 0.5]
                          :respawn [2.5 64.0 0.5]
                          :mobs [{:type :zombie :pos [6.5 64.0 6.5]}]})
               #(and (pos? (:stats/deaths % 0)) (not (:player/dead? %)) (:player/on-ground? %))
               40000
               {:event/kind :go :go/goals []})]
    (is (= 1 (:stats/deaths w)) "died once")
    (is (= 1 (count (entity/hostiles w))) "and knows the zombie again after the respawn")))

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

(deftest a-pond-with-seagrass-is-left-for-a-dry-bank
  (let [cells
        (into {} (for [x (range 6 11) z (range 6 11)] [[x 63 z] (blocks/default-state :seagrass)]))
        [w sim] (intent-run {:intent/kind :swim-out :intent/for :retreat} cells 63 nil 30000)]
    (is (= :done (:intent/status (:plan/intent w))) (pr-str (:plan/intent w)))
    (is (zero? (:stats/deaths w 0)))
    (is (trap/dry-land? w))
    (is (>= (second (:player/pos w)) 64.0) "on the shore, not the submerged plants")
    (is (empty? (:sim/violations sim)))))

(deftest a-rise-enters-the-water-above-an-opened-seagrass-shaft
  (let [cells
        (merge
         (into {} (for [x (range 6 11) z (range 6 11) y (range 56 64)] [[x y z] blocks/water-base]))
         (into {} (for [y (range 52 55)] [[8 y 8] blocks/air]))
         {[8 56 8] (blocks/default-state :tall_seagrass)})
        model (-> (sim/init {:column (world/column-bytes cells) :spawn [8.5 52.0 8.5]})
                  (sim/set-cell [8 55 8] blocks/air))
        step (fn [w e]
               (let [w (game/step w e)
                     i (:plan/intent w)]
                 (cond (= :go (:event/kind e))
                       (assoc w
                              :plan/intent
                              {:intent/kind :swim-out
                               :intent/for :rise
                               :intent/to-y 64
                               :intent/status :active})
                       (and (= :tick (:event/kind e)) i (:player/loaded? w)) (intent/run w i e)
                       :else w)))
        [w model] (sim/run step
                           (game/init fx/opts)
                           model
                           #(or (intent/done? (:plan/intent %))
                                (intent/failed? (:plan/intent %))
                                (pos? (:stats/deaths % 0)))
                           30000)]
    (is (intent/done? (:plan/intent w)) (pr-str (:plan/intent w)))
    (is (zero? (:stats/deaths w 0)))
    (is (trap/dry-land? w))
    (is (>= (second (:player/pos w)) 64.0))
    (is (empty? (:sim/violations model)))))

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
                              {:inventory {[:hotbar 0]
                                           {:item wooden-pickaxe :count 1 :durability 1}}})]
    (is (= [:water] (map :trap/kind (:world/traps w))) (pr-str (plan/summary w)))
    (is (zero? (:stats/deaths w 0)))
    (is (trap/dry-land? w))
    (is (>= (second (:player/pos w)) 64.0) "up on the surface")
    (is (= 0 (terrain/block-at w [0 64 0])) "the cap is gone")
    (is (empty? (:sim/violations sim)))))

(def flooded-cave
  "An aquifer's lake in the rock: a pool five by five and three deep (y 30-32) under three
   cells of air (y 33-35) and a stone roof, its walls rising straight from the water. No cell
   within sixteen is standable: the air over the pool has water under it, the rest is stone."
  (merge (into {} (for [x (range 2 7) y [30 31 32] z (range 2 7)] [[x y z] blocks/water-base]))
         (into {} (for [x (range 2 7) y [33 34 35] z (range 2 7)] [[x y z] 0]))))

(deftest a-flooded-cave-with-no-bank-is-left-by-a-notch-dug-in-its-wall
  ;; issue #73 (sim race, world R4/44, 217 runs): afloat with the eye in air, no bank in the
  ;; band and no cap over the head, the escape held jump and searched again every second
  ;; until it timed out, three times, :no-escape; now it notches the nearest wall
  (let [[w sim _] (escape-run flooded-cave
                              [4.5 30.0 4.5]
                              120000
                              {:inventory {[:hotbar 0]
                                           {:item wooden-pickaxe :count 1 :durability 59}}})]
    (is (= [:water] (map :trap/kind (:world/traps w))) (pr-str (plan/summary w)))
    (is (zero? (:stats/deaths w 0)))
    (is (trap/dry-land? w))
    (is (= [1 4] (let [[x _ z] (path/feet-cell (:player/pos w))] [x z]))
        "out of the pool, in the notch's column")
    (is (= [0 0] (map #(terrain/block-at w %) [[1 33 4] [1 34 4]]))
        "the notch is two cells over the wall's top")
    (is (empty? (:sim/violations sim)))))

(def frozen-sea
  "A sea frozen over: water three deep (y 60-62) on the stone, ice at y 63 over all of it and
   air above; no bank anywhere but on the ice."
  (merge (into {} (for [x (range 16) y [60 61 62] z (range 16)] [[x y z] blocks/water-base]))
         (into {} (for [x (range 16) z (range 16)] [[x 63 z] (blocks/default-state :ice)]))))

(deftest under-a-frozen-sea-the-bot-digs-out-through-the-ice-and-stands-on-it
  ;; issue #177 (sim race 38806318231, world R2/43, 83 runs): come up under a frozen sea, the
  ;; bot dug the ice over its head, which broke to air in the model (a server leaves water),
  ;; floated a block under the hole's rim with no bank in the band (a route stands on no ice)
  ;; and pressed toward a far shore under the ice until it timed out, three times, :no-escape
  (let [[w sim _] (escape-run frozen-sea
                              [8.5 61.0 8.5]
                              90000
                              {:inventory {[:hotbar 0]
                                           {:item wooden-pickaxe :count 1 :durability 59}}})]
    (is (= [:water] (map :trap/kind (:world/traps w))) (pr-str (plan/summary w)))
    (is (zero? (:stats/deaths w 0)))
    (is (trap/dry-land? w))
    (is (= :ice
           (let [[x y z] (path/feet-cell (:player/pos w))]
             (blocks/name-of (terrain/block-at w [x (dec y) z]))))
        "standing on the ice")
    (is (= :water (:fluid/kind (blocks/fluid (sim/block-at sim [8 63 8]))))
        "the ice dug over the head left water, as on a server")
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

;; ---------------------------------------------------------------- the portal through the planner (issue #8)

(defn nether-run
  "The planner against the model toward the Nether (or goals) from the kit, a lava trench beside
   the arena, the frame's site chosen by the bot; sim-opts adds faults. Returns [world sim]."
  ([step sim-opts] (nether-run step sim-opts [:nether]))
  ([step sim-opts goals]
   (sim/run step
            (game/init fx/opts)
            (sim/init (merge
                       {:column (world/column-bytes lava-pit) :spawn [9.5 64.0 10.5] :inventory kit}
                       sim-opts))
            #(or (plan/done? %) (plan/failed? %))
            1800000
            {:event/kind :go :go/goals goals})))

(deftest the-portal-from-a-kit-and-its-recording-replays
  (let [path (str (System/getProperty "java.io.tmpdir") "/clojurecraft-portal.edn")
        tap (record/tap! path)
        [w sim] (nether-run (recording step tap) {})]
    ((:close tap))
    (is (= :done (:plan/status w)) (pr-str (plan/summary w)))
    (is (= "minecraft:the_nether" (:player/dimension w)))
    (is (some? (memory/site w)) "the site is remembered")
    (is (not-any? #(= :failed (:intention/event %)) (memory/intentions w)))
    (is (empty? (:sim/violations sim)))
    (testing "the recording replays with every sent packet the same"
      (is (nil? (record/verify step (game/init fx/opts) path))))))

(deftest a-death-mid-cast-resumes-at-the-first-op-not-done
  ;; the build stores no progress: after a death the planner starts it again at the site it
  ;; remembers, and the first op the world says is not done is the one worked. The new life
  ;; begins off the frame (the site was chosen at the landing, and vanilla never respawns a
  ;; player inside blocks), so it walks back to the site
  (let [[w sim] (nether-run step {:kill-at 120000 :respawn [12.5 64.0 14.5]})]
    (is (= :done (:plan/status w)) (pr-str (plan/summary w)))
    (is (= 1 (:stats/deaths w)) "it died once")
    (is (= "minecraft:the_nether" (:player/dimension w)))
    (is (= 3 (count (filter #(= blocks/lava-base (sim/block-at sim %)) (keys lava-pit))))
        "nine lava scooped, as without the death: no cell cast twice")
    (is (empty? (:sim/violations sim)))))

(def lava-sea
  "Stone from the surface at y 64 down, and under it a lava sea at y 40 from z 10 to 15, roofed
   by the stone over it: the lava of a 1.18+ world, which lies under y -54 under a ceiling of
   deepslate and never on the surface (here at y 40, for a run short enough to test)."
  (into {} (for [x (range 16) z (range 10 16)] [[x 40 z] blocks/lava-base])))

(def hunt-kit
  "The portal kit as a run from spawn holds it: the water bucket, an empty bucket, cobblestone,
   flint and steel, and two stone pickaxes for the way down."
  {[:hotbar 0] {:item (recipe/item-id :stone_pickaxe) :count 1}
   [:hotbar 1] {:item (recipe/item-id :stone_pickaxe) :count 1}
   [:hotbar 2] {:item (recipe/item-id :bucket) :count 1}
   [:hotbar 3] {:item (recipe/item-id :water_bucket) :count 1}
   [:hotbar 4] {:item (recipe/item-id :cobblestone) :count 64}
   [:hotbar 5] {:item (recipe/item-id :flint_and_steel) :count 1}
   [:hotbar 6] {:item (recipe/item-id :cobblestone) :count 64}})

(deftest with-no-lava-near-the-bot-goes-down-to-the-sea-and-casts-beside-it
  ;; issue #41, the wall before the Nether: race 38477665442's 47 real bots and 950 sim runs
  ;; stood at the surface with the kit, no lava within sixteen of any origin, and waited
  ;; :no-site out. Lava under the stone is a site all the same (issue #124: any pool at any
  ;; depth): the site is chosen beside the sea, the bot goes down to the lava's height, digs its
  ;; way to the sea and the room in front of the frame, and opens the roof over a source to scoop
  (let [column (world/column-bytes lava-sea)
        [w sim] (sim/run step
                         (game/init fx/opts)
                         (sim/init {:columns (into {}
                                                   (for [cx [-1 0 1] cz [-1 0 1]] [[cx cz] column]))
                                    :spawn [8.5 64.0 2.5]
                                    :inventory hunt-kit})
                         #(or (plan/done? %) (plan/failed? %))
                         900000
                         {:event/kind :go :go/goals [:cast-one]})]
    (is (= :done (:plan/status w)) (pr-str (plan/summary w)))
    (is (zero? (:stats/deaths w 0)))
    (is (< (second (:player/pos w)) 50) "it went down to the sea's height")
    (let [{:site/keys [origin]} (memory/site w)]
      (is (some? origin) "a site was chosen")
      (is (< (second origin) 41) "the frame cut into the stone under the sea's stand")
      (is (= :obsidian
             (blocks/name-of
              (sim/block-at sim (build/cell origin (:site/facing (memory/site w)) [1 0 0]))))
          "the first cell, obsidian"))
    (is (empty? (:sim/violations sim)))))

;; ---------------------------------------------------------------- the rock is the mould (issue #124)

(def rock-kit
  "What a portal row gives (gym/portal-kit): a bucket each of lava and water, an empty one, a
   handful of cobblestone, flint and steel, and a stone pickaxe."
  {[:hotbar 0] {:item (recipe/item-id :lava_bucket) :count 1}
   [:hotbar 1] {:item (recipe/item-id :water_bucket) :count 1}
   [:hotbar 2] {:item (recipe/item-id :bucket) :count 1}
   [:hotbar 3] {:item (recipe/item-id :cobblestone) :count 8}
   [:hotbar 4] {:item (recipe/item-id :flint_and_steel) :count 1}
   [:hotbar 8] {:item (recipe/item-id :stone_pickaxe) :count 1}})

(defn rock-run
  "The planner toward the Nether from rock-kit, spawned at spawn over blocks, until it ends or
   max-ms. Returns [world sim]."
  [blocks spawn max-ms]
  (sim/run step
           (game/init fx/opts)
           (sim/init {:column (world/column-bytes blocks) :spawn spawn :inventory rock-kit})
           #(or (plan/done? %) (plan/failed? %))
           max-ms
           {:event/kind :go :go/goals [:nether]}))

(defn cast-frame
  "The frame's ten cells at the site the world remembers, as the sim holds them."
  [w sim]
  (let [{:site/keys [origin facing]} (memory/site w)]
    (for [[dx dy] build/frame-cells] (sim/block-at sim (build/cell origin facing [dx dy 0])))))

(def sealed-pool
  "A lava pool three by five at y 40 sealed in the stone (no air round it), and the bot in a
   pocket of its own two cells high at y 41, six blocks off: nowhere open but where it stands."
  (merge (into {} (for [x [11 12 13] z (range 2 7)] [[x 40 z] blocks/lava-base]))
         {[12 41 12] blocks/air [12 42 12] blocks/air}))

(deftest a-portal-is-cut-into-the-rock-beside-a-sealed-pool
  ;; issue #124: the rock is the mould. The pool is underground with no room anywhere: the bot
  ;; digs its way to it, the room in front of a face of the rock, each cell a hole lava is poured
  ;; into and water over, and the inside; a block only where its own holes opened a wall
  (let [[w sim] (rock-run sealed-pool [12.5 41.0 12.5] 1200000)]
    (is (= :done (:plan/status w)) (pr-str (plan/summary w) (memory/site w)))
    (is (= "minecraft:the_nether" (:player/dimension w)))
    (is (every? #(= :obsidian
                    (some-> %
                            blocks/name-of))
                (cast-frame w sim))
        "ten obsidian")
    (is (zero? (:stats/deaths w 0)))
    (is (<= (count (filter #(= :cobblestone (blocks/name-of %)) (vals (:sim/placed sim)))) 8)
        "a handful of blocks placed")
    (is (empty? (:sim/violations sim)))))

;; ---------------------------------------------------------------- the fortress (issue #11)

(def netherrack (blocks/default-state :netherrack))

(def nether-fragment
  "A Nether column (16 sections from y 0): the arrival at x 2, a wall of netherrack x 4..11 to
   tunnel through, a lava source beside the straight way at [8 64 7] that a level step past would
   open onto, and a fortress's bricks at x 14..15. Stone below y 64, as every fixture column."
  (merge (into {} (for [x (range 4 12) y (range 64 68) z (range 4 13)] [[x y z] netherrack]))
         {[8 64 7] blocks/lava-base}
         (into {}
               (for [x [14 15]
                     y (range 64 67)
                     z (range 6 11)]
                 [[x y z] (blocks/default-state :nether_bricks)]))))

(def nether-arrival [2.5 64.0 8.5])

(def fortress-kit
  "What the fortress row gives: a pickaxe for netherrack and cobblestone for floors."
  {[:hotbar 0] {:item (recipe/item-id :iron_pickaxe) :count 1}
   [:hotbar 1] {:item (recipe/item-id :cobblestone) :count 64}})

(defn fortress-run
  "The planner toward the fortress from a Nether landing (the gym's tp there), in the
   nether-fragment. Returns [world sim]."
  [step]
  (sim/run step
           (game/init fx/opts)
           (sim/init {:column (world/column-bytes nether-fragment dimension/nether)
                      :spawn nether-arrival
                      :dimension dimension/nether
                      :inventory fortress-kit})
           #(or (plan/done? %) (plan/failed? %))
           600000
           {:event/kind :go :go/goals [:fortress]}))

(deftest the-fortress-from-a-nether-landing-and-its-recording-replays
  (let [path (str (System/getProperty "java.io.tmpdir") "/clojurecraft-fortress.edn")
        tap (record/tap! path)
        [w sim] (fortress-run (recording step tap))]
    ((:close tap))
    (is (= :done (:plan/status w)) (pr-str (plan/summary w)))
    (is (= dimension/nether (:player/dimension w)) "joined in the Nether, from the login")
    (is (some #(fortress/arrived? w %) (fortress/bricks w)) "within 6 of a brick")
    (is (= blocks/lava-base (sim/block-at sim [8 64 7])) "the lava is still a source")
    (is (= netherrack (sim/block-at sim [8 64 8]))
        "and the cell beside it on the way was never opened")
    (is (zero? (:stats/deaths w 0)))
    (is (not-any? #(= :failed (:intention/event %)) (memory/intentions w)))
    (is (empty? (:sim/violations sim)))
    (testing "the recording replays with every sent packet the same"
      (is (nil? (record/verify step (game/init fx/opts) path))))))

(deftest the-portal-leads-on-to-the-fortress
  ;; one run from the kit: cast the portal, step through, and tunnel to the fortress; the
  ;; overworld rows are done once in the Nether, and the fortress waits for it
  (let [[w sim] (nether-run step
                            {:nether {:column (world/column-bytes nether-fragment dimension/nether)
                                      :at nether-arrival}
                             :inventory (merge kit {[:hotbar 7] (fortress-kit [:hotbar 0])})}
                            [:nether :fortress])]
    (is (= :done (:plan/status w)) (pr-str (plan/summary w)))
    (is (= dimension/nether (:player/dimension w)))
    (is (some #(fortress/arrived? w %) (fortress/bricks w)))
    (is (empty? (:sim/violations sim)))))

;; ---------------------------------------------------------------- a captured Nether (issue #353)

(def lava (blocks/default-state :lava))
(def nether-bricks (blocks/default-state :nether_bricks))

(defn nether-column
  "A Nether column's bytes (16 sections from y 0): netherrack from y 60 to 63 under open air,
   the lava sea's top at y 31 (stone round it, as every fixture column below y 64), and blocks
   over it ({[lx y lz] id})."
  [blocks]
  (world/column-bytes
   (merge (into {} (for [lx (range 16) lz (range 16) y (range 60 64)] [[lx y lz] netherrack]))
          (into {} (for [lx (range 16) lz (range 16)] [[lx 31 lz] lava]))
          blocks)
   dimension/nether))

(def captured-nether
  "A captured Nether's columns, seven chunks along x by three along z round [0 0]: open ground
   on netherrack, a fortress's bricks one chunk east (x 16 and 17), and another's four chunks
   east (x 64), farther than the view the model streams."
  (into {}
        (for [cx (range -2 5)
              cz (range -1 2)]
          [[cx cz]
           (nether-column
            (case [cx cz]
              [1 0]
              (into {} (for [lx [0 1] y (range 64 67) lz (range 6 11)] [[lx y lz] nether-bricks]))
              [4 0] (into {} (for [lz (range 6 11)] [[0 64 lz] nether-bricks]))
              {}))])))

(defn decoded-of
  "The columns decoded, as a prepared world holds them."
  [columns]
  (update-vals columns chunk/decode))

(deftest a-portal-leads-to-the-scaled-point
  ;; NetherPortalBlock.getExitPortal: the overworld's x and z over 8, floored, y kept
  (is (= [10 64 -2] (sim/exit-point [80.7 64.0 -9.2])))
  (is (= [0 -25 0] (sim/exit-point [7.9 -25.0 0.5]))))

(deftest a-nether-arrival-needs-room-for-the-portal-not-an-isolated-foothold
  (let [gravel (blocks/default-state :gravel)
        column (nether-column
                (assoc (into {}
                             (for [x (range 16) z (range 16) y (range 32 60)] [[x y z] blocks/air]))
                       [1 33 1]
                       gravel))
        decoded (decoded-of (assoc captured-nether [0 0] column))
        at #(chunk/block-at decoded %)]
    (is (sim/standing? at [1 34 1]) "one gravel block can hold the player")
    (is (= [1.5 64.0 1.5] (:at (sim/arrival decoded [8.5 18.0 8.5])))
        "the linked portal needs ground for its whole frame, not gravel suspended over lava")))

(deftest a-natural-nether-portal-needs-its-whole-frame-in-either-axis
  (doseq [[dx dz] [[1 0] [0 1]]]
    (let [cells (into {}
                      (for [n (range -1 3)
                            y (range 63 68)]
                        [[(* n dx) y (* n dz)] (if (= y 63) netherrack blocks/air)]))
          fits? #(sim/portal-space? (fn [p] (get % p)) [0 64 0])]
      (is (fits? cells))
      (doseq [state [nil blocks/air lava]]
        (is (not (fits? (assoc cells [(* 2 dx) 63 (* 2 dz)] state)))
            "every floor cell must be solid and loaded"))
      (doseq [state [nil netherrack lava]]
        (is (not (fits? (assoc cells [(* 2 dx) 67 (* 2 dz)] state)))
            "the top of the frame needs clearance too")))))

(deftest the-arrival-is-the-standing-cell-nearest-the-exit-point
  (let [decoded (decoded-of captured-nether)]
    (is (= {:at [1.5 64.0 1.5] :placed {}} (sim/arrival decoded [8.5 64.0 8.5]))
        "on the netherrack at the exit point itself")
    (testing "lava under the exit point: the nearest cell with a solid floor, ties by position"
      (let [pool (decoded-of (assoc captured-nether [0 0] (nether-column {[1 63 1] lava})))]
        (is (= [0.5 64.0 1.5] (:at (sim/arrival pool [8.5 64.0 8.5]))))))
    (testing "nearest in height too: a portal deep in the overworld comes out in a cave under it"
      (let [cave (decoded-of
                  (assoc captured-nether
                         [0 0]
                         (nether-column
                          (into {} (for [x (range 4 8) y (range 40 44)] [[x y 1] blocks/air])))))]
        (is (= [5.5 40.0 1.5] (:at (sim/arrival cave [8.5 -20.0 8.5]))))))
    (testing "the bedrock roof is never stood under: no cell with its head at 128 or over"
      (is (nil? (sim/exit-spot (fn [[_ y _]] (if (< y 128) netherrack blocks/air)) [0 120 0] 128))))
    (testing "nowhere to stand within sixteen: the server's forced platform"
      (is (nil? (sim/exit-spot (constantly netherrack) [1 30 1] 128)))
      (let [{:keys [at placed]} (sim/arrival {} [8.5 30.0 8.5])]
        (is (= [1.5 70.0 1.5] at) "its y held at 70 or over")
        (is (= (blocks/default-state :obsidian) (placed [1 69 1])) "an obsidian floor")
        (is (= blocks/air (placed [1 71 1])) "air over it")
        (is (= 24 (count placed)) "two by three, a floor and three cells of air")))))

(defn captured-nether-run
  "The planner from the portal kit toward the Nether and its fortress, the portal leading into
   captured-nether streamed a chunk round the player, until stop? or the plan ends. Returns
   [world sim]."
  [stop?]
  (sim/run step
           (game/init fx/opts)
           (sim/init {:column (world/column-bytes lava-pit)
                      :spawn [9.5 64.0 10.5]
                      :inventory (merge kit {[:hotbar 7] (fortress-kit [:hotbar 0])})
                      :nether {:columns captured-nether
                               :decoded (decoded-of captured-nether)
                               :view-distance 1}})
           #(or (stop? %) (plan/done? %) (plan/failed? %))
           1800000
           {:event/kind :go :go/goals [:nether :fortress]}))

(deftest a-portal-leads-into-the-captured-nether
  ;; issue #353: the sim race's portal led into a copy of the overworld's columns, and the bot
  ;; tunnelled stone for bricks that were not there. With a captured Nether the player comes out
  ;; where the server would link the portal, on netherrack over the lava sea, and sees the
  ;; fortress within the view the model streams, not one farther off
  (let [arrived? #(and (= dimension/nether (:player/dimension %)) (seq (fortress/bricks %)))
        [w sim] (captured-nether-run arrived?)
        [x y z :as feet] (intent/feet w)]
    (is (= dimension/nether (:player/dimension w)) (pr-str (plan/summary w)))
    (is (= netherrack (terrain/block-at w [x (dec y) z])) "on netherrack")
    (is (= blocks/air (terrain/block-at w feet)))
    (is (<= (- sim/exit-search) x (inc sim/exit-search))
        "within sixteen of the portal's x over 8 (0 or 1: the arena is chunk [0 0])")
    (is (= lava (terrain/block-at w [x 31 z])) "the lava sea's top under it")
    (is (some #(= [16 64 6] %) (fortress/bricks w)) "the near fortress's bricks on record")
    (is (not-any? #(>= (first %) 64) (fortress/bricks w)) "the far one's never sent")
    (is (= (sim/block-at sim [16 64 6]) (terrain/block-at w [16 64 6])))
    (testing "and it tunnels on to the fortress it saw"
      (let [{w' :world sim' :sim} (sim/play-on step
                                               w
                                               sim
                                               #(or (plan/done? %) (plan/failed? %))
                                               600000
                                               (:time/now w)
                                               []
                                               nil)]
        (is (= :done (:plan/status w')) (pr-str (plan/summary w')))
        (is (some #(fortress/arrived? w' %) (fortress/bricks w')))
        (is (empty? (:sim/violations sim')))))))


(deftest a-full-inventory-throws-junk-to-take-the-pickaxe
  ;; race 38805920624 runner 5 (issues #172, #192): with every slot full, the craft's result had
  ;; nowhere to go and the craft failed :inventory-full; a junk stack is thrown out first
  (let [sand {:item (recipe/item-id :sand) :count 64}
        junk (into {}
                   (concat (for [n (range 3 9)] [[:hotbar n] sand])
                           (for [n (range 27)] [[:main n] sand])))
        [w sim] (sim/run step
                         (game/init fx/opts)
                         (sim/init
                          {:column (world/column-bytes {})
                           :spawn [0.5 64.0 0.5]
                           :inventory
                           (merge junk
                                  {[:hotbar 0] {:item (recipe/item-id :oak_planks) :count 4}
                                   [:hotbar 1] {:item (recipe/item-id :stick) :count 3}
                                   [:hotbar 2] {:item (recipe/item-id :crafting_table) :count 2}})})
                         #(or (plan/done? %) (plan/failed? %))
                         60000
                         {:event/kind :go :go/goals [:pickaxe]})]
    (is (plan/done? w) (pr-str (plan/summary w)))
    (is (= 1 (:wooden_pickaxe (held w))))
    (is (= (* 32 64) (:sand (held w))) "one stack of sand thrown, no more")
    (is (= [] (:sim/violations sim)))))

(deftest a-full-inventory-throws-surplus-deepslate-to-replace-the-pickaxe
  (let [deepslate {:item (recipe/item-id :cobbled_deepslate) :count 64}
        cobblestone {:item (recipe/item-id :cobblestone) :count 64}
        inv (into {}
                  (concat (for [n (range 5 9)] [[:hotbar n] deepslate])
                          (for [n (range 27)] [[:main n] deepslate])
                          {[:hotbar 0] cobblestone
                           [:hotbar 1] cobblestone
                           [:hotbar 2] {:item (recipe/item-id :oak_planks) :count 4}
                           [:hotbar 3] {:item (recipe/item-id :stick) :count 3}
                           [:hotbar 4] {:item (recipe/item-id :crafting_table) :count 2}}))
        [w model] (sim/run step
                           (game/init fx/opts)
                           (sim/init
                            {:column (world/column-bytes {}) :spawn [0.5 64.0 0.5] :inventory inv})
                           #(or (plan/done? %) (plan/failed? %))
                           60000
                           {:event/kind :go :go/goals [:stone-pickaxe]})]
    (is (plan/done? w) (pr-str (plan/summary w)))
    (is (= 1 (:stone_pickaxe (held w))))
    (is (= 125 (:cobblestone (held w))) "only the recipe's three cobblestone are spent")
    (is (= (* 30 64) (:cobbled_deepslate (held w))) "one surplus stack thrown")
    (is (not-any? #(= :failed (:intention/event %)) (memory/intentions w)))
    (is (empty? (:sim/violations model)))))

(deftest eyes-of-ender-from-rods-and-pearls
  ;; issue #264: the rods and pearls the two stages before make, given; blaze powder from the rods
  ;; and twelve eyes from the powder and the pearls, all in the 2x2 grid, through the craft rows
  (let [[w sim] (sim/run step
                         (game/init fx/opts)
                         (sim/init {:column (world/column-bytes {})
                                    :spawn [0.5 64.0 0.5]
                                    :inventory
                                    {[:hotbar 0] {:item (recipe/item-id :blaze_rod) :count 7}
                                     [:hotbar 1] {:item (recipe/item-id :ender_pearl) :count 12}}})
                         #(or (plan/done? %) (plan/failed? %))
                         120000
                         {:event/kind :go :go/goals [:eyes-of-ender]})]
    (is (plan/done? w) (pr-str (plan/summary w)))
    (is (<= 12 (get (held w) :ender_eye 0)))
    (is (= 1 (get (held w) :blaze_rod 0)) "six rods for twelve powder, one left")
    (is (zero? (get (held w) :ender_pearl 0)))
    (is (empty? (:window/grid w)) "nothing left in the grid")
    (is (not-any? #(= :failed (:intention/event %)) (memory/intentions w)))
    (is (= [] (:sim/violations sim)))))

;; ---------------------------------------------------------------- blaze rods (issue #262)

(def nether-wall
  "A wall of netherrack one thick and three high at x 7, z 6 to 9, west of a Nether landing at
   [8.5 64 6.5] by its end: round that end, the cells behind it are out of the sight of blazes
   to the east."
  (into {} (for [z (range 6 10) y [64 65 66]] [[7 y z] (blocks/default-state :netherrack)])))

(deftest watched-by-three-of-the-models-blazes-the-bot-hides
  ;; issue #262's blaze fixture against the model: three blazes east of the landing, all in
  ;; sight, a volley (mob/kinds: 5 a hit, 3.5 at Easy, every three seconds with a clear line);
  ;; the bot walks round the wall into a cell none of them sees, whatever its health
  (let [[w sim] (sim/run step
                         (game/init fx/opts)
                         (sim/init {:column (world/column-bytes nether-wall dimension/nether)
                                    :spawn [8.5 64.0 6.5]
                                    :dimension dimension/nether
                                    :difficulty :easy
                                    :food 20
                                    :inventory {[:hotbar 0] {:item (item :iron_sword) :count 1}}
                                    :mobs [{:type :blaze :pos [14.5 65.0 6.5]}
                                           {:type :blaze :pos [14.5 65.0 7.5]}
                                           {:type :blaze :pos [14.5 65.0 8.5]}]})
                         #(or (and (= :cover (get-in % [:plan/last :intent/for]))
                                   (intent/done? (:plan/last %)))
                              (plan/failed? %)
                              (pos? (:stats/deaths % 0)))
                         30000
                         {:event/kind :go :go/goals [:blaze-rods]})]
    (is (intent/done? (:plan/last w)) (pr-str (plan/summary w)))
    (is (zero? (:stats/deaths w 0)) "alive")
    (is (pos? (:sim/health sim)))
    (is (empty? (blaze/watchers w)) "no blaze sees it")
    (is (< (first (:player/pos w)) 7.0) "behind the wall")
    (is (= :cover (:intent/for (:plan/last w))) "the walk there was for cover")
    (is (= [] (:sim/violations sim)))))

(deftest three-of-the-models-blazes-at-hand-the-nearest-is-killed
  ;; gym blaze-fire-b run 3 (run 39136853644): blazes at hand followed the bot into cover after
  ;; cover and it died without a swing; the three here are within 4, the cover round the wall is
  ;; there, and the blaze-rods row fights the nearest to the end (the :defend row leaves blazes to
  ;; it)
  (let [[w sim] (sim/run step
                         (game/init fx/opts)
                         (sim/init {:column (world/column-bytes nether-wall dimension/nether)
                                    :spawn [8.5 64.0 6.5]
                                    :dimension dimension/nether
                                    :difficulty :easy
                                    :food 20
                                    :inventory {[:hotbar 0] {:item (item :iron_sword) :count 1}}
                                    :mobs [{:type :blaze :pos [11.5 65.0 5.5]}
                                           {:type :blaze :pos [11.5 65.0 6.5]}
                                           {:type :blaze :pos [11.5 65.0 7.5]}]})
                         #(or (and (= :fight (get-in % [:plan/last :intent/kind]))
                                   (intent/done? (:plan/last %)))
                              (plan/failed? %)
                              (pos? (:stats/deaths % 0)))
                         30000
                         {:event/kind :go :go/goals [:blaze-rods]})]
    (is (intent/done? (:plan/last w)) (pr-str (plan/summary w)))
    (is (zero? (:stats/deaths w 0)) "alive")
    (is (:intent/to-the-end? (:plan/last w)) "the blaze-rods row's fight on one at hand")
    (is (= 2 (count (blaze/blazes w))) "one of the three killed")
    (is (= [] (:sim/violations sim)))))

(def blaze-spawner-cell [12 64 12])

(deftest the-seventh-rod-from-a-spawner-in-the-models-nether
  ;; issue #262's acceptance, a spawner and no fixture blazes: six rods given, one more wanted;
  ;; the spawner on the floor four blocks from the landing wakes (mob/spawner), makes its first
  ;; batch within 800 ticks and again, the bot camps by it, fights what comes and picks up the rod
  ;; one kill in two drops (rolled from the model's seed). It wears iron armour, as the stage's bot
  ;; does (the first run without died to a batch of four), and the wall west of the landing is
  ;; its cover
  (let [[w sim] (sim/run step
                         (game/init fx/opts)
                         (sim/init {:column (world/column-bytes (assoc nether-wall
                                                                       blaze-spawner-cell
                                                                       mob/spawner-block)
                                                                dimension/nether)
                                    :spawn [8.5 64.0 8.5]
                                    :dimension dimension/nether
                                    :difficulty :easy
                                    :food 20
                                    :seed 3
                                    :inventory {[:hotbar 0] {:item (item :iron_sword) :count 1}
                                                [:hotbar 1] {:item (item :blaze_rod) :count 6}
                                                [:armour 0] {:item (item :iron_helmet) :count 1}
                                                [:armour 1] {:item (item :iron_chestplate) :count 1}
                                                [:armour 2] {:item (item :iron_leggings) :count 1}
                                                [:armour 3] {:item (item :iron_boots) :count 1}}})
                         #(or (plan/done? %) (plan/failed? %) (pos? (:stats/deaths % 0)))
                         240000
                         {:event/kind :go :go/goals [:blaze-rods]})]
    (is (plan/done? w) (pr-str (plan/summary w)))
    (is (>= (inventory/item-count w blaze/rod) blaze/rods-wanted))
    (is (seq (:sim/spawners sim)) "the model found the spawner")
    (is (zero? (:stats/deaths w 0)) "alive")
    (is (pos? (:sim/health sim)))
    (is (= [] (:sim/violations sim)))))

(deftest a-nether-death-respawns-over-the-overworld-as-it-was
  ;; issue #504: a death in the Nether left the Nether's columns under the overworld's dimension,
  ;; so the respawned bot stood in a world with no chunk at the spawn, and the portal it built was
  ;; gone; the overworld's columns and its placed and broken blocks come back
  (let [overworld (world/column-bytes {})
        feet [1 62 1]
        placed {feet (blocks/default-state :nether_portal)
                [3 70 3] (blocks/default-state :obsidian)}
        s (-> (sim/init {:column overworld
                         :spawn [1.27 62.0 1.7]
                         :nether {:column (world/column-bytes {[4 64 4] (blocks/default-state
                                                                         :netherrack)}
                                                              dimension/nether)
                                  :at [8.5 64.0 8.5]}})
              (assoc :sim/phase :play
                     :sim/player-pos [1.27 62.0 1.7]
                     :sim/portal-since 0
                     :sim/placed placed
                     :sim/broken #{[2 63 2]}))
        nether (sim/through-portal s sim/portal-ms)
        dead (assoc nether :sim/health 0.0 :sim/out [])
        alive (sim/on-packet dead
                             {:packet/name :client-command
                              :action (packet/value :client-command :perform-respawn)})]
    (is (= dimension/nether (:sim/dimension nether)))
    (is (not= (:sim/columns s) (:sim/columns nether)) "the Nether's own column")
    (is (= dimension/overworld (:sim/dimension alive)))
    (is (= (:sim/columns s) (:sim/columns alive)) "the overworld's columns again")
    (is (= placed (:sim/placed alive)) "the portal and the obsidian still stand")
    (is (= #{[2 63 2]} (:sim/broken alive)))
    (is (= :obsidian (blocks/name-of (sim/block-at alive [3 70 3]))))
    (is (not (contains? alive :sim/overworld)) "nothing set aside once it is back")
    (is (some #(= :level-chunk-with-light (:packet/name %)) (:sim/out alive))
        "the client is sent the overworld's chunks")))
