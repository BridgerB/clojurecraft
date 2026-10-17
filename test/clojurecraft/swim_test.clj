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
    (is (= [5 62 5] (swim/obstacle (at {[3 62 5] blocks/air}) [5 60 5] [3 61 5]))
        "under a bank a block up the step stays, the impulse climbs it: the ceiling instead")))

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
