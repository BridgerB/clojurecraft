(ns clojurecraft.stone-test
  "The :mine act over the rows that name what they dig: the tool each needs, the drops a
   collect waits for, the stairs it cuts when nothing is in sight, and where it stops."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojurecraft.blocks :as blocks]
            [clojurecraft.fixtures :as fx]
            [clojurecraft.game :as game]
            [clojurecraft.intent :as intent]
            [clojurecraft.plan :as plan]
            [clojurecraft.stairs :as stairs]
            [clojurecraft.stone :as stone]
            [clojurecraft.world :as world]))

(use-fixtures :once fx/instrumented)

(defn row [id] (first (filter #(= id (:goal/id %)) plan/goals)))

(defn standing
  "On a bare stone floor (nothing to mine in sight but stone) with feet at y, holding items by
   name in the hotbar."
  [y & items]
  (assoc (game/init fx/opts)
         :bot/phase :play
         :player/pos [5.5 (double y) 5.5]
         :player/loaded? true
         :player/on-ground? true
         :player/inventory
         (into {} (map-indexed (fn [i n] [i {:item (blocks/items n) :count 1}])) items)
         :world/chunks {[0 0] (world/column {})}))

(deftest redig-places-only-where-the-drop-can-be-collected
  (let [w (standing 64 :gravel)
        place (stone/redig-place w :gravel)]
    (is (= {:intent/kind :place :intent/item :gravel :intent/cell [4 64 5]} place)
        "a supported neighbour, not the first diagonal cell within click reach")
    (is (= [5 64 4] (:intent/cell (stone/redig-place (assoc w :world/blocks {[4 65 5] 1}) :gravel)))
        "a niche with no headroom cannot be entered for the drop")
    (let [walls (into {} (for [cell [[4 64 5] [6 64 5] [5 64 4] [5 64 6]]] [cell 1]))
          child (stone/redig-place (assoc w :world/blocks walls) :gravel)]
      (is (contains? #{:walk :stairs-up} (:intent/kind child))
          "no pickup cell: leave, never cut a niche"))))

(deftest redig-tries-placement-again-after-reaching-fresh-ground
  (doseq [kind [:walk :stairs-up]]
    (let [i {:intent/kind :redig
             :intent/item :gravel
             :intent/child {:intent/kind kind :intent/status :done}}
          w (assoc (standing 64 :gravel) :plan/intent i)
          next (stone/redig-stage w i {:event/kind :tick})]
      (is (nil? (get-in next [:plan/intent :intent/child])))
      (is (nil? (get-in next [:plan/intent :intent/stage]))
          "a completed retreat is not a completed placement"))))

(deftest a-stair-down-seals-water-before-opening-the-rock
  (let [feet [5 64 5]
        cells (stairs/stair-cells feet [1 0])
        w (assoc (standing 64 :stone_pickaxe :dirt) :world/blocks {[7 63 5] blocks/water-base})
        down {:intent/kind :stairs-down
              :intent/to-y 58
              :intent/status :active
              :intent/visited #{[5 63 6] [4 63 5] [5 63 4]}}]
    (is (stairs/safe? w cells) "a floor, with water beside the rock")
    (is (not (stairs/down-safe? w cells)) "the child dig would refuse the water")
    (is (= [[7 63 5]] (stairs/down-lay w feet cells)))
    (is (= :lay
           (get-in (intent/run (assoc w :plan/intent down) down {:event/kind :tick :event/now 0})
                   [:plan/intent :intent/stage]))
        "seal before digging, even with too little filler to floor a cave")
    (is (stairs/down-safe? (assoc w :world/blocks {}) cells) "dry rock is cut as before")
    (is (stairs/down-safe? (update w :world/blocks assoc [6 63 5] blocks/air) cells)
        "an open cell beside water needs no dig")
    (is (nil? (stairs/down-lay (assoc w :player/inventory {}) feet cells)) "no filler: turn")
    (is (nil? (stairs/down-lay (assoc-in w [:world/blocks [6 66 5]] blocks/water-base) feet cells))
        "water overhead cannot be sealed from below")
    (is (nil? (stairs/down-lay (assoc w :world/blocks {[7 63 5] blocks/lava-base}) feet cells))
        "lava is still refused")))

(deftest each-row-collects-what-its-blocks-drop
  (is (= #{(blocks/items :cobblestone)} (stone/drops-of-row (row :mine-stone))))
  (is (= #{(blocks/items :coal)} (stone/drops-of-row (row :mine-coal))))
  (is (= #{(blocks/items :raw_iron)} (stone/drops-of-row (row :mine-iron))))
  (is (= #{(blocks/items :gravel) (blocks/items :flint)} (stone/drops-of-row (row :dig-gravel)))
      "gravel's loot table: flint one time in ten, else gravel")
  (is (= (stone/states-of-row (row :mine-stone)) (stone/states-of-row {})) "stone by default"))

(deftest the-tool-a-row-needs
  (is (= {:plan/wait :no-pickaxe} (stone/mine-next (standing 64) (row :mine-iron))))
  (is (= {:plan/wait :no-pickaxe} (stone/mine-next (standing 64 :wooden_pickaxe) (row :mine-iron)))
      "iron ore drops only to stone or better")
  (is (= :stairs-down (:intent/kind (stone/mine-next (standing 64) (row :dig-gravel))))
      "gravel drops to a hand"))

(deftest with-nothing-in-sight-the-stairs-stop-at-the-rows-depth
  (testing "stairs: six down at a time"
    (is (= {:intent/kind :stairs-down :intent/to-y 58 :intent/floor-to 16}
           (stone/mine-next (standing 64 :stone_pickaxe) (row :mine-iron)))
        "carrying the row's depth, which floors laid through a cave are budgeted to"))
  (testing "never below the row's depth: there, a tunnel"
    (is (= {:intent/kind :stairs-down :intent/to-y 16 :intent/floor-to 16}
           (stone/mine-next (standing 20 :stone_pickaxe) (row :mine-iron))))
    (is (= {:intent/kind :tunnel :intent/dir [1 0] :intent/length stone/tunnel-length}
           (stone/mine-next (standing 16 :stone_pickaxe) (row :mine-iron))))
    (is (= [-1 0]
           (:intent/dir (stone/mine-next
                         (assoc (standing 16 :stone_pickaxe) :plan/go-at [40.5 70.0 5.5])
                         (row :mine-iron))))
        "outward: away from the landing"))
  (testing "a heading refused, or with no rock left to cut, is not taken; with none, deeper"
    (let [lava (first (blocks/states-where (fn [[n]] (= n :lava))))
          w (assoc (standing 16 :stone_pickaxe)
                   :plan/go-at [5.5 70.0 40.5] ; south: north leads farthest from it
                   :world/chunks
                   {[0 0] (world/column {[7 16 5] lava [5 16 6] lava [5 16 4] 0 [5 17 4] 0})})]
      (is (= [-1 0] (stone/tunnel-heading w))
          "north is an open tunnel, east borders lava, so west (south is lava itself)")
      (is (= {:intent/kind :stairs-down :intent/to-y 10}
             (stone/mine-next
              (assoc w
                     :world/chunks
                     {[0 0] (world/column
                             (into {} (for [x [4 5 6] y [16 17] z [4 5 6]] [[x y z] 0])))})
              (row :mine-iron)))
          "a room with no rock round it to tunnel: stairs six deeper")))
  (testing
    "nothing to tunnel and no stair to cut: away along the way out, or out of the water first"
    (let [lava (first (blocks/states-where (fn [[n]] (= n :lava))))
          corridor (merge (into {} (for [x (range 5 16) y [16 17]] [[x y 5] 0]))
                          (into {} (for [z (range 5 16) y [16 17]] [[15 y z] 0]))
                          {[5 16 7] lava [3 16 5] lava [5 16 3] lava [6 14 5] lava})
          w (assoc (standing 16 :stone_pickaxe) :world/chunks {[0 0] (world/column corridor)})
          r (stone/mine-next w (row :mine-iron))]
      (is (= [:walk :retreat] [(:intent/kind r) (:intent/for r)]) (pr-str r))
      (is (< 12 (+ (abs (- (first (:intent/target r)) 5)) (abs (- (nth (:intent/target r) 2) 5))))
          "to the far end of the corridor")
      (is (= {:intent/kind :swim-out :intent/for :retreat}
             (stone/mine-next (assoc w :player/in-water? true) (row :mine-iron)))
          "wading in a flooded tunnel: out of the water first")))
  (testing "stone has no depth: always down"
    (is (= {:intent/kind :stairs-down :intent/to-y -40}
           (stone/mine-next (standing -34 :wooden_pickaxe) (row :mine-stone))))))

(def sea
  "A column with water on top, y 64 to 66: the sea over the rock."
  (world/column
   (into {} (for [x (range 16) y (range 64 67) z (range 16)] [[x y z] blocks/water-base]))))

(deftest tunnels-and-climbs-keep-under-dry-ground
  ;; gym batch iron-a8 run 2 tunnelled 260 blocks east at depth, out under the sea, and its stairs
  ;; up met the seabed every way: :boxed three times
  (testing "a tunnel takes a heading with dry ground over it a tunnel ahead"
    (let [w (assoc
             (standing 16 :stone_pickaxe)
             :player/pos [8.5 16.0 8.5]
             :plan/go-at [8.5 70.0 -40.5] ; north: south leads farthest from it
             :world/chunks
             {[0 0] (world/column {}) [0 1] sea [1 0] (world/column {}) [-1 0] (world/column {})})]
      (is (= [1 0] (stone/tunnel-heading w)) "east, not south under the sea")))
  (testing "under the sea with no shore in sight, a climb steps toward the landing"
    (let [w (assoc (standing 16 :stone_pickaxe)
                   :player/pos [8.5 16.0 8.5]
                   :plan/go-at [-40.5 70.0 8.5] ; west
                   :plan/intent {:intent/kind :stairs-up :intent/to-y 70 :intent/status :active}
                   :world/chunks {[0 0] sea [1 0] sea [-1 0] sea [0 1] sea [0 -1] sea})]
      (is (= [-1 0]
             (:intent/level-dir
              (:plan/intent (stairs/step-aside w {:intent/kind :stairs-up :intent/dir [1 0]})))))
      (is
       (= :level
          (:intent/stage (:plan/intent (stairs/step-aside (assoc w :plan/go-at [-140.5 70.0 8.5])
                                                          {:intent/kind :stairs-up
                                                           :intent/dir [1 0]
                                                           :intent/level-steps 40
                                                           :intent/level-dir [-1 0]}))))
       "the way home under the sea may be long: gym batch iron-a9 run 1, 155 blocks out, was
           :boxed after 32 steps three times"))))

(deftest a-boxed-descent-is-not-started-again-from-the-same-cell
  ;; full run ab3945e died at y 32 over an aquifer: every stair and every further level step was
  ;; refused, then two new stairs-down from that cell boxed in under a second and the plan failed
  (let [lava (first (blocks/states-where (fn [[n]] (= n :lava))))
        corridor (merge (into {} (for [x (range 5 16) y [32 33]] [[x y 5] 0]))
                        (into {} (for [z (range 5 16) y [32 33]] [[15 y z] 0]))
                        {[5 32 7] lava [3 32 5] lava [5 32 3] lava [6 30 5] lava})
        w (assoc (standing 32 :stone_pickaxe) :world/chunks {[0 0] (world/column corridor)})
        died (plan/fail-intent
              (assoc w :plan/blacklist #{} :plan/attempts 0)
              {:intent/kind :stairs-down :intent/reason :boxed :intent/status :failed :intent/id 7})
        r (stone/mine-next died (row :mine-iron))]
    (is (contains? (:plan/boxed-at died) [5 32 5]) "the cell it died on")
    (is (not (contains? (:plan/blacklist died) [5 32 5])) "not a target blacklisted")
    (is (= [:walk :retreat] [(:intent/kind r) (:intent/for r)]) (pr-str r))
    (is (= {:intent/kind :stairs-down :intent/to-y 26 :intent/floor-to 16}
           (stone/mine-next w (row :mine-iron)))
        "the first descent still starts")
    (let [stone (into {} (for [x (range 16) y (range 28 36) z (range 16)] [[x y z] 1]))
          hill (assoc w :world/chunks {[0 0] (world/column (assoc stone [5 32 5] 0 [5 33 5] 0))})]
      (is (stairs/safe? hill (stairs/stair-cells [5 32 5] [1 0])) "solid rock ahead is a stair")
      (is (not (stairs/safe? (assoc hill :plan/boxed-at #{[6 31 5]})
                             (stairs/stair-cells [5 32 5] [1 0])))
          "a later descent does not step back onto the cell"))))

(deftest deeper-only-where-a-stair-can-be-dug
  ;; gym batch iron-a8 run 3: its tunnel at y 16 was boxed, and the stairs deeper it was sent down
  ;; bordered water every way: the dig refused each cell :unsafe and the stairs failed :boxed twice
  ;; in a second
  (let [water (into {}
                    (for [[dx dz] [[1 0] [0 1] [-1 0] [0 -1]]]
                      [[(+ 5 (* 2 dx)) 15 (+ 5 (* 2 dz))] blocks/water-base]))
        w (assoc (standing 16 :stone_pickaxe) :world/chunks {[0 0] (world/column water)})]
    (is (some? (stone/deeper (standing 16 :stone_pickaxe))) "plain rock: stairs deeper")
    (is (nil? (stone/deeper w)) "water beside every stair: none")))

(deftest a-boxed-pocket-retreats-along-a-short-route-to-new-rock
  (let [corridor (into {} (for [x (range 5 10) y [48 49]] [[x y 5] blocks/air]))
        w (assoc (standing 48) :world/chunks {[0 0] (world/column corridor)})
        boxed (assoc w :plan/boxed-at #{[5 48 5]})]
    (is (nil? (stone/retreat w)) "an ordinary retreat still seeks twelve blocks of distance")
    (is (= {:intent/kind :walk :intent/target [9 48 5] :intent/for :retreat} (stone/retreat boxed))
        "a boxed pocket uses the reachable end even when twelve blocks cannot be reached")
    (is (= [8 48 5] (:intent/target (stone/retreat (update boxed :plan/boxed-at conj [9 48 5]))))
        "the shorter way ends on rock not already known to be boxed")
    (let [near (assoc boxed :plan/boxed-at #{[5 48 5] [7 48 5] [8 48 5] [9 48 5]})
          walk (stone/retreat near)
          away (assoc near :player/pos [6.5 48.0 5.5])]
      (is (= [6 48 5] (:intent/target walk)))
      (is (= {:goal/kind :block :goal/pos [6 48 5]} (intent/walk-goal walk)))
      (is (not (intent/arrived? near walk 1.5)) "in reach is still in the boxed pocket")
      (is (intent/arrived? away walk 1.5) "the retreat ends standing on its unboxed destination"))
    (is (nil? (stone/retreat (assoc boxed :plan/boxed-at (set (for [x (range 5 10)] [x 48 5])))))
        "a corridor whose every cell is boxed is not retried")))

(deftest boxed-in-by-water-with-no-way-away-the-mine-climbs-out
  ;; race 38515209959 (issue #105): runner 3 bot Clj_r19 and runner 9 bot Clj_r18 tunnelled for
  ;; gravel at y 38-40 into underground water, swam out onto a cell every stair and tunnel from
  ;; which was refused, with no route away along the flooded tunnel, and waited :boxed out
  ;; holding 405 and 466 cobblestone; runner 3 bot Clj_r13 started three tunnels in a row from
  ;; one pocket between two sheets of water, each :boxed a step or two on
  (let [water (into {[5 16 5] 0 [5 17 5] 0} ; the pocket, water two cells off every way
                    (for [[dx dz] stairs/directions]
                      [[(+ 5 (* 2 dx)) 16 (+ 5 (* 2 dz))] blocks/water-base]))
        pocket (assoc (standing 16 :stone_pickaxe :cobblestone)
                      :world/chunks
                      {[0 0] (world/column water)})]
    (is (nil? (stone/tunnel-heading pocket)) "every first step borders the water")
    (is (nil? (stone/deeper pocket)) "so does every stair down")
    (is (nil? (stone/retreat pocket)) "rock all round: no route away")
    (is (= {:intent/kind :stairs-up :intent/to-y 24 :intent/for :boxed}
           (stone/mine-next pocket (row :dig-gravel)))
        "a climb, eight stairs at a time")
    (is (nil? (stone/climb (standing 64 :stone_pickaxe))) "out of the ground there is none"))
  (testing
    "never back under the stairs just cut: sim race 38839665459 runner 3 (issue #71, world R1/19) cut stairs west to y 1, tunnelled east under them and undermined its only way back up"
    (let [w (standing 16 :stone_pickaxe)
          down
          (assoc w :plan/last {:intent/kind :stairs-down :intent/dir [-1 0] :intent/status :done})]
      (is (= [1 0] (stone/tunnel-heading w)) "plain rock: east first")
      (is (some? (stone/tunnel-heading down)))
      (is (not= [1 0] (stone/tunnel-heading down))
          "the stairs came down westward: not east under them")))
  (testing "a tunnel that died :boxed is not started again from its pocket"
    (let [w (standing 16 :stone_pickaxe)
          died (plan/fail-intent
                (assoc w :plan/blacklist #{} :plan/attempts 0)
                {:intent/kind :tunnel :intent/reason :boxed :intent/status :failed :intent/id 7})]
      (is (some? (stone/tunnel-heading w)) "plain rock: a tunnel")
      (is (contains? (:plan/boxed-at died) [5 16 5]) "the cell it died on")
      (is (nil? (stone/tunnel-heading died)))
      (is (some? (stone/deeper w)) "plain rock: stairs deeper")
      (is (nil? (stone/deeper died)) "no descent from the cell either")
      (is (= :stairs-up (:intent/kind (stone/mine-next died (row :mine-iron))))
          "encased in rock with no way away: the climb"))))

(def sealed-ore
  "Iron ore buried in the floor at [8 61 8], with a two-cell pocket beside it to stand in
   ([9 61 8] and [9 62 8]) and stone all round: exposed, and no route from the surface reaches
   it, as the live run's ore down in a cave. Another iron ore stands in the open at
   [14 64 14], farther off."
  {[8 61 8] (blocks/default-state :iron_ore)
   [9 61 8] blocks/air
   [9 62 8] blocks/air
   [14 64 14] (blocks/default-state :iron_ore)})

(deftest an-ore-no-route-reaches-is-passed-over
  ;; live iron-kit run 4: three walks to iron in an old excavation below, each failing :no-path
  ;; at once, and the third ended the plan
  (let [w (assoc (standing 64 :stone_pickaxe) :world/chunks {[0 0] (world/column sealed-ore)})]
    (is (= [8 61 8] (first (stone/exposed-of-states w (stone/states-of-row (row :mine-iron)))))
        "the sealed ore is the nearest exposed one")
    (is (=
         {:intent/kind :walk :intent/target [14 64 14] :intent/for :mine-iron :intent/pickup? true}
         (stone/mine-next w (row :mine-iron)))
        "the open one, farther, is the one walked to")))

(deftest exposed-rock-that-would-let-fluid-in-is-passed-over
  (let [near [6 64 5]
        far [10 64 5]
        w (assoc (standing 64 :stone_pickaxe)
                 :world/chunks
                 {[0 0] (world/column {near (blocks/default-state :iron_ore)
                                       far (blocks/default-state :iron_ore)})})
        seen #(stone/exposed-of-states % (stone/states-of-row (row :mine-iron)))]
    (is (= near (first (seen w))))
    (doseq [fluid [blocks/water-base blocks/lava-base]
            cell [[7 64 5] [6 65 5] [6 63 5]]
            :let [wet (assoc w :world/blocks {cell fluid})]]
      (is (not-any? #{near} (seen wet)) "the child dig would refuse the fluid")
      (is (= far (:intent/target (stone/mine-next wet (row :mine-iron)))))
      (is (= near (first (seen (assoc wet :world/blocks {}))))
          "a later dry observation makes the rock available again"))))

(deftest ore-beside-a-lava-trap-is-left-alone
  ;; sim race 38844683780 (issue #230, world R2/9): lava falling from ten blocks up caught the bot
  ;; at y 38; it fled a cell, the mine chose the iron two blocks off under the same flow, and the
  ;; lava caught it again until the flee found no footing
  (let [w (assoc (standing 64 :stone_pickaxe)
                 :world/chunks {[0 0] (world/column sealed-ore)}
                 :time/now 60000)
        trapped (fn [kind at]
                  (assoc w :world/traps [{:trap/pos [9 8] :trap/kind kind :trap/at at}]))
        first-ore #(first (stone/exposed-of-states % (stone/states-of-row (row :mine-iron))))]
    (is (= [8 61 8] (first-ore w)) "no trap: the nearest ore")
    (is (not= [8 61 8] (first-ore (trapped :lava 50000))) "a lava trap a column off: passed over")
    (is (= [8 61 8] (first-ore (trapped :water 50000))) "a water trap does not keep the mine away")
    (is (= [8 61 8] (first-ore (assoc (trapped :lava 50000) :time/now 700000)))
        "ten minutes on, the trap is out of force")
    (let [bare (assoc (standing 64 :stone_pickaxe) :time/now 60000)
          caught (assoc bare :world/traps [{:trap/pos [5 5] :trap/kind :lava :trap/at 50000}])]
      (is (= :stairs-down (:intent/kind (stone/mine-next bare (row :mine-iron))))
          "nothing in sight: stairs down")
      (is (= :retreat (:intent/for (stone/mine-next caught (row :mine-iron))))
          "where lava just caught it: away first, no stair under the flow"))))

(def corridor
  "A tunnel two high at y 64-65 along x from 2 to 12 at z 8, cut into stone filling y 64-70, with
   iron ore in its wall at the head (x 5), in its ceiling (x 8) and in its floor (x 11)."
  (merge (into {} (for [x (range 16) y (range 64 71) z (range 16)] [[x y z] 1]))
         (into {} (for [x (range 2 13) y [64 65]] [[x y 8] blocks/air]))
         {[5 65 9] (blocks/default-state :iron_ore)
          [8 66 8] (blocks/default-state :iron_ore)
          [11 63 8] (blocks/default-state :iron_ore)}))

(deftest a-tunnel-shows-ore-at-the-head-and-overhead
  ;; batch iron-a2 run 4 tunnelled over a hundred blocks at y 16 seeing only the walls at its feet
  (let [w (assoc (standing 64 :stone_pickaxe)
                 :player/pos [2.5 64.0 8.5]
                 :world/chunks {[0 0] (world/column corridor)})
        seen (set (stone/exposed-of-states w (stone/states-of-row (row :mine-iron))))]
    (is (contains? seen [5 65 9])
        "in the wall at the head: dug from the cell beside it, a block lower")
    (is (contains? seen [8 66 8]) "in the ceiling: dug from under it")
    (is (contains? seen [11 63 8])
        "in the floor: dug from the tunnel beside it, its drop picked up from the hole it leaves")
    (is (= [12 64 8] (stone/beside w [11 63 8])) "from a cell beside it, a block up")))

(deftest a-block-in-the-floor-counts-only-where-it-leaves-a-step-down
  ;; live run 14 tunnelled for gravel at y 18 over five gravel blocks in its own floor
  (let [w (assoc (standing 64 :stone_pickaxe)
                 :player/pos [2.5 64.0 8.5]
                 :world/chunks {[0 0] (world/column corridor)})
        dug (fn [cells] (assoc w :world/chunks {[0 0] (world/column (merge corridor cells))}))]
    (is (nil? (stone/beside (dug {[11 62 8] blocks/air}) [11 63 8]))
        "nothing under it: a drop, not a step")
    (is (nil? (stone/beside (dug {[11 64 8] 1}) [11 63 8]))
        "stone over it: nowhere to step down from")))

(deftest a-stair-up-asked-for-mid-jump-waits-for-the-ground
  ;; race 38515209959 (issue #107, at 7239df5) bots Clj_r04 (runner 3) and Clj_r06 (runner 7): a
  ;; collect jumping against a two-high wall had the climb out asked for at y 65.17, mid-jump,
  ;; so its cells were planned a block too high, the step under the new feet was never cut, and
  ;; the jump met the wall for 10 s, :stair-not-taken three times. The third bot, Clj_r03
  ;; (runner 9), walked into a stair cell the lagging server had not cut, its FINISH dropped
  ;; behind a delayed destroy; a dig ends on the block update since ac38129. Covered since
  ;; a54c3ec: a staircase waits in :plan for the ground (stairs/airborne?)
  (let [wall {[6 64 5] 1 [6 65 5] 1}
        up {:intent/kind :stairs-up :intent/to-y 70 :intent/to-height? true :intent/status :active}
        tick {:event/kind :tick :event/now 1000 :event/rand 0.5}
        jumping (assoc (standing 64 :stone_pickaxe)
                       :player/pos [5.5 65.17 5.5]
                       :player/on-ground? false
                       :world/chunks {[0 0] (world/column wall)}
                       :plan/intent up)
        w (intent/run jumping up tick)]
    (is (stairs/airborne? jumping))
    (is (nil? (:intent/stage (:plan/intent w))) "still in :plan, nothing planned from the air")
    (is (= {} (:player/controls w)) "and no push: the body lands")
    (let [landed (assoc jumping :player/pos [5.5 64.0 5.5] :player/on-ground? true)
          w (intent/run landed up tick)]
      (is (= :open (:intent/stage (:plan/intent w))))
      (is (= [[5 66 5] [6 66 5] [6 65 5]] (:intent/cells (:plan/intent w)))
          "the stair from the landed feet, its new feet on the wall's lower block"))))

(deftest each-new-stair-gets-a-full-turn
  (doseq [[kind from to cells] [[:stairs-down [4 65 5] 58 stairs/stair-cells]
                                [:stairs-up [4 63 5] 70 stairs/up-cells]]]
    (let [i {:intent/kind kind
             :intent/to-y to
             :intent/to-height? true
             :intent/status :active
             :intent/stage :step
             :intent/dir [1 0]
             :intent/turns 3
             :intent/cells (cells from [1 0])
             :intent/started-y (double (second from))
             :intent/started-tick 0}
          w (assoc (standing 64 :stone_pickaxe)
                   :world/blocks {[7 63 5] blocks/water-base}
                   :time/tick 20
                   :plan/intent i)
          tick {:event/kind :tick :event/now 1000}
          stepped (intent/run w i tick)
          next (intent/run stepped (:plan/intent stepped) tick)]
      (is (= :plan (get-in stepped [:plan/intent :intent/stage])))
      (is (= 0 (get-in stepped [:plan/intent :intent/turns]))
          "refusals from the previous feet cell do not box the new one")
      (is (= :active (get-in next [:plan/intent :intent/status])))
      (is (= 1 (get-in next [:plan/intent :intent/turns])))
      (is (= [0 1] (get-in next [:plan/intent :intent/dir]))))))

(deftest a-stair-up-replans-after-falling-below-its-launch-height
  (let [up {:intent/kind :stairs-up
            :intent/to-y 70
            :intent/to-height? true
            :intent/status :active
            :intent/stage :step
            :intent/dir [1 0]
            :intent/cells (stairs/up-cells [5 65 5] [1 0])
            :intent/started-y 65.0
            :intent/started-tick 0}
        w (assoc (standing 64 :stone_pickaxe)
                 :world/blocks {[6 64 5] 1}
                 :time/tick 20
                 :plan/intent up
                 :player/controls {:control/forward? true :control/jump? true})
        tick {:event/kind :tick :event/now 1000}
        replanned (intent/run w up tick)]
    (is (= :plan (get-in replanned [:plan/intent :intent/stage])))
    (is (nil? (get-in replanned [:plan/intent :intent/cells])))
    (is (= {} (:player/controls replanned)))
    (let [next (intent/run replanned (:plan/intent replanned) tick)]
      (is (= (stairs/up-cells [5 64 5] [1 0]) (get-in next [:plan/intent :intent/cells]))))
    (let [jumping (assoc w :player/pos [5.5 65.4 5.5] :player/on-ground? false)]
      (is (= :step (get-in (intent/run jumping up tick) [:plan/intent :intent/stage]))
          "a normal jump above the launch height keeps its step"))))

(deftest a-duck-is-not-cancelled-while-supported-only-by-the-previous-cell
  (let [duck {:intent/kind :stairs-up
              :intent/to-y 70
              :intent/to-height? true
              :intent/status :active
              :intent/stage :step
              :intent/dir [1 0]
              :intent/rise -1
              :intent/cells (stairs/stair-cells [5 64 5] [1 0])
              :intent/started-y 64.0
              :intent/started-tick 0}
        w (assoc (standing 64 :stone_pickaxe)
                 :player/pos [6.05 64.0 5.5]
                 :world/blocks {[6 63 5] blocks/air [7 64 5] 1}
                 :time/tick 20
                 :plan/intent duck)
        next (intent/run w duck {:event/kind :tick :event/now 1000})]
    (is (stairs/up-dry? w (stairs/up-cells [6 64 5] [1 0]))
        "a higher step is visible from the edge")
    (is (= :step (get-in next [:plan/intent :intent/stage])))
    (is (= -1 (get-in next [:plan/intent :intent/rise])))
    (is (= (:intent/cells duck) (get-in next [:plan/intent :intent/cells])))))

(deftest a-floor-is-never-laid-over-water
  ;; sim race 38657453715 world R4/1 (issue #62): a staircase's level steps laid a floor against
  ;; the last one, twenty-six blocks out over a lake, until the cobblestone was spent; every
  ;; stair from the causeway was :boxed and the run walked it to the end of its time
  (let [pond (into {} (for [x (range 6 16) z (range 16)] [[x 63 z] 0]))
        on (fn [below]
             (assoc (standing 64 :cobblestone)
                    :world/chunks
                    {[0 0] (world/column (merge pond below))}))
        cells (stairs/level-cells [5 64 5] [1 0])]
    (is (= [[6 63 5]] (stairs/floor-laying (on {}) [5 64 5] cells)) "over stone: a trench's floor")
    (is (nil? (stairs/floor-laying
               (on (into {} (for [x (range 6 16) z (range 16)] [[x 62 z] blocks/water-base])))
               [5 64 5]
               cells))
        "the air over a pond: a bridge, never laid")))

(deftest a-stair-down-turns-into-the-rock-before-it-lays-a-floor
  ;; race 38842339863 runner 4 (issue #37) bots Clj_r01 and Clj_r08: the mine's stairs down ran
  ;; along a cliff's face and a cave's wall, laying a floor before every stair with the rock a turn
  ;; away, until the cobblestone was spent and every stair was :boxed. Here a cave opens east of
  ;; the feet, under them from x 6 on; south is rock
  (let [cave (into {} (for [x (range 6 16) y (range 58 64) z (range 16)] [[x y z] blocks/air]))
        down {:intent/kind :stairs-down :intent/to-y 58 :intent/status :active}
        tick {:event/kind :tick :event/now 1000 :event/rand 0.5}
        at (fn [blocks]
             (let [w (assoc (standing 64 :stone_pickaxe :cobblestone)
                            :world/chunks {[0 0] (world/column blocks)}
                            :plan/intent down)
                   w (assoc-in w [:player/inventory 1 :count] 64)]
               (:plan/intent (intent/run w down tick))))]
    (is (= [:open [0 1]] ((juxt :intent/stage :intent/dir) (at cave)))
        "the stair goes south into the rock, nothing laid")
    (is (= :lay
           (:intent/stage (at (merge cave
                                     (into {}
                                           (for [x (range 16)
                                                 y (range 58 64)
                                                 z (range 16)
                                                 :when (not= [x z] [5 5])]
                                             [[x y z] blocks/air]))))))
        "with air every way, a floor is laid as before")))

(defn carrying
  "w holding n cobblestone in hotbar slot 8, beside what it holds."
  [w n]
  (assoc-in w [:player/inventory 8] {:item (blocks/items :cobblestone) :count n}))

(deftest a-stair-up-lays-a-holder-beside-its-floor
  (let [feet [5 66 5]
        cells (stairs/up-cells feet [1 0])
        holder [6 65 5]
        step [6 66 5]
        w (-> (standing 66 :stone_pickaxe)
              (carrying 2)
              (assoc :world/blocks {[5 65 5] 1}))
        up {:intent/kind :stairs-up :intent/to-y 70 :intent/status :active}
        planned (:plan/intent
                 (intent/run (assoc w :plan/intent up) up {:event/kind :tick :event/now 0}))]
    (is (= [holder step] (stairs/step-to-lay w cells)))
    (is (= [holder step] (stairs/step-lay w feet cells)))
    (is (= :lay (:intent/stage planned)) "climb rather than duck down from the ledge")
    (is (= [holder step] (:intent/lay planned)))
    (is (= [step] (stairs/step-to-lay (assoc-in w [:world/blocks holder] 1) cells))
        "an existing support needs only the step")
    (is (stairs/up-dry? (update w :world/blocks assoc holder 1 step 1) cells))
    (is (nil? (stairs/step-lay (carrying w 1) feet cells)) "both blocks must be held")
    (is (nil? (stairs/step-to-lay (assoc w :world/chunks {}) cells))
        "an unloaded holder is not a place to build")
    (doseq [id [blocks/water-base blocks/lava-base]]
      (is (nil? (stairs/step-to-lay (assoc-in w [:world/blocks holder] id) cells))
          "the holder never replaces a fluid"))
    (is (nil? (stairs/step-to-lay (assoc-in w [:world/blocks [5 65 5]] blocks/air) cells))
        "no unsupported holder")))

(deftest a-climb-regains-a-higher-step-instead-of-digging-it-away
  (let [feet [5 66 5]
        cells (stairs/up-cells feet [1 0])
        w (assoc (standing 66 :stone_pickaxe) :world/blocks {[5 65 5] 1 [6 66 5] 1})
        up {:intent/kind :stairs-up
            :intent/to-y 70
            :intent/status :active
            :intent/visited #{(last cells)}}
        tick {:event/kind :tick :event/now 0}
        run #(intent/run (assoc w :plan/intent %) % tick)
        duck (assoc up
                    :intent/rise -1
                    :intent/stage :open
                    :intent/cells (stairs/stair-cells feet [1 0])
                    :intent/child (intent/child-dig [6 66 5]))
        replanned (:plan/intent (run duck))]
    (is (= :open (:intent/stage (:plan/intent (run up))))
        "a higher step is progress even when visited before")
    (is (= :plan (:intent/stage replanned)) "an old duck is reconsidered before digging")
    (is (nil? (:intent/child replanned)))
    (is (nil? (:intent/rise replanned)))
    (is (nil? (:intent/cells replanned)))
    (let [ore (assoc-in w [:world/blocks (last cells)] (blocks/default-state :gold_ore))]
      (is (= :open
             (get-in (intent/run (assoc ore :plan/intent duck) duck tick)
                     [:plan/intent :intent/stage]))
          "ore the held tool cannot cut is not a way up"))
    (is (not (stairs/up-dry? (assoc w :plan/boxed-at #{(last cells)}) cells))
        "a pocket known to be boxed is still refused")))

(deftest a-stair-down-lays-floors-only-while-the-filler-reaches-its-depth
  ;; race 38984748832 runner 3 bot Clj_r01 (issue #37): the mine's stairs down broke into a cave at
  ;; y 50 holding 16 cobblestone and 25 dirt, floored thirty-one stairs down through the open cave,
  ;; and at y 19 stood on a one-block dirt bridge with nothing left to lay, every stair refused for
  ;; the air under it, :boxed. Here air lies every way round the feet down to y 58, six stairs
  (let [air (into {}
                  (for [x (range 16)
                        y (range 58 64)
                        z (range 16)
                        :when (not= [x z] [5 5])]
                    [[x y z] blocks/air]))
        down {:intent/kind :stairs-down :intent/to-y 58 :intent/status :active}
        tick {:event/kind :tick :event/now 1000 :event/rand 0.5}
        at (fn [n]
             (let [w (-> (standing 64 :stone_pickaxe)
                         (carrying n)
                         (assoc :world/chunks {[0 0] (world/column air)} :plan/intent down))]
               (:plan/intent (intent/run w down tick))))
        need (+ stairs/lay-reserve 6)]
    (is (= :lay (:intent/stage (at need))) "enough to floor six stairs and keep the reserve: laid")
    (is (not= :lay (:intent/stage (at (dec need)))) "one short: no floor, the staircase turns")
    (is (= 1 (:intent/turns (at (dec need)))))
    (is
     (not= :lay
           (:intent/stage (let [mine (assoc down :intent/floor-to 30)
                                w (-> (standing 64 :stone_pickaxe)
                                      (carrying need)
                                      (assoc :world/chunks {[0 0] (world/column air)}
                                             :plan/intent mine))]
                            (:plan/intent (intent/run w mine tick)))))
     "a mine's staircase is floored only as far as the filler reaches its depth, not this one's to-y")
    (is (stairs/floors-affordable? (carrying (standing 64) 1)
                                   {:intent/kind :stairs-up :intent/to-y 90}
                                   [5 64 5]
                                   [[6 64 5]])
        "a climb's step is not a cave's floor: never held back")))

(deftest a-tunnel-step-the-dig-refuses-is-planned-again
  ;; sim race 38762695024 runner 8 (issue #171, world R1/40): a tunnel for gravel at y 40 ran
  ;; under a gravel bed; the gravel over each step it opened fell into the step round the body,
  ;; water came down onto it, and the dig to clear the step was refused :unsafe: three tunnels
  ;; failed in eight seconds and the run with them
  (let [refused
        {:intent/kind :dig :intent/target [6 16 5] :intent/status :failed :intent/reason :unsafe}
        tunnel {:intent/kind :tunnel
                :intent/dir [1 0]
                :intent/length stone/tunnel-length
                :intent/stage :level
                :intent/level-cells [[6 17 5] [6 16 5]]
                :intent/level-steps 1
                :intent/started-tick 0
                :intent/status :active
                :intent/child refused}
        w (assoc (standing 16 :stone_pickaxe) :plan/intent tunnel :time/tick 10)
        i (:plan/intent (intent/run w tunnel {:event/kind :tick :event/now 1000 :event/rand 0.5}))]
    (is (= :active (:intent/status i)) "the tunnel goes on")
    (is (= :plan (:intent/stage i)) "its next step planned from where the body stands")
    (is (nil? (:intent/child i)))
    (is (nil? (:intent/level-cells i)))
    (testing "a staircase's level step turns instead"
      (let [stairs (assoc tunnel :intent/kind :stairs-up :intent/to-y 70)
            i (:plan/intent (intent/run (assoc w :plan/intent stairs)
                                        stairs
                                        {:event/kind :tick :event/now 1000 :event/rand 0.5}))]
        (is (= :active (:intent/status i)))
        (is (= :plan (:intent/stage i)))
        (is (= 1 (:intent/turns i)))))))

(deftest a-cell-beside-a-gravel-bed-over-water-is-never-dug
  ;; race 38842339863 runner 4 (issue #37) bot Clj_r26: a stair dug beside gravel standing on a
  ;; cave lake dropped the beach into it, the lake over it poured into the pit, and every stair
  ;; from there was refused for the water: :boxed
  (let [gravel (blocks/default-state :gravel)
        at (fn [blocks] (assoc (standing 61) :world/chunks {[0 0] (world/column blocks)}))
        target [5 60 5]]
    (is (intent/exposes-fluid? (at {[6 60 5] gravel [6 59 5] blocks/water-base}) target)
        "gravel beside it, on water")
    (is (intent/exposes-fluid? (at {[6 60 5] gravel [6 59 5] gravel [6 58 5] blocks/water-base})
                               target)
        "a stack of gravel beside it, on water")
    (is (not (intent/exposes-fluid? (at {[6 60 5] gravel}) target)) "gravel on stone stays put")
    (is (not (intent/exposes-fluid? (at {[6 60 5] gravel [6 59 5] blocks/air}) target))
        "gravel over a cave drops into air, and no water follows")
    (is (stairs/level-safe? (at {[6 60 5] gravel}) [[5 61 5] target])
        "beside a bed on stone: a level step")
    (is (not (stairs/level-safe? (at {[6 60 5] gravel [6 59 5] blocks/water-base})
                                 [[5 61 5] target]))
        "beside a bed on water: refused, so the stairs go round")))

(deftest a-stair-up-is-not-cut-from-feet-in-fed-water
  ;; race 38988094276 runner 3 bot Clj_r05 (issue #320): the portal build's stairs up started in
  ;; its own tunnel with flowing water over the floor, fed from beside; from feet in water a jump
  ;; only bobs, and three stairs failed :stair-not-taken. Here water stands in the feet cell and
  ;; the cell west of it feeds it; the step east is solid, the stair's cells open
  (let [cells (stairs/up-cells [5 64 5] [1 0])
        at (fn [blocks]
             (assoc (standing 64 :cobblestone)
                    :world/chunks
                    {[0 0] (world/column (merge {[6 64 5] 1} blocks))}))
        fed (at {[5 64 5] blocks/water-base [4 64 5] blocks/water-base})
        dry (at {})]
    (is (stairs/up-sealed? fed cells) "the stair's own cells border no water")
    (is (= [[4 64 5]] (stairs/wet-feet fed cells)) "what feeds the water under the feet")
    (is (not (stairs/up-dry? fed cells)) "so the stair is not cut as it stands")
    (is (= [[4 64 5]] (stairs/up-lay fed [5 64 5] cells)) "the feeding cell is filled first")
    (is (= [] (stairs/wet-feet dry cells)))
    (is (stairs/up-dry? dry cells) "dry feet: cut as before")))
