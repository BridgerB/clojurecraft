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
   throws in five."
  (:require [clojure.math :as math]
            [clojurecraft.blocks :as blocks]
            [clojurecraft.game :as game]
            [clojurecraft.intent :as intent]
            [clojurecraft.inventory :as inventory]
            [clojurecraft.packet :as packet]
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
(def min-flight 1.0)                  ; blocks on the plane an eye must have flown for its line to count
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

(defmethod throw-stage :follow
  ;; the eye's last place kept each tick; its line read follow-ms after it was seen (or at once
  ;; when the server removes it sooner), recorded as a throw; done when the server removes it
  ;; (dropped as an item or shattered), or life-ms and answer-ms past with it still there.
  ;; Failed :eye-unmoved when it had not flown min-flight by then
  [world {:intent/keys [since target eye-from eye-pos]}]
  (let [now (:time/now world)
        e (get-in world [:world/entities target])
        pos (or (:entity/pos e) eye-pos)
        world (intent/assoc-intent world :intent/eye-pos pos)
        due? (or (nil? e) (>= (- now since) (by-server world follow-ms)))
        world'
        (if (and due? (not (recorded? world since))) (record-throw world eye-from pos since) world)
        over? (or (nil? e) (> (- now since) (by-server world (+ life-ms answer-ms))))]
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

(defmethod plan/next-intent :throw
  ;; the gym's throw trial: one throw, an eye held; none held waits :no-eye
  [world _ _]
  (if (pos? (eyes-held world))
    {:intent/kind :throw-eye :intent/for :stronghold}
    {:plan/wait :no-eye}))
