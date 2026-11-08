(ns clojurecraft.pearls.sense
  "What the pearl hunt perceives: endermen, who is angry or menaced, shooters, and the state it keeps about them."
  (:require [clojure.math :as math]
            [clojurecraft.armour :as armour]
            [clojurecraft.avoided :as avoided]
            [clojurecraft.blocks :as blocks]
            [clojurecraft.combat :as combat]
            [clojurecraft.drops :as drops]
            [clojurecraft.entity :as entity]
            [clojurecraft.game :as game]
            [clojurecraft.intent :as intent]
            [clojurecraft.memory :as memory]
            [clojurecraft.mob :as mob]
            [clojurecraft.path :as path]
            [clojurecraft.pearls.params :as pparams]
            [clojurecraft.physics :as physics]
            [clojurecraft.recipe :as recipe]
            [clojurecraft.terrain :as terrain]))

(set! *warn-on-reflection* true)

;; ---------------------------------------------------------------- the quarry
(defn endermen
  "{eid entity} of the endermen the hunt may go after: alive (not dead by the server's word), not
   blacklisted (a ghost, an enderman that would not come), within radius (hunt-radius when not
   given) of the feet."
  ([world] (endermen world pparams/hunt-radius))
  ([world radius]
   (let [pos (:player/pos world)
         bl (avoided/all-of world :blacklist)]
     (into {}
           (filter (fn [[eid e]]
                     (and (= :enderman (entity/kind e))
                          (not (entity/dead? e))
                          (not (contains? bl eid))
                          (<= (physics/distance pos (:entity/pos e)) radius))))
           (:world/entities world)))))

(defn at-hand
  "[eid entity] of the enderman the hunt reckons with where the bot stands: the nearest not
   blacklisted when one stands within strike-range, else the nearest of all (blacklisted or not:
   one a held fight gave up on is still angry); nil with none. Not the nearest of all first: a
   blacklisted calm one nearest by a hair would hide the one that is not."
  [world]
  (let [pos (:player/pos world)
        [_ e :as near] (entity/nearest (endermen world) pos)]
    (cond (and e (<= (physics/distance pos (:entity/pos e)) pparams/strike-range)) near
          :else (entity/nearest (endermen (avoided/forget world :blacklist)) pos))))

(defn crowd
  "{eid entity} of the endermen standing within strike-range of the burrow's mouth
   (:pearls/mouth), height counted, blacklisted or not; empty with no mouth. A walk out to one
   would end beside it too near to stare at (stare-from). Never on the plane alone: one under the
   mouth on the floor below a ledge is no crowd. Blacklisted or not: one a held fight gave up on
   is still angry and still hits the bot that steps out to it."
  [world]
  (if-let [m (some-> (:pearls/mouth world)
                     physics/centre)]
    (into {}
          (filter (fn [[_ e]] (<= (physics/distance m (:entity/pos e)) pparams/strike-range)))
          (endermen (avoided/forget world :blacklist)))
    {}))

(defn crowded?
  "Does an enderman stand within strike-range of the burrow's mouth (crowd)?"
  [world]
  (boolean (seq (crowd world))))

(defn blocking
  "[eid entity] of an enderman standing in the burrow's stairs, blacklisted or not: its cell
   below the mouth's height (:pearls/mouth), within burrow-span of the end (:pearls/shelter) and
   not the end; nil when none, or with no burrow. The stairs are one cell wide, so a walk out
   through it stalls on its body and fails :no-path. One out of the sword's reach is waited for
   where the bot stands; one within it is struck."
  [world]
  (let [end (:pearls/shelter world)
        mouth (:pearls/mouth world)]
    (when (and end mouth)
      (entity/nearest (into {}
                            (filter (fn [[_ e]]
                                      (let [c (combat/cell (:entity/pos e))]
                                        (and (< (physics/y-of c) (physics/y-of mouth))
                                             (not= c end)
                                             (<= (physics/distance c end) pparams/burrow-span)))))
                            (endermen (avoided/forget world :blacklist)))
                      (:player/pos world)))))

; ms one fought out is left, as combat leaves one its engage gave up on (combat/given-up?)
(defn fought-out?
  "Have fight-tries or more of the newest sixteen :fight intentions to end :done (memory/
   newest-dones) had eid for their target, it still stands, and the newest of them ended
   within fight-rest-ms? A kill is one fight; one that keeps teleporting out of the tracked
   entities as it is faced ends its fight :done (entity/gone?, taken for a kill) and is fought
   again the next tick it is tracked. Fought out, it is no fight's target and no reason to stay
   (angry-near), as one lure-tries lures did not bring is (lured-out?): the burrow goes stale and
   the hunt moves on. Only for the rest: a minute on, one that is still at hand is fought again."
  [world eid]
  (boolean (and eid
                (let [on-eid (filter #(= eid (:intention/target %))
                                     (memory/newest-dones world :fight (* 2 pparams/fight-tries)))]
                  (and (>= (count on-eid) pparams/fight-tries)
                       (< (- (:time/now world 0) (:intention/at (first on-eid) 0))
                          pparams/fight-rest-ms))))))

(defn unfightable?
  "Is enderman e (id eid) no fight's target now: fought out (fought-out?), given up by a fight's
   engage as not strikable (combat/given-up?: it is not within the sword's reach from here), or
   failed a fight less than fight-retry-ms ago and not within the sword's reach now? A fight that
   fails (:unreachable) blacklists its target, and an angry one is fought regardless of the
   blacklist. Several instant fights on one out of reach count as fought out, so the walk that
   follows is no longer cut by it."
  [world eid e]
  (or (fought-out? world eid)
      (combat/given-up? world eid e)
      (boolean (when-let [at (memory/last-failed-at world :fight eid)]
                 (and (not (combat/in-reach? world e))
                      (or (< (- (:time/now world 0) at) pparams/fight-retry-ms)
                          (>= (memory/failed-count-since world
                                                         :fight
                                                         eid
                                                         (- (:time/now world 0)
                                                            pparams/fight-giveup-ms))
                              pparams/fight-giveups)))))))

(defn lured-out?
  "Has the bot walked lure-tries times out to its burrow's mouth and back since the burrow was cut
   (memory/done-since: two :walk intentions done a lure, every walk since the burrow counted), so
   that a blacklisted or unfightable angry enderman near the mouth that never came is no reason to
   stay?"
  [world]
  (>= (or (memory/done-since world :walk :burrow) 0) (* 2 pparams/lure-tries)))

(defn angry-near
  "[eid entity] of the nearest angry enderman (entity/creepy?), blacklisted or not (not once
   lured-out?), within danger-radius of the burrow's mouth (:pearls/mouth) and danger-rise of its
   height; nil when none, or with no burrow. One a held fight gave up on (:unreachable, then
   blacklisted) is still angry and still hits the bot that walks out. The height counts as the
   radius does, not less. Blacklisted ones only until the bot is lured-out?: one that has not come
   in lure-tries lures cannot reach the mouth."
  [world]
  (when-let [m (:pearls/mouth world)]
    (let [c (physics/centre m)]
      (entity/nearest (into
                       {}
                       (filter (fn [[eid e]]
                                 (let [p (:entity/pos e)]
                                   (and (entity/creepy? e)
                                        (not (fought-out? world eid))
                                        (not (and (lured-out? world) (unfightable? world eid e)))
                                        (<= (physics/distance c p) pparams/danger-radius)
                                        (<= (abs (- (physics/y-of p) (physics/y-of c)))
                                            pparams/danger-rise)))))
                       (endermen (if (lured-out? world) world (avoided/forget world :blacklist))
                                 pparams/stare-range))
                      (:player/pos world)))))

(defn menaced?
  "Does an angry enderman (entity/creepy?), blacklisted or not, stand within danger-radius of the
   feet and danger-rise of their height? Then no drop is walked to or waited for: the hunt meets
   it first."
  [world]
  (let [p (:player/pos world)]
    (boolean (some (fn [[_ e]]
                     (let [q (:entity/pos e)]
                       (and (entity/creepy? e)
                            (<= (physics/distance p q) pparams/danger-radius)
                            (<= (abs (- (physics/y-of q) (physics/y-of p))) pparams/danger-rise))))
                   (endermen (avoided/forget world :blacklist) pparams/stare-range)))))

(defn track-angry
  "The world with :pearls/angry updated: for each angry enderman (entity/creepy?) within stare-range
   {:since :best :closed-at}, :best the nearest it has been to the feet and :closed-at the ms it
   last came close-gain nearer than that; one that moved off by more than four blocks gets a
   farther best, not a newer :closed-at; one no longer angry or tracked is dropped. Pure; kept by
   the :pearls steer. Parked ones are told apart by motion, not by the hunt's bookkeeping."
  [world]
  (let [now (:time/now world 0)
        p (:player/pos world)
        old (:pearls/angry world {})
        angry (into {}
                    (filter (comp entity/creepy? val))
                    (endermen (avoided/forget world :blacklist) pparams/stare-range))]
    (assoc world
           :pearls/angry
           (into {}
                 (for [[eid e] angry
                       :let [d (physics/distance p (:entity/pos e))
                             o (get old eid)]]
                   [eid
                    (cond (nil? o) {:since now :best d :closed-at now}
                          (< d (- (:best o) pparams/close-gain)) (assoc o :best d :closed-at now)
                          (> d (+ (:best o) 4.0)) (assoc o :best d)
                          :else o)])))))

(defn parked?
  "Is angry enderman eid, standing at pos q, parked: more than park-near from the feet and no
   closer than close-gain to its best for park-ms (:pearls/angry)? One that has not been tracked
   long is not."
  [world eid q]
  (let [o (get (:pearls/angry world) eid)]
    (boolean (and o
                  (>= (- (:time/now world 0) (:closed-at o)) pparams/park-ms)
                  (> (physics/distance (:player/pos world) q) pparams/park-near)))))

(defn live-menaced?
  "Is an angry enderman within danger-radius and danger-rise of the feet that is not parked
   (parked?)? Only that holds a wander in."
  [world]
  (let [p (:player/pos world)]
    (boolean (some (fn [[eid e]]
                     (let [q (:entity/pos e)]
                       (and (entity/creepy? e)
                            (<= (physics/distance p q) pparams/danger-radius)
                            (<= (abs (- (physics/y-of q) (physics/y-of p))) pparams/danger-rise)
                            (not (parked? world eid q)))))
                   (endermen (avoided/forget world :blacklist) pparams/stare-range)))))

(defn menace
  "[eid entity] of the nearest angry enderman (entity/creepy?) within park-near (8) blocks and
   danger-rise of the feet, blacklisted or fought out or not, or nil: one on the bot, never
   parked (parked?). The hunt with no burrow cuts one where it stands for it, and takes no
   crowd-site walk. The wander is held in by live-menaced? alone."
  [world]
  (let [p (:player/pos world)]
    (entity/nearest (into {}
                          (filter (fn [[_ e]]
                                    (let [q (:entity/pos e)]
                                      (and (entity/creepy? e)
                                           (<= (physics/distance p q) pparams/park-near)
                                           (<= (abs (- (physics/y-of q) (physics/y-of p)))
                                               pparams/danger-rise)))))
                          (endermen (avoided/forget world :blacklist) pparams/stare-range))
                    p)))

(defn lure?
  "Has the bot stood lure-lookouts or more :lookout intents since its last walk ended
   (memory/done-since), or since the burrow was cut when no walk has ended since? An angry
   enderman near the mouth (angry-near) that will not come down the stairs on its own comes the
   moment the bot shows itself, so after half a minute of it standing there the bot walks to the
   mouth, and back in as soon as it is out (an angry one near sends the bot home from outside),
   the enderman after it down the stairs to the sword."
  [world]
  (>= (or (memory/done-since world :lookout :walk) (memory/done-since world :lookout :burrow) 0)
      pparams/lure-lookouts))

(defn blocked-out?
  "Has the bot stood stale-lookouts in its burrow over an enderman given up on (blacklisted)
   standing in its stairs (blocking), out of the sword's reach and never coming down to it?
   Then that one holds the burrow no longer, as one lure-tries lures did not bring holds it no
   longer (lured-out?): the lure's walks to the mouth are never taken past one standing in
   the stairs, so the lookouts here are the count."
  [world]
  (let [[bid] (blocking world)]
    (boolean (and bid
                  (contains? (avoided/all-of world :blacklist) bid)
                  (>= (or (memory/done-since world :lookout :burrow) 0) pparams/stale-lookouts)))))

(defn stale?
  "Is the hunt's burrow given up: stale-lookouts or more :lookout intents done since it was cut
   (memory/done-since), and no angry enderman near its mouth (angry-near: an angry one still
   hits the bot that steps out, blacklisted or not; a calm one blacklisted as :unprovoked holds
   nothing)? A minute of lookouts is a minute with nothing to provoke from the burrow, in it or
   from a vantage within its reach. Given up, the burrow is no shelter (shelter), so the hunt
   walks to the enderman and cuts a new burrow beside it. One given up on standing in the stairs
   (blocked-out?) no longer keeps it fresh either: the way out is then cut the other way
   (cut-out), never walked through it."
  [world]
  (boolean (and (>= (or (memory/done-since world :lookout :burrow) 0) pparams/stale-lookouts)
                (or (nil? (angry-near world)) (blocked-out? world)))))

; hostile kinds no threat is: the enderman is the quarry, met its own way; a creeper is the :defend row's, backed away from, and a burrow is no place to back off in
(defn threat
  "[eid entity] of the nearest hostile (entity/hostiles) within combat/defend-radius of the feet
   that is of no shunned kind and not blacklisted (one a held fight gave up on, a ghost); nil
   when none. With a burrow near the hunt meets it there, where the :defend row would fight it in
   the open. The burrow is one cell wide and two high: a hoglin or a spider cannot follow, an
   arrow has no line to the end, and a zombie or a piglin comes down the stairs to the sword one
   at a time."
  [world]
  (let [pos (:player/pos world)
        bl (avoided/all-of world :blacklist)]
    (entity/nearest (into {}
                          (filter (fn [[eid e]]
                                    (and (not (contains? bl eid))
                                         (not (contains? pparams/shunned (entity/kind e)))
                                         (<= (physics/distance pos (:entity/pos e))
                                             combat/defend-radius))))
                          (entity/hostiles world))
                    pos)))

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
  "Can a stare from where the bot stands provoke enderman e: its head within provoke-range of the
   eye and no nearer than stare-from, with a clear line to it?"
  [world e]
  (let [eye (game/eye world)
        d (physics/distance eye (head e))]
    (and (<= pparams/stare-from d pparams/provoke-range)
         (mob/sight? (sight-fn world) eye (head e)))))

(defn stare-budget
  "The ms a provoke stares at most in world: stare-ms on a server at 20 ticks a second, and
   stare-ticks of the server's own ticks (:server/tick-ms, as game reads them) when that is
   longer, never past stare-max-ms. The enderman turns once the stare has held over its goal
   passes, which a slow server spaces further apart."
  [world]
  (let [tick (:server/tick-ms world game/server-tick-ms)]
    (long (min pparams/stare-max-ms (max pparams/stare-ms (* pparams/stare-ticks tick))))))

(defn reaches?
  "Can enderman e reach cell to on foot: within danger-radius (16 blocks: past 16 it teleports,
   EndermanLookForPlayerGoal), a route from its cell to to is :found (path/plan). Nil or false
   when no route reaches to within max nodes."
  [world e to]
  (if-not to
    true
    (let [c (if (map? to) (:goal/pos to) to)
          ec (combat/cell (:entity/pos e))
          d (physics/distance (physics/centre c) (:entity/pos e))
          goal (if (map? to) to {:goal/kind :block :goal/pos c})]
      (or (> d pparams/danger-radius)
          (= :found
             (:path/status (path/plan world ec goal {:path/max-nodes (path/budget :reach)})))))))

(defn quarry
  "[eid entity] of the enderman a stare is for: the nearest within stare-range not yet coming
   (entity/creepy?) that is provokable? from where the bot stands (one within 16 blocks and two or
   more below the burrow's mouth only when a route reaches the mouth: within 16 it never teleports,
   EndermanLookForPlayerGoal, and cannot climb a ledge to the mouth), or nil. Not only the
   nearest: one behind a ledge cannot be stared at. One stared at from past 16 blocks teleports
   toward the bot: it need not be walked to."
  [world]
  (let [pos (:player/pos world)
        mouth (:pearls/mouth world)
        in-burrow? (or (= mouth (intent/feet world))
                       (= (:pearls/shelter world) (intent/feet world)))]
    (->> (endermen world pparams/stare-range)
         (remove (comp entity/creepy? val))
         (sort-by (fn [[_ e]] (physics/distance pos (:entity/pos e))))
         (some (fn [[_ e :as ee]]
                 (when (provokable? world e)
                   (if (and in-burrow?
                            mouth
                            (<= (physics/distance (:entity/pos e) (physics/centre mouth))
                                pparams/danger-radius)
                            (< (physics/y-of (:entity/pos e)) (dec (physics/y-of mouth))))
                     (when (reaches? world e mouth) ee)
                     ee)))))))

(defn feet-at
  "The feet point of a bot standing in cell [x y z]: the middle of the cell's floor."
  [[x y z]]
  [(+ x 0.5) y (+ z 0.5)])

; blocks that burn whoever stands in them
(defn burning-at
  "A cell the body overlaps, at the feet's height or the head's, that holds fire (fires), the
   feet's own first, or nil. The body's cells, not the feet's one: the box (physics/half-width
   each way) can reach into a fire in the next cell."
  [world]
  (let [[fx fy fz] (intent/feet world)
        [px _ pz] (:player/pos world)
        span (fn [p c]
               (distinct [c (long (math/floor (- p physics/half-width)))
                          (long (math/floor (+ p physics/half-width)))]))
        cells (for [y [fy (inc fy)] x (span px fx) z (span pz fz)] [x y z])]
    (some (fn [c]
            (when (contains? pparams/fires
                             (some-> (terrain/block-at world c)
                                     blocks/name-of))
              c))
          cells)))

(defn drop-of
  "[eid entity] of a pearl lying round where kill k last saw its target (drops/nearest: within
   its drop-radius, holding a pearl or not yet saying what it holds), nearest the feet, or nil."
  [world k]
  (drops/nearest world pparams/pearl (:intent/target-pos k)))

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

(defn angry-within?
  "Does an angry enderman (entity/creepy?, blacklisted or not, not once lured-out?) stand within
   radius of the feet and danger-rise of their height?"
  [world radius]
  (let [p (:player/pos world)
        bl (if (lured-out? world) world (avoided/forget world :blacklist))]
    (boolean (some (fn [[eid e]]
                     (let [q (:entity/pos e)]
                       (and (entity/creepy? e)
                            (not (and (lured-out? world) (unfightable? world eid e)))
                            (<= (physics/distance p q) radius)
                            (<= (abs (- (physics/y-of q) (physics/y-of p))) pparams/danger-rise))))
                   (endermen bl pparams/stare-range)))))

(defn angry-tracked?
  "Does any angry enderman (entity/creepy?) stand within radius of the feet and danger-rise of
   their height, whatever the hunt thinks of it: blacklisted, fought out, lured out or given up
   (angry-within? leaves the lured-out unfightable ones out, as no reason to stay)? One of those
   still strikes the bot the moment it walks out in the open."
  [world radius]
  (let [p (:player/pos world)]
    (boolean (some (fn [[_ e]]
                     (let [q (:entity/pos e)]
                       (and (entity/creepy? e)
                            (<= (physics/distance p q) radius)
                            (<= (abs (- (physics/y-of q) (physics/y-of p))) pparams/danger-rise))))
                   (endermen (avoided/forget world :blacklist) pparams/stare-range)))))

;; ---------------------------------------------------------------- the plan
(defn at-site?
  "Has the bot just walked to a burrow site: the last intent a :walk for :shelter that ended done?
   There the burrow is cut away from the nearest enderman (at-hand) even with none in sight, so
   the bot does not shuttle between a site and the walk toward a quarry hidden from it."
  [world]
  (let [{:intent/keys [kind for status]} (:plan/last world)]
    (and (= :walk kind) (= :shelter for) (= :done status))))

(defn struck-by
  "[eid e] of the nearest angry enderman (entity/creepy?) within the sword's reach (combat/in-reach?)
   and strike-range of the feet while health is under struck-health, fought out or not, or nil.
   One that has hit the bot and stands where the sword reaches it is fought back, never walked
   away from."
  [world]
  (when (< (:player/health world 20.0) pparams/struck-health)
    (let [pos (:player/pos world)]
      (entity/nearest (into {}
                            (filter (fn [[_ e]]
                                      (and (entity/creepy? e)
                                           (<= (physics/distance pos (:entity/pos e))
                                               pparams/strike-range)
                                           (combat/in-reach? world e))))
                            (endermen (avoided/forget world :blacklist) pparams/strike-range))
                      pos))))

(defn swarm
  "{eid entity} of the monsters (blocks/mobs category) alive within swarm-radius of pos (the feet when
   not given) that are no enderman (the quarry, met its own way from the burrow): a zombified
   piglin counts, hostile on sight or not, as it comes in packs and one hit angers them all."
  ([world] (swarm world (:player/pos world)))
  ([world pos]
   (into {}
         (filter (fn [[_ e]]
                   (and (not (entity/dead? e))
                        (not= :enderman (entity/kind e))
                        (= :monster (:category (blocks/mobs (entity/kind e))))
                        (<= (physics/distance pos (:entity/pos e))
                            (or (:range (mob/kinds (entity/kind e))) pparams/swarm-radius))
                        ;; a blaze behind a wall hits nobody, and travel is gated on swarmed?
                        (or (not (contains? pparams/ranged-kinds (entity/kind e)))
                            (not= pos (:player/pos world))
                            (combat/seen? world e)))))
         (:world/entities world))))

(defn shooters
  "{eid entity} of the ranged-kinds alive within their :range of the feet, in sight or not."
  [world]
  (into {}
        (filter (fn [[_ e]]
                  (and (not (entity/dead? e))
                       (contains? pparams/ranged-kinds (entity/kind e))
                       (<= (physics/distance (:player/pos world) (:entity/pos e))
                           (or (:range (mob/kinds (entity/kind e))) pparams/swarm-radius)))))
        (:world/entities world)))

; blocks over a cell's floor that a standing bot's eye is
(defn unseen-from?
  "Is cell [x y z], a standing eye over it, out of the sight of every entity in shooters (no clear
   line, combat/clear?, to its aim)?"
  [world shooters-seq [x y z]]
  (let [eye [(+ x 0.5) (+ y pparams/eye-height) (+ z 0.5)]]
    (every? #(not (combat/clear? world eye (combat/aim %))) shooters-seq)))

(defn shot-at?
  "Is a blaze or a ghast within its :range (mob/kinds) with a clear line from the eye (combat/seen?)?
   Never fought (they hover out of the sword's reach); only covered from."
  [world]
  (boolean (some (fn [[_ e]]
                   (and (contains? pparams/ranged-kinds (entity/kind e)) (combat/seen? world e)))
                 (swarm world))))

(defn recently-shot?
  "Was a ghast in sight (:pearls/shot-at-ms, shot-at?) less than shot-hold-ms ago? Then no walk
   goes elsewhere, so a hide walk and a travel walk do not undo each other."
  [world]
  (let [t (:pearls/shot-at-ms world)]
    (boolean (and t (< (- (:time/now world 0) t) pparams/shot-hold-ms)))))

(defn weak?
  "Is entity e of a kind mob/kinds knows to hit for weak-melee or less on Normal (a zombie, a
   spider), and no archer? A piglin, a hoglin or a blaze is not."
  [e]
  (let [k (mob/kinds (entity/kind e))]
    (boolean (and k (:melee k) (nil? (:shoot k)) (<= (:melee k) pparams/weak-melee)))))

(defn heavy?
  "Is entity e of a heavy kind (heavy-kinds), or one mob/kinds knows to shoot or to hit for more than
   weak-melee? A kind neither list names (a magma cube) is not: the fight rules' own."
  [e]
  (let [kind (entity/kind e)
        k (mob/kinds kind)]
    (boolean (or (contains? pparams/heavy-kinds kind)
                 (and k (or (:shoot k) (> (or (:melee k) 0) pparams/weak-melee)))))))

(defn swarmed?
  "Are two or more monsters within their reach (swarm), a ghast in sight (shot-at?), one monster
   while health is under danger-health, or one hostile that is heavy? (a piglin, a hoglin)? A
   lone one that is not over that health is met by the fight rules (a magma cube is small and slow)."
  [world]
  (let [sw (swarm world)
        n (count sw)]
    (boolean (or (>= n 2)
                 (and (= 1 n) (< (:player/health world 20.0) pparams/danger-health))
                 (some (fn [[_ e]] (and (entity/hostile? e) (heavy? e))) sw)
                 (shot-at? world)))))

(defn enderman-hit
  "The damage one enderman hit does the bot through the armour it wears (armour/worn over the four
   slots, mob/armour-points, mob/absorbed) of the 7 it does on Normal (mob/kinds :melee): 5.74
   through an iron helmet and chestplate."
  [world]
  (let [[points toughness] (reduce (fn [[pt tg] n]
                                     (let [[dp dt]
                                           (get mob/armour-points (armour/worn world n) [0 0.0])]
                                       [(+ pt dp) (+ tg dt)]))
                                   [0 0.0]
                                   (range 4))]
    (mob/absorbed (:melee (mob/kinds :enderman)) points toughness)))

(defn provoke-line
  "The health under which no enderman is provoked: provoke-margin over two of its hits
   (enderman-hit), at most provoke-health."
  [world]
  (min pparams/provoke-health (+ pparams/provoke-margin (* 2.0 (enderman-hit world)))))

(defn coming?
  "Is the nearest angry enderman (entity/creepy?) within stare-range one the hunt still fights
   (not unfightable?: given up, fought out, cut or failed lately)? No other is provoked while it
   is: one enderman at a time."
  [world]
  (let [[ceid ce] (entity/nearest
                   (into {} (filter (comp entity/creepy? val)) (endermen world pparams/stare-range))
                   (:player/pos world))]
    (boolean (and ce (not (unfightable? world ceid ce))))))

(defn pearls-held
  "The ender pearls held."
  [world]
  (get (recipe/counts (:player/inventory world)) :ender_pearl 0))

(defn beset?
  "Does an angry enderman (entity/creepy?), blacklisted or not, stand within strike-range of the
   feet, height counted? Not one fought out (fought-out?): it has had fight-tries fights to
   come into the open and kill or be killed and has done neither, and the hunt's way on is a
   walk away from it, which an interrupt here would cut every tick."
  [world]
  (let [p (:player/pos world)]
    (boolean (some (fn [[eid e]]
                     (and (entity/creepy? e)
                          (not (fought-out? world eid))
                          (<= (physics/distance p (:entity/pos e)) pparams/strike-range)))
                   (endermen (avoided/forget world :blacklist) pparams/stare-range)))))

(defn in-forest?
  "Is a warped forest chunk known (memory/warped-chunks) within forest-reach chunks of the feet's
   chunk, in the Nether? The hunt's ground: its endermen spawn there and nowhere near a fortress."
  [world]
  (let [[fx _ fz] (intent/feet world)
        cx (math/floor-div (long fx) 16)
        cz (math/floor-div (long fz) 16)]
    (boolean (some (fn [{[x z] :forest/chunk}]
                     (<= (max (abs (- x cx)) (abs (- z cz))) pparams/forest-reach))
                   (memory/warped-chunks world)))))

(defn nearest-forest
  "The pos [x y z] of the nylium block of the nearest known warped forest chunk to the feet whose
   cell above it is not in :plan/blacklist (a walk to it failed), or nil."
  [world]
  (let [feet (intent/feet world)
        bl (avoided/all-of world :blacklist)]
    (->> (memory/warped-chunks world)
         (map :forest/pos)
         (remove #(contains? bl (update % 1 inc)))
         (sort-by #(path/octile feet %))
         first)))
