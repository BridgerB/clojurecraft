(ns clojurecraft.scene-test
  "What the judge prints for a moment (scene): the facts a fix starts from, absent ones left out,
   and the blocks round the feet as text, the feet marked, unloaded cells shown, a legend."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [clojurecraft.blocks :as blocks]
            [clojurecraft.scene :as scene]))

(defn state "A state id of the block named n." [n] (first (sort (blocks/states-of n))))

(def world
  {:player/pos [0.5 64.0 0.5]
   :player/health 14.0
   :player/inventory {36 {:item (blocks/items :cobblestone) :count 12}
                      37 {:item (blocks/items :cobblestone) :count 4}
                      38 {:item (blocks/items :stone_pickaxe) :count 1}}
   :plan/intent {:intent/kind :tunnel-to :intent/target [10 -25 21] :intent/route [[0 0 0] [1 0 0]]}
   :plan/status :active
   :world/blocks (merge (into {}
                              (for [x (range -5 6) z (range -5 6)] [[x 63 z] (state :netherrack)]))
                        {[1 64 0] (state :lava) [0 65 0] (state :air) [0 64 0] (state :air)})})

(deftest the-facts-a-fix-starts-from
  (let [f (scene/facts world)]
    (is (= [0.5 64.0 0.5] (:pos f)))
    (is (= {:cobblestone 16 :stone_pickaxe 1} (:held f)) "stacks summed, by name")
    (is (= {:intent/kind :tunnel-to :intent/target [10 -25 21]} (:intent f)) "never the route")
    (is (not (contains? f :food)) "absent facts left out, never nil")))

(deftest the-blocks-round-the-feet
  (let [lines (scene/slice world)
        feet (first (filter #(str/starts-with? % "y+0 z+0 ") lines))
        floor (first (filter #(str/starts-with? % "y-1 z+0 ") lines))]
    (testing "a line per layer and row, then the legend"
      (is (= (inc (* 4 11)) (count lines)))
      (is (str/starts-with? (peek lines) "legend ")))
    (testing "the feet marked, the lava beside them, unloaded cells as ?"
      (is (= "?????@L????" (subs feet 8))))
    (testing "the floor is netherrack, its own letter in the legend"
      (is (= "aaaaaaaaaaa" (subs floor 8)))
      (is (str/includes? (peek lines) "a=netherrack"))))
  (is (= [] (scene/slice {})) "no position, no slice"))
