(ns clojurecraft.plan-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojurecraft.blocks :as blocks]
            [clojurecraft.fixtures :as fx :refer [fold packet packets sent ticks]]
            [clojurecraft.game :as game]
            [clojurecraft.intent :as intent]
            [clojurecraft.make]
            [clojurecraft.memory :as memory]
            [clojurecraft.path :as path]
            [clojurecraft.physics :as physics]
            [clojurecraft.plan :as plan]
            [clojurecraft.recipe :as recipe]
            [clojurecraft.scoop :as scoop]
            [clojurecraft.spec]
            [clojurecraft.stairs :as stairs]
            [clojurecraft.stone :as stone]
            [clojurecraft.swim]
            [clojurecraft.terrain :as terrain]
            [clojurecraft.wood :as wood]
            [clojurecraft.world :as world]))

(use-fixtures :once fx/instrumented)

(def step (game/compose game/step plan/step))

(defn world-state
  "In play, loaded, standing at pos, with a 3-log trunk at [tx 64..66 0] under a leaf block,
   already seen (remembered)."
  ([pos] (world-state pos 3))
  ([pos tx]
   (let [col (world/column {[tx 64 0] 136 [tx 65 0] 136 [tx 66 0] 136 [tx 67 0] 252})]
     (-> (game/init fx/opts)
         (assoc :bot/phase :play :player/pos pos :player/loaded? true :player/on-ground? true)
         (assoc :world/chunks {[0 0] col})
         (memory/remember-column [0 0] col)))))

(defn run [w events] (fold step w events))
(def go {:event/kind :go :go/goals [:wood]})

(deftest memory-finds-the-bottom-of-the-trunk
  (let [w (world-state [0.5 64.0 0.5])]
    (is (= [3 64 0] (memory/nearest-log w (game/eye w) 48 #{})))
    (is (nil? (memory/nearest-log w (game/eye w) 48 #{[3 64 0]})) "blacklist")
    (is (= [3 65 0] (memory/nearest-log (memory/observe w [3 64 0] 0) (game/eye w) 48 #{}))
        "once the bottom is observed as air, the log above is the trunk bottom")
    (is (= 4 (intent/face-toward (game/eye w) [3 64 0]))
        "eye is west of the block, so its west face")))

(deftest a-branch-is-not-a-trunk-bottom
  ;; recorded by the gym and a plan at landing 21280,18720 (wood-a2 run 5, smoke2 run 5): a
  ;; branch log with air under it passed for a trunk bottom, and its drop came to rest on the
  ;; trunk below it, out of reach. A trunk bottom stands on ground, or over its own dug base.
  (let [col (world/column
             {[3 64 0] 136 [3 65 0] 136 [3 66 0] 136 [3 67 0] 136 [2 67 0] 136 [3 68 0] 252})
        w (-> (game/init fx/opts)
              (assoc :bot/phase :play
                     :player/pos [0.5 70.0 0.5]
                     :player/loaded? true
                     :player/on-ground? true)
              (assoc :world/chunks {[0 0] col})
              (memory/remember-column [0 0] col))
        eye (game/eye w)]
    (is (= [2 67 0] (memory/nearest-log w eye 48 #{} memory/trunk-bottom?))
        "the old rule takes the branch")
    (is (= [3 64 0] (memory/nearest-log w eye 48 #{} wood/standing-trunk?)))
    (is (= [3 65 0]
           (memory/nearest-log (memory/observe w [3 64 0] 0) eye 48 #{} wood/standing-trunk?))
        "over its own dug base a trunk still stands: the drop falls to where the base was")))

(deftest face-is-the-side-nearest-the-eye
  (is (= 1 (intent/face-toward [3.5 70.0 0.5] [3 64 0])) "eye above → top")
  (is (= 2 (intent/face-toward [3.5 65.0 -5.0] [3 64 0])) "eye north → north face"))

(deftest a-dig-finishes-at-the-pace-of-the-penalties-in-force-now
  ;; sim race 39598424082 (issue #344, world R1/3): a granite dig by hand begun with the eyes
  ;; under water was timed 187 s, 25 times the hand's pace, and went on being timed so after the
  ;; body floated up with its eyes in air; the server weighs the finish by the rate it has then
  (let [granite (blocks/default-state :granite)
        w (assoc (world-state [0.5 64.0 0.5]) :player/on-ground? false :player/eye-in-water? true)
        slow (intent/dig-time granite nil (intent/penalties w))
        i {:intent/state granite
           :intent/started 1000
           :intent/finish-at (+ 1000 (intent/finish-delay slow))}
        surfaced (assoc w :player/eye-in-water? false)
        landed (assoc surfaced :player/on-ground? true)]
    (is (= (:intent/finish-at i) (intent/finish-due w i)) "the penalties it began with still hold")
    (is (< (intent/finish-due surfaced i) (:intent/finish-at i)) "eyes in air: shorter")
    (is (< (intent/finish-due landed i) (intent/finish-due surfaced i))
        "and on the ground: shorter still")
    (is (= (+ 1000 (intent/finish-delay (intent/dig-time granite nil {})))
           (intent/finish-due landed i))
        "the unpenalised pace")
    (is (= 500 (intent/finish-due landed (assoc i :intent/finish-at 500)))
        "never later than the finish it was given")))

(deftest dig-timeline-then-pickup
  (let [[w fx] (run (world-state [0.5 64.0 0.5])
                    (concat [go]
                            (ticks 50 8000)
                            [(packet {:packet/name :block-update :pos [3 64 0] :state 0})])) ; the server's word, as it always comes
        actions (filter #(= :player-action (:packet/name (second %))) (sent fx))
        [[t-start start] [t-finish finish]] actions
        swings (filter #(= :swing (:packet/name (second %))) (sent fx))]
    (is (= :collect (get-in w [:plan/intent :intent/kind])))
    (is (= [3 64 0] (get-in w [:plan/intent :intent/target])))
    (testing "START after settling, FINISH late enough never to abort the break"
      (is (= 0 (:status start)))
      (is (= 2 (:status finish)))
      (is (= [3 64 0] (:pos start)))
      (is (<= intent/settle-ms t-start 2000))
      (is (>= (- t-finish t-start) (intent/finish-delay 3000)))
      (is (= (:sequence finish) (inc (:sequence start)))))
    (testing "swings every 350 ms while digging" (is (<= 11 (count swings) 14)))
    (testing "the broken block is air locally and in memory"
      (is (= 0 (terrain/block-at w [3 64 0])))
      (is (= 0 (memory/remembered w [3 64 0]))))
    (testing "done once the inventory shows a log"
      (let [[w2 _] (run w
                        [(packet {:packet/name :set-player-inventory
                                  :slot 0
                                  :item {:item (blocks/items :oak_log) :count 1}})
                         {:event/kind :tick :event/now 8050 :event/rand 0.5}])]
        (is (plan/done? w2))
        (is (= {} (:player/controls w2)))))))

(deftest walks-to-a-far-log
  (let [[w fx] (run (world-state [0.5 64.0 0.5] 13) (cons go (ticks 50 6000)))]
    (is (contains? #{:dig :collect} (get-in w [:plan/intent :intent/kind])))
    (is (<= (physics/distance (game/eye w) [13.5 64.5 0.5]) 4.0))
    (is (some #(= :move-player-pos-rot (:packet/name %)) (packets fx)))))

(deftest gives-up-without-logs
  (let [w (assoc (world-state [0.5 64.0 0.5]) :world/facts (memory/empty-facts))
        [w _] (run w (cons go (ticks 50 25000)))]
    (is (plan/failed? w))
    (is (= :no-log (:plan/reason w)))))

(defn remembered
  "In play at pos over blocks, the column remembered."
  [blocks pos]
  (let [col (world/column blocks)]
    (-> (game/init fx/opts)
        (assoc :bot/phase :play :player/pos pos :player/loaded? true :player/on-ground? true)
        (assoc :world/chunks {[0 0] col})
        (memory/remember-column [0 0] col))))

(def shaft
  "An air stair from [0 40 8] up to [14 54 8], and a trunk beside the top, more than 12 above an
   eye at y 40: the near search's band misses it."
  (merge (into {} (for [x (range 0 15) dy [0 1 2]] [[x (+ 40 x dy) 8] blocks/air]))
         {[14 55 9] 1 [14 56 9] 136 [14 57 9] 136}))

(deftest a-surface-with-no-tree-still-waits
  (is (= {:plan/wait :no-log} (wood/gather-next (remembered {} [0.5 64.0 0.5])))))

(deftest underground-with-no-log-the-bot-waits
  ;; full run 29ef069 cut a stair at spawn before any tree was remembered
  (let [w (remembered {[8 50 8] blocks/air [8 51 8] blocks/air [8 52 8] blocks/air} [8.5 50.0 8.5])]
    (is (not (terrain/under-sky? w (intent/feet w))))
    (is (= {:plan/wait :no-log} (wood/gather-next w)))))

(deftest a-trunk-with-no-route-is-climbed-for
  (let [w (remembered
           {[8 50 8] blocks/air [8 51 8] blocks/air [8 52 8] blocks/air [8 69 8] 1 [8 70 8] 136}
           [8.5 50.0 8.5])]
    (is (= {:intent/kind :stairs-up
            :intent/to-y (+ 50 stairs/climb-stairs)
            :intent/dir [-1 0]
            :intent/for :surface}
           (wood/gather-next w)))))

(deftest a-climb-out-is-cut-toward-the-trunk
  ;; sim snapshots of issues #186 and #89 (worlds R4/16, R2/29, R1/4): the climb out of the
  ;; ground took the first heading east whichever side the trees lay, left a mountain by its east
  ;; face into the sky and pillared up in the air until its own steps were a floor; no route
  ;; reached a trunk from the top and the plan waited :no-log. The climb is cut toward the
  ;; nearest trunk, the way trees-climb's is
  (doseq [[trunk dir] {[2 70 8] [-1 0] [14 70 8] [1 0] [8 70 2] [0 -1] [8 70 14] [0 1]}]
    (let [[tx ty tz] trunk
          w (remembered {[8 50 8] blocks/air
                         [8 51 8] blocks/air
                         [8 52 8] blocks/air
                         [tx (dec ty) tz] 1
                         trunk 136}
                        [8.5 50.0 8.5])]
      (is (= dir (:intent/dir (wood/gather-next w))) (str "toward the trunk at " trunk)))))

(deftest boxed-under-the-sea-the-bot-digs-up-into-it
  ;; sim race 38806318231 runner 1 and 38811426215 runner 8 (issue #89): bots under the sea floor
  ;; with no pickaxe needed a log; every climb out was :boxed under the water and no route led
  ;; home, and each waited :no-log
  (let [sea (into
             {[8 50 8] blocks/air [8 51 8] blocks/air [8 52 8] blocks/air [8 69 8] 1 [8 70 8] 136}
             (for [y (range 54 64)] [[8 y 8] blocks/water-base]))
        boxed #(assoc % :plan/boxed-at #{[8 50 8]})
        bed (fn [b] (remembered (assoc sea [8 53 8] (blocks/default-state b)) [8.5 50.0 8.5]))]
    (is (= :stairs-up (:intent/kind (wood/gather-next (bed :dirt)))) "not yet boxed: climb out")
    (is (= {:intent/kind :dig
            :intent/target [8 53 8]
            :intent/status :active
            :intent/exposes-ok? true
            :intent/for :into-water}
           (wood/gather-next (boxed (bed :dirt))))
        "boxed: dig the bed over the head, letting the sea in")
    (is (= {:intent/kind :swim-out :intent/for :rise :intent/to-y 64}
           (wood/gather-next (boxed (remembered (assoc sea [8 53 8] blocks/water-base)
                                                [8.5 50.0 8.5]))))
        "the bed dug and the sea in: up the shaft to the top of the water")
    (is (= {:plan/wait :no-log} (wood/gather-next (boxed (bed :gravel))))
        "never under gravel, which would fall on the bot")))

(deftest boxed-under-water-that-has-no-top-the-bot-does-not-dig-into-it
  ;; sim world R3/13 (issue #71): a tunnel at y 16 dug up into an aquifer seven deep under stone,
  ;; and the water followed the bot along its tunnel; a lake's ice is open water's roof
  (let [pocket (into {[8 50 8] blocks/air [8 51 8] blocks/air [8 52 8] blocks/air}
                     (for [y (range 54 61)] [[8 y 8] blocks/water-base]))
        boxed #(assoc % :plan/boxed-at #{[8 50 8]})
        under (fn [roof]
                (boxed (remembered (assoc pocket [8 53 8] 1 [8 61 8] roof) [8.5 50.0 8.5])))]
    (is (= {:plan/wait :no-log} (wood/gather-next (under 1)))
        "water under stone is a pocket: no way up")
    (is (= :into-water (:intent/for (wood/gather-next (under blocks/air))))
        "open at the top: dig up into it")
    (is (= :into-water (:intent/for (wood/gather-next (under (blocks/default-state :ice)))))
        "under a lake's ice: dug into, the rise digs the ice")))

(deftest a-trunk-across-a-chasm-is-bridged-by-stairs-up
  ;; sim worlds R3/45 (issues #87 and #108): the only trunk in reach stood across two blocks of air
  ;; from the pillar the bot had climbed out on, no route reached it, and the plan waited :no-log
  (let [chasm (into {} (for [x (range 16) y (range 40 64) z (range 16)] [[x y z] blocks/air]))
        platforms (fn [y & boxes]
                    (into {}
                          (for [[x0 x1] boxes x (range x0 (inc x1)) z (range 6 11)] [[x y z] 1])))
        trunk {[1 64 8] 136 [1 65 8] 136}
        near (remembered (merge chasm (platforms 63 [7 10] [0 1]) trunk) [8.5 64.0 8.5])
        high (remembered (merge chasm (platforms 63 [0 1]) (platforms 66 [7 10]) trunk)
                         [8.5 67.0 8.5])
        far (remembered (merge chasm (platforms 63 [0 2] [13 15]) {[15 64 8] 136 [15 65 8] 136})
                        [1.5 64.0 8.5])
        bridge {:intent/kind :stairs-up
                :intent/to-y 66
                :intent/to-height? true
                :intent/dir [-1 0]
                :intent/for :surface}]
    (is (nil? (wood/near-walk near)) "no route reaches the trunk across the chasm")
    (is (= bridge (wood/gather-next near)) "two stairs up toward it")
    (is (= bridge (wood/trees-bridge near [[1 64 8]])))
    (is (nil? (wood/trees-bridge (assoc near :plan/boxed-at #{[8 64 8]}) [[1 64 8]]))
        "never from a cell where a climb died boxed")
    (is (nil? (wood/trees-bridge high [[1 64 8]]))
        "bounded: three over the trunk's bottom, no more")
    (is (nil? (wood/trees-bridge far [[15 64 8]]))
        "a trunk past bridge-reach is not climbed toward")))

(deftest a-perch-under-the-sky-descends-toward-a-lower-trunk
  ;; sim race 39934917165 (issue #89, world R1/23): a descent ended at the trunk's height, the
  ;; opening was walked, and the lip was not out, so stairs up to the top of the world spent the
  ;; filler and the stairs down back were :boxed. A pillar under the sky goes down toward the trunk
  (let [w (remembered {[1 80 2] 1 [14 69 2] 1 [14 70 2] 136} [1.5 81.0 2.5])]
    (is (wood/perched? w (intent/feet w)) "a pillar under the sky, no pit and not out")
    (is (= {:intent/kind :stairs-down
            :intent/trunk [14 70 2]
            :intent/to-y (- 81 stone/stair-depth)
            :intent/dir [1 0]
            :intent/for :trees}
           (wood/gather-next w))
        "down toward the trunk, not up to the top of the world")))

(deftest a-shaft-under-the-sky-descends-toward-a-lower-trunk
  ;; real-6 s1 Clj_r07 (issue #89): already in the shaft, each length's pit-rim was the stair's
  ;; own block a few above the feet, so perched? was false and stairs up continued to y 124.
  ;; Under the sky, with the trunk more than twelve under the eye, the next length is down
  (let [w (remembered {[1 80 2] 1 [2 84 2] 1 [14 60 2] 1 [14 61 2] 136} [1.5 81.0 2.5])
        feet (intent/feet w)]
    (is (terrain/under-sky? w feet) "the sky is over the shaft")
    (is (not (stairs/out? w feet)) "a pillar is not out of the ground")
    (is (some? (stairs/pit-rim w feet)) "the block beside the shaft reads as a rim")
    (is (not (wood/perched? w feet)) "a rim is set, so this is not the ledge")
    (is (= {:intent/kind :stairs-down
            :intent/trunk [14 61 2]
            :intent/to-y (- 81 stone/stair-depth)
            :intent/dir [1 0]
            :intent/for :trees}
           (wood/gather-next w))
        "down toward the trunk, not another length to the top of the world")))

(deftest a-perch-across-a-valley-is-descended-for-the-trees
  ;; sim snapshot real-2-s2-Clj_r15 (issue #186): the bot stood on the top of an ice spike with the
  ;; nearest trunk thirty-five blocks off at its own height across a valley no route crosses; no
  ;; walk, climb, hunt or crossing applied, and the plan waited :no-log. The gather act cuts a
  ;; length of stairs down toward the trunk; a spare never does
  (let [chasm (into {} (for [x (range 16) y (range 40 64) z (range 16)] [[x y z] blocks/air]))
        platforms (fn [y & boxes]
                    (into {}
                          (for [[x0 x1] boxes x (range x0 (inc x1)) z (range 6 11)] [[x y z] 1])))
        perch (remembered (merge chasm (platforms 63 [0 2] [13 15]) {[15 64 8] 136 [15 65 8] 136})
                          [1.5 64.0 8.5])
        down {:intent/kind :stairs-down
              :intent/trunk [15 64 8]
              :intent/to-y (- 64 stone/stair-depth)
              :intent/dir [1 0]
              :intent/for :valley}]
    (is (stairs/out? perch (intent/feet perch)) "on open ground")
    (is (= {:plan/wait :no-log} (wood/gather-next perch)) "no walk, climb, hunt or crossing")
    (is (= down (wood/valley perch)) "down toward the trunk, whatever its height")
    (is (= down (wood/gather-or-out perch)) "the gather act takes it before the wait")
    (is (nil? (wood/valley (assoc perch :plan/boxed-at #{(intent/feet perch)})))
        "never from a cell where a climb died boxed")))

(deftest a-valley-goes-on-from-the-foot-of-its-last-length
  ;; sim snapshot real-2-s2-Clj_r15 (issue #186) played on: from the foot of the first length, in
  ;; the ice, the walk up the air already dug took the bot back to the perch every few seconds to
  ;; cut the same length again. The next length goes on from the foot, toward the same trunk,
  ;; until the bot is out on open ground, and no deeper than valley-floor under the trunk
  (let [w (remembered {[8 50 8] blocks/air [8 51 8] blocks/air [8 52 8] blocks/air [12 60 8] 136}
                      [8.5 50.0 8.5])
        leg
        {:intent/kind :stairs-down :intent/for :valley :intent/trunk [12 60 8] :intent/status :done}
        on (assoc w :plan/last leg)]
    (is (not (stairs/out? w (intent/feet w))) "in the ground")
    (is (wood/descended-valley? on))
    (is (= {:intent/kind :stairs-down
            :intent/trunk [12 60 8]
            :intent/to-y (- 50 stone/stair-depth)
            :intent/dir [1 0]
            :intent/for :valley}
           (wood/gather-next on))
        "on, before the walk up to the mouth")
    (is (not (wood/descended-valley? w)) "nothing cut before")
    (is (not (wood/descended-valley? (assoc w :plan/last (assoc leg :intent/for :trees))))
        "a descent toward the trees is trees-descend's")
    (is (nil? (wood/valley
               (assoc (remembered
                       {[8 50 8] blocks/air [8 51 8] blocks/air [8 52 8] blocks/air [12 70 8] 136}
                       [8.5 50.0 8.5])
                      :plan/last
                      (assoc leg :intent/trunk [12 70 8]))))
        "never deeper than valley-floor under the trunk")))

(deftest a-mouth-below-the-feet-is-the-way-down-not-out
  ;; sim world R2/16 (issue #87): stairs cut up from the mouth at y 83 toward trees on a cliff
  ;; ended on a ledge at y 99 that was not out of the ground; the opening walked the bot back
  ;; down to the mouth, where the climb began again, every twenty seconds
  (is (wood/mouth-below? [2312 99 2655] [2327 83 2656]) "sixteen under the feet")
  (is (not (wood/mouth-below? [2312 99 2655] [2327 98 2656])) "a step down is the way out")
  (is (not (wood/mouth-below? [2312 20 2655] [2327 70 2656])) "a mouth above is the way out"))

(deftest a-climb-for-wood-boxed-under-gravel-takes-the-short-way-away
  (let [corridor (into {[12 64 8] 136} (for [x (range 5 10) y [48 49]] [[x y 5] blocks/air]))
        bed (into corridor (for [x (range 5 10)] [[x 51 5] (blocks/default-state :gravel)]))
        w (assoc (remembered bed [5.5 48.0 5.5]) :plan/boxed-at #{[5 48 5]})
        next (wood/gather-or-out w)]
    (is (= {:plan/wait :no-log} (wood/gather-next w)) "no tree can yet be reached")
    (is (= {:intent/kind :walk :intent/target [9 48 5] :intent/for :retreat} next)
        "the gather act leaves instead of waiting in the boxed pocket")
    (let [away (assoc w :player/pos [9.5 48.0 5.5])]
      (is (= :stairs-up (:intent/kind (wood/gather-or-out away))))
      (is (not (stairs/up-dry? away (stairs/up-cells (intent/feet away) [-1 0]))))
      (is (not (stairs/level-safe? away [[5 49 5] [5 48 5]]))
          "the way back into the boxed pocket stays refused"))))

(deftest a-boxed-cell-with-no-retreat-is-climbed-out-of-for-wood
  ;; issue #89 (real race, bot Clj_r32): in a cell a stair down had died boxed in, with the
  ;; nearest trunk past the near band and no retreat left, the plan waited :no-log out
  (let [w (assoc (remembered
                  {[8 50 8] blocks/air [8 51 8] blocks/air [8 52 8] blocks/air [12 60 8] 136}
                  [8.5 50.0 8.5])
                 :plan/boxed-at
                 #{[8 50 8]})]
    (is (= {:plan/wait :no-log} (wood/gather-next w))
        "no climb is cut from a boxed cell for a trunk")
    (is (nil? (stone/retreat w)) "and no way away from the pocket")
    (is (= {:intent/kind :stairs-up :intent/to-y (+ 50 stairs/climb-stairs) :intent/for :boxed}
           (wood/gather-or-out w))
        "the gather act climbs out of the ground instead of waiting")))

(deftest a-near-trunk-with-no-route-is-climbed-for-too
  ;; sim race 38395033917, world R3/24 (issue #75): at the bottom of two staircases that met, the
  ;; second having dug out the first's riser, no route led up, and every walk to a trunk within
  ;; the near band failed :no-path at once, blacklisting one trunk a tick until the plan failed.
  ;; A trunk in the band is asked for a route before it is walked at, as a far one is.
  (let [w (remembered {[8 50 8] blocks/air [8 51 8] blocks/air [8 52 8] blocks/air [12 60 8] 136}
                      [8.5 50.0 8.5])
        eye (game/eye w)]
    (is (= [12 60 8]
           (memory/nearest-log w eye wood/search-radius (wood/skipped w) wood/standing-trunk?))
        "the trunk is in the near band: within 12 of the eye, on stone")
    (is (not (intent/routable? w (wood/log-walk w [12 60 8]))) "sealed in stone: no route")
    (is (nil? (wood/near-walk w)))
    (is (= {:intent/kind :stairs-up
            :intent/to-y (+ 50 stairs/climb-stairs)
            :intent/dir [1 0]
            :intent/for :surface}
           (wood/gather-next w))
        "climbed for, never walked at")))

(deftest a-pit-under-the-sky-is-climbed-out-of
  ;; race 38477665442 (issues #56, #58, #54): the stone mine dug the bot two deep beside a cliff
  ;; (Clj_r20), into a notch under an overhang by a pond (Clj_r09), onto a ledge of its own
  ;; quarry (Clj_r01) and onto a lakeshore under a cliff (Clj_r12). Every trunk was up a wall
  ;; no jump climbs, twelve walks failed :no-path in a few seconds, and with the open sky over
  ;; the feet nothing was cut. A pit is not out of the ground: stairs up are cut from it
  (let [pit (remembered {[8 62 8] blocks/air [8 63 8] blocks/air [12 64 8] 136} [8.5 62.0 8.5])
        feet (intent/feet pit)]
    (is (terrain/under-sky? pit feet) "the sky is over the pit")
    (is (not (stairs/out? pit feet)) "but no level step leads onto open ground")
    (is (not (intent/routable? pit (wood/log-walk pit [12 64 8]))) "two deep: no jump climbs it")
    (is (= {:intent/kind :stairs-up
            :intent/to-y (stairs/climb-to pit terrain/top-y)
            :intent/dir [1 0]
            :intent/for :surface}
           (wood/gather-next pit))))
  (let [ground (remembered {[12 64 8] 136 [14 64 8] 1 [14 65 8] 1 [14 66 8] 1} [8.5 64.0 8.5])]
    (is (stairs/out? ground (intent/feet ground)))
    (is (= :walk (:intent/kind (wood/gather-next ground)))
        "on open ground the trunk is walked to")))

(deftest a-trunk-up-the-cliff-is-climbed-toward-when-out
  ;; race 38674091115 runner 4 (issue #87): the iron mine left the bot at the mouth of its
  ;; shaft under the open sky at y 70 with 999 trunks remembered and the nearest thirty blocks
  ;; above on the mountain; the near band stops twelve over the eye, no walk climbs the cliff,
  ;; the hunt's targets stop sixteen over the eye and never come nearer the landing, and the
  ;; climb would not cut because out? said out. The plan waited :no-log. Out is not at the
  ;; trees: stairs up toward the trunk, to-height only
  (let [w (remembered {[12 69 8] 1 [12 70 8] 136} [8.5 64.0 8.5])]
    (is (stairs/out? w (intent/feet w)) "on open ground under the sky")
    (is (memory/nearest-log w (game/eye w) wood/search-radius #{} wood/standing-trunk?)
        "the trunk on its ledge is remembered")
    (is (nil? (wood/near-walk w))
        "six up, past the walk's range and the dig's reach: no walk reaches it")
    (is (= {:intent/kind :stairs-up
            :intent/to-y 70
            :intent/to-height? true
            :intent/dir [1 0]
            :intent/for :surface}
           (wood/trees-climb
            w
            [(memory/nearest-log w (game/eye w) wood/far-radius #{} wood/standing-trunk?)]))
        "climbed toward, to-height only")
    (is (= :stairs-up (:intent/kind (wood/gather-next w)))))
  (let [level (remembered {[12 64 8] 136 [12 65 8] 136} [8.5 64.0 8.5])
        bottoms [(memory/nearest-log level (game/eye level) 48 #{} wood/standing-trunk?)]]
    (is (nil? (wood/trees-climb level bottoms))
        "a trunk at the eye's height is hunted for, not climbed toward")
    ;; sim race 38811417241 runner 2 (issue #87, world R3/4): on an island, the trunk five over
    ;; the eye a hundred blocks off across a lake was climbed toward, a stair at a time
    (is (nil? (wood/trees-climb level [[108 69 8]])) "a trunk past the near band is no climb")))

(deftest a-long-way-back-to-the-trees-keeps-its-search-budget
  ;; Issue #186, R2/17: a nearby trunk required a long detour down the mine's stairs and
  ;; around the mountain. Local searches spent their budget and the plan waited :no-log.
  (let [w (assoc (remembered {[12 64 8] 136} [1.5 64.0 8.5]) :time/tick 0)
        local (assoc (wood/log-walk w [12 64 8]) :intent/max-nodes 2)
        i (wood/return-walk w)
        routed (:plan/intent (intent/route! (assoc w :plan/intent i) i))]
    (is (not (intent/routable? w local)) "the local search is partial, not a complete route")
    (is (= :partial
           (get-in (intent/route! (assoc w :plan/intent local) local) [:plan/intent :intent/route]))
        "the executor honors a smaller explicit budget too")
    (is (= (assoc local :intent/max-nodes wood/return-max-nodes) i))
    (is (intent/routable? w i) "the larger search finds a complete route")
    (is (= :found (:intent/route routed)) "the executor uses the same budget")
    (is (seq (:intent/waypoints routed)))
    (is (nil? (wood/return-walk (assoc w :plan/blacklist #{[12 64 8]}))))
    (is (nil? (wood/return-walk (assoc w :player/pos [1.5 50.0 8.5]))))
    (let [replanned (:plan/intent (intent/route! (assoc w :plan/intent routed) routed))]
      (is (= :found (:intent/route replanned)))
      (is (= 2 (:intent/replans replanned))))))

(deftest a-trunk-far-below-is-descended-toward-when-out
  ;; real race (issue #57, snapshot real-4-s2-Clj_r07): out on a mountain top at y 238 with the
  ;; nearest trunk fifty-six below and sixty across, no walk reaching it, the plan waited :no-log.
  ;; Out is not at the trees: stairs down toward the trunk, a length at a time
  (let [w (remembered {[12 9 8] 136 [12 10 8] 136} [8.5 64.0 8.5])
        trunk (first (memory/nearest-first (game/eye w)
                                           wood/far-radius
                                           (memory/positions-now w blocks/log-states)))]
    (is (stairs/out? w (intent/feet w)) "on open ground under the sky")
    (is (= {:intent/kind :stairs-down
            :intent/trunk trunk
            :intent/to-y 58
            :intent/dir [1 0]
            :intent/for :trees}
           (wood/trees-descend w [trunk]))
        "down toward the trunk, one length")
    (is (= :stairs-down (:intent/kind (wood/trees-descend w [[130 9 8]])))
        "a hundred and twenty across, issue #108: far, but no nearer trees in reach")
    (is (nil? (wood/trees-descend w [[400 9 8]])) "a trunk past descend-reach is no descent")
    (is (nil? (wood/trees-descend w [[12 60 8]])) "a trunk within the near band is walked to")
    (is (nil? (wood/trees-descend (assoc w :plan/boxed-at #{(intent/feet w)}) [trunk]))
        "never from a cell where a climb died boxed")))

(deftest the-next-length-down-goes-on-from-the-foot-of-the-last
  ;; sim race 39593219246 (issue #135, world R4/4): out at the mouth of its own stairs, the bot cut
  ;; a length down toward trunks forty below, was in the ground at its foot, and walked back up
  ;; the air already dug to the mouth (intent/opening) to cut the same length again, every seven
  ;; seconds for fifteen minutes
  (let [cells
        {[8 50 8] blocks/air [8 51 8] blocks/air [8 52 8] blocks/air [12 9 8] 136 [12 10 8] 136}
        w (remembered cells [8.5 50.0 8.5])
        cut {:intent/kind :stairs-down :intent/for :trees :intent/status :done}
        trunk [12 9 8]]
    (is (not (stairs/out? w (intent/feet w))) "in the ground, at the foot of the length")
    (is (nil? (wood/trees-descend w [trunk]))
        "nothing was cut: a way down from the ground is not this")
    (is (not (wood/descended? w)))
    (is (wood/descended? (assoc w :plan/last cut)))
    (is (not (wood/descended? (assoc w :plan/last (assoc cut :intent/for :surface)))))
    (is (not (wood/descended? (assoc w :plan/last (assoc cut :intent/status :failed)))))
    (is (= {:intent/kind :stairs-down
            :intent/trunk trunk
            :intent/to-y 44
            :intent/dir [1 0]
            :intent/for :trees}
           (wood/trees-descend (assoc w :plan/last cut) [trunk]))
        "the next length goes on down toward the trunk")
    (is (= {:intent/kind :stairs-down
            :intent/trunk [12 43 8]
            :intent/to-y 44
            :intent/dir [1 0]
            :intent/for :trees}
           (wood/trees-descend (assoc w :plan/last cut) [[12 43 8]]))
        "a trunk under twelve below the eye, no route reaching it, is cut toward from the foot")
    (is (nil? (wood/trees-descend (assoc w :plan/last cut) [[12 50 8]]))
        "but not one level with the eye: that is the near band's")
    (is (nil? (wood/trees-descend (assoc w :plan/last cut :plan/boxed-at #{(intent/feet w)})
                                  [trunk]))
        "never from a cell where a climb died boxed")))

(deftest a-trunk-across-powder-snow-is-walked-to
  ;; sim race 38811417241 runner 2 (issue #87, world R2/20): out of the mine on one cobblestone
  ;; block in a powder-snow field, a spruce eight blocks off; powder snow was neither floor nor
  ;; clear to a route, so no route had a move, and the plan waited :no-log. A player sinks
  ;; through it: the route goes through it to the floor below
  (let [snow (blocks/default-state :powder_snow)
        field (into {}
                    (for [x (range 4 13) z (range 4 13) :when (not= [x z] [8 8])] [[x 63 z] snow]))
        w (remembered (merge field {[14 64 8] 136 [14 65 8] 136}) [8.5 64.0 8.5])]
    (is (= :clear (path/classify w [9 63 8])) "powder snow: no collision for a player")
    (is (= :walk (:intent/kind (wood/near-walk w))) "the trunk is walked to through the snow")))

(deftest on-an-island-the-bot-swims-for-the-trees
  ;; sim race 38811417241 runner 2 (issue #87, world R3/4): on a sand island at its landing,
  ;; the trees across a lake, every route ending at the shore and every hunt bearing at water,
  ;; the plan waited :no-log; now it crosses
  (let [lake (into {}
                   (for [x (range 16)
                         z (range 16)
                         y (range 60 64)
                         :when (not (or (and (<= 2 x 6) (<= 2 z 6)) (>= x 13)))]
                     [[x y z] blocks/water-base]))
        w (remembered (merge lake {[14 64 8] 136 [14 65 8] 136}) [4.5 64.0 4.5])]
    (is (nil? (wood/near-walk w)) "no route crosses the water")
    (is (empty? (scoop/hunt-targets w true)))
    (is (wood/water-toward? w (intent/feet w) [14 64 8] (physics/y-of (game/eye w))))
    (is (= {:intent/kind :cross :intent/target [14 64 8]} (wood/gather-next w)))))

(deftest a-bot-in-the-water-swims-for-the-trees-before-it-climbs
  ;; sim snapshot R1-4-1 (issue #186): the bot dug up under the sea, swam to the surface and cut
  ;; stairs up from the water, a pillar twenty blocks over the sea, with the trees across the
  ;; water; from the top no floor could be laid down over the sea and it waited :no-log. At the
  ;; surface under the open sky it crosses first
  (let [lake (into {}
                   (for [x (range 16)
                         z (range 16)
                         y (range 60 64)
                         :when (not (>= x 13))]
                     [[x y z] blocks/water-base]))
        w (assoc (remembered (merge lake {[14 64 8] 136 [14 65 8] 136}) [4.5 63.0 4.5])
                 :player/in-water?
                 true)]
    (is (not (stairs/out? w (intent/feet w))) "afloat: no floor to step onto")
    (is (= {:intent/kind :cross :intent/target [14 64 8]} (wood/gather-next w)))))

(def quarry
  "A quarry two wide and two deep in the stone, open to the sky, as the stone mine digs round its
   feet: the floor at y 62 under [8 8] and [9 8], and a trunk on the ground four blocks east."
  {[8 62 8] blocks/air
   [8 63 8] blocks/air
   [9 62 8] blocks/air
   [9 63 8] blocks/air
   [12 64 8] 136
   [12 65 8] 136})

(deftest a-quarry-with-a-level-step-across-its-floor-is-climbed-out-of
  ;; race 38515209959 (issue #102, 16 real bots; #103, 14) and the sim race in worlds R3/36 and
  ;; R3/32: the stone mine dug its cobblestone round the bot's feet, the stairs it came down by
  ;; among them, and left it at the bottom of a quarry two or three deep and a few cells wide
  ;; with the trees seven blocks off. The sky was over the feet and a level step crossed the
  ;; floor, so stairs/out? said out and nothing was cut; no route reached a trunk, the hunt had
  ;; none either, and each waited :no-log. A quarry is a pit: its floor has a wall every way
  (let [w (remembered quarry [8.5 62.0 8.5])
        feet (intent/feet w)]
    (is (terrain/under-sky? w feet) "the sky is over the quarry")
    (is (stairs/open-step? w (stairs/level-cells feet [1 0])) "a level step east, across its floor")
    (is (= 64 (stairs/pit-rim w feet)) "walls two high every way: the rim is the ground")
    (is (not (stairs/out? w feet)) "a quarry is not out of the ground")
    (is (not (intent/routable? w (wood/log-walk w [12 64 8]))) "two deep: no jump climbs it")
    (is (nil? (wood/hunt w)) "no ground a hunt could walk to")
    (is (= {:intent/kind :stairs-up
            :intent/to-y (stairs/climb-to w terrain/top-y)
            :intent/dir [1 0]
            :intent/for :surface}
           (wood/gather-next w))))
  (let [ground (remembered {[12 64 8] 136 [12 65 8] 136} [8.5 64.0 8.5])
        feet (intent/feet ground)]
    (is (nil? (stairs/pit-rim ground feet)) "open ground is no pit")
    (is (stairs/out? ground feet))))

(def crevice
  "A crevice two deep in the stone under the canopy of a chopped tree, like the sim race's in world
   R2/45 at [2256 68 4598]: the floor at y 62 under [8 8] and [8 9], a notch one up at [8 63 7]
   that the leaves at y 65 cap, so no jump leaves it, and a trunk on the ground six blocks north."
  {[8 62 8] blocks/air
   [8 63 8] blocks/air
   [8 62 9] blocks/air
   [8 63 9] blocks/air
   [8 63 7] blocks/air
   [8 65 7] 252
   [8 65 8] 252
   [8 64 2] 136
   [8 65 2] 136
   [8 66 2] 136
   [8 67 2] 252})

(deftest a-crevice-under-the-canopy-is-climbed-out-of
  ;; sim race 38515210080, world R2/45 (issue #104, 19 runs; race 38515209959, 4 real bots): the
  ;; first log came down into a crevice of the forest floor two deep under its own canopy, and
  ;; the kit's sticks sent the bot for a second with the trunk two blocks off. A level step ran
  ;; along the crevice's floor and the leaves over it are not cover, so stairs/out? said out;
  ;; the one notch up ended under the leaves, no route reached a trunk or a hunt's ground, and
  ;; the bot waited :no-log. The crevice is a pit (stairs/pit-rim): stairs are cut up out of it
  (let [w (remembered crevice [8.5 62.0 8.5])
        feet (intent/feet w)]
    (is (terrain/under-sky? w feet) "leaves are not cover: the sky is over the crevice")
    (is (stairs/open-step? w (stairs/level-cells feet [0 1])) "a level step south, along it")
    (is (= 64 (stairs/pit-rim w feet)) "a wall every way past the notch: the rim is the ground")
    (is (not (stairs/out? w feet)) "a crevice is not out of the ground")
    (is (not (intent/routable? w (wood/log-walk w [8 64 2]))) "the notch ends under the leaves")
    (is (nil? (wood/hunt w)) "no ground a hunt could walk to")
    (is (= {:intent/kind :stairs-up
            :intent/to-y (stairs/climb-to w terrain/top-y)
            :intent/dir [0 -1]
            :intent/for :surface}
           (wood/gather-next w))))
  ;; played on (issue check, PR #114): the first stair up put the bot in the notch, one deep
  ;; under the leaves; every column round it had a cell to stand in a block up, so pit-rim took
  ;; it for open ground, the climb ended and the wait began again. A step up needs the jump's
  ;; headroom: under the leaves the notch is still a pit
  (let [w (remembered crevice [8.5 63.0 7.5])
        notch (intent/feet w)]
    (is (= [8 63 7] notch))
    (is (= [-1 0] (stairs/step-rises w notch)) "leaves over the head: no step goes up")
    (is (= 64 (stairs/pit-rim w notch)) "a wall every way")
    (is (not (stairs/out? w notch)))
    (is (nil? (stairs/pit-rim (remembered (dissoc crevice [8 65 7]) [8.5 63.0 7.5]) notch))
        "with the sky clear over the head, the ground a block up is stepped onto: no pit")))

(def canopy-pocket
  "A pocket one deep under an oak canopy, like race 38477665442 runner 10 Clj_r16's at
   [2256 69 4597]: walls two high round the feet at [8 64 8], the one step north open at body
   height but leaves at head height over it and over the feet, so no jump climbs it; a trunk on
   open ground six blocks north."
  (merge
   (into
    {}
    (for [dx [-1 0 1] dz [-1 0 1] y [64 65] :when (not= [dx dz] [0 0])] [[(+ 8 dx) y (+ 8 dz)] 1]))
   {[8 65 7] blocks/air}
   (into {} (for [[x z] [[8 7] [8 8] [7 7] [9 7] [7 8] [9 8] [8 6]]] [[x 66 z] 252]))
   {[8 64 2] 136 [8 65 2] 136 [8 66 2] 136 [8 67 2] 136 [8 68 2] 252}))

(deftest a-pocket-under-a-canopy-is-climbed-out-of
  ;; race 38477665442 runner 10 bot Clj_r16 (issue #63): after the first log the bot stood in a
  ;; hole under an oak canopy, every exit a jump whose headroom the leaves filled (leaves are
  ;; solid to a route), and twelve walks to trunks failed :no-path. A trunk no route reaches is
  ;; passed over (#75) and the pocket, not out of the ground, has stairs cut up out of it (#56)
  (let [w (remembered canopy-pocket [8.5 64.0 8.5])
        feet (intent/feet w)]
    (is (terrain/under-sky? w feet) "leaves are not cover: the sky is over the pocket")
    (is (not (stairs/out? w feet)) "but no level step leads onto open ground")
    (is (not (intent/routable? w (wood/log-walk w [8 64 2]))) "every jump out meets leaves")
    (is (= {:intent/kind :stairs-up
            :intent/to-y (stairs/climb-to w terrain/top-y)
            :intent/dir [0 -1]
            :intent/for :surface}
           (wood/gather-next w)))))

(deftest a-near-trunk-a-route-reaches-is-walked-to
  ;; the plain case, unchanged: on the surface with a trunk in reach the gather walks to it
  (is (= {:intent/kind :walk :intent/target [3 64 0] :intent/for :log :intent/trunk [3 64 0]}
         (wood/gather-next (world-state [0.5 64.0 0.5])))))

(def sky-shaft
  "Air from a pocket at [2 50 8] up a stair to [8 56 8], that column open to the sky. A log at
   [14 70 14] is sealed in stone, past the near band, and no route reaches it."
  (merge (into {} (for [i (range 7) dy [0 1 2]] [[(+ 2 i) (+ 50 i dy) 8] blocks/air]))
         (into {} (for [y (range 59 64)] [[8 y 8] blocks/air]))
         (into {} (for [x (range 12 17) y (range 68 74) z (range 12 17)] [[x y z] 1]))
         {[14 70 14] 136}))

(deftest a-shaft-to-the-sky-is-walked-not-cut
  ;; full run 4528031 cut a new stair out of the iron mine and the only pickaxe broke on it
  (let [surface (into {} (for [x (range 8 16) y (range 56 64) z (range 16)] [[x y z] blocks/air]))
        w (remembered (merge sky-shaft surface) [2.5 50.0 8.5])
        i (wood/gather-next w)]
    (is (not (terrain/under-sky? w (intent/feet w))))
    (is
     (nil?
      (memory/nearest-log w (game/eye w) wood/search-radius (wood/skipped w) wood/standing-trunk?))
     "the log is in the stone, past the near band")
    (is (= {:intent/kind :walk :intent/target [8 56 8] :intent/for :opening} i))
    (is (stairs/out? w (:intent/target i)))
    (let [out (assoc w :player/pos [8.5 56.0 8.5])]
      (is (intent/arrived? out i 2.0))
      (is (nil? (intent/opening out))))))

(deftest the-opening-up-the-shaft-is-kept
  ;; the mouth is above the pocket: the walk up the shaft already dug, as intent/opening says
  (let [surface (into {} (for [x (range 8 16) y (range 56 64) z (range 16)] [[x y z] blocks/air]))
        w (remembered (merge sky-shaft surface) [2.5 50.0 8.5])]
    (is (= :walk (:intent/kind (wood/opening-up w))))
    (is (= (intent/opening w) (wood/opening-up w)))))

(deftest a-shaft-open-to-the-sky-is-not-a-way-out
  (let [w (remembered sky-shaft [2.5 50.0 8.5])
        mouth [8 56 8]
        opening {:intent/kind :walk :intent/for :opening :intent/target mouth}
        at-mouth (assoc w :player/pos [8.5 56.0 8.5])]
    (is (= :found (:path/status (path/plan w (intent/feet w) {:goal/kind :sky}))))
    (is (terrain/under-sky? w mouth))
    (is (not (stairs/out? w mouth)))
    (is (not (path/goal-done? w {:goal/kind :surface} mouth)))
    (is (nil? (intent/opening w)) "do not walk back down into the shaft between climbs")
    (is (not (intent/arrived? at-mouth opening 2.0)) "an in-flight walk must also reach ground")
    (is (= :stairs-up (:intent/kind (wood/gather-next w))))
    (is (= :stairs-up (:intent/kind (wood/gather-next at-mouth))))))

(deftest a-walk-out-continues-past-the-sky-over-a-shaft
  (let [steps (into {} (for [i (range 9) dy [0 1 2]] [[8 (+ 56 i dy) (- 8 i)] blocks/air]))
        w (remembered (merge sky-shaft steps) [8.5 56.0 8.5])
        i (intent/opening w)]
    (is (terrain/under-sky? w (intent/feet w)))
    (is (not (stairs/out? w (intent/feet w))))
    (is (= [:walk :opening] [(:intent/kind i) (:intent/for i)]))
    (is (not (intent/arrived? w i 2.0)))
    (is (stairs/out? w (:intent/target i)))))

(deftest a-trunk-above-the-band-is-walked-to
  (let [w (remembered shaft [0.5 40.0 8.5])
        eye (game/eye w)]
    (is (nil? (memory/nearest-log w eye wood/search-radius (wood/skipped w) wood/standing-trunk?))
        "more than 12 above the eye: the near search misses it")
    ;; sixteen over the feet: the walk ends where the drop lies in the pickup box (wood/log-walk,
    ;; the forest property at FOREST_SEED 388575908371)
    (is (= {:intent/kind :walk
            :intent/target [14 56 9]
            :intent/for :log
            :intent/trunk [14 56 9]
            :intent/pickup? true}
           (wood/gather-next w)))))

(deftest a-failed-intent-is-blacklisted-and-retried
  (let [w (world-state [0.5 64.0 0.5])
        w (assoc w
                 :plan/status :active
                 :plan/since 0
                 :plan/blacklist #{}
                 :plan/attempts 0
                 :time/tick 1300 ; past the walk's timeout
                 :plan/intent
                 {:intent/kind :walk
                  :intent/target [13 64 0]
                  :intent/status :active
                  :intent/started 0
                  :intent/best-tick 0})
        [w _] (run w [{:event/kind :tick :event/now 50 :event/rand 0.5}])]
    (is (= {:intent/kind :walk
            :intent/target [3 64 0]
            :intent/for :log
            :intent/trunk [3 64 0]
            :intent/status :active
            :intent/id 1
            :intent/goal :wood}
           (:plan/intent w))
        "re-planned in the same tick toward the next log")
    (is (= #{[13 64 0]} (:plan/blacklist w)))
    (is (= 1 (:plan/attempts w)))))

(deftest a-trunk-that-fails-twice-is-given-up-whole
  (let [failing {:intent/kind :walk
                 :intent/target [13 65 0]
                 :intent/for :log
                 :intent/trunk [3 64 0]
                 :intent/status :active
                 :intent/started 0
                 :intent/best-tick 0}
        w (assoc (world-state [0.5 64.0 0.5])
                 :plan/status :active
                 :plan/since 0
                 :plan/blacklist #{}
                 :plan/attempts 0
                 :time/tick 1300
                 :plan/intent failing) ; past the walk's timeout: it fails at once
        [w1 _] (run w [{:event/kind :tick :event/now 50 :event/rand 0.5}])]
    (is (= {[3 0] 1} (:plan/trunk-failures w1)))
    (is (= [3 64 0] (:intent/trunk (:plan/intent w1))) "once is chance: the same trunk again")
    (let [[w2 _] (run (assoc w1 :plan/intent (assoc failing :intent/target [13 66 0]))
                      [{:event/kind :tick :event/now 100 :event/rand 0.5}])]
      (is (= {[3 0] 2} (:plan/trunk-failures w2))
          "counted by column, whichever log of it the chain was at")
      (is (nil? (:plan/intent w2)) "twice: given up, and there is no other trunk"))))

(deftest a-walk-that-fails-no-path-puts-the-trees-round-it-aside
  ;; real race 39042120284 bot Clj_r14 (issue #75): the trees were a grove two hundred blocks off
  ;; across the sea, and each of twelve walks to a trunk of it failed :no-path alone
  (let [w (assoc (world-state [0.5 64.0 0.5])
                 :plan/status :active
                 :plan/blacklist #{}
                 :plan/attempts 0
                 :plan/unwalkable 0)
        walk {:intent/kind :walk :intent/target [13 65 0] :intent/for :log :intent/trunk [13 65 0]}
        failed (plan/fail-intent w (assoc walk :intent/reason :no-path))
        skip (wood/skipped failed)]
    (is (= #{[13 65 0]} (:plan/unreached failed)))
    (is (skip [13 65 0]) "the trunk itself")
    (is (skip [17 70 6]) "a trunk of the same grove")
    (is (not (skip [40 65 0])) "one far from it")
    (is (not (contains? (plan/fail-intent w (assoc walk :intent/reason :stuck)) :plan/unreached))
        "only a walk with no route puts a place aside")
    (is (not (contains? (plan/fail-intent w (assoc walk :intent/reason :no-path :intent/for :stone))
                        :plan/unreached))
        "and only a walk for a log: a stone no route reaches says nothing of the trees")
    (is (not ((wood/skipped w) [17 70 6])) "before any walk failed")))

(deftest digs-the-log-at-feet-height
  (testing "standing level with the trunk base: the bottom log"
    (is (= [3 64 0] (clojurecraft.wood/trunk-target (world-state [0.5 64.0 0.5]) [3 64 0]))))
  (testing "standing two blocks above the base: the log at feet height, so the drop lands in reach"
    (is (= [3 66 0] (clojurecraft.wood/trunk-target (world-state [0.5 66.0 0.5]) [3 64 0]))))
  (testing "standing above the whole trunk: its top log"
    (is (= [3 66 0] (clojurecraft.wood/trunk-target (world-state [0.5 70.0 0.5]) [3 64 0])))))

(deftest a-success-ends-the-failure-streak
  (let [w (assoc
           (world-state [0.5 64.0 0.5])
           :plan/status :active
           :plan/since 0
           :plan/blacklist #{}
           :plan/attempts 2
           :plan/goals #{:wood}
           :plan/intent
           {:intent/kind :walk :intent/target [3 64 0] :intent/for :log :intent/status :active})
        [w _] (run w [{:event/kind :tick :event/now 50 :event/rand 0.5}])]
    (is (= 0 (:plan/attempts w)) "the walk succeeded, so two earlier failures no longer count")
    (is (= :dig (get-in w [:plan/intent :intent/kind])))))

(deftest a-go-with-a-landing-waits-until-the-bot-is-there
  (let [w (assoc (world-state [12.5 64.0 12.5]) :time/now 0) ; same loaded chunk, 17 blocks away
        go-at (assoc go :go/at [0.5 64.0 0.5])
        [w _] (run w [go-at])]
    (is (= :landing (:plan/status w)) "the fixture's :go names a landing")
    (is (= [0.5 64.0 0.5] (:plan/go-at w)))
    (let [[w' _] (run w [{:event/kind :tick :event/now 50 :event/rand 0.5}])]
      (is (= :landing (:plan/status w'))
          "in a loaded chunk but far from the landing: no planning yet")
      (is (nil? (:plan/intent w'))))
    (let [[w' _] (run (assoc w :player/pos [0.5 64.0 0.5])
                      [{:event/kind :tick :event/now 50 :event/rand 0.5}])]
      (is (= :active (:plan/status w')) "at the landing with the chunk loaded: planning starts"))
    (let [[w' _] (run (assoc w :player/pos [0.5 64.0 200.5])
                      [{:event/kind :tick :event/now 50 :event/rand 0.5}])]
      (is (= :landing (:plan/status w')) "far away in an unloaded chunk: still waiting"))
    (let [[w' _] (run w
                      [{:event/kind :tick :event/now (+ 50 plan/landing-timeout) :event/rand 0.5}])]
      (is (= [:failed :no-landing] [(:plan/status w') (:plan/reason w')]) "never arrived"))))

(deftest a-go-without-a-landing-starts-where-the-bot-stands
  (let [[w _] (run (world-state [0.5 64.0 0.5]) [go])] (is (= :active (:plan/status w)))))

(deftest until-names-come-from-the-goal-table
  (is (= [:wood] (plan/goals-for "wood")))
  (is (= [:kit] (plan/goals-for "table")))
  (is (= [:pickaxe] (plan/goals-for "pickaxe")))
  (is (nil? (plan/goals-for "play"))))

(deftest the-current-stage-is-the-furthest-target
  (is (= "pearls-12" (plan/current-until)))
  ;; A gym stage names only its own target. A fresh run plays every step up to it.
  (is (= [:ender-pearls] (plan/goals-for (plan/current-until))))
  (let [chain (plan/through (plan/current-until))]
    (is (= [:wood :kit :pickaxe :cobblestone :stone-pickaxe :furnace :ingots :bucket :water-bucket
            :flint-and-steel :iron-kit :portal-kit :cast-one :portal :nether :fortress :blaze-rods
            :ender-pearls]
           chain))
    (is (= [:wood] (plan/through "wood")))
    (is (nil? (plan/through "play")))
    (is (= :wood
           (:goal/id (plan/choose (assoc (world-state [0.5 64.0 0.5]) :plan/goals (set chain))
                                  plan/goals)))
        "empty hands at spawn: the chain starts at wood, not the portal"))
  (is (= "later"
         (plan/current-until
          [{:goal/id :wood :goal/target? true :goal/priority 1 :goal/until "wood"}
           {:goal/id :escape
            :goal/target? true
            :goal/always? true
            :goal/priority 30
            :goal/until "escape"}
           {:goal/id :old
            :goal/target? true
            :goal/priority 40
            :goal/until "old"
            :goal/superseded-by :later}
           {:goal/id :later :goal/target? true :goal/priority 9 :goal/until "later"}]))))

(deftest a-chained-run-does-not-go-back-for-a-step-it-reached
  ;; grok's first chained run (PR #32, run 38216982945) crafted the pickaxe, which spent the
  ;; kit's sticks, so :kit was undone again and preempted it: open the table, craft, craft a
  ;; stick, twice a second for five minutes. A chained run's steps are milestones.
  (let [chain (plan/through "pickaxe")
        holding (fn [w items]
                  (assoc w
                         :player/inventory
                         (into {}
                               (map-indexed
                                (fn [i [item n]] [(+ 36 i) {:item (recipe/item-id item) :count n}])
                                items))))
        w (plan/begin (world-state [0.5 64.0 0.5]) {:go/goals chain :go/chain? true} plan/goals)
        kit-held (plan/reach (holding w {:crafting_table 1 :stick 4 :oak_log 1}) plan/goals)
        spent (holding kit-held {:crafting_table 1 :stick 2 :oak_planks 1})]
    (is (= #{:wood :kit} (:plan/reached kit-held)) "the logs and the kit held: both reached")
    (is (= :pickaxe (:goal/id (plan/choose spent plan/goals)))
        "two sticks spent on the pickaxe: the kit is not made again")
    (testing "a gym stage, not chained, still re-derives every step"
      (let [stage (plan/begin (world-state [0.5 64.0 0.5]) {:go/goals chain} plan/goals)]
        (is (nil? (:plan/reached (plan/reach (holding stage {:crafting_table 1 :stick 4})
                                             plan/goals))))
        (is (= :wood
               (:goal/id (plan/choose (holding stage {:crafting_table 1 :stick 2})
                                      plan/goals))))))))

(def superseding-table
  "A table where :wood was fixed as :wood-2 and :wood-2 again as :wood-3."
  [{:goal/id :wood
    :goal/priority 1
    :goal/target? true
    :goal/provides {:tag/logs 1}
    :goal/done? :provided?
    :goal/superseded-by :wood-2}
   {:goal/id :wood-2
    :goal/priority 1
    :goal/target? true
    :goal/provides {:tag/logs 1}
    :goal/done? :provided?
    :goal/superseded-by :wood-3}
   {:goal/id :wood-3
    :goal/priority 1
    :goal/target? true
    :goal/until "wood"
    :goal/provides {:tag/logs 1}
    :goal/done? :provided?}])

(deftest a-retired-goal-stays-and-points-at-its-replacement
  (is (= :wood-3 (plan/current-id superseding-table :wood))
      "the chain is followed to the row in force")
  (is (= :wood-3 (plan/current-id superseding-table :wood-3)))
  (is (= :other (plan/current-id superseding-table :other))
      "an id the table does not know is itself")
  (is (= [:wood-3] (map :goal/id (plan/targets-of superseding-table))) "only live rows are chosen")
  (is (= :a
         (plan/current-id [{:goal/id :a :goal/superseded-by :b}
                           {:goal/id :b :goal/superseded-by :a}]
                          :a))
      "a cycle ends instead of hanging")
  (testing "a recorded :go naming the old id plans for the replacement"
    (let [[w _] (fold (game/compose game/step (partial plan/step-over superseding-table))
                      (world-state [0.5 64.0 0.5])
                      [{:event/kind :go :go/goals [:wood]}])]
      (is (= #{:wood-3} (:plan/goals w)) "no redefinition: the table is an argument")))
  (is (true? clojurecraft.spec/goals-valid?) "the real table's replacements all exist"))

(deftest the-leaf-over-a-step-is-a-blocker
  (let [col (world/column {[0 64 1] 1 [0 66 1] 252})
        w (assoc (game/init fx/opts) :player/pos [0.5 64.0 0.5] :world/chunks {[0 0] col})]
    (is (= [0 66 1] (clojurecraft.intent/blocker w [0.5 65.0 3.5]))
        "a step at the feet, leaves where the jump goes"))
  (let [col (world/column {[0 66 1] 252})
        w (assoc (game/init fx/opts) :player/pos [0.5 64.0 0.5] :world/chunks {[0 0] col})]
    (is (nil? (clojurecraft.intent/blocker w [0.5 64.0 3.5]))
        "no step: a leaf above the head is not in the way")))

(deftest a-wall-is-not-a-step
  (let [col (world/column {[0 64 1] 136 [0 65 1] 136 [0 66 1] 252})
        w (assoc (game/init fx/opts) :player/pos [0.5 64.0 0.5] :world/chunks {[0 0] col})]
    (is (not (clojurecraft.intent/step? w [0 64 1])) "a trunk is a wall")
    (is (nil? (clojurecraft.intent/blocker w [0.5 64.0 3.5]))
        "so the leaf on top of it is not in the way")))

(deftest a-jump-onto-a-step-needs-headroom-over-the-player-too
  (let [col (world/column {[0 64 1] 1 [0 66 0] 252})
        w (assoc (game/init fx/opts) :player/pos [0.5 64.0 0.5] :world/chunks {[0 0] col})]
    (is (= [0 66 0] (clojurecraft.intent/blocker w [0.5 65.0 3.5]))
        "the leaf over the bot caps the jump")))

(defn walking
  "Active plan over goals, mid-walk toward a point out of reach, the walk serving goal."
  [goals goal]
  (assoc (world-state [0.5 64.0 0.5])
         :plan/status :active
         :plan/since 0
         :plan/blacklist #{}
         :plan/attempts 0
         :plan/goals goals
         :plan/intents 1
         :plan/intent
         {:intent/kind :walk
          :intent/target [13 64 0]
          :intent/for :log
          :intent/status :active
          :intent/id 1
          :intent/goal goal
          :intent/started 0
          :intent/best-tick 0}))

(deftest done-is-re-derived-every-tick-even-mid-intent
  (let [w (assoc (walking #{:wood} :wood)
                 :player/inventory
                 {0 {:item (blocks/items :oak_log) :count 1}}) ; a log arrived
        [w _] (run w [{:event/kind :tick :event/now 50 :event/rand 0.5}])]
    (is (plan/done? w) "the world says the goal is met, so the plan is done this tick")
    (is (nil? (:plan/intent w)) "the walk is dropped")
    (is (= [:abandoned :goal-met]
           ((juxt :intention/event :intention/reason) (last (memory/intentions w)))))))

(deftest a-regressed-target-preempts-the-running-intent
  ;; the walk serves :kit, but :wood (priority 1) is in play and no log is held: :wood comes first
  (let [[w _] (run (walking #{:wood :kit} :kit)
                   [{:event/kind :tick :event/now 50 :event/rand 0.5}])]
    (is (= [:abandoned :preempted]
           ((juxt :intention/event :intention/reason)
            (first (filter #(= 1 (:intention/id %)) (reverse (memory/intentions w)))))))
    (is (= :wood (get-in w [:plan/intent :intent/goal]))
        "the next intent serves the regressed target")))

(deftest nothing-is-dropped-with-an-item-on-the-cursor
  (let [w (-> (walking #{:wood} :wood)
              (assoc :player/inventory {0 {:item (blocks/items :oak_log) :count 1}}
                     :window/cursor {:item (blocks/items :oak_planks) :count 1}))
        [w _] (run w [{:event/kind :tick :event/now 50 :event/rand 0.5}])]
    (is (not (plan/done? w)) "the plan waits until the hand is empty")
    (is (= 1 (get-in w [:plan/intent :intent/id])))))

;; ---------------------------------------------------------------- a walk follows a route

(deftest a-walk-plans-again-only-when-its-route-breaks
  ;; gym batch cobble-a1 run 7: six chunks arrived in the first seconds after landing and each
  ;; one re-planned the walk, spending its six plans before a step was taken, though every plan
  ;; found the same route. A chunk arriving is not news; a waypoint ahead that can no longer be
  ;; stood on is
  (let [w (walking #{:wood} :wood)
        [w1 _] (run w [{:event/kind :tick :event/now 50 :event/rand 0.5}])
        i1 (:plan/intent w1)
        arrivals (reduce (fn [w n]
                           (first (run (assoc w :stats/chunks (+ n (:stats/chunks w 0)))
                                       [{:event/kind :tick :event/now (* 100 n) :event/rand 0.5}])))
                         w1
                         (range 1 7))]
    (is (= 1 (:intent/replans i1)) "the first tick plans")
    (is (= :found (:intent/route i1)))
    (is (= 1 (:intent/replans (:plan/intent arrivals)))
        "six chunk arrivals, the route intact: no plan spent")
    (let [i (:plan/intent arrivals)
          broken (terrain/set-block arrivals (get (:intent/waypoints i) (:intent/at i)) 1) ; stone where the next step is
          [w2 _] (run broken [{:event/kind :tick :event/now 1000 :event/rand 0.5}])]
      (is (= 2 (:intent/replans (:plan/intent w2))) "a waypoint ahead gone solid: plan again"))))

(deftest a-long-route-gets-the-time-to-walk-it
  ;; sim world R2/19 seed 12 (issue #87's second round): a route of 123 waypoints over a snowy
  ;; mountain was at waypoint 116 when the walk's sixty seconds ran out, and three such walks
  ;; failed :stuck
  (let [route (fn [n] {:intent/kind :walk :intent/waypoints (vec (repeat n [0 64 0]))})]
    (is (= intent/walk-timeout-ticks (intent/walk-deadline (route 0))) "no route yet: the base")
    (is (< (* 116 10) (- (intent/walk-deadline (route 123)) intent/walk-timeout-ticks))
        "123 waypoints: time for every one, at half a second each")))

(deftest a-walk-with-no-way-on-fails-no-path
  ;; walled in on every side two blocks high: the search exhausts its frontier at once
  (let [cage (into {} (for [x [-1 1] y [64 65] z [-1 0 1]] [[(+ 2 x) y (+ 2 z)] 1]))
        cage (merge cage (into {} (for [z [-1 1] y [64 65]] [[2 y (+ 2 z)] 1])))
        col (world/column (merge cage {[13 64 0] 136 [13 65 0] 136 [13 66 0] 252}))
        w (-> (game/init fx/opts)
              (assoc :bot/phase :play
                     :player/pos [2.5 64.0 2.5]
                     :player/loaded? true
                     :player/on-ground? true)
              (assoc :world/chunks {[0 0] col})
              (memory/remember-column [0 0] col)
              (assoc :plan/status :active
                     :plan/since 0
                     :plan/blacklist #{}
                     :plan/attempts 0
                     :plan/goals #{:wood}
                     :plan/intents 1
                     :plan/intent
                     {:intent/kind :walk
                      :intent/target [13 64 0]
                      :intent/for :log
                      :intent/status :active
                      :intent/id 1
                      :intent/goal :wood}))
        [w _] (run w [{:event/kind :tick :event/now 50 :event/rand 0.5}])]
    (is (= 0 (:plan/attempts w))
        "no way to walk it: the target is given up, and the search is not a failed attempt")
    (is (= #{[13 64 0]} (:plan/blacklist w)) "and its target is blacklisted")
    (is (= :no-path
           (:intent/reason (first (filter #(= :failed (:intention/event %)) (memory/intentions w)))
                           (:intention/reason (first (filter #(= :failed (:intention/event %))
                                                             (memory/intentions w))))))
        "for :no-path")
    (is (= 1 (:plan/unwalkable w)) "it counts apart")))

(deftest walks-that-never-arrive-still-end-the-plan
  ;; a :no-path walk spends no attempt, so a search whose places keep changing would never fail;
  ;; max-unwalkable of them in a row do, and anything that succeeds starts the count again
  (let [no-path (fn [n]
                  {:intent/kind :walk :intent/reason :no-path :intent/target [n 64 0] :intent/id n})
        active (plan/begin (world-state [0.5 64.0 0.5]) {:go/goals [:wood]} plan/goals)
        fail-n (fn [n] (reduce plan/fail-intent active (map no-path (range n))))
        failed (fail-n plan/max-unwalkable)]
    (is (= :active (:plan/status (fail-n (dec plan/max-unwalkable)))))
    (is (= :failed (:plan/status failed)))
    (is (= :no-path (:plan/reason failed)))
    (is (zero? (:plan/attempts failed)) "none of them an attempt")
    (is (zero? (:plan/unwalkable (plan/finish-intent (fail-n 5) {:intent/kind :dig :intent/id 99})))
        "a success starts the count again")
    (is (zero? (:plan/unwalkable (plan/fail-intent
                                  (fail-n 1)
                                  {:intent/kind :dig :intent/reason :target-gone :intent/id 2})))
        "any other failure breaks the streak")))

(deftest an-identical-failed-intent-steps-back-instead-of-repeating
  ;; findings 2026-10-27 item 1: stairs-down or end-portal failing from feet must not be retried
  ;; identically in a loop to fail the plan in under a second; it steps back to the previous cell
  (let [active (-> (plan/begin (world-state [5.5 64.0 5.5]) {:go/goals [:wood]} plan/goals)
                   (assoc :plan/trail [[0 64 0] [5 64 5]]))
        failed (plan/fail-intent active
                                 {:intent/kind :stairs-down :intent/to-y 20 :intent/reason :boxed})
        goals [{:goal/id :test-goal :goal/done? :never :goal/act :stair}]
        table goals]
    (with-redefs [plan/choose (constantly (first goals))
                  plan/next-intent (constantly {:intent/kind :stairs-down :intent/to-y 20})]
      (let [w (plan/plan-tick failed {:event/kind :tick :event/now 50 :event/rand 0.5} table)]
        (is (= :active (:plan/status w)) "does not fail the plan")
        (is (contains? (:plan/boxed-at w) [5 64 5]) "failed feet cell marked boxed")
        (is (= :walk (get-in w [:plan/intent :intent/kind])) "steps back via walk")
        (is (= [0 64 0] (get-in w [:plan/intent :intent/target])) "walks back to previous cell")))))

(deftest run-3-boxed-stairs-down-steps-back-and-never-fails-in-retry-storm
  ;; gym chain-w6 run 3 (17:47:19): stairs-down boxed at [14828 31 13564] failed attempts 1, 2, 3
  ;; in 0.4s because turned dir [-1 0] did not match planned dir [0 -1]. An identical stairs-down
  ;; must step back along trail and never repeat the identical intent nor fail while time remains.
  (let [feet [14828 31 13564]
        prev [14829 36 13563]
        active (-> (plan/begin (world-state [14828.5 31.0 13564.5]) {:go/goals [:wood]} plan/goals)
                   (assoc :plan/trail [prev feet]))
        ;; During execution stairs-down turned through headings to [-1 0] before dying :boxed
        failed-intent {:intent/kind :stairs-down
                       :intent/to-y 23
                       :intent/dir [-1 0]
                       :intent/turns 3
                       :intent/reason :boxed}
        goals [{:goal/id :test-goal :goal/done? :never :goal/act :stair}]
        table goals]
    (with-redefs [plan/choose (constantly (first goals))
                  ;; Next-intent computes initial heading toward frame [0 -1], NOT [-1 0]
                  plan/next-intent (constantly
                                    {:intent/kind :stairs-down :intent/to-y 23 :intent/dir [0 -1]})]
      ;; Driven with failure: plan-tick must detect identical retry despite different dir
      (let [w1 (plan/plan-tick (plan/fail-intent active failed-intent)
                               {:event/kind :tick :event/now 50 :event/rand 0.5}
                               table)]
        (is (= :active (:plan/status w1)) "first failure does not fail the plan")
        (is (contains? (:plan/boxed-at w1) feet) "feet cell marked boxed-at")
        (is (= :walk (get-in w1 [:plan/intent :intent/kind])) "steps back via walk")
        (is (= prev (get-in w1 [:plan/intent :intent/target]))
            "walks back to previous cell in trail")
        (is (not= :stairs-down (get-in w1 [:plan/intent :intent/kind]))
            "does not repeat identical stairs-down")))))

(deftest any-intent-failing-no-path-counts-as-unwalkable-not-attempts
  ;; end-portal or child walks failing :no-path are unwalkable routes, not instant failure attempts
  (let [active (plan/begin (world-state [0.5 64.0 0.5]) {:go/goals [:wood]} plan/goals)
        failed (plan/fail-intent active {:intent/kind :end-portal :intent/reason :no-path})]
    (is (zero? (:plan/attempts failed)) "no-path spends no attempt")
    (is (= 1 (:plan/unwalkable failed)) "counts under unwalkable")))

;; ---------------------------------------------------------------- digging with tools

(defn digging
  "In play at [0.5 64 0.5] over stone, with block id at [3 64 0] (and a floor under it), inv
   as the player inventory, and a dig of it as the current intent, already numbered."
  [id inv]
  (let [col (world/column {[3 64 0] id [3 63 0] 1})]
    (-> (game/init fx/opts)
        (assoc :bot/phase :play
               :player/pos [0.5 64.0 0.5]
               :player/vel [0.0 -0.078 0.0]
               :player/loaded? true
               :player/on-ground? true
               :player/inventory inv
               :world/chunks {[0 0] col})
        (memory/remember-column [0 0] col)
        (assoc :plan/status :active
               :plan/since 0
               :plan/blacklist #{}
               :plan/attempts 0
               :plan/goals #{:wood}
               :plan/intents 1
               :plan/intent
               {:intent/kind :dig
                :intent/target [3 64 0]
                :intent/status :active
                :intent/id 1
                :intent/goal :wood}))))

(def wooden-pickaxe (get blocks/items :wooden_pickaxe))
(def stone-pickaxe (get blocks/items :stone_pickaxe))
(def iron-ore (first (blocks/states-where (fn [[n]] (= n :iron_ore)))))

(deftest stone-is-dug-with-the-pickaxe-in-its-slot
  (let [[w fx] (run (digging 1
                             {3 {:item wooden-pickaxe :count 1}
                              0 {:item (blocks/items :stone) :count 2}})
                    (ticks 50 5000)) ; slot 0 holds stone, not a log: :wood stays wanted; 5 s outlasts confirm-ticks
        carried (filter #(= :set-carried-item (:packet/name (second %))) (sent fx))
        actions (filter #(= :player-action (:packet/name (second %))) (sent fx))
        [[t-start start] [t-finish finish]] actions]
    (is (= 3 (:slot (second (first carried)))) "the pickaxe's hotbar slot, not 0")
    (is (= 3 (:player/held-slot w)))
    (is (= 0 (:status start)))
    (is (= 2 (:status finish)))
    (is (>= (- t-finish t-start) (intent/finish-delay 1150))
        "stone with a wooden pickaxe is 1150 ms")
    (is (< (- t-finish t-start) (intent/finish-delay 3000)) "and not a log's 3000")
    (is (= (:sequence finish) (inc (:sequence start))))
    (is (= [3 wooden-pickaxe 1150]
           ((juxt :intent/slot :intent/tool :intent/dig-ms) (:plan/last w))))))

(deftest a-restored-block-after-finish-is-a-refused-break
  ;; the server breaks the block and sends its block update before the ack of FINISH: the cell
  ;; open is the break. A loaded server acks first and breaks seconds later by its own clock
  ;; (delayed destroy, issue #64), so the ack is waited on; the block still standing when
  ;; confirm-ticks run out is the server's word that it did not break it (race 38477665442,
  ;; runner 9: a lagging server dropped early FINISHes queued behind one another, the ack
  ;; alone, and the stairs walked into rock, issue #59)
  (let [sent-by (long (+ 400 (intent/finish-delay 1150))) ; settled, START, then FINISH
        [w _] (run (digging 1 {3 {:item wooden-pickaxe :count 1}}) (ticks 50 sent-by))
        i (:plan/intent w)
        failed-for (fn [w]
                     (:intention/reason (last (filter #(= :failed (:intention/event %))
                                                      (memory/intentions w)))))]
    (is (= :confirm (:intent/stage i)) "FINISH sent, waiting for the server")
    (is (= 1 (terrain/block-at w [3 64 0])) "still stone locally until the server says")
    (let [[w2 _] (run w
                      [(packet {:packet/name :block-update :pos [3 64 0] :state 0})
                       {:event/kind :tick :event/now (+ sent-by 50) :event/rand 0.5}])]
      (is (= :done (:intent/status (:plan/last w2))) "the block update is the break")
      (is (= 0 (terrain/block-at w2 [3 64 0]))))
    (let [acked [(packet {:packet/name :block-changed-ack :sequence (:intent/sequence i)})
                 {:event/kind :tick :event/now (+ sent-by 50) :event/rand 0.5}]
          waited (ticks (+ sent-by 100) (+ sent-by 100 (* 50 (inc intent/confirm-ticks))))
          [w3 _] (run w (cons (packet {:packet/name :block-update :pos [3 64 0] :state 1}) acked))
          [w4 _] (run w acked)
          [w4b _] (run w4
                       [(packet {:packet/name :block-update :pos [3 64 0] :state 0})
                        {:event/kind :tick :event/now (+ sent-by 100) :event/rand 0.5}])
          [w4c _] (run w4 waited)]
      (is (= :confirm (:intent/stage (:plan/intent w3)))
          "the block put back, then the ack: the server may yet break it by its own clock")
      (is (= :confirm (:intent/stage (:plan/intent w4)))
          "the ack alone, the block standing: waited on")
      (is (= :done (:intent/status (:plan/last w4b))) "the late break is the break")
      (is (= 1 (:plan/attempts w4c))
          "the ack and no break in confirm-ticks: the server did not break it")
      (is (= :rejected (failed-for w4c)))
      (is (= 1 (terrain/block-at w4c [3 64 0])) "and it is still there"))
    (let [[w5 _] (run w (ticks (+ sent-by 50) (+ sent-by 50 (* 50 (inc intent/confirm-ticks)))))]
      (is (= :done (:intent/status (:plan/last w5)))
          "no word in confirm-ticks: the overlay is trusted")
      (is (= 0 (terrain/block-at w5 [3 64 0]))))))

(deftest a-tiered-block-with-no-reaching-tool-is-refused-before-start
  (let [[w fx] (run (digging iron-ore {3 {:item wooden-pickaxe :count 1}}) (ticks 50 1000))]
    (is (empty? (filter #(= :player-action (:packet/name (second %))) (sent fx)))
        "no START: it would drop nothing")
    (is (= :needs-tool
           (:intention/reason (last (filter #(= :failed (:intention/event %))
                                            (memory/intentions w)))))))
  (let [[w fx] (run (digging iron-ore {3 {:item stone-pickaxe :count 1}}) (ticks 50 1000))]
    (is (seq (filter #(= :player-action (:packet/name (second %))) (sent fx)))
        "a stone pickaxe reaches it")
    (is (= 1150 (:intent/dig-ms (:plan/intent w))))))

(deftest a-dig-whose-drop-is-not-wanted-breaks-ore-the-pickaxe-cannot-harvest
  ;; sim race 38806318231 (issue #70, 1065 runs): the cast's room met deepslate redstone ore with
  ;; a wooden pickaxe, and the dig refused it :needs-tool three times; a build only needs the cell
  (let [[w fx] (run (assoc-in (digging iron-ore {3 {:item wooden-pickaxe :count 1}})
                     [:plan/intent :intent/drop-ok?]
                     true)
                    (ticks 50 1000))]
    (is (seq (filter #(= :player-action (:packet/name (second %))) (sent fx)))
        "START: the ore is broken for nothing")
    (is (= 3 (:player/held-slot w)) "with the pickaxe, the fastest of its kind")
    (is (= 7500 (:intent/dig-ms (:plan/intent w))) "at a hundredth of the wooden pickaxe's speed")))

(deftest a-tool-that-vanishes-mid-dig-fails-tool-broke
  (let [[w _] (run (digging 1 {3 {:item wooden-pickaxe :count 1}}) (ticks 50 1200))]
    (is (= :digging (:intent/stage (:plan/intent w))))
    (let [[w2 _] (run w
                      [(packet {:packet/name :set-player-inventory :slot 3 :item nil})
                       {:event/kind :tick :event/now 1250 :event/rand 0.5}])]
      (is (= :tool-broke
             (:intention/reason (last (filter #(= :failed (:intention/event %))
                                              (memory/intentions w2)))))))))

(deftest a-failed-crossing-keeps-its-trunk-for-the-bridge
  ;; sim worlds R3/45 (issues #87 and #108): the cross toward the only trunk in reach met a cliff
  ;; and failed :stuck; blacklisting the trunk left the climb up the cliff nothing to climb toward
  (let [w (assoc (world-state [0.5 64.0 0.5])
                 :plan/status :active
                 :plan/blacklist #{}
                 :plan/attempts 0)
        cross
        {:intent/kind :cross :intent/target [3 64 0] :intent/status :failed :intent/reason :stuck}
        once (plan/fail-intent w cross)
        twice (plan/fail-intent once cross)]
    (is (= #{} (:plan/blacklist once)) "the trunk is not blacklisted")
    (is (= {[3 0] 1} (:plan/trunk-failures once)) "the failure counts against its column")
    (is (not ((wood/skipped once) [3 64 0])) "one failed cross is chance")
    (is ((wood/skipped twice) [3 64 0]) "a second gives the trunk up")
    (is (= #{[3 64 0]} (:plan/blacklist (plan/fail-intent w (assoc cross :intent/kind :walk))))
        "a walk that failed there still blacklists it")))

(deftest a-cell-a-place-or-a-redig-chose-is-never-blacklisted
  ;; race 38477665442 bot Clj_r04 (issue #43): a redig failed :not-picked-up at the cell before
  ;; its feet, the table for a spare pickaxe went into that cell, make/near skipped it as
  ;; blacklisted, and the bot went for the wood of a new table with its last pickaxe
  (let [w (assoc (world-state [0.5 64.0 0.5])
                 :plan/status :active
                 :plan/blacklist #{}
                 :plan/attempts 0)
        failed (fn [i]
                 (plan/fail-intent w
                                   (assoc i :intent/status :failed :intent/reason :not-picked-up)))]
    (is (= #{}
           (:plan/blacklist (failed
                             {:intent/kind :redig :intent/target [1 64 0] :intent/item :gravel})))
        "the redig's cell is where it put the gravel, not a place it could not reach")
    (is (= #{}
           (:plan/blacklist
            (failed {:intent/kind :place :intent/target [1 64 0] :intent/item :crafting_table}))))
    (is (= #{[1 64 0]} (:plan/blacklist (failed {:intent/kind :collect :intent/target [1 64 0]})))
        "a collect that failed there still is")
    (is (= 1 (:plan/attempts (failed {:intent/kind :redig :intent/target [1 64 0]})))
        "the attempt counts")))

(def shore
  "Chunks cx -3..3, cz -2..2 of bare floor, the bot's own among them, with a trunk at [40 64 8]
   on an island in a moat of water four wide: in the near band, standing on ground, and no
   route reaches it."
  (into {}
        (for [cx (range -3 4)
              cz (range -2 3)]
          [[cx cz]
           (world/column (if (= [2 0] [cx cz])
                           (merge (into {}
                                        (for [x (range 4 13)
                                              z (range 4 13)
                                              :when (not= [8 8] [x z])]
                                          [[x 63 z] blocks/water-base]))
                                  {[8 64 8] 136 [8 65 8] 136})
                           {}))])))

(deftest with-no-route-to-any-trunk-the-bot-walks-toward-the-trees
  ;; race 38477665442 bots Clj_r14 and Clj_r20 (issue #57) landed on a lake's shore with the
  ;; forest across the water: no route reached a trunk, and each waited :no-log until the plan
  ;; failed. Under the open sky the gather now hunts toward the nearest log, as a water hunt
  ;; goes out, so the chunks on the way are remembered and a route round is found
  (let [w (-> (remembered {} [8.5 64.0 8.5])
              (assoc :world/chunks shore)
              (memory/remember-column [2 0] (shore [2 0])))
        i (wood/gather-next w)]
    (is (= [40 64 8]
           (memory/nearest-log w (game/eye w) wood/search-radius #{} wood/standing-trunk?)))
    (is (not (intent/routable? w (wood/log-walk w [40 64 8]))) "sealed in stone")
    (is (nil? (wood/near-walk w)))
    (is (nil? (wood/climb-out w)) "under the open sky: nothing to climb")
    (is (= {:intent/kind :walk :intent/target [48 64 8] :intent/for :log-hunt} i)
        "forty blocks east, toward the trunk")
    (is (= :log-hunt
           (:intent/for (wood/gather-next
                         (assoc (remembered {} [8.5 64.0 8.5]) :world/chunks shore))))
        "with no log remembered the hunt goes out all the same (race 38665691517, issue #57)")))
(deftest a-dig-that-never-settles-starts-by-the-deadline
  ;; race 38477665442 bots Clj_r12 (runner 5, on a moss carpet the physics does not know, the
  ;; server putting it back on the carpet every tick) and Clj_r19 (runner 3, fallen through a
  ;; guessed air the server kept as stone): never three still ticks, and the dig's settle
  ;; waited for them for the rest of the run
  (let [never-still (fn [w e]
                      (let [w (assoc (game/step w e) :player/on-ground? false)]
                        (if (and (= :tick (:event/kind e)) (:plan/intent w))
                          (intent/run w (:plan/intent w) e)
                          w)))
        w (assoc (world-state [2.5 64.0 0.5])
                 :plan/intent
                 {:intent/kind :dig :intent/target [3 64 0] :intent/status :active})
        started (fn [fx] (some (fn [[t p]] (when (= :player-action (:packet/name p)) t)) (sent fx)))
        [_ early] (fold never-still w (ticks 10 (- intent/settle-deadline 100)))
        [w late] (fold never-still w (ticks 30 (+ intent/settle-deadline 400)))]
    (is (nil? (started early)) "within the deadline it waits to be still")
    (is (> (started late) intent/settle-deadline) "past it, START anyway")
    (is (= :digging (get-in w [:plan/intent :intent/stage])))))

(deftest with-no-tree-known-the-wood-hunt-sets-out
  ;; race 38665691517 runner 1 (issue #57): slots Clj_r08, Clj_r14 and Clj_r15 landed where no
  ;; standing trunk was in reach, with routes out on three bearings, and waited :no-log at the
  ;; landing round after round: the hunt set out only toward a log already remembered
  (let [w (assoc (remembered {} [8.5 64.0 8.5])
                 :world/chunks
                 (into {} (for [cx (range -3 4) cz (range -3 4)] [[cx cz] (world/column {})])))
        i (wood/gather-next w)]
    (is (empty? (memory/positions-now w blocks/log-states)) "no log anywhere")
    (is (= :walk (:intent/kind i)))
    (is (= :log-hunt (:intent/for i)) "out along a bearing, where the next chunks load")))

(deftest the-wait-fails-in-ticks-not-wall-ms
  ;; race 38680745815 runner 5 (issue #57): with 25 bots and two servers to a runner the
  ;; bot's loop got 9 ticks in 21 s, and the wait's wall clock failed the plan :no-log while
  ;; the water trap's 20 ticks and the hunt's next routable hop were still owed theirs
  (let [w (assoc (world-state [0.5 64.0 0.5]) :world/facts (memory/empty-facts))
        [w0 _] (run w [go])
        at (fn [n] (first (run w0 (repeat n {:event/kind :tick :event/now 1000 :event/rand 0.5}))))]
    (is (= :active (:plan/status (at plan/wait-ticks))) "the wait-tick-th tick still waits")
    (is (plan/failed? (at (inc plan/wait-ticks))) "one tick past it, the plan fails")
    (is (= :no-log (:plan/reason (at (inc plan/wait-ticks)))))))

(deftest a-hunt-that-knows-its-trees-walks-back-toward-the-landing
  ;; race 38680745815 runner 5 (issue #57) bot Clj_r10: its first hunt hop left the landing
  ;; with no trunk remembered; once the valley's trunks were known behind it, every bearing
  ;; back toward them was refused as nearer the landing, the one bearing left had no route,
  ;; and the plan waited :no-log out its clock
  (let [w (-> (assoc (remembered {} [8.5 64.0 8.5]) :world/chunks shore :plan/go-at [48.5 64.0 8.5])
              (memory/remember-column [2 0] (shore [2 0])))
        i (wood/hunt w)]
    (is (= [48 64 8] (:intent/target i)) "toward the trunk, back toward the landing")))

(deftest a-hunt-never-heads-for-a-log-it-gave-up
  ;; sim world R1/4 (issue #370): the nearest remembered log was the tree whose logs were
  ;; blacklisted, so each hop ended beside it and the next set out for another given-up one,
  ;; back and forth for the rest of the window
  (let [w (-> (assoc (remembered {} [8.5 64.0 8.5]) :world/chunks shore :plan/go-at [48.5 64.0 8.5])
              (memory/remember-column [2 0] (shore [2 0])))
        given-up (set (memory/positions-now w blocks/log-states))]
    (is (seq given-up) "a trunk is remembered")
    (is (= [48 64 8] (:intent/target (wood/hunt w))) "toward it while it is a tree to take")
    (is (not= [48 64 8] (:intent/target (wood/hunt (assoc w :plan/blacklist given-up))))
        "not once every log of it is blacklisted")))

(deftest a-slow-server-stretches-the-dig
  ;; race 38665691517 (issues #88, #144): the server ran at 130 ms a tick, counted the digs'
  ;; progress in its ticks, and left blocks standing that the bot had finished by its own clock
  (testing "set-time comes every 20 server ticks: two 2.6 s apart are ticks of 130 ms"
    (let [[w _] (run (world-state [0.5 64.0 0.5])
                     (concat (ticks 50 150)
                             [(packet {:packet/name :set-time})]
                             (ticks 150 2750)
                             [(packet {:packet/name :set-time})]))]
      (is (= 130.0 (:server/tick-ms w)))
      (is (= 2.6 (intent/slowdown w)))))
  (testing "FINISH and the wait for the break stretch by the slowdown"
    (let [[_ fx] (run (assoc (world-state [0.5 64.0 0.5]) :server/tick-ms 130.0)
                      (concat [go] (ticks 50 12000)))
          [[t-start _] [t-finish _]] (filter #(= :player-action (:packet/name (second %)))
                                             (sent fx))]
      (is (>= (- t-finish t-start) (intent/finish-delay 3000 2.6)))
      (is (> (intent/finish-delay 3000 2.6) (* 2 (intent/finish-delay 3000)))))))

(deftest a-trunk-walled-in-by-its-leaves-is-tunnelled-to
  ;; sim world R1/4 (issues #370, #186) and real-2/1 (issue #87): out on a platform four over a
  ;; two-log oak whose leaves wall it in, no route reaches the trunk, and the plan waited :no-log
  (let [leaves (blocks/default-state :oak_leaves)
        ground (into {} (for [x (range 0 12) z (range 5 12)] [[x 63 z] 1]))
        canopy (into {} (for [x (range 0 4) y (range 64 68) z (range 7 10)] [[x y z] leaves]))
        deck (into {} (for [x (range 7 11) z (range 7 10)] [[x 68 z] 1]))
        w (remembered (merge ground canopy deck {[1 64 8] 136 [1 65 8] 136}) [8.5 69.0 8.5])
        trunk [1 64 8]
        run {:intent/kind :tunnel-to
             :intent/target trunk
             :intent/trunk trunk
             :intent/reach wood/tunnel-reach
             :intent/steps wood/tunnel-steps
             :intent/dry? true
             :intent/for :trees}
        done {:intent/kind :tunnel-to :intent/for :trees :intent/status :done}]
    (is (stairs/out? w (intent/feet w)) "out of the ground, on the deck")
    (is (nil? (wood/near-walk w)) "no route reaches the trunk through its leaves")
    (is (= run (wood/trees-tunnel w)) "a level run through the leaves to a cell beside it")
    (is (= run (wood/gather-next w)))
    (is (nil? (wood/trees-tunnel (assoc w :plan/boxed-at #{[8 69 8]})))
        "never from a cell where a climb died boxed")
    (is (nil? (wood/trees-tunnel (assoc w :plan/blacklist #{trunk})))
        "a trunk given up when a step was refused is not tunnelled to again")
    (is (nil? (wood/trees-tunnel (assoc w :plan/trunk-failures {[1 8] wood/max-trunk-failures})))
        "nor after failures in its column")
    (is (nil? (wood/trees-tunnel (assoc w :player/pos [2.5 64.0 8.5])))
        "beside the trunk there is nothing to tunnel to")
    (is (not (wood/tunneled? w)))
    (is (wood/tunneled? (assoc w :plan/last done)))
    (is (not (wood/tunneled? (assoc w :plan/last (assoc done :intent/for :surface)))))
    (is (not (wood/tunneled? (assoc w :plan/last (assoc done :intent/status :failed)))))))

(deftest the-leaf-under-the-feet-is-a-blocker-of-a-drop-below-it
  ;; sim world R1/4 (issues #186, #370): a log dug from the top of the canopy fell into the hole
  ;; under the leaves the bot stood on, out of the pickup box, and the collect timed out
  (let [col (world/column {[0 63 0] 252 [0 62 0] 136})
        w (assoc (game/init fx/opts) :player/pos [0.5 64.0 0.5] :world/chunks {[0 0] col})
        under #(clojurecraft.intent/leaf-under w %)]
    (is (= [0 63 0] (under [0.5 62.5 0.5])) "the drop two under the feet, a leaf between")
    (is (nil? (under [0.5 63.5 0.5])) "within the pickup box: nothing to dig")
    (is (nil? (under [4.5 62.5 0.5])) "not in the bot's column")
    (is (nil? (clojurecraft.intent/leaf-under
               (assoc w :world/chunks {[0 0] (world/column {[0 63 0] 1})})
               [0.5 62.5 0.5]))
        "stone under the feet is the pathfinder's, not a leaf's")))

(deftest a-collect-centres-over-a-drop-below-the-feet
  ;; sim world R1/4 (issues #186, #370): the bot stood on a leaf beside the hole its log fell
  ;; into, its box over the edge, the log a quarter block out of the pickup box, and the collect
  ;; waited 0.2 blocks from the drop's centre, doing nothing, until it timed out
  (let [col (world/column {[0 62 0] 136 [1 63 0] 252})
        at (fn [x y]
             (assoc (game/init fx/opts)
                    :player/pos [x y 0.5]
                    :time/now 1000
                    :time/tick 10
                    :world/chunks {[0 0] col}))
        run (fn [w]
              (clojurecraft.intent/run w
                                       {:intent/kind :collect :intent/target [0 63 0]}
                                       {:event/kind :tick}))
        item (fn [w]
               (assoc w
                      :world/entities
                      {1 {:entity/type blocks/item-entity-type :entity/pos [0.5 63.25 0.5]}}))]
    (is (seq (:player/controls (run (item (at 0.72 64.0)))))
        "0.2 off, 0.75 over the drop: walks on")
    (is (empty? (:player/controls (run (item (at 0.72 63.4))))) "within the pickup box: waits")))

(deftest a-descent-goes-on-toward-the-trunk-it-began-for
  ;; sim world R1/4 (issue #370): trunks either side of the shaft, the nearest one changing at
  ;; every length, so the heading turned north and south and the bot zigzagged down
  (let [w (remembered {[8 50 8] blocks/air [8 51 8] blocks/air [8 52 8] blocks/air} [8.5 50.0 8.5])
        w (assoc w :player/pos [8.5 70.0 8.5])
        near [[8 20 12]]
        far [[8 20 -20]]
        cut {:intent/kind :stairs-down :intent/for :trees :intent/status :done}
        dir #(:intent/dir (wood/trees-descend (assoc w :plan/last (assoc cut :intent/trunk %))
                                              (concat near far)))
        pick (wood/trees-descend (assoc w :plan/last (assoc cut :intent/trunk (first far)))
                                 (concat near far))]
    (is (= [0 1] (dir (first near))) "the trunk it began for is the near one")
    (is (= [0 -1] (dir (first far))) "or the far one, whichever it began for")
    (is (= (first far) (:intent/trunk pick)) "the intent names the trunk it is for")))

(deftest a-descent-that-ends-level-with-the-trunk-tunnels-on
  ;; sim snapshot R2-43-1 (issue #135): stairs down inside a hill ended level with a trunk fifteen
  ;; blocks off; out? is false there, so the climb-out cut stairs up to the top of the world, and
  ;; the bot walked down its own stairs and cut the same descent again for the whole window
  (let [rock (into {} (for [x (range 0 16) y (range 58 71) z (range 4 13)] [[x y z] 1]))
        w (remembered (merge rock
                             {[0 61 8] blocks/air [0 62 8] blocks/air [14 61 8] 136 [14 62 8] 136})
                      [0.5 61.0 8.5])
        leg
        {:intent/kind :stairs-down :intent/for :trees :intent/trunk [14 61 8] :intent/status :done}
        run {:intent/kind :tunnel-to
             :intent/target [14 61 8]
             :intent/trunk [14 61 8]
             :intent/reach wood/tunnel-reach
             :intent/steps wood/tunnel-steps
             :intent/dry? true
             :intent/for :trees}]
    (is (not (stairs/out? w (intent/feet w))) "in the ground")
    (is (nil? (wood/trees-tunnel w)) "no tunnel from the ground a descent did not end in")
    (is (= run (wood/trees-tunnel (assoc w :plan/last leg))) "on from the foot, level")
    (is (= run (wood/trees-tunnel (assoc w :plan/last (assoc leg :intent/for :valley)))))
    (is (not= :stairs-up (:intent/kind (wood/climb-out (assoc w :plan/last leg))))
        "not up to the top of the world")
    (is (nil? (wood/trees-tunnel (assoc w :plan/last (assoc leg :intent/status :failed)))))))
