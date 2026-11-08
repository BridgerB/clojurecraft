(ns clojurecraft.pearls.cover-test
  "Cover from shooters and swarms."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojurecraft.blaze :as blaze]
            [clojurecraft.blocks :as blocks]
            [clojurecraft.fixtures :as fx]
            [clojurecraft.fortress :as fortress]
            [clojurecraft.fortress-exit :as fexit]
            [clojurecraft.intent :as intent]
            [clojurecraft.make]
            [clojurecraft.memory :as memory]
            [clojurecraft.mob :as mob]
            [clojurecraft.pearls-support :as ps]
            [clojurecraft.pearls.cover :as pcover]
            [clojurecraft.pearls.params :as pparams]
            [clojurecraft.pearls.sense :as psense]
            [clojurecraft.plan :as plan]
            [clojurecraft.world :as world]))

(use-fixtures :once fx/instrumented)

(deftest a-walk-out-of-a-ghasts-sight-ends-centred-in-the-cell
  ;; gym plan run 40905321229 job 6 (issue #263, pearls-12): :walk [2241 83 2705], one cell from the
  ;; feet, planned 45 times in 2.5 s: a walk for :pearls is over within reach of its target, so it
  ;; ended where it began and was planned again
  (let [wall (into {} (for [y (range 64 70)] [[11 y 9] ps/stone]))
        w (-> (ps/standing {7 (ps/mob :ghast [14.5 66.0 8.5])} wall)
              (assoc :player/inventory {0 (ps/item :iron_sword 1)}))
        i (pcover/cover-from w)]
    (is (psense/shot-at? w))
    (is (= [8 64 9] (pcover/hide-cell w (get-in w [:world/entities 7])))
        "out of its line, beside the feet")
    (is (= {:intent/kind :walk :intent/target [8 64 9] :intent/for :cover :intent/wall? true} i))
    (is (not (intent/arrived? w i 1.0)) "from the cell beside it, a walk for :cover is not over")
    (is (intent/arrived? (assoc w :player/pos [8.5 64.0 9.5]) i 0.0) "centred in the cell it is")))

(deftest on-open-ground-a-block-is-laid-between-the-bot-and-a-ghast
  ;; pearl-fight-c job 1 (plan run 41155212096, issue #263): ghast 88 at [2463 101 2235], the bot at
  ;; [2474 99 2233] on open ground, "no cover" and stood in the fire. Offsets kept: 11 west, 2 up, 2 south
  (let [ghast (ps/mob :ghast [-2.5 66.0 10.5])
        held {0 (ps/item :iron_sword 1) 1 (ps/item :cobblestone 64)}
        w (-> (ps/wide (ps/standing {7 ghast}))
              (assoc :player/inventory held))
        wall {:intent/kind :place
              :intent/item :cobblestone
              :intent/cell [7 64 8]
              :intent/for :pearls
              :intent/wall? true}]
    (is (psense/shot-at? w))
    (is (nil? (pcover/hide-cell w (get-in w [:world/entities 7])))
        "open ground: nothing to hide in")
    (is (nil? (pcover/line-cell w (get-in w [:world/entities 7])))
        "and nothing to lay against on the line")
    (is (= wall (pcover/cover-from w)) "so a block at the feet's level on its side")
    (testing "then one over it"
      (let [floor (-> (ps/wide (ps/standing {7 ghast} {[7 64 8] ps/stone}))
                      (assoc :player/inventory held))]
        (is (= [7 65 8] (:intent/cell (pcover/cover-from floor))))))))

(deftest on-open-ground-with-no-block-a-hole-is-dug-under-the-feet
  ;; pearls-travel-d job 6 (plan run 41156746096, issue #263), the fold at 0.22 to 0.4 min: the bot at
  ;; [2463 101 2230] on open ground, ghast 53 at [2454.5 101 2265.5] in sight, shot-at? true, the
  ;; pearl kit holds no block, cover-from nil, and the hunt went on with its stairs; fireballed at 39 s
  (let [ghast (ps/mob :ghast [8.5 64.0 44.5])
        w (-> (ps/wide (ps/standing {7 ghast}))
              (assoc :player/inventory {0 (ps/item :iron_sword 1) 1 (ps/item :iron_pickaxe 1)}))]
    (is (psense/shot-at? w))
    (is (nil? (pcover/hide-cell w (get-in w [:world/entities 7]))))
    (is (= {:intent/kind :dig :intent/target [8 63 8] :intent/for :pearls} (pcover/cover-from w))
        "the cell under the feet: a hole to stand in, the rim between it and the ghast")
    (is (= :dig (:intent/kind (plan/next-intent w ps/row plan/goals))))))

(deftest out-of-the-trail-the-bot-leaves-a-blazes-sight-by-the-nearest-cover-and-never-digs-in-it
  ;; chain-dragon's race f8b28e39d, Clj_r11: a shaft dug straight up under fire, place :no-support
  ;; and pillar :boxed, repeating, with no trail cell out of sight; and the rods bots of race
  ;; 9d15bd69b went from 20 to 3.3 hp digging and pillaring in a blaze's view
  (let [blaze {:entity/type (blocks/entity-types :blaze)
               :entity/pos [14.5 64.0 8.5]
               :entity/seen-at 0
               :entity/health 20.0}
        stone (blocks/default-state :stone)
        floor (into {} (for [x (range 0 16) z (range 0 16)] [[x 63 z] stone]))
        wall (into {} (for [y (range 64 68) z (range 6 11)] [[5 y z] stone]))
        open (-> (ps/standing {9 blaze})
                 (assoc :world/chunks {[0 0] (world/column floor)}))
        walled (assoc open :world/chunks {[0 0] (world/column (merge floor wall))})
        hurt #(assoc % :player/health 6.0)
        digging? #(contains? #{:dig :pillar :place} (:intent/kind %))]
    (testing "a wall within reach: a walk to a cell behind it, trail or none"
      (let [i (pcover/leave-sight walled)]
        (is (= :walk (:intent/kind i)) (pr-str i))
        (is (< (first (:intent/target i)) 5) "west of the wall, out of the blaze's line")
        (is (psense/unseen-from? walled [blaze] (:intent/target i)))
        (is (not (digging? (plan/next-intent (hurt walled) ps/row plan/goals))))))
    (testing "no cover at all and under danger-health: a back-off, never a dig or a pillar"
      (let [i (pcover/leave-sight (hurt open))]
        (is (nil? (pcover/near-cover-cell open)) "open floor: no cell out of sight within reach")
        (is (= {:intent/kind :back-off :intent/target 9 :intent/for :pearls} i))
        (is (not (digging? (plan/next-intent (hurt open) ps/row plan/goals))))))
    (testing "healthy with no cover: the old branches, no back-off"
      (is (nil? (pcover/leave-sight open))))))

(deftest the-blaze-room-is-no-way-to-travel-nor-to-climb-out
  ;; Clj_r11 of race f8b28e39d (replayed on main, probe 41267637866): leave-sight walked the bot out
  ;; of the room and the travel to the forest beyond walked it back in, flip after flip; Clj_r02
  ;; (probe 41267636159): the climb-out from a sealed pocket opened straight up into 23 blazes
  (let [nether-world (fn [pos]
                       (-> (ps/standing {})
                           (assoc :player/dimension "minecraft:the_nether" :player/pos pos)
                           (memory/observe [30 64 8] blaze/spawner)
                           (assoc :fortress/trail
                                  {:trail/target [30 64 8]
                                   :trail/cells [[0 64 8] [5 64 8] [20 64 8]]})))
        outside (nether-world [8.5 64.0 8.5])
        inside (nether-world [30.5 64.0 12.5])
        forest [50 65 8]
        boxes (pcover/room-boxes outside)]
    (is (= 1 (count boxes)) "one room round the spawner")
    (is (pcover/crosses-room? boxes [8 64 8] forest) "the straight line goes through it")
    (let [step (fn [pos]
                 (let [w (assoc outside :player/pos (mapv #(+ 0.5 %) pos))]
                   (fexit/room-waypoint w forest)))
          wp1 (step [8 64 8])
          wp2 (step wp1)
          wp3 (step wp2)]
      (is (not= forest wp1))
      (is (not (pcover/crosses-room? boxes [8 64 8] wp1)) "the first leg is clear")
      (is (not (pcover/crosses-room? boxes wp1 wp2)) "and so is the second")
      (is (= forest wp3) "from the second waypoint the forest is in the clear")
      (is (not (pcover/crosses-room? boxes wp2 forest))))
    (is (= [5 64 8] (fexit/room-waypoint inside forest))
        "from inside: the newest trail cell outside the room, the way it came")
    (is (= forest
           (fexit/room-waypoint (assoc (ps/standing {}) :player/dimension "minecraft:the_nether")
                                forest))
        "no spawner known: straight")
    (let [i (fexit/room-exit inside)]
      (is (= {:intent/kind :tunnel-to
              :intent/target [5 64 8]
              :intent/reach 1.5
              :intent/steps fortress/steps-per-tunnel
              :intent/for :pearls}
             i))
      (is (= :tunnel-to (:intent/kind (plan/next-intent inside ps/row plan/goals)))
          "the hunt tunnels out along the trail, no stairs-up into the room"))
    (is (nil? (fexit/room-exit outside)) "outside the room: nothing to leave")))

(deftest after-rods-the-bot-leaves-the-blaze-room-along-the-plan-trail-and-never-churns-in-place
  ;; real race 41294596356 s1, Clj_r07 and 13 other rod bots (issue #263): held 7 rods, switched to
  ;; :ender-pearls, and died to blazes 1 to 5 blocks from the spawner. :fortress/trail had been cleared
  ;; or held only local cells, so out-of-sight-cell was nil and room-exit returned nil or was shadowed
  ;; by leave-sight's near-cover-cell. The walk was planned with :intent/for :pearls; not being in
  ;; standing-walks, arrived? finished the walk on tick 0 when d <= 4.0 blocks, replanning every 50 ms
  ;; (churn x150 to x600) until the bot died standing in blaze fire.
  (let [blaze {:entity/type (blocks/entity-types :blaze)
               :entity/pos [892.5 60.0 -204.5]
               :entity/seen-at 0
               :entity/health 20.0}
        spawner-pos [889 60 -206]
        bot-pos [890.5 60.0 -203.9]
        exit-cell [902 63 -198]
        w (-> (ps/standing {9 blaze})
              (assoc :player/dimension "minecraft:the_nether" :player/pos bot-pos)
              (memory/observe spawner-pos blaze/spawner)
              (assoc :fortress/trail {:trail/target spawner-pos :trail/cells []})
              (assoc :plan/trail
                     [[976 48 -176] [958 49 -190] [926 63 -190] exit-cell [897 61 -204]
                      [890 60 -204]]))
        boxes (pcover/room-boxes w)]
    (is (pcover/in-room? boxes [890 60 -204]) "bot feet are inside the blaze room")
    (is (not (pcover/in-room? boxes exit-cell))
        "exit cell on :plan/trail is outside the blaze room")
    (is (= exit-cell (fexit/room-waypoint w [2000 60 2000]))
        "room-waypoint navigates toward the trail exit cell, not directly through the spawner room")
    (let [i (fexit/room-exit w)]
      (is (= :tunnel-to (:intent/kind i)))
      (is (= exit-cell (:intent/target i)))
      (is (= 1.5 (:intent/reach i))
          "reach 1.5 prevents tunnel-to from completing 6 blocks away on tick 0")
      (is (= fortress/steps-per-tunnel (:intent/steps i))
          "carries steps-per-tunnel to bound steps before planner looks again"))
    (testing
      "walk to cover along the trail uses :for :cover so arrived? requires standing in the cell"
      (when-let [i (pcover/leave-sight w)]
        (is (= :cover (:intent/for i))
            "must be :cover to ensure standing-walks centres in the cell")))))

(def line-floor
  "Stone at y 64 along x 9 to 13 at z 8: cells over it have a solid neighbour to lay against."
  (into {} (for [x (range 9 14)] [[x 64 8] ps/stone])))

(deftest a-block-is-not-laid-in-a-cell-a-mob-stands-in
  ;; the server refuses a block into a mob: four of six real fortress deaths placed again and
  ;; again into the cell a wither skeleton stood in while hp ran out
  (let [ghast (ps/mob :ghast [14.5 64.0 8.5])
        open (ps/standing {7 ghast} line-floor)
        cell (pcover/line-cell open ghast)
        zombie (assoc (ps/mob :zombie [0 0 0])
                      :entity/pos
                      [(+ (first cell) 0.5) (second cell) (+ (nth cell 2) 0.5)])
        crowded (assoc open :world/entities {7 ghast 8 zombie})
        next-cell (pcover/line-cell crowded ghast)]
    (is (some? cell) "a cell on the line to lay against")
    (is (not= cell next-cell) "not the cell the mob stands in")
    (is (not (mob/occupied? (:world/entities crowded) next-cell)))))

(deftest cover-lays-no-block-again-after-a-place-failed
  (let [w (-> (ps/standing {7 (ps/mob :ghast [14.5 64.0 8.5])} line-floor)
              (assoc :time/now 1000)
              (assoc-in [:player/inventory 3] (ps/item :cobblestone 64)))
        failed (memory/remember-intention w
                                          {:intent/id 900 :intent/kind :place :intent/wall? true}
                                          :failed)]
    (is (pcover/placing? w) "nothing failed: blocks may be laid")
    (is (not (pcover/placing? failed)) "a place just failed: none now")
    (is (not= :place (:intent/kind (pcover/cover-from failed))) "the next rung answers")
    (is (pcover/placing? (assoc failed :time/now (+ 1000 pparams/cover-place-hold-ms 1)))
        "and after the hold, blocks may be laid again")
    (let [begun (fn [n]
                  (reduce #(-> %1
                               (assoc :time/now (+ 1000 (* 10 %2)))
                               (memory/remember-intention {:intent/id (+ 950 %2)
                                                           :intent/kind :place}
                                                          :started))
                          w
                          (range n)))]
      (is (pcover/placing? (begun (dec pparams/cover-place-max))) "a wall of two levels is laid")
      (is (not (pcover/placing? (begun pparams/cover-place-max)))
          "one more at the same spot is not"))))

(deftest a-cell-only-a-table-supports-is-no-place-for-cover
  ;; place/support refuses a block a click opens: a cover cell it cannot click against fails
  ;; :no-support, so cover does not choose it
  (let [table (blocks/default-state :crafting_table)
        ghast (ps/mob :ghast [14.5 64.0 8.5])
        tables (ps/standing {7 ghast} (into {} (for [x (range 9 14)] [[x 64 8] table])))]
    (is (nil? (pcover/line-cell tables ghast)))
    (is (some? (pcover/line-cell (ps/standing {7 ghast} line-floor) ghast)) "stone it can click")))

(deftest a-cell-out-of-sight-is-never-the-one-the-bot-stands-at
  ;; real-18 (24e4a24) Clj_r11: the walk to the cell out of a blaze's sight was done as soon as it
  ;; began (the bot stood at it, and was shot there) and was planned again every half second, the
  ;; cover-from-swarm pillar between: the bot never moved. The cell it stands
  ;; at is no way out of sight.
  (let [blaze {:entity/type (blocks/entity-types :blaze)
               :entity/pos [14.5 64.0 8.5]
               :entity/seen-at 0
               :entity/health 20.0}
        stone (blocks/default-state :stone)
        floor (into {} (for [x (range 0 16) z (range 0 16)] [[x 63 z] stone]))
        wall (into {} (for [y (range 64 68) z (range 6 11)] [[5 y z] stone]))
        behind (-> (ps/standing {9 blaze})
                   (assoc :world/chunks {[0 0] (world/column (merge floor wall))}
                          :player/pos [3.5 64.0 8.5]))
        feet [3 64 8]]
    (is (psense/unseen-from? behind [blaze] feet) "the bot stands out of the blaze's sight")
    (is (not= feet (pcover/near-cover-cell behind)) "so the cell it stands at is not the answer")
    (is (not (pcover/at-feet? behind (pcover/near-cover-cell behind))))
    (is (nil? (pcover/out-of-sight-cell (assoc behind :plan/trail [feet])))
        "a trail whose newest cell is the feet's has no way out")
    (is (= [0 64 8] (pcover/out-of-sight-cell (assoc behind :plan/trail [[0 64 8] feet])))
        "an older cell, farther off, is")))
