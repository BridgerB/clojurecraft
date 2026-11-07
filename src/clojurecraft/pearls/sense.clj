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
   one a held fight gave up on is still angry, and batch pearls-warped-b's bots walked out to it
   and were slain); nil with none. Not the nearest of all first: gym batch pearls-warped7-a
   (plan run 39714009099, probe 39720871893) run 4 had three endermen in one spot 3.8 blocks
   off, two of them calm and blacklisted as :unprovoked and one not, and with the blacklisted
   one nearest by a hair no burrow was cut for the one that was not, and the walk to it was
   planned 18,760 times over the run's last fifteen minutes."
  [world]
  (let [pos (:player/pos world)
        [_ e :as near] (entity/nearest (endermen world) pos)]
    (cond (and e (<= (physics/distance pos (:entity/pos e)) pparams/strike-range)) near
          :else (entity/nearest (endermen (avoided/forget world :blacklist)) pos))))

(defn crowd
  "{eid entity} of the endermen standing within strike-range of the burrow's mouth
   (:pearls/mouth), height counted, blacklisted or not; empty with no mouth. A walk out to one
   would end beside it too near to stare at (stare-from), and the hunt would walk back in: batch
   pearls-warped-b's run 2 went from the end to the mouth and back for two minutes while one
   stood there. Never on the plane alone: one under the mouth on the floor below a ledge is no
   crowd (batch pearls-warped-d run 1's thirty-four minutes in the burrow). Blacklisted or not:
   one a held fight gave up on (:unreachable, then blacklisted) is still angry and still hits
   the bot that steps out to it (batch pearls-warped-b, plan run 39135767198: two landings in
   three left the burrow after a held fight timed out and were slain by that enderman within
   seconds)."
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
   not the end; nil when none, or with no burrow. The stairs are one cell wide: a walk out
   through it stalls on its body and fails :no-path, and three fail the plan (gym batch
   pearls-warped4-a, plan run 39544677155, run 2 at 426 s). One out of the sword's reach is
   waited for where the bot stands; one within it is struck."
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
   within fight-rest-ms? A kill is one fight; one that keeps teleporting out of the
   tracked entities as it is faced ends its fight :done (entity/gone?, taken for a kill)
   and is fought again the next tick it is tracked: gym batch pearls-w3 (plan run
   40434266158) run 3 planned some sixty-four thousand fights on one enderman from its
   second minute to the five-hour timeout, nineteen of them failing :unreachable and the
   rest ending done, and held one pearl. Fought out, it is no fight's target and no
   reason to stay (angry-near), as one lure-tries lures did not bring is (lured-out?):
   the burrow goes stale and the hunt moves on. Only for the rest: sim gate r3fr3b (probe
   40470765785, world R4 seed 3) left one fought out for good, and it teleported after
   the bot and killed it 188 times while the hunt backed off and never fought back; a
   minute on, one that is still at hand is fought again."
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
   blacklist: runs 2 and 3 of gym plan run 40775811459 failed :fight :unreachable eight times and
   held a fight thirteen minutes.
   Run 4 of gym plan run 40747177526 (issue #263, pearls-12) planned :fight on one eight blocks
   below it eight times in 10 ms, each given up and ended done at once, and the eight counted as
   fought out: the walk that followed was no longer cut by it, and the bot walked on while it
   took 20 health in five seconds, with no attack sent."
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
   lured-out?), within
   danger-radius of the burrow's mouth (:pearls/mouth) and danger-rise of its height; nil when
   none, or with no burrow. One a held fight gave up on (:unreachable, then blacklisted) is
   still angry and still hits the bot that walks out: gym batch pearls-warped5-a (plan run
   39625347094, probe 39668407179) run 4 held a fight on one at 1498 s that never came, gave
   the burrow up a minute later with it standing 7 blocks off (no crowd: past strike-range of
   the mouth), walked out toward another and was slain by it at 1640 s. The height counts as
   the radius does, not less: batch pearls-warped6-a (plan run 39687964414, probe 39699149771)
   run 4's held fight gave up on one standing by the mouth, which then stood 3 to 9 blocks off
   on the plane and 5 or 6 below it, on the slope, past the four the rise then allowed; the
   burrow went stale three times, and each walk out cost the bot half its health, the third its
   life at 597 s. Batch pearls-warped5-b's run 2 was slain the same way by one six below.
   Blacklisted ones only until the bot is lured-out?: one that has not come in lure-tries lures
   cannot reach the mouth (batch pearls-warped6-a's run 4 came the moment the bot stepped out), and
   gym batch pearls-warped8-a (plan run 39731473452, probe 39776733945) run 1 walked to the mouth
   and back every 34 s for four hours, its burrow never stale, over one blacklisted after a held
   fight timed out :unreachable, standing 4.2 blocks from the mouth and never moving."
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
   it first. Sim probe 39885574495 (world R2, seed 5): two provoked at once, one killed, and the
   walk to its pearl, out of the burrow, was slain in five seconds by the other, beside it."
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
   the :pearls steer. Churn-guard's pearl-fight-a job 3 (issue #263) died to a blacklisted angry
   enderman that stalked at 6 to 19 blocks for 30 s and then hit five times; travel-b job 6 had
   one parked at 12 blocks for 41 minutes: told apart by motion, not by the hunt's bookkeeping."
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
   crowd-site walk. Pearl-fight-a job 3 (plan run 41143668588) died to one that stalked at 6 to
   19 blocks for 30 s. The wander is held in by live-menaced? alone: travel-b job 6 stood 41
   minutes on one parked at 12."
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
   moment the bot shows itself (gym batch pearls-warped6-a's run 4, plan run 39687964414: three
   times), so after half a minute of it standing there the bot walks to the mouth, and back in
   as soon as it is out (hunt: an angry one near sends the bot home from outside), the enderman
   after it down the stairs to the sword. Batch pearls-warped7-a (plan run 39714009099, probe
   39720871893) run 2 stood eight minutes in its burrow over one 5 blocks off and 5 above, and
   twenty-four over the next, 10 off and 3 above, which stood in one place from the 16th minute
   to the end; batch pearls-warped6-b's run 3 stood twenty-five the same way."
  [world]
  (>= (or (memory/done-since world :lookout :walk) (memory/done-since world :lookout :burrow) 0)
      pparams/lure-lookouts))

(defn blocked-out?
  "Has the bot stood stale-lookouts in its burrow over an enderman given up on (blacklisted)
   standing in its stairs (blocking), out of the sword's reach and never coming down to it?
   Then that one holds the burrow no longer, as one lure-tries lures did not bring holds it no
   longer (lured-out?): the lure's walks to the mouth are never taken past one standing in
   the stairs, so the lookouts here are the count. Sim probe 40102602895 (world R3, seed 4)
   stood in its burrow from minute 26 to 60 over such a one, ten pearls held: blocking kept
   it in, angry-near kept the burrow fresh, and no walk ever counted toward lured-out?."
  [world]
  (let [[bid] (blocking world)]
    (boolean (and bid
                  (contains? (avoided/all-of world :blacklist) bid)
                  (>= (or (memory/done-since world :lookout :burrow) 0) pparams/stale-lookouts)))))

(defn stale?
  "Is the hunt's burrow given up: stale-lookouts or more :lookout intents done since it was cut
   (memory/done-since), and no angry enderman near its mouth (angry-near: an angry one still
   hits the bot that steps out, blacklisted or not; a calm one blacklisted as :unprovoked holds
   nothing: batch pearls-warped5-a's run 3 stood 37 minutes in lookouts beside its burrow over
   one such by the mouth)?
   A minute of lookouts is a minute with nothing to provoke from the burrow, in it or from a
   vantage within its reach: a calm one by the mouth that never moves held the bot for the run
   (gym batch pearls-warped4-a, plan run 39544677155: run 1 stood in its burrow from 308 s to
   the end, thirty-five minutes, over a calm enderman 2.7 blocks from the mouth, and run 5
   twenty-one minutes over one 3.9 off), and every enderman hidden from the burrow's reach
   would hold it as long. Given up, the burrow is no shelter (shelter), so the hunt walks to
   the enderman and cuts a new burrow beside it, the way run 5 did at 1714 s by chance and
   killed that enderman from the new burrow's end at 1731 s. One given up on standing in the
   stairs (blocked-out?) no longer keeps it fresh either: the way out is then cut the other
   way (cut-out), never walked through it."
  [world]
  (boolean (and (>= (or (memory/done-since world :lookout :burrow) 0) pparams/stale-lookouts)
                (or (nil? (angry-near world)) (blocked-out? world)))))

; hostile kinds no threat is: the enderman is the quarry, met its own way; a creeper is the :defend row's, backed away from, and a burrow is no place to back off in
(defn threat
  "[eid entity] of the nearest hostile (entity/hostiles) within combat/defend-radius of the feet
   that is of no shunned kind and not blacklisted (one a held fight gave up on, a ghost); nil
   when none. With a burrow near the hunt meets it there, where the :defend row would fight it in
   the open: gym batch pearls-warped4-a (plan run 39544677155) runs 3 and 4 were slain by other
   mobs that row fought and backed away from at the forest's edge, each with a burrow cut, and
   its pearls-1-live run 2 by one the midnight arena spawned. The burrow is one cell wide and
   two high: a hoglin or a spider cannot follow, an arrow has no line to the end, and a zombie
   or a piglin comes down the stairs to the sword one at a time."
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
  "Can a stare from where the bot stands provoke enderman e: its head within stare-range of the
   eye and no nearer than stare-from, with a clear line to it?"
  [world e]
  (let [eye (game/eye world)
        d (physics/distance eye (head e))]
    (and (<= pparams/stare-from d pparams/stare-range) (mob/sight? (sight-fn world) eye (head e)))))

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
          (= :found (:path/status (path/plan world ec goal {:path/max-nodes 300})))))))

(defn quarry
  "[eid entity] of the enderman a stare is for: the nearest within stare-range not yet coming
   (entity/creepy?) that is provokable? from where the bot stands (one within 16 blocks and two or
   more below the burrow's mouth only when a route reaches the mouth: within 16 it never teleports,
   EndermanLookForPlayerGoal, and cannot climb a ledge to the mouth), or nil. Not only the nearest:
   gym batch pearls-263d (plan run 38899294690, probe 38904622614) tracked seventeen endermen 30
   to 80 blocks round each warped forest landing, none within a route's reach, and stood looking
   round for forty minutes while the nearest, behind a ledge, could not be stared at. One stared
   at from past 16 blocks teleports toward the bot (EndermanLookForPlayerGoal): it need not be
   walked to."
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
   feet's own first, or nil. Gym batch pearls-warped8-b (plan run 39775345028, probe
   39849820209) run 1's walk ended in a fire lit on gravel in its path, and it stood there in
   :lookout, an :eat between, from 16 health to its death in seventeen seconds. The body's
   cells, not the feet's one: gym batch pearls-w1 (plan run 40387599204, probe 40421394796)
   run 1 dug out the fire in its feet's cell [2457 37 2165] and still burned to death over
   twenty seconds of :eat and :lookout, standing at z 2165.14 with its box (physics/half-width
   each way) reaching into the fire on gravel in the cell north of it."
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
   (angry-within? leaves the lured-out unfightable ones out, as no reason to stay). One of those
   still strikes the bot the moment it walks out in the open: sim R1-2-1 on the stage-18 rods
   start (lane 42212990209, replay probe 42217021861) held its first pearl, fought enderman 955
   four times from its burrow without a hit landed (40 of 40 health), and with 955 angry ten
   blocks off walked out 34 blocks to a new stare site; 955 struck it from 15.9 to 0 in four
   seconds."
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
   There the burrow is cut away from the nearest enderman (at-hand) even with none in sight: sim
   probe 39861051222 (world R4, teleporting endermen) walked for fifty-seven minutes between a site,
   from which its quarry was hidden, and the walk toward that quarry, from which it showed again,
   three pearls held."
  [world]
  (let [{:intent/keys [kind for status]} (:plan/last world)]
    (and (= :walk kind) (= :shelter for) (= :done status))))

(defn struck-by
  "[eid e] of the nearest angry enderman (entity/creepy?) within the sword's reach (combat/in-reach?)
   and strike-range of the feet while health is under struck-health, fought out or not, or nil.
   One that has hit the bot and stands where the sword reaches it is fought back, never walked
   away from: runs 4 and 5 of the same plan run took three hits in five seconds with a walk and a
   back-off going on, and sent no attack (run 4) or too few."
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
   not given) that are
   no enderman (the quarry, met its own way from the burrow): a zombified piglin counts, hostile
   on sight or not, as it comes in packs and one hit angers them all. Gym plan run 40825468783 run
   5 (issue #263, pearls-12): an enderman and two zombified piglins took 20 health in 1.5 s."
  ([world] (swarm world (:player/pos world)))
  ([world pos]
   (into {}
         (filter (fn [[_ e]]
                   (and (not (entity/dead? e))
                        (not= :enderman (entity/kind e))
                        (= :monster (:category (blocks/mobs (entity/kind e))))
                        (<= (physics/distance pos (:entity/pos e))
                            (or (:range (mob/kinds (entity/kind e))) pparams/swarm-radius))
                        ;; a blaze behind a wall hits nobody: pearl-fight-o's race bots (race 9d15bd69b,
                        ;; Clj_r24 and four more) stood `swarmed?` by one 19 to 29 blocks off for the
                        ;; rest of their lives, and travel is gated on it
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
   Never fought (they hover out of the sword's reach); only covered from. Gym plan run 40839050567
   job 6 (issue #263, pearls-12): five fireballs in 67 s of digging a staircase down in the open,
   then burned to death."
  [world]
  (boolean (some (fn [[_ e]]
                   (and (contains? pparams/ranged-kinds (entity/kind e)) (combat/seen? world e)))
                 (swarm world))))

(defn recently-shot?
  "Was a ghast in sight (:pearls/shot-at-ms, shot-at?) less than shot-hold-ms ago? Pearls-fight-c job 1
   (plan run 41155212096, issue #263) died to a ghast in 1m48: a hide walk and a travel walk
   alternated every second, 23 swings in 5.6 s, each undoing the other, 13 hits and no cover."
  [world]
  (let [t (:pearls/shot-at-ms world)]
    (boolean (and t (< (- (:time/now world 0) t) pparams/shot-hold-ms)))))

(defn weak?
  "Is entity e of a kind mob/kinds knows to hit for weak-melee or less on Normal (a zombie, a
   spider), and no archer? A piglin, a hoglin or a blaze is not: gym plan run 41109441111
   (pearls-travel-a) job 5 walked down to a piglin and fought it, three hits in four seconds,
   dead at 39 minutes."
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
   lone one that is not over that health is met by the fight rules (a magma cube is small and slow).
   Pearls-travel-d job 5 (plan run 41156746096, issue #263) lived 73 minutes and two pearls, then
   the :defend row fought a lone piglin at full health: two swings, hits of 6.7 at 73.06, .09 and
   .16 min, dead."
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
   through an iron helmet and chestplate, as gym plan run 40860669235 job 4 measured."
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
   (enderman-hit), at most provoke-health. Job 4 of that plan run provoked at 11.19 health and
   was felled by the second and third hit of a fight in the open."
  [world]
  (min pparams/provoke-health (+ pparams/provoke-margin (* 2.0 (enderman-hit world)))))

(defn coming?
  "Is the nearest angry enderman (entity/creepy?) within stare-range one the hunt still fights
   (not unfightable?: given up, fought out, cut or failed lately)? No other is provoked while it
   is: churn-a (plan 41096292443) run 3 provoked 1310, 1561 and 1691 in 14 s and had three within
   2.5 blocks of the bot."
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
   walk away from it, which an interrupt here would cut every tick. Sim gate r3fr3 (probe
   40465621253, world R4 seed 3): with one fought out over its burrow's roof the hunt chose
   the crowd site's walk 28,545 times, each cut at once, where on main the same run fought
   it 18,657 times (probe 40465674776)."
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
