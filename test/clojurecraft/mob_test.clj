(ns clojurecraft.mob-test
  "Mobs in the server model (issue #260): the rules (mob.clj) and the model spawning, walking,
   hitting, shooting, blasting and dying by them, from fixtures (:mobs given) as every combat
   stage's sim tests start, and the whole bot against them."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojurecraft.blocks :as blocks]
            [clojurecraft.dimension :as dimension]
            [clojurecraft.entity :as entity]
            [clojurecraft.fixtures :as fx]
            [clojurecraft.game :as game]
            [clojurecraft.mob :as mob]
            [clojurecraft.physics :as physics]
            [clojurecraft.plan :as plan]
            [clojurecraft.recipe :as recipe]
            [clojurecraft.sim :as sim]
            [clojurecraft.world :as world]))

(use-fixtures :once fx/instrumented)

;; ---------------------------------------------------------------- the rules

(deftest a-hit-does-the-damage-of-the-held-item
  (is (= 1.0 (mob/weapon-damage nil)) "the hand")
  (is (= 1.0 (mob/weapon-damage (recipe/item-id :stick))))
  (is (= 4.0 (mob/weapon-damage (recipe/item-id :wooden_sword))))
  (is (= 6.0 (mob/weapon-damage (recipe/item-id :iron_sword))))
  (is (= 9.0 (mob/weapon-damage (recipe/item-id :stone_axe))))
  (is (= 2.0 (mob/weapon-damage (recipe/item-id :wooden_pickaxe))))
  (is (= 5.5 (mob/weapon-damage (recipe/item-id :diamond_shovel)))))

(deftest a-blast-hurts-less-with-distance
  (is (= 43.0 (mob/blast-damage 3.0 0.0)) "a creeper at the feet kills")
  (is (= 16.75 (mob/blast-damage 3.0 3.0)))
  (is (= 0.0 (mob/blast-damage 3.0 6.0)) "nothing at twice the power"))

(deftest difficulty-scales-a-mobs-damage
  (is (= 2.5 (mob/scaled 3.0 :easy)))
  (is (= 3.0 (mob/scaled 3.0 :normal)))
  (is (= 4.5 (mob/scaled 3.0 :hard))))

(deftest night-is-from-thirteen-to-twenty-three-thousand
  (is (not (mob/night? 12999)))
  (is (mob/night? 13000))
  (is (mob/night? (+ 24000 18000)) "every day")
  (is (not (mob/night? 23000))))

(defn floor-free?
  "A free? over a stone floor under y 64 with solid cells added."
  [solid]
  (fn [[_ y _ :as c]] (and (>= y 64) (not (contains? solid c)))))

(defn near?
  "Are points a and b within a millionth of a block on every axis?"
  [a b]
  (every? #(< (abs %) 1e-6) (map - a b)))

(deftest a-mob-walks-straight-steps-up-one-and-stops-at-two
  (let [free? (floor-free? #{[4 64 0] [9 64 0] [9 65 0]})]
    (is (near? [7.385 64.0 0.5] (mob/step-toward free? [7.5 64.0 0.5] [0.5 64.0 0.5] 0.115 1.0)))
    (is (near? [4.885 65.0 0.5] (mob/step-toward free? [5.0 64.0 0.5] [0.5 64.0 0.5] 0.115 1.0))
        "one block in the way is jumped")
    (is (= [8.95 64.0 0.5] (mob/step-toward free? [8.95 64.0 0.5] [20.5 64.0 0.5] 0.115 1.0))
        "a wall two high stops it")
    (is (= [1.5 64.0 0.5] (mob/step-toward free? [1.5 64.0 0.5] [0.5 64.0 0.5] 0.115 1.0))
        "already at its stop")))

(deftest an-enderman-stays-out-of-a-tunnel-two-high
  ;; issue #263: 2.9 tall, an enderman needs three free cells (mob/tall); a roof at y 66 over x 3
  ;; and on keeps it out where a zombie walks in
  (let [free? (floor-free? (set (for [x (range 3 10)] [x 66 0])))]
    (is (= 3 (mob/tall :enderman)))
    (is (= 2 (mob/tall :zombie)) "every other walker as before")
    (is (= [2.95 64.0 0.5]
           (mob/step-toward free? [2.95 64.0 0.5] [8.5 64.0 0.5] 0.197 1.12 (mob/tall :enderman))))
    (is (near?
         [3.065 64.0 0.5]
         (mob/step-toward free? [2.95 64.0 0.5] [8.5 64.0 0.5] 0.115 1.12 (mob/tall :zombie))))))

(defn pit-free?
  "A free? over a stone floor under y 64 with solid cells added and the cells of pit dug out."
  [solid pit]
  (let [floor (floor-free? solid)] (fn [c] (or (contains? pit c) (floor c)))))

(deftest a-route-steps-round-a-wall-down-a-drop-and-ends-nearest-when-it-cannot-arrive
  ;; issue #263: vanilla's mobs walk a path; the model's enderman searches one when its straight
  ;; walk is stopped (mob/route), ending, like the pathfinder, at the cell nearest the target
  (let [pit #{[1 63 0] [1 62 0] [1 61 0]}]
    (is (some #{[1 61 0]} (mob/moves (pit-free? #{} pit) [0 64 0] 3)) "a drop of three")
    (is (not-any? #(= 1 (first %)) (mob/moves (pit-free? #{} (conj pit [1 60 0])) [0 64 0] 3))
        "never four")
    (is (some #{[1 65 0]} (mob/moves (pit-free? #{[1 64 0]} #{}) [0 64 0] 3)) "a jump of one")
    (is (not-any? #(= 1 (first %)) (mob/moves (pit-free? #{[1 64 0] [0 67 0]} #{}) [0 64 0] 3))
        "never with the head against a roof"))
  (let [wall (set (for [z (range -3 4) y (range 64 67)] [4 y z]))
        r (mob/route (floor-free? wall) [8 64 0] [0.5 64.0 0.5] 3)]
    (is (= [0 64 0] (peek r)) "round the wall to the target's cell")
    (is (every? (fn [[[ax _ az] [bx _ bz]]] (= 1 (+ (abs (- ax bx)) (abs (- az bz)))))
                (partition 2 1 (cons [8 64 0] r)))
        "a side step at a time")
    (is (not-any? wall r) "never through it"))
  (let [roof (set (for [x (range 3 10) z (range -20 21)] [x 66 z]))]
    (is (= [2 64 0] (peek (mob/route (floor-free? roof) [0 64 0] [8.5 64.0 0.5] 3)))
        "a tunnel two high keeps it at the mouth, the cell nearest")
    (is (= [] (mob/route (floor-free? roof) [2 64 0] [8.5 64.0 0.5] 3)) "already there")))

(deftest an-enderman-aims-its-teleports-as-vanilla-and-lands-on-a-floor-with-room
  ;; EnderMan.teleportTowards: 16 along the line from its middle to the target's eye, spread 8
  ;; on x and z and -8 to 7 on y; EnderMan.teleport: up to 32 off on each axis;
  ;; LivingEntity.randomTeleport: down to a floor, the body clear of blocks and liquids
  (let [eye (physics/eye [0.5 64.0 0.5])
        y (- (second eye) 1.45)]
    (is (near? [4.5 y 0.5] (mob/toward-aim [20.5 y 0.5] eye 0.5 0.5 0.5)) "sixteen nearer")
    (is (near? [0.5 (- y 8.0) 4.5] (mob/toward-aim [20.5 y 0.5] eye 0.0 0.0 1.0))
        "four aside, eight down at most"))
  (is (near? [-31.5 32.0 32.5] (mob/away-aim [0.5 64.0 0.5] 0.0 0.0 1.0)))
  (let [world (fn [blocks]
                (fn [[x y z :as c]]
                  (cond (> (max (abs x) (abs z)) 20) nil
                        (contains? blocks c) (blocks c)
                        (< y 64) 1
                        :else 0)))
        water (first blocks/water-states)]
    (is (= [3.3 64.0 2.2] (mob/landing (world {}) [3.3 70.6 2.2] -64)) "down onto the floor")
    (is (nil? (mob/landing (world {}) [3.3 60.2 2.2] -64)) "never into the rock")
    (is (nil? (mob/landing (world {[3 64 2] water}) [3.3 70.6 2.2] -64)) "nor into water")
    (is (nil? (mob/landing (world {[3 66 2] 1}) [3.3 65.4 2.2] -64)) "nor under a roof two high")
    (is (nil? (mob/landing (world {}) [30.3 70.6 2.2] -64)) "nor where nothing is loaded")))

;; ---------------------------------------------------------------- on the model

(defn arena
  "A model in play over blocks (on the stone floor), the player at [0.5 64 0.5] holding inv, at
   difficulty (or none) at day time day, with mobs given as a fixture's."
  [blocks inv difficulty day mobs]
  (-> (sim/init
       (cond->
         {:column (world/column-bytes blocks) :spawn [0.5 64.0 0.5] :inventory inv :day-time day}
         difficulty (assoc :difficulty difficulty)))
      (assoc :sim/phase :play :sim/player-pos [0.5 64.0 0.5] :sim/on-ground? true)
      (sim/with-mobs mobs)))

(defn ticks
  "The model after n game ticks, 50 ms apart."
  [sim n]
  (reduce (fn [sim _] (sim/step sim {:sim/kind :tick :sim/now (+ (:sim/now sim) physics/tick-ms)}))
          sim
          (range n)))

(defn sends
  "The model after the client sends pkt."
  [sim pkt]
  (sim/step sim {:sim/kind :packet :sim/packet pkt}))

(defn said
  "Every packet named n the model has queued."
  [sim n]
  (filter #(= n (:packet/name %)) (:sim/out sim)))

(defn the-mob
  "The one mob of kind in the model, [eid mob], or nil."
  [sim kind]
  (first (filter #(= kind (:type (val %))) (:sim/mobs sim))))

(def night 18000)

(deftest a-fixtures-mobs-are-sent-at-login
  (let [sim (-> (sim/init {:column (world/column-bytes {})
                           :spawn [0.5 64.0 0.5]
                           :mobs [{:type :blaze :pos [4.5 66.0 0.5]}]})
                (assoc :sim/phase :configuration)
                (sends {:packet/name :finish-configuration}))
        [eid] (the-mob sim :blaze)]
    (is (some #(and (= eid (:entity-id %)) (= (mob/type-id :blaze) (:type %)))
              (said sim :add-entity)))
    (is (= [{:entity-id eid :metadata {entity/health-index 20.0}}]
           (map #(select-keys % [:entity-id :metadata]) (said sim :set-entity-data))))
    (is (nil? (:sim/fixture-mobs sim)))))

(deftest a-zombie-walks-at-the-player-and-hits-it-every-second
  (let [sim (arena {} {} nil night [{:type :zombie :pos [8.5 64.0 0.5]}])
        later (ticks sim 100)
        [_ z] (the-mob later :zombie)]
    (is (< (first (:pos z)) 2.0) "it walked up")
    (is (seq (said later :move-entity-pos)) "the client saw it walk")
    (is (< (:sim/health later) 20.0) "and was hit")
    (is (zero? (mod (- 20.0 (:sim/health later)) 3.0)) "three a hit on Normal")
    (is (some #(< (:health %) 20.0) (said later :set-health)) "the client is told")))

(defn close? "Is the number a within a millionth of b?" [a b] (< (abs (- a b)) 1e-6))

(deftest armour-takes-its-share-of-a-mobs-hit
  ;; CombatRules.getDamageAfterAbsorb (issue #263): with no armour in the model an enderman beat
  ;; every sword before the fight's break-off
  (is (= 7.0 (mob/absorbed 7.0 0 0.0)) "nothing worn")
  (is (close? 5.74 (mob/absorbed 7.0 8 0.0)) "an enderman's 7 on iron helmet and chestplate")
  (is (close? 1.89 (mob/absorbed 7.0 20 8.0)) "on all four diamond pieces")
  (let [worn {[:armour 0] {:item (recipe/item-id :iron_helmet) :count 1}
              [:armour 1] {:item (recipe/item-id :iron_chestplate) :count 1}}
        sim (arena {} worn nil night [{:type :zombie :pos [2.5 64.0 0.5]}])]
    (is (close? 5.74 (sim/armoured sim 7.0)) "the model reads what window 0's armour slots hold")
    (is (= 7.0 (sim/armoured (arena {} {} nil night []) 7.0)))
    (is (close? (- 20.0 (mob/absorbed 3.0 8 0.0)) (:sim/health (ticks sim 15)))
        "a zombie's first hit, through the iron")))

(def iron-sword {[:hotbar 0] {:item (recipe/item-id :iron_sword) :count 1}})

(defn attack [sim eid] (sends sim {:packet/name :attack :entity-id eid}))

(deftest an-iron-sword-kills-a-zombie-in-four-hits-and-it-drops-and-goes
  (let [sim (arena {} iron-sword nil night [{:type :zombie :pos [2.5 64.0 0.5]}])
        [eid] (the-mob sim :zombie)
        hit (fn [sim] (ticks (attack sim eid) 13)) ; 650 ms between swings
        dead (nth (iterate hit sim) 4)]
    (is (= 14.0 (:health (get-in (attack sim eid) [:sim/mobs eid]))))
    (is (= 14.0 (:health (get-in (attack (attack sim eid) eid) [:sim/mobs eid])))
        "a second swing inside the hurt window does nothing")
    (is (some? (:dead-at (get-in dead [:sim/mobs eid]))))
    (is (= [{:entity-id eid :status entity/death-status}]
           (map #(select-keys % [:entity-id :status]) (said dead :entity-event))))
    (is (= 246 (get-in dead [:sim/inv 36 :durability])) "the sword wore one a hit")
    (testing "removed once its death is over"
      (let [gone (ticks dead 21)]
        (is (nil? (get-in gone [:sim/mobs eid])))
        (is (some #(= [eid] (:ids %)) (said gone :remove-entities)))))))

(deftest out-of-reach-the-attack-does-nothing
  (let [sim (arena {} iron-sword nil night [{:type :zombie :pos [6.5 64.0 0.5]}])
        [eid] (the-mob sim :zombie)]
    (is (= 20.0 (:health (get-in (attack sim eid) [:sim/mobs eid]))))))

(deftest an-enderman-drops-its-pearl-by-the-mobs-own-rolls
  ;; 40 health: seven iron sword hits; a pearl one time in two (0-1 in the loot row)
  (let [kills (for [seed (range 6)]
                (let [sim (assoc
                           (arena {} iron-sword nil night [{:type :enderman :pos [2.5 64.0 0.5]}])
                           :sim/mob-rng
                           seed)
                      [eid] (the-mob sim :enderman)]
                  (nth (iterate #(ticks (attack % eid) 13) sim) 7)))
        pearl? #(= (recipe/item-id :ender_pearl) (:item %))
        ;; lying where it fell, or picked up already (the swings go on 650 ms past the kill)
        pearls (map (fn [s]
                      (some pearl? (concat (map :item (vals (:sim/items s))) (vals (:sim/inv s)))))
                    kills)]
    (is (every? #(:dead-at (val (the-mob % :enderman))) kills))
    (is (some true? pearls) "some kills drop a pearl")
    (is (some nil? pearls) "and some none")))

(deftest a-creeper-swells-for-a-second-and-a-half-and-blasts
  (let [sim (arena {} {} :normal night [{:type :creeper :pos [3.5 64.0 0.5]}])
        lit (ticks sim 2)
        blown (ticks sim 40)]
    (is (some? (:fuse-at (val (the-mob lit :creeper)))) "lit within reach")
    (is (nil? (the-mob blown :creeper)) "gone in its blast")
    (is (= 0.0 (:sim/health blown)) "a blast at a block or two kills")
    (testing "a player who walks away puts the fuse out"
      (let [fled (-> lit
                     (sends {:packet/name :move-player-pos :x 12.5 :y 64.0 :z 0.5 :flags 1})
                     (ticks 2))]
        (is (nil? (:fuse-at (val (the-mob fled :creeper)))))))))

(def wall
  "Stone three high across the arena at x 4, between the player and anything east."
  (into {} (for [y [64 65 66] z (range 16)] [[4 y z] (blocks/default-state :stone)])))

(deftest a-skeleton-shoots-with-a-clear-line-and-never-through-a-wall
  (let [open (ticks (arena {} {} nil night [{:type :skeleton :pos [9.5 64.0 0.5]}]) 30)
        walled (ticks (arena wall {} nil night [{:type :skeleton :pos [9.5 64.0 0.5]}]) 30)]
    (is (= 17.0 (:sim/health open)) "one arrow of three")
    (is (= 20.0 (:sim/health walled)))))

(deftest an-enderman-turns-only-when-stared-at
  (let [sim (arena {} {} nil night [{:type :enderman :pos [6.5 64.0 0.5]}])
        eye (physics/eye [0.5 64.0 0.5])
        [yaw pitch] (physics/look-at eye (mob/head :enderman [6.5 64.0 0.5]))
        look (fn [sim yaw pitch]
               (sends sim {:packet/name :move-player-rot :yaw yaw :pitch pitch :flags 1}))
        ignored (ticks (look sim (+ yaw 90.0) 0.0) 40)
        ;; a stare kept up within 4 would teleport it away (an-angry-enderman-stared-at-up-close-teleports-away)
        stared (-> sim
                   (look yaw pitch)
                   (ticks 3)
                   (look (+ yaw 90.0) 0.0)
                   (ticks 40))]
    (is (not (:angry? (val (the-mob ignored :enderman)))))
    (is (= 20.0 (:sim/health ignored)) "it leaves a player who looks away alone")
    (is (:angry? (val (the-mob stared :enderman))))
    (is (< (:sim/health stared) 20.0) "and comes for one who stares")))

(deftest a-zombie-burns-in-daylight-under-the-sky-and-not-under-a-roof
  (let [open (ticks (arena {} {} :normal 1000 [{:type :zombie :pos [12.5 64.0 12.5]}]) 20)
        roof (into {}
                   (for [x (range 10 16) z (range 10 16)] [[x 68 z] (blocks/default-state :stone)]))
        shaded (ticks (arena roof {} :normal 1000 [{:type :zombie :pos [12.5 64.0 12.5]}]) 20)]
    (is (= 19.0 (:health (val (the-mob open :zombie)))))
    (is (= 20.0 (:health (val (the-mob shaded :zombie)))))))

(defn flat-world
  "Seven by seven chunks of the stone floor round [0 0], each topped by blocks."
  [blocks]
  (let [column (world/column-bytes blocks)]
    (into {} (for [cx (range -3 4) cz (range -3 4)] [[cx cz] column]))))

(defn angry-enderman
  "A Normal model by day over flat-world's floor topped by blocks, the player at [0.5 64 0.5]
   looking nowhere, and one enderman at pos already turned on it: [model eid]."
  [blocks pos]
  (let [sim (-> (sim/init {:columns (flat-world blocks)
                           :spawn [0.5 64.0 0.5]
                           :difficulty :normal
                           :day-time 1000
                           :seed 3})
                (assoc :sim/phase :play :sim/player-pos [0.5 64.0 0.5] :sim/on-ground? true)
                (sim/with-mobs [{:type :enderman :pos pos}]))
        [eid] (the-mob sim :enderman)]
    [(assoc-in sim [:sim/mobs eid :angry?] true) eid]))

(defn jumps
  "How many of the n ticks after sim moved mob eid more than two blocks at once."
  [sim eid n]
  (->> (reductions (fn [s _] (ticks s 1)) sim (range n))
       (map #(get-in % [:sim/mobs eid :pos]))
       (partition 2 1)
       (filter (fn [[a b]] (> (physics/distance a b) 2.0)))
       count))

(deftest an-angry-enderman-far-off-teleports-toward-the-player
  ;; EndermanLookForPlayerGoal: farther than 16 for 30 ticks, it teleports toward its target
  (let [[sim eid] (angry-enderman {} [40.5 64.0 0.5])
        later (ticks sim 60)]
    (is (zero? (jumps sim eid 29)) "it walks the first thirty ticks")
    (is (= 1 (jumps sim eid 60)) "then teleports once")
    (is (seq (filter #(= eid (:entity-id %)) (said later :entity-position-sync)))
        "the client told of a jump too long for a move")
    (is (< (physics/distance (get-in later [:sim/mobs eid :pos]) [0.5 64.0 0.5]) 24.0))
    (is (zero? (apply jumps (conj (angry-enderman {} [14.5 64.0 0.5]) 60)))
        "within 16 it only walks")))

(deftest an-angry-enderman-stared-at-up-close-teleports-away
  (let [[sim eid] (angry-enderman {} [3.5 64.0 0.5])
        [yaw pitch] (physics/look-at (physics/eye [0.5 64.0 0.5])
                                     (mob/head :enderman [3.5 64.0 0.5]))
        look #(sends sim {:packet/name :move-player-rot :yaw %1 :pitch %2 :flags 1})]
    (is (pos? (jumps (look yaw pitch) eid 20)) "looked in the face within 4, it teleports")
    (is (zero? (jumps (look (+ yaw 90.0) 0.0) eid 20)) "looked away from, it comes on")))

(deftest an-angry-enderman-walks-round-a-wall-to-the-player
  ;; vanilla's enderman paths round a wall; the model's searches a route once its straight walk
  ;; stops (sim/trail, mob/route). A wall three high from z -3 to 3 at x 4 (every chunk's)
  (let [wall (into {} (for [lz (concat (range 0 4) (range 13 16)) y (range 64 67)] [[4 y lz] 1]))
        [sim eid] (angry-enderman wall [8.5 64.0 0.5])
        later (ticks sim 200)]
    (is (< (:sim/health later) 20.0) "it found its way round and hit")
    (is (zero? (jumps sim eid 200)) "walking, within 16")
    (is (= 20.0 (:sim/health (ticks (update-in sim [:sim/mobs eid] dissoc :angry?) 200)))
        "one not angry stays where it is")))

(deftest a-night-spawns-a-hostile-in-the-ring-and-a-day-does-not
  (let [play (fn [day]
               (-> (sim/init {:columns (flat-world {})
                              :spawn [0.5 64.0 0.5]
                              :difficulty :normal
                              :day-time day
                              :seed 7})
                   (assoc :sim/phase :play :sim/player-pos [0.5 64.0 0.5])
                   (ticks 100)))
        at-night (play night)
        by-day (play 1000)
        [_ m] (first (:sim/mobs at-night))]
    (is (= 1 (count (:sim/mobs at-night))) "one try every five seconds, and it found ground")
    (is (:hostile (mob/kinds (:type m))))
    (is (<= mob/spawn-near (mob/flat-distance (:pos m) [0.5 64.0 0.5]) (+ mob/spawn-far 1.0)))
    (is (empty? (:sim/mobs by-day)) "no roof anywhere, no spawn by day")))

(deftest a-loaded-chunk-has-animals-on-its-grass-one-time-in-ten
  (let [grass
        (into {} (for [x (range 16) z (range 16)] [[x 63 z] (blocks/default-state :grass_block)]))
        sim (-> (sim/init {:columns (flat-world grass)
                           :spawn [0.5 64.0 0.5]
                           :difficulty :normal
                           :day-time 1000
                           :seed 3})
                (assoc :sim/phase :play :sim/player-pos [0.5 64.0 0.5])
                (ticks 1))
        animals (filter #(:passive (mob/kinds (:type (val %)))) (:sim/mobs sim))]
    (is (= 49 (count (:sim/populated sim))) "every chunk in reach had its turn, once")
    (is (seq animals) "some chunks got a herd")
    (is (every? #(:persistent? (val %)) animals))
    (is (every? #(= 64.0 (second (:pos (val %)))) animals) "standing on the grass")))

(deftest the-bot-killed-by-a-zombie-respawns
  ;; an open field is a back-off now: a fist loses the trade and the bot leaves. This is the
  ;; death that back-off cannot prevent. The zombie shares the cell and the cell is bedrock, so
  ;; the hits land until the bot dies and its client-command is answered with a new life.
  (let [rock (blocks/default-state :bedrock)
        walls (into {}
                    (for [x (range 7 10)
                          z (range 7 10)
                          y [64 65]
                          :when (not (and (= x 8) (= z 8)))]
                      [[x y z] rock]))
        step (game/compose game/step plan/step)
        [w sim] (sim/run step
                         (game/init fx/opts)
                         (sim/init {:column (world/column-bytes (assoc walls [8 66 8] rock))
                                    :spawn [8.5 64.0 8.5]
                                    :difficulty :normal
                                    :day-time night
                                    :mobs [{:type :zombie :pos [8.9 64.0 8.5]}]})
                         #(or (pos? (:stats/deaths % 0)) (plan/failed? %))
                         60000
                         {:event/kind :go :go/goals [:wood]})]
    (is (= 1 (:stats/deaths w)) (pr-str (plan/summary w)))
    (is (:player/dead? w) "the zombie killed it")
    (is (= 1 (:sim/respawns sim)) "its client-command was answered with a new life")))

(deftest a-zombie-the-sun-kills-hits-no-more
  ;; the burn and the swing fall on one tick: a mob that dies to the sun is dying, not chasing
  (let [sim (arena {} {} :normal 1000 [{:type :zombie :pos [1.5 64.0 0.5]}])
        [eid] (the-mob sim :zombie)
        sim (update-in sim [:sim/mobs eid] assoc :health 1.0 :attack-at 1000)
        burnt (ticks sim 20)]
    (is (some? (:dead-at (get-in burnt [:sim/mobs eid]))))
    (is (= 20.0 (:sim/health burnt)))))

;; ---------------------------------------------------------------- the Nether's own spawns (issue #263)

(def nylium (blocks/default-state :warped_nylium))

(def warped-floor
  "A column's top at y 63 of warped nylium: a warped forest's floor, as the model tells one."
  (into {} (for [x (range 16) z (range 16)] [[x 63 z] nylium])))

(defn nether-world
  "Seven by seven Nether columns of the stone floor round [0 0], each topped by blocks."
  [blocks]
  (let [column (world/column-bytes blocks dimension/nether)]
    (into {} (for [cx (range -3 4) cz (range -3 4)] [[cx cz] column]))))

(def nether-spawn [0.5 64.0 0.5])

(defn nether-play
  "A Normal model in play in the Nether over nether-world's columns, by the overworld's day
   (nothing to do with the Nether's spawns), after n ticks."
  [blocks seed n]
  (-> (sim/init {:columns (nether-world blocks)
                 :spawn nether-spawn
                 :dimension dimension/nether
                 :difficulty :normal
                 :day-time 1000
                 :seed seed})
      (assoc :sim/phase :play :sim/player-pos nether-spawn :sim/on-ground? true)
      (ticks n)))

(deftest an-enderman-spawns-on-warped-nylium-with-three-cells-of-room
  (let [floor (fn [[_ y _]]
                (cond (= y 63) nylium
                      (< y 63) 1))
        rock (fn [[_ y _]] (cond (<= y 63) 1))
        open (fn [c] (nil? (floor c)))
        low (fn [[_ y _ :as c]] (and (open c) (< y 66)))]
    (is (mob/nether-spot? floor open [3 64 3]) "nylium under the feet, the air above open")
    (is (not (mob/nether-spot? rock open [3 64 3])) "netherrack or stone is no warped forest")
    (is (not (mob/nether-spot? floor low [3 64 3])) "2.9 tall, it needs three cells (mob/tall)")
    (is (not (mob/nether-spot? floor open [3 65 3])) "nothing under the feet")
    (is (= [[3 64 3]] (mob/nether-spots floor open 3 3 48 80)))
    (is (= 5 (mob/pack-step 0.99 0.0)) "nextInt(6) - nextInt(6): five at most")
    (is (= -5 (mob/pack-step 0.0 0.99)))
    (is (= 0 (mob/pack-step 0.5 0.5)))))

(deftest a-warped-forest-spawns-packs-of-endermen-by-day-and-netherrack-spawns-nothing
  ;; issue #263: vanilla spawns the Nether's hostiles by biome, the warped forest's list being
  ;; the enderman alone in packs of four, in any light the model tells (the overworld's night
  ;; rule would spawn nothing here by day, with no roof); the model tells the forest by its floor
  (let [forest (nether-play warped-floor 7 500)
        mobs (vals (:sim/mobs forest))]
    (is (<= 1 (count mobs) (* 5 mob/enderman-pack))
        "five tries, a pack of up to four each, some despawned beyond 32")
    (is (every? #(= :enderman (:type %)) mobs) "the forest's one kind")
    (is (every? #(= 64.0 (second (:pos %))) mobs) "standing on the nylium")
    (is (every? #(<= mob/spawn-near (physics/distance (:pos %) nether-spawn)) mobs)
        "none nearer the player than 24")
    (is (every? #(<= (mob/flat-distance (:pos %) nether-spawn)
                     (+ mob/spawn-far (* mob/enderman-pack (dec mob/pack-walk)) 1.0))
                mobs)
        "in the ring, a pack's walk past it at most")
    (is (empty? (:sim/mobs (nether-play {} 7 500))) "a stone floor is no warped forest")))

(deftest the-overworld-spends-no-roll-of-the-nethers-stream
  ;; the Nether's spawns roll from their own stream (sim/wild-roll), so the overworld's night
  ;; rule rolls as it did before it: a seed's run is the same with the Nether's rule or without
  (let [sim0 (-> (sim/init {:columns (flat-world {})
                            :spawn nether-spawn
                            :difficulty :normal
                            :day-time night
                            :seed 7})
                 (assoc :sim/phase :play :sim/player-pos nether-spawn))
        states (reductions (fn [sim _] (ticks sim 1)) sim0 (range 500))
        sim (last states)]
    (is (seq (:sim/mobs sim)) "the night rule spawned")
    (is (= (:sim/wild-rng sim0) (:sim/wild-rng sim)) "and the Nether's stream never rolled")
    (is (every? #(identical? % (sim/try-nether-spawn %)) states)
        "the Nether's rule leaves every overworld tick as it was")))

;; ---------------------------------------------------------------- blaze spawners

(def sp [12 64 12])

(deftest a-spawner-wakes-within-sixteen-of-a-player
  (is (mob/spawner-awake? sp [12.5 64.0 12.5]))
  (is (mob/spawner-awake? sp [12.5 64.5 28.5]) "sixteen from its centre exactly")
  (is (not (mob/spawner-awake? sp [12.5 64.5 28.6]))))

(deftest a-spawners-delay-and-batch-are-rolled-from-their-ranges
  (is (= 200 (mob/spawner-delay 0.0)))
  (is (= 800 (mob/spawner-delay 0.9999)))
  (is (= 4 (mob/spawner-batch 0.0)) "vanilla's SpawnCount: four tries, whatever the roll")
  (is (= 4 (mob/spawner-batch 0.5)))
  (is (= 4 (mob/spawner-batch 0.99))))

(deftest a-spawner-makes-no-more-blazes-once-six-stand-about-it
  (is (= 6 (mob/spawner-room 0)))
  (is (= 2 (mob/spawner-room 4)))
  (is (= 0 (mob/spawner-room 6)))
  (is (= 0 (mob/spawner-room 9)) "never under zero")
  (is (mob/spawner-crowded? sp [8.0 64.0 12.0]))
  (is (mob/spawner-crowded? sp [17.0 68.0 12.0]))
  (is (not (mob/spawner-crowded? sp [7.9 64.0 12.0])))
  (is (not (mob/spawner-crowded? sp [17.1 64.0 12.0]))))

(deftest a-spawners-try-lands-as-basespawner-picks-it
  ;; x and z are (r1 - r2) * 4 from the cell plus a half, floored: a triangle over nine cells;
  ;; y is one of the three cells under, level and over
  (is (= [12 64 12] (mob/spawner-spot sp 0.5 0.5 0.5 0.5 0.5)) "equal rolls: on the spawner")
  (is (= [8 63 8] (mob/spawner-spot sp 0.0 0.999 0.0 0.0 0.999)) "the near, low corner")
  (is (= [16 65 16] (mob/spawner-spot sp 0.999 0.0 0.999 0.999 0.0)) "the far, high corner")
  (is (= [12 64 13] (mob/spawner-spot sp 0.5 0.5 0.5 0.65 0.5)) "a small difference: a cell off")
  (let [xs (frequencies (for [a (range 0 1 0.05)
                              b (range 0 1 0.05)]
                          (first (mob/spawner-spot sp a b 0.5 0.5 0.5))))]
    (is (> (xs 12) (* 4 (get xs 16 0)))
        "a triangle: the spawner's own column far likelier than the edge"))
  (is (mob/spawner-dark? 11) "a blaze spawns at 11")
  (is (not (mob/spawner-dark? 12)))
  (is (mob/spawner-dark? mob/interior-light) "the model's fortress is dark"))

(deftest a-blaze-is-placed-where-its-body-fits-once-to-a-cell
  (let [free? (floor-free? #{sp [8 65 9]})]
    (is (mob/spawner-fits? free? [8 64 8] #{}))
    (is (not (mob/spawner-fits? free? [8 64 8] #{[8 64 8]})) "taken")
    (is (not (mob/spawner-fits? free? [8 63 8] #{})) "in the floor")
    (is (not (mob/spawner-fits? free? [8 64 9] #{})) "a roof over its head")
    (is (= [[8 64 8]]
           (mob/spawner-spawns free?
                               sp
                               3
                               [[0.0 0.999 0.5 0.0 0.999] [0.0 0.999 0.5 0.0 0.999]
                                [0.5 0.5 0.5 0.5 0.5]]))
        "the second try's cell is taken, the third is the spawner")
    (is (= [] (mob/spawner-spawns free? sp 3 [[0.0 0.999 0.0 0.0 0.999]]))
        "a try in the floor makes none")
    (is (= [[8 64 8]]
           (mob/spawner-spawns free? sp 1 [[0.0 0.999 0.5 0.0 0.999] [0.9 0.0 0.5 0.9 0.0]]))
        "one try, one blaze at most")))

(def fortress-floor
  "A Nether column with a spawner at sp on its floor and the cells under the floor round it
   carved out, so every cell of the spawner's box is open but its own."
  (assoc (into {} (for [x (range 8 17) z (range 8 17)] [[x 63 z] 0])) sp mob/spawner-block))

(defn in-fortress
  "A model in play in the Nether over fortress-floor, the player standing at feet, at difficulty."
  [feet difficulty]
  (-> (sim/init {:column (world/column-bytes fortress-floor dimension/nether)
                 :spawn feet
                 :dimension dimension/nether
                 :difficulty difficulty
                 :food 20})
      (assoc :sim/phase :play :sim/player-pos feet :sim/on-ground? true)))

(defn blazes-of
  "The living blazes of the model, {eid mob}."
  [sim]
  (into {} (filter #(= :blaze (:type (val %))) (:sim/mobs sim))))

(deftest a-spawner-near-the-player-makes-blazes-within-eight-hundred-ticks
  (let [sim (in-fortress [8.5 64.0 8.5] :normal)]
    (is (empty? (blazes-of (ticks sim 100))) "none before its delay")
    (let [later (ticks sim 810)
          bs (blazes-of later)]
      (is (<= 1 (count bs) 6) "a batch of four tries, never past the cap")
      (is (every? #(mob/spawner-crowded? sp (:pos %)) (vals bs)) "in the box about it")
      (is (= (count bs) (count (said later :add-entity))) "each told to the client"))))

(deftest a-spawner-the-player-is-far-from-or-a-peaceful-one-makes-none
  (is (empty? (blazes-of (ticks (in-fortress [8.5 64.0 -10.5] :normal) 900))) "asleep")
  (is (empty? (blazes-of (ticks (in-fortress [8.5 64.0 8.5] :peaceful) 900))) "peaceful"))

(deftest a-broken-spawner-stops
  (let [sim (-> (in-fortress [8.5 64.0 8.5] :normal)
                (ticks 10)
                (update :sim/broken conj sp)
                (ticks 900))]
    (is (empty? (blazes-of sim)))
    (is (not (contains? (:sim/spawners sim) sp)))))
