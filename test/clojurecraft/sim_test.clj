(ns clojurecraft.sim-test
  "The whole bot against the pure server model: handshake → spawn → chunk → find → walk → dig →
   drop → pickup → done, with no socket and no Java process."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojurecraft.fixtures :as fx]
            [clojurecraft.game :as game]
            [clojurecraft.make]
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
        (is (= state (get-in w [:world/sightings pos :block/state])))))))

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

(deftest an-early-finish-does-not-break-the-block
  (let [sim0 (assoc (sim/init {:column (world/column-bytes {}) :spawn [0.5 64.0 0.5]}) :sim/phase :play :sim/now 1000)
        sim (-> sim0
                (sim/step {:sim/kind :packet :sim/packet {:packet/name :player-action :status 0 :pos [1 64 0] :face 4 :sequence 1}})
                (sim/step {:sim/kind :tick :sim/now 2000})
                (sim/step {:sim/kind :packet :sim/packet {:packet/name :player-action :status 2 :pos [1 64 0] :face 4 :sequence 2}}))]
    (is (empty? (:sim/broken sim)))
    (is (= [:block-changed-ack] (mapv :packet/name (:sim/out sim))))))
