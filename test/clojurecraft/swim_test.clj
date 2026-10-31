(ns clojurecraft.swim-test
  "The :swim-out executor's choices, one tick at a time, in small built worlds."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [clojurecraft.blocks :as blocks]
            [clojurecraft.fixtures :as fx]
            [clojurecraft.game :as game]
            [clojurecraft.intent :as intent]
            [clojurecraft.swim :as swim]
            [clojurecraft.trap :as trap]
            [clojurecraft.world :as world]))

(use-fixtures :once fx/instrumented)

(deftest a-water-plant-is-not-a-dry-bank
  (let [bank [5 64 5]
        at (fn [cells] (assoc (game/init fx/opts) :world/chunks {[0 0] (world/column cells)}))]
    (doseq [plant [:seagrass :tall_seagrass :kelp :kelp_plant :bubble_column]
            cell [bank [5 65 5]]
            state (blocks/states-of plant)]
      (is (not (swim/bank? (at {cell state}) bank)) (str plant " at " cell)))
    (is (swim/bank? (at {bank (blocks/default-state :short_grass)}) bank)
        "a dry plant is still a bank")))

(deftest a-kept-bank-containing-seagrass-is-dropped
  (let [bank [5 64 5]
        swim {:intent/kind :swim-out
              :intent/for :retreat
              :intent/bank bank
              :intent/target bank
              :intent/best-dist 0.0
              :intent/best-tick 0
              :intent/started 0
              :intent/status :active}
        w (assoc (game/init fx/opts)
                 :bot/phase :play
                 :player/pos [5.5 64.0 5.5]
                 :player/loaded? true
                 :player/on-ground? true
                 :player/in-water? true
                 :time/tick 50
                 :world/chunks {[0 0] (world/column {bank (blocks/default-state :seagrass)})}
                 :plan/intent swim)
        tick {:event/kind :tick :event/now 2500 :event/rand 0.5}
        dropped (intent/run w swim tick)
        i (:plan/intent dropped)]
    (is (= :active (:intent/status i)) "re-plan instead of failing :stuck")
    (is (nil? (:intent/bank i)))
    (is (nil? (:intent/best-dist i)))
    (let [picked (intent/run dropped i tick)
          b (:intent/bank (:plan/intent picked))]
      (is (some? b))
      (is (not= bank b))
      (is (swim/bank? picked b)))))

(deftest a-bank-flooded-since-it-was-picked-is-dropped-for-another
  ;; race 38805758078 runner 1 bot Clj_r44 (issue #157) and race 38709226128 runner 8 bot
  ;; Clj_r31 (issue #156), replayed in probe 38830884734: the swim-out reached the bank it had
  ;; picked a block off, water had flowed into it, the trap held, and stuck-ticks later it dug
  ;; over its head and failed :stuck
  (let [bank [5 64 5]
        swim {:intent/kind :swim-out
              :intent/bank bank
              :intent/target bank
              :intent/best-dist 0.0
              :intent/best-tick 0
              :intent/started 0
              :intent/status :active}
        w (assoc (game/init fx/opts)
                 :bot/phase :play
                 :player/pos [5.5 64.0 5.5]
                 :player/loaded? true
                 :player/on-ground? true
                 :player/in-water? true
                 :time/tick 10
                 :world/chunks {[0 0] (world/column {bank blocks/water-base})}
                 :plan/intent swim)
        tick {:event/kind :tick :event/now 1000 :event/rand 0.5}]
    (is (swim/flooded? w bank))
    (is (not (swim/flooded? w [8 64 5])) "a dry cell on the stone beside it")
    (let [w (intent/run w swim tick)
          i (:plan/intent w)]
      (is (= :active (:intent/status i)))
      (is (nil? (:intent/bank i)) "dropped, not kept until the swim-out is stuck")
      (let [b (:intent/bank (:plan/intent (intent/run w i tick)))]
        (is (some? b) "another bank picked")
        (is (not= bank b))))))

(deftest at-sea-with-no-bank-in-reach-the-escape-swims-for-the-shore
  ;; sim race 38811426215 runner 8 (issue #89, worlds R4/21 and R1/32): bots under the sea floor,
  ;; land 33 and 40 blocks off; an escape with no bank within reach rose and floated until it
  ;; timed out
  (let [sea (fn [xs]
              (into {} (for [x xs y (range 55 64) z (range 16)] [[x y z] blocks/water-base])))
        swim {:intent/kind :swim-out :intent/status :active}
        w (assoc (game/init fx/opts)
                 :bot/phase :play
                 :player/pos [8.5 62.0 8.5]
                 :player/loaded? true
                 :player/in-water? true
                 :player/eye-in-water? true
                 :time/tick 10
                 :world/chunks
                 {[0 0] (world/column (sea (range 16)))
                  [1 0] (world/column (sea (range 16)))
                  [2 0] (world/column (sea (range 8)))
                  [3 0] (world/column {})}
                 :plan/intent swim)
        tick {:event/kind :tick :event/now 1000 :event/rand 0.5}
        w2 (intent/run w swim tick)
        i (:plan/intent w2)]
    (is (nil? (:intent/bank i)) "no bank within reach")
    (is (= [40 64 8] (:intent/shore i)) "the shore east, at the water's surface")
    (is (= [true true]
           [(:control/forward? (:player/controls w2)) (:control/jump? (:player/controls w2))])
        "swimming toward it, rising")))

(deftest the-shore-swum-for-is-kept-while-it-is-dry-ground
  ;; sim race 39550831606 runner 4 (issue #177, world R4/1): sixteen blocks from a shore on each
  ;; side, the first bearing at that distance changed with the cell the feet were in, and the
  ;; escape dithered about one cell for 120 s, three times, :no-escape
  (let [sea (fn [xs]
              (into {} (for [x xs y (range 55 64) z (range 16)] [[x y z] blocks/water-base])))
        w (assoc (game/init fx/opts)
                 :world/chunks
                 {[0 0] (world/column (sea (range 8 16)))
                  [1 0] (world/column (sea (range 16)))
                  [2 0] (world/column (sea (range 9)))})
        feet [24 62 8]]
    (is (= [41 64 8] (swim/far-shore w feet 64)) "east and west tie at seventeen, east first")
    (is (= [7 64 8] (swim/far-shore w feet 64 [7 64 8])) "the west shore chosen before is kept")
    (is (= [41 64 8] (swim/far-shore w feet 64 [20 64 8]))
        "unless its column is water: a shore no more")
    (let [roofed (assoc w
                        :world/chunks
                        {[0 0] (world/column (sea (range 8 16)))
                         [1 0] (world/column (sea (range 16)))
                         [2 0] (world/column (merge (sea (range 16))
                                                    (into {}
                                                          (for [x (range 16)]
                                                            [[x 100 8]
                                                             (blocks/default-state
                                                              :cobblestone)]))))})]
      (is (= [7 64 8] (swim/far-shore roofed feet 64))
          "a block high over the sea does not make the sea round it a shore"))))

(deftest a-deep-swim-rises-into-reach-before-notching
  (let [swim {:intent/kind :swim-out :intent/status :active}
        cells (merge (into {} (for [y (range 39 55)] [[5 y 5] blocks/water-base]))
                     (into {} (for [y (range 55 64)] [[5 y 5] blocks/air])))
        w (assoc (game/init fx/opts)
                 :bot/phase :play
                 :player/pos [5.5 39.0 5.5]
                 :player/loaded? true
                 :player/in-water? true
                 :player/eye-in-water? true
                 :world/chunks {[0 0] (world/column cells)}
                 :plan/intent swim)
        tick {:event/kind :tick :event/now 1000 :event/rand 0.5}
        rising (intent/run w swim tick)]
    (is (nil? (swim/notch w [5 39 5] 55)) "the float level is not the current eye level")
    (is (nil? (:intent/child (:plan/intent rising))))
    (is (:control/jump? (:player/controls rising)))
    (is (some? (swim/notch (assoc w :player/pos [5.5 53.0 5.5]) [5 53 5] 55))
        "near the surface the wall can be notched")
    (let [old (assoc swim
                     :intent/child
                     (assoc (swim/child-dig [4 55 5])
                            :intent/status :failed
                            :intent/reason :out-of-reach))
          recovered (intent/run (assoc w :plan/intent old) old tick)]
      (is (= :active (:intent/status (:plan/intent recovered))))
      (is (nil? (:intent/child (:plan/intent recovered))))
      (is (:control/jump? (:player/controls recovered)))
      (let [next (intent/run recovered (:plan/intent recovered) tick)]
        (is (nil? (:intent/child (:plan/intent next))))
        (is (:control/jump? (:player/controls next)))))))

(deftest a-notch-checks-reach-to-the-solid-upper-cell
  (let [w (assoc (game/init fx/opts)
                 :player/pos [5.5 64.0 5.5]
                 :world/chunks
                 {[0 0] (world/column {[8 65 7] (blocks/default-state :stone)
                                       [8 67 7] (blocks/default-state :stone)})})]
    (is (nil? (swim/notch w [5 64 5] 66)) "the lower cell is air and only it is in reach")
    (is (= [8 67 7] (swim/notch (assoc w :player/pos [5.5 64.5 5.5]) [5 64 5] 66))
        "the solid upper cell is dug once it is in reach")))

(deftest a-crossing-swims-at-the-surface-and-ends-on-the-far-side
  ;; issue #87 (world R3/4): the :cross wood/crossing starts from an island
  (let [lake (into {}
                   (for [x (range 16)
                         z (range 16)
                         y (range 60 64)
                         :when (< 3 x 13)]
                     [[x y z] blocks/water-base]))
        cross {:intent/kind :cross :intent/target [14 64 8] :intent/status :active}
        at (fn [pos in-water?]
             (assoc (game/init fx/opts)
                    :bot/phase :play
                    :player/pos pos
                    :player/loaded? true
                    :player/on-ground? (not in-water?)
                    :player/in-water? in-water?
                    :time/tick 10
                    :world/chunks {[0 0] (world/column lake)}
                    :plan/intent cross))
        tick {:event/kind :tick :event/now 1000 :event/rand 0.5}
        swimming (intent/run (at [6.5 63.0 8.5] true) cross tick)]
    (is (= :active (:intent/status (:plan/intent swimming))))
    (is (true? (:control/jump? (:player/controls swimming))) "jump held: the eye stays in air")
    (is (true? (:control/forward? (:player/controls swimming))))
    (is (= :active
           (:intent/status (:plan/intent (intent/run (at [2.5 64.0 8.5] false) cross tick))))
        "still on the near shore, never wet: not done")
    (let [i (assoc cross :intent/wet? true :intent/from-dist 11.0 :intent/started 0)
          landed (assoc (at [13.5 64.0 8.5] false) :plan/intent i)]
      (is (= :done (:intent/status (:plan/intent (intent/run landed i tick))))
          "wet, then on dry land ten nearer the trunk: across"))))

(deftest a-wall-between-the-swim-and-a-level-bank-is-dug
  ;; race 38805758078 runner 1 bot Clj_r44 (issues #156, #157), replayed in probe 38830884734: in
  ;; a flooded tunnel at y 16 the bank lay two blocks off at the feet's height with a block
  ;; between at the feet; the swim dug the ceiling over its head, then named nothing, :stuck
  (let [cells {[5 60 5] blocks/water-base
               [5 61 5] blocks/water-base
               [4 61 5] blocks/air
               [3 60 5] blocks/air
               [3 61 5] blocks/air}
        at (fn [extra]
             (assoc (game/init fx/opts)
                    :bot/phase :play
                    :player/pos [5.5 60.0 5.5]
                    :player/loaded? true
                    :world/chunks {[0 0] (world/column (merge cells extra))}))]
    (is (= [4 60 5] (swim/obstacle (at {}) [5 60 5] [3 60 5])) "the wall at the feet is dug")
    (is (= [4 62 5] (swim/obstacle (at {[3 62 5] blocks/air}) [5 60 5] [3 61 5]))
        "under a higher bank the step stays, but the overhang blocking the lift is dug")))

(deftest a-bank-overhang-is-cleared-without-digging-its-step
  ;; issue #156, race 38999008545 Clj_r06: the bank's feet and head cells were clear,
  ;; but packed ice above the step stopped the body's lift out of the water.
  (let [w (assoc (game/init fx/opts)
                 :world/chunks
                 {[0 0] (world/column {[5 62 5] blocks/water-base
                                       [6 63 5] blocks/air
                                       [6 64 5] (blocks/default-state :packed_ice)})})]
    (is (= [6 64 5] (swim/obstacle w [5 62 5] [8 63 5])))
    (is (nil? (swim/obstacle w [5 62 5] nil)) "no bank: only the cap over the body is considered")))

(deftest a-bank-left-below-a-rising-swim-is-dropped
  ;; issue #156, race 38999008545 Clj_r06: a bank kept at y 49 remained dry while
  ;; the swim rose to y 62 directly over it, then failed :stuck at distance zero.
  (let [bank [5 49 5]
        swim {:intent/kind :swim-out
              :intent/bank bank
              :intent/target bank
              :intent/best-dist 0.0
              :intent/best-tick 0
              :intent/status :active}
        w (assoc (game/init fx/opts)
                 :player/pos [5.5 62.0 5.5]
                 :player/in-water? true
                 :time/tick 50
                 :world/chunks
                 {[0 0] (world/column
                         {bank blocks/air [5 50 5] blocks/air [5 62 5] blocks/water-base})}
                 :plan/intent swim)
        tick {:event/kind :tick :event/now 2500 :event/rand 0.5}
        i (:plan/intent (intent/run w swim tick))]
    (is (swim/bank? w bank) "still dry, but no longer within the swim's height band")
    (is (= :active (:intent/status i)))
    (is (nil? (:intent/bank i)))
    (is (nil? (:intent/best-dist i)))
    (is (nil? (:intent/best-tick i)))
    (is (= bank (get-in (intent/run (assoc w :time/tick 10) swim tick) [:plan/intent :intent/bank]))
        "do not redirect a rise before its progress has stalled")))

(deftest a-diagonal-bank-approach-clears-the-side-wall
  (let [w (assoc (game/init fx/opts)
                 :world/chunks
                 {[0 0] (world/column {[5 62 5] blocks/water-base
                                       [5 63 5] blocks/air
                                       [5 62 6] blocks/water-base
                                       [5 63 6] blocks/air
                                       [5 64 6] blocks/air
                                       [6 63 5] (blocks/default-state :packed_ice)})})]
    (is (= [6 63 5] (swim/obstacle w [5 62 5] [6 63 10]))
        "the larger axis is clear, but the body catches the wall along the other axis")))

(deftest a-bank-approach-clears-a-wall-touching-the-bodys-edge
  (let [w (assoc (game/init fx/opts)
                 :player/pos [5.71 62.0 5.7]
                 :world/chunks
                 {[0 0] (world/column {[5 62 5] blocks/water-base
                                       [5 63 5] blocks/air
                                       [5 62 6] blocks/water-base
                                       [5 63 6] blocks/air
                                       [5 64 6] blocks/air
                                       [6 63 5] blocks/air
                                       [6 63 6] (blocks/default-state :packed_ice)})})]
    (is (= [6 63 6] (swim/obstacle w [5 62 5] [5 63 10]))
        "the body extends into another column even when the bank is straight ahead")))

(deftest a-crossing-clears-a-wall-and-continues-after-the-dig
  ;; issue #156, race 39060308623 Clj_r14: a crossing pressed into packed ice,
  ;; sometimes out of water during a bob, and spent three attempts on :stuck.
  (let [cross {:intent/kind :cross
               :intent/target [14 64 5]
               :intent/status :active
               :intent/started 0
               :intent/best-dist 8.0
               :intent/best-tick 0}
        w (assoc (game/init fx/opts)
                 :player/pos [5.7 64.0 5.5]
                 :player/on-ground? true
                 :player/horizontal-collision? true
                 :time/tick 200
                 :world/chunks
                 {[0 0] (world/column {[6 64 5] (blocks/default-state :packed_ice)
                                       [6 65 5] (blocks/default-state :packed_ice)})}
                 :plan/intent cross)
        tick {:event/kind :tick :event/now 10000 :event/rand 0.5}]
    (doseq [wet? [false true]]
      (let [i (:plan/intent (intent/run (assoc w :player/in-water? wet?) cross tick))]
        (is (= :active (:intent/status i)))
        (is (= :dig (get-in i [:intent/child :intent/kind])))
        (is (= [6 65 5] (get-in i [:intent/child :intent/target])))
        (let [dug (assoc-in i [:intent/child :intent/status] :done)
              resumed (:plan/intent (intent/run (assoc w :plan/intent dug) dug tick))]
          (is (= :active (:intent/status resumed)) "a dig does not finish the crossing")
          (is (nil? (:intent/child resumed)))
          (is (nil? (:intent/best-tick resumed))))))
    (let [afloat (assoc w :player/in-water? true :player/on-ground? false)
          sinking (intent/run afloat cross tick)
          i (:plan/intent sinking)
          down (assoc sinking :player/on-ground? true)
          digging (:plan/intent (intent/run down i tick))]
      (is (= [6 65 5] (:intent/dig-when-down i)))
      (is (true? (get-in sinking [:player/controls :control/sneak?])))
      (is (= [6 65 5] (get-in digging [:intent/child :intent/target])))
      (is (nil? (:intent/dig-when-down digging))))))

(deftest a-crossing-stuck-in-the-water-retreats-to-a-bank
  ;; sim race 38844589684 runner 6 (issue #57, world R1/30 seed 4): forty blocks over, the swim
  ;; met a far bank too high to climb out onto and failed :stuck three times; now it swims for
  ;; the nearest bank and the crossing ends there
  (let [lake (into {}
                   (for [x (range 16)
                         z (range 16)
                         y (range 60 64)
                         :when (< 3 x 13)]
                     [[x y z] blocks/water-base]))
        cross {:intent/kind :cross
               :intent/target [14 64 8]
               :intent/status :active
               :intent/started 0
               :intent/wet? true
               :intent/from-dist 40.0
               :intent/best-dist 6.0
               :intent/best-tick 0}
        w (assoc (game/init fx/opts)
                 :bot/phase :play
                 :player/pos [8.5 63.0 8.5]
                 :player/loaded? true
                 :player/on-ground? false
                 :player/in-water? true
                 :time/tick 200
                 :world/chunks {[0 0] (world/column lake)}
                 :plan/intent cross)
        tick {:event/kind :tick :event/now 1000 :event/rand 0.5}
        i (:plan/intent (intent/run w cross tick))]
    (is (= :active (:intent/status i)) "not failed :stuck")
    (is (= [:swim-out :retreat] ((juxt :intent/kind :intent/for) (:intent/child i))))
    (let [ashore (assoc cross
                        :intent/child
                        {:intent/kind :swim-out :intent/for :retreat :intent/status :done})]
      (is (= :done
             (:intent/status (:plan/intent (intent/run (assoc w :plan/intent ashore) ashore tick))))
          "on the bank: the crossing is over"))))

(deftest a-flee-never-ends-in-the-lava-it-flees
  ;; sim race 38844683780 runner 7 (issue #230, world R2/9): lava from a source ten over flowed
  ;; into the pocket the bot smelted in; it fled to a cell the flow covered and timed out
  ;; :no-footing, three times
  (let [feet [5 64 5]
        flow (inc blocks/lava-base)
        lava (into {} (for [dx (range -2 3) dz (range -2 3)] [[(+ 5 dx) 64 (+ 5 dz)] flow]))
        w (assoc (game/init fx/opts)
                 :bot/phase :play
                 :player/pos [5.5 64.0 5.5]
                 :player/loaded? true
                 :player/in-lava? true
                 :world/chunks {[0 0] (world/column lava)})
        cell (swim/footing w feet)]
    (is (some? cell))
    (is (swim/lava-free? w cell) "out of the flow")
    (is (not (swim/lava-beside? w cell)) "and clear of its edge when such a cell is in reach")))

(deftest a-flee-lands-on-its-footing-instead-of-bouncing
  ;; sim races 38844683780 runner 7 and 38843557186 runner 3 (issues #230, #231): out of the
  ;; lava, jump still held bounced the body off the footing every tick, the trap never cleared
  ;; and the flee timed out :no-footing
  (let [target [6 64 5]
        w (assoc (game/init fx/opts)
                 :bot/phase :play
                 :player/pos [5.5 64.0 5.5]
                 :player/loaded? true
                 :world/chunks {[0 0] (world/column {})})]
    (is (:control/jump? (swim/flee-controls (assoc w :player/in-lava? true) target))
        "in the lava: jump")
    (is (:control/forward? (swim/flee-controls w target)) "out of it: on toward the footing")
    (is (not (:control/jump? (swim/flee-controls w target))) "without jumping")
    (is (= {} (swim/flee-controls (assoc w :player/pos [6.5 64.0 5.5]) target))
        "centred on it: still, so the body lands")))

(deftest a-flee-clears-its-trap-on-a-placed-floor
  ;; Issue #339: the flee landed on cobblestone over lava, but floating-point cell coordinates
  ;; missed the block update, so the chunk's old lava kept the trap until :no-footing.
  (doseq [[pos floor chunk local] [[[5.5 64.0 5.5] [5 63 5] [0 0] [5 63 5]]
                                   [[-0.5 -54.0 -0.5] [-1 -55 -1] [-1 -1] [15 -55 15]]]]
    (let [w (assoc (game/init fx/opts)
                   :bot/phase :play
                   :player/pos pos
                   :player/on-ground? true
                   :world/chunks {chunk (world/column {local blocks/lava-base})}
                   :world/blocks {floor (blocks/default-state :cobblestone)}
                   :plan/trap :lava
                   :plan/trap-at [0 0]
                   :plan/dry-ticks (dec trap/dry-ticks-to-clear))]
      (is (trap/dry-land? w) "the placed floor, not the chunk's old lava")
      (is (nil? (:plan/trap (trap/trapped-tick w))) "dry land clears the escape goal")
      (is (not (trap/dry-land? (assoc w :world/blocks {floor blocks/air})))
          "a missing floor is not dry land"))))

(deftest a-rise-under-a-frozen-lake-digs-the-ice-and-leaves-other-roofs
  ;; race 39088700340 bot Clj_r02 (issue #71) rose from an air pocket up a shaft under a frozen
  ;; sea; a rise never dug its cap, so it swam sideways into the ice and drowned
  (let [rise {:intent/kind :swim-out :intent/for :rise :intent/to-y 62 :intent/status :active}
        under (fn [roof]
                (assoc (game/init fx/opts)
                       :bot/phase :play
                       :player/pos [5.5 60.0 5.5]
                       :player/loaded? true
                       :player/in-water? true
                       :player/eye-in-water? true
                       :world/chunks
                       {[0 0] (world/column
                               (merge (into {} (for [y (range 55 62)] [[5 y 5] blocks/water-base]))
                                      {[5 62 5] roof}))}
                       :plan/intent rise))
        tick {:event/kind :tick :event/now 1000 :event/rand 0.5}
        child #(:intent/child (:plan/intent (intent/run % (:plan/intent %) tick)))]
    (is (= [5 62 5] (:intent/target (child (under (blocks/default-state :ice)))))
        "the lake's ice over the head: dug")
    (is (nil? (child (under 1))) "any other roof is left alone")))

(deftest a-sea-against-a-cliff-is-left-by-a-block-laid-for-a-bank
  ;; race 39109674329 runner 4 bot Clj_r16 (issue #92): a bot with 489 cobblestone and no pickaxe
  ;; swam to a cliff three blocks over the water, dug a notch in it by hand (75 s a block under
  ;; water) and was :no-escape three times in six minutes. A notch under the sky is never a bank;
  ;; a block laid into the water against the wall is one
  (let [sea (into {}
                  (for [x (range 3 16) z (range 16) y (range 56 64)] [[x y z] blocks/water-base]))
        cliff
        (into
         {}
         (for [x (range 3) z (range 16) y (range 64 72)] [[x y z] (blocks/default-state :stone)]))
        swim {:intent/kind :swim-out :intent/status :active}
        w (assoc (game/init fx/opts)
                 :bot/phase :play
                 :player/pos [6.5 62.97 8.5]
                 :player/loaded? true
                 :player/in-water? true
                 :player/eye-in-water? false
                 :player/inventory {0 {:item (blocks/items :cobblestone) :count 20}}
                 :time/tick 10
                 :world/chunks {[0 0] (world/column (merge sea cliff))}
                 :plan/intent swim)
        tick {:event/kind :tick :event/now 1000 :event/rand 0.5}
        feet (intent/feet w)]
    (is (= [3 63 8] (swim/bank-block w feet 64)) "the water cell against the wall nearest the feet")
    (is (= [3 63 7]
           (swim/bank-block (assoc-in w
                             [:world/chunks [0 0]]
                             (world/column
                              (merge sea cliff {[3 65 8] (blocks/default-state :stone)})))
                            feet
                            64))
        "not under a roof: the next cell along")
    (let [laid (:plan/intent (intent/run w swim tick))]
      (is
       (=
        {:intent/kind :place :intent/item :cobblestone :intent/cell [3 63 8] :intent/status :active}
        (select-keys (:intent/child laid) [:intent/kind :intent/item :intent/cell :intent/status]))
       "a cobblestone laid there, not a notch dug")
      (let [refused (assoc swim
                           :intent/child
                           {:intent/kind :place :intent/status :failed :intent/reason :rejected})
            next (:plan/intent (intent/run (assoc w :plan/intent refused) refused tick))]
        (is (= :active (:intent/status next)) "a refused lay does not fail the escape")
        (is (true? (:intent/no-lay next)))
        (is (nil? (:intent/child next)))))
    (let [far (assoc w :player/pos [10.5 62.97 8.5])]
      (is (nil? (swim/bank-block far (intent/feet far) 64)) "no wall within reach"))))

(deftest a-finished-dig-adds-its-time-to-the-escape
  ;; sim race 39598424082 (issue #344, world R4/16): fifty blocks of swimming to a cliff, no
  ;; pickaxe, a notch dug by hand at 37 s a block; the 120 s ran out between the second and the
  ;; third cell, three times
  (let [done {:intent/kind :dig :intent/status :done :intent/started 1000}
        now {:time/now 38000}]
    (is (= 740 (swim/dug-credit now nil done)) "the ticks from its start to now")
    (is (= 1480 (swim/dug-credit now 740 done)) "added to what was credited before")
    (is (= swim/dig-credit-ticks (swim/dug-credit now (- swim/dig-credit-ticks 10) done))
        "never more than dig-credit-ticks in all"))
  (let [bank [5 64 5]
        swim {:intent/kind :swim-out
              :intent/bank bank
              :intent/target bank
              :intent/best-dist 0.0
              :intent/best-tick 0
              :intent/started 0
              :intent/status :active}
        w (assoc (game/init fx/opts)
                 :bot/phase :play
                 :player/pos [5.5 64.0 5.5]
                 :player/loaded? true
                 :player/on-ground? false
                 :player/in-water? true
                 :time/tick (inc swim/escape-timeout-ticks)
                 :world/chunks {[0 0] (world/column {})})
        tick {:event/kind :tick :event/now 120100 :event/rand 0.5}
        run (fn [i] (:plan/intent (intent/run (assoc w :plan/intent i) i tick)))]
    (is (= :no-escape (:intent/reason (run swim))) "past the timeout with no dig finished")
    (is (not= :no-escape (:intent/reason (run (assoc swim :intent/dug 400))))
        "400 ticks of finished digs put the timeout back")))
(deftest a-wall-bank-just-out-of-reach-is-swum-to-not-notched
  ;; issue #92 (sim race 39550831606, world R4/9): the sea met a cliff, the cells it could lay a
  ;; bank into lay a block or two past the place reach, and with no pickaxe the escape dug a notch
  ;; in the wall by hand (37 s a block) until it timed out, three times
  (let [sea (into {}
                  (for [x (range 3 16) z (range 16) y (range 56 64)] [[x y z] blocks/water-base]))
        cliff
        (into
         {}
         (for [x (range 3) z (range 16) y (range 64 72)] [[x y z] (blocks/default-state :stone)]))
        swim {:intent/kind :swim-out :intent/status :active}
        w (assoc (game/init fx/opts)
                 :bot/phase :play
                 :player/pos [10.5 62.97 8.5]
                 :player/loaded? true
                 :player/in-water? true
                 :player/eye-in-water? false
                 :player/inventory {0 {:item (blocks/items :cobblestone) :count 20}}
                 :time/tick 10
                 :world/chunks {[0 0] (world/column (merge sea cliff))}
                 :plan/intent swim)
        tick {:event/kind :tick :event/now 1000 :event/rand 0.5}
        feet (intent/feet w)
        i (:plan/intent (intent/run w swim tick))]
    (is (nil? (swim/bank-block w feet 64)) "no wall within reach")
    (is (= [3 63 8] (swim/wall-toward w feet 64)) "the wall's cell nearest the feet")
    (is (= [3 63 8] (:intent/shore i)) "the swim heads for it")
    (is (nil? (:intent/child i)) "no notch dug")
    (is (true? (:control/jump? (:player/controls (intent/run w swim tick)))) "swimming on")
    (let [far (assoc w :player/pos [14.5 62.97 8.5])]
      (is (nil? (swim/wall-toward far (intent/feet far) 64)) "none past approach-radius"))))

(deftest the-escape-never-digs-ore-no-held-tool-can-take
  ;; a real race's bot Clj_r10 (issue #163): iron ore was the nearest wall of a flooded shaft and
  ;; the bot held no pickaxe, so each dig was refused :needs-tool
  (let [ore [5 64 5]
        rock [6 64 5]
        w (assoc (game/init fx/opts)
                 :world/chunks
                 {[0 0] (world/column {ore (blocks/default-state :iron_ore)
                                       rock (blocks/default-state :stone)})}
                 :player/inventory {})]
    (is (swim/solid-cell? w ore))
    (is (not (swim/diggable-cell? w ore)) "iron ore with no pickaxe")
    (is (swim/diggable-cell? w rock) "stone is dug by hand if it must be")))

(defn- bank-world
  "A sea (water up to y 62) against a one-block bank at x 2 (stone to y 63, air over it, a cliff
   beyond), the player at pos with a stack of cobblestone: issue #45 R1-17-2, a swim whose lift
   peaks at 63.95 under the bank's top 64.0."
  [pos intent stone]
  (let [air (into {} (for [x (range 3 16) z (range 16)] [[x 63 z] blocks/air]))
        sea (into {}
                  (for [x (range 3 16) z (range 16) y (range 56 63)] [[x y z] blocks/water-base]))
        bank
        (into
         {}
         (for [x (range 3) z (range 16) y (range 56 64)] [[x y z] (blocks/default-state :stone)]))
        cliff
        (into
         {}
         (for [x (range 2) z (range 16) y (range 64 72)] [[x y z] (blocks/default-state :stone)]))]
    (assoc (game/init fx/opts)
           :bot/phase :play
           :player/pos pos
           :player/loaded? true
           :player/on-ground? false
           :player/in-water? false
           :player/eye-in-water? false
           :player/inventory
           (if (pos? stone) {0 {:item (blocks/items :cobblestone) :count stone}} {})
           :time/tick 200
           :world/chunks {[0 0] (world/column (merge air sea bank cliff))}
           :plan/intent intent)))

(deftest a-swim-stuck-under-a-banks-top-lays-a-block-for-a-step
  ;; the bot is pressed to the bank's face, bobbing between 62.6 and 63.95 with nothing to dig: a
  ;; block laid into the water beside it is the step; with no filler it still fails :stuck
  (let [swim {:intent/kind :swim-out
              :intent/status :active
              :intent/bank [2 64 8]
              :intent/started 100
              :intent/best-tick 100
              :intent/best-dist 0.5}
        tick {:event/kind :tick :event/now 1000 :event/rand 0.5}
        w (bank-world [3.3 63.2 8.5] swim 20)
        laid (:intent/child (:plan/intent (intent/run w swim tick)))
        bare (:plan/intent (intent/run (bank-world [3.3 63.2 8.5] swim 0) swim tick))]
    (is (= :place (:intent/kind laid)))
    (is (= :cobblestone (:intent/item laid)))
    (is (= 3 (first (:intent/cell laid))) "against the bank's face")
    (is (= 62 (second (:intent/cell laid))) "into the water's top cell")
    (is (= :failed (:intent/status bare)))
    (is (= :stuck (:intent/reason bare)))))

(defn- lip-world
  "A one-block shore (stone at [2 63 8], air at [2 64 8]) and one water cell at [3 63 8], the
   lip a body at pos stands in. stone blocks of cobblestone when positive, else an empty hand."
  [pos stone]
  (assoc (game/init fx/opts)
         :bot/phase :play
         :player/pos pos
         :player/loaded? true
         :player/on-ground? false
         :player/in-water? true
         :player/eye-in-water? false
         :player/inventory (if (pos? stone) {0 {:item (blocks/items :cobblestone) :count stone}} {})
         :time/tick 200
         :world/chunks
         {[0 0] (world/column {[2 63 8] (blocks/default-state :stone)
                               [3 63 8] blocks/water-base})}))

(deftest a-swim-stuck-in-the-only-lay-cell-backs-off-then-lays
  ;; race 39991081881 runner 3 (issue #351): pressed into a one-block shore, the only water
  ;; cell at the float-top was inside the body, a placement there would be refused, and three
  ;; swims failed :stuck with cobblestone in hand. Back off the bank, then lay once it is free
  (let [bank [2 64 8]
        cell [3 63 8]
        swim {:intent/kind :swim-out
              :intent/status :active
              :intent/bank bank
              :intent/started 100
              :intent/best-tick 100
              :intent/best-dist 0.5}
        tick {:event/kind :tick :event/now 1000 :event/rand 0.5}
        w (assoc (lip-world [3.3 63.9 8.5] 20) :plan/intent swim)
        feet (intent/feet w)
        surface (swim/surface-y w :water feet)
        backed (:plan/intent (intent/run w swim tick))
        clear (assoc (lip-world [4.3 63.9 8.5] 20) :plan/intent swim)
        laid (:intent/child (:plan/intent (intent/run clear swim tick)))
        bare (:plan/intent
              (intent/run (assoc (lip-world [3.3 63.9 8.5] 0) :plan/intent swim) swim tick))
        late (assoc w :time/tick (+ 100 swim/stuck-ticks swim/backoff-ticks 1))
        gave-up (:plan/intent (intent/run late swim tick))]
    (is (= 64 surface) "the open air over the lip is the surface")
    (is (nil? (swim/bank-block w feet surface)) "the lip intersects the body")
    (is (= cell (swim/occupied-bank w feet surface)))
    (is (= :active (:intent/status backed)) "backing off does not fail the swim")
    (is (nil? (:intent/child backed)))
    (is (= bank (:intent/bank backed)) "the bank stays, so the next tick can lay")
    (is (= 100 (:intent/best-tick backed)) "the stall keeps running while it drifts")
    (is (= (swim/off-bank w bank) (:player/controls (intent/run w swim tick)))
        "jump held, away from the bank")
    (is (= :place (:intent/kind laid)))
    (is (= cell (:intent/cell laid)) "once the body has left the lip, the block goes there")
    (is (= :stuck (:intent/reason bare)) "no filler still fails")
    (is (= :stuck (:intent/reason gave-up)) "a stall past the backoff is still :stuck")))

(defn- over-lip-world
  "A world: a one-block shore (stone through y 63) and water only at y 62, air at y 63, so a
   body lifted to pos (feet in the air cell over the water) has its float-top a cell above the
   surface block. count cobblestone when positive."
  [pos stone]
  (assoc (game/init fx/opts)
         :bot/phase :play
         :player/pos pos
         :player/loaded? true
         :player/on-ground? false
         :player/in-water? true
         :player/eye-in-water? false
         :player/inventory (if (pos? stone) {0 {:item (blocks/items :cobblestone) :count stone}} {})
         :time/tick 200
         :world/chunks
         {[0 0] (world/column {[2 62 8] (blocks/default-state :stone)
                               [2 63 8] (blocks/default-state :stone)
                               [3 62 8] blocks/water-base
                               [3 63 8] blocks/air})}))

(deftest a-swim-lifted-over-the-water-lays-into-the-cell-below
  ;; issue #351: the feet are in the air cell over the water, so the float-top is a cell above
  ;; the surface block and the only supported water is one under it
  (let [swim {:intent/kind :swim-out
              :intent/status :active
              :intent/bank [2 64 8]
              :intent/started 100
              :intent/best-tick 100
              :intent/best-dist 0.5}
        tick {:event/kind :tick :event/now 1000 :event/rand 0.5}
        w (assoc (over-lip-world [3.3 63.9 8.5] 20) :plan/intent swim)
        feet (intent/feet w)
        surface (swim/surface-y w :water feet)
        laid (:intent/child (:plan/intent (intent/run w swim tick)))]
    (is (= 64 surface))
    (is (= 63 (swim/float-top w feet surface)) "the float-top is the air over the water")
    (is (= [3 62 8] (swim/bank-block w feet surface)) "the surface block, one under it")
    (is (= :place (:intent/kind laid)))
    (is (= [3 62 8] (:intent/cell laid)))))

(deftest a-swim-sinks-to-a-bank-below-the-feet
  ;; issue #222 (real-1-s1-Clj_r02, R1-2-1): the only bank is a dry floor under the water the
  ;; body floats on; holding jump bobs over it until :stuck, so the swim lets go and sinks
  (let [w (assoc (game/init fx/opts)
                 :bot/phase :play
                 :player/pos [5.5 64.0 5.5]
                 :player/loaded? true
                 :player/in-water? true
                 :world/chunks {[0 0] (world/column {})})]
    (is (not (:control/jump? (swim/swim-controls w [5 62 5]))) "a bank below: no jump, sink")
    (is (:control/forward? (swim/swim-controls w [5 62 5])) "still toward the bank")
    (is (:control/jump? (swim/swim-controls w [5 65 5])) "a bank above: jump, as before")))

(deftest a-stuck-swim-under-a-roof-carves-a-bank
  ;; issue #222 (world R1/2): pressed under a roof toward a bank the lift cannot reach, with
  ;; nothing to dig toward it and no block a lay could use (a lay wants the open sky), the
  ;; swim carves a notch at its own level instead of failing :stuck, and drops the kept bank
  ;; so the next search can pick the notch
  (let [swim {:intent/kind :swim-out
              :intent/status :active
              :intent/bank [5 64 3]
              :intent/target [5 64 3]
              :intent/started 0
              :intent/best-tick 0
              :intent/best-dist 0.5}
        cells {[5 64 5] blocks/water-base
               [5 64 4] blocks/water-base
               [5 63 3] (blocks/default-state :stone)
               [5 70 5] (blocks/default-state :stone)
               [7 64 5] (blocks/default-state :stone)
               [7 65 5] (blocks/default-state :stone)}
        w (assoc (game/init fx/opts)
                 :bot/phase :play
                 :player/pos [5.5 64.0 5.5]
                 :player/loaded? true
                 :player/in-water? true
                 :time/tick 200
                 :world/chunks {[0 0] (world/column cells)}
                 :plan/intent swim)
        tick {:event/kind :tick :event/now 10000 :event/rand 0.5}
        out (:plan/intent (intent/run w swim tick))]
    (is (= :active (:intent/status out)))
    (is (= :dig (:intent/kind (:intent/child out))))
    (is (= [7 65 5] (:intent/target (:intent/child out))) "the notch's lower cell")
    (is (nil? (:intent/bank out)) "the kept bank is dropped for the search to come")))

(deftest a-flee-with-no-footing-in-reach-rises-and-looks-again
  ;; issue #231 (world R1/3): sunk a cell below the floor it fell from, footing (one up or
  ;; down) finds nothing; the flee rises in the lava and looks again instead of failing
  ;; :no-footing on the spot, and only its timeout gives up
  (let [flee {:intent/kind :flee :intent/status :active}
        lava
        (into {} (for [dx (range -5 7) dz (range -5 7)] [[(+ 5 dx) 64 (+ 5 dz)] blocks/lava-base]))
        w (assoc (game/init fx/opts)
                 :bot/phase :play
                 :player/pos [5.5 64.0 5.5]
                 :player/loaded? true
                 :player/in-lava? true
                 :time/tick 100
                 :world/chunks {[0 0] (world/column lava)}
                 :plan/intent flee)
        tick {:event/kind :tick :event/now 5000 :event/rand 0.5}
        out (intent/run w flee tick)
        i (:plan/intent out)]
    (is (nil? (swim/footing w [5 64 5])) "nothing standable in reach")
    (is (= :active (:intent/status i)) "still fleeing, not failed :no-footing")
    (is (:control/jump? (:player/controls out)) "rising toward a footing")))

(deftest a-body-bobbing-at-a-bank-is-trapped-but-a-swimmer-is-not
  (let [at (fn [kind ground? ticks]
             (-> (bank-world [3.3 63.2 8.5] {:intent/kind kind :intent/status :active} 0)
                 (assoc :player/on-ground? ground? :plan/afloat-ticks ticks)
                 trap/trapped-tick))
        almost (dec trap/afloat-after-ticks)]
    (is (nil? (:plan/trap (at :walk false 10))) "a short hop over water is not a trap")
    (is (= 11 (:plan/afloat-ticks (at :walk false 10))))
    (is (= :water (:plan/trap (at :walk false almost))) "afloat for three seconds is")
    (is (nil? (:plan/trap (at :cross false almost))) "a crossing swims at the surface on purpose")
    (is (nil? (:plan/trap (at :wade false almost))) "so does a wade")
    (is (zero? (:plan/afloat-ticks (at :cross false almost))))
    (is (zero? (:plan/afloat-ticks (at :walk true almost))) "on the ground the count restarts")))

(deftest a-flee-chooses-again-when-the-flow-covers-its-target
  ;; pearl-fight-m job 1 (plan run 41216588429): landed beside a lava fall; the flee's footing, one
  ;; cell away, was covered by the flow in the second it took, the target kept, eight lava hits in
  ;; 4 s and dead with a footing four cells off
  (let [flee {:intent/kind :flee :intent/target [5 64 4] :intent/status :active :intent/started 100}
        flow (inc blocks/lava-base)
        lava (into {} (for [dx (range -1 2) dz (range -1 2)] [[(+ 5 dx) 64 (+ 4 dz)] flow]))
        w (assoc (game/init fx/opts)
                 :bot/phase :play
                 :player/pos [5.5 64.0 5.5]
                 :player/loaded? true
                 :player/in-lava? true
                 :time/tick 105
                 :world/chunks {[0 0] (world/column lava)}
                 :plan/intent flee)
        out (intent/run w flee {:event/kind :tick :event/now 1000 :event/rand 0.5})]
    (is (nil? (:intent/target (:plan/intent out))) "the covered target is dropped for a new choice")
    (let [cell (swim/footing w [5 64 5])]
      (is (some? cell))
      (is (swim/lava-free? w cell)))))
