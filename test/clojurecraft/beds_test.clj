(ns clojurecraft.beds-test
  "The bed as a weapon: its facts and the pure geometry, and the dragon row's steps with beds held."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [clojurecraft.beds :as beds]
            [clojurecraft.fixtures :as fx]
            [clojurecraft.gym :as gym]
            [clojurecraft.physics :as physics]
            [clojurecraft.recipe :as recipe]))

(use-fixtures :once fx/instrumented)

(deftest the-blast-follows-the-games-formula
  (is (< (abs (- 30.8 (beds/blast-damage 4.5 1.0))) 0.2) "4.5 out in the open: a lethal 31")
  (is (== 0.0 (beds/blast-damage 10.0 1.0)) "past the range: nothing")
  (is (< (beds/blast-damage 4.5 0.2) (beds/blast-damage 4.5 1.0)) "behind blocks: less")
  (is (== 1.0 (beds/blast-damage 9.99999 0.0)) "the game's one point at the edge"))

(deftest a-bed-is-held-by-its-name
  (let [w {:player/inventory {3 {:item (recipe/item-id :white_bed) :count 5}
                              4 {:item (recipe/item-id :cobblestone) :count 64}}}]
    (is (= :white_bed (beds/held w)))
    (is (nil? (beds/held {:player/inventory {4 {:item (recipe/item-id :cobblestone) :count 64}}})))
    (is (nil? (beds/held {:player/inventory {3 {:item (recipe/item-id :white_bed) :count 0}}})))))

(deftest the-bot-stands-beyond-the-head-on-the-line-from-the-fountain
  (let [e {:entity/pos [0.5 66.0 0.5] :entity/yaw 270.0}
        [hx hz] (beds/head-point e)
        [sx sz] (beds/stand-point [hx hz] [0.5 0.5])]
    (is (< (abs (- 6.5 (physics/horizontal-distance [0.5 0 0.5] [hx 0 hz]))) 1.0e-6)
        "the head's ground point is head-out from the fountain")
    (is (< (abs (- (+ 6.5 beds/standoff) (physics/horizontal-distance [0.5 0 0.5] [sx 0 sz])))
           1.0e-6)
        "the stand is standoff further out on the same line")))

(deftest the-dragon-beds-row-gives-six-beds-and-resistance
  (let [row (gym/gym "dragon-beds")]
    (is (= :dragon (:gym/goal row)))
    (is (some #(= "minecraft:white_bed 6" %) (:gym/prereqs row)))
    (is (some #(re-find #"resistance" %) (:gym/summon row)))))

(deftest a-wall-between-takes-the-blast-down-to-a-point
  (is (< 30.0 (beds/expected-damage 4.5 0)) "in the open at 4.5 blocks: a death")
  (is (< (beds/expected-damage 4.5 1) (beds/expected-damage 4.5 0)) "one block: less")
  (is (== 1.0 (beds/expected-damage 4.5 2)) "the feet's and the eye's cells: a point")
  (is (== 1.0 (beds/expected-damage 4.5 5)) "more than two count as two")
  (is (== (beds/expected-damage 4.5 0) (beds/expected-damage 4.5 -1)) "never less than none"))

(deftest a-cell-in-the-dragons-box-is-no-place-for-a-block
  ;; gym chain-w19 dragon-beds run 2 (plan run 40918096417): `:place` three times at [-8 65 6],
  ;; :rejected, the dragon at [0 65.86 0]; its bed at [7 61 1], under its box, went off
  (let [d {:entity/pos [0.0 65.86 0.0]}]
    (is (beds/in-dragon-box? d [-8 65 6]) "the rim cell the wall was laid at")
    (is (not (beds/in-dragon-box? d [7 61 1])) "the cell the bed was laid at: under the box")
    (is (not (beds/in-dragon-box? d [-9 65 6])) "past the box's side")
    (is (not (beds/in-dragon-box? d [0 74 0])) "over its top")))
