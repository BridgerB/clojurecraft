(ns clojurecraft.mob
  "Mobs as the server runs them, for the server model (sim.clj): each kind's numbers as data, and
   the rules a mob lives by as pure functions over a cell lookup, the way fluid.clj models water.
   Vanilla 26.1.2's mobs are goal-driven AI over a pathfinder; this is a model of what a player
   meets, not of that AI: a mob spawns by a seeded rule (dark cells near the player, more at
   night, under a cap), walks straight at the player when it is in follow range, steps up one
   block, falls, and hurts the player by its kind: a melee hit in reach on a cooldown, an arrow
   or a fireball at range with a clear line, a creeper's fuse and blast. Passive animals stand
   where the world placed them, food for the player. A player's attack hurts a mob by the held
   item; a mob at zero health dies and drops its loot. A fortress's blaze spawner (the Nether's only
   spawning) runs by the rules at the end: awake within 16 of a player, a batch every 200 to 800
   ticks, under a cap of six blazes about it.

   Every function here takes free?, a function of a cell [x y z] to whether a body may stand in
   it (loaded and not solid), or solid?, its opposite for sight: what a mob does is a function of
   the cells round it, its own value and the player's. Time and rolls are the caller's.

   Not modelled: pathfinding round walls for any kind but the enderman (a mob walks straight and
   stops at what it cannot step up; an enderman stuck so finds a route, route), knockback, an
   arrow's flight (the damage lands when the skeleton fires), a blast breaking blocks, an
   enderman's teleports out of water, rain, daylight or a non-living hurt (its teleports at its
   target are, toward-aim and away-aim), spiders climbing, mobs swimming, armour wearing out. The
   armour the player wears takes its share of a mob's hit (absorbed, issue #263)."
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
   them; :loot [[item lo hi]], each item lo to hi when killed by the player; :paths finds a
   route round what stops its straight walk (route); :teleports teleports at its target as an
   enderman does (toward-aim, away-aim)."
  {:zombie {:health 20.0
            :box [0.6 1.95]
            :speed 0.115
            :follow 35.0
            :melee 3.0
            :paths true
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
              :paths true
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
             :paths true
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
            :paths true
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
              :paths true
              :teleports true
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
(def bite-out 8.0)                    ; blocks along its facing the sitting dragon's head reaches: the head part 6.5 out, its box inflated 1.5 more (EnderDragon.aiStep, .hurt)
(def bite-across 2.5)                 ; blocks across its facing: the neck part's box, 3 wide, inflated by 1
(def bite-rise 5.0)                   ; blocks a player's feet may be under or over the dragon's and be reached
(def bite-damage 10.0)                ; the head's and neck's hurt before the difficulty (EnderDragon.hurt)
(def bite-every 10)                   ; game ticks between its hurts (its hurtTime between them)
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

(def armour-points
  "Each armour piece's armour points and toughness, [points toughness], by item name
   (ArmorMaterials: a material's defense for the piece's slot, its toughness for every piece;
   read from the game's classes, probe run 38890566495)."
  {:leather_helmet [1 0.0]
   :leather_chestplate [3 0.0]
   :leather_leggings [2 0.0]
   :leather_boots [1 0.0]
   :copper_helmet [2 0.0]
   :copper_chestplate [4 0.0]
   :copper_leggings [3 0.0]
   :copper_boots [1 0.0]
   :chainmail_helmet [2 0.0]
   :chainmail_chestplate [5 0.0]
   :chainmail_leggings [4 0.0]
   :chainmail_boots [1 0.0]
   :golden_helmet [2 0.0]
   :golden_chestplate [5 0.0]
   :golden_leggings [3 0.0]
   :golden_boots [1 0.0]
   :iron_helmet [2 0.0]
   :iron_chestplate [6 0.0]
   :iron_leggings [5 0.0]
   :iron_boots [2 0.0]
   :diamond_helmet [3 2.0]
   :diamond_chestplate [8 2.0]
   :diamond_leggings [6 2.0]
   :diamond_boots [3 2.0]
   :netherite_helmet [3 3.0]
   :netherite_chestplate [8 3.0]
   :netherite_leggings [6 3.0]
   :netherite_boots [3 3.0]
   :turtle_helmet [2 0.0]})

(defn absorbed
  "The damage left of a hit of damage on a player wearing armour points and toughness
   (CombatRules.getDamageAfterAbsorb): the armour counts for points - damage / (2 + toughness / 4),
   never under a fifth of the points nor over 20, each point taking 4% off. Issue #263: an
   enderman's 7 on iron helmet and chestplate (8 points) is 5.74."
  [damage points toughness]
  (let [f (+ 2.0 (/ toughness 4.0))
        a (min 20.0 (max (* points 0.2) (- points (/ damage f))))]
    (* damage (- 1.0 (/ a 25.0)))))

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

(defn facing
  "[x z], the unit vector on the plane from the dragon's body toward its head for its yaw in
   degrees: EnderDragon.aiStep puts the head part at (sin yaw · 6.5, −cos yaw · 6.5) from the
   body, the opposite of any other mob's look (physics/direction), as the dragon flies along it."
  [yaw]
  (let [r (math/to-radians yaw)] [(math/sin r) (- (math/cos r))]))

(defn ahead
  "[along across] of point p from the dragon standing at pos with yaw, on the plane: blocks along
   its facing (toward the head, negative behind the body) and across it (positive to the
   facing's left, [−fz fx])."
  [pos yaw p]
  (let [[fx fz] (facing yaw)
        dx (- (first p) (first pos))
        dz (- (nth p 2) (nth pos 2))]
    [(+ (* dx fx) (* dz fz)) (- (* dz fx) (* dx fz))]))

(defn beside
  "[x z], the point on the plane along and across (ahead's frame) from the dragon standing at pos
   with yaw."
  [pos yaw [along across]]
  (let [[fx fz] (facing yaw)]
    [(+ (first pos) (* along fx) (* across (- fz))) (+ (nth pos 2) (* along fz) (* across fx))]))

(defn bitten?
  "Is a player whose feet are at p within the sitting dragon's head and neck, the dragon standing
   at pos with yaw: along its facing from its body's middle out to bite-out, across within
   bite-across and the player's half width, the feet within bite-rise of the dragon's. The head
   lowered toward a player near it brings the neck back toward the body (6.5 · cos pitch: gym
   dragon-e run 2 and dragon-f run 2 were slain two blocks from the fountain, in front of the
   head); the model takes every pitch down to the neck's box reaching the body's middle."
  [pos yaw p]
  (let [[along across] (ahead pos yaw p)]
    (and (<= 0.0 along bite-out)
         (<= (abs across) (+ bite-across physics/half-width))
         (<= (abs (- (second p) (second pos))) bite-rise))))

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

(defn tall
  "How many cells a body of kind fills upward to walk: its box's height rounded up, two at the
   least, as every walking mob was modelled before (issue #263: an enderman, 2.9 tall, takes
   three, so a tunnel two high keeps it out, as the server's pathfinder does)."
  [kind]
  (max 2 (long (math/ceil (second (:box (kinds kind) [0.6 1.8]))))))

(defn stands-in?
  "May a body n cells tall stand with its feet in cell [x y z]: those n cells free?"
  [free? [x y z] n]
  (every? #(free? [x (+ y %) z]) (range n)))

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
   in the way and the head clears it (a mob jumps it), else it stays; then it falls (fall). A
   body n cells tall (tall; two when not given) stands and jumps only where its n cells are free."
  ([free? pos target speed stop] (step-toward free? pos target speed stop 2))
  ([free? pos target speed stop n]
   (let [[x y z] pos
         d (flat-distance pos target)
         k (min speed (max 0.0 (- d stop)))
         [tx _ tz] target
         nx (if (pos? d) (+ x (* k (/ (- tx x) d))) x)
         nz (if (pos? d) (+ z (* k (/ (- tz z) d))) z)
         [cx cy cz] (cell-of [nx y nz])]
     (cond (zero? k) pos
           (stands-in? free? [cx cy cz] n) (fall free? [nx y nz])
           (and (stands-in? free? [cx (inc cy) cz] n) (free? (update (cell-of pos) 1 + n)))
           [nx (double (inc cy)) nz]
           :else pos))))

(defn in-reach?
  "Is a melee mob of kind at pos near enough to hit a player at target: on the plane within its
   :reach, the feet within 1.5 in height?"
  [kind pos target]
  (and (<= (flat-distance pos target) (:reach (kinds kind) 0.0))
       (< (abs (- (second pos) (second target))) 1.5)))

;; ---------------------------------------------------------------- a route round a wall (issue #263)

(def route-budget 1024)               ; cells a route search visits at most: the follow range times 16, vanilla's PathFinder budget (Mob.createNavigation), an enderman's 64
(def route-drop 3)                    ; blocks a route steps down at once at most: a mob's safe fall (Mob.getMaxFallDistance)
(def route-every 10)                  ; ticks between a stuck mob's route searches: about MeleeAttackGoal's 4 + nextInt(7) between recalculations, unrolled
(def route-near 1.0)                  ; blocks from the target a route's cell ends the search

(defn moves
  "The cells a body n cells tall goes to from cell [x y z] in one move, on the four sides: the
   side cell at its height when its n cells are free, where it falls to the first floor at most
   route-drop below (none when the drop is deeper); one up when that cell's n cells are free and
   the head clears the cell n over [x y z] (a jump)."
  [free? [x y z] n]
  (keep (fn [[dx dz]]
          (let [nx (+ x dx)
                nz (+ z dz)
                fy (reduce (fn [fy _] (if (free? [nx (dec fy) nz]) (dec fy) (reduced fy)))
                           y
                           (range route-drop))]
            (cond (and (stands-in? free? [nx y nz] n) (not (free? [nx (dec fy) nz]))) [nx fy nz]
                  (stands-in? free? [nx y nz] n) nil
                  (and (stands-in? free? [nx (inc y) nz] n) (free? [x (+ y n) z])) [nx (inc y) nz]
                  :else nil)))
        [[1 0] [-1 0] [0 1] [0 -1]]))

(defn route
  "The cells, in order and without from, a body n cells tall walks from cell from toward point
   target: a breadth-first search over moves visiting route-budget cells at most, ended early by
   a cell within route-near of target, to the cell it reached nearest target (by its feet's
   centre; the first reached of equals), as vanilla's pathfinder ends a path it cannot finish at
   its node nearest the target. Empty when from is that cell."
  [free? from target n]
  (let [far #(physics/distance [(+ (first %) 0.5) (double (second %)) (+ (nth % 2) 0.5)] target)
        [came best]
        (loop [queue (conj clojure.lang.PersistentQueue/EMPTY from)
               came {from from}
               best from]
          (cond (or (empty? queue) (>= (count came) route-budget) (< (far best) route-near))
                [came best]
                :else
                (let [c (peek queue)
                      fresh (remove came (moves free? c n))]
                  (recur (into (pop queue) fresh)
                         (reduce #(assoc %1 %2 c) came fresh)
                         (reduce #(if (< (far %2) (far %1)) %2 %1) best fresh)))))]
    (vec (reverse (take-while #(not= from %) (iterate came best))))))

;; ---------------------------------------------------------------- an enderman's teleports (issue #263)

(def teleport-far 16.0)               ; blocks from its target past which an angry enderman teleports toward it (EndermanLookForPlayerGoal.tick: distanceToSqr over 256)
(def teleport-wait 30)                ; ticks it stays that far first: the goal's adjustedTickDelay(30), 15 of its ticks, the goal ticking every other game tick
(def teleport-near 4.0)               ; blocks within which an angry enderman stared at teleports away (distanceToSqr under 16)
(def toward-reach 16.0)               ; blocks teleportTowards aims along the line from the enderman to its target
(def toward-spread 8.0)               ; blocks its aim is spread over on x and on z: (nextDouble - 0.5) * 8
(def toward-rise 16)                  ; its aim's rise: nextInt(16) - 8
(def away-spread 64.0)                ; EnderMan.teleport's aim on x and z: (nextDouble - 0.5) * 64
(def away-rise 64)                    ; and on y: nextInt(64) - 32

(defn toward-aim
  "The point an enderman at pos aims a teleport toward a target whose eye is at eye, for rolls
   r1 r2 r3 in [0, 1) (EnderMan.teleportTowards): toward-reach along the line from its middle to
   the eye, spread (r1 - 0.5) toward-spread on x, floor(toward-rise r2) - toward-rise/2 on y and
   (r3 - 0.5) toward-spread on z."
  [[x y z] eye r1 r2 r3]
  (let [mid [x (+ y (/ (second (:box (kinds :enderman))) 2.0)) z]
        d (physics/distance mid eye)
        [ux uy uz] (if (pos? d) (mapv #(/ (- %1 %2) d) mid eye) [0.0 0.0 0.0])]
    [(- (+ x (* (- r1 0.5) toward-spread)) (* ux toward-reach))
     (- (+ y (- (long (math/floor (* r2 toward-rise))) (quot toward-rise 2))) (* uy toward-reach))
     (- (+ z (* (- r3 0.5) toward-spread)) (* uz toward-reach))]))

(defn away-aim
  "The point an enderman at [x y z] aims a teleport away at for rolls r1 r2 r3 in [0, 1)
   (EnderMan.teleport): (r1 - 0.5) away-spread on x, floor(away-rise r2) - away-rise/2 on y,
   (r3 - 0.5) away-spread on z."
  [[x y z] r1 r2 r3]
  [(+ x (* (- r1 0.5) away-spread))
   (+ y (- (long (math/floor (* r2 away-rise))) (quot away-rise 2)))
   (+ z (* (- r3 0.5) away-spread))])

(defn landing
  "Where an enderman aiming a teleport at point [x y z] lands, or nil where it lands nowhere and
   stays (LivingEntity.randomTeleport): straight down from the point's cell, at min-y at the
   lowest, to the cell over the first solid one, its feet on that floor, when every cell on the
   way is loaded (at of a cell: its state, nil unloaded) and its tall cells there hold no block,
   water or lava (noCollision, containsAnyLiquid). Two things are simpler than vanilla: the feet
   are put on the floor, not the point's fraction of a block over it (the next tick's fall ends
   there), and only the point's column is looked at, not the cells the body's 0.6 width
   straddles."
  [at [x y z] min-y]
  (let [[cx cy cz] (cell-of [x y z])
        solid? #(blocks/solid? (at [cx % cz]))
        feet (reduce (fn [_ fy]
                       (cond (nil? (at [cx (dec fy) cz])) (reduced nil)
                             (solid? (dec fy)) (reduced fy)
                             :else nil))
                     nil
                     (range cy min-y -1))
        clear? (fn [fy]
                 (let [id (at [cx fy cz])]
                   (boolean (and id
                                 (not (blocks/solid? id))
                                 (not (contains? blocks/water-states id))
                                 (not (contains? blocks/lava-states id))))))]
    (cond (nil? feet) nil
          (every? #(clear? (+ feet %)) (range (tall :enderman))) [x (double feet) z]
          :else nil)))

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

;; ---------------------------------------------------------------- the Nether's own spawns (issue #263)

(def warped-kinds
  "The kinds a warped forest's monster list spawns, with their weights: the enderman alone
   (weight 1, in packs of enderman-pack), as vanilla's warped_forest biome lists them. The
   Nether spawns by biome; the model tells a warped forest by its floor (warped-floor?)."
  [[:enderman 1]])

(def enderman-pack 4)                 ; endermen a warped forest's spawn tries for at once (the biome's spawner: minCount and maxCount both 4)
(def pack-walk 6)                     ; each pack member steps nextInt(6) - nextInt(6) on x and on z from the last (NaturalSpawner.spawnCategoryForPosition)

(def warped-floor-states
  "The states of warped nylium: the warped forest's floor, where the model spawns its endermen.
   The captured Nether's biome containers are not decoded (chunk/read-section keeps the blocks
   alone), so the floor is the forest's sign, as it is the bot's."
  (blocks/states-of :warped_nylium))

(defn warped-floor?
  "Is state id a warped forest's floor (warped nylium)?"
  [id]
  (contains? warped-floor-states id))

(defn nether-spot?
  "May an enderman spawn with its feet in cell [x y z]: warped nylium under them and its tall
   cells free? Any light: the model's light is 0 everywhere, under the Nether's limit of 7
   (the dimension's monster_spawn_light_level) that vanilla's Monster.checkMonsterSpawnRules
   tests, so the shroomlit cells it would refuse are not told apart."
  [at free? [x y z :as cell]]
  (let [below (at [x (dec y) z])]
    (boolean (and below (warped-floor? below) (stands-in? free? cell (tall :enderman))))))

(defn nether-spots
  "The cells of column x z from lo to hi an enderman may spawn in (nether-spot?), lowest first."
  [at free? x z lo hi]
  (for [y (range lo (inc hi)) :when (nether-spot? at free? [x y z])] [x y z]))

(defn pack-step
  "How far the next pack member stands from the last on one axis for rolls r1 and r2 in
   [0, 1): floor(pack-walk r1) - floor(pack-walk r2), vanilla's nextInt(6) - nextInt(6)."
  [r1 r2]
  (- (long (math/floor (* pack-walk r1))) (long (math/floor (* pack-walk r2)))))

;; ---------------------------------------------------------------- spawners

(def spawner-block
  "The state id of a spawner block: a fortress's makes blazes (every spawner the model's Nether
   holds, a dungeon's zombies being the overworld's)."
  (blocks/default-state :spawner))

(def spawner
  "A blaze spawner as vanilla 26.1.2 runs it (BaseSpawner): :wake the distance from its centre a
   player must be within for it to run; :delay [lo hi] the ticks to the next spawn, rolled from
   lo to hi; :batch [lo hi] the blazes one spawn tries for; :cap the blazes of its kind that may
   stand within :near blocks of it before it makes no more; :box [x y z] the cells round it a
   blaze is placed in; :light the greatest light level a blaze spawns in."
  {:kind :blaze :wake 16.0 :delay [200 800] :batch [4 4] :cap 6 :near 4 :box [9 3 9] :light 11})

(def interior-light
  "The light level the model gives every cell of a fortress: none is lit, no torch being modelled."
  0)

(defn spawner-awake?
  "Is a player whose feet are at p within the spawner's :wake of the spawner block at cell
   [x y z] (its centre)? A spawner does nothing with no player near."
  [[x y z] p]
  (<= (physics/distance [(+ x 0.5) (+ y 0.5) (+ z 0.5)] p) (:wake spawner)))

(defn spawner-between
  "The whole number a roll r in [0, 1) lands on from lo to hi, evenly."
  [[lo hi] r]
  (+ lo (long (math/floor (* r (inc (- hi lo)))))))

(defn spawner-delay
  "The game ticks to a spawner's next spawn for a roll r in [0, 1): 200 to 800, evenly."
  [r]
  (spawner-between (:delay spawner) r))

(defn spawner-batch
  "How many blazes one spawn tries for, for a roll r in [0, 1): four, vanilla's SpawnCount (the
   roll is still taken, so the stream runs as it did when the batch was two to four)."
  [r]
  (spawner-between (:batch spawner) r))

(defn spawner-crowded?
  "Is a blaze at mob position m within the cells a spawner at cell [x y z] counts: its :near
   blocks each way of the cell, the cell's own span included (the box vanilla counts blazes in)?"
  [[x y z] [mx my mz]]
  (let [n (:near spawner)]
    (and (<= (- x n) mx (+ x n 1)) (<= (- y n) my (+ y n 1)) (<= (- z n) mz (+ z n 1)))))

(defn spawner-room
  "How many more blazes a spawner may make with nearby of its kind already round it: the :cap
   less them, never under zero."
  [nearby]
  (max 0 (- (:cap spawner) nearby)))

(defn spawner-dark?
  "May a blaze spawn at light level l: at most the spawner's :light?"
  [l]
  (<= l (:light spawner)))

(defn spawner-spot
  "The cell a try of the spawner at cell [x y z] lands on for rolls rx1 rx2 ry rz1 rz2 in [0, 1),
   as BaseSpawner picks it: x and z each (r1 - r2) times the spawn range (4) from the spawner's
   cell plus a half, floored, so a triangle over nine cells with its peak on the spawner; y a
   uniform one of the cell under, level and over (nextInt(3) - 1)."
  [[x y z] rx1 rx2 ry rz1 rz2]
  (let [range (quot (first (:box spawner)) 2)]
    [(long (math/floor (+ x (* (- rx1 rx2) range) 0.5))) (+ y (long (math/floor (* ry 3))) -1)
     (long (math/floor (+ z (* (- rz1 rz2) range) 0.5)))]))

(defn spawner-fits?
  "May a blaze be placed at cell c: its body free there (stands-in? of its tall cells), dark
   (the model's interior-light), and no other taken (cells already chosen this spawn)?"
  [free? c taken]
  (and (spawner-dark? interior-light) (not (contains? taken c)) (stands-in? free? c (tall :blaze))))

(defn spawner-spawns
  "The cells a spawner at cell sp places blazes in for one spawn: one try for each of tries, each
   try five rolls of rolls ([rx1 rx2 ry rz1 rz2] each, in order), a try whose cell does not fit
   (spawner-fits?) making none. Distinct cells, in the order tried."
  [free? sp tries rolls]
  (reduce (fn [cells rs]
            (let [c (apply spawner-spot sp rs)]
              (cond-> cells (spawner-fits? free? c (set cells)) (conj c))))
          []
          (take tries rolls)))
