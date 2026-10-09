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
  (is (not (blocks/solid? -1)))
  (is (not (blocks/solid? 999999))))

(deftest logs
  (is (every? blocks/log? (range 136 163)))
  (is (not (blocks/log? 252)))
  (is (not (blocks/log? 0)))
  (is (blocks/log-item? (:oak_log blocks/items)))
  (is (= 134 (:oak_log blocks/items)))
  (is (= 71 blocks/item-entity-type)))
