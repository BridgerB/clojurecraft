(ns clojurecraft.sim-test
  "The whole bot against the pure server model: handshake → spawn → chunk → find → walk → dig →
   drop → pickup → done, with no socket and no Java process."
  (:require [clojure.core.async :as a]
            [clojure.spec.alpha]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.test.check :as tc]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [clojurecraft.fixtures :as fx]
            [clojurecraft.game :as game]
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
  (let [sim0 (assoc (sim/init {:column (world/column-bytes {}) :spawn [0.5 64.0 0.5]}) :sim/phase :play :sim/now 1000)
        sim (-> sim0
                (sim/step {:sim/kind :packet :sim/packet {:packet/name :player-action :status 0 :pos [1 64 0] :face 4 :sequence 1}})
                (sim/step {:sim/kind :tick :sim/now 2000})
                (sim/step {:sim/kind :packet :sim/packet {:packet/name :player-action :status 2 :pos [1 64 0] :face 4 :sequence 2}}))]
    (is (empty? (:sim/broken sim)))
    (is (= [:block-changed-ack] (mapv :packet/name (:sim/out sim))))))

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
   spawn columns, with a leaf block on top; maybe on a one-block stone mound (the trunk's cell
   and its four neighbours), maybe with low leaves one block above its base over every cell
   within two steps of the trunk. A mound under a low canopy is the ledge a live run once
   failed on: leaves over the step and over the ground beside it."
  (gen/let [x (gen/choose 3 13) z (gen/choose 3 13) h (gen/choose 3 6) id (gen/elements log-species)
            mound? gen/boolean canopy? gen/boolean]
    {:x x :z z :h h :id id :mound? mound? :canopy? canopy?}))

(def stone 1)

(defn tree-blocks
  "{[lx y lz] id} for one tree."
  [{:keys [x z h id mound? canopy?]}]
  (let [base (if mound? 65 64)
        around [[(inc x) z] [(dec x) z] [x (inc z)] [x (dec z)]]]
    (merge (when mound? (into {} (for [[mx mz] (cons [x z] around) :when (and (<= 0 mx 15) (<= 0 mz 15))] [[mx 64 mz] stone])))
           (when canopy? (into {} (for [dx (range -2 3) dz (range -2 3)
                                        :let [cx (+ x dx) cz (+ z dz) d (+ (abs dx) (abs dz))]
                                        :when (and (<= 1 d 2) (<= 0 cx 15) (<= 0 cz 15))]
                                    [[cx (inc base) cz] leaf-id])))
           (into {} (for [y (range base (+ base h))] [[x y z] id]))
           {[x (+ base h) z] leaf-id})))

(defn forest
  "{[lx y lz] id} for trees on the stone floor (y 64 up); a later tree's blocks win."
  [trees]
  (apply merge (map tree-blocks trees)))

(deftest the-wood-goal-holds-in-generated-forests
  ;; the bot against the model in 2,000 worlds test.check builds (docs/hickey.md: "thousands of
  ;; generated worlds in the time one real run takes"): 1-3 trees of mixed species and heights
  ;; anywhere in the column, on mounds or flat ground, under low leaves or open sky, from a
  ;; varied spawn. In every one it ends holding a log, without the sim ever seeing something a
  ;; real server would punish. FOREST_TRIALS=n overrides the count, FOREST_SEED=s fixes the seed
  ;; (a fleet shard's), FOREST_OUT=f writes the verdict as EDN.
  (let [r (tc/quick-check
           (or (some-> (System/getenv "FOREST_TRIALS") Long/parseLong) 2000)
           :seed (or (some-> (System/getenv "FOREST_SEED") Long/parseLong) (System/currentTimeMillis))
           (prop/for-all [trees (gen/vector tree-gen 1 3) sx (gen/choose 0 1) sz (gen/choose 0 1)]
                         (let [sim0 (sim/init {:column (world/column-bytes (forest trees)) :spawn [(+ sx 0.5) 64.0 (+ sz 0.5)]})
                               [w sim] (sim/run step (game/init fx/opts) sim0 #(or (plan/done? %) (plan/failed? %))
                                                90000 {:event/kind :go :go/goals [:wood]})]
                           (and (plan/done? w) (= 1 (inventory/logs-held w)) (empty? (:sim/violations sim))))))]
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
