(ns clojurecraft.sim.mobs
  "Mobs in the model: spawning and despawning, their goals (chase, walk, shoot, volley, blink), what they do to the player and the player to them, the dragon's perch and bite, and the tick that runs them all."
  (:refer-clojure :exclude [send])
  (:require [clojure.math :as math]
            [clojurecraft.blocks :as blocks]
            [clojurecraft.dimension :as dimension]
            [clojurecraft.entity :as entity]
            [clojurecraft.fire :as fire]
            [clojurecraft.hunger :as hunger]
            [clojurecraft.inventory :as inventory]
            [clojurecraft.mob :as mob]
            [clojurecraft.physics :as physics]
            [clojurecraft.recipe :as recipe]
            [clojurecraft.sim.protocol :as sim-protocol :refer
             [health-data hurt spawn-item tell-gear]]
            [clojurecraft.sim.window :as sim-window :refer [full sync-window]]
            [clojurecraft.sim.world :as sim-world :refer
             [add-exhaustion block-at drop-rise held-item held-slot mob-salt on-packet player-eid-of
              roll send tell-level-event tell-sound wear wild-salt]]))

(set! *warn-on-reflection* true)

(def unswell 7.0)                     ; a lit creeper's fuse goes out with the player farther than this (SwellGoal)

(def populate-radius 3)               ; chunks round the player that have had their animals placed

(defn mob-roll
  "[sim' r]: the next roll of the mobs' own stream, r in [0, 1). Spawns, despawns and drops roll
   from :sim/mob-rng (started at :seed mixed with mob-salt), never from the loot's :sim/rng, so
   gravel's flint falls the same with mobs or without."
  [sim]
  (let [[{r :sim/rng} x] (roll {:sim/rng (:sim/mob-rng sim (bit-xor (:sim/rng sim) mob-salt))})]
    [(assoc sim :sim/mob-rng r) x]))

(defn wild-roll
  "[sim' r]: the next roll of the Nether's own spawns' stream, r in [0, 1), from :sim/wild-rng
   (started at :seed mixed with wild-salt, or from the loot's :sim/rng for a model kept before it),
   never from the mobs' :sim/mob-rng, so a try that finds no warped floor costs the mobs' stream
   nothing and a Nether run that meets no warped forest rolls its fortress's spawners as before. The
   endermen it spawns act and despawn by mob-roll, as every mob."
  [sim]
  (let [[{r :sim/rng} x] (roll {:sim/rng (:sim/wild-rng sim (bit-xor (:sim/rng sim) wild-salt))})]
    [(assoc sim :sim/wild-rng r) x]))

(defn overworld?
  "Is the player in the overworld, where the day turns and the night rule spawns?"
  [sim]
  (= dimension/overworld (:sim/dimension sim dimension/overworld)))

(defn column-top
  "The height just over the highest block a column of the player's dimension holds."
  [sim]
  (let [{:dimension/keys [min-y height]} (dimension/by-name (:sim/dimension sim
                                                                            dimension/overworld))]
    (+ min-y height)))

(defn free-fn
  "A cell → may a mob's body be in it: loaded, not solid, not lava (a mob keeps out of lava)."
  [sim]
  (fn [c]
    (let [id (block-at sim c)]
      (boolean (and id (not (blocks/solid? id)) (not (contains? blocks/lava-states id)))))))

(defn solid-fn
  "A cell → does it block a line of sight: solid, or not loaded."
  [sim]
  (fn [c] (let [id (block-at sim c)] (or (nil? id) (blocks/solid? id)))))

(defn yaw-byte
  "The byte add-entity carries yaw degrees in: 1/256 turns, -128 to 127."
  [yaw]
  (let [b (mod (math/round (* yaw (/ 256.0 360.0))) 256)] (if (> b 127) (- b 256) b)))

(defn add-mob
  "The model with a mob of kind standing at pos: its own entity id and the kind's full health,
   the client told (add-entity, then its health). With :persistent? in opts it never despawns
   (a fixture's, a chunk's animals); with :yaw it faces that way, told in the add-entity's
   rotation (the dragon's head, mob/bitten?)."
  [sim kind [x y z :as pos] {:keys [persistent? yaw]}]
  (let [eid (:sim/next-eid sim)
        health (:health (mob/kinds kind))
        add
        {:packet/name :add-entity :entity-id eid :uuid nil :type (mob/type-id kind) :x x :y y :z z}]
    (->
      sim
      (assoc :sim/next-eid (inc eid))
      (assoc-in [:sim/mobs eid]
                (cond-> {:type kind :pos pos :health health}
                  persistent? (assoc :persistent? true)
                  yaw (assoc :yaw (double yaw) :head-byte (yaw-byte yaw))))
      (send
       (cond-> add
         yaw
         (assoc :vel [0.0 0.0 0.0] :pitch 0 :yaw (yaw-byte yaw) :head-yaw (yaw-byte yaw) :data 0)))
      (send (health-data eid health))
      (tell-gear eid kind))))

(def face-radius 24.0)                ; blocks within which a mob turns its head to the player (and the client is told)

(defn face-player
  "The model with mob eid's head turned toward the player (entity/yaw-toward as the game turns
   it), told to the client as rotate-head when the byte it would send changed, as a server sends
   a head turn; unchanged for a dead mob, a passive one, a crystal, the dragon (it flies its own
   way), one beyond face-radius, or one already looking that way."
  [sim eid]
  (let [{:keys [type pos dead-at head-byte] :as m} (get-in sim [:sim/mobs eid])
        k (mob/kinds type)
        player (:sim/player-pos sim)
        b (when (and m player) (yaw-byte (entity/yaw-toward pos player)))]
    (cond (or (nil? b)
              dead-at
              (:passive k)
              (:crystal k)
              (:perched k)
              (> (physics/distance pos player) face-radius)
              (= b head-byte))
          sim
          :else
          (-> sim
              (assoc-in [:sim/mobs eid :head-byte] b)
              (send {:packet/name :rotate-head :entity-id eid :head-yaw b})))))

(defn with-mobs
  "The model with each of mobs ({:type kind :pos [x y z]}, as a fixture gives them, init's :mobs)
   standing where it says, persistent."
  [sim mobs]
  (reduce (fn [sim {:keys [type pos]}] (add-mob sim type pos {:persistent? true})) sim mobs))

(defn remove-mob
  "The model without mob eid, the client told (remove-entities)."
  [sim eid]
  (-> sim
      (update :sim/mobs dissoc eid)
      (send {:packet/name :remove-entities :ids [eid]})))

(def max-delta 32767)                 ; the largest move move-entity-pos carries, in 1/4096 blocks

(defn tell-move
  "The model having told the client entity eid moved from one point to another as the server
   tells it: move-entity-pos with the change in 1/4096 blocks between the points as sent (so the
   client's sum is the model's position), entity-position-sync for a move too long for it (a
   fall), nothing for none."
  [sim eid from [x y z :as to]]
  (let [q #(math/round (* 4096.0 (double %)))
        d (mapv #(- (q %2) (q %1)) from to)]
    (cond
      (= [0 0 0] d) sim
      (some #(> (abs %) max-delta) d)
      (send sim {:packet/name :entity-position-sync :entity-id eid :x x :y y :z z})
      :else
      (let [[dx dy dz] d]
        (send
         sim
         {:packet/name :move-entity-pos :entity-id eid :dx dx :dy dy :dz dz :on-ground true})))))

(defn move-mob
  "The model with mob eid at pos, the client told (tell-move)."
  [sim eid pos]
  (-> sim
      (tell-move eid (get-in sim [:sim/mobs eid :pos]) pos)
      (assoc-in [:sim/mobs eid :pos] pos)))

(defn drop-loot
  "The model with the loot of a mob of kind killed at [x y z]: each row's count rolled
   (mob/loot-count), each lying where it fell as an item entity."
  [sim kind [x y z]]
  (reduce (fn [sim row]
            (let [[sim r] (mob-roll sim)
                  n (mob/loot-count row r)]
              (cond-> sim
                (pos? n) (spawn-item [x (+ y drop-rise) z] {:item (mob/loot-item row) :count n}))))
          sim
          (:loot (mob/kinds kind))))

(defn armoured
  "What is left of a mob's hit of amount on the player once the armour it wears in window 0's four
   armour slots takes its share (mob/absorbed over the pieces' summed points and toughness,
   mob/armour-points)."
  [sim amount]
  (let [[points toughness]
        (reduce (fn [[p t] n]
                  (let [[dp dt] (get mob/armour-points
                                     (some->> (get-in sim
                                                      [:sim/inv (inventory/slot [:armour n]) :item])
                                              recipe/item-name)
                                     [0 0.0])]
                    [(+ p dp) (+ t dt)]))
                [0 0.0]
                (range 4))]
    (mob/absorbed amount points toughness)))

(defn blast
  "Mob eid blows up with its kind's power (a creeper's fuse, a crystal hit): it is gone, and the
   player is hurt by the blast (mob/blast-damage by its distance from the mob's feet, scaled by
   the difficulty) when the line from the mob's head to the player's eye is clear, unless a
   raised shield faces it (sim-protocol/shield-blocks?, counted blocked). A blast breaks no blocks
   here (not modelled)."
  [sim eid]
  (let [{:keys [type pos]} (get-in sim [:sim/mobs eid])
        player (:sim/player-pos sim)
        dmg
        (if player (mob/blast-damage (:blast (mob/kinds type)) (physics/distance pos player)) 0.0)]
    (cond (not (and (pos? dmg)
                    (mob/sight? (solid-fn sim) (mob/head type pos) (physics/eye player))))
          (remove-mob sim eid)
          (sim-protocol/shield-blocks? sim pos)
          (sim-protocol/blocked (remove-mob sim eid) type :blast)
          :else
          (-> sim
              (remove-mob eid)
              (hurt (armoured sim (mob/scaled dmg (:sim/difficulty sim))) true type nil eid)))))

(defn split
  "The model with what a mob of kind dying at pos leaves (:split [kind lo hi]): lo to hi of kind,
   rolled from the mobs' stream (mob/loot-count), each a quarter of the dead one's width off its
   feet on x and on z by its place in the litter (Slime.remove: 2 + nextInt(3), offsets of a
   quarter of the size), facing the player as any hostile; none for a kind with no :split."
  [sim kind [x y z]]
  (let [[child lo hi] (:split (mob/kinds kind))
        q (/ (first (:box (mob/kinds kind))) 4.0)]
    (cond (nil? child) sim
          :else
          (let [[sim r] (mob-roll sim)
                n (mob/loot-count [child lo hi] r)]
            (reduce (fn [sim i]
                      (add-mob sim
                               child
                               [(+ x (* q (dec (* 2 (mod i 2))))) y
                                (+ z (* q (dec (* 2 (mod (quot i 2) 2)))))]
                               {}))
                    sim
                    (range n))))))

(defn die-mob
  "Mob eid at zero health: a crystal blasts (blast); any other dies, the client told
   (entity-event entity/death-status), its loot dropped when the player killed it (drop-loot), what
   it splits into standing where it died (split, a magma cube's), and it is removed once its death
   is over (act-mob)."
  [sim eid by-player?]
  (let [{:keys [type pos]} (get-in sim [:sim/mobs eid])]
    (cond (:crystal (mob/kinds type)) (blast sim eid)
          :else
          (-> sim
              (assoc-in [:sim/mobs eid :dead-at] (:sim/now sim))
              (send {:packet/name :entity-event :entity-id eid :status entity/death-status})
              (cond-> by-player? (drop-loot type pos))
              (split type pos)))))

(defn anger
  "The model with mob eid turned on the player (:angry?). An enderman shows it as the server does
   when it takes a target: its creepy flag set (entity/creepy-index, set-entity-data), the fact the
   bot's hunt reads to know one is coming. Once only."
  [sim eid]
  (let [{:keys [type angry?]} (get-in sim [:sim/mobs eid])]
    (cond angry? sim
          :else
          (cond-> (assoc-in sim [:sim/mobs eid :angry?] true)
            (= :enderman type)
            (-> (send {:packet/name :set-entity-data
                       :entity-id eid
                       :metadata {entity/creepy-index true}})
                (tell-sound :entity.enderman.stare (:pos (get-in sim [:sim/mobs eid]))))))))

(defn hurt-mob
  "The model after mob eid takes amount of damage, by-player? when the player dealt it: within
   mob/hurt-window-ms of its last hurt only a harder one lands, for its excess (a swing inside
   the window does nothing, the siblings' 40 swings in 66 ms); its health told (set-entity-data);
   hurt by the player it turns on the player (:angry?); at zero it dies (die-mob)."
  [sim eid amount by-player?]
  (let [{:keys [hit-at last-hit health]} (get-in sim [:sim/mobs eid])
        now (:sim/now sim)
        window? (and hit-at (< (- now hit-at) mob/hurt-window-ms))
        left (max 0.0 (- health (if window? (- amount last-hit) amount)))]
    (cond (and window? (<= amount last-hit)) sim
          :else
          (-> sim
              (update-in [:sim/mobs eid]
                         assoc
                         :health left
                         :hit-at (if window? hit-at now)
                         :last-hit amount)
              (cond-> by-player? (anger eid))
              (send (health-data eid left))
              ;; the hurt the server shows every client tracking the mob (a damage-event naming
              ;; the mob, never hurt-animation, which names only the player's own entity): the
              ;; bot tells a swing that landed by it (game.clj, :entity/hurt-at)
              (send {:packet/name :damage-event
                     :entity-id eid
                     :source-type (get mob/damage-source (if by-player? :player_attack :on_fire))
                     :source-cause (if by-player? (inc (player-eid-of sim)) 0)
                     :source-direct (if by-player? (inc (player-eid-of sim)) 0)})
              (cond-> (<= left 0.0) (die-mob eid by-player?))))))

(defn wear-weapon
  "The model after a hit with the held item: a sword wears one, any other tool two (the game's
   weapon and tool components), the client told what window 0 holds; anything else untouched."
  [sim]
  (let [slot (held-slot sim)
        kind (:kind (blocks/tool (held-item sim)))
        before (full (:sim/inv sim) (inventory/layouts :inventory))]
    (cond (nil? kind) sim
          :else
          (-> sim
              (update :sim/inv wear slot)
              (cond-> (not= kind :sword) (update :sim/inv wear slot))
              (sync-window :inventory before (:sim/cursor sim) false)))))

(defmethod on-packet [:play :attack]
  ;; the player's hit (775's attack packet): a living mob whose box is in reach of the eye
  ;; (mob/attack-reach) is hurt by the held item (mob/weapon-damage) less its armour's share
  ;; (mob/mob-hit), the item worn, the hit's
  ;; exhaustion spent; out of reach, dead or unknown, nothing, as the server ignores it
  [sim {:keys [entity-id]}]
  (let [m (get-in sim [:sim/mobs entity-id])
        player (:sim/player-pos sim)]
    (cond (or (nil? m)
              (:dead-at m)
              (nil? player)
              (> (mob/box-distance (physics/eye player) (:type m) (:pos m)) mob/attack-reach))
          sim
          :else
          (let [dmg (mob/mob-hit (:type m) (mob/weapon-damage (held-item sim)))]
            (-> sim
                (add-exhaustion (hunger/exhaustion :attack))
                wear-weapon
                (hurt-mob entity-id dmg true))))))

(defn burn
  "An undead mob in the overworld's daylight with no roof over its head (mob/roofed?) burns:
   mob/burn-damage every mob/burn-every ticks, no loot (the player did not kill it). Only in a
   model that keeps the day (:sim/day-time)."
  [sim eid]
  (let [{:keys [type pos]} (get-in sim [:sim/mobs eid])
        day (:sim/day-time sim)]
    (cond (not (and day
                    (:undead (mob/kinds type))
                    (overworld? sim)
                    (zero? (mod (quot (:sim/now sim) physics/tick-ms) mob/burn-every))
                    (not (mob/night? day))
                    (not (mob/roofed? #(block-at sim %)
                                      (mob/cell-of (mob/head type pos))
                                      (column-top sim)))))
          sim
          :else (hurt-mob sim eid mob/burn-damage false))))

(defn provoke
  "An enderman the player stares at (mob/stared?, from the look the client last reported) turns
   on the player."
  [sim eid]
  (let [{:keys [type pos angry?]} (get-in sim [:sim/mobs eid])
        look (:sim/player-look sim)]
    (cond (or angry? (not (:neutral (mob/kinds type))) (nil? look)) sim
          (mob/stared? (solid-fn sim) (physics/eye (:sim/player-pos sim)) look pos) (anger sim eid)
          :else sim)))

(defn targets?
  "Does mob m have the player as its target: the player in its follow range, and, for a neutral
   kind, only once hurt or stared at (:angry?)? Never for a kind with no follow range (an animal:
   a cow, a pig), which targets nothing."
  [sim {:keys [type pos angry?]}]
  (let [k (mob/kinds type)
        follow (:follow k)]
    (boolean (and follow
                  (<= (physics/distance pos (:sim/player-pos sim)) follow)
                  (or (not (:neutral k)) angry?)))))

(defn trail
  "Mob eid of a kind that finds its way (:paths, the enderman) a tick on toward the player,
   stopping stop short of it on the plane: along its route (:route) while it has one, a cell
   dropped once its feet stand on that cell's centre and the route dropped when a step there
   leaves it where it stood; with none, straight (mob/step-toward); each step held off the walls
   by its half width (mob/keep-off-walls). A straight step that leaves
   it where it stood with the player out of its reach (mob/in-reach?: a wall, a drop, the player
   above or below) searches a route toward the player (mob/route), at most once every
   mob/route-every ticks (:route-at), walked from the next tick."
  [sim eid stop]
  (let [{:keys [type pos route route-at]} (get-in sim [:sim/mobs eid])
        speed (:speed (mob/kinds type))
        n (mob/tall type)
        free? (free-fn sim)
        player (:sim/player-pos sim)
        now (:sim/now sim)
        [wx wy wz :as w] (first route)]
    (cond w
          (let [centre [(+ wx 0.5) (double wy) (+ wz 0.5)]
                to (mob/keep-off-walls free? type (mob/step-toward free? pos centre speed 0.0 n) n)
                left (cond (and (= w (mob/cell-of to)) (< (mob/flat-distance to centre) 1e-3))
                           (subvec route 1)
                           (= to pos) []
                           :else route)]
            (cond-> (move-mob sim eid to)
              (seq left) (assoc-in [:sim/mobs eid :route] left)
              (empty? left) (update-in [:sim/mobs eid] dissoc :route)))
          :else
          (let [to (mob/keep-off-walls free? type (mob/step-toward free? pos player speed stop n) n)
                stuck? (and (= to pos)
                            (not (mob/in-reach? type pos player))
                            (>= now (+ (or route-at 0) (* mob/route-every physics/tick-ms))))
                found (when stuck? (mob/route free? (mob/cell-of pos) player n))]
            (cond-> (move-mob sim eid to)
              stuck? (assoc-in [:sim/mobs eid :route-at] now)
              (seq found) (assoc-in [:sim/mobs eid :route] found))))))

(def sight-ms 250)                     ; a flier's line to the player is looked at this often (five ticks), not every tick: forty blocks of cells a look

(def lost-sight 5)                     ; ticks BlazeAttackGoal still moves after the line goes (lastSeen < 5). Inside two blocks with no line it returns at once. A blaze that has never seen the player has no target (NearestAttackableTargetGoal's mustSee) and does not use these ticks.

(defn sees-player
  "[sim' sees?]: does mob eid have a clear line from its head to the player's eye? Looked at when
   the last look is sight-ms old or older (kept in the mob, :sight [ms sees?]), so a blaze that
   flies and shoots does not walk the line twice a tick; a look kept by a tick or two is the
   server's own (Sensing caches a mob's sight for a tick)."
  [sim eid]
  (let [{:keys [type pos sight]} (get-in sim [:sim/mobs eid])
        now (:sim/now sim)]
    (cond (and sight (< (- now (first sight)) sight-ms)) [sim (second sight)]
          :else
          (let [ok? (boolean (mob/sight? (solid-fn sim)
                                         (mob/head type pos)
                                         (physics/eye (:sim/player-pos sim))))]
            [(assoc-in sim [:sim/mobs eid :sight] [now ok?]) ok?]))))

(defn fly
  "Mob eid, a flier (a blaze), one tick of BlazeAttackGoal's movement (26.1.2
   Blaze$BlazeAttackGoal.tick). Distance squared under 4 (inside :reach) and no line: it stays,
   and it does not shoot. Inside :reach with a line: it closes on the feet and strike melees.
   At :reach or beyond, inside follow, with a line: it stays and volley shoots. With no line,
   and only after it has seen the player, it keeps closing for lost-sight ticks, then stays.
   It does not fly at a player it has never seen, so a sealed pocket does not pull the
   spawner's blazes out of the cap box."
  [sim eid]
  (let [{:keys [type pos seen? lost]} (get-in sim [:sim/mobs eid])
        k (mob/kinds type)
        player (:sim/player-pos sim)]
    (cond (:sim/still-blazes? sim) sim
          :else
          (let [[sim sees?] (sees-player sim eid)
                d (physics/distance pos player)
                close? (< d (:reach k))
                seen?' (boolean (or seen? sees?))
                lost' (cond sees? 0
                            seen?' (inc (or lost 0))
                            :else lost-sight)
                sim (-> sim
                        (assoc-in [:sim/mobs eid :seen?] seen?')
                        (assoc-in [:sim/mobs eid :lost] lost'))
                toward #(move-mob
                         %
                         eid
                         (mob/fly-toward (free-fn %) pos player (:speed k) (mob/tall type)))]
            (cond (and close? (not sees?)) sim
                  (and sees? close?) (toward sim)
                  (and sees? (<= d (:follow k))) sim
                  (and seen?' (< lost' lost-sight)) (toward sim)
                  :else sim)))))

(defn walk
  "Mob eid a tick on toward the player (mob/step-toward, held off the walls by its half width:
   mob/keep-off-walls), stopping where its kind fights from: a
   melee mob inside its reach, a shooter at :keep, a creeper a block off; one that does not walk
   (a blaze hovers) stays; one that finds its way (:paths) walks round what stops it (trail)."
  [sim eid]
  (let [{:keys [type pos]} (get-in sim [:sim/mobs eid])
        k (mob/kinds type)
        ;; :contact on a ground mob (a magma cube) is its hit: not :melee, so the bot's pack
        ;; line does not read it, and it still stops inside its reach
        stop (cond (or (:melee k) (and (:contact k) (not (:flies k)))) (* 0.8 (:reach k))
                   (:shoot k) (:keep k)
                   :else 1.0)]
    (cond (not (pos? (:speed k 0.0))) sim
          (:flies k) (fly sim eid)
          (:paths k) (trail sim eid stop)
          :else
          (let [free? (free-fn sim)
                n (mob/tall type)]
            (move-mob sim
                      eid
                      (mob/keep-off-walls
                       free?
                       type
                       (mob/step-toward free? pos (:sim/player-pos sim) (:speed k) stop n)
                       n))))))

(defn teleport-rolls
  "[sim' rs]: the next n rolls of the mobs' own stream (mob-roll), in order."
  [sim n]
  (reduce (fn [[sim rs] _] (let [[sim r] (mob-roll sim)] [sim (conj rs r)])) [sim []] (range n)))

(defn teleport-mob
  "[sim' landed?]: mob eid where its teleport aimed at point lands (mob/landing over the
   dimension's height, a cell over its top air), its route dropped, the client told (tell-move:
   a jump this long goes as entity-position-sync), and true; where it lands nowhere, the model
   as it was and false."
  [sim eid point]
  (let [top (column-top sim)
        at #(if (>= (second %) top) 0 (block-at sim %))
        min-y (:dimension/min-y (dimension/by-name (:sim/dimension sim dimension/overworld)))
        to (mob/landing at point min-y)]
    (cond (nil? to) [sim false]
          :else
          [(-> sim
               (tell-sound :entity.enderman.teleport (get-in sim [:sim/mobs eid :pos]))
               (move-mob eid to)
               (tell-sound :entity.enderman.teleport to)
               (update-in [:sim/mobs eid] dissoc :route)) true])))

(defn blink
  "Mob eid's teleports at the player it has as its target, for a kind that teleports
   (:teleports, the enderman; EndermanLookForPlayerGoal.tick): stared at (mob/stared?) its far
   count (:far-ticks) goes to zero, and within mob/teleport-near it teleports away (mob/away-aim,
   EnderMan.teleport); not stared at and farther than mob/teleport-far, the count grows a tick,
   and from mob/teleport-wait on it teleports toward the player (mob/toward-aim,
   EnderMan.teleportTowards), the count back to zero where it lands and kept where it does not,
   so it tries again the next tick; nearer, the count stays. Every aim takes three rolls of
   mob-roll; any other kind, the model untouched."
  [sim eid]
  (let [{:keys [type pos far-ticks]} (get-in sim [:sim/mobs eid])
        player (:sim/player-pos sim)
        look (:sim/player-look sim)
        far (or far-ticks 0)
        stared? (and look
                     (:teleports (mob/kinds type))
                     (mob/stared? (solid-fn sim) (physics/eye player) look pos))
        d (physics/distance pos player)]
    (cond (not (:teleports (mob/kinds type))) sim
          (and stared? (< d mob/teleport-near))
          (let [[sim [r1 r2 r3]] (teleport-rolls (assoc-in sim [:sim/mobs eid :far-ticks] 0) 3)]
            (first (teleport-mob sim eid (mob/away-aim pos r1 r2 r3))))
          stared? (assoc-in sim [:sim/mobs eid :far-ticks] 0)
          (<= d mob/teleport-far) sim
          (< far mob/teleport-wait) (assoc-in sim [:sim/mobs eid :far-ticks] (inc far))
          :else
          (let [[sim [r1 r2 r3]] (teleport-rolls sim 3)
                [sim landed?]
                (teleport-mob sim eid (mob/toward-aim pos (physics/eye player) r1 r2 r3))]
            (cond-> sim landed? (assoc-in [:sim/mobs eid :far-ticks] 0))))))

(defn tell-damage
  "The model with a damage-event for the player queued: the hurt's source type (mob/damage-source of
   cause, generic for one not there) and the mob that dealt it (eid, its id + 1 as the cause, as the
   server numbers it; direct the same, a projectile's own id + 1), nil for none. The bot reads the
   strike from it (:player/hurt-at, combat/striker; game/hit-of names the attacker by the direct id,
   which was sent bare before and named the entity before the attacker)."
  ([sim cause eid] (tell-damage sim cause eid eid))
  ([sim cause eid direct]
   (send sim
         {:packet/name :damage-event
          :entity-id (player-eid-of sim)
          :source-type (get mob/damage-source cause 18)
          :source-cause (if eid (inc eid) 0)
          :source-direct (if direct (inc direct) 0)})))

(defn tell-flags
  "The model with the player's shared flags byte told to the client (set-entity-data): bits, the
   on-fire bit while it burns, 0 when it is out."
  [sim bits]
  (send sim
        {:packet/name :set-entity-data
         :entity-id (player-eid-of sim)
         :metadata {entity/flags-index bits}}))

(defn ignite-player
  "The model with the player set alight for ms (fire/ignite), the flags byte told when it was not
   burning."
  [sim ms]
  (let [fresh? (nil? (:sim/fire sim))]
    (cond-> (update sim :sim/fire fire/ignite (:sim/now sim) ms)
      fresh? (tell-flags entity/on-fire-bit))))

(defn tick-burn
  "One game tick of the player's burning (fire/tick): a hurt of fire/burn-damage when one is due
   (on_fire, told as a damage-event, armour takes none of it), the flags byte told when a fire block
   lit it and when it ended or water put it out. A dead player burns no more."
  [sim]
  (let [pos (:sim/player-pos sim)
        id (when pos (block-at sim (mapv #(long (math/floor %)) pos)))
        env {:water? (boolean (and id (contains? blocks/water-states id)))
             :flame? (boolean (and id (fire/flame? id)))}
        before (:sim/fire sim)
        [after event] (fire/tick before (:sim/now sim) env)]
    (cond (<= (:sim/health sim) 0.0) (dissoc sim :sim/fire)
          :else
          (cond-> (if after (assoc sim :sim/fire after) (dissoc sim :sim/fire))
            (and after (nil? before)) (tell-flags entity/on-fire-bit)
            (= :out event) (tell-flags 0)
            (= :hurt event)
            (-> (hurt fire/burn-damage true :on_fire)
                (tell-damage :on_fire nil))))))

(defn in-lava?
  "Are the player's feet in lava? The cell the feet stand in (block-at of the floored
   position), which is where a body in a source stands."
  [sim]
  (when-let [pos (:sim/player-pos sim)]
    (contains? blocks/lava-states (block-at sim (mapv #(long (math/floor (double %))) pos)))))

(defn tick-lava
  "One game tick of the player standing in lava (Entity.lavaHurt): fire/lava-damage, armour
   takes none of it, and the player is set alight for fire/lava-ms. The hurt window lands the
   four once a half-second; a swallowed one is not told. Runs after tick-burn, so the tick that
   first steps in deals the four and not also that tick's burn. A dead player is left."
  [sim]
  (cond (or (<= (:sim/health sim) 0.0) (not (in-lava? sim))) sim
        :else
        (let [before (:sim/health sim)
              sim (hurt sim fire/lava-damage false :lava)]
          (cond-> (ignite-player sim fire/lava-ms)
            (< (:sim/health sim) before) (tell-damage :lava nil)))))

(defn tick-wither
  "One game tick of the wither a skeleton put on the player (mob/wither-tick): mob/wither-damage
   when one is due, armour takes none of it, told as a damage-event. The wither skeleton is the
   cause, so a death by it classes as :wither. A dead player is left."
  [sim]
  (let [[after event] (mob/wither-tick (:sim/wither sim) (:sim/now sim))]
    (cond (<= (:sim/health sim) 0.0) (dissoc sim :sim/wither)
          :else
          (cond-> (if after (assoc sim :sim/wither after) (dissoc sim :sim/wither))
            (= :hurt event)
            (-> (hurt mob/wither-damage false :wither_skeleton :wither)
                (tell-damage :wither nil))))))

(defn strike
  "A melee mob (:melee) whose attack box meets the player's with a clear line from its head
   (mob/melee-reaches?, vanilla's box grown attack-pad sideways, not a radius) hits it every
   :every ticks for its damage scaled by the difficulty; a jumper (:hop, a magma cube) as high
   as its hop with a clear line (mob/hop-reach?); any other ground :contact mob within its :reach
   on the plane, feet within 1.5 in height (mob/in-reach?). A flier's strike is
   its :contact (a blaze's 6 every :contact-every ticks) and only when the boxes touch with a
   clear line (mob/can-strike?): a separate key, because the bot's own tables read :melee as a
   kind it fights in a pack (combat/melee-damage), and a reach of two through a wall is not a hit.
   A raised shield facing the mob takes the hit (sim-protocol/shield-blocks?): nothing lands,
   counted blocked."
  [sim eid]
  (let [{:keys [type pos attack-at]} (get-in sim [:sim/mobs eid])
        k (mob/kinds type)
        player (:sim/player-pos sim)
        now (:sim/now sim)
        can? (cond (:flies k) (mob/can-strike? (solid-fn sim) type pos player)
                   (:melee k) (mob/melee-reaches? (solid-fn sim) type pos player)
                   (:hop k) (mob/hop-reach? (solid-fn sim) type pos player)
                   :else (mob/in-reach? type pos player))]
    (cond (not (and (or (:melee k) (:contact k)) can? (>= now (or attack-at 0)))) sim
          (sim-protocol/shield-blocks? sim pos)
          (-> sim
              (assoc-in [:sim/mobs eid :attack-at]
                        (+ now (* (or (:contact-every k) (:every k)) physics/tick-ms)))
              (sim-protocol/blocked type :melee))
          :else
          (let [before (:sim/health sim)
                sim (-> sim
                        (assoc-in [:sim/mobs eid :attack-at]
                                  (+ now (* (or (:contact-every k) (:every k)) physics/tick-ms)))
                        (hurt (armoured sim
                                        (mob/scaled (or (:melee k) (:contact k))
                                                    (:sim/difficulty sim)))
                              true
                              type
                              nil
                              eid)
                        (tell-damage :mob_attack eid))]
            ;; the wither only when the hit landed: a swallowed swing applies none
            (cond-> sim
              (and (:wither k) (< (:sim/health sim) before))
              (update :sim/wither mob/wither-apply now))))))

(def fireball-type (get blocks/entity-types :small_fireball)) ; add-entity's type for a blaze's fireball

(defn spawn-fireball
  "The model with a small fireball leaving blaze eid for the player's middle (mob/aim: four rolls of
   the mobs' stream for the error), told to the client as the server does (add-entity with the
   direction times 0.1 and its blaze as data, then its motion)."
  [sim eid]
  (let [{:keys [type pos]} (get-in sim [:sim/mobs eid])
        [x y z] pos
        from [x (+ y (:start-height mob/fireball)) z]
        [px py pz] (:sim/player-pos sim)
        [sim r1] (mob-roll sim)
        [sim r2] (mob-roll sim)
        [sim r3] (mob-roll sim)
        [sim r4] (mob-roll sim)
        dir (mob/aim from [px (+ py 0.9) pz] r1 r2 r3 r4)
        fid (:sim/next-eid sim)]
    (-> sim
        (assoc :sim/next-eid (inc fid))
        (assoc-in [:sim/fireballs fid]
                  {:pos from :dir dir :owner eid :born (:sim/now sim) :kind type})
        (tell-level-event 1018 pos)
        (send {:packet/name :add-entity
               :entity-id fid
               :uuid nil
               :type fireball-type
               :x (first from)
               :y (second from)
               :z (last from)
               :vel (mapv #(* % (:vel-scale mob/fireball)) dir)
               :pitch 0
               :yaw 0
               :head-yaw 0
               :data eid}))))

(defn volley
  "Blaze eid's turn to shoot, as BlazeAttackGoal runs it: at least :reach out (distance squared
   of 4; inside that, seen, it melees, and with no line it stays), within its range and a clear
   line from its head to the player's eye, and its timer due, one step of its volley
   (mob/volley-next): the charge flag raised for the charge, a fireball at each of the shots
   after it, the flag lowered and the rest. Off the line nothing moves on; the timer waits. A
   blaze within two blocks of a sealed bot has no line, so it does not fire."
  [sim eid]
  (let [{:keys [type pos shot-step shot-at]} (get-in sim [:sim/mobs eid])
        k (mob/kinds type)
        player (:sim/player-pos sim)
        eye (physics/eye player)
        now (:sim/now sim)
        near? (and (>= now (or shot-at 0))
                   (>= (physics/distance pos player) (:reach k))
                   (<= (physics/distance (mob/head type pos) eye) (:range k)))
        [sim sees?] (if near? (sees-player sim eid) [sim false])]
    (cond (not (and near? sees?)) sim
          :else
          (let [[step wait charged? fire?] (mob/volley-next (:volley k) (or shot-step 0))]
            (cond-> (-> sim
                        (assoc-in [:sim/mobs eid :shot-step] step)
                        (assoc-in [:sim/mobs eid :shot-at] (+ now (* wait physics/tick-ms)))
                        (send {:packet/name :set-entity-data
                               :entity-id eid
                               :metadata {entity/blaze-flags-index (if charged? 1 0)}}))
              fire? (spawn-fireball eid))))))

(defn shoot
  "A shooter (a skeleton's arrow, a blaze's fireball) within its :range of the player, with a
   clear line from its head to the player's eye, hits every :every ticks for its damage scaled by
   the difficulty; the shot's flight is not modelled, it lands as it is fired, or on a raised
   shield facing the shooter (sim-protocol/shield-blocks?, counted blocked)."
  [sim eid]
  (let [{:keys [type pos attack-at]} (get-in sim [:sim/mobs eid])
        k (mob/kinds type)
        eye (physics/eye (:sim/player-pos sim))
        now (:sim/now sim)]
    (cond (:volley k) (volley sim eid)
          (not (and (:shoot k)
                    (>= now (or attack-at 0))
                    (<= (physics/distance (mob/head type pos) eye) (:range k))
                    (mob/sight? (solid-fn sim) (mob/head type pos) eye)))
          sim
          (sim-protocol/shield-blocks? sim pos)
          (-> sim
              (assoc-in [:sim/mobs eid :attack-at] (+ now (* (:every k) physics/tick-ms)))
              (sim-protocol/blocked type (or (:projectile k) :shot)))
          :else
          (-> sim
              (assoc-in [:sim/mobs eid :attack-at] (+ now (* (:every k) physics/tick-ms)))
              (hurt (armoured sim (mob/scaled (:shoot k) (:sim/difficulty sim)))
                    true
                    type
                    (:projectile k)
                    eid)
              (tell-damage (or (:projectile k) type) eid)
              (cond-> (:ignites k) (ignite-player (:ignites k)))))))

(defn swell
  "A creeper within :swell of the player lights its fuse; with the player past unswell the fuse
   goes out; :fuse ticks after it was lit, it blasts (blast)."
  [sim eid]
  (let [{:keys [type pos fuse-at]} (get-in sim [:sim/mobs eid])
        k (mob/kinds type)
        d (physics/distance pos (:sim/player-pos sim))
        now (:sim/now sim)]
    (cond (not (:fuse k)) sim
          (and fuse-at (> d unswell)) (update-in sim [:sim/mobs eid] dissoc :fuse-at)
          (and fuse-at (>= (- now fuse-at) (* (:fuse k) physics/tick-ms))) (blast sim eid)
          (and (nil? fuse-at) (<= d (:swell k))) (assoc-in sim [:sim/mobs eid :fuse-at] now)
          :else sim)))

(defn chase
  "A hostile's turn at a living player: an enderman stared at turns (provoke); with the player as
   its target (targets?) it teleports by its kind (blink), walks, then hits, shoots or swells by
   its kind."
  [sim eid]
  (let [player (:sim/player-pos sim)
        sim (if player (provoke sim eid) sim)
        m (get-in sim [:sim/mobs eid])]
    (cond (or (nil? player) (<= (:sim/health sim) 0.0) (not (targets? sim m))) sim
          :else
          (reduce (fn [sim f] (if (get-in sim [:sim/mobs eid]) (f sim eid) sim))
                  sim
                  [blink walk strike shoot swell]))))

(def dragon-heal-every 10)            ; game ticks between a crystal's heals of the dragon (EnderDragon.checkCrystals)

(defn bite
  "The perched dragon eid's head and neck: with a yaw, a player within them (mob/bitten?) is hurt
   mob/bite-damage, scaled by the difficulty and its armour, every mob/bite-every ticks (the
   tick's sync-health tells it)."
  [sim eid]
  (let [{:keys [pos yaw attack-at]} (get-in sim [:sim/mobs eid])
        player (:sim/player-pos sim)
        now (:sim/now sim)]
    (cond (not (and yaw player (mob/bitten? pos yaw player) (>= now (or attack-at 0)))) sim
          :else
          (-> sim
              (assoc-in [:sim/mobs eid :attack-at] (+ now (* mob/bite-every physics/tick-ms)))
              (hurt (armoured sim (mob/scaled mob/bite-damage (:sim/difficulty sim)))
                    true
                    :ender_dragon
                    nil
                    eid)))))

(defn perch
  "The perched dragon's tick: its bite, and while any crystal stands, one health back every
   dragon-heal-every ticks up to its kind's full health, told (set-entity-data)."
  [sim eid]
  (let [{:keys [health]} (get-in sim [:sim/mobs eid])
        full-health (:health (mob/kinds :ender_dragon))
        crystal? (some #(and (= :end_crystal (:type %)) (not (:dead-at %))) (vals (:sim/mobs sim)))]
    (cond (not (and crystal?
                    (< health full-health)
                    (zero? (mod (quot (:sim/now sim) physics/tick-ms) dragon-heal-every))))
          sim
          :else
          (let [h (min full-health (inc health))]
            (-> sim
                (assoc-in [:sim/mobs eid :health] h)
                (send (health-data eid h)))))))

(defn act-mob
  "One game tick of mob eid: a dead one is removed once its death is over (mob/death-ticks, the
   dragon's mob/dragon-death-ticks); the perched dragon heals from its crystals (perch); an
   animal and a crystal stand; any other
   burns in daylight when undead (burn) and goes after the player (chase)."
  [sim eid]
  (let [{:keys [type dead-at] :as m} (get-in sim [:sim/mobs eid])
        k (mob/kinds type)
        over (* physics/tick-ms (if (= :ender_dragon type) mob/dragon-death-ticks mob/death-ticks))]
    (cond (nil? m) sim
          dead-at (cond-> sim (>= (- (:sim/now sim) dead-at) over) (remove-mob eid))
          (:perched k) (perch (bite sim eid) eid)
          (or (:passive k) (:crystal k)) sim
          :else
          (let [sim (burn sim eid)
                burnt (get-in sim [:sim/mobs eid])]
            ;; one the sun just killed is dying, not chasing
            (cond-> sim (and burnt (not (:dead-at burnt))) (chase eid))))))

(defn fireball-path
  "What one tick does to a small fireball at pos flying along dir (mob/fireball :speed a tick, in
   three samples): [:wall last-free-pos] on the first solid cell, [:hit] when a sample in air is
   inside the living player's box (grown by the fireball's radius), or [:fly pos'] to where it
   ends the tick. The solid cell wins when a sample is in both, so a shot stops at a wall and
   never hurts through it."
  [sim pos dir]
  (let [{:keys [speed radius]} mob/fireball
        player (:sim/player-pos sim)
        alive? (pos? (:sim/health sim))
        solid? (solid-fn sim)]
    (reduce (fn [[_ last] i]
              (let [p (mapv + pos (map #(* % speed (/ i 3.0)) dir))]
                (cond (solid? (mob/cell-of p)) (reduced [:wall last])
                      (and alive? player (mob/hits-box? p player radius)) (reduced [:hit p])
                      :else [:fly p])))
            [:fly pos]
            [1 2 3])))

(defn set-fire
  "The model with a fire block in cell if it is air over a solid floor (a small fireball's end,
   FireBlock canSurvive on the floor), told to the client: for good over netherrack or magma, else
   until fire/block-until (kept in :sim/fires)."
  [sim cell]
  (let [below (mapv + cell [0 -1 0])
        base (some-> (block-at sim below)
                     blocks/name-of)
        id (blocks/default-state :fire)]
    (cond (not (and (= :air (blocks/name-of (block-at sim cell)))
                    (some-> (block-at sim below)
                            blocks/solid?)))
          sim
          :else
          (let [until (fire/block-until base (:sim/now sim))]
            (cond-> (-> sim
                        (assoc-in [:sim/placed cell] id)
                        (send {:packet/name :block-update :pos cell :state id}))
              until (assoc-in [:sim/fires cell] until))))))

(defn tick-fires
  "One game tick of the fire blocks fireballs lit (:sim/fires {cell ms}): one past its time is air
   again, told to the client."
  [sim]
  (reduce (fn [sim [cell until]]
            (cond-> sim
              (>= (:sim/now sim) until)
              (-> (update :sim/fires dissoc cell)
                  (assoc-in [:sim/placed cell] 0)
                  (send {:packet/name :block-update :pos cell :state 0}))))
          sim
          (:sim/fires sim)))

(defn tick-fireballs
  "One game tick of every small fireball in flight (fireball-path), each move told (tell-move): one
   that hits the player hurts it by its blaze's shot damage through the armour, sets it alight for
   the blaze's :ignites and tells the damage, unless a raised shield faces it
   (sim-protocol/shield-blocks?: gone, counted blocked); one that meets a block or is older than its life is
   gone, told to the client."
  [sim]
  (reduce (fn [sim [fid {:keys [pos dir owner born kind]}]]
            (let [[what at] (fireball-path sim pos dir)
                  k (mob/kinds kind)
                  old? (>= (- (:sim/now sim) born) (* (:life mob/fireball) physics/tick-ms))
                  gone (fn [sim]
                         (-> sim
                             (update :sim/fireballs dissoc fid)
                             (send {:packet/name :remove-entities :ids [fid]})))]
              (cond (and (= :hit what) (sim-protocol/shield-blocks? sim at))
                    (sim-protocol/blocked (gone sim) kind :fireball)
                    (= :hit what)
                    (-> sim
                        gone
                        (hurt (armoured sim (mob/scaled (:shoot k) (:sim/difficulty sim)))
                              true kind
                              :fireball owner)
                        (tell-damage :fireball owner fid)
                        (ignite-player (:ignites k)))
                    (= :wall what)
                    (-> sim
                        gone
                        (set-fire (mob/cell-of at)))
                    old? (gone sim)
                    :else
                    (-> sim
                        (tell-move fid pos at)
                        (assoc-in [:sim/fireballs fid :pos] at)))))
          sim
          (sort-by key (:sim/fireballs sim))))
