(ns clojurecraft.lava-avoid-test
  "Lava kept out of every move that is not a route's: a straight step (intent/unsafe-ahead?,
   toward-safe), a dig's new floor (path/lava-under?, stairs/safe?), and magma as no floor. The
   states of real bots that went into lava on 5d894db."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojurecraft.blocks :as blocks]
            [clojurecraft.fixtures :as fx]
            [clojurecraft.game :as game]
            [clojurecraft.intent :as intent]
            [clojurecraft.path :as path]
            [clojurecraft.stairs :as stairs]
            [clojurecraft.world :as world]))

(use-fixtures :once fx/instrumented)

(def lava (blocks/default-state :lava))
(def magma (blocks/default-state :magma_block))

(defn standing
  "A bot in play with its feet at [8.5 64 8.5] on the stone floor of one column topped by blocks."
  [blocks]
  (-> (game/init fx/opts)
      (assoc :bot/phase :play
             :player/pos [8.5 64.0 8.5]
             :player/loaded? true
             :player/on-ground? true
             :player/health 20.0
             :world/chunks {[0 0] (world/column blocks)})))

(def pit
  "A ledge east of the feet: from x 10 the floor is dug out for three cells, lava under that."
  (into {}
        (concat (for [x (range 10 14) z (range 6 11) y (range 61 64)] [[x y z] blocks/air])
                (for [x (range 10 14) z (range 6 11)] [[x 60 z] lava]))))

(deftest a-straight-step-off-a-ledge-into-lava-is-not-taken
  ;; real Clj_r34 (botlogs 2026-11-10-21, 42891612798-r6-b6): :fight (hunt/fight-at-hand) on an
  ;; enderman, the approach straight at it, y 41.5 to 31.2 in a second into lava, then 15 s of fire
  (let [w (standing pit)
        across [12.5 64.0 8.5]]
    (is (not (intent/ledge-ahead? w across)) "the ledge look (0.6 ahead) still sees a floor")
    (is (intent/lava-ahead? w across) "the cell at x 10 has no landing: lava under the drop")
    (is (intent/unsafe-ahead? w across))
    (is (nil? (:control/forward? (intent/toward-safe w across 0.0))) "it stands and looks")
    (is (some? (:control/look (intent/toward-safe w across 0.0))))
    (testing "the same step over a floor is taken"
      (let [floored (standing {})]
        (is (not (intent/unsafe-ahead? floored across)))
        (is (true? (:control/forward? (intent/toward-safe floored across 0.0))))))
    (testing "a cell beside lava is not stepped into either"
      (let [beside (standing {[10 64 8] lava})]
        (is (intent/lava-ahead? beside across) "the cell at x 9 has lava beside it")
        (is (intent/lava-ahead? (standing {[9 63 8] lava}) across)
            "nor one with lava for a floor")))))

(deftest a-stair-down-onto-a-floor-over-lava-is-refused
  ;; real Clj_r08 and Clj_r05 (42891612798-r6-b3 and -b2): a :burrow's stairs down, then a fall
  ;; through into lava (y 41 to 30), 15 s of fire after it
  (let [cells (stairs/tall-stair-cells [8 64 8] [1 0] 3)]
    (is (= [9 63 8] (last cells)) "the stair's new feet")
    (is (stairs/safe? (standing {}) cells) "over rock it is cut")
    (is (not (stairs/safe? (standing {[9 60 8] lava}) cells))
        "lava two under the new floor: refused")
    (is (not (stairs/safe? (standing {[9 59 8] lava}) cells)) "three under: refused")
    (is (stairs/safe? (standing {[9 58 8] lava}) cells) "four under is past path/lava-depth")
    (is (path/lava-under? (standing {[9 59 8] lava}) [9 62 8]))))

(deftest magma-is-no-floor-for-a-route
  ;; real Clj_r29 (42812754059-r2-b5): a hunt/search walk down a slope onto magma (hot_floor), then
  ;; into lava at y 32
  (is (path/standable? (standing {}) [9 64 8]))
  (is (not (path/standable? (standing {[9 63 8] magma}) [9 64 8]))
      "magma under the feet: the route never stands there (path/awkward-types :magma)"))
