(ns clojurecraft.blocks-test
  (:require [clojure.test :refer [deftest is]]
            [clojurecraft.blocks :as blocks]))

(deftest names
  (is (= :air (blocks/name-of 0)))
  (is (= :oak_log (blocks/name-of 136)))
  (is (= :oak_log (blocks/name-of 138)))
  (is (= :cave_air (blocks/name-of 15293)))
  (is (nil? (blocks/name-of 999999))))

(deftest solidity
  (is (blocks/solid? 1) "stone")
  (is (blocks/solid? 8) "grass_block")
  (is (blocks/solid? 136) "oak_log")
  (is (blocks/solid? 252) "oak_leaves")
  (is (not (blocks/solid? 0)) "air")
  (is (not (blocks/solid? 15293)) "cave_air")
  (is (not (blocks/solid? 86)) "water")
  (is (not (blocks/solid? 2248)) "short_grass")
  (is (not (blocks/solid? 6919)) "snow layer")
  (is (not (blocks/solid? (blocks/default-state :nether_portal)))
      "a nether portal: the body walks into it to change dimension (#8)")
  (is (not (blocks/solid? -1)))
  (is (not (blocks/solid? 999999))))

(deftest logs
  (is (every? blocks/log? (range 136 163)))
  (is (not (blocks/log? 252)))
  (is (not (blocks/log? 0)))
  (is (every? blocks/log? (blocks/states-of :warped_stem))
      "a warped stem is a log: it crafts to planks")
  (is (every? blocks/log? (blocks/states-of :crimson_hyphae)))
  (is (not (blocks/log? (blocks/default-state :pumpkin_stem))) "a pumpkin's stem is a plant")
  (is (blocks/log-item? (:warped_stem blocks/items)))
  (is (blocks/log-item? (:oak_log blocks/items)))
  (is (= 134 (:oak_log blocks/items)))
  (is (= 71 blocks/item-entity-type)))

(deftest fluids
  (is (= {:fluid/kind :water :fluid/level 0} (blocks/fluid 86)) "a water source")
  (is (= {:fluid/kind :water :fluid/level 7} (blocks/fluid 93)) "flowing water")
  (is (= {:fluid/kind :water :fluid/level 15} (blocks/fluid 101)))
  (is (= {:fluid/kind :lava :fluid/level 0} (blocks/fluid 102)) "a lava source")
  (is (= {:fluid/kind :water :fluid/level 0} (blocks/fluid 27055))
      "a waterlogged copper grate is a water source")
  (is (nil? (blocks/fluid 1)) "stone")
  (is (nil? (blocks/fluid nil)))
  (is (blocks/source? 86))
  (is (not (blocks/source? 93)))
  (is (not (blocks/source? 1))))

(deftest a-bubble-column-takes-a-block
  ;; sim race 39068773618 (issue #44, world R4/37): the cast bridged a stream over magma with
  ;; cobblestone into its bubble column, which the model refused three times, :rejected
  (is (blocks/placeable-into? (blocks/default-state :bubble_column))
      "the game's replaceable column")
  (is (blocks/placeable-into? 86) "water still")
  (is (not (blocks/placeable-into? (blocks/default-state :stone))) "stone is no place")
  (is (not (blocks/placeable-into? (blocks/default-state :kelp))) "kelp keeps its cell"))
