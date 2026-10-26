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
            [clojurecraft.blaze :as blaze]
            [clojurecraft.blocks :as blocks]
            [clojurecraft.combat :as combat]
            [clojurecraft.drops :as drops]
            [clojurecraft.entity :as entity]
            [clojurecraft.game :as game]
            [clojurecraft.intent :as intent]
            [clojurecraft.make :as make]
            [clojurecraft.memory :as memory]
            [clojurecraft.mob :as mob]
            [clojurecraft.path :as path]
            [clojurecraft.physics :as physics]
            [clojurecraft.plan :as plan]
            [clojurecraft.recipe :as recipe]
            [clojurecraft.stairs :as stairs]
            [clojurecraft.stone :as stone]
            [clojurecraft.sword :as sword]
            [clojurecraft.terrain :as terrain]
            [clojurecraft.window :as window]))

(set! *warn-on-reflection* true)

(def pearl :ender_pearl)               ; the item a kill may drop

(def hunt-radius 32.0)                ; blocks from the feet an enderman is hunted within
(def seek-radius 64.0)                ; blocks from the feet an enderman is walked toward, none nearer
(def lookout-ms 5000)                 ; ms a :lookout stands turning before the plan looks again
(def lookout-turn 0.09)               ; degrees of yaw a :lookout turns a ms: a full turn in four seconds
(def roam-reach 16)                   ; blocks a roam walks from the feet, at the least, to look round somewhere else
(def toward-gain 4)                   ; blocks nearer an enderman, by a route's measure, a walk toward one must bring the feet
(def roam-turn-ms 10000)              ; ms after which a roam sets out a heading further round
(def headings ; the eight ways a roam may go, a quarter turn of each other in pairs
  [[1 0] [1 1] [0 1] [-1 1] [-1 0] [-1 -1] [0 -1] [1 -1]])
(def strike-range 4.5)                ; blocks from the feet, height counted, within which an enderman is at hand (hunt/strike-range: past a walk's reach)
(def stare-range 64.0)                ; blocks to the head within which a provoke stares, with a clear line: the enderman's follow range
(def stare-from 6.0)                  ; never stare nearer: an angry enderman stared at within 4 teleports away
(def stare-ms 3000)                   ; ms a provoke stares at most before the enderman says it has a target (creepy)
(def burrow-depth 3)                  ; stairs down a burrow cuts: from the third, the ground roofs a tunnel two high
(def burrow-length 2)                 ; tunnel steps past the stairs: two keep the bot out of an enderman's reach from the last stair
(def burrow-span (+ burrow-depth burrow-length)) ; blocks from the burrow's end every cell of it lies within: a walk toward one never ends inside
(def shelter-reach 16.0)              ; blocks from the feet a burrow is still the hunt's shelter; farther, a new one is dug
(def descend-drop 8)                  ; blocks below the feet the nearest enderman stands, at the least, before the hunt cuts stairs down toward it
(def descend-step 8)                  ; blocks one flight of those stairs goes down before the hunt looks round again
(def descend-radius 128.0)            ; blocks from the feet an enderman far below is descended toward: the server shows them that far
(def bore-length 8)                   ; level steps a tunnel toward an enderman no route reaches cuts before the hunt looks again
(def site-reach 6)                    ; cells on the plane round the feet a burrow site is looked for within, when none can be cut from the feet
(def candidates 3)                    ; nearest endermen asked for a route, as the other acts ask
(def spare-digs 32)                   ; digs a pickaxe keeps for a burrow: a burrow's sixteen (burrow-steps) twice over, for one that turns or fails :no-shelter; with fewer left no stairs or tunnel is cut for anything else

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
    (cond (and e (<= (physics/distance pos (:entity/pos e)) strike-range)) near
          :else (entity/nearest (endermen (dissoc world :plan/blacklist)) pos))))

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
          (filter (fn [[_ e]] (<= (physics/distance m (:entity/pos e)) strike-range)))
          (endermen (dissoc world :plan/blacklist)))
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
                                      (let [c (cell (:entity/pos e))]
                                        (and (< (physics/y-of c) (physics/y-of mouth))
                                             (not= c end)
                                             (<= (physics/distance c end) burrow-span)))))
                            (endermen (dissoc world :plan/blacklist)))
                      (:player/pos world)))))

(def danger-radius 16.0)              ; blocks from the burrow's mouth an angry enderman keeps the bot in from: the enderman's own stare range (EndermanLookForPlayerGoal: within 16 an angry one stands while looked at and rushes when not; farther, one with a target teleports to it after 30 ticks, so one farther that has not come has lost its target); eight let a burrow go stale with one angry nine to sixteen off that would rush the bot stepping out. The wait within is bounded by the lure (lure?), where one that never comes held batch pearls-warped-d run 1 thirty-four minutes
(def danger-rise 8)                   ; blocks above or below the burrow's mouth an angry enderman keeps the bot in from: one seventeen below in another cave never comes (batch pearls-warped-c run 1's forty minutes); one five or six below on the slope beside the mouth comes the moment the bot steps out (batch pearls-warped6-a run 4, three times, the third its death)

(def lure-tries 3)                    ; lures (a walk out to the burrow's mouth and one back in) after which a blacklisted angry enderman near the mouth that has not come holds the burrow no longer

(defn lured-out?
  "Has the bot walked lure-tries times out to its burrow's mouth and back since the burrow was cut
   (memory/done-since: two :walk intentions done a lure, every walk since the burrow counted), so
   that a blacklisted angry enderman near the mouth that never came is no reason to stay?"
  [world]
  (>= (or (memory/done-since world :walk :burrow) 0) (* 2 lure-tries)))

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
      (entity/nearest
       (into {}
             (filter (fn [[_ e]]
                       (let [p (:entity/pos e)]
                         (and (entity/creepy? e)
                              (<= (physics/distance c p) danger-radius)
                              (<= (abs (- (physics/y-of p) (physics/y-of c))) danger-rise)))))
             (endermen (if (lured-out? world) world (dissoc world :plan/blacklist)) stare-range))
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
                            (<= (physics/distance p q) danger-radius)
                            (<= (abs (- (physics/y-of q) (physics/y-of p))) danger-rise))))
                   (endermen (dissoc world :plan/blacklist) stare-range)))))

(def stale-lookouts 12)              ; lookouts (five seconds each, a minute) stood with the burrow and nothing to do from it before it is given up for one cut beside the next enderman
(def lure-lookouts 6)                 ; lookouts (half a minute) stood in the burrow over an angry enderman near its mouth that has not come before the bot shows itself at the mouth to bring it

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
      lure-lookouts))

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
                  (contains? (:plan/blacklist world #{}) bid)
                  (>= (or (memory/done-since world :lookout :burrow) 0) stale-lookouts)))))

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
  (boolean (and (>= (or (memory/done-since world :lookout :burrow) 0) stale-lookouts)
                (or (nil? (angry-near world)) (blocked-out? world)))))

(def shunned #{:enderman :creeper})    ; hostile kinds no threat is: the enderman is the quarry, met its own way; a creeper is the :defend row's, backed away from, and a burrow is no place to back off in

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
        bl (:plan/blacklist world #{})]
    (entity/nearest (into {}
                          (filter (fn [[eid e]]
                                    (and (not (contains? bl eid))
                                         (not (contains? shunned (entity/kind e)))
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
    (and (<= stare-from d stare-range) (mob/sight? (sight-fn world) eye (head e)))))

(defn reaches?
  "Can enderman e reach cell to on foot: within danger-radius (16 blocks: past 16 it teleports,
   EndermanLookForPlayerGoal), a route from its cell to to is :found (path/plan). Nil or false
   when no route reaches to within max nodes."
  [world e to]
  (if-not to
    true
    (let [c (if (map? to) (:goal/pos to) to)
          ec (cell (:entity/pos e))
          d (physics/distance (physics/centre c) (:entity/pos e))
          goal (if (map? to) to {:goal/kind :block :goal/pos c})]
      (or (> d danger-radius)
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
    (->> (endermen world stare-range)
         (remove (comp entity/creepy? val))
         (sort-by (fn [[_ e]] (physics/distance pos (:entity/pos e))))
         (some (fn [[_ e :as ee]]
                 (when (provokable? world e)
                   (if (and in-burrow?
                            mouth
                            (<= (physics/distance (:entity/pos e) (physics/centre mouth))
                                danger-radius)
                            (< (physics/y-of (:entity/pos e)) (dec (physics/y-of mouth))))
                     (when (reaches? world e mouth) ee)
                     ee)))))))

(def roam-reaches [roam-reach (* 2 roam-reach) (* 3 roam-reach)]) ; how far a roam looks for an end not yet walked to, nearest first: sixteen blocks, then thirty-two, then forty-eight

(defn roam-goal
  "The path goal of a roam along heading [dx dz] from the feet, reach blocks out (roam-reach
   when not given): any cell more than twice reach, less one, on the plane (path's :afar, by
   its octile) from the point reach behind the feet, which the cells reach or more ahead
   satisfy at any height, and the cells beside and behind do not. The route finds the way to
   one: along a cave's passage, up a cliff's side, round a lake of lava. Never by the height
   alone: gym batch pearls-warped2-a (plan run 39179234118) run 3 roamed its own staircase up
   and down for thirty minutes, each flight far enough by path's :away, which counts the
   height."
  ([feet heading] (roam-goal feet heading roam-reach))
  ([[x y z] [dx dz] reach]
   {:goal/kind :afar
    :goal/pos [(- x (* dx reach)) y (- z (* dz reach))]
    :goal/range (dec (* 2 reach))}))

(defn roam
  "A walk to look round from somewhere else: along the first of the eight headings, from one the
   clock picks (roam-turn-ms a heading), to the end of a route that reaches a roam-goal cell
   (path/plan :found, its last node, which the walk then plans to again and reaches), roam-reach
   out first and then each of roam-reaches farther; nil when
   no heading has one at any reach. Gym batch pearls-263e (plan run 38905343997): with the stare reaching 64
   blocks, a warped forest landing still stood looking round for forty minutes, every enderman
   out of its sight. Batch pearls-263f (plan run 38918584959, probe 39130057525): the roam then
   asked for one cell a heading, roam-reach off within eight of the feet's height, and from a
   cave under the forest found rock in all eight, forty minutes long; the Nether is caves and
   cliffs, and a route knows where the cells are. Never to an end a walk already arrived at
   (memory/arrived): it is somewhere else that is wanted, and batch pearls-warped3-a (plan run
   39529384355, probe 39543118281) roamed to the same ends between its lookouts. Farther when
   every end sixteen off is spent: gym batch pearls-warped6-b (plan run 39700288080, probe
   39711523945) run 5 took six pearls in twenty-two minutes from one stretch of the forest
   floor, then had no enderman within 64 for most of its last sixteen minutes, every one of the
   eighty-six ends it had walked to lying within thirty blocks, and stood in lookouts from the
   twenty-seventh minute with the roam finding no end it had not seen; the forest spawns four
   endermen at a time and no more where the bot has thinned them, and somewhere else is
   farther. With no route reaching any roam-goal (the search spends its expansions in the
   caves and cliffs on the way: :partial, or its frontier: :none), the farthest end those
   routes came to, roam-reach or more from the feet on the plane and not spent, is walked to
   instead, as a walk toward an enderman goes as far as its route goes (toward): from there
   the routes are planned again, so the search chains outward past one search's budget. And
   with every end a route reaches spent, the farthest of those spent ends is walked to
   again: from forty-eight blocks off the ends round there are unspent, and the forest there
   has had the walk's minutes to spawn into; the ends near the feet are revisited only by
   being walked past. Gym batch pearls-warped9-a (plan run 39898400239) run 1 took three
   pearls by about minute forty and then stood in lookouts in one place for four hours with
   no enderman tracked: nothing for the roam or dig-out to go after, and the forest it never
   searched past its stretch of floor spawned endermen it never saw."
  [world]
  (let [feet (intent/feet world)
        start (mod (quot (:time/now world 0) roam-turn-ms) (count headings))
        spent (memory/arrived world)
        tries (for [reach roam-reaches k (range (count headings))] [reach k])
        plan-to (fn [[reach k]]
                  (let [heading (nth headings (mod (+ start k) (count headings)))
                        r (path/plan world
                                     (path/feet-cell (:player/pos world))
                                     (roam-goal feet heading reach)
                                     {})]
                    [(:path/status r) (last (:path/waypoints r))]))
        walk (fn [to] {:intent/kind :walk :intent/target to :intent/for :pearls})
        ;; every route planned once: the first pass wanted the first unspent end a route
        ;; reaches, the second the farthest ends of those same routes, and planning the
        ;; twenty-four routes twice over spent the tick on searches already searched
        planned (mapv plan-to tries)]
    (or (some (fn [[status to]]
                (when (and (= :found status) to (not (contains? spent to))) (walk to)))
              planned)
        (let [[_ partial _ spent-end] (reduce (fn [[pg p sg s] [status to]]
                                                (let [gain (when to (path/octile feet to))]
                                                  (cond (and gain
                                                             (not= :found status)
                                                             (>= gain roam-reach)
                                                             (not (contains? spent to))
                                                             (> gain pg))
                                                        [gain to sg s]
                                                        (and gain (= :found status) (> gain sg))
                                                        [pg p gain to]
                                                        :else [pg p sg s])))
                                              [0 nil 0 nil]
                                              planned)]
          (when-let [to (or partial spent-end)] (walk to))))))

(defn routable
  "[eid entity] of the first of the candidates nearest endermen past a walk's reach of the eye
   (intent/reach: nearer, the walk arrives where it begins and is planned again every tick) that
   a route reaches (intent/routable?), or nil. Gym batch pearls-warped7-a (plan run 39714009099,
   probe 39720871893) run 4 planned the walk to one 3.8 blocks off 18,760 times over its last
   fifteen minutes, five pearls held."
  [world ms]
  (let [pos (:player/pos world)
        eye (game/eye world)]
    (->> ms
         (sort-by (fn [[_ e]] (physics/distance pos (:entity/pos e))))
         (take candidates)
         (some (fn [[_ e :as ee]]
                 (let [c (cell (:entity/pos e))]
                   (when (and (> (physics/distance eye (physics/centre c)) intent/reach)
                              (intent/routable? world {:intent/kind :walk :intent/target c}))
                     ee)))))))

(defn toward
  "A walk toward the nearest of the candidates nearest endermen in ms when no route reaches one
   (routable found none): to the end of the route that comes nearest it (path/plan's :partial or
   :none, the way as far as it goes), when that end is toward-gain or more nearer it than the
   feet on the plane (path/octile), past a walk's reach of the eye (intent/reach: nearer, the
   walk arrives where it starts, and batch pearls-warped-a's run 3, plan run 39131478416,
   planned it again every tick for minutes) and not inside the hunt's own burrow (within
   burrow-span of :pearls/shelter); nil when no route gains on any. Gym batch pearls-263f (plan
   run 38918584959, probe 39130057525): a landing on the forest floor tracked fifteen endermen,
   every route to them :none, the nearest 33 blocks off, and stood looking round for thirty
   minutes. The way as far as it goes changes what is in sight, and from the new place the
   route is asked again. Batch pearls-warped-c's run 2 (plan run 39152813557, probe
   39157605071), on a ledge over the forest floor, walked from its burrow's mouth back to its
   end, three lower and nearer by the route's measure of height, and out again, for forty
   minutes: the height is the stairs' to gain (descend). Never to an end a walk has already
   arrived at (memory/arrived): batch pearls-warped3-a (plan run 39529384355, probe
   39543118281) run 3 walked between the ends of the routes toward two endermen, each end four
   nearer one of them than the other end, for twenty-five minutes, and run 1 between its
   burrow's mouth and one such end; what is in sight from an end was seen the first time."
  [world ms]
  (let [pos (:player/pos world)
        feet (path/feet-cell pos)
        home (:pearls/shelter world)
        spent (memory/arrived world)]
    (->> ms
         (sort-by (fn [[_ e]] (physics/distance pos (:entity/pos e))))
         (take candidates)
         (some (fn [[_ e]]
                 (let [c (cell (:entity/pos e))
                       i {:intent/kind :walk :intent/target c :intent/for :pearls}
                       to (last (:path/waypoints (path/plan world feet (intent/goal-of i) {})))]
                   (when (and to
                              (not (contains? spent to))
                              (>= (- (path/octile feet c) (path/octile to c)) toward-gain)
                              (> (physics/distance (game/eye world) (physics/centre to))
                                 intent/reach)
                              (not (and home (<= (physics/distance to home) burrow-span))))
                     (assoc i :intent/target to))))))))

(defn feet-at
  "The feet point of a bot standing in cell [x y z]: the middle of the cell's floor."
  [[x y z]]
  [(+ x 0.5) y (+ z 0.5)])

;; ---------------------------------------------------------------- the kill and its drop

(def fires #{:fire :soul_fire})        ; blocks that burn whoever stands in them

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
            (when (contains? fires
                             (some-> (terrain/block-at world c)
                                     blocks/name-of))
              c))
          cells)))

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
  "The hunt's burrow (:pearls/shelter) while it is within shelter-reach of the feet, still a
   shelter? by the terrain and not given up after a minute with nothing to do from it (stale?),
   else nil."
  [world]
  (let [s (:pearls/shelter world)]
    (when (and s
               (<= (physics/distance (intent/feet world) s) shelter-reach)
               (shelter? world s)
               (not (stale? world)))
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

(defn worn-out?
  "Has the hunt spent its pickaxe: a burrow cut this run (memory/last-done) and no pickaxe held
   now? A bot that never held one hunts in the open as it always has; one that wore its pickaxe
   out on burrows does not: sim probe 39890964793 (seed 5) struck calm endermen in the open in
   worlds R2 and R3 after its pickaxe broke and was slain, R2 nine times."
  [world]
  (boolean (and (not (pickaxe? world)) (memory/last-done world :burrow))))


(defn digs-left
  "The most digs any held pickaxe has left (window/swings-left: its tier's durability less the
   :damage the server sends on a worn one, or the model's counted-down :durability), or nil
   with none held."
  [world]
  (let [left (->> (vals (:player/inventory world))
                  (filter #(= :pickaxe (:kind (blocks/tool (:item %)))))
                  (keep window/swings-left))]
    (when (seq left) (apply max left))))

(defn spare?
  "Does a held pickaxe have spare-digs or more left, so that stairs or a tunnel for something
   other than a burrow may spend some? Gym batch pearls-warped3-a (plan run 39529384355) run 1:
   three kills from a burrow, then stairs, tunnels and bores wore the iron pickaxe out at
   fourteen minutes (:stairs-down failed :no-pickaxe, 238 netherrack held), and with no burrow
   to be cut it stood in :lookout for the last twenty-five; run 3 ended with 25 digs left.
   Netherrack makes no pickaxe, but a warped stem does (make-pickaxe): under spare-digs the
   hunt makes the next one before this one breaks."
  [world]
  (boolean (some-> (digs-left world)
                   (>= spare-digs))))

(def cut-reach 32)                    ; blocks on the plane (path/octile), at most, from the feet to an enderman stairs or a tunnel are cut toward: a flight is eight blocks and about twenty digs, so one farther off is walked toward first (toward); batch pearls-warped6-b's run 2 cut four flights toward ones 47 to 69 off, 10 to 14 below, and three of its tunnels toward ones 41 to 61 off, with its kill's pearl in hand

(defn level?
  "Does enderman e stand on the bot's level: less than descend-drop above or below the feet?"
  [world e]
  (< (abs (- (physics/y-of (cell (:entity/pos e)))
             (physics/y-of (path/feet-cell (:player/pos world)))))
     descend-drop))

(defn within-cut-reach?
  "Does enderman e stand within cut-reach of the feet on the plane (path/octile), near enough
   for stairs or a tunnel to be cut toward it?"
  [world e]
  (<= (path/octile (path/feet-cell (:player/pos world)) (cell (:entity/pos e))) cut-reach))

(defn descend
  "Stairs down toward the first of the candidates nearest endermen in ms that stands
   descend-drop or more below the feet within cut-reach on the plane (within-cut-reach?), a
   pickaxe held with digs to spare past a burrow's (spare?): a :stairs-down to descend-step
   lower, or to its height, cut the straight way toward it (the opposite of away); nil when none
   stands so low and so near, no pickaxe with spare digs is held, or a staircase from these feet
   was boxed (:plan/boxed-at:
   batch pearls-warped-d run 3, plan run 39160760074, cut the same refused stairs from the same
   cell for twenty-seven minutes and failed :boxed; a roam moves it first). Gym batch pearls-warped-c (plan run 39152813557, probe
   39157605071): both landings that lived stood on a ledge of the forest, every enderman 14 to
   50 blocks below, no route down to any and none in sight, and looked round for forty minutes.
   The forest floor lies under its ledges; a route refuses the drop, a staircase takes it. Only
   toward one near on the plane: a flight is eight blocks and about twenty digs of the pickaxe,
   and gym batch pearls-warped6-b (plan run 39700288080, probe 39711523945) run 2 cut four
   flights toward endermen 47 to 69 blocks off and 10 to 14 below (about 90 digs) and three
   tunnels toward ones 41 to 61 off in the five minutes after its first kill, and wore the
   pickaxe out at eleven minutes; the flights that paid (run 4's seven kills after six, run 5's
   four after three) went toward ones 12 to 31 off and 8 to 22 below. Not only with none on this
   level, as ascend asks: run 4's paying flights went with one to seven on its level within 128,
   all hidden and far, and the many below near."
  [world ms]
  (let [pos (:player/pos world)
        fy (physics/y-of (path/feet-cell pos))]
    (when (and (spare? world) (not (contains? (:plan/boxed-at world) (intent/feet world))))
      (->> ms
           (sort-by (fn [[_ e]] (physics/distance pos (:entity/pos e))))
           (take candidates)
           (some (fn [[_ e]]
                   (let [drop (- fy (physics/y-of (cell (:entity/pos e))))]
                     (when (and (>= drop descend-drop) (within-cut-reach? world e))
                       {:intent/kind :stairs-down
                        :intent/to-y (- fy (min drop descend-step))
                        :intent/dir (mapv - (away world e))
                        :intent/for :pearls}))))))))

(defn ascend
  "Stairs up toward the first of the candidates nearest endermen in ms that stands descend-drop
   or more above the feet within cut-reach on the plane (within-cut-reach?, as descend asks), a
   pickaxe held with digs to spare past a burrow's (spare?): a
   :stairs-up to descend-step higher, or to its height (:intent/to-height?: it ends at that
   height, not where the ground opens), cut the straight way toward it; nil when none stands so
   high and so near, no pickaxe with spare digs is held, or a staircase from these feet was boxed
   (:plan/boxed-at). The way back up that descend has no answer to: gym batch pearls-warped5-b
   (plan run 39671293287, probe 39683564524) run 5 took five pearls in 150 s from a burrow on
   the forest floor at y 59, cut seven flights down to three endermen in a cave at y 36 and
   killed them, and then stood thirty-two minutes at y 33 to 50 with every enderman on the
   floor above it, 20 to 30 blocks up, no route up and a roam that gained no height; runs 2, 3
   and 4 of the same batch spent most of their forty minutes in the caves under the floor the
   same way. The forest's endermen spawn on its floor; a bot under it hunts nothing. Only with
   none of ms on this level (less than descend-drop above or below the feet): one on this level
   is walked toward, tunnelled toward or looked out for first, and a climb toward a far ledge
   over a forest that still has endermen on the floor is the stairs' wear for nothing (batch
   pearls-warped6-a's run 1, plan run 39687964414, climbed toward one thirty above on a ledge
   with one three above seventeen blocks off, was refused under the ledge's overhang, stepped
   aside downhill, and cut 161 stairs up and 25 down in nineteen minutes, down to y 28)."
  [world ms]
  (let [pos (:player/pos world)
        fy (physics/y-of (path/feet-cell pos))]
    (when (and (spare? world)
               (not (contains? (:plan/boxed-at world) (intent/feet world)))
               (not-any? (fn [[_ e]] (level? world e)) ms))
      (->> ms
           (sort-by (fn [[_ e]] (physics/distance pos (:entity/pos e))))
           (take candidates)
           (some (fn [[_ e]]
                   (let [rise (- (physics/y-of (cell (:entity/pos e))) fy)]
                     (when (and (>= rise descend-drop) (within-cut-reach? world e))
                       {:intent/kind :stairs-up
                        :intent/to-y (+ fy (min rise descend-step))
                        :intent/dir (mapv - (away world e))
                        :intent/to-height? true
                        :intent/for :pearls}))))))))

(defn burrow-steps
  "The cells a burrow cut from feet along dir opens, a step at a time: burrow-depth stairs an
   enderman walks down (stairs/tall-stair-cells for mob/tall, four high: the feet a block down
   and on at each), then burrow-length level steps (stairs/level-cells, two high); the last cell
   of the last step is where the bot ends."
  [feet dir]
  (let [n (mob/tall :enderman)
        stairs (reductions (fn [cells _] (stairs/tall-stair-cells (last cells) dir n))
                           (stairs/tall-stair-cells feet dir n)
                           (range (dec burrow-depth)))
        tunnel (reductions (fn [cells _] (stairs/level-cells (last cells) dir))
                           (stairs/level-cells (last (last stairs)) dir)
                           (range (dec burrow-length)))]
    (vec (concat stairs tunnel))))

(defn mouth-cells
  "The cells over a burrow's mouth [x y z] (the feet it is cut from) that an enderman standing
   in it fills and the bot does not: from two over the feet to one under mob/tall over them,
   one cell, [x y+2 z], for its 2.9. The bot stands in two open cells and an enderman needs
   three, so a burrow whose mouth it cannot stand in (stand?) it never enters, however its
   stairs are cut: :burrow digs these first. Gym batch pearls-warped7-b (plan run 39725964182,
   probe 39732372050) run 3 cut its burrow under a huge fungus's cap, a warped wart block two
   over the mouth's feet, with every stair four high below it; the enderman the lure brought
   stood in the hollows two to four blocks beside the mouth, and six held fights in six timed
   out :unreachable over twenty minutes."
  [[x y z]]
  (mapv (fn [dy] [x (+ y dy) z]) (range 2 (mob/tall :enderman))))

(defn burrowable?
  "Would a burrow cut from feet along dir end in a shelter?: the cells over its mouth an
   enderman needs (mouth-cells) may be opened (stairs/clear-way?: no water or lava), each of
   its steps (burrow-steps) may be cut (stairs/safe?: no water, lava or drop, not a cell a
   staircase was boxed on) once the steps before it are open, and its end, every step open, is
   a shelter?. The cells are opened on the world's block overlay (:world/blocks), as the digs
   would open them."
  [world feet dir]
  (let [roof (mouth-cells feet)
        steps (burrow-steps feet dir)
        open (fn [w cells] (reduce #(assoc-in %1 [:world/blocks %2] blocks/air) w cells))
        dug (when (stairs/clear-way? world roof)
              (reduce (fn [w cells] (if (stairs/safe? w cells) (open w cells) (reduced nil)))
                      (open world roof)
                      steps))]
    (boolean (and dug (shelter? dug (last (last steps)))))))

(defn standable?
  "Could the bot stand with its feet in cell [x y z]: the feet and head cells clear and a solid
   floor under them (path/classify)."
  [world [x y z]]
  (and (= :clear (path/classify world [x y z]))
       (= :clear (path/classify world [x (inc y) z]))
       (= :solid (path/classify world [x (dec y) z]))))

(defn burrow-site
  "The cell nearest the feet, within site-reach on the plane and a block up or down at most,
   not the feet, that the bot could stand in (standable?), cut a burrow along dir from
   (burrowable?), and walk to (intent/routable?), and that a walk has not failed toward
   (:plan/blacklist); nil when there is none. Gym batch pearls-warped-e (plan run 39164914828)
   run 1: on broken ground :burrow failed :no-shelter six times in a row where the bot stood,
   and it then provoked an enderman with no shelter and was slain in the open. Batch
   pearls-warped2-b (plan run 39182782203) run 1: a site with no way to it was chosen again
   after each failed walk, thirteen :no-path in a row. The route is asked last and one cell at
   a time (some, not a chunked filter): it is the costly test."
  [world dir]
  (let [[x y z :as feet] (intent/feet world)
        bl (:plan/blacklist world #{})]
    (->> (for [dx (range (- site-reach) (inc site-reach))
               dz (range (- site-reach) (inc site-reach))
               dy [0 1 -1]]
           [(+ x dx) (+ y dy) (+ z dz)])
         (remove #{feet})
         (sort-by #(physics/distance feet %))
         (some (fn [c]
                 (when (and (not (contains? bl c))
                            (standable? world c)
                            (burrowable? world c dir)
                            (intent/routable?
                             world
                             {:intent/kind :walk :intent/target c :intent/for :shelter}))
                   c))))))

(def vantage-reach 10)                ; cells on the plane from an enderman, at most, a vantage is looked for within (stare-from at the least): the nearer the stare, the surer it follows
(def vantage-rise 2)                  ; cells up or down from an enderman's feet a vantage may stand: one on the stairs is three below the ground, and is waited out
(def vantage-tries 8)                 ; vantages, nearest the feet first, a route is asked for before none is taken: the route is the costly test
(def vantage-nodes 1500)              ; expansions a route to or from a vantage may spend: it lies within shelter-reach, and sixteen such routes may be asked in one planner tick

(defn vantage
  "A walk to look from within the burrow's reach, with none of ms provokable from here: a cell
   on the plane between stare-from and vantage-reach from the nearest of the candidates nearest
   endermen in ms, within vantage-rise of its height and shelter-reach of the burrow
   (:pearls/shelter) and no farther from the burrow than from the enderman (provoked, it comes
   at the bot's pace and more; the bot must be home first: gym batch pearls-warped5-b, plan run
   39671293287, probe 39683564524, run 1 provoked one 7.6 blocks off from a vantage 12 from its
   burrow's end and was slain on the walk home at 106 s, and run 5 lost eleven health on the
   walk home from one 15 off), that the bot could stand in (standable?), from which the enderman is
   provokable?, past a walk's reach of the eye (nearer, the walk arrives where it starts) and
   not where a walk already arrived (memory/arrived); of those, nearest the feet first and
   vantage-tries at most, the first a route reaches (intent/routable?) with a route from it
   home (gym batch pearls-warped-e's run 1, plan run 39164914828, provoked fifteen blocks from
   its burrow and found no path home); nil when none. Gym batch pearls-warped4-b (plan run
   39596287510, probe 39616249061): the stone arena's endermen wander off its lit square within
   the first minute, out of the mouth's line, and run 2 stood at its mouth at 33 s with all
   three 16 to 28 blocks off and hidden, walked out to the nearest and met the zombies, a
   skeleton and a creeper the dark round the arena spawned, its burrow out of reach by then;
   pearls-12's run 3 stood in its burrow from 341 s to the end over a calm enderman four blocks
   from the mouth, too near to stare at from there and hidden from the end, giving the burrow
   up every minute and cutting the same one again beside it; run 4 walked from its mouth to an
   enderman's cell, back in and out again 130 times. Provoked from a vantage, the enderman
   follows the bot home, and the fight is the burrow's."
  [world ms]
  (let [pos (:player/pos world)
        feet (intent/feet world)
        home (:pearls/shelter world)
        spent (memory/arrived world)
        eye (game/eye world)
        way-home (fn [c]
                   (intent/routable? (assoc world :player/pos (feet-at c))
                                     {:intent/kind :walk
                                      :intent/target home
                                      :intent/for :shelter
                                      :intent/max-nodes vantage-nodes}))
        cells (fn [e]
                (let [[ex ey ez :as ec] (cell (:entity/pos e))]
                  (for [dx (range (- vantage-reach) (inc vantage-reach))
                        dz (range (- vantage-reach) (inc vantage-reach))
                        dy (range (- vantage-rise) (inc vantage-rise))
                        :let [c [(+ ex dx) (+ ey dy) (+ ez dz)]
                              on-plane (path/octile ec c)]
                        :when (and (<= stare-from on-plane vantage-reach)
                                   (<= (physics/distance c home) shelter-reach)
                                   (<= (physics/distance c home)
                                       (physics/distance c (:entity/pos e)))
                                   (not (contains? spent c))
                                   (> (physics/distance eye (physics/centre c)) intent/reach)
                                   (standable? world c)
                                   (provokable? (assoc world :player/pos (feet-at c)) e))]
                    c)))]
    (->> ms
         (sort-by (fn [[_ e]] (physics/distance pos (:entity/pos e))))
         (take candidates)
         (some (fn [[_ e]]
                 (->> (cells e)
                      (sort-by #(physics/distance feet %))
                      (take vantage-tries)
                      (some (fn [c]
                              (let [i {:intent/kind :walk
                                       :intent/target c
                                       :intent/for :shelter
                                       :intent/max-nodes vantage-nodes}]
                                (when (and (intent/routable? world i) (way-home c))
                                  (dissoc i :intent/max-nodes)))))))))))

(def crowd-reach 10)                  ; cells on the plane from a calm enderman crowding a given-up burrow's mouth, at most, a new burrow is cut within: the nearer its mouth, the surer the stare from it follows
(def crowd-margin 1.0)                ; blocks past stare-from that a new burrow's mouth stands from that enderman's head, at the least, so that the stare from it is not cut short as one that came near (:provoke)

(defn crowder
  "[eid entity] of the nearest calm enderman (not entity/creepy?) crowding the burrow's mouth
   (crowd) that is not blacklisted (one that would not provoke is no reason to move), or nil."
  [world]
  (let [bl (:plan/blacklist world #{})]
    (entity/nearest
     (into {} (remove (fn [[eid e]] (or (contains? bl eid) (entity/creepy? e)))) (crowd world))
     (:player/pos world))))

(defn stare-site
  "The cell to cut a burrow at for enderman e, from whose mouth the stare at it goes: within
   crowd-reach of it on the plane and vantage-rise of its height, its head stare-from and
   crowd-margin or more from the eye standing there and provokable? from it, a cell the bot could
   stand in (standable?) and cut a burrow from heading from it (burrowable?, along away), past a
   walk's reach of the eye, not one a walk failed toward (:plan/blacklist), and of those, nearest
   the feet first and vantage-tries at most, the first a route reaches (intent/routable?); nil
   when there is none. A walk to the enderman itself ends beside it, the burrow is cut there,
   and a look while digging stares at it point blank: sim probe 39885559223 (world R2, seed 4)
   walked to a calm one with its burrow given up, was provoked two blocks off as it began the
   burrow beside it, and was slain in the open in six seconds."
  [world e]
  (let [feet (intent/feet world)
        eye (game/eye world)
        bl (:plan/blacklist world #{})
        [ex ey ez] (cell (:entity/pos e))
        from (fn [c] (assoc world :player/pos (feet-at c)))]
    (->> (for [dx (range (- crowd-reach) (inc crowd-reach))
               dz (range (- crowd-reach) (inc crowd-reach))
               dy (range (- vantage-rise) (inc vantage-rise))]
           [(+ ex dx) (+ ey dy) (+ ez dz)])
         (filter (fn [c]
                   (and (not (contains? bl c))
                        (> (physics/distance eye (physics/centre c)) intent/reach)
                        (standable? world c))))
         (sort-by #(physics/distance feet %))
         (filter (fn [c]
                   (let [at (from c)]
                     (and (>= (physics/distance (game/eye at) (head e)) (+ stare-from crowd-margin))
                          (provokable? at e)
                          (burrowable? world c (away at e))))))
         (take vantage-tries)
         (some (fn [c]
                 (when (intent/routable? world
                                         {:intent/kind :walk :intent/target c :intent/for :shelter})
                   c))))))

(defn crowd-site
  "The stare-site of the calm enderman crowding the burrow's mouth (crowder), where a new burrow
   is cut when the old one is given up (stale?) over it; nil when there is none, or no crowder.
   Sim probe 39883402083 (world R1, seed 4): a calm enderman stood by the mouth from the eighth
   minute to the end, too near to stare at from the mouth and hidden from the end; every minute
   of lookouts gave the burrow up, the walk toward the nearest enderman went to that one, and the
   burrow cut beside it was the same burrow again, its end and mouth the cells they had been.
   Worlds R1, R2 and R4 stood 28 to 49 of their 60 minutes so over seeds 3 to 5; gym batch
   pearls-warped4-a's runs 1 and 5 stood 35 and 21 minutes over such ones (plan run
   39544677155)."
  [world]
  (when-let [[_ e] (crowder world)] (stare-site world e)))

(defn floored-length
  "How many of the first n level steps from feet along dir stand on a floor once the steps
   before them are open (stairs/level-safe? a step at a time on the block overlay, as
   burrowable? opens a burrow's steps): the length a tunnel toward an enderman may cut before
   a step out over a drop. Gym batch pearls-warped9-a (plan run 39898400239) run 3's tunnel
   toward an enderman broke into the cave it stood in and the bot came down from y 51 to
   y 38 into the fight in the open there, where it died: the step the tunnel would lay a
   floor for over that drop is refused, as dig-out refuses a cut from a brink (brink?)."
  [world feet dir n]
  (let [steps (reductions (fn [cells _] (stairs/level-cells (last cells) dir))
                          (stairs/level-cells feet dir)
                          (range (dec n)))
        open (fn [w cells] (reduce #(assoc-in %1 [:world/blocks %2] blocks/air) w cells))]
    (second (reduce (fn [[w k] cells]
                      (if (stairs/level-safe? w cells) [(open w cells) (inc k)] (reduced [w k])))
                    [world 0]
                    steps))))

(defn bore
  "A tunnel (:tunnel, stairs.clj: level steps, turning where one is refused) the straight way
   toward the nearest enderman in ms, as far as its steps stand on a floor, bore-length at
   most (floored-length), standing less than descend-drop above or
   below the feet and within cut-reach on the plane (within-cut-reach?), a pickaxe held with
   digs to spare past a burrow's (spare?) and no staircase boxed from these feet
   (:plan/boxed-at); nil
   otherwise. For when no route reaches one and none gains on one (toward): the bot stands
   enclosed, at the foot of its own descent inside the rock. Gym batch pearls-warped2-a (plan
   run 39179234118) run 3: three flights down to the endermen's level ended in the rock, with
   none in sight and no route, and the bot walked its staircase for thirty minutes. Near on the
   plane only: a tunnel of eight is sixteen digs, and gym batch pearls-warped6-b (plan run
   39700288080, probe 39711523945) run 5 cut three toward one 41 to 54 blocks off in its last
   quarter hour, the pickaxe's last 46 digs, and run 2 one toward one 61 off. And never toward
   one nearer than bore-length on the plane: the tunnel would run past it, and the next one
   back; batch pearls-warped7-a (plan run 39714009099, probe 39720871893) run 4 cut fifteen
   tunnels east and west over one standing 5 blocks off and 4 below in a cave, about 100 digs
   in a minute."
  [world ms]
  (let [pos (:player/pos world)
        feet (intent/feet world)
        [_ e] (entity/nearest ms pos)]
    (when (and e
               (spare? world)
               (not (contains? (:plan/boxed-at world) feet))
               (< (abs (- (physics/y-of (:entity/pos e)) (physics/y-of feet))) descend-drop)
               (within-cut-reach? world e)
               (>= (path/octile feet (cell (:entity/pos e))) bore-length))
      (let [dir (mapv - (away world e))
            n (floored-length world feet dir bore-length)]
        (when (pos? n)
          {:intent/kind :tunnel :intent/dir dir :intent/length n :intent/for :pearls})))))

(def first-cut
  ;; the cells the first cut of each kind opens from the feet along a heading (stairs.clj)
  {:stairs-up stairs/up-cells :stairs-down stairs/stair-cells :tunnel stairs/level-cells})

(defn cheapest-heading
  "The heading of the four straight ones (stairs/directions) whose first cut of kind (:stairs-up,
   :stairs-down or :tunnel) from feet opens the fewest solid cells (solid-at?), toward first among
   equals. Where the rock is already cut a stair costs no digs, and a step a later cut took out is
   laid (stairs/up-lay): gym batch pearls-warped8-a (plan run 39731473452, probe 39778589970) run
   2's second flight down took the floor of its first flight's third stair, and the way back up
   lay two stairs north, open, one block to lay and no dig."
  [world kind feet toward]
  (first (sort-by (fn [h] (count (filter #(solid-at? world %) ((first-cut kind) feet h))))
                  (cons toward (remove #{toward} stairs/directions)))))

(def brink-drop 3)                    ; blocks of open air under a cell beside the feet that make the feet a brink no cut starts from

(defn brink?
  "Does one of the eight cells beside feet [x y z] at its height stand open over brink-drop or more
   open cells: a drop the bot can be pushed or step off into? Gym batch pearls-warped8-c (plan run
   39845223845, probe 39854206523) run 2 started dig-out's stairs up on a ledge over a lava lake;
   the stair's lay backed the bot off the edge, eleven blocks down into the lava, the plan failing
   :no-footing."
  [world [x y z]]
  (boolean (some (fn [[dx dz]]
                   (let [cx (+ x dx)
                         cz (+ z dz)]
                     (not-any? #(solid-at? world [cx (- y %) cz]) (range 0 (inc brink-drop)))))
                 (for [dx [-1 0 1] dz [-1 0 1] :when (not= [0 0] [dx dz])] [dx dz]))))

(defn dig-out
  "A way cut out of the pocket the bot stands in, for when the roam finds no end either: toward the
   nearest enderman within descend-radius, a walk to the end of the route that comes nearest it
   (path/plan's :partial or :none) while that end is past a walk's reach of the eye, and from
   there a :stairs-up to it or descend-step higher when it stands two or more above, a
   :stairs-down when two or more below, else a :tunnel as far as its steps stand on a floor
   (floored-length), along the heading whose
   first cut digs least (cheapest-heading); nil with no enderman tracked, no pickaxe held with
   spare digs (spare?), or a staircase from these feet boxed (:plan/boxed-at), and no cut from a brink (brink?: batch
   pearls-warped8-c's run 2 was backed off a ledge into lava). Cut-reach is not asked, as descend,
   ascend and bore ask it; the burrow's digs are kept: sim probes 39889946639 and 39889965974 (world R3, seeds 4 and 5) wore the pickaxe out on cuts out of pockets, and with no burrow to be cut fought calm ones in the open and were slain. Gym batch
   pearls-warped8-a (plan run 39731473452, probes 39776762205, 39776787768, 39778589970 and
   39780802996): runs 2 and 3 held 3 and 4 pearls by their 21st and 31st minutes and then stood
   in lookouts for four and a half hours, run 2 at y 45 in its own stairs and tunnels with 21
   digs left, run 3 on a ledge over a pit with 208, every route from the feet ending within a
   few blocks and every enderman past cut-reach."
  [world]
  (let [pos (:player/pos world)
        feet (path/feet-cell pos)
        [_ e] (entity/nearest (endermen world descend-radius) pos)]
    (when (and e (spare? world) (not (contains? (:plan/boxed-at world) (intent/feet world))))
      (let [c (cell (:entity/pos e))
            i {:intent/kind :walk :intent/target c :intent/for :pearls}
            to (or (last (:path/waypoints (path/plan world feet (intent/goal-of i) {}))) feet)
            rise (- (physics/y-of c) (physics/y-of feet))
            toward (mapv - (away world e))]
        (cond (> (physics/distance (game/eye world) (physics/centre to)) intent/reach)
              (assoc i :intent/target to)
              (brink? world feet) nil
              (>= rise 2)
              {:intent/kind :stairs-up
               :intent/to-y (+ (physics/y-of feet) (min rise descend-step))
               :intent/dir (cheapest-heading world :stairs-up feet toward)
               :intent/to-height? true
               :intent/for :pearls}
              (<= rise -2)
              {:intent/kind :stairs-down
               :intent/to-y (- (physics/y-of feet) (min (- rise) descend-step))
               :intent/dir (cheapest-heading world :stairs-down feet toward)
               :intent/for :pearls}
              :else
              (let [dir (cheapest-heading world :tunnel feet toward)
                    n (floored-length world feet dir bore-length)]
                (when (pos? n)
                  {:intent/kind :tunnel :intent/dir dir :intent/length n :intent/for :pearls})))))))

(defn cut-out
  "Stairs up out of the burrow's end, the other way from its mouth, for when the burrow is
   given up over an enderman in its stairs (blocked-out?): up to the mouth's height, along
   the heading from the mouth through the feet, on past the end (the way back along its own
   tunnel is the cheapest cut and the wrong one: it surfaces over the stairs the enderman
   stands in), with digs to spare past the next burrow's (spare?) and no staircase boxed
   from these feet. The stairs it stands in are never walked through (blocking), and once
   the burrow is stale the hunt out here cuts its next one where it stands. Nil with no
   mouth, no spare digs, or boxed feet."
  [world]
  (let [feet (intent/feet world)
        mouth (:pearls/mouth world)]
    (when (and mouth (spare? world) (not (contains? (:plan/boxed-at world) feet)))
      (let [[fx _ fz] feet
            [mx _ mz] mouth
            dx (- fx mx)
            dz (- fz mz)
            out (if (>= (abs dx) (abs dz)) [(if (neg? dx) -1 1) 0] [0 (if (neg? dz) -1 1)])]
        {:intent/kind :stairs-up
         :intent/to-y (physics/y-of mouth)
         :intent/dir out
         :intent/to-height? true
         :intent/for :pearls}))))

(defmethod intent/run :burrow
  ;; {:intent/kind :burrow :intent/dir [dx dz]}: stairs down burrow-depth (stairs.clj, refusing
  ;; water, lava and drops, turning where refused), then a tunnel burrow-length on along the
  ;; heading the stairs ended on: the stairs are four high (:intent/tall, mob/tall), so an
  ;; enderman follows down them, and the tunnel two high, so it stops at its mouth, two cells
  ;; from the bot (three-high stairs under a hill held it at the first: probe 39206015361).
  ;; First, while a cell over the mouth that an enderman standing in it would fill is solid
  ;; (mouth-cells), a child :dig of it: under a fungus's cap the mouth is two high, the
  ;; enderman never enters, and batch pearls-warped7-b's run 3 held six fights :unreachable
  ;; (probe 39732372050). Done when the feet stand in a shelter?, kept as :pearls/shelter, with
  ;; where it began as :pearls/mouth; failed :no-shelter when they do not, or with a child's
  ;; reason
  [world {:intent/keys [stage dir mouth] :as i} event]
  (let [mouth (or mouth (intent/feet world))
        roof (first (filter #(solid-at? world %) (mouth-cells mouth)))
        i (assoc i :intent/mouth mouth :intent/stage (or stage (if roof :roof :down)))
        world (assoc world :plan/intent i)]
    (case (:intent/stage i)
      :roof
      (cond (nil? roof) (intent/assoc-intent world :intent/stage :down)
            :else
            (stone/link world
                        i
                        event
                        (intent/child-dig roof)
                        (fn [w _] (intent/assoc-intent w :intent/stage :down))))
      :down
      (stone/link
       world
       i
       event
       {:intent/kind :stairs-down
        :intent/to-y (- (physics/y-of mouth) burrow-depth)
        :intent/dir dir
        :intent/tall (mob/tall :enderman)}
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

(defn at-site?
  "Has the bot just walked to a burrow site: the last intent a :walk for :shelter that ended done?
   There the burrow is cut away from the nearest enderman (at-hand) even with none in sight: sim
   probe 39861051222 (world R4, teleporting endermen) walked for fifty-seven minutes between a site,
   from which its quarry was hidden, and the walk toward that quarry, from which it showed again,
   three pearls held."
  [world]
  (let [{:intent/keys [kind for status]} (:plan/last world)]
    (and (= :walk kind) (= :shelter for) (= :done status))))

(def provoke-health 18.0)             ; health under which no enderman is provoked, from the mouth or anywhere: the bot rests in its burrow first (sim-endermen3's probe 39847287615: in worlds R1, R2 and R4 the first death came of a provoke 4 to 8 blocks off at 10.8 to 17.8 health just outside the burrow, the enderman landing three to five blows in five seconds); away from the mouth (a vantage) only at full health

(def danger-health combat/retreat-health) ; health under which the hunt never fights, provokes or goes toward an enderman: one enderman hit (7 on Normal, ns doc) plus a margin, the same line combat breaks a fight off at. At or under it the bot backs off, walks home, eats or rests where it stands until its health is back over it (issue #563, probe 40022662598: after the second stone sword was crafted the hunt re-fought an angry enderman it had just backed away from, again and again down to 1.3 health, one hit from death)

(defn hunt
  "The hunt's next step with no kill to pick up, in order: out of the burrow (shelter) with one
   coming (creepy), one at hand (within strike-range, blacklisted or not: one a held fight gave
   up on is still angry, and batch pearls-warped-b's bots, plan run 39135767198, walked out to
   it and were slain), under 10 health, or with another hostile within the :defend row's reach
   (threat: batch pearls-warped4-a's runs 3 and 4 were slain by mobs that row fought in the
   open at the forest's edge with a burrow cut), back to it (gym batch pearls-263b: a bot
   that met one in the open, or backed away hurt, died) when a route reaches it, else on as
   with no burrow, so that one coming at hand is fought (batch pearls-warped8-b, plan run
   39775345028, probe 39848848462: run 3's walk home from its first stair failed :no-path and
   it stood in :lookout while the enderman beside it killed it in three seconds); in it, the one coming, one at hand
   and angry (not one a held fight gave up on, blacklisted, unless within the sword's reach: sim probe 39890029962, worlds R1 and R4 seed 3, held three fights in a row on one by the mouth that never came, each timing out :unreachable, and the plan failed), or the threat fought where the bot stands (:fight :intent/hold?; at hand by the
   distance in three, never on the plane alone: batch pearls-warped-b's run 3 held a fight
   twenty minutes on one two blocks off on the plane and fourteen below, and batch
   pearls-warped-c's run 1 stood over one fourteen below for forty; a calm one at hand only
   within the sword's reach, combat/attack-reach, where the blow angers it, never one out of
   it, which does not come: batch pearls-warped4-b's arena run 3, plan run 39596287510, held
   one 3.8 off for 107 s from 21 s on, blacklisted it, and found the others gone); in the
   burrow with one standing in its stairs out of reach (blocking), a :lookout where it is,
   never a walk out through its body (batch pearls-warped4-a's run 2 failed :no-path three
   times to its mouth), and one given up on stood over for stale-lookouts (blocked-out?)
   gives the burrow up and the way out is cut up the other way from its end (cut-out: sim
   probe 40102602895's world R3 stood so from minute 26 to 60); with an angry one near the mouth, blacklisted or not (angry-near), in
   the burrow a :lookout and out of it the walk home, and the burrow never goes stale (batch
   pearls-warped5-a's run 4 gave its burrow up with one standing seven blocks off and was
   slain walking out); with no burrow, one at hand backed away
   from under 10 health, fought when it is coming or no pickaxe was ever held (worn-out?: with one spent on burrows, none struck) (a calm one given up on,
   a ghost, is left) (batch
   pearls-warped-a, plan run 39131478416: two landings in three walked up to a calm one, struck
   it in the open and died); hurt, a meal when the food is under hunger/heal-level (blaze/meal:
   health comes back only from there), and in the burrow a :lookout where it is, never out to
   the mouth (the same batch's run 3 went from the burrow's end to its mouth and back, hurt,
   for twelve minutes), and with no burrow and none coming a :lookout too, never a walk or a
   roam in the open (batch pearls-warped7-a, plan run 39714009099, probe 39723064504: run 5
   roamed the floor at 7 health with its burrow given up and no enderman within 32, and was
   slain at 36 minutes); in the burrow with an angry one near the mouth (angry-near) a
   :lookout, and after lure-lookouts of them since the last walk (lure?) a walk to the mouth to
   bring it, the angry one sending the bot straight back in from outside (batch
   pearls-warped7-a's run 2 stood thirty-two minutes over two that never came); no burrow near
   and one to provoke, coming or at hand, one dug there
   (:burrow, a pickaxe held) away from it, so the stare goes from its mouth, when one cut from
   the feet would end in a shelter (burrowable?), else a walk to the nearest cell one would
   (burrow-site; batch pearls-warped-e, plan run 39164914828, run 1: on broken ground the
   burrow failed :no-shelter six times where the bot stood, and it then provoked with no
   shelter and was slain); in the burrow, one
   provokable from where it stands provoked from there, and with one at the mouth (crowded?,
   blacklisted or not), a walk to a vantage within the burrow's reach from which it can be
   stared at (vantage), else a :lookout where it is; no provoke under provoke-health (in the
   burrow a :lookout, out of it the walk home, a meal first when the food will not heal), and one
   from a vantage away from the mouth only at full health; in the burrow with none coming, out to its mouth
   to look round (an angry one given up on that stands off the mouth never comes: batch
   pearls-warped-d, plan run 39160760074, run 1 took three pearls in six minutes, then stood in
   its burrow thirty-four over such ones); one not yet coming and provokable? from here within
   stare-range (quarry), provoked from the burrow's mouth, from anywhere within the burrow's
   reach with a route home (intent/routable?), or from anywhere with no pickaxe ever held to dig one
   (one worn out on burrows: next-intent waits :no-pickaxe instead; never one already coming: a provoke of it ends at once, and batch pearls-263b's bot, its
   pickaxe lost, provoked one 26 times in a row; with a burrow but no route home, a walk to the
   mouth first: the same run 1 provoked fifteen blocks from its burrow, and the walk home found
   no path); one coming and no burrow to meet it in, fought where the bot stands while a
   pickaxe is held (:fight :intent/hold?, as in the burrow: it teleports or rushes to ground
   the bot already stands on, and one that never comes times out :unreachable and is given
   up on; gym batch pearls-m10-a, plan run 40106206590, run 3 charged one across the open
   and fell into lava on the way, and a :lookout there instead stood the hunt still for as
   long as any angry one was tracked within stare-range, never given up on), charged only
   with no pickaxe ever held, as the arena row fights; with a burrow near and none
   provokable from here, a walk to a vantage within the burrow's reach from which one is
   (vantage), else a :lookout where it stands, and after stale-lookouts of them the burrow is
   given up (stale?): the hunt never walks out of its burrow's reach after an enderman (batch
   pearls-warped4-b's arena runs, plan run 39596287510, walked 16 and 55 blocks to ones hidden
   from the mouth, and run 2 met the dark's zombies, skeleton and creeper there with its burrow
   out of reach); given up over a calm enderman crowding its mouth (crowder), a walk to a cell a
   new burrow cut from would stare at it from its mouth (crowd-site), before any burrow cut
   where the bot stands: the one cut from the old end, or beside the crowder after the walk
   toward it, was the old burrow again (sim probe 39883402083); with no burrow, a walk to the nearest within seek-radius a
   route reaches (to a cell a burrow cut from would stare at it, stare-site, when a pickaxe is held and there is one: never up to it, where the burrow beside it provokes it point blank), or toward one as far as a route goes (toward); else a :lookout, standing where
   it is: no enderman yet is no failure (batch pearls-263c's warped forest landings failed
   :no-enderman and :no-route in 40 s); before the walk toward one, stairs down toward one far
   below within descend-radius (descend: batch pearls-warped-c's landings stood on ledges over
   the forest floor, and batch pearls-warped-d's run 2 walked toward one and another, the stairs
   down and up, between lookouts for forty minutes with none within seek-radius for six), and
   stairs up toward one as far above (ascend: batch pearls-warped5-b's run 5 descended to three
   endermen in a cave under the forest floor, killed them, and stood thirty-two minutes under
   the floor with every enderman 20 to 30 blocks over it; runs 2, 3 and 4 hunted under the
   floor the same way); after
   the walk toward, a tunnel toward the nearest on this level no route reaches (bore: the foot
   of a descent is inside the rock); after a lookout that found nothing, a roam somewhere else to
   look from, and with no end for a roam either, a way cut out of the pocket toward the nearest
   enderman (dig-out: batch pearls-warped8-a's runs 2 and 3 stood four and a half hours walled
   in, one in its own diggings, one on a ledge over a pit). The stairs down and the tunnel toward one are cut only with digs to spare past a
   burrow's (spare?): the burrow is what the pickaxe is for, and batch pearls-warped3-a's run 1
   wore its iron pickaxe out on them at fourteen minutes and had no burrow after. A walk toward
   one never ends where a walk already arrived (memory/arrived): the same batch's run 3 walked
   between two such ends for twenty-five minutes."
  [world]
  (let [pos (:player/pos world)
        [eid e] (at-hand world)
        [ceid ce] (entity/nearest
                   (into {} (filter (comp entity/creepy? val)) (endermen world stare-range))
                   pos)
        near? (and e (<= (physics/distance pos (:entity/pos e)) strike-range))
        angry? (and near? (entity/creepy? e) (not (contains? (:plan/blacklist world #{}) eid)))
        in-reach? (and near?
                       (<= (physics/distance (game/eye world) (combat/aim e)) combat/attack-reach))
        hurt? (< (:player/health world 20.0) danger-health)
        rested? (>= (:player/health world 20.0) provoke-health)
        meal (when-not rested? (blaze/meal world))
        home (shelter world)
        in? (and home (= home (intent/feet world)))
        at-end? (= (:pearls/shelter world) (intent/feet world))
        [tid _] (threat world)
        [bid _] (when at-end? (blocking world))
        [aid _] (when home (angry-near world))
        [qid q] (quarry world)
        ghost? (and near? (not angry?) (contains? (:plan/blacklist world #{}) eid))
        dir (when (and (nil? home)
                       (pickaxe? world)
                       (or ce q (and near? (not ghost?)) (and e (at-site? world))))
              (away world (or q ce e)))
        digs? (and dir (burrowable? world (intent/feet world) dir))
        site (when (and dir (not digs?)) (burrow-site world dir))
        csite (when (and (nil? home) (pickaxe? world) (not (at-site? world)) (stale? world))
                (crowd-site world))
        mouth (:pearls/mouth world)
        way-home {:intent/kind :walk :intent/target home :intent/for :shelter}
        far (endermen world seek-radius)]
    (cond
      (and home (not in?) (or ce near? hurt? tid aid) (intent/routable? world way-home)) way-home
      (and hurt? in?) (or meal {:intent/kind :lookout :intent/for :pearls})
      (and near? hurt? (not in?)) {:intent/kind :back-off :intent/target eid :intent/for :pearls}
      (and hurt? (not in?) tid) {:intent/kind :back-off :intent/target tid :intent/for :pearls}
      (and in? (or ce angry? in-reach? tid))
      {:intent/kind :fight
       :intent/target (cond ce ceid
                            (or angry? in-reach?) eid
                            :else tid)
       :intent/hold? true
       :intent/yield? true
       :intent/for :pearls}
      (and at-end? bid (blocked-out? world))
      (or (cut-out world) {:intent/kind :lookout :intent/for :pearls})
      (and near?
           (not in?)
           (or (entity/creepy? e)
               (and (not (pickaxe? world))
                    (not (worn-out? world))
                    (not (contains? (:plan/blacklist world #{}) eid)))))
      {:intent/kind :fight :intent/target eid :intent/for :pearls}
      meal meal
      (or hurt? (and in? bid)) {:intent/kind :lookout :intent/for :pearls}
      (and in? aid (lure? world)) {:intent/kind :walk :intent/target mouth :intent/for :shelter}
      (and in? aid) {:intent/kind :lookout :intent/for :pearls}
      csite {:intent/kind :walk :intent/target csite :intent/for :shelter}
      digs? {:intent/kind :burrow :intent/dir dir :intent/for :pearls}
      site {:intent/kind :walk :intent/target site :intent/for :shelter}
      (and in? qid rested?) {:intent/kind :provoke :intent/target qid :intent/for :pearls}
      (and in? qid) {:intent/kind :lookout :intent/for :pearls}
      (and in? (crowded? world))
      (or (vantage world far) {:intent/kind :lookout :intent/for :pearls})
      (and in? mouth) {:intent/kind :walk :intent/target mouth :intent/for :shelter}
      (and qid
           rested?
           (or (and (not (pickaxe? world)) (not (worn-out? world)))
               (= mouth (intent/feet world))
               (and home (>= (:player/health world 20.0) 20.0) (intent/routable? world way-home))))
      {:intent/kind :provoke :intent/target qid :intent/for :pearls}
      (and qid home rested?) {:intent/kind :walk :intent/target mouth :intent/for :shelter}
      (and qid home (intent/routable? world way-home)) way-home
      (and ce (pickaxe? world))
      {:intent/kind :fight
       :intent/target ceid
       :intent/hold? true
       :intent/yield? true
       :intent/for :pearls}
      ce {:intent/kind :fight :intent/target ceid :intent/for :pearls}
      home (or (vantage world far) {:intent/kind :lookout :intent/for :pearls})
      :else
      (or (if (pickaxe? world)
            (some (fn [[_ e]]
                    (when-let [c (stare-site world e)]
                      {:intent/kind :walk :intent/target c :intent/for :shelter}))
                  (->> far
                       (sort-by (fn [[_ e]] (physics/distance (:player/pos world) (:entity/pos e))))
                       (take candidates)))
            (when-not (worn-out? world)
              (when-let [[_ e] (routable world far)]
                {:intent/kind :walk :intent/target (cell (:entity/pos e)) :intent/for :pearls})))
          (descend world (endermen world descend-radius))
          (ascend world (endermen world descend-radius))
          (toward world far)
          (bore world (endermen world descend-radius))
          (when (= :lookout (:intent/kind (:plan/last world))) (or (roam world) (dig-out world)))
          {:intent/kind :lookout :intent/for :pearls}))))

(defn look-off
  "The look [yaw pitch], unless from the eye it stares at a calm enderman (not entity/creepy?;
   mob/stared?, the server's own cone and line test), then straight down at the same yaw."
  [world look]
  (let [eye (game/eye world)
        solid? (sight-fn world)]
    (if (some (fn [[_ e]]
                (and (not (entity/creepy? e)) (mob/stared? solid? eye look (:entity/pos e))))
              (endermen (dissoc world :plan/blacklist) stare-range))
      [(first look) 90.0]
      look)))

(defmethod plan/steer :pearls
  ;; out of the burrow's end, every look but a provoke's turned from a calm enderman's eyes
  ;; (look-off): a walk steers by :control/yaw and a dig or a place is judged by distance, so the
  ;; pitch is free. Sim probes 39887735653 and 39887752302 (seeds 4 and 5): four bots stared at
  ;; one two or three blocks off on a walk or a tunnel, out of the burrow, and were slain in the
  ;; open. In the end a stare by chance is what the hunt wants: the enderman comes to the sword
  [world _]
  (let [look (:control/look (:player/controls world))
        home (:pearls/shelter world)]
    (cond (or (nil? look)
              (= :provoke (:intent/kind (:plan/intent world)))
              (and home (= home (intent/feet world))))
          world
          :else (assoc-in world [:player/controls :control/look] (look-off world look)))))

(defn beset?
  "Does an angry enderman (entity/creepy?), blacklisted or not, stand within strike-range of the
   feet, height counted?"
  [world]
  (let [p (:player/pos world)]
    (boolean (some (fn [[_ e]]
                     (and (entity/creepy? e)
                          (<= (physics/distance p (:entity/pos e)) strike-range)))
                   (endermen (dissoc world :plan/blacklist) stare-range)))))

(def answers-to-hand #{:fight :back-off :eat :dig}) ; intents a running hunt keeps with an angry enderman at hand: the answers to it, a meal, a fire put out

(defmethod plan/interrupts? :pearls
  ;; any running intent but an answer (answers-to-hand) or the walk home ends with an angry
  ;; enderman at hand (beset?), and the hunt plans again: home, the fight held there, or the
  ;; fight where the bot stands. Nothing else ends a running intent: sim probes 39887735653 and
  ;; 39887752302 had a walk and a tunnel go on while one struck the bot from 20 health to none in
  ;; six to ten seconds, the bot carried on away from it. Never in the burrow's end, where none
  ;; reaches: sim probe 39892490709 (worlds R1 and R4, seed 3) cut its lookout there every tick
  ;; for twenty minutes over one given up on by the mouth, and the lure never counted a lookout
  [world _ i]
  (and (not (contains? answers-to-hand (:intent/kind i)))
       (not (and (= :walk (:intent/kind i)) (= (:intent/target i) (:pearls/shelter world))))
       (not= (:pearls/shelter world) (intent/feet world))
       (beset? world)))

(defmethod combat/answers :pearls
  ;; the hunt provokes endermen and fights them from its burrow; an angry one (entity/creepy?)
  ;; is a hostile the :defend row would otherwise walk out of the burrow after
  [_]
  #{:enderman})

(defmethod combat/answering :pearls
  ;; with a burrow near (shelter), every hostile tracked but the shunned kinds is the hunt's too
  ;; (threat): met from the burrow, where the :defend row would fight it in the open. With none,
  ;; the endermen alone, as answers says
  [world _]
  (cond-> (combat/answers :pearls)
    (shelter world)
    (into (comp (map (comp entity/kind val)) (remove shunned)) (entity/hostiles world))))

(def sword-acts
  "Intents that make a sword from what is already held: placing or opening a table, the craft,
   a dig that takes the table along, a toss that makes room for it."
  #{:place :craft :open-container :dig :toss})

(defn make-sword
  "The intent that makes the best sword the held items pay for (sword/payable) through the needs
   planner, when that craft can start from here: one of sword-acts, or the log chain to a
   remembered trunk (:intent/for :log) while the table's pieces are not already held
   (sword/pieces?). Nil when nothing is payable, or the planner would only wait, go looking for
   a tree, or chop another trunk with a log already in the grid. The Nether has no oak, and a
   wait there fails the plan. With no blade payable from the planks or cobblestone held but a
   log held, in the inventory or the grid (sword/with-laid: in the warped forest a stem, which
   counts as a log), a wooden sword is asked for instead: its planks come from the log, as a
   pickaxe's do (make-pickaxe). Gym batch pearls-m10-a (plan run 40106206590) run 2 held
   twenty-five warped stems and six pearls when its iron sword broke and failed :no-sword:
   sword/payable counts planks, and none were held. With no sword at all (combat/sword nil:
   the hands a death leaves empty), a wooden sword is asked for with nothing held either, and
   the log hunt for a trunk (:intent/for :log-hunt, wood/hunt) may start it: the craft is
   made where the bot stands rather than the plan failing :no-sword on the respawn (gym
   batches pearls-m10-a and pearls-m10-c, plan runs 40106206590 and 40133706786: each death
   was followed at once by the :no-sword wait). A worn sword still held is replaced only as
   before, never by a hunt for wood."
  [world goal table]
  (let [bare? (nil? (combat/sword world))]
    (when-let [k (or (sword/payable world)
                     (when (pos? (recipe/have (recipe/counts (:player/inventory (sword/with-laid
                                                                                 world)))
                                              (recipe/tags :logs)))
                       :wooden_sword)
                     (when bare? :wooden_sword))]
      (let [i (make/decide (sword/for-decide world)
                           (assoc goal :goal/provides {(keyword "item" (name k)) 1})
                           table)]
        (when (and (:intent/kind i)
                   (not (and (sword/pieces? world) (= :log (:intent/for i))))
                   (or (= :log (:intent/for i))
                       (and bare? (= :log-hunt (:intent/for i)))
                       (contains? sword-acts (:intent/kind i))))
          i)))))

(def pick-acts
  "Intents that make a pickaxe from what is already held: placing or opening a table, the craft,
   a dig that takes the table along, a toss that makes room for it."
  #{:place :craft :open-container :dig :toss})

(defn worn-pick?
  "Is this stack a pickaxe under spare-digs digs (window/swings-left)?"
  [item]
  (boolean (and (= :pickaxe (:kind (blocks/tool (:item item))))
                (some-> (window/swings-left item)
                        (< spare-digs)))))

(defn drop-worn-picks
  "A world whose worn pickaxes (worn-pick?) keep their slots but are not pickaxes, as
   sword/drop-worn does for swords: make/counts treats any pickaxe as the one a row provides,
   worn or fresh, and would make nothing while the worn one still counts."
  [world]
  (update
   world
   :player/inventory
   (fn [inv]
     (into {} (map (fn [[p item]] [p (if (worn-pick? item) (dissoc item :item) item)])) inv))))

(defn for-pick-decide
  "The world a pickaxe's needs are decided on: worn pickaxes hidden (drop-worn-picks), the
   crafting grid visible (sword/with-laid), and a finished log chain not continued once the
   table's pieces are held (sword/pieces?), as sword/for-decide does for a sword."
  [world]
  (cond-> (sword/with-laid (drop-worn-picks world))
    (and (sword/pieces? world) (= :log (:intent/for (:plan/last world)))) (dissoc :plan/last)))

(defn make-pickaxe
  "The intent that makes a pickaxe through the needs planner (a goal providing
   {:tag/pickaxes 1}, as blaze.clj's row asks), when that craft can start from here: one of
   pick-acts, or the log chain to a remembered trunk (:intent/for :log) while the table's
   pieces are not already held (sword/pieces?). Nil when the planner would only wait, or go
   out by stairs, a swim or a search for the wood. In a warped forest the trunk is a warped
   stem (blocks/log? counts stems, and the item tag :logs lists them beside the logs): stem to
   planks to sticks and a crafting table, then the pickaxe the held items reach, a stone one
   with cobblestone held, a wooden one without. Gym batch pearls-warped9-a (plan run
   39898400239) run 2 spent 239 digs on five pearls, about sixteen a burrow, and with eleven
   digs left stood in lookouts for four hours: no burrow could be cut, and no pickaxe remade.
   With no pickaxe at all (pickaxe? false: the hands a death leaves empty, respawned at the
   overworld spawn), the log hunt for a trunk (:intent/for :log-hunt, wood/hunt) may start
   it too, as it may a bare-handed sword (make-sword): gym batch pearls-w1 (plan run
   40387599204) run 1 died, remade its wooden sword from the spruces at spawn, then failed
   :no-pickaxe twenty seconds later with sticks and its table held, the hunt for the
   pickaxe's wood refused. A worn pickaxe still held is replaced only as before, never by
   a hunt for wood (wood/hunt is nil underground, so the Nether remake is unchanged)."
  [world goal table]
  (let [bare? (not (pickaxe? world))
        i (make/decide (for-pick-decide world) (assoc goal :goal/provides {:tag/pickaxes 1}) table)]
    (when (and (:intent/kind i)
               (not (and (sword/pieces? world) (= :log (:intent/for i))))
               (or (= :log (:intent/for i))
                   (and bare? (= :log-hunt (:intent/for i)))
                   (contains? pick-acts (:intent/kind i))))
      i)))

(defmethod plan/next-intent :pearls
  ;; a fire the bot stands in put out first (a :dig: the server breaks fire on the first click,
  ;; as blaze/put-out does); then the kill's drop, unless another angry one is near (menaced?);
  ;; with no serviceable sword (none, or one under window/spare-uses swings), make the one the
  ;; held items pay for when that craft can start (make-sword). With none at all and no craft,
  ;; a wait that fails the plan, never a bare-hand hunt
  ;; (:no-sword): gym batch pearls-warped8-b (plan run 39775345028) runs 1 and 3 respawned with
  ;; nothing held after their first deaths and hunted on bare-handed, 39 and 26 deaths in five
  ;; hours. Sim race 39909643851 (issue #563, world R3/13 seed 2) held a stone sword with nine
  ;; swings left, fought until it broke, and the same wait failed the plan. A worn sword that
  ;; cannot be replaced yet is still hunted with.
  ;; With no pickaxe over spare-digs (spare?) and none angry near (menaced?), a new one is made
  ;; first when that craft can start (make-pickaxe): a burrow is about sixteen digs, and batch
  ;; pearls-warped9-a's run 2 had eleven left at five pearls and stood in lookouts for four
  ;; hours. With the pickaxe worn out (worn-out?) and no craft to start, no burrow near and
  ;; none angry near, no hunt either (:no-pickaxe): no burrow can be cut, and
  ;; sim probe 39890964793 (seed 5) wore its pickaxe out on burrows, then struck calm endermen in
  ;; the open in worlds R2 and R3 and was slain, R2 nine times
  [world goal table]
  (let [k (when-not (menaced? world) (fresh-kill world))
        [_ d] (when k (drop-of world k))
        fire (burning-at world)
        replacing (when-not (sword/serviceable world) (make-sword world goal table))
        remaking (when (and (not (spare? world)) (not (menaced? world)))
                   (make-pickaxe world goal table))]
    (cond fire {:intent/kind :dig :intent/target fire}
          d
          {:intent/kind :collect-drops
           :intent/item pearl
           :intent/target (cell (:entity/pos d))
           :intent/for :pearls}
          (and k (body? world k)) {:plan/wait :drop}
          (< (:player/health world 20.0) danger-health) (hunt world)
          replacing replacing
          (nil? (combat/sword world)) {:plan/wait :no-sword}
          remaking remaking
          (and (worn-out? world) (nil? (shelter world)) (not (menaced? world)))
          {:plan/wait :no-pickaxe}
          :else (hunt world))))
