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
