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
            [clojurecraft.window :as window]))

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
(def travel 150)                      ; blocks from the first throw the second is made, off its line
(def travel-turn 45.0)                ; degrees off the first line the way to the second throw turns: ahead and aside at once
(def hop-reach 32)                    ; blocks a walk toward somewhere far goes at a time
(def hop-rise 12)                     ; blocks over and under the feet a hop's end is looked for in its column
(def hop-turns [0.0 30.0 -30.0 60.0 -60.0 90.0 -90.0]) ; degrees off the way a hop may turn when straight on has no route
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
  "[eid entity] of an eye lying within drops/drop-radius of pos, where a thrown eye ended, brought
   down to the feet's height (it flew up, and its item fell to the ground), or nil."
  [world [ex _ ez]]
  (let [[_ fy _] (:player/pos world)] (drops/nearest world eye [ex fy ez])))

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
  "[x z] where the throw after throw t is made: travel blocks from it along its line turned
   travel-turn degrees, so the second line crosses the first ahead of both at an angle. Thrown
   from along the line it would be the same line (triangulate's parallel case)."
  [{:throw/keys [from dir]}]
  (let [[x z] (plane from)
        [dx dz] (rotate dir travel-turn)]
    [(+ x (* travel dx)) (+ z (* travel dz))]))

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
   turned by each of hop-turns. nil when none is, or when the feet are there already. A walk to
   somewhere hundreds of blocks off is beyond a route's node budget, so it goes a hop at a time,
   the planner choosing the next from where the last ended."
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
                (when (and i (not= c [fx fy fz]) (intent/routable? world i)) i)))
            hop-turns))))

(defn picked-up
  "The pickup of the eye the last throw dropped: when the last intent was a :throw-eye that ended
   done and an eye lies where it fell (eye-drop), a :collect-drops of it; else nil."
  [world]
  (let [l (:plan/last world)
        [_ d] (when (and (= :throw-eye (:intent/kind l)) (intent/done? l) (:intent/eye-pos l))
                (eye-drop world (:intent/eye-pos l)))]
    (when d
      {:intent/kind :collect-drops
       :intent/item eye
       :intent/target (mapv #(long (math/floor %)) (:entity/pos d))
       :intent/for :stronghold})))


(defn explore
  "A hop toward the first of the eight points explore-reach round the crossing c that the feet are
   not at and a hop reaches, from a heading the clock picks (a minute each), so the walks load
   the chunks round it; nil when none."
  [world [cx cz]]
  (let [start (quot (:time/now world 0) 60000)]
    (some (fn [k]
            (let [[dx dz] (rotate [1.0 0.0] (* 45.0 (+ start k)))]
              (hop world [(+ cx (* explore-reach dx)) (+ cz (* explore-reach dz))])))
          (range 8))))

(defn stronghold-next
  "The search's next step, in order: the eye just thrown picked up (picked-up); with no throw,
   one; with a crossing, a hop toward it, and there with nothing seen, a throw from there when the
   last was made more than rethrow-beyond off, else walks round it (explore); with throws that do
   not cross, a hop to the next throw's spot (second-spot of the latest) and a throw there. A hop
   none reaches waits :no-way."
  [world]
  (let [ts (throws world)
        c (crossing world)
        last-from (some-> (peek ts)
                          :throw/from)]
    (or (picked-up world)
        (cond (empty? ts) (throw-now world)
              (and c (not (at? world c))) (or (hop world c) {:plan/wait :no-way})
              (and c (> (physics/horizontal-distance (:player/pos world) last-from) rethrow-beyond))
              (throw-now world)
              c (or (explore world c) {:plan/wait :no-frame})
              (at? world (second-spot (peek ts))) (throw-now world)
              :else (or (hop world (second-spot (peek ts))) {:plan/wait :no-way})))))

(defmethod plan/done-by :frames-seen? [world _] (boolean (seq (frames world))))

(defmethod plan/next-intent :stronghold [world _ _] (stronghold-next world))
