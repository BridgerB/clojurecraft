(ns clojurecraft.combat
  "Fighting (issue #259): the :fight and :back-off executors, the swords, and the rows that use
   them: :defend (an always row at priority 0, after the escapes: a hostile within defend-radius
   preempts any plan, a creeper within creeper-radius is backed away from, never fought) and the
   gym's :attack (every hostile within attack-radius killed).

   775 split attacking out of interact: an attack is its own packet, the target's entity id alone
   (ruststeve src/bot/mod.rs: an interact with mouse 1 was a right-click that dealt nothing, and
   without a hand field it got the bot kicked). The server judges an attack's reach from the pose
   it last had, so the look is set a tick before (the movement packet carries it on the next
   tick, game/movement-packets) and an attack goes out only once the eyes face the target.

   Swings are paced by the clock, as a deadline on the intent (:intent/next-swing, swing-every
   650 ms after the last): ruststeve's swings, paced by ticks that collapsed under the packet
   flood, sent forty in 66 ms, all but one inside the mob's 10-tick hurt invulnerability. A kill
   is the target gone (entity/gone?: removed, or dead by the server's word); a target that
   survives max-swings swings in reach is a ghost (ruststeve tasks/nether.rs), failed :ghost and
   blacklisted by the planner; below retreat-health the fight breaks off and walks away."
  (:require [clojure.math :as math]
            [clojurecraft.entity :as entity]
            [clojurecraft.game :as game]
            [clojurecraft.intent :as intent]
            [clojurecraft.packet :as packet]
            [clojurecraft.path :as path]
            [clojurecraft.physics :as physics]
            [clojurecraft.place :as place]
            [clojurecraft.plan :as plan]
            [clojurecraft.recipe :as recipe]
            [clojurecraft.terrain :as terrain]
            [clojurecraft.window :as window]))

(set! *warn-on-reflection* true)

(def swing-every 650)                 ; ms between attacks: past a mob's 10-tick hurt invulnerability and a sword's full charge
(def approach-range 3.0)              ; blocks on the plane to the target where the approach stops
(def attack-reach 3.2)                ; eye to the target's middle within which an attack is sent
(def aim-height 0.9)                  ; blocks over the target's feet the eyes look at (a blaze hovers, a zombie's chest)
(def aimed-within 20.0)               ; degrees of yaw the eyes may be off the target and still attack
(def max-swings 25)                   ; attacks in reach on one target before it is a ghost
(def retreat-health 10)               ; health under which a fight breaks off
(def retreat-distance 6.0)            ; blocks a broken-off fight puts between the bot and the target
(def approach-timeout-ms 20000)       ; an approach that never reaches the target fails :unreachable
(def stall-ticks 20)                  ; ticks an approach straight at the target may go without coming 0.1 nearer before it takes a route
(def route-range 2.0)                 ; blocks on the plane from the target's cell a route walk ends within: nearer than approach-range, where the straight approach stalled
(def walk-drift 3.0)                  ; blocks the target may move from the cell a route walk heads for before the walk is dropped
(def press-within 0.8)                ; blocks on the plane under which an engage out of reach stops pressing in (it is under the target)
(def hold-timeout-ms 60000)           ; a fight that holds its ground waits this long for its target to come
(def back-off-timeout-ms 10000)       ; a walk away ends after this, however far it got

(def swords
  "The swords by name, best first (attack damage: netherite 8, diamond 7, iron 6, stone and copper
   5, golden and wooden 4)."
  [:netherite_sword :diamond_sword :iron_sword :stone_sword :copper_sword :golden_sword
   :wooden_sword])

(def store-slots 36)                  ; player slots 0-35, the hotbar and the main inventory

(defn sword
  "[player-slot name] of the best sword held in the hotbar or the main inventory, or nil."
  [world]
  (let [held (into {}
                   (for [[p {:keys [item]}] (sort-by key (:player/inventory world))
                         :when (< -1 p store-slots)
                         :let [n (recipe/item-name item)]
                         :when (some #{n} swords)]
                     [n p]))]
    (some (fn [n] (when-let [p (held n)] [p n])) swords)))

(defn aim
  "The point an attack on entity e looks at: aim-height over its feet."
  [e]
  (update (:entity/pos e) 1 + aim-height))

(defn yaw-off
  "Degrees between two yaws, 0-180."
  [a b]
  (let [d (mod (- a b) 360.0)] (min d (- 360.0 d))))

(defn horizontal
  "Blocks on the plane from the feet to entity e."
  [world e]
  (physics/horizontal-distance (:player/pos world) (:entity/pos e)))

(defn away-point
  "A point retreat-distance and more beyond the feet, straight away from entity e (along e →
   feet; due south when they stand in one column)."
  [world e]
  (let [[px py pz] (:player/pos world)
        [ex _ ez] (:entity/pos e)
        dx (- px ex)
        dz (- pz ez)
        d (math/sqrt (+ (* dx dx) (* dz dz)))
        [ux uz] (if (< d 1.0e-6) [0.0 1.0] [(/ dx d) (/ dz d)])
        far (+ retreat-distance 2.0)]
    [(+ px (* far ux)) py (+ pz (* far uz))]))

(defn attack
  "Send one attack on eid, then the arm's swing, as the vanilla client sends them
   (MultiPlayerGameMode.attack, then the swing); the next swing swing-every from now, the swing
   counted. Never the swing first, nor the held slot restated: gym attack-259a (run 38881958899)
   sent set-carried-item, swing, attack, and its stone sword took a NoAI husk's twenty health a
   point a hit, twenty-two hits for a kill, every one at the charge of a swing just begun."
  [world eid]
  (let [now (:time/now world)]
    (-> world
        (game/emit {:packet/name :attack :entity-id eid})
        (game/emit {:packet/name :swing :hand (packet/value :hand :main-hand)})
        (intent/assoc-intent :intent/next-swing (+ now swing-every)
                             :intent/swings (inc (:intent/swings (:plan/intent world) 0))))))

(defmulti fight-stage
  "Advance a :fight intent one tick in its :intent/stage (:arm :approach :engage :retreat), the
   target e alive in the world. Called only when no window-0 click is unanswered."
  {:arglists '([world intent e])}
  (fn [_world i _e] (:intent/stage i)))

(defmethod fight-stage :arm
  ;; the best sword in hand (place/equip), or the hand when none is held
  [world _ _]
  (if-let [[_ n] (sword world)]
    (place/equip world n :approach)
    (intent/assoc-intent world :intent/stage :approach)))

(defn face
  "Controls that stand still and look at entity e's aim."
  [world e]
  {:control/look (physics/look-at (game/eye world) (aim e))})

(defn cell "The block cell a point is in." [p] (mapv #(long (math/floor %)) p))

(defn route-walk
  "A walk to the end of a route to within approach-range of entity e's cell on the plane (path's
   :near goal), stood in (intent/standing-walks: a walk near a cell ends within reach of it in a
   straight line, through a wall), for an approach the straight way does not bring nearer; nil
   when no route reaches it, or :here when the feet already stand within route-range of it (a
   fence between keeps the straight approach just past approach-range: gym blaze-fortress-j run 3,
   run 38911617612, failed three fights :unreachable there)."
  [world e]
  (let [r (path/plan world
                     (path/feet-cell (:player/pos world))
                     {:goal/kind :near :goal/pos (cell (:entity/pos e)) :goal/range route-range})
        to (last (:path/waypoints r))]
    (cond (not= :found (:path/status r)) nil
          (or (nil? to) (= to (intent/feet world))) :here
          :else {:intent/kind :walk :intent/target to :intent/for :fight})))

(defmethod fight-stage :approach
  ;; toward the target until approach-range: straight at it while that brings it nearer, and once
  ;; it has not for stall-ticks, along a route (route-walk, a child :walk the :fight runs), failed
  ;; :unreachable when no route reaches it or after approach-timeout-ms. Gym blaze-fortress-h
  ;; (run 38905665758) failed three fights in a row :unreachable on blazes in sight with a route
  ;; to them, walking straight into the fortress's fences for twenty seconds each. A fight that
  ;; holds its ground (:intent/hold?) stands facing it while it comes, hold-timeout-ms at most: an
  ;; enderman stared at 60 blocks off teleports toward the bot and walks the rest (issue #263)
  [world {:intent/keys [since hold? best-dist best-tick] :or {best-dist Double/MAX_VALUE}} e]
  (let [now (:time/now world)
        tick (:time/tick world)
        h (horizontal world e)
        progressed? (< h (- best-dist 0.1))
        best-tick (if (or progressed? (nil? best-tick)) tick best-tick)
        world
        (intent/assoc-intent world :intent/best-dist (min h best-dist) :intent/best-tick best-tick)]
    (cond (<= h approach-range)
          (-> world
              (assoc :player/controls (face world e))
              (intent/dissoc-intent :intent/best-dist :intent/best-tick)
              (intent/assoc-intent :intent/stage :engage :intent/since now))
          (> (- now since) (if hold? hold-timeout-ms approach-timeout-ms))
          (intent/fail world :unreachable)
          hold? (assoc world :player/controls (face world e))
          (> (- tick best-tick) stall-ticks)
          (let [w (route-walk world e)]
            (cond (= :here w)
                  (-> world
                      (assoc :player/controls (face world e))
                      (intent/dissoc-intent :intent/best-dist :intent/best-tick)
                      (intent/assoc-intent :intent/stage :engage :intent/since now))
                  w
                  (-> world
                      (assoc :player/controls {})
                      (intent/assoc-intent :intent/child (assoc w :intent/status :active)))
                  :else (intent/fail world :unreachable)))
          :else (assoc world :player/controls (intent/toward world (aim e) 0.0)))))

(defn walk-on
  "One tick of an approach's route walk (the :fight's :intent/child) toward entity e: run, then
   dropped when done (the straight approach takes over, its stall clock begun again) or when e
   has moved walk-drift past approach-range from the cell it heads for, on the plane; a walk that
   failed fails the fight :unreachable."
  [world {:intent/keys [child]} e event]
  (cond (intent/done? child)
        (intent/dissoc-intent world :intent/child :intent/best-dist :intent/best-tick)
        (intent/failed? child) (intent/fail (assoc world :player/controls {}) :unreachable)
        (> (physics/horizontal-distance (physics/centre (:intent/target child)) (:entity/pos e))
           (+ approach-range walk-drift))
        (intent/dissoc-intent (assoc world :player/controls {})
                              :intent/child
                              :intent/best-dist
                              :intent/best-tick)
        :else (intent/run-child world event)))

(defn pressing?
  "Should an engaged fight walk on toward entity e: its aim out of attack-reach of the eye while
   it is still press-within or more away on the plane. A blaze hovers: two blocks over the floor,
   its aim from approach-range on the plane was 3.3 from the eye, past attack-reach, and the engage
   faced it and never attacked (issue #262, the server model's blaze)."
  [world e]
  (and (> (physics/distance (game/eye world) (aim e)) attack-reach)
       (>= (horizontal world e) press-within)))

(defmethod fight-stage :engage
  ;; face the target every tick, walking on toward it while it is out of reach (pressing?);
  ;; attack when the deadline has passed, the eyes face it (the look went out a tick before, with
  ;; the movement packet) and it is in reach; failed :unreachable when no attack has gone out for
  ;; approach-timeout-ms (a fight that holds its ground waits as long as it is not broken off)
  [world {:intent/keys [target next-swing swings hold? since] :or {next-swing 0 swings 0}} e]
  (let [now (:time/now world)
        eye (game/eye world)
        [yaw _ :as look] (physics/look-at eye (aim e))
        world (assoc world
                     :player/controls
                     (if (and (not hold?) (pressing? world e))
                       (intent/toward world (aim e) 0.0)
                       {:control/look look}))]
    (cond (> (horizontal world e) (inc approach-range))
          (intent/assoc-intent world :intent/stage :approach :intent/since now)
          (>= swings max-swings) (intent/fail world :ghost)
          (and (not hold?)
               since
               (> (- now (if (pos? next-swing) (- next-swing swing-every) since))
                  approach-timeout-ms))
          (intent/fail world :unreachable)
          (and (>= now next-swing)
               (<= (physics/distance eye (aim e)) attack-reach)
               (<= (yaw-off yaw (first (:player/look world))) aimed-within))
          (attack world target)
          :else world)))

(defmethod fight-stage :retreat
  ;; straight away from the target until retreat-distance is between them, or back-off-timeout-ms
  [world {:intent/keys [since]} e]
  (cond (or (>= (horizontal world e) retreat-distance)
            (> (- (:time/now world) since) back-off-timeout-ms))
        (-> world
            (assoc :player/controls {})
            intent/done)
        :else (assoc world :player/controls (intent/toward world (away-point world e) 0.0))))

(defmethod intent/run :fight
  ;; {:intent/kind :fight :intent/target eid}: done when the target is gone (a kill); below
  ;; retreat-health it breaks off and walks away (done once away: a fight left is not a failure).
  ;; Where the target was last seen is kept (:intent/target-pos): a hunt collects its drop there.
  ;; With :intent/hold? the bot never walks: it waits for the target to come within approach-range,
  ;; never presses after it, and never breaks off (issue #263: the pearl hunt fights from a burrow
  ;; an enderman cannot reach into, where a step out is the one way to be hit)
  [world i event]
  (let [now (:time/now world)
        e (get-in world [:world/entities (:intent/target i)])
        i (cond-> i
            (nil? (:intent/stage i)) (assoc :intent/stage :arm :intent/since now)
            e (assoc :intent/target-pos (:entity/pos e)))
        world (assoc world :plan/intent i)]
    (cond (entity/gone? world (:intent/target i))
          (-> world
              (assoc :player/controls {})
              intent/done)
          (and (not= :retreat (:intent/stage i))
               (not (:intent/hold? i))
               (< (:player/health world 20.0) retreat-health))
          (-> world
              (intent/dissoc-intent :intent/child)
              (intent/assoc-intent :intent/stage :retreat :intent/since now))
          (and (= :approach (:intent/stage i)) (:intent/child i)) (walk-on world i e event)
          :else
          (case (window/waiting world i)
            :overdue (window/lost world i :arm)
            :waiting world
            (if (:intent/resync i)
              (window/resync world (window/view world :inventory))
              (fight-stage world i e))))))

;; ---------------------------------------------------------------- backing away

(def back-off-to 8.0)                 ; blocks a :back-off puts between the bot and its target

(defmethod intent/run :back-off
  ;; {:intent/kind :back-off :intent/target eid}: straight away from it, done once back-off-to
  ;; is between them, it is gone, or back-off-timeout-ms has passed (a cornered bot plans again)
  [world i _]
  (let [now (:time/now world)
        i (cond-> i (nil? (:intent/since i)) (assoc :intent/since now))
        world (assoc world :plan/intent i)
        e (get-in world [:world/entities (:intent/target i)])]
    (cond (or (entity/gone? world (:intent/target i))
              (>= (horizontal world e) back-off-to)
              (> (- now (:intent/since i)) back-off-timeout-ms))
          (-> world
              (assoc :player/controls {})
              intent/done)
          :else (assoc world :player/controls (intent/toward world (away-point world e) 0.0)))))

;; ---------------------------------------------------------------- the rows

(def defend-radius 4.0)               ; a hostile this near preempts any plan
(def over-feet 3.0)                   ; blocks a mob's feet may be over the bot's and be fought (its body, aim-height up, comes within reach)
(def under-feet 2.0)                  ; blocks under the bot's feet a mob may be and be fought
(def sight-step 0.25)                 ; blocks between the samples of a line of sight
(def creeper-radius 6.0)              ; a creeper this near is backed away from, never fought
(def attack-radius 16.0)              ; the gym's :attack kills every hostile this near

(defn clear?
  "Is the line from point a to point b clear of solid cells (terrain/solid-fn: an unloaded cell is
   solid), sampled every sight-step, a's own cell aside?"
  [world a b]
  (let [d (physics/distance a b)
        solid? (terrain/solid-fn world)]
    (or (< d 1.0e-6)
        (not-any? (fn [[x y z]] (solid? x y z))
                  (rest (physics/cells-along a (mapv #(/ (- %2 %1) d) a b) d sight-step))))))

(defn seen?
  "Is the line from the eye to entity e's aim clear (clear?)?"
  [world e]
  (clear? world (game/eye world) (aim e)))

(defn hittable?
  "Can entity e be fought from where the bot stands: its feet no more than over-feet over the
   bot's nor under-feet under them, and in sight (seen?). Gym blaze-fortress-c (run 38895759229):
   from its tunnel beside a fortress the :defend row went after blazes through the walls and
   hovering over it, failed :unreachable, and switched every half second between one of them and
   the blaze the blaze-rods row was fighting, killing neither, three times until the plan failed."
  [world e]
  (let [dy (- (physics/y-of (:entity/pos e)) (physics/y-of (:player/pos world)))]
    (and (<= (- under-feet) dy over-feet) (seen? world e))))

(defn threats
  "{eid entity} of the hostiles (entity/hostiles) the :defend row answers: within defend-radius of
   the feet, a creeper within creeper-radius; none the plan blacklisted (a ghost), and only what
   can be fought from here (hittable?)."
  [world]
  (let [pos (:player/pos world)
        bl (:plan/blacklist world #{})]
    (into {}
          (filter (fn [[eid e]]
                    (and (not (contains? bl eid))
                         (<= (physics/distance pos (:entity/pos e))
                             (if (= :creeper (entity/kind e)) creeper-radius defend-radius))
                         (hittable? world e))))
          (entity/hostiles world))))

(defn backing-off?
  "Is a :back-off for the :defend goal running? It runs to its end (back-off-to blocks between
   the bot and its target, the target gone, or back-off-timeout-ms): a threat that is out of
   sight for a tick (threats asks hittable?, and a step back turns a corner) does not end it.
   Gym snapshot R3-18-1 of issue #161 (a bot starved to one health, a hostile at the edge of its
   sight) switched between the back-off and the mine's walk every few ticks for fifteen minutes
   and stood where it began."
  [world]
  (let [i (:plan/intent world)] (and (= :back-off (:intent/kind i)) (= :defend (:intent/goal i)))))

(defmethod plan/done-by :safe?
  [world _]
  (and (not (backing-off? world)) (or (nil? (:player/pos world)) (empty? (threats world)))))

(defmethod plan/next-intent :defend
  ;; the nearest threat: a creeper, or anything while the health is under retreat-health, is
  ;; backed away from; anything else fought
  [world _ _]
  (when-let [[eid e] (entity/nearest (threats world) (:player/pos world))]
    (if (or (= :creeper (entity/kind e)) (< (:player/health world 20.0) retreat-health))
      {:intent/kind :back-off :intent/target eid}
      {:intent/kind :fight :intent/target eid})))

(defn quarry
  "{eid entity} of the hostiles the gym's :attack row kills: within attack-radius, not
   blacklisted."
  [world]
  (let [pos (:player/pos world)
        bl (:plan/blacklist world #{})]
    (into {}
          (filter (fn [[eid e]]
                    (and (not (contains? bl eid))
                         (<= (physics/distance pos (:entity/pos e)) attack-radius))))
          (entity/hostiles world))))

(def look-ms 3000)                    ; ms an :attack trial looks round before it may find nothing to kill

(defmethod plan/done-by :cleared?
  ;; nothing to kill, once look-ms have passed since the plan went active: the server shows the
  ;; entities round a landing a moment after its chunks, and gym attack-259a run 3 (run
  ;; 38881958899) was done 0.2 s after the go with the husk three blocks off still unseen
  [world _]
  (or (nil? (:player/pos world))
      (and (empty? (quarry world)) (>= (- (:time/now world) (:plan/since world 0)) look-ms))))

(defmethod plan/next-intent :attack
  ;; the nearest in the quarry fought; none yet while the trial looks round (:cleared?), a wait
  [world _ _]
  (if-let [[eid _] (entity/nearest (quarry world) (:player/pos world))]
    {:intent/kind :fight :intent/target eid}
    {:plan/wait :looking}))
