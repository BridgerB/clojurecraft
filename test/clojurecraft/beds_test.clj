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
