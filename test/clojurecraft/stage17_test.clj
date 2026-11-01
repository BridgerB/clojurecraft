(ns clojurecraft.stage17-test
  "Stage 17's target bars against the model's flying blazes (issue #262): the four scenes the unit
   gate runs with still blazes (:still-blazes?, because the bot as it is does not pass them against
   blazes that fly, volley and burn), here with the blazes as the recordings show them. Each is
   marked ^:target: left out of the default run (clojure -M:test) and run by name
   (clojure -M:test -v clojurecraft.stage17-test/<name>), a failing bar for the blaze work to
   clear; when one passes it moves to its twin's place and loses the mark."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [clojurecraft.blaze :as blaze]
            [clojurecraft.blaze-test :as bt]
            [clojurecraft.dimension :as dimension]
            [clojurecraft.fixtures :as fx]
            [clojurecraft.game :as game]
            [clojurecraft.intent :as intent]
            [clojurecraft.inventory :as inventory]
            [clojurecraft.mob :as mob]
            [clojurecraft.plan :as plan]
            [clojurecraft.sim :as sim]
            [clojurecraft.sim-test :as st]
            [clojurecraft.world :as world]))

(use-fixtures :once fx/instrumented)

(def kit
  "The iron sword, six rods and the four iron pieces a stage-17 bot wears."
  {[:hotbar 0] {:item (st/item :iron_sword) :count 1}
   [:hotbar 1] {:item (st/item :blaze_rod) :count 6}
   [:armour 0] {:item (st/item :iron_helmet) :count 1}
   [:armour 1] {:item (st/item :iron_chestplate) :count 1}
   [:armour 2] {:item (st/item :iron_leggings) :count 1}
   [:armour 3] {:item (st/item :iron_boots) :count 1}})

(deftest ^:target watched-by-three-flying-blazes-the-bot-hides
  (let [[w sim] (sim/run st/step
                         (game/init fx/opts)
                         (sim/init {:column (world/column-bytes st/nether-wall dimension/nether)
                                    :spawn [8.5 64.0 6.5]
                                    :dimension dimension/nether
                                    :difficulty :easy
                                    :food 20
                                    :inventory {[:hotbar 0] {:item (st/item :iron_sword) :count 1}}
                                    :mobs [{:type :blaze :pos [14.5 65.0 6.5]}
                                           {:type :blaze :pos [14.5 65.0 7.5]}
                                           {:type :blaze :pos [14.5 65.0 8.5]}]})
                         #(or (and (= :cover (get-in % [:plan/last :intent/for]))
                                   (intent/done? (:plan/last %)))
                              (plan/failed? %)
                              (pos? (:stats/deaths % 0)))
                         30000
                         {:event/kind :go :go/goals [:blaze-rods]})]
    (is (intent/done? (:plan/last w)) (pr-str (plan/summary w)))
    (is (zero? (:stats/deaths w 0)) "alive")
    (is (pos? (:sim/health sim)))
    (is (= [] (:sim/violations sim)))))

(deftest ^:target three-flying-blazes-at-hand-the-nearest-is-killed
  (let [[w sim] (sim/run st/step
                         (game/init fx/opts)
                         (sim/init {:column (world/column-bytes st/nether-wall dimension/nether)
                                    :spawn [8.5 64.0 6.5]
                                    :dimension dimension/nether
                                    :difficulty :easy
                                    :food 20
                                    :inventory {[:hotbar 0] {:item (st/item :iron_sword) :count 1}}
                                    :mobs [{:type :blaze :pos [11.5 65.0 5.5]}
                                           {:type :blaze :pos [11.5 65.0 6.5]}
                                           {:type :blaze :pos [11.5 65.0 7.5]}]})
                         #(or (and (= :fight (get-in % [:plan/last :intent/kind]))
                                   (intent/done? (:plan/last %)))
                              (plan/failed? %)
                              (pos? (:stats/deaths % 0)))
                         30000
                         {:event/kind :go :go/goals [:blaze-rods]})]
    (is (intent/done? (:plan/last w)) (pr-str (plan/summary w)))
    (is (zero? (:stats/deaths w 0)) "alive")
    (is (= 2 (count (blaze/blazes w))) "one of the three killed")
    (is (= [] (:sim/violations sim)))))

(deftest ^:target the-seventh-rod-from-a-spawner-and-flying-blazes
  (let [[w sim] (sim/run st/step
                         (game/init fx/opts)
                         (sim/init {:column (world/column-bytes (assoc st/nether-wall
                                                                       st/blaze-spawner-cell
                                                                       mob/spawner-block)
                                                                dimension/nether)
                                    :spawn [8.5 64.0 8.5]
                                    :dimension dimension/nether
                                    :difficulty :easy
                                    :food 20
                                    :seed 3
                                    :inventory kit})
                         #(or (plan/done? %) (plan/failed? %) (pos? (:stats/deaths % 0)))
                         240000
                         {:event/kind :go :go/goals [:blaze-rods]})]
    (is (plan/done? w) (pr-str (plan/summary w)))
    (is (>= (inventory/item-count w blaze/rod) blaze/rods-wanted))
    (is (zero? (:stats/deaths w 0)) "alive")
    (is (= [] (:sim/violations sim)))))

(deftest ^:target the-seventh-rod-from-five-flying-blazes
  (let [[w sim] (sim/run st/step
                         (game/init fx/opts)
                         (sim/init {:column (world/column-bytes {} dimension/nether)
                                    :spawn [8.5 64.0 8.5]
                                    :dimension dimension/nether
                                    :difficulty :easy
                                    :food 20
                                    :seed 3
                                    :inventory {[:hotbar 0] (bt/item :iron_sword 1)
                                                [:hotbar 1] (bt/item :blaze_rod 6)}
                                    :mobs bt/blazes})
                         #(or (plan/done? %) (plan/failed? %))
                         120000
                         {:event/kind :go :go/goals [:blaze-rods]})]
    (is (plan/done? w) (pr-str (plan/summary w)))
    (is (>= (inventory/item-count w blaze/rod) blaze/rods-wanted))
    (is (pos? (:sim/health sim)) "alive")
    (is (= [] (:sim/violations sim)))))
