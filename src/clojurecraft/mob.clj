(ns clojurecraft.mob
  "Mobs as the server runs them, for the server model (sim.clj): each kind's numbers as data, and
   the rules a mob lives by as pure functions over a cell lookup, the way fluid.clj models water.
   Vanilla 26.1.2's mobs are goal-driven AI over a pathfinder; this is a model of what a player
   meets, not of that AI: a mob spawns by a seeded rule (dark cells near the player, more at
   night, under a cap), walks straight at the player when it is in follow range, steps up one
   block, falls, and hurts the player by its kind: a melee hit in reach on a cooldown, an arrow
   or a fireball at range with a clear line, a creeper's fuse and blast. Passive animals stand
   where the world placed them, food for the player. A player's attack hurts a mob by the held
   item; a mob at zero health dies and drops its loot.

   Every function here takes free?, a function of a cell [x y z] to whether a body may stand in
   it (loaded and not solid), or solid?, its opposite for sight: what a mob does is a function of
   the cells round it, its own value and the player's. Time and rolls are the caller's.

   Not modelled: pathfinding round walls (a mob walks straight and stops at what it cannot step
   up), knockback, an arrow's flight (the damage lands when the skeleton fires), a blast breaking
   blocks, an enderman's teleports, spiders climbing, mobs swimming, armour."
  (:require [clojure.math :as math]
            [clojurecraft.blocks :as blocks]
            [clojurecraft.physics :as physics]
            [clojurecraft.recipe :as recipe]))

(set! *warn-on-reflection* true)

;; ---------------------------------------------------------------- the kinds, as data

(def kinds
  "Each kind's numbers. :health the game's max health; :box [width height] its hitbox; :speed
   blocks a tick walking at the player (the movement speed attribute through the ground's
   friction: a zombie's 0.23 walks 2.3 blocks a second); :follow how near the player must be for
   it to chase; :melee the damage of one hit on Normal and :every the ticks between hits, :reach
   how near (feet to feet on the plane) a hit lands; :shoot a ranged hit's damage on Normal,
   :range how far it shoots with a clear line, :keep how near it walks before it stands to shoot;
   :fuse a creeper's ticks from swelling to blast, :swell how near the player starts it, :blast
   the blast's power; :undead burns in daylight under the sky; :neutral attacks only once hurt or
   stared at; :passive never attacks; :hostile spawns by the night rule, :weight how often among
   them; :loot [[item lo hi]], each item lo to hi when killed by the player."
  {:zombie {:health 20.0
            :box [0.6 1.95]
            :speed 0.115
            :follow 35.0
            :melee 3.0
            :every 20
            :reach 1.4
            :undead true
            :hostile true
            :weight 100
            :loot [[:rotten_flesh 0 2]]}
   :skeleton {:health 20.0
              :box [0.6 1.99]
              :speed 0.137
              :follow 16.0
              :shoot 3.0
              :every 40
              :range 15.0
              :keep 10.0
              :undead true
              :hostile true
              :weight 100
              :loot [[:bone 0 2] [:arrow 0 2]]}
   :creeper {:health 20.0
             :box [0.6 1.7]
             :speed 0.137
             :follow 16.0
             :fuse 30
             :swell 3.0
             :blast 3.0
             :hostile true
             :weight 100
             :loot [[:gunpowder 0 2]]}
   :spider {:health 16.0
            :box [1.4 0.9]
            :speed 0.197
            :follow 16.0
            :melee 2.0
            :every 20
            :reach 1.6
            :hostile true
            :weight 100
            :loot [[:string 0 2]]}
   :enderman {:health 40.0
              :box [0.6 2.9]
              :speed 0.197
              :follow 64.0
              :melee 7.0
              :every 20
              :reach 1.4
              :neutral true
              :hostile true
              :weight 10
              :loot [[:ender_pearl 0 1]]}
   :blaze {:health 20.0
           :box [0.6 1.8]
           :speed 0.0
           :follow 48.0
           :shoot 5.0
           :every 60
           :range 16.0
           :keep 16.0
           :loot [[:blaze_rod 0 1]]}
   :cow {:health 10.0 :box [0.9 1.4] :passive true :loot [[:beef 1 3] [:leather 0 2]]}
   :pig {:health 10.0 :box [0.9 0.9] :passive true :loot [[:porkchop 1 3]]}
   :sheep {:health 8.0 :box [0.9 1.3] :passive true :loot [[:mutton 1 2] [:white_wool 1 1]]}
   :chicken {:health 4.0 :box [0.4 0.7] :passive true :loot [[:chicken 1 1] [:feather 0 2]]}
   :ender_dragon {:health 200.0 :box [16.0 8.0] :perched true :loot []}
   :end_crystal {:health 1.0 :box [2.0 2.0] :blast 6.0 :crystal true :loot []}})

(defn type-id
  "The entity type id of kind (entity-types.edn), as add-entity carries it."
  [kind]
  (get blocks/entity-types kind))

(def hostile-kinds
  "The kinds the night rule spawns, with their weights, in a fixed order so a roll replays."
  (vec (sort-by key (into {} (for [[k v] kinds :when (:hostile v)] [k (:weight v)])))))

(def passive-kinds
  "The animals a chunk is given when it is first loaded, in a fixed order."
  [:chicken :cow :pig :sheep])

(def death-ticks 20)                  ; a dead mob is removed this long after it died (LivingEntity.tickDeath)
(def dragon-death-ticks 200)          ; the dragon's death takes ten seconds (EnderDragon.tickDeath)
(def hurt-window-ms 500)              ; after a hurt only a harder one lands, for its excess (invulnerableTime past 10 of 20 ticks)
(def attack-reach 3.2)                ; how far from the eye a player's attack reaches the mob's box
(def day-ticks 24000)                 ; game ticks in a day
(def night-from 13000)                ; the day time the sky is dark enough for monsters under it
(def night-until 23000)
(def spawn-every 100)                 ; game ticks between spawn attempts (a model's rate, not vanilla's per-chunk one)
(def spawn-near 24.0)                 ; no hostile spawns nearer the player than this
(def spawn-far 48.0)                  ; nor farther (the model's ring, where a mob can matter)
(def spawn-band 16)                   ; the heights tried: this far above and below the player's feet
(def hostile-cap 70)                  ; hostiles within despawn-far of the player at most (the monster category's cap)
(def despawn-far 128.0)               ; a hostile farther than this from the player is removed at once
(def despawn-near 32.0)               ; beyond this, a hostile may be removed at random
(def despawn-chance (/ 1.0 800))      ; each tick, for a hostile beyond despawn-near (Mob.checkDespawn)
(def animal-chance 0.1)               ; a newly loaded chunk gets animals this often (creatureGenerationProbability)
(def herd 3)                          ; animals in a chunk's herd
(def burn-every 20)                   ; game ticks between a burning undead's hurts
(def burn-damage 1.0)

;; ---------------------------------------------------------------- damage

(def material-bonus
  "Each tool material's attack damage bonus (ToolMaterial)."
  {:wooden 0.0 :golden 0.0 :stone 1.0 :copper 1.0 :iron 2.0 :diamond 3.0 :netherite 4.0})

(def kind-damage
  "A tool kind's attack damage over the player's 1 (sword 3, pickaxe 1, shovel 1.5, hoe none:
   its bonus cancels the material's), before the material's bonus."
  {:sword 3.0 :pickaxe 1.0 :shovel 1.5})

(def axe-damage
  "An axe's whole attack damage by material: each has its own base."
  {:wooden 7.0 :golden 7.0 :stone 9.0 :copper 9.0 :iron 9.0 :diamond 9.0 :netherite 10.0})

(def hand-damage 1.0)                 ; the player's own attack damage attribute

(defn weapon-damage
  "The damage one hit with item id does (the attack damage attribute the item gives the player):
   a sword 4 to 8 by material, an axe 7 to 10, a pickaxe 2 to 6, a shovel 2.5 to 6.5, anything
   else the hand's 1. The attack's charge is not modelled: a bot that swings every 650 ms always
   swings charged."
  [id]
  (let [{:keys [tier kind]} (blocks/tool id)]
    (cond (= kind :axe) (axe-damage tier)
          (kind-damage kind) (+ hand-damage (kind-damage kind) (material-bonus tier))
          :else hand-damage)))

(defn scaled
  "A mob's damage to a player at difficulty (Player.hurt's scalesWithDifficulty): easy halves it
   plus one (never above it), normal keeps it, hard adds half; nil difficulty is normal."
  [damage difficulty]
  (case difficulty
    :easy (min damage (inc (/ damage 2.0)))
    :hard (* damage 1.5)
    damage))

(defn blast-damage
  "The damage a blast of power does to a player dist from its centre with a clear line
   (ServerExplosion: impact = 1 - dist / 2·power; damage = (impact² + impact) / 2 · 7 · 2·power
   + 1), zero beyond 2·power."
  [power dist]
  (let [impact (- 1.0 (/ dist (* 2.0 power)))]
    (if (pos? impact) (+ 1.0 (* (/ (+ (* impact impact) impact) 2.0) 7.0 2.0 power)) 0.0)))

;; ---------------------------------------------------------------- geometry

(defn flat-distance
  "Distance on the plane between two points."
  [[ax _ az] [bx _ bz]]
  (math/hypot (- ax bx) (- az bz)))

(defn box-distance
  "How far point p is from the box of a mob of kind standing at pos (feet centre): zero inside."
  [p kind pos]
  (let [[w h] (:box (kinds kind))
        [x y z] pos
        lo [(- x (/ w 2)) y (- z (/ w 2))]
        hi [(+ x (/ w 2)) (+ y h) (+ z (/ w 2))]
        d (mapv (fn [v a b] (max 0.0 (- a v) (- v b))) p lo hi)]
    (math/sqrt (reduce + (map * d d)))))

(defn head
  "The point a mob of kind at pos is looked at and shot from: its eye, 85% of its height."
  [kind [x y z]]
  [x (+ y (* 0.85 (second (:box (kinds kind))))) z])

(defn sight?
  "Is the line from a to b clear of solid cells (solid? of a cell)? Sampled every quarter block."
  [solid? a b]
  (let [d (physics/distance a b)]
    (or (< d 1e-6)
        (not-any? solid? (rest (physics/cells-along a (mapv #(/ (- %2 %1) d) a b) d 0.25))))))

(defn stared?
  "Is a player with eye and look [yaw pitch] staring at an enderman at pos: its head inside the
   narrow cone vanilla tests (EnderMan.isBeingStaredBy: the look and the line to the head closer
   than 1 - 0.025 / distance), within 64 blocks, with a clear line?"
  [solid? eye look pos]
  (let [h (head :enderman pos)
        d (physics/distance eye h)
        to (mapv #(/ (- %2 %1) d) eye h)]
    (and (< 0.0 d 64.0)
         (> (reduce + (map * (physics/direction look) to)) (- 1.0 (/ 0.025 d)))
         (sight? solid? eye h))))

;; ---------------------------------------------------------------- moving

(defn cell-of "The cell a point is in." [p] (mapv #(long (math/floor %)) p))

(defn stands?
  "May a body stand with its feet in cell [x y z]: feet and head cells free?"
  [free? [x y z]]
  (and (free? [x y z]) (free? [x (inc y) z])))

(defn fall
  "pos come down onto what is under it: the feet on the top of the first cell below that is not
   free (free? of a cell), at most 32 blocks, as a falling body ends a tick's fall."
  [free? [x y z]]
  (let [[cx cy cz] (cell-of [x y z])
        floor-y
        (reduce (fn [fy dy] (if (free? [cx (- cy dy) cz]) (- cy dy) (reduced fy))) cy (range 1 33))]
    [x (double (min y floor-y)) z]))

(defn step-toward
  "A mob at pos one tick on toward target at speed blocks a tick, stopping stop short of it on
   the plane: the straight step when the body stands there, one block up when a single block is
   in the way and the head clears it (a mob jumps it), else it stays; then it falls (fall)."
  [free? pos target speed stop]
  (let [[x y z] pos
        d (flat-distance pos target)
        k (min speed (max 0.0 (- d stop)))
        [tx _ tz] target
        nx (if (pos? d) (+ x (* k (/ (- tx x) d))) x)
        nz (if (pos? d) (+ z (* k (/ (- tz z) d))) z)
        [cx cy cz] (cell-of [nx y nz])]
    (cond (zero? k) pos
          (stands? free? [cx cy cz]) (fall free? [nx y nz])
          (and (stands? free? [cx (inc cy) cz]) (free? (update (cell-of pos) 1 + 2)))
          [nx (double (inc cy)) nz]
          :else pos)))

;; ---------------------------------------------------------------- spawning

(defn night?
  "Is day time t (game ticks) dark enough for monsters under the sky?"
  [t]
  (<= night-from (mod t day-ticks) (dec night-until)))

(defn roofed?
  "Is cell [x y z] under a roof: a solid block that is not leaves above it, up to top? A roofed
   cell is dark day and night (no torch is modelled), an open one only at night."
  [at [x y z] top]
  (boolean (some #(let [id (at [x % z])] (and id (blocks/solid? id) (not (blocks/leaves? id))))
                 (range (inc y) top))))

(defn spawn-spots
  "The cells of column x z from lo to hi a mob may spawn in: solid under the feet, the body
   free, and dark (roofed?, or night under the sky), lowest first."
  [at free? x z lo hi top night]
  (for [y (range lo (inc hi))
        :let [below (at [x (dec y) z])]
        :when (and below
                   (blocks/solid? below)
                   (stands? free? [x y z])
                   (or night (roofed? at [x y z] top)))]
    [x y z]))

(defn pick
  "The entry of weighted [[k w] ...] that a roll r in [0, 1) lands on."
  [weighted r]
  (let [total (reduce + (map second weighted))
        at (* r total)]
    (reduce (fn [acc [k w]] (if (< at (+ acc w)) (reduced k) (+ acc w))) 0.0 weighted)))

(defn loot-count
  "How many of a loot row [item lo hi] a roll r in [0, 1) drops: lo to hi, evenly."
  [[_ lo hi] r]
  (+ lo (long (math/floor (* r (inc (- hi lo)))))))

(defn loot-item "The item id of a loot row [item lo hi]." [[item]] (recipe/item-id item))
