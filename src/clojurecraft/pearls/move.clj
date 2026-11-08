(ns clojurecraft.pearls.move
  "The pearl hunt's movers: roam, wander, explore, travel to a forest, relocate, and the cuts out of a pocket that no walk leaves."
  (:require [clojurecraft.avoided :as avoided]
            [clojurecraft.blaze :as blaze]
            [clojurecraft.combat :as combat]
            [clojurecraft.dimension :as dimension]
            [clojurecraft.entity :as entity]
            [clojurecraft.fortress-exit :as fexit]
            [clojurecraft.game :as game]
            [clojurecraft.intent :as intent]
            [clojurecraft.memory :as memory]
            [clojurecraft.path :as path]
            [clojurecraft.pearls.params :as pparams]
            [clojurecraft.pearls.place :as pplace]
            [clojurecraft.pearls.sense :as psense]
            [clojurecraft.pearls.upkeep :as pupkeep]
            [clojurecraft.physics :as physics]
            [clojurecraft.stairs :as stairs]))

(set! *warn-on-reflection* true)

; how far a roam looks for an end not yet walked to, nearest first: sixteen, thirty-two, forty-eight, sixty-four blocks
(defn roam-goal
  "The path goal of a roam along heading [dx dz] from the feet, reach blocks out (roam-reach
   when not given): any cell more than twice reach, less one, on the plane (path's :afar, by
   its octile) from the point reach behind the feet, which the cells reach or more ahead
   satisfy at any height, and the cells beside and behind do not. The route finds the way to
   one: along a cave's passage, up a cliff's side, round a lake of lava. Never by the height
   alone (path's :away counts it), or a roam walks its own staircase up and down."
  ([feet heading] (roam-goal feet heading pparams/roam-reach))
  ([[x y z] [dx dz] reach]
   {:goal/kind :afar
    :goal/pos [(- x (* dx reach)) y (- z (* dz reach))]
    :goal/range (dec (* 2 reach))}))

(defn roam
  "A walk to look round from somewhere else: along the first of the eight headings, from one the
   clock picks (roam-turn-ms a heading), to the end of a route that reaches a roam-goal cell
   (path/plan :found, its last node), roam-reach out first and then each of roam-reaches
   farther; nil when no heading has one at any reach. Never to an end a walk already arrived at
   (memory/arrived): it is somewhere else that is wanted. With no route reaching any roam-goal
   (:partial, or :none), the farthest end those routes came to, roam-reach or more from the feet
   on the plane and not spent, is walked to instead, as toward does: from there the routes are
   planned again, so the search chains outward past one search's budget. With every end a route
   reaches spent, the farthest of the spent ends is walked to again, since the forest there has
   had the walk's minutes to spawn into. Never one of the two ends the last walks arrived at
   (memory/newest-dones), and never a spent end when an enderman is tracked within
   descend-radius (the hunt cuts toward it with dig-out instead). With both refused the hunt
   digs out or rests where it stands."
  [world]
  (let [feet (intent/feet world)
        start (mod (quot (:time/now world 0) pparams/roam-turn-ms) (count pparams/headings))
        spent (memory/arrived world)
        recent (into #{} (map :intention/target) (memory/newest-dones world :walk 2))
        tries (for [reach pparams/roam-reaches k (range (count pparams/headings))] [reach k])
        plan-to (fn [[reach k]]
                  (let [heading (nth pparams/headings (mod (+ start k) (count pparams/headings)))
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
        (let [[_ partial _ spent-end]
              (reduce (fn [[pg p sg s] [status to]]
                        (let [gain (when to (path/octile feet to))]
                          (cond (and gain
                                     (not= :found status)
                                     (>= gain pparams/roam-reach)
                                     (not (contains? spent to))
                                     (> gain pg))
                                [gain to sg s]
                                (and gain
                                     (= :found status)
                                     (empty? (psense/endermen world pparams/descend-radius))
                                     (not (contains? recent to))
                                     (> gain sg))
                                [pg p gain to]
                                :else [pg p sg s])))
                      [0 nil 0 nil]
                      planned)]
          (when-let [to (or partial spent-end)] (walk to))))))

(defn routable
  "[eid entity] of the first of the candidates nearest endermen past a walk's reach of the eye
   (intent/reach: nearer, the walk arrives where it begins and is planned again every tick) that
   a route reaches (intent/routable?), or nil."
  [world ms]
  (let [pos (:player/pos world)
        eye (game/eye world)]
    (->> ms
         (sort-by (fn [[_ e]] (physics/distance pos (:entity/pos e))))
         (take pparams/candidates)
         (some (fn [[_ e :as ee]]
                 (let [c (combat/cell (:entity/pos e))]
                   (when (and (> (physics/distance eye (physics/centre c)) intent/reach)
                              (intent/routable? world {:intent/kind :walk :intent/target c}))
                     ee)))))))

(defn toward
  "A walk toward the nearest of the candidates nearest endermen in ms when no route reaches one
   (routable found none): to the end of the route that comes nearest it (path/plan's :partial or
   :none, the way as far as it goes), when that end is toward-gain or more nearer it than the
   feet on the plane (path/octile), past a walk's reach of the eye (intent/reach: nearer, the
   walk arrives where it starts and is planned again every tick) and not inside the hunt's own
   burrow (within burrow-span of :pearls/shelter); nil when no route gains on any. The way as far
   as it goes changes what is in sight, and from the new place the route is asked again. The
   height is the stairs' to gain (descend), not the walk's. Never to an end a walk has already
   arrived at (memory/arrived)."
  [world ms]
  (let [pos (:player/pos world)
        feet (path/feet-cell pos)
        home (:pearls/shelter world)
        spent (memory/arrived world)]
    (->> ms
         (sort-by (fn [[_ e]] (physics/distance pos (:entity/pos e))))
         (take pparams/candidates)
         (some (fn [[_ e]]
                 (let [c (combat/cell (:entity/pos e))
                       i {:intent/kind :walk :intent/target c :intent/for :pearls}
                       to (last (:path/waypoints (path/plan world feet (intent/goal-of i) {})))]
                   (when (and to
                              (not (contains? spent to))
                              (>= (- (path/octile feet c) (path/octile to c)) pparams/toward-gain)
                              (> (physics/distance (game/eye world) (physics/centre to))
                                 intent/reach)
                              (not (and home (<= (physics/distance to home) pparams/burrow-span))))
                     (assoc i :intent/target to))))))))

(defn descend
  "Stairs down toward the first of the candidates nearest endermen in ms that stands
   descend-drop or more below the feet within cut-reach on the plane (within-cut-reach?), a
   pickaxe held with digs to spare past a burrow's (spare?): a :stairs-down to descend-step
   lower, or to its height, cut the straight way toward it (the opposite of away); nil when none
   stands so low and so near, no pickaxe with spare digs is held, or a staircase from these feet
   was boxed (:plan/boxed-at; a roam moves it first). The forest floor lies under its ledges: a
   route refuses the drop, a staircase takes it. Only toward one near on the plane, since a
   flight is eight blocks and about twenty digs. Not only with none on this level, as ascend
   asks."
  [world ms]
  (let [pos (:player/pos world)
        fy (physics/y-of (path/feet-cell pos))]
    (when (and (pupkeep/spare? world)
               (not (contains? (avoided/all-of world :boxed-at) (intent/feet world))))
      (->> ms
           (sort-by (fn [[_ e]] (physics/distance pos (:entity/pos e))))
           (take pparams/candidates)
           (some (fn [[_ e]]
                   (let [drop (- fy (physics/y-of (combat/cell (:entity/pos e))))
                         to-y (max (- fy (min drop pparams/descend-step))
                                   (pplace/lava-floor world))]
                     (when (and (>= drop pparams/descend-drop)
                                (pplace/within-cut-reach? world e)
                                (< to-y fy))
                       {:intent/kind :stairs-down
                        :intent/to-y to-y
                        :intent/dir (mapv - (pplace/away world e))
                        :intent/for :pearls}))))))))

(defn ascend
  "Stairs up toward the first of the candidates nearest endermen in ms that stands descend-drop
   or more above the feet within cut-reach on the plane (within-cut-reach?, as descend asks), a
   pickaxe held with digs to spare past a burrow's (spare?): a :stairs-up to descend-step higher,
   or to its height (:intent/to-height?: it ends at that height, not where the ground opens), cut
   the straight way toward it; nil when none stands so high and so near, no pickaxe with spare
   digs is held, or a staircase from these feet was boxed (:plan/boxed-at). The way back up that
   descend has no answer to: the forest's endermen spawn on its floor, and a bot under it hunts
   nothing. Only with none of ms on this level (less than descend-drop above or below the feet):
   one on this level is walked toward, tunnelled toward or looked out for first."
  [world ms]
  (let [pos (:player/pos world)
        fy (physics/y-of (path/feet-cell pos))]
    (when (and (pupkeep/spare? world)
               (not (contains? (avoided/all-of world :boxed-at) (intent/feet world)))
               (not-any? (fn [[_ e]] (pplace/level? world e)) ms))
      (->> ms
           (sort-by (fn [[_ e]] (physics/distance pos (:entity/pos e))))
           (take pparams/candidates)
           (some (fn [[_ e]]
                   (let [rise (- (physics/y-of (combat/cell (:entity/pos e))) fy)]
                     (when (and (>= rise pparams/descend-drop) (pplace/within-cut-reach? world e))
                       {:intent/kind :stairs-up
                        :intent/to-y (+ fy (min rise pparams/descend-step))
                        :intent/dir (mapv - (pplace/away world e))
                        :intent/to-height? true
                        :intent/for :pearls}))))))))

(defn bore
  "A tunnel (:tunnel, stairs.clj: level steps, turning where one is refused) the straight way
   toward the nearest enderman in ms, as far as its steps stand on a floor, bore-length at
   most (floored-length), standing less than descend-drop above or below the feet and within
   cut-reach on the plane (within-cut-reach?), a pickaxe held with digs to spare past a burrow's
   (spare?) and no staircase boxed from these feet (:plan/boxed-at); nil otherwise. For when no
   route reaches one and none gains on one (toward): the bot stands enclosed, at the foot of its
   own descent inside the rock. Near on the plane only, since a tunnel of eight is sixteen digs,
   and never toward one nearer than bore-length on the plane: the tunnel would run past it."
  [world ms]
  (let [pos (:player/pos world)
        feet (intent/feet world)
        [_ e] (entity/nearest ms pos)]
    (when (and e
               (pupkeep/spare? world)
               (not (contains? (avoided/all-of world :boxed-at) feet))
               (< (abs (- (physics/y-of (:entity/pos e)) (physics/y-of feet))) pparams/descend-drop)
               (pplace/within-cut-reach? world e)
               (>= (path/octile feet (combat/cell (:entity/pos e))) pparams/bore-length))
      (let [dir (mapv - (pplace/away world e))
            n (pplace/floored-length world feet dir pparams/bore-length)]
        (when (and (pos? n) (pplace/solid-cut? world :tunnel feet dir n))
          {:intent/kind :tunnel :intent/dir dir :intent/length n :intent/for :pearls})))))

(defn cheapest-heading
  "The heading of the four straight ones (stairs/directions) whose first cut of kind (:stairs-up,
   :stairs-down or :tunnel) from feet opens the fewest solid cells (solid-or-unloaded?), toward first
   among equals. Where the rock is already cut a stair costs no digs, and a step a later cut took
   out is laid (stairs/up-lay)."
  [world kind feet toward]
  (first (sort-by (fn [h]
                    (count (filter #(pplace/solid-or-unloaded? world %)
                                   ((pparams/first-cut kind) feet h))))
                  (cons toward (remove #{toward} stairs/directions)))))

(defn dig-out
  "A way cut out of the pocket the bot stands in, for when the roam finds no end either: toward the
   nearest enderman within descend-radius, a walk to the end of the route that comes nearest it
   (path/plan's :partial or :none) while that end is past a walk's reach of the eye, and from
   there a :stairs-up to it or descend-step higher when it stands two or more above, a
   :stairs-down when two or more below, else a :tunnel as far as its steps stand on a floor
   (floored-length), along the heading whose first cut digs least (cheapest-heading); nil with no
   enderman tracked, no pickaxe held with spare digs (spare?), or a staircase from these feet
   boxed (:plan/boxed-at), and no cut from a brink (brink?). Cut-reach is not asked, as descend,
   ascend and bore ask it; the burrow's digs are kept."
  [world]
  (let [pos (:player/pos world)
        feet (path/feet-cell pos)
        [_ e] (entity/nearest (psense/endermen world pparams/descend-radius) pos)]
    (when (and e
               (pupkeep/spare? world)
               (not (contains? (avoided/all-of world :boxed-at) (intent/feet world))))
      (let [c (combat/cell (:entity/pos e))
            i {:intent/kind :walk :intent/target c :intent/for :pearls}
            to (or (last (:path/waypoints (path/plan world feet (intent/goal-of i) {}))) feet)
            rise (- (physics/y-of c) (physics/y-of feet))
            toward (mapv - (pplace/away world e))]
        (cond (> (physics/distance (game/eye world) (physics/centre to)) intent/reach)
              (assoc i :intent/target to)
              (pplace/brink? world feet) nil
              (>= rise 2)
              {:intent/kind :stairs-up
               :intent/to-y (+ (physics/y-of feet) (min rise pparams/descend-step))
               :intent/dir (cheapest-heading world :stairs-up feet toward)
               :intent/to-height? true
               :intent/for :pearls}
              (and (<= rise -2)
                   (< (max (- (physics/y-of feet) (min (- rise) pparams/descend-step))
                           (pplace/lava-floor world))
                      (physics/y-of feet)))
              {:intent/kind :stairs-down
               :intent/to-y (max (- (physics/y-of feet) (min (- rise) pparams/descend-step))
                                 (pplace/lava-floor world))
               :intent/dir (cheapest-heading world :stairs-down feet toward)
               :intent/for :pearls}
              :else
              (let [dir (cheapest-heading world :tunnel feet toward)
                    n (pplace/floored-length world feet dir pparams/bore-length)]
                (when (and (pos? n) (pplace/solid-cut? world :tunnel feet dir n))
                  {:intent/kind :tunnel :intent/dir dir :intent/length n :intent/for :pearls})))))))

(defn stuck?
  "Has the bot stood stale-lookouts or more :lookout intents since its last walk ended
   (memory/done-since), or since its burrow was cut when no walk has ended since? A minute
   of lookouts with no walk taken, as stale? counts them in a burrow, out of one."
  [world]
  (>= (or (memory/done-since world :lookout :walk) (memory/done-since world :lookout :burrow) 0)
      pparams/stale-lookouts))

(defn climb-heading
  "A heading from feet to cut stairs up into rock toward the surface: one that cuts solid
   blocks (solid-cut?) and whose stair step does not step onto an open brink (brink?). Sorted
   by cheapest first."
  [world feet]
  (let [ways (sort-by (fn [h]
                        (count (filter #(pplace/solid-or-unloaded? world %)
                                       ((pparams/first-cut :stairs-up) feet h))))
                      stairs/directions)]
    (some (fn [[dx dz :as h]]
            (let [step [(+ (first feet) dx) (inc (second feet)) (+ (last feet) dz)]]
              (when (and (pplace/solid-cut? world :stairs-up feet h)
                         (not (pplace/brink? world step)))
                h)))
          ways)))

(defn roof-cap
  "The height a stair up from y stops at: y + descend-step, in the Nether at most
   nether-climb-ceiling, a little under the bedrock roof. Past the ceiling the bot digs and
   tunnels out level (dig-out, tunnel-out), it does not climb: under the roof neither a warped
   forest nor an enderman is."
  [world y]
  (let [up (+ y pparams/descend-step)]
    (if (= dimension/nether (dimension/of world)) (min up pparams/nether-climb-ceiling) up)))

(defn climb-out
  "Stairs up from the feet, descend-step higher along the heading whose first cut digs
   into rock without stepping onto an open brink (climb-heading), for a pocket no roam finds an
   end out of and no enderman tracked gives dig-out a way to cut: up is toward the forest floor
   the endermen spawn on, and from the top the roam is asked again. Nil with no digs to spare
   past a burrow's (spare?), or a staircase boxed from these feet (:plan/boxed-at)."
  [world]
  (let [feet (intent/feet world)]
    (when (and (pupkeep/spare? world) (not (contains? (avoided/all-of world :boxed-at) feet)))
      (when-let [dir (when (< (physics/y-of feet) (dec (roof-cap world (physics/y-of feet))))
                       (climb-heading world feet))]
        {:intent/kind :stairs-up
         :intent/to-y (roof-cap world (physics/y-of feet))
         :intent/dir dir
         :intent/to-height? true
         :intent/for :pearls}))))

(defn tunnel-out
  "A :tunnel of up to bore-length steps along the heading of stairs/directions the clock picks (a
   new one every thirty seconds, so a refused heading is followed by another), in the Nether, with
   digs to spare (spare?), a staircase not boxed from these feet, a floor under the steps
   (floored-length) and rock to cut (solid-cut?); nil otherwise. The Nether has no open sky to
   climb to."
  [world]
  (let [feet (intent/feet world)
        dir (nth stairs/directions (mod (quot (:time/now world 0) 30000) (count stairs/directions)))
        n (pplace/floored-length world feet dir pparams/bore-length)]
    (when (and (= dimension/nether (dimension/of world))
               (pupkeep/spare? world)
               (not (contains? (avoided/all-of world :boxed-at) feet))
               (pos? n)
               (pplace/solid-cut? world :tunnel feet dir n))
      {:intent/kind :tunnel :intent/dir dir :intent/length n :intent/for :pearls})))

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
    (when (and mouth (pupkeep/spare? world) (not (contains? (avoided/all-of world :boxed-at) feet)))
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

(defn loitering?
  "Did the newest loiter-walks :walk intentions to end :done all end within loiter-radius of the
   feet (memory/newest-dones, their targets)? Then the hunt has shuffled in one place however
   many lookouts it stood between the walks, each walk resetting the count of lookouts since the
   last."
  [world]
  (let [feet (intent/feet world)
        ds (memory/newest-dones world :walk pparams/loiter-walks)]
    (and (= pparams/loiter-walks (count ds))
         (every? (fn [d]
                   (let [t (:intention/target d)]
                     (and (vector? t) (<= (path/octile feet t) pparams/loiter-radius))))
                 ds))))

(defn wandering?
  "Has the hunt stood wander-lookouts or more :lookout intents with no walk ended since (stuck?
   counts the same, from stale-lookouts), and does no enderman tracked have a route to it
   (routable), however near it is by the air? Or is it loitering (loitering?), whatever is
   tracked."
  [world]
  (boolean (or (loitering? world)
               (and (>= (or (memory/done-since world :lookout :walk)
                            (memory/done-since world :lookout :burrow)
                            0)
                        pparams/wander-lookouts)
                    (nil? (routable world (psense/endermen world pparams/seek-radius)))))))

(defn far-ends
  "Where each of the eight headings' routes (path/plan toward ground wander-reach out, from index
   start in headings) ends, as [status end gain] with gain the end's octile distance from the feet:
   the ends roam-reach or more away and not in spent, nor within roam-reach of where one of the
   last explore-memory walks arrived (an end near one already walked to is not new ground),
   :found first, then the farthest."
  [world start spent]
  (let [feet (intent/feet world)
        visited (into []
                      (keep #(when (vector? (:intention/target %)) (:intention/target %)))
                      (memory/newest-dones world :walk pparams/explore-memory))
        ends (for [k (range (count pparams/headings))
                   :let [heading (nth pparams/headings (mod (+ start k) (count pparams/headings)))
                         r (path/plan world
                                      (path/feet-cell (:player/pos world))
                                      (roam-goal feet heading pparams/wander-reach)
                                      {})
                         to (last (:path/waypoints r))]
                   :when (and to
                              (not= to feet)
                              (not (contains? spent to))
                              (not-any? #(< (path/octile to %) pparams/roam-reach) visited))]
               [(:path/status r) to (path/octile feet to)])
        far (filter #(>= (nth % 2) pparams/roam-reach) ends)]
    (concat (filter #(= :found (first %)) far)
            (sort-by #(- (nth % 2)) (remove #(= :found (first %)) far)))))

(defn far-walk
  "A walk to ground far from the feet: to the end of the first of the eight headings (headings,
   from index start) whose route reaches ground wander-reach out (path/plan :found, as roam looks)
   and is not in spent, else the farthest end of those routes that comes roam-reach or more from
   the feet and is not in spent. Nil when no heading has a route that leaves the feet."
  [world start spent]
  (when-let [best (first (far-ends world start spent))]
    {:intent/kind :walk :intent/target (second best) :intent/for :pearls}))

(defn wander
  "A far-walk from the heading the clock picks, nothing spent. Not roam: it refuses an end already
   walked to while an enderman is tracked within descend-radius, and that stays true for hours."
  [world]
  (far-walk world
            (mod (quot (:time/now world 0) pparams/roam-turn-ms) (count pparams/headings))
            #{}))

(defn explore
  "A walk to the end (far-ends) farthest from the targets of the last explore-memory walks arrived
   at (memory/newest-dones), ground not arrived at (memory/arrived) only: the widening search for
   a warped forest none is known of goes on away from where it has been."
  [world]
  (let [spent (memory/arrived world)
        feet (intent/feet world)
        been (conj (vec (keep #(when (vector? (:intention/target %)) (:intention/target %))
                              (memory/newest-dones world :walk pparams/explore-memory)))
                   feet)
        ends (far-ends world 0 spent)]
    (when (seq ends)
      (let [away (fn [[_ to _]] (apply min (map #(path/octile to %) been)))
            best (apply max-key away ends)]
        {:intent/kind :walk :intent/target (second best) :intent/for :pearls}))))

(defn forest-way
  "The way to target, a cell over a warped forest's nylium: a walk when a route reaches it
   (intent/routable?), else, with a pickaxe held, a sealed tunnel toward it (a :tunnel-to, sealing
   behind as the fortress exit bores; it climbs and descends to the target's height), else the walk
   (a walk with no route fails :no-path, and the hunt chooses again)."
  [world target]
  (let [walk {:intent/kind :walk :intent/target target :intent/for :pearls}]
    (cond (intent/routable? world (assoc walk :intent/max-nodes pparams/vantage-nodes)) walk ; a capped test: travel is asked several times a tick (#766)
          (pupkeep/pickaxe? world)
          {:intent/kind :tunnel-to
           :intent/target target
           :intent/reach 1.5
           :intent/seal-behind? true
           :intent/for :pearls}
          :else walk)))

(defn travel
  "The way to a warped forest (forest-way: a walk, or a sealed tunnel when no walk reaches it), or
   nil: none in the Nether when the bot is in one (in-forest?); to the cell over the nylium nearest
   of the nearest known forest chunk (nearest-forest), else explore for one."
  [world]
  (when (and (= dimension/nether (dimension/of world)) (not (psense/in-forest? world)))
    (cond (and (fexit/fortress-escaping? world) (fexit/can-tunnel-out? world))
          (fexit/fortress-tunnel-out world)
          (and (fexit/fortress-escaping? world) (not (fexit/can-tunnel-out? world)))
          (if-let [pos (psense/nearest-forest world)]
            (forest-way world (fexit/room-waypoint world (update pos 1 inc)))
            (explore world))
          :else
          (if-let [pos (psense/nearest-forest world)]
            (forest-way world
                        (if (fexit/near-fortress? world)
                          (fexit/room-waypoint world (update pos 1 inc))
                          (update pos 1 inc)))
            (explore world)))))

(defn travel-caged?
  "Is the bot in the Nether, in no warped forest, with no enderman tracked, and no walk to or toward
   one (travel) to be had: sealed in a pocket whose routes end within sixteen blocks?"
  [world]
  (boolean (and (= dimension/nether (dimension/of world))
                (not (psense/in-forest? world))
                (empty? (psense/endermen world pparams/seek-radius))
                (nil? (travel world)))))

(defn shelter-walk
  "A :walk for :shelter to target, or nil when target is nil or in :plan/blacklist (a walk to it
   failed, plan/fail-intent). A walk is tried once before it is known to fail: a fence between
   the bot and its mouth is walked at first, and given up after."
  [world target]
  (when (and target (not (contains? (avoided/all-of world :blacklist) target)))
    (combat/shelter-walk target)))

(defn uncage
  "A walk down to the burrow's end (:pearls/shelter) when the bot stands at a brink (brink?) and
   the end is within shelter-reach and routable: climb-out, dig-out and bore refuse to cut from
   a brink, so a bot on a ledge over a cavern, the burrow behind it, has no move. From the end,
   in rock, the cuts are allowed."
  [world]
  (let [feet (intent/feet world)
        s (:pearls/shelter world)
        walk (when (and s
                        (not= s feet)
                        (<= (physics/distance feet s) pparams/shelter-reach)
                        (pplace/brink? world feet))
               (shelter-walk world s))]
    (when (and walk (intent/routable? world walk)) walk)))

(defn relocate-walk
  "A walk to leave this place for another, or nil: toward the densest of the remembered endermen
   (blacklisted or not, by how many others stand within cluster-radius of each, nearest first)
   that stand relocate-dist or more from the feet: to the first a route reaches (routable), else
   as far as the route goes (toward), else explore, else wander, else the cage-break cuts
   (dig-out, tunnel-out, climb-out, uncage) from a pocket nothing walks out of."
  [world]
  (let [pos (:player/pos world)
        all (psense/endermen (avoided/forget world :blacklist) 128.0)
        far (into {}
                  (filter (fn [[_ e]]
                            (>= (physics/distance pos (:entity/pos e)) pparams/relocate-dist)))
                  all)
        density (fn [[_ e]]
                  (count (filter (fn [[_ o]]
                                   (<= (physics/distance (:entity/pos e) (:entity/pos o))
                                       pparams/cluster-radius))
                                 all)))
        ranked (sort-by (juxt (comp - density) #(physics/distance pos (:entity/pos (second %))))
                        far)
        ms (into {} (take pparams/candidates ranked))
        w (or (when (seq ms)
                (if-let [[_ e] (routable world ms)]
                  {:intent/kind :walk :intent/target (combat/cell (:entity/pos e))}
                  (toward world ms)))
              (explore world)
              (wander world)
              (if (= dimension/nether (dimension/of world))
                (or (dig-out world) (tunnel-out world) (climb-out world) (uncage world))
                (or (climb-out world) (dig-out world) (uncage world))))]
    (cond-> w (= :walk (:intent/kind w)) (assoc :intent/for :relocate))))

(defn distant-gave-up?
  "Has sighting eid been given up: chase-max-fails failed goes toward it, or a chase of
   chase-giveup-ms, recorded in :pearls/chase (track-distant)? A given-up sighting is
   blacklisted by track-distant, so endermen leaves it out and the chase never churns."
  [world eid]
  (let [c (:pearls/chase world)]
    (boolean (and c
                  (= eid (:eid c))
                  (or (>= (:fails c 0) pparams/chase-max-fails)
                      (>= (- (:time/now world 0) (:since c 0)) pparams/chase-giveup-ms))))))

(defn distant-sighting
  "[eid entity] of the nearest tracked enderman beyond hunt-radius and within
   sighting-radius, not given up on (distant-gave-up?), or nil. These are the sightings the
   hunt never acted on: on 0ace52e every first sighting of 23 real rod bots was 54 to 105
   blocks off, past hunt-radius 32, so endermen filtered it out before quarry or any fight
   row could see it, and stare/provoke stayed 0 of 141."
  [world]
  (let [pos (:player/pos world)]
    (->> (psense/endermen world pparams/sighting-radius)
         (filter (fn [[eid e]]
                   (and (> (physics/distance pos (:entity/pos e)) pparams/hunt-radius)
                        (not (distant-gave-up? world eid)))))
         (sort-by (fn [[_ e]] (physics/distance pos (:entity/pos e))))
         first)))

(defn distant-stand
  "A standable cell near enderman e from which the hunt can take over, or nil: within
   hunt-radius of it (so the enderman is hunted once the bot stands there) and at least
   stare-from away, a few blocks above or below its own level at most, not a cell a walk
   failed toward (:plan/blacklist) or already arrived at, nearest the feet first and
   vantage-tries at most, the first a route reaches (intent/routable?). Not the
   enderman's own cell: a sighting 20 to 60 blocks above or below on another fortress or
   terrain level is met from ground near it on its level, and the route, the stairs
   (descend, ascend) and the tunnel close the rest once the bot is near on the plane."
  [world e]
  (let [feet (intent/feet world)
        eye (game/eye world)
        bl (avoided/all-of world :blacklist)
        spent (memory/arrived world)
        [ex ey ez] (combat/cell (:entity/pos e))]
    (->> (for [dx (range -16 17) dz (range -16 17) dy (range -3 4)] [(+ ex dx) (+ ey dy) (+ ez dz)])
         (filter (fn [c]
                   (let [d (physics/distance (physics/centre c) (:entity/pos e))]
                     (and (<= pparams/stare-from d pparams/hunt-radius)
                          (not (contains? bl c))
                          (not (contains? spent c))
                          (> (physics/distance eye (physics/centre c)) intent/reach)
                          (pplace/standable? world c)))))
         (sort-by #(physics/distance feet %))
         (take pparams/vantage-tries)
         (some (fn [c]
                 (when (intent/routable? world
                                         {:intent/kind :walk :intent/target c :intent/for :chase})
                   c))))))

(defn go-toward-sighting
  "The go toward the nearest distant sighting (sighting), or nil: only out of the fortress
   (not fortress-escaping?), with no burrow held (shelter), safe (health over danger-health,
   no blaze watching (blaze/watchers)), and never under danger health. A walk to a reachable
   stand near it (distant-stand); with none reachable, a walk as far as the route goes
   toward it (toward); with no route gaining either, a sealed level tunnel toward its
   ground (a :tunnel-to at the feet's height, no-down, seal-behind, as the fortress exit
   bores) when a pickaxe is held, it stands on this level (level?) and farther than
   bore-length on the plane. The hunt asks it only after its own rows (the burrow, the
   stairs toward one above or below, the relocation, the dig-out) have had their say, and
   the pearl stage asks it directly as its fallback (pearl-fallback).
   Tagged :intent/for :chase with :intent/chase-eid, so track-distant counts its failures
   and gives the sighting up after chase-max-fails of them."
  [world]
  (when (and (not (fexit/fortress-escaping? world))
             (nil? (pplace/shelter world))
             (>= (:player/health world 20.0) pparams/danger-health)
             (empty? (blaze/watchers world)))
    (when-let [[eid e] (distant-sighting world)]
      (let [feet (intent/feet world)
            tag (fn [i] (assoc i :intent/for :chase :intent/chase-eid eid))]
        (or (when-let [c (distant-stand world e)] (tag {:intent/kind :walk :intent/target c}))
            (when-let [i (toward world {eid e})] (tag i))
            (when (and (pupkeep/pickaxe? world)
                       (pplace/level? world e)
                       (> (path/octile feet (combat/cell (:entity/pos e))) pparams/bore-length))
              (tag {:intent/kind :tunnel-to
                    :intent/target [(first (combat/cell (:entity/pos e))) (second feet)
                                    (nth (combat/cell (:entity/pos e)) 2)]
                    :intent/reach 1.5
                    :intent/climb 0
                    :intent/no-down? true
                    :intent/seal-behind? true})))))))

(defn track-distant
  "The world with :pearls/chase kept for the running sighting chase: a new chase intent for
   another enderman starts it, a failed chase intent (:plan/failed, for :chase, not yet
   counted) adds a failure, and a given-up sighting (distant-gave-up?) joins :plan/blacklist
   so it is never chased again. Called from the :pearls steer every tick."
  [world]
  (let [i (:plan/intent world)
        f (:plan/failed world)
        c (:pearls/chase world)
        eid (when (and i (= :chase (:intent/for i))) (:intent/chase-eid i))
        world (cond (and eid (not= eid (:eid c)))
                    (assoc world :pearls/chase {:eid eid :fails 0 :since (:time/now world 0)})
                    (and f
                         (= :chase (:intent/for f))
                         (:intent/chase-eid f)
                         (not= (:intent/id f) (:counted c))
                         (= (:intent/chase-eid f) (:eid c)))
                    (assoc world
                           :pearls/chase
                           (assoc c :fails (inc (:fails c 0)) :counted (:intent/id f)))
                    :else world)
        c2 (:pearls/chase world)]
    (if (and c2 (distant-gave-up? world (:eid c2)))
      (-> world
          (avoided/note :blacklist (:eid c2))
          (dissoc :pearls/chase))
      world)))
