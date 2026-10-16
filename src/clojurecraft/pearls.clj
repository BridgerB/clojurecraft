(ns clojurecraft.pearls
  "Ender pearls by hunting endermen (issue #263), never by bartering: bartering takes a stack of
   gold the run does not have. The game's loot table (data/minecraft/loot_table/entities/
   enderman.json, probe run 38889277228) gives one roll of ender_pearl, its count uniform 0 to 1
   (plus 0 to 1 a level of looting): a kill drops a pearl one time in two, so twelve pearls are
   about twenty-four kills. A warped forest spawns endermen and nothing else hostile (its biome
   file's monster spawner, the same probe), so that is where the Nether leg hunts.

   Requiring this namespace registers the :pearls plan and the :provoke and :burrow intents. A
   row planned by :pearls (the :ender-pearls stage, the gym's :ender-pearl trial) takes, in order:
   the pearl of a kill just made (picked up by #262's :collect-drops, which reads what a drop
   holds, so a carried block's drop is left); the drop still to come while the body lies there (a
   wait); then the fight, from a burrow (hunt). An enderman hits for 7 a second on Normal, 5.74
   through iron, against forty health a sword takes seven hits to spend: in the open the bot
   loses (gym batch pearls-263a, plan run 38892130785: five live trials, five deaths). So the
   hunt digs a burrow first, three stairs down and a tunnel two high (:burrow); an enderman, 2.9
   tall, follows down the stairs and stops at the tunnel's mouth, two cells from the bot, where
   its reach (its box grown 0.83 sideways, never up or down) cannot touch the bot and the
   sword's can touch it (shelter?). From the burrow's mouth the bot provokes one with a stare,
   goes back to the end, and fights it there without a step (:fight :intent/hold?).

   An enderman is neutral (entity/neutral): it attacks only once looked in the face or hit. Its
   rules, read from the game's classes (EnderMan, EndermanLookForPlayerGoal, the same probe):
   stared at it turns on the player; an angry one stared at within 4 blocks teleports away, and
   one farther than 16 teleports toward the player; hit by a living entity it never teleports
   (only a projectile or a non-living source moves it). So the stare is from range only, and the
   fight's look is combat/aim, its legs, never its head. Water hurts it and it teleports out of
   rain and water: the trap rows already keep the bot from fighting while it stands in water."
  (:require [clojure.math :as math]
            [clojure.string :as str]
            [clojurecraft.blocks :as blocks]
            [clojurecraft.combat :as combat]
            [clojurecraft.drops :as drops]
            [clojurecraft.entity :as entity]
            [clojurecraft.game :as game]
            [clojurecraft.intent :as intent]
            [clojurecraft.mob :as mob]
            [clojurecraft.physics :as physics]
            [clojurecraft.plan :as plan]
            [clojurecraft.recipe :as recipe]
            [clojurecraft.stone :as stone]
            [clojurecraft.terrain :as terrain]))

(set! *warn-on-reflection* true)

(def pearl :ender_pearl)               ; the item a kill may drop

(def hunt-radius 32.0)                ; blocks from the feet an enderman is hunted within
(def seek-radius 64.0)                ; blocks from the feet an enderman is walked toward, none nearer
(def lookout-ms 5000)                 ; ms a :lookout stands turning before the plan looks again
(def lookout-turn 0.09)               ; degrees of yaw a :lookout turns a ms: a full turn in four seconds
(def strike-range 4.5)                ; blocks on the plane within which the hunt fights (hunt/strike-range: past a walk's reach)
(def stare-range 64.0)                ; blocks to the head within which a provoke stares, with a clear line: the enderman's follow range
(def stare-from 6.0)                  ; never stare nearer: an angry enderman stared at within 4 teleports away
(def stare-ms 3000)                   ; ms a provoke stares at most before the enderman says it has a target (creepy)
(def burrow-depth 3)                  ; stairs down a burrow cuts: from the third, the ground roofs a tunnel two high
(def burrow-length 2)                 ; tunnel steps past the stairs: two keep the bot out of an enderman's reach from the last stair
(def shelter-reach 16.0)              ; blocks from the feet a burrow is still the hunt's shelter; farther, a new one is dug
(def candidates 3)                    ; nearest endermen asked for a route, as the other acts ask

(defn cell "The block cell a point is in." [p] (mapv #(long (math/floor %)) p))

;; ---------------------------------------------------------------- the quarry

(defn endermen
  "{eid entity} of the endermen the hunt may go after: alive (not dead by the server's word), not
   blacklisted (a ghost, an enderman that would not come), within radius (hunt-radius when not
   given) of the feet."
  ([world] (endermen world hunt-radius))
  ([world radius]
   (let [pos (:player/pos world)
         bl (:plan/blacklist world #{})]
     (into {}
           (filter (fn [[eid e]]
                     (and (= :enderman (entity/kind e))
                          (not (entity/dead? e))
                          (not (contains? bl eid))
                          (<= (physics/distance pos (:entity/pos e)) radius))))
           (:world/entities world)))))

(defn head
  "The point an enderman at pos is stared at: its eye, as the server's stare test measures it
   (mob/head, the model's)."
  [e]
  (mob/head :enderman (:entity/pos e)))

(defn sight-fn
  "A cell [x y z] → does it block the eye's line (terrain/solid-fn: unloaded counts as solid)."
  [world]
  (let [solid? (terrain/solid-fn world)] (fn [[x y z]] (solid? x y z))))

(defn provokable?
  "Can a stare from where the bot stands provoke enderman e: its head within stare-range of the
   eye and no nearer than stare-from, with a clear line to it?"
  [world e]
  (let [eye (game/eye world)
        d (physics/distance eye (head e))]
    (and (<= stare-from d stare-range) (mob/sight? (sight-fn world) eye (head e)))))

(defn quarry
  "[eid entity] of the enderman a stare is for: the nearest within stare-range not yet coming
   (entity/creepy?) that is provokable? from where the bot stands, or nil. Not only the nearest:
   gym batch pearls-263d (plan run 38899294690, probe 38904622614) tracked seventeen endermen 30
   to 80 blocks round each warped forest landing, none within a route's reach, and stood looking
   round for forty minutes while the nearest, behind a ledge, could not be stared at. One stared
   at from past 16 blocks teleports toward the bot (EndermanLookForPlayerGoal): it need not be
   walked to."
  [world]
  (let [pos (:player/pos world)]
    (->> (endermen world stare-range)
         (remove (comp entity/creepy? val))
         (sort-by (fn [[_ e]] (physics/distance pos (:entity/pos e))))
         (some (fn [[_ e :as ee]] (when (provokable? world e) ee))))))

(defn routable
  "[eid entity] of the first of the candidates nearest endermen a route reaches
   (intent/routable?), or nil."
  [world ms]
  (let [pos (:player/pos world)]
    (->> ms
         (sort-by (fn [[_ e]] (physics/distance pos (:entity/pos e))))
         (take candidates)
         (some (fn [[_ e :as ee]]
                 (when (intent/routable? world
                                         {:intent/kind :walk :intent/target (cell (:entity/pos e))})
                   ee))))))

;; ---------------------------------------------------------------- the kill and its drop

(defn fresh-kill
  "The hunt's kill whose drop may still be picked up: the last intent, when it is a :fight for
   :pearls that ended done, with where its target was last seen, and the target is gone (dead or
   removed): a fight broken off under 10 health is done too, its enderman alive, and is no kill.
   nil otherwise."
  [world]
  (let [l (:plan/last world)]
    (when (and (= :fight (:intent/kind l))
               (= :pearls (:intent/for l))
               (:intent/target-pos l)
               (entity/gone? world (:intent/target l)))
      l)))

(defn drop-of
  "[eid entity] of a pearl lying round where kill k last saw its target (drops/nearest: within
   its drop-radius, holding a pearl or not yet saying what it holds), nearest the feet, or nil."
  [world k]
  (drops/nearest world pearl (:intent/target-pos k)))

(defn body?
  "Does the body of kill k still lie in the world (dead, not yet removed)? The server drops the
   loot as the enderman dies and removes the body a second later (LivingEntity.tickDeath), so
   while it lies there a drop not yet seen may still come."
  [world k]
  (contains? (:world/entities world) (:intent/target k)))

;; ---------------------------------------------------------------- the provoke

(defn look-at
  "Controls that stand still and look from the eye at point."
  [world point]
  {:control/look (physics/look-at (game/eye world) point)})

(defmethod intent/run :provoke
  ;; {:intent/kind :provoke :intent/target eid}: a stare at its head until the server says it has
  ;; a target (entity/creepy?), or it comes nearer than stare-from on its own: done, and the plan
  ;; takes the bot to its burrow to meet it; stare-ms with neither, failed :unprovoked (the
  ;; planner blacklists it). Done at once when it is gone. The look leaves its head the moment it
  ;; is provoked: stared at within 16 an angry enderman stands frozen, within 4 it teleports away
  [world i _]
  (let [now (:time/now world)
        i (cond-> i (nil? (:intent/since i)) (assoc :intent/since now))
        world (assoc world :plan/intent i)
        e (get-in world [:world/entities (:intent/target i)])]
    (cond (entity/gone? world (:intent/target i))
          (-> world
              (assoc :player/controls {})
              intent/done)
          (or (entity/creepy? e) (< (physics/distance (game/eye world) (head e)) stare-from))
          (-> world
              (assoc :player/controls (look-at world (combat/aim e)))
              intent/done)
          (> (- now (:intent/since i)) stare-ms)
          (-> world
              (assoc :player/controls {})
              (intent/fail :unprovoked))
          :else (assoc world :player/controls (look-at world (head e))))))

;; ---------------------------------------------------------------- the burrow

(defn solid-at?
  "Is cell c solid, or not loaded (what the bot does not know is not a place to stand in)?"
  [world c]
  (let [id (terrain/block-at world c)] (or (nil? id) (blocks/solid? id))))

(defn stand?
  "Could an enderman stand with its feet in cell [x y z]: a solid floor under it and the three
   cells it fills (mob/tall: 2.9 high) open?"
  [world [x y z]]
  (and (solid-at? world [x (dec y) z])
       (not-any? #(solid-at? world [x (+ y %) z]) (range (mob/tall :enderman)))))

(defn shelter?
  "Can the bot standing with its feet in cell [x y z] not be hit by an enderman: its feet and head
   cells open, and no cell an enderman could stand in (stand?) in the nine columns round it at the
   heights whose body would overlap the bot's (feet two under to one over). A mob's melee reaches
   its own box grown 0.83 sideways and not at all up or down (Mob.getAttackBoundingBox, probe run
   38893918055), so an enderman two cells off on the plane, or wholly above or below, cannot
   reach; one cell off it always can. Gym batch pearls-263a (plan run 38892130785): five of five
   live trials died fighting in the open in iron, the enderman landing 5.74 a second."
  [world [x y z]]
  (and (not (solid-at? world [x y z]))
       (not (solid-at? world [x (inc y) z]))
       (not-any? #(stand? world %)
                 (for [dx [-1 0 1] dz [-1 0 1] dy [-2 -1 0 1]] [(+ x dx) (+ y dy) (+ z dz)]))))

(defn shelter
  "The hunt's burrow (:pearls/shelter) while it is within shelter-reach of the feet and still a
   shelter? by the terrain, else nil."
  [world]
  (let [s (:pearls/shelter world)]
    (when (and s (<= (physics/distance (intent/feet world) s) shelter-reach) (shelter? world s))
      s)))

(defn away
  "The heading [dx dz] of the four straight ones nearest to the way from entity e to the feet: a
   burrow cut that way opens its stairs toward e, which comes down them to the tunnel's mouth."
  [world e]
  (let [[px _ pz] (:player/pos world)
        [ex _ ez] (:entity/pos e)
        dx (- px ex)
        dz (- pz ez)]
    (if (>= (abs dx) (abs dz)) [(if (neg? dx) -1 1) 0] [0 (if (neg? dz) -1 1)])))

(defn pickaxe?
  "Does the bot hold any pickaxe, which every stair of a burrow through rock needs (a stair the
   held tool cannot drop is refused, stairs.clj)?"
  [world]
  (boolean (some (fn [[n c]] (and (pos? c) (str/ends-with? (name n) "_pickaxe")))
                 (recipe/counts (:player/inventory world)))))

(defmethod intent/run :burrow
  ;; {:intent/kind :burrow :intent/dir [dx dz]}: stairs down burrow-depth (stairs.clj, refusing
  ;; water, lava and drops, turning where refused), then a tunnel burrow-length on along the
  ;; heading the stairs ended on: the stairs are three high, so an enderman follows down them,
  ;; and the tunnel two high, so it stops at its mouth, two cells from the bot. Done when the
  ;; feet stand in a shelter?, kept as :pearls/shelter, with where it began as :pearls/mouth;
  ;; failed :no-shelter when they do not, or with a child's reason
  [world {:intent/keys [stage dir mouth] :as i} event]
  (let [mouth (or mouth (intent/feet world))
        i (assoc i :intent/mouth mouth :intent/stage (or stage :down))
        world (assoc world :plan/intent i)]
    (case (:intent/stage i)
      :down
      (stone/link
       world
       i
       event
       {:intent/kind :stairs-down
        :intent/to-y (- (physics/y-of mouth) burrow-depth)
        :intent/dir dir}
       (fn [w child]
         (intent/assoc-intent w :intent/stage :along :intent/dir (:intent/dir child dir))))
      :along
      (stone/link world
                  i
                  event
                  {:intent/kind :tunnel :intent/dir dir :intent/length burrow-length}
                  (fn [w _]
                    (let [feet (intent/feet w)]
                      (cond (shelter? w feet)
                            (-> w
                                (assoc :pearls/shelter feet :pearls/mouth mouth :player/controls {})
                                intent/done)
                            :else (intent/fail w :no-shelter))))))))

;; ---------------------------------------------------------------- the lookout

(defmethod intent/run :lookout
  ;; {:intent/kind :lookout}: stand, the eyes level and turning (lookout-turn), for lookout-ms,
  ;; then done: the hunt with nothing to go after waits as an intent, not a planner wait, whose
  ;; 400 ticks fail the plan. Gym batch pearls-263c (plan run 38897897563): the warped forest
  ;; landings failed :no-enderman and :no-route in 40 s, before any enderman had come. A stare
  ;; it turns onto by chance provokes one, which is what the hunt wants
  [world i _]
  (let [now (:time/now world)
        since (or (:intent/since i) now)
        world (intent/assoc-intent world :intent/since since)]
    (cond (>= (- now since) lookout-ms)
          (-> world
              (assoc :player/controls {})
              intent/done)
          :else
          (assoc world
                 :player/controls
                 {:control/look [(mod (* lookout-turn (- now since)) 360.0) 0.0]}))))

;; ---------------------------------------------------------------- the plan

(defn hunt
  "The hunt's next step with no kill to pick up, in order: out of the burrow (shelter) with one
   coming (creepy), one at hand, or under 10 health, back to it (gym batch pearls-263b: a bot
   that met one in the open, or backed away hurt, died); in it, the one coming or one at hand
   fought where the bot stands (:fight :intent/hold?); with no burrow, one at hand fought, or
   backed away from under 10 health; no burrow near, one dug (:burrow, a pickaxe held) away from
   the nearest enderman; in the burrow with none coming, out to its mouth to look round; one not
   yet coming and provokable? from here within stare-range (quarry), provoked (never one already coming: a provoke of it
   ends at once, and batch pearls-263b's bot, its pickaxe lost, provoked one 26 times in a row);
   one coming and no burrow to meet it in, fought; else a walk to the nearest within seek-radius a
   route reaches; else a :lookout, standing where it is: no enderman yet is no failure (batch
   pearls-263c's warped forest landings failed :no-enderman and :no-route in 40 s)."
  [world]
  (let [pos (:player/pos world)
        ms (endermen world)
        [eid e] (entity/nearest ms pos)
        [ceid ce] (entity/nearest
                   (into {} (filter (comp entity/creepy? val)) (endermen world stare-range))
                   pos)
        near? (and e (<= (combat/horizontal world e) strike-range))
        hurt? (< (:player/health world 20.0) combat/retreat-health)
        home (shelter world)
        in? (and home (= home (intent/feet world)))
        [qid] (quarry world)]
    (cond
      (and home (not in?) (or ce near? hurt?))
      {:intent/kind :walk :intent/target home :intent/for :shelter}
      (and in? (or ce near?))
      {:intent/kind :fight :intent/target (if ce ceid eid) :intent/hold? true :intent/for :pearls}
      (and near? hurt?) {:intent/kind :back-off :intent/target eid :intent/for :pearls}
      near? {:intent/kind :fight :intent/target eid :intent/for :pearls}
      (and (nil? home) (pickaxe? world))
      {:intent/kind :burrow :intent/dir (if e (away world e) [1 0]) :intent/for :pearls}
      (and in? (:pearls/mouth world))
      {:intent/kind :walk :intent/target (:pearls/mouth world) :intent/for :shelter}
      qid {:intent/kind :provoke :intent/target qid :intent/for :pearls}
      ce {:intent/kind :fight :intent/target ceid :intent/for :pearls}
      :else
      (if-let [[_ far] (routable world (endermen world seek-radius))]
        {:intent/kind :walk :intent/target (cell (:entity/pos far)) :intent/for :pearls}
        {:intent/kind :lookout :intent/for :pearls}))))

(defmethod plan/next-intent :pearls
  [world _ _]
  (let [k (fresh-kill world)
        [_ d] (when k (drop-of world k))]
    (cond d
          {:intent/kind :collect-drops
           :intent/item pearl
           :intent/target (cell (:entity/pos d))
           :intent/for :pearls}
          (and k (body? world k)) {:plan/wait :drop}
          :else (hunt world))))
