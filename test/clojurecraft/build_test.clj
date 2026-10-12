(ns clojurecraft.build-test
  "The portal plan as data (issue #8): its geometry against ruststeve's mold, and the properties
   that keep a cast from pouring lava on the bot or sealing a cup before it is poured."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.test.check :as tc]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [clojurecraft.blocks :as blocks]
            [clojurecraft.build :as build]
            [clojurecraft.fixtures :as fx]
            [clojurecraft.game :as game]
            [clojurecraft.path :as path]
            [clojurecraft.world :as world]))

(def origin [100 64 200])

(defn ops-of [plan op] (filter #(= op (:build/op %)) plan))

(deftest the-plan-casts-the-ten-cells-in-layer-order
  (let [plan (build/portal-plan origin :south)
        lava (filter #(= :lava (:build/step %)) plan)
        water (filter #(= :water (:build/step %)) plan)]
    (is (= build/frame-cells (map :build/cell lava)) "never sorted by height")
    (is (= build/frame-cells (map :build/cell water)) "two pours a cell")
    (testing "every pour from the cell's stance: a block over the cell, one toward the bot"
      (doseq [{[dx dy] :build/cell :as op} (concat lava water)]
        (is (= [(+ 100 dx) (+ 64 dy 1) 201] (:build/stand op)) (pr-str op))))
    (testing "lava into the cup, water into the bowl over it"
      (doseq [{[dx dy] :build/cell :as op} lava]
        (is (= [(+ 100 dx) (+ 64 dy) 200] (:build/into op))))
      (doseq [{[dx dy] :build/cell :as op} water]
        (is (= [(+ 100 dx) (+ 64 dy 1) 200] (:build/into op)))))
    (is (= :use-on (:build/op (last (butlast plan)))) "lit")
    (is (= [:dimension "minecraft:the_nether"] (:build/done (last plan))) "then stepped through")))

(defn obsidian-at [p] {:world/chunks {} :world/blocks {p (blocks/default-state :obsidian)}})

(deftest no-dig-can-take-obsidian
  ;; ruststeve dug its own frame and restarted forever: every dig is done once its cell is
  ;; obsidian, so it is never worked
  (doseq [facing build/facings
          op (ops-of (build/portal-plan origin facing) :dig)]
    (is (build/satisfied? (obsidian-at (:build/pos op)) op) (pr-str op))))

(def gen-origin (gen/tuple (gen/choose -1000 1000) (gen/choose -40 200) (gen/choose -1000 1000)))
(def gen-facing (gen/elements (sort build/facings)))

(defn lid?
  "Does the place op at index i put a block two over a cup a lava pour after i fills?"
  [plan i]
  (let [p (:build/pos (nth plan i))]
    (some (fn [op] (and (= :lava (:build/step op)) (= p (update (:build/into op) 1 + 2))))
          (drop (inc i) plan))))

(deftest the-plan-never-places-into-its-own-stance-nor-lids-a-cup
  (let [r (tc/quick-check 200
                          (prop/for-all
                           [o gen-origin facing gen-facing]
                           (let [plan (build/portal-plan o facing)]
                             (and (every? (fn [{:build/keys [pos stand]}]
                                            (and (not= pos stand) (not= pos (update stand 1 inc))))
                                          (ops-of plan :place))
                                  (not-any? #(and (= :place (:build/op (nth plan %))) (lid? plan %))
                                            (range (count plan)))
                                  (= plan (build/portal-plan o facing))))))]
    (is (:pass? r) (pr-str (select-keys r [:fail :shrunk])))))

(deftest no-op-touches-a-column-a-station-may-be-in
  ;; station-among refuses a source under the footprint, so the footprint must cover every cell
  ;; the plan works in: the stair to the top layer runs east past the mold's box, and a step
  ;; placed into a station's source would destroy it
  (let [r (tc/quick-check 200
                          (prop/for-all [o gen-origin facing gen-facing]
                                        (every? (fn [[x _ z]] (build/footprint? o facing [x z]))
                                                (keep #(or (:build/pos %) (:build/into %))
                                                      (build/portal-plan o facing)))))]
    (is (:pass? r) (pr-str (select-keys r [:fail :shrunk])))))

(deftest turning-the-facing-moves-every-cell-and-loses-none
  (let [cells (fn [facing] (set (keep :build/pos (build/portal-plan origin facing))))]
    (doseq [facing build/facings]
      (is (= (count (cells :south)) (count (cells facing))) (name facing)))
    (is (= #{[101 64 200] [99 64 200] [100 64 199] [100 64 201]}
           (set (for [f build/facings]
                  (:build/pos (first (ops-of (build/portal-plan origin f) :use-on))))))
        "the bottom cell the fire goes on turns about the origin")))

(deftest cast-one-casts-the-first-cell
  (let [plan (build/cast-one-plan origin :south)]
    (is (= [[1 0]] (keep :build/cell (filter #(= :lava (:build/step %)) plan))))))

(def hill-origin [2 63 8])
(def hill-stand [6 63 8])

(def hill-op
  {:build/op :place
   :build/item :cobblestone
   :build/pos [6 62 8]
   :build/stand hill-stand
   :build/step :pad
   :build/done [:solid [6 62 8]]})

(def hill-intent
  {:intent/kind :build :intent/build :cast-one :intent/origin hill-origin :intent/facing :south})

(defn open-ground
  "Air at the feet's height and the head, over the stone floor the column fills below y 64."
  [extra]
  (merge (into {} (for [x (range 16) y [63 64] z (range 16)] [[x y z] blocks/air])) extra))

(defn standing-at
  [blocks pos]
  (-> (game/init fx/opts)
      (assoc :bot/phase :play :player/pos pos :player/loaded? true :player/on-ground? true)
      (assoc :world/chunks {[0 0] (world/column blocks)})))

(deftest a-stand-inside-a-hill-is-dug
  ;; full run 4e59246 stood at the pond and the pad's stand was stone; the walk was :none and
  ;; three :no-path failures ended the plan
  (let [pos [2.5 63.0 8.5]
        flat (standing-at (open-ground {}) pos)
        hill (standing-at (open-ground (into {} (for [x (range 3 7) y [63 64]] [[x y 8] 1]))) pos)]
    (is (= {:intent/kind :walk :intent/target hill-stand :intent/for :build}
           (build/op-child flat hill-intent hill-op))
        "open ground is walked to")
    (is (= :none
           (:path/status
            (path/plan hill (path/feet-cell pos) {:goal/kind :block :goal/pos hill-stand})))
        "the stand is inside the hill, so a walk at it has no route")
    (is (=
         {:intent/kind :dig :intent/target [3 63 8] :intent/status :active :intent/exposes-ok? true}
         (build/op-child hill hill-intent hill-op))
        "the first solid on the way, in reach")
    (let [opened (assoc-in hill [:world/blocks [3 63 8]] blocks/air)]
      (is (= [3 64 8] (:intent/target (build/op-child opened hill-intent hill-op)))
          "then the head over it"))
    (let [cobble (blocks/default-state :cobblestone)
          ;; [4 63 8] is a cell the cast places; cobble there is the mold, the stone past it is the hill
          w (standing-at (open-ground {[4 63 8] cobble [5 63 8] 1 [5 64 8] 1}) pos)]
      (is (= [5 63 8] (:intent/target (build/op-child w hill-intent hill-op)))
          "cobblestone the plan places stays; the stone past it is dug"))))
