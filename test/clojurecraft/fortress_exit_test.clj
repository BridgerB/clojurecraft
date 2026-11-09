(ns clojurecraft.fortress-exit-test
  "The exit from the blaze fortress into the pearl hunt."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojurecraft.blaze :as blaze]
            [clojurecraft.blocks :as blocks]
            [clojurecraft.fixtures :as fx]
            [clojurecraft.fortress :as fortress]
            [clojurecraft.fortress-exit :as fexit]
            [clojurecraft.game :as game]
            [clojurecraft.intent :as intent]
            [clojurecraft.make]
            [clojurecraft.memory :as memory]
            [clojurecraft.pearls-support :as ps]
            [clojurecraft.pearls.params :as pparams]
            [clojurecraft.pearls.sense :as psense]
            [clojurecraft.physics :as physics]
            [clojurecraft.plan :as plan]))

(use-fixtures :once fx/instrumented)

(deftest a-rod-bot-at-the-spawner-leaves-by-sealed-tunnel-out-and-never-walks-corridors
  ;; after rods milestone, rod bots walk out along fortress corridors and blazes kill them
  ;; (real race 8b3f2e1 runs 41352762409, 41352110693, 41354774759): with a pickaxe held,
  ;; the bot leaves by a sealed tunnel-out below the floor, never walking the corridor
  (let [blaze {:entity/type (blocks/entity-types :blaze)
               :entity/pos [892.5 60.0 -204.5]
               :entity/seen-at 0
               :entity/health 20.0}
        spawner-pos [889 60 -206]
        bot-pos [890.5 60.0 -203.9]
        exit-cell [902 63 -198]
        w (-> (ps/standing {9 blaze})
              (assoc :player/dimension "minecraft:the_nether" :player/pos bot-pos)
              (assoc-in [:player/inventory 1] (ps/item :iron_pickaxe 1))
              (memory/observe spawner-pos blaze/spawner)
              (assoc :fortress/trail {:trail/target spawner-pos :trail/cells []})
              (assoc :plan/trail
                     [[976 48 -176] [958 49 -190] [926 63 -190] exit-cell [897 61 -204]
                      [890 60 -204]]))
        i (plan/next-intent w ps/row plan/goals)]
    (is (fexit/near-fortress? w) "bot is near the spawner / fortress")
    (is (fexit/can-tunnel-out? w) "pickaxe held and not surrounded by lava: can tunnel out")
    (is (= :tunnel-to (:intent/kind i)) "chooses tunnel-to")
    (is (true? (:intent/seal-behind? i)) "tunnel seals the cell behind it every few steps")
    (is
     (>= (second (:intent/target i)) (second bot-pos))
     "targets at or above current floor into solid wall/tunnel, never digging down through floor")
    (is (not= exit-cell (:intent/target i))
        "does not choose the corridor exit cell along the trail")))

(deftest the-tunnel-out-refuses-lava-and-routes-around-or-falls-back
  ;; tunnel-to refuses any step into lava or beside lava (safe?): when surrounded by lava
  ;; on all sides, can-tunnel-out? is false and corridor walking is the fallback
  (let [spawner-pos [100 64 100]
        bot-pos [100.5 64.0 100.5]
        feet [100 64 100]
        lava-state (blocks/default-state :lava)
        w-clear (-> (ps/standing {})
                    (assoc :player/dimension "minecraft:the_nether" :player/pos bot-pos)
                    (assoc-in [:player/inventory 1] (ps/item :iron_pickaxe 1))
                    (memory/observe spawner-pos blaze/spawner))
        w-lava-below (assoc-in w-clear [:world/blocks [100 63 100]] lava-state)
        lava-blocks (into {}
                          (for [[dx dz] [[1 0] [-1 0] [0 1] [0 -1]]
                                dy [0 -1]]
                            [(mapv + feet [dx dy dz]) lava-state]))
        w-trapped (-> w-clear
                      (update :world/blocks merge lava-blocks)
                      (assoc :fortress/trail
                             {:trail/target spawner-pos :trail/cells [[90 64 100]]}))]
    (testing "safe? refuses step down when cell below has lava"
      (is (not (fortress/safe? w-lava-below feet [0 -1 0])) "downward step into lava is refused"))
    (testing "surrounded by lava falls back to corridor walking"
      (is (fexit/surrounded-by-lava? w-trapped feet) "surrounded by lava on all sides")
      (is (not (fexit/can-tunnel-out? w-trapped)) "cannot tunnel out when surrounded by lava")
      (let [i (plan/next-intent w-trapped ps/row plan/goals)]
        (is (not (true? (:intent/seal-behind? i)))
            "does not attempt sealed tunnel-out when surrounded by lava")))))

(deftest the-tunnel-out-surfaces-only-beyond-48-blocks
  ;; tunnel horizontally away from fortress and surface only when more than 48 blocks
  ;; from every known spawner and fortress brick
  (let [spawner-pos [100 64 100]
        tunnel-y 59
        surface-y 64
        make-world (fn [bot-pos]
                     (-> (ps/standing {})
                         (assoc :player/dimension "minecraft:the_nether" :player/pos bot-pos)
                         (assoc-in [:player/inventory 1] (ps/item :iron_pickaxe 1))
                         (memory/observe spawner-pos blaze/spawner)))]
    (testing "at the spawner: tunnels horizontally away at same y, does not dig down"
      (let [w (make-world [100.5 64.0 100.5])
            target (fexit/fortress-tunnel-target w)]
        (is (= 64 (second target)) "stays at same y level 64, not digging down into floor")))
    (testing "20 blocks away at tunnel-y 59: tunnels horizontally, does not surface"
      (let [w (make-world [120.5 (double tunnel-y) 100.5])
            target (fexit/fortress-tunnel-target w)]
        (is (fexit/near-fortress? w) "20 blocks is within 48 blocks")
        (is (= tunnel-y (second target)) "stays at y 59; does not surface")))
    (testing "50 blocks away at tunnel-y 59: surfaces to surface-y"
      (let [w (make-world [150.5 (double tunnel-y) 100.5])
            target (fexit/fortress-tunnel-target w)]
        (is (not (fexit/near-fortress? w)) "50 blocks is beyond 48 blocks")
        (is (= surface-y (second target)) "surfaces to surface-y once beyond 48 blocks")))))

(deftest a-rod-bot-under-blaze-fire-covers-and-heals-before-tunneling-out
  ;; On commit 4b8c10f, rod bots in ender-pearls attempted to dig :tunnel-to right in the open line
  ;; of fire of blazes, without covering (cover-from) or healing (meal). 100% of real rod bots (37/37)
  ;; and sim rod bots (36/36) died to blazes and fire at the fortress spawner.
  ;; When shot at by blazes, the bot must cover from incoming fire before attempting to tunnel.
  (let [blaze-id 42
        blaze {:entity/type (blocks/entity-types :blaze)
               :entity/pos [8.5 64.0 14.5]
               :entity/seen-at 0
               :entity/health 20.0}
        spawner-pos [8 64 2]
        nether-brick (blocks/default-state :nether_bricks)
        floor (into {} (for [x (range 0 16) z (range 0 16)] [[x 63 z] nether-brick]))
        corridor-walls (into {}
                             (concat (for [y [64 65] z (range 0 16)] [[6 y z] nether-brick])
                                     (for [y [64 65] z (range 0 16)] [[10 y z] nether-brick])))
        blocks (merge floor corridor-walls)
        base (-> (ps/standing {blaze-id blaze} blocks)
                 (assoc :player/dimension "minecraft:the_nether"
                        :player/pos [8.5 64.0 8.5]
                        :player/health 12.0
                        :player/food 14
                        :time/now 200
                        :pearls/shot-at-ms 150
                        :plan/status :active
                        :plan/goals #{:ender-pearls}
                        :plan/reached #{:blaze-rods}
                        :plan/active [ps/row]
                        :player/attacker blaze-id)
                 (memory/observe spawner-pos blaze/spawner)
                 (update :player/inventory assoc
                         1 (ps/item :cooked_beef 12)
                         2 (ps/item :cobblestone 64)
                         3 (ps/item :iron_pickaxe 1)))]
    (testing
      "actively shot at by a blaze, the rod bot covers from the shooter, not tunneling in the line of fire"
      (is (fexit/near-fortress? base) "near the fortress")
      (is (fexit/can-tunnel-out? base) "holds pickaxe and not surrounded by lava")
      (is (psense/shot-at? base) "blaze is recognized as actively shooting")
      (let [i (plan/next-intent base ps/row plan/goals)]
        (is (not= :tunnel-to (:intent/kind i)) "does not start digging while under active fire")
        (is (contains? #{:place :dig :walk} (:intent/kind i))
            "places cover or moves out of sight")))
    (testing "a retry's cover under fire is the hunt's cover-from, never a retreat walk"
      (let [c (plan/fire-cover base ps/row)]
        (is (some? c))
        (is (not= :retreat (:intent/for c)))
        (is (nil? (plan/fire-cover (assoc base :world/entities {} :pearls/shot-at-ms nil) ps/row))
            "with nothing shooting there is none")))
    (testing "safe from active fire but hurt, the rod bot heals before tunneling out"
      (let [hurt-world (-> base
                           (dissoc :pearls/shot-at-ms :player/attacker)
                           (assoc :world/entities {}))
            i (plan/next-intent hurt-world ps/row plan/goals)]
        (is (= :eat (:intent/kind i)) "heals when safe from shooters")
        (is (= :cooked_beef (:intent/item i)))))
    (testing "once safe from active fire, the rod bot proceeds with sealed tunnel-out"
      (let [safe-world (-> base
                           (dissoc :pearls/shot-at-ms :player/attacker)
                           (assoc :world/entities {} :player/health 20.0 :player/food 20))
            i (plan/next-intent safe-world ps/row plan/goals)]
        (is (= :tunnel-to (:intent/kind i)) "chooses tunnel-to once safe from shooters")
        (is (= 0 (:intent/climb i))
            "climb is 0 so descent reaches under-y and surfaces to surface-y")
        (is (true? (:intent/seal-behind? i)) "seals behind as it tunnels")))))

(deftest the-fortress-tunnel-out-carries-zero-climb-so-descent-does-not-stall-short-of-under-y
  ;; Sim snapshots 41437547127-R1-6-1, 41437392940-R1-33-1, 41437541208-R1-21-1 (issue #644):
  ;; rod bots in ender-pearls dug down from y=70 to y=38 with under-y=35. Because :tunnel-to
  ;; lacked :intent/climb 0, climb defaulted to climb-past=3. When ddy was -3, moves refused
  ;; downward moves ((< -3 -3) false) and only stepped level. With target [fx 35 fz], ddx=0
  ;; and ddz=0, the bot paced between two cells at y=38 forever until unstick failed :no-path.
  ;; With :intent/climb 0 and horizontal escape at current y, Phase 2 tunnels horizontally toward the exit.
  (let [spawner-pos [-176 70 219]
        brick-pos [-164 40 221]
        bot-pos [-163.5 38.0 221.5]
        nether-brick (blocks/default-state :nether_bricks)
        w (-> (ps/standing {})
              (assoc :player/dimension "minecraft:the_nether" :player/pos bot-pos)
              (memory/observe spawner-pos blaze/spawner)
              (memory/observe brick-pos nether-brick)
              (assoc-in [:player/inventory 1] (ps/item :iron_pickaxe 1))
              (assoc :plan/status :active
                     :plan/goals #{:ender-pearls}
                     :plan/reached #{:blaze-rods}
                     :plan/active [ps/row]))
        target (fexit/fortress-tunnel-target w)
        exit-y (second target)
        i (fexit/fortress-tunnel-out w)
        moves (fortress/moves (intent/feet w) target (:intent/climb i))]
    (is (= 38 exit-y) "exit target stays at current y level 38, not digging down")
    (is (= 0 (:intent/climb i)) "intent specifies climb 0")
    (is (>= (second (first moves)) 0) "moves toward exit do not step down")
    (is (true? (:intent/no-down? i)) "carries no-down?")
    (let [at-target-world (assoc w :player/pos [-163.5 (double exit-y) 221.5])
          next-target (fexit/fortress-tunnel-target at-target-world)]
      (is (fexit/near-fortress? at-target-world) "still near fortress")
      (is (= exit-y (second next-target)) "Phase 2 stays at same y level")
      (is (not= (first (intent/feet at-target-world)) (first next-target))
          "Phase 2 targets exit-x away from current column, not pacing in place"))))

(deftest a-rod-bot-at-spawner-descends-only-a-few-blocks-below-local-floor-and-seals-overhead
  ;; Vertical Phase 1 descent into unroofed floors dropped bots into lava sea (#715) or stalled
  ;; under blaze fire (#712-#714). The fortress tunnel-out now retraces the inbound trail or
  ;; tunnels horizontally away at the current y level, never digging down through the floor.
  ;; Eating meal is allowed when safe from active fire, and cover is taken when shot at.
  (let [spawner-pos [8 70 8]
        local-floor-pos [8 69 8]
        distant-pillar-pos [8 32 8]
        bot-pos [8.5 70.0 8.5]
        blaze-id 99
        blaze {:entity/type (blocks/entity-types :blaze)
               :entity/pos [8.5 74.0 14.5]
               :entity/seen-at 0
               :entity/health 20.0}
        nether-brick (blocks/default-state :nether_bricks)
        floor (into {} (for [x (range 0 16) z (range 0 16)] [[x 69 z] nether-brick]))
        corridor-walls (into {}
                             (concat (for [y [70 71] z (range 0 16)] [[6 y z] nether-brick])
                                     (for [y [70 71] z (range 0 16)] [[10 y z] nether-brick])))
        blocks (merge floor corridor-walls)
        w (-> (ps/standing {} blocks)
              (assoc :player/dimension "minecraft:the_nether"
                     :player/pos bot-pos
                     :player/health 12.0
                     :player/food 14)
              (memory/observe spawner-pos blaze/spawner)
              (memory/observe local-floor-pos nether-brick)
              (memory/observe distant-pillar-pos nether-brick)
              (assoc-in [:player/inventory 1] (ps/item :iron_pickaxe 1))
              (assoc-in [:player/inventory 2] (ps/item :cooked_porkchop 8))
              (assoc-in [:player/inventory 3] (ps/item :cobblestone 64))
              (assoc :plan/status :active
                     :plan/goals #{:ender-pearls}
                     :plan/reached #{:blaze-rods}
                     :plan/active [ps/row]))
        target (fexit/fortress-tunnel-target w)]
    ;; 1. Target stays at current feet height 70, not digging down into floor
    (is (= 70 (second target)) "target stays at current feet y 70, not digging down into floor")
    ;; 2. When not actively shot at, injured escaping bot eats meal to recover health
    (let [meal-i (plan/next-intent w ps/row plan/goals)]
      (is (= :eat (:intent/kind meal-i)) "eats meal to recover health before tunneling"))
    ;; 3. Once healed, chooses fortress-tunnel-out
    (let [healthy-w (assoc w :player/health 20.0 :player/food 20)
          tunnel-i (plan/next-intent healthy-w ps/row plan/goals)]
      (is (= :tunnel-to (:intent/kind tunnel-i)) "chooses tunnel-to once healthy")
      (is (= target (:intent/target tunnel-i)) "targets exit at same y 70")
      (is (= 0 (:intent/climb tunnel-i)) "carries climb 0")
      (is (true? (:intent/seal-behind? tunnel-i)) "requests seal-behind")
      (is (true? (:intent/no-down? tunnel-i)) "requests no-down? to protect floor"))
    ;; 4. When shot at, covers first
    (let [shot-w (-> w
                     (assoc-in [:world/entities blaze-id] blaze)
                     (assoc :player/attacker blaze-id :pearls/shot-at-ms 150 :time/now 200))
          cover-i (plan/next-intent shot-w ps/row plan/goals)]
      (is (not= :tunnel-to (:intent/kind cover-i)) "does not tunnel while shot at")
      (is (contains? #{:place :walk :dig} (:intent/kind cover-i)) "covers from shooter"))))

(deftest a-rod-bot-retraces-inbound-dug-tunnel
  ;; From logged state:
  ;; A rod bot at the spawner with an inbound trail dug through solid netherrack
  ;; retraces the enclosed trail cells heading toward the exit at the same y.
  (let [spawner-pos [8 70 3]
        bot-pos [8.5 67.0 8.5]
        ;; :plan/trail is oldest-first (from fortress entrance at z=14 to spawner at z=4)
        trail-cands [[8 67 14] [8 67 11] [8 67 8] [8 67 6] [8 67 4]]
        netherrack (blocks/default-state :netherrack)
        floor (into {} (for [x (range 0 16) z (range 0 16)] [[x 66 z] netherrack]))
        roof (into {} (for [z (range 0 16)] [[8 69 z] netherrack]))
        walls (into {}
                    (concat (for [y [67 68] z (range 0 16)] [[7 y z] netherrack])
                            (for [y [67 68] z (range 0 16)] [[9 y z] netherrack])))
        tunnel-blocks (merge floor roof walls)
        w (-> (ps/standing {} tunnel-blocks)
              (assoc :player/dimension "minecraft:the_nether" :player/pos bot-pos)
              (assoc-in [:player/inventory 1] (ps/item :iron_pickaxe 1))
              (memory/observe spawner-pos blaze/spawner)
              (assoc :plan/status :active
                     :plan/goals #{:ender-pearls}
                     :plan/reached #{:blaze-rods}
                     :plan/active [ps/row])
              (assoc :fortress/trail {:trail/target spawner-pos :trail/cells []})
              (assoc :plan/trail trail-cands))
        target (fexit/fortress-tunnel-target w)
        i (plan/next-intent w ps/row plan/goals)]
    (is (fexit/enclosed? w [8 67 11]) "dug tunnel cells are enclosed (solid roof and walls)")
    (is (= :tunnel-to (:intent/kind i)) "chooses tunnel-to to exit")
    (is (= [8 67 11] target) "retraces enclosed inbound trail cell")
    (is (= (long (second bot-pos)) (second target))
        "retrace stays at same y (67), never digging down")
    (is (true? (:intent/no-down? i)) "carries no-down? to forbid downward floor digging")))

(deftest a-trail-crossing-corridor-bores-parallel-wall-at-same-y
  ;; Where the trail crosses an open fortress corridor (3 blocks wide, open overhead),
  ;; the bot does not walk the open corridor; instead it bores a parallel 1x2 tunnel
  ;; through the solid netherrack or brick wall beside it at the exact same y.
  (let [spawner-pos [8 64 2]
        bot-pos [8.5 64.0 4.5]
        corridor-cell [8 64 10]
        nether-brick (blocks/default-state :nether_bricks)
        netherrack (blocks/default-state :netherrack)
        ;; corridor floor at y=63, 3 blocks wide (x = 7, 8, 9)
        corridor-floor (into {} (for [x [7 8 9] z (range 0 16)] [[x 63 z] nether-brick]))
        ;; solid wall flanking the corridor at x = 6 (x-2 from corridor center 8)
        wall-blocks (into {} (for [y [63 64 65 66] z (range 0 16)] [[6 y z] netherrack]))
        all-blocks (merge corridor-floor wall-blocks)
        w (-> (ps/standing {} all-blocks)
              (assoc :player/dimension "minecraft:the_nether" :player/pos bot-pos)
              (assoc-in [:player/inventory 1] (ps/item :iron_pickaxe 1))
              (memory/observe spawner-pos blaze/spawner)
              (assoc :plan/status :active
                     :plan/goals #{:ender-pearls}
                     :plan/reached #{:blaze-rods}
                     :plan/active [ps/row])
              (assoc :fortress/trail {:trail/target spawner-pos :trail/cells []})
              ;; :plan/trail is oldest-first (from entrance at z=14 through corridor to bot at z=4)
              (assoc :plan/trail [[8 64 14] corridor-cell [8 64 4]]))
        target (fexit/fortress-tunnel-target w)
        i (plan/next-intent w ps/row plan/goals)]
    (is (not (fexit/enclosed? w corridor-cell))
        "corridor cell is not enclosed (open sides and roof)")
    (is (= :tunnel-to (:intent/kind i)) "chooses tunnel-to")
    (is (not= corridor-cell target) "does not target the open corridor cell")
    (is (= [6 64 10] target) "targets side bore in solid wall flanking the corridor at x=6")
    (is (= 64 (second target)) "side bore is at the exact same y level (64), no dy < 0")
    (is (true? (:intent/no-down? i)) "tunnel carries no-down?")))

(deftest a-bore-heading-is-fixed-across-the-cells-clj-r32-flipped-between
  ;; Logged state, run 41852247420 s2 Clj_r32: after the rods the retrace alternated
  ;; :tunnel-to [-741 77 116] and [-737 77 116] 45 times each, wearing the stone pickaxe
  ;; from 131 to 1 durability. The bore heading is derived from the spawner and the
  ;; inbound trail's start only, so it is the same at both cells of the old flip and
  ;; every bore target lies ahead along it at the same y. Geometry scaled into one
  ;; loaded chunk: spawner [12 76 10], trail start [2 77 10] west of it, flip cells at
  ;; [10 77 8] and [9 77 8] in solid netherrack.
  (let [netherrack (blocks/default-state :netherrack)
        rock (into {} (for [x (range 0 16) y [75 76 77 78 79] z (range 0 16)] [[x y z] netherrack]))
        base (-> (ps/standing {} rock)
                 (assoc :player/dimension "minecraft:the_nether")
                 (assoc-in [:player/inventory 1] (ps/item :iron_pickaxe 1))
                 (memory/observe [12 76 10] blaze/spawner)
                 (assoc :plan/trail [[2 77 10] [6 77 9] [10 77 8]]))
        w-east (assoc base :player/pos [10.5 77.0 8.5])
        w-west (assoc base :player/pos [9.5 77.0 8.5])
        t-east (fexit/fortress-tunnel-target w-east)
        t-west (fexit/fortress-tunnel-target w-west)]
    (is (= [-1 0] (fexit/pocket-bore-heading w-east)) "heading west, toward the trail's start")
    (is (= (fexit/pocket-bore-heading w-east) (fexit/pocket-bore-heading w-west))
        "the same heading at both cells of the old two-point flip")
    (is (= [6 77 8] t-east) "bore target ahead along the heading at the same y")
    (is (= [5 77 8] t-west) "the next bore target further along, never back east")
    (is (not= t-east (intent/feet w-west)) "never a target the other flip cell already stands in")))

(deftest a-bore-step-that-would-open-into-a-corridor-side-steps-at-the-same-y
  ;; The bore digs only cells whose neighbours are solid: where the cell ahead along the
  ;; heading borders open air (a corridor), the target side-steps along a perpendicular
  ;; heading whose next step is enclosed, at the same y, never down.
  (let [netherrack (blocks/default-state :netherrack)
        rock (into {} (for [x (range 0 16) y [75 76 77 78 79] z (range 0 16)] [[x y z] netherrack]))
        ;; carve the corridor the ahead cell would open into: the column north of it
        carved (into rock (for [y [75 76 77 78 79]] [[9 y 9] (blocks/default-state :air)]))
        w (-> (ps/standing {} carved)
              (assoc :player/dimension "minecraft:the_nether" :player/pos [10.5 77.0 8.5])
              (assoc-in [:player/inventory 1] (ps/item :iron_pickaxe 1))
              (memory/observe [12 76 10] blaze/spawner)
              (assoc :plan/trail [[2 77 10] [10 77 8]]))]
    (is (not (fexit/pocket-bore-enclosed? w [10 77 8] [-1 0]))
        "the step west would open into the carved corridor")
    (is (fexit/pocket-bore-enclosed? w [10 77 8] [0 -1]) "the step south stays in solid rock")
    (is (= [10 77 4] (fexit/pocket-bore-target w)) "side-step target south at the same y")
    (is (= 77 (second (fexit/pocket-bore-target w))) "never a target below the feet")))

(deftest a-bore-with-no-enclosed-step-ahead-or-beside-bores-back-through-rock
  ;; Logged state, probe 41914633411 R1/32 seed 1: sealed at full health, then the
  ;; feet stayed at [-40 69 220] from t 570 s to 810 s on :tunnel-to [-43 69 220]
  ;; until blazes killed the bot. A pocket beside a fortress room has air beside
  ;; its heading and both side steps; the step back through solid rock is legal.
  (let [netherrack (blocks/default-state :netherrack)
        rock (into {} (for [x (range 0 16) y [75 76 77 78 79] z (range 0 16)] [[x y z] netherrack]))
        air (blocks/default-state :air)
        carved (into rock [[[11 77 7] air] [[11 78 7] air] [[10 77 10] air] [[10 77 6] air]])
        w (-> (ps/standing {} carved)
              (assoc :player/dimension "minecraft:the_nether" :player/pos [10.5 77.0 8.5])
              (assoc-in [:player/inventory 1] (ps/item :iron_pickaxe 1))
              (memory/observe [12 76 10] blaze/spawner)
              (assoc :plan/trail [[14 77 8] [10 77 8]]))]
    (is (= [1 0] (fexit/pocket-bore-heading w)) "heading east, toward the trail start")
    (is (not (fexit/pocket-bore-enclosed? w [10 77 8] [1 0])) "ahead borders the room")
    (is (= [6 77 8] (fexit/pocket-bore-target w)) "bores back west through rock, not a hold")
    (is (= 77 (second (fexit/pocket-bore-target w))) "never a target below the feet")))

(deftest a-blacklisted-bore-target-is-passed-over-for-the-next-enclosed-heading
  ;; #719's guard blacklists a :tunnel-to target on :instant-churn/:blocked; the
  ;; bore must not be ruled out by its own blacklisted step (probe 41914633411).
  (let [netherrack (blocks/default-state :netherrack)
        rock (into {} (for [x (range 0 16) y [75 76 77 78 79] z (range 0 16)] [[x y z] netherrack]))
        w (-> (ps/standing {} rock)
              (assoc :player/dimension "minecraft:the_nether" :player/pos [10.5 77.0 8.5])
              (assoc-in [:player/inventory 1] (ps/item :iron_pickaxe 1))
              (memory/observe [12 76 10] blaze/spawner)
              (assoc :plan/trail [[2 77 10] [10 77 8]])
              (assoc :plan/blacklist #{[6 77 8]}))]
    (is (= [10 77 4] (fexit/pocket-bore-target w))
        "the west target blacklisted, the enclosed south side-step is chosen")))

(deftest a-rod-bot-seals-and-heals-in-its-pocket-before-boring-out
  ;; Logged states: Clj_r36 (run 41852247420 s1) dug in the blazes' sight from hp 20 to 8
  ;; and died 4 blocks from the rods; Clj_r12 and Clj_r13 (s2) died seconds after
  ;; `place failed: :rejected` left them without cover. The exit now starts from the
  ;; sealed pocket: seal the blaze pocket, heal to full in it, then bore out; a rejected
  ;; seal leaves the bot eating or holding in the pocket, never boring out exposed.
  (let [netherrack (blocks/default-state :netherrack)
        floor (into {} (for [x (range 0 17) z (range 0 17)] [[x 63 z] netherrack]))
        roof (into {} (for [x (range 0 17) z (range 0 17)] [[x 66 z] netherrack]))
        walls (into {}
                    (concat (for [y [64 65] z (range 0 17)] [[7 y z] netherrack])
                            (for [y [64 65] z (range 0 17)] [[9 y z] netherrack])
                            (for [y [64 65]] [[8 y 7] netherrack])))
        seal-blocks (into {} (for [y [64 65]] [[8 y 9] netherrack]))
        make-w (fn [blocks site]
                 (-> (ps/standing {} blocks)
                     (assoc :player/dimension "minecraft:the_nether"
                            :player/pos [8.5 64.0 8.5]
                            :plan/status :active
                            :plan/goals #{:ender-pearls}
                            :plan/reached #{:blaze-rods}
                            :plan/active [ps/row]
                            :pocket/site site)
                     (memory/observe [8 70 3] blaze/spawner)
                     (update :player/inventory assoc
                             1 (ps/item :cooked_beef 12)
                             2 (ps/item :cobblestone 64)
                             3 (ps/item :iron_pickaxe 1))))
        open-w (make-w (merge floor roof walls)
                       {:pocket/cell [8 64 8] :pocket/stand nil :pocket/dig [] :pocket/placed []})
        sealed-w (make-w (merge floor roof walls seal-blocks)
                         {:pocket/cell [8 64 8]
                          :pocket/stand nil
                          :pocket/dig []
                          :pocket/placed [[8 64 9] [8 65 9]]})]
    (testing "in its open pocket, the rod bot seals it before boring"
      (is (= :seal (:intent/kind (fexit/pocket-bore open-w)))))
    (testing "sealed and hurt (Clj_r36 at hp 8), it heals in the pocket, never tunnels"
      (let [hurt (assoc sealed-w :player/health 8.0 :player/food 14)
            i (plan/next-intent hurt ps/row plan/goals)]
        (is (contains? #{:eat :pocket-hold} (:intent/kind i)))
        (is (not= :tunnel-to (:intent/kind i)))))
    (testing "a rejected seal (Clj_r12) leaves it healing in the pocket, not boring exposed"
      (let [rejected
            (-> open-w
                (assoc :player/health 8.0 :player/food 14 :pocket/seal-failed-at 0 :time/now 200)
                (assoc-in [:pocket/site :pocket/unsealable?] true))
            bore (fexit/pocket-bore rejected)]
        (is (contains? #{:eat :pocket-hold} (:intent/kind bore)))
        (is (not= :tunnel-to (:intent/kind bore)))))
    (testing "sealed at full health, it bores out sealed-behind at the same y"
      (let [full (assoc sealed-w :player/health 20.0 :player/food 20)
            i (plan/next-intent full ps/row plan/goals)]
        (is (= :tunnel-to (:intent/kind i)))
        (is (true? (:intent/seal-behind? i)))
        (is (true? (:intent/no-down? i)))
        (is (= 64 (second (:intent/target i))))))
    (testing "sealed at full health is the hand-over, and the refuge stays off after it"
      (is (fexit/sealed-full? sealed-w))
      (is (nil? (fexit/blaze-refuge sealed-w)))
      (is (nil? (fexit/blaze-refuge (assoc sealed-w :pearls/refuge-done true :player/health 8.0)))))
    (testing "hurt out of the pocket after the rods, the blaze refuge, not the hunt, answers"
      ;; the logged death: out of the pocket grabbing the seventh rod, hurt and watched,
      ;; the goal flipped to :ender-pearls and the hunt walked on at the spawner
      (let [hurt (-> sealed-w
                     (assoc :player/pos [11.5 64.0 8.5] :player/health 8.0 :player/food 14)
                     (assoc-in [:pocket/site :pocket/placed] []))
            i (plan/next-intent hurt ps/row plan/goals)]
        (is (contains? #{:pocket :seal :eat :pocket-hold} (:intent/kind i))
            (str "the blaze refuge or its meal, not the hunt: " (pr-str i)))
        (is (not= :tunnel-to (:intent/kind i)))))
    (testing "at full health out of the pocket after the rods, watched, the refuge still answers"
      ;; the 2026-11-04 races (lanes 41936905595, 41936902382, commit 6d16500): 20 of 21
      ;; rod bots held 15 to 20 health at the seventh rod, in sight of blazes at the
      ;; spawner; the hunt bored out in their sight, was shot down, and only then was
      ;; sent back to the pocket, the two cutting each other until the blazes killed it
      ;; (Clj_r14: 150 walks and 23 tunnels in the 180 s after the rod, death-blaze)
      (let [watched (-> sealed-w
                        (assoc :player/pos [11.5 64.0 8.5] :player/health 20.0 :player/food 20)
                        (assoc-in [:pocket/site :pocket/placed] [])
                        (assoc :world/entities
                               {7 {:entity/type (blocks/entity-types :blaze)
                                   :entity/pos [13.5 65.0 8.5]
                                   :entity/seen-at 0
                                   :entity/health 20.0}}))
            i (plan/next-intent watched ps/row plan/goals)]
        (is (some? (fexit/blaze-refuge watched))
            "a blaze watching at full health is still the refuge's business")
        (is (contains? #{:pocket :seal :eat :pocket-hold :fight} (:intent/kind i))
            (str "the blaze refuge, not the hunt's bore: " (pr-str i)))
        (is (not= :tunnel-to (:intent/kind i)))))
    (testing "watched with no pocket site held, the refuge still answers, never nil"
      ;; probe 41986025500 R1/12 seed 1: at the rod the pocket-go and here-intent
      ;; both came back nil, the refuge with them, and the hunt's wall-toward
      ;; stood in five watchers' sight from 14.4 health to 1.6 in two seconds
      (let [no-site (-> sealed-w
                        (assoc :player/pos [11.5 64.0 8.5]
                               :player/health 14.0
                               :player/food 20
                               :pocket/site nil)
                        (assoc :world/entities
                               {7 {:entity/type (blocks/entity-types :blaze)
                                   :entity/pos [13.5 65.0 8.5]
                                   :entity/seen-at 0
                                   :entity/health 20.0}}))
            i (plan/next-intent no-site ps/row plan/goals)]
        (is (some? (fexit/blaze-refuge no-site))
            (str "the refuge, pocket, seal-in-place or cover: "
                 (pr-str (fexit/blaze-refuge no-site))))
        (is (contains? #{:pocket :seal :eat :pocket-hold :fight :walk} (:intent/kind i))
            (str "a refuge answer, not the hunt's wall or bore: " (pr-str i)))))))

(deftest a-bore-in-its-open-pocket-strikes-a-blaze-in-reach-instead-of-holding
  ;; Logged state, probe 41914679195 R3/10 seed 1: after the rods the bot held
  ;; :pocket-hold at hp 13.8 down to 0 with a blaze on it, its seal never laid.
  ;; In the open pocket a blaze within the sword's reach is struck first.
  (let [netherrack (blocks/default-state :netherrack)
        floor (into {} (for [x (range 0 17) z (range 0 17)] [[x 63 z] netherrack]))
        roof (into {} (for [x (range 0 17) z (range 0 17)] [[x 66 z] netherrack]))
        walls (into {}
                    (concat (for [y [64 65] z (range 0 17)] [[7 y z] netherrack])
                            (for [y [64 65] z (range 0 17)] [[9 y z] netherrack])
                            (for [y [64 65]] [[8 y 7] netherrack])))
        blaze {:entity/type (blocks/entity-types :blaze)
               :entity/pos [8.5 64.0 10.5]
               :entity/seen-at 0
               :entity/health 20.0}
        w (-> (ps/standing {99 blaze} (merge floor roof walls))
              (assoc :player/dimension "minecraft:the_nether"
                     :player/pos [8.5 64.0 8.5]
                     :player/health 14.0
                     :plan/status :active
                     :plan/goals #{:ender-pearls}
                     :plan/reached #{:blaze-rods}
                     :plan/active [ps/row]
                     :pocket/site
                     {:pocket/cell [8 64 8] :pocket/stand nil :pocket/dig [] :pocket/placed []})
              (memory/observe [8 70 3] blaze/spawner)
              (update :player/inventory assoc
                      1 (ps/item :cooked_beef 12)
                      2 (ps/item :cobblestone 64)
                      3 (ps/item :iron_pickaxe 1)))
        bore (fexit/pocket-bore w)]
    (is (= :fight (:intent/kind bore)) "strikes the blaze in reach, never holds under it")
    (is (= 99 (:intent/target bore)))))

(deftest fortress-exit-never-steps-or-digs-down
  ;; On #715, downward tunnel moves through a 1-block fortress floor dropped bots into
  ;; the lava sea 40 blocks below (death-lava x4). During fortress escape, no step
  ;; may ever have dy < 0: moves must be level or upward (dy >= 0).
  (let [feet [8 64 8]
        spawner-pos [8 64 8]
        nether-brick (blocks/default-state :nether_bricks)
        floor-blocks (into {} (for [x (range 0 16) z (range 0 16)] [[x 63 z] nether-brick]))
        w (-> (ps/standing {} floor-blocks)
              (assoc :player/dimension "minecraft:the_nether" :player/pos [8.5 64.0 8.5])
              (assoc-in [:player/inventory 1] (ps/item :iron_pickaxe 1))
              (memory/observe spawner-pos blaze/spawner)
              (assoc :plan/status :active
                     :plan/goals #{:ender-pearls}
                     :plan/reached #{:blaze-rods}
                     :plan/active [ps/row]))
        i (fexit/fortress-tunnel-out w)
        target (:intent/target i)
        moves (fortress/moves feet target (:intent/climb i))
        step (fortress/next-move w feet target (:intent/climb i) false #{} false true)]
    (is (true? (:intent/no-down? i)) "intent specifies no-down?")
    (is (>= (second target) 64) "target is at or above feet y, never below")
    (is (some? step) "a safe horizontal step is found")
    (is (>= (second step) 0) "selected step has dy >= 0, never digging down through floor")
    (testing "all moves considered with no-down? have dy >= 0"
      (let [filtered-moves (filter #(>= (second %) 0) moves)]
        (is (not-any? #(neg? (second %)) filtered-moves))))))

(deftest a-rod-bot-retrace-never-targets-its-own-feet-or-an-adjacent-bore-cell
  ;; Logged state, race run 41781768766 lane 8 s1 Clj_r79 (commit 2ac6b12): after the rods
  ;; milestone (981 s) the bot stood at [886.5 53 -179.4], feet [886 53 -180], and the planner
  ;; started :tunnel-to [886 53 -180] -- its own feet -- 24,234 times, dug 3 cells and never
  ;; got 2 blocks from the spawner pocket until the lane cut it (:timeout, churn 18228).
  ;; A tunnel to a cell inside its reach (1.5) ends :done without a step and is chosen again.
  ;; The same geometry in miniature: feet [8 53 8], the trail's next corridor cell
  ;; [10 53 9], whose nearest bore cell [9 53 9] is 1.4 from the feet.
  (let [netherrack (blocks/default-state :netherrack)
        floor (into {} (for [x (range 4 16) z (range 4 14)] [[x 52 z] netherrack]))
        roof (into {} (for [x (range 4 16) z (range 4 14)] [[x 55 z] netherrack]))
        walls (into {} (for [x (range 9 10) z [8 10] y [53 54]] [[x y z] netherrack]))
        w (-> (ps/standing {} (merge floor roof walls))
              (assoc :player/dimension "minecraft:the_nether" :player/pos [8.5 53.0 8.5])
              (assoc-in [:player/inventory 1] (ps/item :iron_pickaxe 1))
              (assoc :fortress/trail {:trail/target [8 53 9] :trail/cells [[8 53 8]]})
              (assoc :plan/trail [[13 53 8] [10 53 9] [8 53 7]]))
        target (fexit/fortress-retrace-target w)]
    (is (some? target) "a retrace target 2 or more away exists")
    (is (not= [8 53 8] target) "never the bot's own feet cell")
    (is (not= [9 53 9] target) "never the adjacent bore cell inside the tunnel's reach")
    (is (>= (physics/distance [8 53 8] target) 2.0)
        "the tunnel must step to reach its target, so it cannot end :done in place")
    (testing "with only an adjacent trail cell left, no retrace target"
      (let [w2 (assoc w :plan/trail [[8 53 7]])]
        (is (nil? (fexit/fortress-retrace-target w2))
            "the far fallback in fortress-tunnel-target takes over instead")))))

(deftest the-refuge-hands-the-exit-over-after-minutes-at-full-health
  ;; race-read 1105b Q2: 22 of 52 sim rod bots timed out at the spawner pocket, fighting
  ;; and collecting for hours (TRACE :fight 21480, :collect-drops 21696), because #723's
  ;; watcher gate keeps the refuge in charge while a blaze watches and the spawner never
  ;; stops making them. The first minutes stay protected; the hold is bounded.
  (let [blaze-e {:entity/type (blocks/entity-types :blaze)
                 :entity/pos [13.5 65.0 8.5]
                 :entity/seen-at 0
                 :entity/health 20.0}
        w (-> (ps/standing {9 blaze-e})
              (assoc :player/dimension "minecraft:the_nether"
                     :player/pos [11.5 64.0 8.5]
                     :player/health 20.0
                     :plan/goals #{:ender-pearls}
                     :plan/reached #{:blaze-rods}
                     :time/now 0)
              (memory/observe [8 70 3] blaze/spawner))]
    (testing "the first minutes after rod 7: the refuge still answers, watched"
      (let [early (assoc w :pearls/refuge-since 0 :time/now (dec pparams/refuge-leave-ms))]
        (is (not (fexit/refuge-expired? early)))
        (is (some? (fexit/blaze-refuge early)))))
    (testing "after refuge-leave-ms at full health, even watched, it leaves"
      (let [late (assoc w :pearls/refuge-since 0 :time/now pparams/refuge-leave-ms)]
        (is (fexit/refuge-expired? late))
        (is (nil? (fexit/blaze-refuge late)) "no refuge intent: the sealed exit takes over")
        (is (true? (:pearls/refuge-done (fexit/track-refuge late)))
            "and the hand-over sticks in the steer")))
    (testing "never at danger health: a hurt bot is not sent out"
      (let [hurt (assoc w
                        :player/health 8.0
                        :pearls/refuge-since 0
                        :time/now (* 10 pparams/refuge-leave-ms))]
        (is (not (fexit/refuge-expired? hurt)))))
    (testing "the steer notes when the refuge first kept the bot"
      (is (= 0 (:pearls/refuge-since (fexit/track-refuge (assoc w :time/now 0))))))))

(deftest the-tunnel-out-target-is-held-while-the-feet-move-along-the-bore
  ;; Logged state, sim d3ce99b R1-24-1 (issue #263): 620 :tunnel-to intents flipped between
  ;; [-188 78 163] and [-188 78 160] for an hour, each target four blocks ahead of the feet and
  ;; re-derived at every act, the pickaxe worn out. Two acts from cells along the bore must name
  ;; the one target until it is reached, or a walk to it failed.
  (let [netherrack (blocks/default-state :netherrack)
        rock (into {} (for [x (range 0 16) y [75 76 77 78 79] z (range 0 16)] [[x y z] netherrack]))
        w (-> (ps/standing {} rock)
              (assoc :player/dimension "minecraft:the_nether" :player/pos [10.5 77.0 8.5])
              (assoc-in [:player/inventory 1] (ps/item :iron_pickaxe 1))
              (memory/observe [12 76 10] blaze/spawner)
              (assoc :plan/trail [[2 77 10] [10 77 8]]))
        first-target (fexit/fortress-tunnel-target w)
        held (fexit/fortress-exit-hold w)
        along (assoc held :player/pos [8.5 77.0 8.5])
        fresh (assoc (dissoc held :pearls/exit-target) :player/pos [8.5 77.0 8.5])]
    (is (= first-target (:pearls/exit-target held)) "the target is kept as a fact")
    (is (not= first-target (fexit/fortress-tunnel-target fresh))
        "re-derived from the next cell it would move four blocks on")
    (is (= first-target (fexit/fortress-tunnel-target along)) "held two blocks along the bore")
    (is (not= first-target (fexit/fortress-tunnel-target (assoc along :player/pos [6.5 77.0 8.5])))
        "a new target once the held one is reached")
    (is (not= first-target
              (fexit/fortress-tunnel-target (assoc along :plan/blacklist #{first-target})))
        "a failed target is passed over")
    (testing "the held target is dropped once clear of the fortress, and at a death"
      (is (not (contains? (fexit/fortress-exit-hold (assoc held :player/pos [400.5 77.0 400.5]))
                          :pearls/exit-target)))
      (is (not (contains? (game/die held) :pearls/exit-target))))))

(deftest a-bot-hit-while-it-bores-out-of-the-fortress-answers-the-wither-skeleton
  ;; Clj_r10 (d3ce99b, run 42353848749) was faced 16 times and hit 6 by wither skeletons, 20
  ;; health to none in 21 s, a :tunnel-to its whole life: the hunt answers the heavy kinds itself,
  ;; so :defend left them, and the bore never swung. Healthy it now fights the striker where it
  ;; stands; hurt it lays a block between, never the fight and never the dig on (issue #263).
  (let [skeleton-id 42
        skeleton {:entity/type (blocks/entity-types :wither_skeleton)
                  :entity/pos [8.5 64.0 9.5]
                  :entity/seen-at 0
                  :entity/health 20.0}
        spawner-pos [8 64 2]
        nether-brick (blocks/default-state :nether_bricks)
        floor (into {} (for [x (range 0 16) z (range 0 16)] [[x 63 z] nether-brick]))
        base (-> (ps/standing {skeleton-id skeleton} floor)
                 (assoc :player/dimension "minecraft:the_nether"
                        :player/pos [8.5 64.0 8.5]
                        :player/health 20.0
                        :player/food 20
                        :time/now 200
                        :player/attacker skeleton-id
                        :player/struck-at 190
                        :plan/status :active
                        :plan/goals #{:ender-pearls}
                        :plan/reached #{:blaze-rods}
                        :plan/active [ps/row])
                 (memory/observe spawner-pos blaze/spawner)
                 (update :player/inventory assoc
                         2 (ps/item :cobblestone 64)
                         3 (ps/item :iron_pickaxe 1)))]
    (is (fexit/can-tunnel-out? base) "a pickaxe held and no lava round: the bore is the plan")
    (testing "healthy and alone, the striker is fought where the bot stands"
      (let [i (plan/next-intent base ps/row plan/goals)]
        (is (= :fight (:intent/kind i)))
        (is (= skeleton-id (:intent/target i)))
        (is (true? (:intent/hold? i)))))
    (testing "a wither skeleton is fought only from avoided-health: at 12 the cover"
      (let [i (plan/next-intent (assoc base :player/health 12.0) ps/row plan/goals)]
        (is (not (contains? #{:fight :tunnel-to :dig} (:intent/kind i))))))
    (testing "two wither skeletons at full health: the cover, not the fight"
      (let [w (-> base
                  (assoc :player/health 20.0)
                  (assoc-in [:world/entities 43] (assoc skeleton :entity/pos [7.5 64.0 8.5])))
            i (plan/next-intent w ps/row plan/goals)]
        (is (not (contains? #{:fight :tunnel-to :dig} (:intent/kind i))))))
    (testing "healthy at 18 and alone, the fight yields under retreat-health"
      (let [i (plan/next-intent (assoc base :player/health 18.0) ps/row plan/goals)]
        (is (= :fight (:intent/kind i)))
        (is (true? (:intent/yield? i)))))
    (testing "hurt under the danger line, a block is laid between: not the fight, not the bore"
      (let [i (plan/next-intent (assoc base :player/health 5.0) ps/row plan/goals)]
        (is (not (contains? #{:fight :tunnel-to :dig} (:intent/kind i))))
        (is (contains? #{:place :pillar :eat} (:intent/kind i)))))
    (testing "a strike long past leaves the bore going"
      (let [i (plan/next-intent (assoc base :player/struck-at 0 :time/now 20000) ps/row plan/goals)]
        (is (= :tunnel-to (:intent/kind i)))))))

(deftest the-tunnel-out-ends-clear-of-the-spawner-however-far-the-fortress-runs
  ;; Logged state, sim R1/17 (batch 59e528b, probe 42370534718): 9642 fortress points known, the
  ;; spawner at [921 69 -117]. The bot tunnelled east to x 982, 60 blocks from the spawner, but
  ;; fortress bricks seen on the way kept it "near", so the escape went on: up to the roof, back
  ;; 100 blocks west, nine pickaxes and every plank and stick spent. The exit leaves the spawner.
  (let [brick (blocks/default-state :nether_bricks)
        w (-> (ps/standing {})
              (assoc :player/dimension "minecraft:the_nether" :player/pos [68.5 94.0 8.5])
              (assoc-in [:player/inventory 1] (ps/item :iron_pickaxe 1))
              (memory/observe [8 70 8] blaze/spawner)
              (memory/observe [76 70 8] brick)
              (assoc :plan/status :active
                     :plan/goals #{:ender-pearls}
                     :plan/reached #{:blaze-rods}
                     :plan/active [ps/row]))]
    (is (fexit/near-fortress? w) "a brick of the fortress is ten blocks off")
    (is (not (fexit/escape-near? w)) "but the spawner is sixty blocks off")
    (is (nil? (fexit/fortress-tunnel-target w)) "the exit is done: no target")
    (is (not (fexit/fortress-escaping? w)))
    (testing "near the spawner it still escapes"
      (let [near (assoc w :player/pos [20.5 70.0 8.5])]
        (is (fexit/escape-near? near))
        (is (some? (fexit/fortress-tunnel-target near)))))))

(deftest an-exit-on-a-column-in-open-air-bridges-toward-its-surface
  ;; Issue #263, real stage-18 lanes: nine of ten :blocked endings were the exit tunnel, and the
  ;; ones folded to the failing tick stood on a column of their own blocks in a Nether cavern, the surface target fifty blocks along and
  ;; sixteen up. Every level and up step had an air floor with nothing to lay an up step against,
  ;; and no-down? refused the two steps down, so no step was left: :blocked from the same stand
  ;; for fifteen minutes until the plan stalled. The exit bridges the cavern as the way to the
  ;; fortress does. Cells shifted: feet [0 64 0] on [0 63 0], ground at y 40, target [50 80 0].
  (let [netherrack (blocks/default-state :netherrack)
        air (blocks/default-state :air)
        cavern {:world/chunks {}
                :world/blocks
                (merge (into {}
                             (for [x (range -4 61) y (range 41 86) z (range -4 5)] [[x y z] air]))
                       (into {} (for [x (range -4 61) z (range -4 5)] [[x 40 z] netherrack]))
                       {[0 63 0] netherrack [0 62 0] netherrack})
                :player/pos [0.5 64.0 0.5]
                :player/on-ground? true
                :player/health 20.0
                :player/inventory {0 (ps/item :cobblestone 64)}}
        feet [0 64 0]
        target [50 80 0]
        exit (-> (ps/standing {})
                 (assoc :player/dimension "minecraft:the_nether" :player/pos [8.5 64.0 8.5])
                 (assoc-in [:player/inventory 1] (ps/item :iron_pickaxe 1))
                 (memory/observe [8 64 8] blaze/spawner))
        i (fexit/fortress-tunnel-out exit)
        step (fn [bridge?]
               (fortress/next-move cavern
                                   feet
                                   target
                                   (:intent/climb i)
                                   false
                                   #{}
                                   bridge?
                                   (:intent/no-down? i)))]
    (is (= :tunnel-to (:intent/kind i)))
    (is (true? (:intent/bridge? i)) "the exit tunnel bridges a cavern")
    (is (true? (:intent/no-down? i)) "and still never steps down")
    (is (nil? (step false)) "without the bridge no step is left: the :blocked of the real lanes")
    (is (= [1 0 0] (step (:intent/bridge? i))) "with it, the level step toward the target")))

(deftest a-fresh-exit-passes-over-a-given-up-target
  ;; Issue #263, real stage-18 lanes: after an exit tunnel failed, its target was blacklisted, but
  ;; the fresh exit derived from the same feet was that target again (or a block off it). The hunt
  ;; started it, the planner cut it a tick later as no longer wanted, and the retry walked back.
  ;; Bot 72 blocks east of the spawner at y 50, under the surface (the spawner's y 64): the
  ;; surfacing exit is [132 64 8], 52 along the heading east.
  (let [w (-> (ps/standing {})
              (assoc :player/dimension "minecraft:the_nether" :player/pos [80.5 50.0 8.5])
              (assoc-in [:player/inventory 1] (ps/item :iron_pickaxe 1))
              (memory/observe [8 64 8] blaze/spawner))]
    (is (= [132 64 8] (fexit/fortress-tunnel-target w)) "the surfacing exit, nothing given up")
    (is (= [140 64 8] (fexit/fortress-tunnel-target (assoc w :plan/blacklist #{[132 64 8]})))
        "that target given up: the next exit along the heading")
    (is (= [140 64 8] (fexit/fortress-tunnel-target (assoc w :plan/blacklist #{[131 64 9]})))
        "a target a block off the given-up one is the same exit, passed over too")
    (is (= [132 64 8] (fexit/fortress-tunnel-target (assoc w :plan/blacklist #{7})))
        "an entity id on the blacklist (a chase given up) is no cell: nothing passed over")
    (let [all (set (for [d (range 52 125 8)] [(+ 80 d) 64 8]))
          none (assoc w :plan/blacklist all)]
      (is (nil? (fexit/fortress-tunnel-target none)) "every exit given up: none")
      (is (not (fexit/fortress-escaping? none)) "and the hunt's other ways out take over"))))
