(ns clojurecraft.pearls
  "Ender pearls by hunting endermen (issue #263), never by bartering: bartering takes a stack of
   gold the run does not have. The game's loot table (data/minecraft/loot_table/entities/
   enderman.json, probe run 38889277228) gives one roll of ender_pearl, its count uniform 0 to 1
   (plus 0 to 1 a level of looting): a kill drops a pearl one time in two, so twelve pearls are
   about twenty-four kills. A warped forest spawns endermen and nothing else hostile (its biome
   file's monster spawner, the same probe), so that is where the Nether leg hunts.

   Requiring this namespace registers the :pearls plan and the :provoke intent. A row planned by
   :pearls (the :ender-pearls stage, the gym's :ender-pearl trial) takes, in order: the pearl of a
   kill just made (an item lying where the enderman died, collected); the drop still to come while
   the body lies there (a wait); an enderman within strike-range, fought (combat's :fight, the
   sword, 650 ms apart, a break-off under 10 health), or backed away from when the health is
   already under 10; one in sight within stare-range, provoked (:provoke) so it comes to the bot
   over ground the bot need not route; else a walk to the nearest a route reaches.

   An enderman is neutral (entity/neutral): it attacks only once looked in the face or hit. Its
   rules, read from the game's classes (EnderMan, EndermanLookForPlayerGoal, the same probe):
   stared at it turns on the player; an angry one stared at within 4 blocks teleports away, and
   one farther than 16 teleports toward the player; hit by a living entity it never teleports
   (only a projectile or a non-living source moves it). So the stare is from range only, and the
   fight's look is combat/aim, its legs, never its head. Water hurts it and it teleports out of
   rain and water: the trap rows already keep the bot from fighting while it stands in water."
  (:require [clojure.math :as math]
            [clojurecraft.combat :as combat]
            [clojurecraft.entity :as entity]
            [clojurecraft.game :as game]
            [clojurecraft.intent :as intent]
            [clojurecraft.mob :as mob]
            [clojurecraft.physics :as physics]
            [clojurecraft.plan :as plan]
            [clojurecraft.recipe :as recipe]
            [clojurecraft.terrain :as terrain]))

(set! *warn-on-reflection* true)

(def pearl (recipe/item-id :ender_pearl)) ; the item a kill may drop
(def drops "What a pearl collect waits for." #{pearl})

(def hunt-radius 32.0)                ; blocks from the feet an enderman is hunted within
(def strike-range 4.5)                ; blocks on the plane within which the hunt fights (hunt/strike-range: past a walk's reach)
(def stare-range 24.0)                ; blocks to the head within which a provoke stares, with a clear line
(def stare-from 6.0)                  ; never stare nearer: an angry enderman stared at within 4 teleports away
(def stare-ms 1000)                   ; ms a provoke stares: past the goal's 5-tick aggro delay with the server's lag to spare
(def come-ms 12000)                   ; ms after the stare a provoked enderman has to come within strike-range
(def drop-reach 3.0)                  ; blocks from where the kill was seen last that an item counts as its drop
(def candidates 3)                    ; nearest endermen asked for a route, as the other acts ask

(defn cell "The block cell a point is in." [p] (mapv #(long (math/floor %)) p))

;; ---------------------------------------------------------------- the quarry

(defn endermen
  "{eid entity} of the endermen the hunt may go after: alive (not dead by the server's word), not
   blacklisted (a ghost, an enderman that would not come), within hunt-radius of the feet."
  [world]
  (let [pos (:player/pos world)
        bl (:plan/blacklist world #{})]
    (into {}
          (filter (fn [[eid e]]
                    (and (= :enderman (entity/kind e))
                         (not (entity/dead? e))
                         (not (contains? bl eid))
                         (<= (physics/distance pos (:entity/pos e)) hunt-radius))))
          (:world/entities world))))

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
  "The item entity lying within drop-reach of where kill k last saw its target, nearest it, or
   nil. The bot cannot tell a pearl from another item (an item's stack is in metadata the skim
   does not read), so any item there is taken for the drop."
  [world k]
  (let [at (:intent/target-pos k)]
    (->> (:world/entities world)
         vals
         (filter entity/item?)
         (map (fn [e] [(physics/distance at (:entity/pos e)) e]))
         (filter (fn [[d _]] (<= d drop-reach)))
         (sort-by first)
         first
         second)))

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
  ;; {:intent/kind :provoke :intent/target eid}: :stare at its head for stare-ms (it turns on the
  ;; bot), then :wait looking at its legs (combat/aim: a stare frozen on it holds it still within
  ;; 16 blocks, and within 4 sends it away) until it comes within strike-range, done; come-ms at
  ;; most, failed :unprovoked (the planner blacklists it). Done at once when it is gone. It
  ;; leaves the stare early when the enderman comes nearer than stare-from on its own
  [world i _]
  (let [now (:time/now world)
        i (cond-> i (nil? (:intent/stage i)) (assoc :intent/stage :stare :intent/since now))
        world (assoc world :plan/intent i)
        e (get-in world [:world/entities (:intent/target i)])
        {:intent/keys [stage since]} i]
    (cond (entity/gone? world (:intent/target i))
          (-> world
              (assoc :player/controls {})
              intent/done)
          (<= (combat/horizontal world e) strike-range)
          (-> world
              (assoc :player/controls (look-at world (combat/aim e)))
              intent/done)
          (and (= :stare stage)
               (or (>= (- now since) stare-ms)
                   (< (physics/distance (game/eye world) (head e)) stare-from)))
          (-> world
              (assoc :player/controls (look-at world (combat/aim e)))
              (intent/assoc-intent :intent/stage :wait :intent/since now))
          (= :stare stage) (assoc world :player/controls (look-at world (head e)))
          (> (- now since) come-ms)
          (-> world
              (assoc :player/controls {})
              (intent/fail :unprovoked))
          :else (assoc world :player/controls (look-at world (combat/aim e))))))

;; ---------------------------------------------------------------- the plan

(defn hunt
  "The hunt's next step with no kill to pick up: toward the nearest of the endermen (endermen),
   by the rules in this namespace's docstring; a wait when there is none."
  [world]
  (let [ms (endermen world)
        [eid e] (entity/nearest ms (:player/pos world))
        near? (and e (<= (combat/horizontal world e) strike-range))]
    (cond (nil? e) {:plan/wait :no-enderman}
          (and near? (< (:player/health world 20.0) combat/retreat-health))
          {:intent/kind :back-off :intent/target eid :intent/for :pearls}
          near? {:intent/kind :fight :intent/target eid :intent/for :pearls}
          (provokable? world e) {:intent/kind :provoke :intent/target eid :intent/for :pearls}
          :else
          (if-let [[_ far] (routable world ms)]
            {:intent/kind :walk :intent/target (cell (:entity/pos far)) :intent/for :pearls}
            {:plan/wait :no-route}))))

(defmethod plan/next-intent :pearls
  [world _ _]
  (let [k (fresh-kill world)
        d (when k (drop-of world k))]
    (cond d
          {:intent/kind :collect
           :intent/target (cell (:entity/pos d))
           :intent/drops drops
           :intent/for :pearls}
          (and k (body? world k)) {:plan/wait :drop}
          :else (hunt world))))
