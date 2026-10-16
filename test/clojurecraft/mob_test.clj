(ns clojurecraft.mob-test
  "Mobs in the server model (issue #260): the rules (mob.clj) and the model spawning, walking,
   hitting, shooting, blasting and dying by them, from fixtures (:mobs given) as every combat
   stage's sim tests start, and the whole bot against them."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojurecraft.blocks :as blocks]
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
        stared (ticks (look sim yaw pitch) 40)]
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
  ;; the sim lanes' deaths: a bot with nothing to do (no tree) beside a zombie at night dies, its
  ;; client-command answered with a new life, as a real Normal server does
  (let [step (game/compose game/step plan/step)
        [w sim] (sim/run step
                         (game/init fx/opts)
                         (sim/init {:column (world/column-bytes {})
                                    :spawn [0.5 64.0 0.5]
                                    :difficulty :normal
                                    :day-time night
                                    :mobs [{:type :zombie :pos [3.5 64.0 0.5]}]})
                         #(or (pos? (:stats/deaths % 0)) (plan/failed? %))
                         60000
                         {:event/kind :go :go/goals [:wood]})]
    (is (= 1 (:stats/deaths w)) (pr-str (plan/summary w)))
    (is (:player/dead? w) "the zombie killed it")
    (is (= 1 (:sim/respawns sim)) "its client-command was answered with a new life")))
