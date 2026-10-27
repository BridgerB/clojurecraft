(ns clojurecraft.stronghold
  "Finding the stronghold (issue #265), the one stage of the run no sibling has proven: steve's
   finder is a stub (\"TODO: Track the eye entity, calculate direction, triangulate\") and
   ruststeve never threw an eye.

   An eye of ender used in the overworld finds the nearest stronghold (EnderEyeItem.use:
   findNearestMapStructure over the eye_of_ender_located structures) and flies from the
   thrower's middle toward it, twelve blocks along the line on the plane and eight up when the
   stronghold is farther (EyeOfEnder.signalTo), straight on the plane all the way. So two
   positions of the eye a moment apart give the line to the stronghold exactly, up to how the
   server rounds a move (1/4096 block); two such lines from two places cross at it. A throw is
   kept as a fact on the world, {:throw/from :throw/dir :throw/at} in :stronghold/throws, and
   the crossing is a pure function of two of them (triangulate).

   Requiring this namespace registers the :throw-eye intent and the :eye-throw row's rules (the
   gym's throw trial: one throw followed and kept). The throw: the eye put in the hand
   (place/equip), the eyes looking up a little (a use-item while looking at an end portal frame
   fills the frame instead), one use-item; then the next eye entity the server adds at the bot's
   middle is followed for follow-ms, and the line from where it was first seen to where it is
   then is the throw's. The intent stays on until the server removes the eye (its life is 80
   ticks), so the planner after it knows where it ended and can pick up the eye it drops four
   throws in five.

   The :stronghold row's plan (stronghold-next) is the whole search: the eye just thrown picked
   up where it fell; a first throw; then a walk off the first line at an angle (travel blocks at
   travel-turn degrees) and a second throw there; then the walk to where the lines cross; near
   the crossing with nothing seen, a throw from there, whose line crosses the first at a wide
   angle; then walks round it. The row is done when an end portal frame is remembered: a column's
   chunk data holds every block in it, so the portal room is seen the moment its chunk loads
   (memory/landmark-kinds), however deep it lies. Every long walk goes in hops a route reaches
   (hop), as the pearl hunt's roam does."
  (:require [clojure.math :as math]
            [clojurecraft.blocks :as blocks]
            [clojurecraft.drops :as drops]
            [clojurecraft.fortress :as fortress]
            [clojurecraft.game :as game]
            [clojurecraft.intent :as intent]
            [clojurecraft.inventory :as inventory]
            [clojurecraft.memory :as memory]
            [clojurecraft.packet :as packet]
            [clojurecraft.path :as path]
            [clojurecraft.physics :as physics]
            [clojurecraft.place :as place]
            [clojurecraft.plan :as plan]
            [clojurecraft.recipe :as recipe]
            [clojurecraft.stairs :as stairs]
            [clojurecraft.terrain :as terrain]
            [clojurecraft.window :as window]
            [clojurecraft.wood :as wood]))

(set! *warn-on-reflection* true)

(def eye :ender_eye)                  ; the item thrown
(def eye-type (blocks/entity-types :eye_of_ender)) ; the entity a thrown eye becomes (50)
(def throw-pitch -20.0)               ; degrees the eyes look up for a throw: never down at a frame, which a use-item would fill
(def look-ms 300)                     ; ms the look stands before the use-item, so the server has the pose
(def middle 0.9)                      ; blocks over the feet a thrown eye starts (the thrower's middle, Entity.getY(0.5))
(def seen-within 3.0)                 ; blocks from that middle a new eye must be first seen within to be the bot's own
(def answer-ms 3000)                  ; ms after the use-item, by the server's clock, the eye must be seen within
(def follow-ms 2000)                  ; ms after it is seen, by the server's clock, its line is read: half its life, several blocks of flight
(def life-ms 4000)                    ; ms an eye flies before it drops or shatters (EyeOfEnder: 80 ticks), by the server's clock
(def drop-grace-ms 1000)              ; ms after the eye is removed its dropped item may take to be added, by the server's clock
(def min-flight 1.0)                  ; blocks on the plane an eye must have flown for its line to count
(def travel 64)                       ; blocks from a throw the next is made, off its line
(def travel-turn 60.0)                ; degrees off a throw's line the way to the next turns, either side: ahead and well aside
(def min-offset 32.0)                 ; blocks off the last throw's line from which the next throw is made, wherever the way got to
(def pickup-rise 4)                   ; blocks over or under the feet a dropped eye is picked up within
(def room-over 1)                     ; blocks over a frame a staircase down to it ends: the feet at the frames' standing level, the tunnel the rest of the way; a stand higher over the portal room's air has no tunnel step down
(def deep 8)                          ; blocks over a frame past which the way down is stairs, not the tunnel: a tunnel steps down only onto a floor, the stairs lay one over a cave
(def stair-leg 8)                     ; blocks a staircase toward the frame goes down before the planner looks again, so each leg turns toward the frame
(def start-reach 16)                  ; blocks round the feet a dry start for the way down is looked for
(def tunnel-gain 4.0)                 ; blocks nearer a frame a tunnel that took every step must end, else it is spent
(def far-reach 80)                    ; blocks along a bearing a dry start past start-reach is looked for: inside the six chunks the server shows round the feet
(def shore 2)                         ; blocks round a dry start, on the plane, no water or lava lies within from three under it to one over: the first stairs' cells and what they expose
(def tunnel-y 30)                     ; feet height a tunnel toward the crossing goes down to: under lakes and seas, where strongholds lie
(def hop-reach 32)                    ; blocks a walk toward somewhere far goes at a time
(def hop-rise 12)                     ; blocks over and under the feet a hop's end is looked for in its column
(def hop-turns [0.0 30.0 -30.0 60.0 -60.0 90.0 -90.0]) ; degrees off the way a hop may turn when straight on has no route
(def downhill-reaches [32 16 8])      ; blocks toward the frame a walk down the surface goes at a time, the farther first: a slope steeper than a stair's one in one is walked, never staired into the air
(def downhill-drop 4)                 ; blocks lower than the feet such a walk must end: the ground's bumps are no slope
(def there 16.0)                      ; blocks on the plane from a point that count as there
(def rethrow-beyond 48.0)             ; at the crossing with no frame seen, a throw from there when the last was made farther off
(def explore-reach 64)                ; blocks from the crossing the walks round it go, each loading chunks further out
(def frame-radius 512.0)              ; blocks a remembered end portal frame is the stronghold's within
(def min-crossing 0.035)              ; the sine of the least angle two throws' lines may cross at (2 degrees): nearer parallel, a sliver of error moves the crossing hundreds of blocks

;; ---------------------------------------------------------------- the plane

(defn plane
  "[x z] of a point given as [x y z] or already as [x z]."
  [p]
  (if (= 3 (count p)) [(nth p 0) (nth p 2)] (vec p)))

(defn cross
  "The 2-D cross product of [ax az] and [bx bz]: positive when b turns counter-clockwise from a
   (x east, z south), zero when they are parallel."
  [[ax az] [bx bz]]
  (- (* ax bz) (* az bx)))

(defn norm "The length of a 2-D vector [x z]." [[x z]] (math/hypot x z))

;; ---------------------------------------------------------------- the crossing

(defn triangulate
  "[x z] where the lines of throws a and b cross, each a ray on the plane from :throw/from (where
   the eye left the bot) along :throw/dir (the way it flew), or nil when they do not cross ahead
   of both: nearly parallel (the sine of their angle under min-crossing, so two eyes thrown along
   one line, which say nothing about how far, are no answer), or the crossing behind either throw
   (an eye never flies away from its stronghold). A pure function of the two facts."
  [a b]
  (let [[ax az] (plane (:throw/from a))
        [bx bz] (plane (:throw/from b))
        da (:throw/dir a)
        db (:throw/dir b)
        c (cross da db)
        w [(- bx ax) (- bz az)]]
    (when (> (abs c) (* min-crossing (norm da) (norm db)))
      (let [ta (/ (cross w db) c)
            tb (/ (cross w da) c)]
        (when (and (>= ta 0.0) (>= tb 0.0))
          [(+ ax (* ta (first da))) (+ az (* ta (second da)))])))))

;; ---------------------------------------------------------------- the throw

(defn heading
  "The unit [dx dz] on the plane from point a to point b, or nil when they are under min-flight
   apart on it: an eye that has not flown says no way."
  [a b]
  (let [[ax az] (plane a)
        [bx bz] (plane b)
        v [(- bx ax) (- bz az)]
        d (norm v)]
    (when (>= d min-flight) [(/ (first v) d) (/ (second v) d)])))

(defn throws "The throws on record, oldest first." [world] (:stronghold/throws world []))

(defn own-eye
  "[eid entity] of an eye of ender the server added at or after ms since within seen-within of the
   bot's middle: the one its use-item threw. Nearest the middle when several."
  [world since]
  (let [[x y z] (:player/pos world)
        at [x (+ y middle) z]]
    (some->> (:world/entities world)
             (filter (fn [[_ e]]
                       (and (= eye-type (:entity/type e))
                            (>= (:entity/seen-at e 0) since)
                            (<= (physics/distance at (:entity/pos e)) seen-within))))
             seq
             (apply min-key (fn [[_ e]] (physics/distance at (:entity/pos e)))))))

(defn by-server
  "ms of the server's clock as the bot's: stretched by its slowdown (intent/slowdown), since the
   eye flies and dies in server ticks."
  [world ms]
  (* ms (intent/slowdown world)))

(defmulti throw-stage
  "Advance a :throw-eye intent one tick in its :intent/stage (:equip :look :use :seek :follow).
   Called only when no inventory click is unanswered."
  {:arglists '([world intent])}
  (fn [_world i] (:intent/stage i)))

(defmethod throw-stage :equip [world _] (place/equip world eye :look))

(defmethod throw-stage :look
  ;; the eyes up at throw-pitch along the yaw they have, look-ms standing, so the pose reaches the
  ;; server with the movement packets before the use-item
  [world {:intent/keys [since]}]
  (let [now (:time/now world)
        since (or since now)
        [yaw _] (:player/look world)]
    (cond (>= (- now since) look-ms)
          (intent/assoc-intent world :intent/stage :use :intent/since now)
          :else
          (-> world
              (assoc :player/controls {:control/look [yaw throw-pitch]})
              (intent/assoc-intent :intent/since since)))))

(defmethod throw-stage :use
  ;; one use-item along the look; the server throws the eye (EnderEyeItem.use) or, with no
  ;; stronghold to find (not the overworld), does nothing, which :seek times out on
  [world _]
  (let [seq (inc (:bot/sequence world))
        [yaw pitch] (:player/look world)]
    (-> world
        (assoc :bot/sequence seq)
        (game/emit {:packet/name :use-item
                    :hand (packet/value :hand :main-hand)
                    :sequence seq
                    :y-rot yaw
                    :x-rot pitch})
        (intent/assoc-intent :intent/stage :seek :intent/since (:time/now world)))))

(defmethod throw-stage :seek
  ;; the eye the use-item threw (own-eye): followed from where it was first seen; none within
  ;; answer-ms, failed :no-eye
  [world {:intent/keys [since]}]
  (let [now (:time/now world)
        [eid e] (own-eye world since)]
    (cond e
          (intent/assoc-intent world
                               :intent/stage :follow
                               :intent/target eid
                               :intent/eye-from (:entity/pos e)
                               :intent/eye-pos (:entity/pos e)
                               :intent/since now)
          (> (- now since) (by-server world answer-ms)) (intent/fail world :no-eye)
          :else world)))

(defn recorded?
  "Is the throw of the eye first seen at ms at on record?"
  [world at]
  (boolean (some #(= at (:throw/at %)) (throws world))))

(defn record-throw
  "world with the throw of an eye first seen at from at ms at, seen last at pos, appended to
   :stronghold/throws; nil when it has not flown min-flight on the plane."
  [world from pos at]
  (when-let [dir (heading from pos)]
    (update world
            :stronghold/throws
            (fnil conj [])
            {:throw/from (mapv double from) :throw/dir dir :throw/at at})))

(defn eye-drop
  "[eid entity] of an eye lying on a floor (path/floor?: the item spawns at the top of the
   flight and falls for a second before landing) within drops/drop-radius of pos, where a
   thrown eye ended, brought down to the feet's height, or nil."
  [world [ex _ ez]]
  (let [[_ fy _] (:player/pos world)
        [_ d :as hit] (drops/nearest world eye [ex fy ez])]
    (when (and d (path/floor? world (update (mapv #(long (math/floor %)) (:entity/pos d)) 1 dec)))
      hit)))

(defmethod throw-stage :follow
  ;; the eye's last place kept each tick; its line read follow-ms after it was seen (or at once
  ;; when the server removes it sooner), recorded as a throw. Once the server removes it
  ;; (:intent/gone-at), done when its dropped item lies there (eye-drop) or drop-grace-ms later
  ;; (it shattered): the removal and the item's add need not come in one tick, and the planner
  ;; picks the item up only if it is there when the intent ends. Done too past life-ms,
  ;; answer-ms and drop-grace-ms with the eye still there. Failed :eye-unmoved when it had not
  ;; flown min-flight when its line was read
  [world {:intent/keys [since target eye-from eye-pos gone-at]}]
  (let [now (:time/now world)
        e (get-in world [:world/entities target])
        pos (or (:entity/pos e) eye-pos)
        gone-at (or gone-at (when (nil? e) now))
        world (cond-> (intent/assoc-intent world :intent/eye-pos pos)
                gone-at (intent/assoc-intent :intent/gone-at gone-at))
        due? (or gone-at (>= (- now since) (by-server world follow-ms)))
        world'
        (if (and due? (not (recorded? world since))) (record-throw world eye-from pos since) world)
        over? (or (and gone-at
                       (or (eye-drop world pos)
                           (>= (- now gone-at) (by-server world drop-grace-ms))))
                  (> (- now since) (by-server world (+ life-ms answer-ms drop-grace-ms))))]
    (cond (nil? world') (intent/fail world :eye-unmoved)
          over? (intent/done world')
          :else world')))

(defmethod intent/run :throw-eye
  ;; {:intent/kind :throw-eye}: an eye in hand, a look up, one use-item, the eye followed until the
  ;; server removes it; the throw is kept in :stronghold/throws. Standing still all along
  [world i _]
  (let [i (cond-> i (nil? (:intent/stage i)) (assoc :intent/stage :equip))
        world (assoc world :plan/intent i :player/controls {})]
    (case (window/waiting world i)
      :overdue (window/lost world i :equip)
      :waiting world
      (if (:intent/resync i)
        (window/resync world (window/view world :inventory))
        (throw-stage world i)))))

;; ---------------------------------------------------------------- the throw trial

(defn eyes-held
  "How many eyes of ender the bot holds."
  [world]
  (inventory/item-count world (recipe/item-id eye)))

(defmethod plan/done-by :thrown? [world _] (boolean (seq (throws world))))

(defn throw-now
  "A throw, an eye held; else the wait :no-eye."
  [world]
  (if (pos? (eyes-held world))
    {:intent/kind :throw-eye :intent/for :stronghold}
    {:plan/wait :no-eye}))

(defmethod plan/next-intent :throw [world _ _] (throw-now world)) ; the gym's throw trial: one throw

;; ---------------------------------------------------------------- the search

(def frame-states
  "Every state of an end portal frame, with an eye or without."
  (set (blocks/states-of :end_portal_frame)))

(defn frames
  "The end portal frames on record in the bot's dimension that nothing seen since has replaced
   (memory/positions-now)."
  [world]
  (memory/positions-now world frame-states))

(defn nearest-frame
  "The end portal frame on record nearest the feet within frame-radius, or nil."
  [world]
  (memory/nearest-of (physics/centre (intent/feet world)) frame-radius (frames world)))

(defn rotate
  "The 2-D vector [dx dz] turned by deg degrees."
  [[dx dz] deg]
  (let [a (math/to-radians deg)
        c (math/cos a)
        s (math/sin a)]
    [(- (* dx c) (* dz s)) (+ (* dx s) (* dz c))]))

(defn second-spot
  "[x z] where the throw after throw t is made: travel blocks from it along its line turned turn
   degrees (travel-turn when not given), so the next line crosses it ahead of both at an angle.
   Thrown from along the line it would be the same line (triangulate's parallel case)."
  ([t] (second-spot t travel-turn))
  ([{:throw/keys [from dir]} turn]
   (let [[x z] (plane from) [dx dz] (rotate dir turn)] [(+ x (* travel dx)) (+ z (* travel dz))])))

(defn side
  "Which side of throw t's line point [x z] lies on, as the sign of the cross product, times how
   far off it it is."
  [{:throw/keys [from dir]} [x z]]
  (let [[fx fz] (plane from)] (cross dir [(- x fx) (- z fz)])))

(defn off-line
  "Blocks on the plane from the feet to the line of throw t."
  [world t]
  (let [[px _ pz] (:player/pos world)] (abs (side t [px pz]))))

(def on-line 4.0)                     ; blocks off a throw's line within which the feet are on it, for spots

(defn spots
  "The two places the throw after t may be made from, second-spot turned either way: the one on
   the side of its line the feet are on first, once they are more than on-line off it. Gym batch
   stronghold-265b (plan run 38930188463) run 2: a lake between, a hop toward one spot ended where
   only the other had a way, and the bot walked to and fro between them twelve times."
  [world t]
  (let [[px _ pz] (:player/pos world)
        a (second-spot t travel-turn)
        b (second-spot t (- travel-turn))
        here (side t [px pz])]
    (if (and (> (abs here) on-line) (neg? (* here (side t a)))) [b a] [a b])))

(defn crossing
  "[x z] of the stronghold by the throws on record: the latest throw crossed with each earlier
   one (triangulate), the widest crossing (the largest sine of the angle) kept; nil when none
   crosses."
  [world]
  (let [ts (throws world)
        t (peek ts)]
    (some->> (butlast ts)
             (keep (fn [u]
                     (when-let [c (triangulate u t)]
                       [(abs (cross (:throw/dir u) (:throw/dir t))) c])))
             seq
             (apply max-key first)
             second)))

(defn at?
  "Are the feet within there blocks of [x z] on the plane?"
  [world [x z]]
  (<= (physics/horizontal-distance (:player/pos world) [x 0.0 z]) there))

(defn stand-in
  "A cell in column x z a body can stand in (path/standable?), within hop-rise over or under
   height y, the highest first; nil when none is."
  [world x z y]
  (some #(when (path/standable? world [x % z]) [x % z]) (range (+ y hop-rise) (- y hop-rise 1) -1)))

(defn hop
  "A walk toward the far point [x z]: to a cell (stand-in) hop-reach blocks along the way, or
   nearer when the point is, that a route reaches (intent/routable?); straight on first, then
   turned by each of hop-turns, never to a cell a walk failed at (:plan/blacklist). nil when none
   is, or when the feet are there already. A walk to somewhere hundreds of blocks off is beyond a
   route's node budget, so it goes a hop at a time, the planner choosing the next from where the
   last ended. Gym batch stronghold-265a (plan run 38927120252) run 3 walked at one cell twelve
   times, each walk failing :no-path, the same hop chosen again after each."
  [world [x z]]
  (let [[fx fy fz] (intent/feet world)
        v [(- x fx) (- z fz)]
        d (norm v)]
    (when-not (at? world [x z])
      (some (fn [turn]
              (let [[ux uz] (rotate [(/ (first v) d) (/ (second v) d)] turn)
                    r (min hop-reach d)
                    c (stand-in world
                                (long (math/floor (+ fx 0.5 (* r ux))))
                                (long (math/floor (+ fz 0.5 (* r uz))))
                                fy)
                    i (when c {:intent/kind :walk :intent/target c :intent/for :stronghold})]
                (when (and i
                           (not= c [fx fy fz])
                           (not (contains? (:plan/blacklist world #{}) c))
                           (intent/routable? world i))
                  i)))
            hop-turns))))

(defn way
  "A way toward the far point [x z]: a hop; else, with open water toward it, a swim across it
   (swim's :cross, done on the far side's dry land); else a dry tunnel toward it at height y
   (the fortress's :tunnel-to with :intent/dry?, fortress/steps-per-tunnel steps) when its first
   step can be taken; nil when none is. Gym batch stronghold-265a (plan run 38927120252, probe
   38929010443): two landings stood on a lake's shore with the stronghold across it and every hop
   ending in the water, one on a mountainside whose hops had no route; each waited :no-way."
  [world [x z] y]
  (let [feet (intent/feet world)
        target [(long (math/floor x)) y (long (math/floor z))]]
    (or (hop world [x z])
        (when (wood/water-toward? world feet target (physics/y-of (game/eye world)))
          {:intent/kind :cross
           :intent/target (assoc target 1 (physics/y-of feet))
           :intent/for :stronghold})
        (when (fortress/choose-move world feet target fortress/climb-past true)
          {:intent/kind :tunnel-to
           :intent/target target
           :intent/steps fortress/steps-per-tunnel
           :intent/dry? true
           :intent/for :stronghold}))))

(defn picked-up
  "The pickup of the eye the last throw dropped: when the last intent was a :throw-eye that ended
   done and an eye lies where it fell (eye-drop) within pickup-rise of the feet's height, in a cell
   a route reaches, a :collect-drops of it; else nil. Gym batch stronghold-265b (plan run
   38930188463): four of six landings went for an eye lying on a tree or a hill six over the
   feet and failed :not-picked-up, each an attempt toward the three that end a plan."
  [world]
  (let [l (:plan/last world)
        [_ d] (when (and (= :throw-eye (:intent/kind l)) (intent/done? l) (:intent/eye-pos l))
                (eye-drop world (:intent/eye-pos l)))
        c (when d (mapv #(long (math/floor %)) (:entity/pos d)))]
    (when (and c
               (<= (abs (- (physics/y-of c) (physics/y-of (intent/feet world)))) pickup-rise)
               (intent/routable? world {:intent/kind :walk :intent/target c}))
      {:intent/kind :collect-drops :intent/item eye :intent/target c :intent/for :stronghold})))


(defn explore
  "A way (way, at the feet's height) toward the first of the eight points explore-reach round the
   crossing c that the feet are not at and a way reaches, from a heading the clock picks (a minute
   each), so the walks load the chunks round it; nil when none."
  [world [cx cz]]
  (let [start (quot (:time/now world 0) 60000)
        [_ fy _] (intent/feet world)]
    (some (fn [k]
            (let [[dx dz] (rotate [1.0 0.0] (* 45.0 (+ start k)))
                  p [(+ cx (* explore-reach dx)) (+ cz (* explore-reach dz))]]
              (when-not (at? world p) (way world p fy))))
          (range 8))))

(defn stronghold-next
  "The search's next step, in order: the eye just thrown picked up (picked-up); with no throw,
   one; with a crossing, a way toward it (way: a hop, a swim, or a tunnel at tunnel-y), and there
   with nothing seen, a throw from there when the last was made more than rethrow-beyond off,
   else walks round it (explore); with throws that do not cross, a throw once the feet are
   min-offset off the latest's line, else a way toward one of its spots (spots) and a throw
   there. Nothing reaching waits :no-way."
  [world]
  (let [ts (throws world)
        t (peek ts)
        c (crossing world)
        [_ fy _] (when (:player/pos world) (intent/feet world))]
    (or (picked-up world)
        (cond (empty? ts) (throw-now world)
              (and c (not (at? world c))) (or (way world c tunnel-y) {:plan/wait :no-way})
              (and c
                   (> (physics/horizontal-distance (:player/pos world) (:throw/from t))
                      rethrow-beyond))
              (throw-now world)
              c (or (explore world c) {:plan/wait :no-frame})
              (>= (off-line world t) min-offset) (throw-now world)
              :else (or (some #(way world % fy) (spots world t)) {:plan/wait :no-way})))))

(defmethod plan/done-by :frames-seen?
  [world _]
  (boolean (and (seq (frames world)) (not= :throw-eye (:intent/kind (:plan/intent world))))))

(defmethod plan/next-intent :stronghold [world _ _] (stronghold-next world))

;; ---------------------------------------------------------------- the portal room

(defmethod plan/done-by :in-portal-room?
  ;; within fortress/reach (6) of a remembered frame, from the feet cell's centre
  [world _]
  (boolean (some->> (nearest-frame world)
                    (fortress/arrived? world))))

(defn dry-cell?
  "Is cell c a dry start for a way down: every cell within shore blocks of it on the plane, from
   three under it to one over (the cells the first stairs open and expose, stairs/exposed),
   loaded and neither water nor lava?"
  [world [x y z]]
  (every? (fn [c] (let [id (terrain/block-at world c)] (and id (not (blocks/fluid id)))))
          (for [dx (range (- shore) (inc shore))
                dz (range (- shore) (inc shore))
                dy (range -3 2)]
            [(+ x dx) (+ y dy) (+ z dz)])))

(defn dry-start
  "Where the way down to frame f starts: the feet when they stand dry (dry-cell?); else the
   nearest cell on the plane within start-reach a body stands in (stand-in, within hop-rise of the
   feet's height) that is dry, not one a walk failed at (:plan/blacklist) and one a route reaches
   (intent/routable?), the nearer f first among those as near; nil when none is. On a shore every
   stair opens a cell by water: gym batch stronghold-265c (plan run 38934412014) run 2 of the
   portal room swam out onto the lake's bank over the frame, had every stair refused there and
   failed :boxed; batch 265b run 2 the tunnel's every first step from the bank, :blocked."
  [world [x _ z]]
  (let [[fx fy fz :as feet] (intent/feet world)]
    (if (dry-cell? world feet)
      feet
      (->> (for [dx (range (- start-reach) (inc start-reach))
                 dz (range (- start-reach) (inc start-reach))
                 :let [r (max (abs dx) (abs dz))]
                 :when (pos? r)]
             [r (math/hypot (- (+ fx dx) x) (- (+ fz dz) z)) [(+ fx dx) (+ fz dz)]])
           (sort-by (fn [[r d _]] [r d]))
           (some (fn [[_ _ [cx cz]]]
                   (let [c (stand-in world cx cz fy)]
                     (when (and c
                                (dry-cell? world c)
                                (not (contains? (:plan/blacklist world #{}) c))
                                (intent/routable? world {:intent/kind :walk :intent/target c}))
                       c))))))))

(defn far-start
  "With no dry start within start-reach: the nearest cell along eight bearings from the feet,
   from past start-reach out to far-reach, in a column whose top is ground (stairs/dry-top?: not
   a lake, nor a cave under one) that a body stands in (stand-in) dry (dry-cell?), not one a walk
   failed at (:plan/blacklist); nil when none is. Its way is a swim (swim's :cross), no route:
   gym batch stronghold-room-b (plan run 39132210882) run 2 of the portal room ended its swim
   toward the crossing on a block in the lake over the frame, nothing dry within 48 of it (probe
   39134431529), every stair from it into the water, and failed :boxed."
  [world]
  (let [[fx fy fz] (intent/feet world)]
    (some (fn [[d k]]
            (let [a (* k (/ math/PI 4))
                  x (long (math/floor (+ fx 0.5 (* d (math/cos a)))))
                  z (long (math/floor (+ fz 0.5 (* d (math/sin a)))))
                  c (when (stairs/dry-top? world [x z]) (stand-in world x z fy))]
              (when (and c (dry-cell? world c) (not (contains? (:plan/blacklist world #{}) c))) c)))
          (for [d (range (inc start-reach) (inc far-reach)) k (range 8)] [d k]))))

(defn walked-ground
  "Where the newest walk that arrived ended (memory/last-done), ground the bot stood on, when it
   is farther than start-reach from the feet on the plane; nil otherwise. The way back to land
   from water with nothing dry in sight: batch stronghold-room-b run 2 had walked the lake's far
   shore before its swim. Not one more than hop-rise over or under the feet: a walk on the
   mountain over a cave is no way out of the cave."
  [world]
  (let [[fx fy fz] (intent/feet world)
        [x y z :as c] (:intention/target (memory/last-done world :walk))]
    (when (and c (> (max (abs (- x fx)) (abs (- z fz))) start-reach) (<= (abs (- y fy)) hop-rise))
      c)))

(defn downhill
  "A walk down the surface toward frame [x _ z] from feet under the open sky
   (terrain/under-sky?): the cell a body stands in (stand-in) downhill-drop or more under the
   feet, each of downhill-reaches along the way, the farther first, straight on first then
   turned by each of hop-turns, not one a walk failed at (:plan/blacklist), one a route reaches
   (intent/routable?); nil when none is, or the feet are not under the sky. A mountain's slope falls faster than a
   staircase's one in one: stairs cut from it leave it into the air and lay a floor every stair
   over the valley, a bridge into the sky that ends where the stairs' floor would be a bridge
   over the valley's water (stairs/over-fluid?). Gym batch stronghold-room-e (plan run
   39152932850) run 3 of the portal room cut six legs from the mountain at y 120 and left its
   western slope at y 83 into the open air over a flooded valley, laid a floor a stair down to
   y 72, fifteen over the water, where every stair was refused and every level step laid one
   more block of causeway, 32 east, :boxed three times (probes 39158899900, 39159213076)."
  [world [x _ z]]
  (let [[fx fy fz :as feet] (intent/feet world)
        v [(- x fx) (- z fz)]
        d (norm v)]
    (when (and (pos? d) (terrain/under-sky? world feet))
      (some (fn [[r turn]]
              (let [[ux uz] (rotate [(/ (first v) d) (/ (second v) d)] turn)
                    r (min r d)
                    [_ cy _ :as c] (stand-in world
                                             (long (math/floor (+ fx 0.5 (* r ux))))
                                             (long (math/floor (+ fz 0.5 (* r uz))))
                                             fy)
                    i (when c {:intent/kind :walk :intent/target c :intent/for :stronghold})]
                (when (and i
                           (<= cy (- fy downhill-drop))
                           (not (contains? (:plan/blacklist world #{}) c))
                           (intent/routable? world i))
                  i)))
            (for [r downhill-reaches turn hop-turns] [r turn])))))

(defn stairs-toward
  "A staircase down toward frame f from feet, to height to-y: the stairs' heading is the axis
   with the farther way to go (stairs.clj: it turns where water or lava is, steps aside, and lays
   a floor over a cave from the cobblestone carried)."
  [[fx _ fz] [x _ z] to-y]
  {:intent/kind :stairs-down
   :intent/to-y to-y
   :intent/dir (if (>= (abs (- x fx)) (abs (- z fz)))
                 [(if (neg? (- x fx)) -1 1) 0]
                 [0 (if (neg? (- z fz)) -1 1)])
   :intent/for :stronghold})

(defn tunnel-spent?
  "Did the last intent, a dry tunnel toward f, take every step it had without arriving, and end
   less than tunnel-gain nearer f than it began (:intent/from-feet)? It went round and round:
   gym batch stronghold-265c (plan run 38934412014) run 3 of the portal room stood over the frame
   at y 84, a cave under it, every step down refused for the air under the next cell
   (fortress/safe? never steps onto air), stepped level between two cells 32 times a tunnel for
   eighteen minutes to the timeout. One that came nearer is the way on, not spent: batch
   stronghold-room-d (plan run 39138341075) run 1, three over the frame 135 blocks off, had the
   stairs cut after every tunnel, away from the frame into the rock, and the next tunnel walked
   back up them, fifteen minutes to the timeout."
  [world f]
  (let [l (:plan/last world)
        from (:intent/from-feet l)
        gone (fn [c] (physics/distance (physics/centre c) (physics/centre f)))]
    (boolean (and (= :tunnel-to (:intent/kind l))
                  (= f (:intent/target l))
                  (intent/done? l)
                  (>= (:intent/taken l 0) (:intent/steps l 0))
                  (or (nil? from) (< (- (gone from) (gone (intent/feet world))) tunnel-gain))))))

(defn safe-portal-move?
  "Does move from feet open no cell that is or borders lava, and have no lava floor?
   In the portal room the 3x3 pool under the frame is lava; stepping or digging into it
   is a death."
  [world feet move]
  (let [cells (fortress/opens feet move)
        floor (fortress/plus (fortress/plus feet move) [0 -1 0])
        lava? (fn [c]
                (let [id (terrain/block-at world c)]
                  (or (contains? blocks/lava-states id) (= :lava (terrain/fluid-kind world c)))))]
    (and (not-any? lava? (conj cells floor)) (empty? (fortress/lava-beside world feet move)))))

(defn safe-tunnel-move
  "The first move toward f that is safe?, dry?, and has no lava in its opened cells,
   floor or border."
  [world feet f]
  (when (fortress/choose-move world feet f fortress/climb-past true)
    (first (filter (fn [m]
                     (and (fortress/safe? world feet m)
                          (fortress/dry? world feet m)
                          (safe-portal-move? world feet m)))
                   (fortress/moves feet f fortress/climb-past)))))

(defn to-room
  "The way to the portal room's frame f, in order: in water, out to the nearest bank first
   (swim's :swim-out :retreat); from a shore, a walk to a dry start (dry-start) that ends
   standing in that cell (a :retreat walk, intent/standing-walks: one ending near it was chosen
   again from the wet cell it ended in, twelve thousand times a model run); with none within
   start-reach and the open sky over the feet (a lake's, not a cave's), a swim (:cross) to one
   farther (far-start), else back to the ground last walked (walked-ground), never stairs from a
   block in a lake, nor once the way down has begun (the last intent the stairs or the tunnel:
   batch stronghold-room-d run 2 swam back up from its first leg of stairs); standing dry under
   the open sky with lower ground toward the frame a route reaches, a walk down to it
   (downhill: a slope is walked, never staired into the air; batch stronghold-room-e run 3 built
   a bridge off the mountain's slope into the sky over a flooded valley); more than deep
   over the frame, or after a tunnel that got nowhere (tunnel-spent?), a staircase down toward
   it a leg of stair-leg at a time (stairs-toward), never under room-over over the frame; then
   the fortress's tunnel to it, its steps opening no cell by water (fortress/dry?) and no cell
   through or bordering lava (safe-portal-move?), when its first step can be taken from the feet
   (through the stronghold's walls, never into its frames: fortress/unbreakable); else, still over
   room-over, the stairs; else with feet boxed or no move forward, a retreat step back along the
   plan trail to an earlier unboxed cell (a different stair start); else the wait :no-way. Gym
   batch stronghold-265b (plan run 38930188463) run 2 of the portal room: the frame at y 0
   under a lake, seen while the bot swam it, every first step of the tunnel from the water opened
   a cell by water and it failed :blocked."
  [world [_ y _ :as f]]
  (let [[_ fy _ :as feet] (intent/feet world)
        high? (> fy (+ y deep))
        boxed? (contains? (:plan/boxed-at world #{}) feet)
        start (when (and high?
                         (not (:player/in-water? world))
                         (terrain/under-sky? world feet)
                         (not (#{:stairs-down :tunnel-to} (:intent/kind (:plan/last world)))))
                (dry-start world f))
        far (when (and high?
                       (not (:player/in-water? world))
                       (nil? start)
                       (terrain/under-sky? world feet)
                       (not (#{:stairs-down :tunnel-to} (:intent/kind (:plan/last world)))))
              (or (far-start world) (walked-ground world)))
        down (when (and high? (= start feet)) (downhill world f))
        stairs (stairs-toward feet f (max (+ y room-over) (- fy stair-leg)))
        tunnel-move (safe-tunnel-move world feet f)
        retreat (when (or boxed? (nil? tunnel-move))
                  (some #(when-not (contains? (:plan/boxed-at world #{}) %) %)
                        (rseq (:plan/trail world []))))]
    (cond (:player/in-water? world) {:intent/kind :swim-out :intent/for :retreat}
          (and start (not= start feet))
          {:intent/kind :walk :intent/target start :intent/for :retreat}
          far {:intent/kind :cross :intent/target far :intent/for :stronghold}
          down down
          (and (not boxed?) (> fy (+ y room-over)) (or high? (tunnel-spent? world f))) stairs
          tunnel-move
          {:intent/kind :tunnel-to
           :intent/target f
           :intent/steps fortress/steps-per-tunnel
           :intent/dry? true
           :intent/from-feet feet}
          (and (not boxed?) (> fy (+ y room-over))) stairs
          retreat {:intent/kind :walk :intent/target retreat :intent/for :retreat}
          :else {:plan/wait :no-way})))

(defmethod plan/next-intent :portal-room
  ;; the eye the last throw dropped first (picked-up: a frame seen during the throw moves the
  ;; chain on to this row when the throw ends), then a frame remembered: the way to it (to-room);
  ;; none yet: the search (stronghold-next)
  [world _ _]
  (or (picked-up world)
      (if-let [f (nearest-frame world)]
        (to-room world f)
        (stronghold-next world))))
