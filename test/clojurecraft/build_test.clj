(ns clojurecraft.build-test
  "The portal plan as data (issue #8): its geometry against ruststeve's mold, and the properties
   that keep a cast from pouring lava on the bot or sealing a cup before it is poured."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.test.check :as tc]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [clojurecraft.blocks :as blocks]
            [clojurecraft.build :as build]))

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
