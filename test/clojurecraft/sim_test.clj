(ns clojurecraft.sim-test
  "The whole bot against the pure server model: handshake → spawn → chunk → find → walk → dig →
   drop → pickup → done, with no socket and no Java process."
  (:require [clojure.spec.alpha]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.test.check :as tc]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [clojurecraft.fixtures :as fx]
            [clojurecraft.game :as game]
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
    (is (= 1 (game/logs-held w)))
    (is (= :play (:bot/phase w)))
    (is (pos? (:stats/keep-alives w)) "the model's keep-alives were answered")
    (is (= 0 (game/block-at w [6 64 0])))))

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
      (is (= (into {} (for [[s it] (:sim/inv sim) :when (<= 9 s 44)] [(game/container->player-slot s) it]))
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
    (is (= 1 (game/logs-held w)))
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
   spawn column, with a leaf block on top."
  (gen/let [x (gen/choose 3 13) z (gen/choose 3 13) h (gen/choose 3 6) id (gen/elements log-species)]
    {:x x :z z :h h :id id}))

(defn forest
  "{[lx y lz] id} for trees standing on the stone floor (y 64 up); a later tree's blocks win."
  [trees]
  (into {} (for [{:keys [x z h id]} trees
                 [y b] (concat (for [y (range 64 (+ 64 h))] [y id]) [[(+ 64 h) leaf-id]])]
             [[x y z] b])))

(deftest the-wood-goal-holds-in-generated-forests
  ;; the bot against the model in worlds test.check builds: 1-3 trees of mixed species and
  ;; heights anywhere in the column. In every one it ends holding a log, without the sim ever
  ;; seeing something a real server would punish.
  (let [r (tc/quick-check
           (or (some-> (System/getenv "FOREST_TRIALS") Long/parseLong) 100)
           (prop/for-all [trees (gen/vector tree-gen 1 3)]
                         (let [sim0 (sim/init {:column (world/column-bytes (forest trees)) :spawn [0.5 64.0 0.5]})
                               [w sim] (sim/run step (game/init fx/opts) sim0 #(or (plan/done? %) (plan/failed? %))
                                                90000 {:event/kind :go :go/goals [:wood]})]
                           (and (plan/done? w) (= 1 (game/logs-held w)) (empty? (:sim/violations sim))))))]
    (is (:pass? r) (pr-str (select-keys r [:fail :shrunk :num-tests])))))
