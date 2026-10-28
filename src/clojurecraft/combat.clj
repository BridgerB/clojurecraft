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
   blacklisted by the planner; below retreat-health the fight breaks off and walks away, or ends
   for its row to retreat (:intent/yield?). A break-off is remembered (:plan/fled) until health
   is well above that line, and a fight the held weapon cannot win is not started. A row whose
   act answers a kind of hostile itself (answers) has the :defend row leave that kind to it."
  (:require [clojure.math :as math]
            [clojurecraft.blocks :as blocks]
            [clojurecraft.entity :as entity]
            [clojurecraft.game :as game]
            [clojurecraft.intent :as intent]
            [clojurecraft.mob :as mob]
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
(def give-up-ms 60000)                ; ms a target given up as unreachable (given-up) is not picked again
(def resume-health 16)                ; a break-off under retreat-health waits for this before that mob is fought again: two zombie hits of 3 over the line, so regeneration from 9.2 to 10.2 does not restart it (race 40466567590, Clj_r04)
(def flee-radius 12.0)                ; a fled mob stays a threat this far, three times defend-radius and past back-off-to, so the back-off is not handed back to the walk it interrupted
(def flee-keep 32.0)                  ; past this a fled mob is no longer between the bot and a walk
(def craft-room 6.0)                  ; a losing fight makes a sword only once the mob is this far; inside defend-radius the back-off comes first
(def pass-by 1.5)                     ; a walk whose line comes this close to a fled mob on the plane is a walk toward it

(def swords
  "The swords by name, best first (attack damage: netherite 8, diamond 7, iron 6, stone and copper
   5, golden and wooden 4)."
  [:netherite_sword :diamond_sword :iron_sword :stone_sword :copper_sword :golden_sword
   :wooden_sword])

(def store-slots 36)                  ; player slots 0-35, the hotbar and the main inventory

(defn sword
  "[player-slot name] of the best sword held in the hotbar or the main inventory, or nil.
   Two of one name: the one with more swings left (window/swings-left), the higher slot when
   they are equal, so a fresh stone sword is armed beside the worn one it replaces."
  [world]
  (let [rows (sort-by :slot
                      >
                      (for [[p stack] (:player/inventory world)
                            :when (and (< -1 p store-slots) stack)
                            :let [n (recipe/item-name (:item stack))]
                            :when (some #{n} swords)]
                        {:slot p :name n :left (or (window/swings-left stack) 0)}))]
    (some (fn [n]
            (when-let [of (seq (filter #(= n (:name %)) rows))]
              (let [best (apply max-key :left of)] [(:slot best) n])))
          swords)))

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

(defn under-cell
  "Entity e's cell, brought down to the feet's height when it is over them: a hovering target is
   walked under on the bot's own floor (path's :near counts the height apart in its range, so a
   blaze two over its floor was near only from the one cell under it)."
  [world e]
  (let [[x y z] (cell (:entity/pos e))] [x (min y (second (intent/feet world))) z]))

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
                     {:goal/kind :near :goal/pos (under-cell world e) :goal/range route-range})
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

(defn floor-pos
  "The bot's feet position with y at the floor it stands on or jumped from: the cell the feet are
   in (a hair over a whole number counts as that number), or the one under it when nothing solid
   is under that (a jump rises at most a block and a quarter, so one cell down is the floor it left).
   Where the bot is whatever its jump has lifted it to, a stairs up's shaft lifting it to the next
   level at the top of every step."
  [world]
  (let [[x y z] (:player/pos world)
        solid? (terrain/solid-fn world)
        cx (long (math/floor x))
        cz (long (math/floor z))
        top (long (math/floor (+ (double y) 1.0e-3)))]
    [x (double (if (or (solid? cx (dec top) cz) (not (solid? cx (- top 2) cz))) top (dec top))) z]))

(defn floor-eye
  "The eye of a bot standing on its floor (floor-pos). A jump lifts :player/pos, and a reach
   judged from the top of it says a blaze one floor down is out of reach on the way up and in
   reach on the way down."
  [world]
  (physics/eye (floor-pos world)))

(defn in-reach?
  "Is entity e's aim within attack-reach of the eye standing on the floor?"
  [world e]
  (<= (physics/distance (floor-eye world) (aim e)) attack-reach))

(defn press-reaches?
  "Would closing to press-within on the plane put entity e's aim within attack-reach of the
   floor eye? The vertical gap stays; only the horizontal closes. A target at the same height,
   and a blaze two over the floor (issue #262), are true. One whose aim stays past attack-reach
   even there is false."
  [world e]
  (let [dy (- (physics/y-of (aim e)) (physics/y-of (floor-eye world)))
        h (min (horizontal world e) press-within)]
    (<= (math/sqrt (+ (* h h) (* dy dy))) attack-reach)))

(defn press-cell
  "The cell the feet would occupy after walking in to press-within toward entity e, at the
   floor's height."
  [world e]
  (let [[px py pz] (floor-pos world)
        [ax _ az] (aim e)
        dx (- ax px)
        dz (- az pz)
        h (math/sqrt (+ (* dx dx) (* dz dz)))
        step (if (pos? h) (max 0.0 (- h press-within)) 0.0)
        scale (if (pos? h) (/ step h) 0.0)
        fy (long (math/floor (+ (double py) 0.5)))]
    [(long (math/floor (+ px (* dx scale)))) fy (long (math/floor (+ pz (* dz scale))))]))

(defn press-stands?
  "Can the feet stand where a press in to press-within toward entity e would put them
   (press-cell): the floor's own cell, or one path/standable?? Issue #564 (sim R2-17-2): a
   blaze within approach-range on the plane stood below a soul-sand wall, so the cell the
   press would occupy was solid and no route went down to it."
  [world e]
  (let [c (press-cell world e)
        [px py pz] (floor-pos world)
        here [(long (math/floor px)) (long (math/floor (+ (double py) 0.5)))
              (long (math/floor pz))]]
    (or (= c here) (path/standable? world c))))

(defn strikable?
  "Can the sword reach entity e from here (in-reach?), or by pressing in to press-within onto
   a cell the feet can stand (press-reaches? and press-stands?) while it is still within
   approach-range on the plane?"
  [world e]
  (or
   (in-reach? world e)
   (and (<= (horizontal world e) approach-range) (press-reaches? world e) (press-stands? world e))))

(defn approachable?
  "Can a fight's approach come to entity e: the sword reaches from here or from a press that
   has a place to stand (strikable?), or a route walks there (route-walk a walk, not :here and
   not nil)? An act that chooses a target asks this, so it never starts a fight the approach
   can only fail :unreachable: gym blaze-fire-a (run 39131023637) runs 2 and 4 chose blazes a
   walk's route came within three of, from the end of a tunnel not yet into the spawner's room,
   and failed three fights in a row :unreachable in four seconds, the approach's own route,
   within two, finding none. A blaze within approach-range on the plane, the press's cell solid
   and no route down, is not (issue #564, sim R2-17-2): the shortcut used to be the horizontal
   alone, the engage pressed into the wall for twenty seconds, and the tunnel down was
   interrupted by the next one."
  [world e]
  (or (strikable? world e) (map? (route-walk world e))))

(defn given-up
  "The world with entity eid kept in :plan/given-up at now: a fight's engage gave it up as
   unreachable (not strikable, no route), so no act picks it again for a while (given-up?)."
  [world eid]
  (assoc-in world [:plan/given-up eid] (:time/now world)))

(defn given-up?
  "Was entity eid given up (given-up) less than give-up-ms ago, and is its aim still out of
   the sword's reach from here (in-reach?)? One that has come within reach since is fought
   at once. Issues #69, #62, #582 and #161: a defend or hunt fight on a mob in water two
   blocks below (hittable, never strikable, no route) ended done at once, the act picked
   the same target again the next tick, and the stage's own task never ran to its timeout."
  [world eid e]
  (let [at (get (:plan/given-up world) eid)]
    (boolean (and at (< (- (:time/now world) at) give-up-ms) (not (in-reach? world e))))))

(defmethod fight-stage :engage
  ;; face the target every tick, walking on toward it while it is out of reach (pressing?);
  ;; attack when the deadline has passed, the eyes face it (the look went out a tick before, with
  ;; the movement packet) and it is in reach; failed :unreachable when no attack has gone out for
  ;; approach-timeout-ms, hold-timeout-ms for a fight that holds its ground: gym batch
  ;; pearls-warped-b (plan run 39135767198) run 3 held a fight twenty minutes on an enderman two
  ;; blocks off on the plane and fourteen below, never in reach
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
          ;; already engaged on a blaze the sword cannot reach and cannot walk under, with no
          ;; route down: end done, so the blaze is not blacklisted and the tunnel starts. Issue
          ;; #564 starts here, :engage :to-the-end?, and three :unreachable failures end the plan.
          (and (not hold?) (not (strikable? world e)))
          (let [w (route-walk world e)]
            (cond (map? w)
                  (-> world
                      (assoc :player/controls {})
                      (intent/dissoc-intent :intent/best-dist :intent/best-tick)
                      (intent/assoc-intent :intent/stage :approach
                                           :intent/since now
                                           :intent/child (assoc w :intent/status :active)))
                  :else
                  (-> world
                      (assoc :player/controls {})
                      (given-up target)
                      intent/done)))
          (>= swings max-swings) (intent/fail world :ghost)
          (and since
               (> (- now (if (pos? next-swing) (- next-swing swing-every) since))
                  (if hold? hold-timeout-ms approach-timeout-ms)))
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

(defn flee-kind?
  "Is entity e one a break-off is remembered for? A creeper is backed away from at any health
   and shunned when it stands still, and a neutral is fought to the end, so neither is a fled fact."
  [e]
  (let [k (entity/kind e)] (and (some? e) (not= :creeper k) (not (contains? entity/neutral k)))))

(defn fled
  "The world with eid kept in :plan/fled at now for why (:hurt a break-off under retreat-health,
   :trade a fight the blows lose). A :hurt fact is not replaced by :trade: the hysteresis
   outranks the trade."
  [world eid why]
  (update-in world
             [:plan/fled eid]
             (fn [seen]
               (if (= :hurt (:fled/why seen)) seen {:fled/at (:time/now world) :fled/why why}))))

(defmethod intent/run :fight
  ;; {:intent/kind :fight :intent/target eid}: done when the target is gone (a kill); below
  ;; retreat-health it breaks off and walks away (done once away: a fight left is not a failure).
  ;; Where the target was last seen is kept (:intent/target-pos): a hunt collects its drop there.
  ;; With :intent/yield? it ends there instead (done), and its row plans the retreat itself; with
  ;; :intent/to-the-end? it never breaks off (a blaze at hand follows any retreat). A fight for
  ;; :hunt never breaks off either: an animal does not strike back, and a starving bot (food 0,
  ;; health under retreat-health) that backed off ended the fight done with the animal alive,
  ;; which the hunt took for a kill: it collected at the live animal, failed :not-picked-up and
  ;; fought again, to the end of the run (issue #178, sim race 39742404581, world R2/37).
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
    (cond (or (entity/gone? world (:intent/target i))
              (and (:intent/yield? i) (< (:player/health world 20.0) retreat-health)))
          (-> world
              (assoc :player/controls {})
              intent/done)
          (and (not= :retreat (:intent/stage i))
               (not (:intent/hold? i))
               (not (:intent/to-the-end? i))
               (not= :hunt (:intent/for i))
               (< (:player/health world 20.0) retreat-health))
          (-> (cond-> world (flee-kind? e) (fled (:intent/target i) :hurt))
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

(def shun-drift 1.0)                  ; blocks a creeper may stray from where it was first backed away from and still be the one standing still

(defn noted
  "The world with one more back-off from hostile eid (entity e) kept in :plan/backed-off, as
   {eid {:pos where it stood the first time :times back-offs from it there}}: a mob that stands
   within shun-drift of that place has not come on in all those backings away. One that has moved
   on starts its count again. Any kind is kept, not only a creeper's: sim race 39076908669 (issue
   #62, world R1/11) backed away at a third of a health from a zombie stuck in the rock three
   blocks off, seven thousand times."
  [world eid e]
  (update-in world
             [:plan/backed-off eid]
             (fn [{:keys [pos times] :as seen}]
               (if (and seen (<= (physics/distance pos (:entity/pos e)) shun-drift))
                 (assoc seen :times (inc times))
                 {:pos (:entity/pos e) :times 1}))))

(defn cornered
  "The world with entity eid kept in :plan/cornered: a back-off from it ended on its clock, or
   the way away was a drop no landing survives, the target still nearer than back-off-to, so
   there was nowhere to back off to."
  [world eid]
  (update world :plan/cornered (fnil conj #{}) eid))

(def void-look 3)                     ; blocks along a retreat looked at for a drop no landing survives

(defn void-step?
  "Is cell a clear drop with no floor under it and no landing within path/max-fall?"
  [world cell]
  (and (= :clear (path/classify world cell))
       (not (path/floor? world (update cell 1 dec)))
       (nil? (path/landing world cell))))

(defn void-retreat?
  "Does backing straight away from entity e step off a drop landing cannot survive: a cell one,
   two or three blocks along that line, at the feet or one up (where a jump onto a lip comes
   down), clear, with no floor and no landing within max-fall? ledge-look 0.6 sees only the lip.
   Issue #514 (sim R3-5-1): the cell one north was nether brick, the jump cleared it, and the
   bot fell 31 into the lava sea."
  [world e]
  (let [[px _ pz] (:player/pos world)
        [ax _ az] (away-point world e)
        d (physics/horizontal-distance [px 0.0 pz] [ax 0.0 az])
        [_ fy _] (intent/feet world)]
    (boolean (when (pos? d)
               (let [ux (/ (- ax px) d)
                     uz (/ (- az pz) d)]
                 (some (fn [n]
                         (let [x (long (math/floor (+ px (* (double n) ux))))
                               z (long (math/floor (+ pz (* (double n) uz))))]
                           (or (void-step? world [x fy z]) (void-step? world [x (inc fy) z]))))
                       (range 1 (inc void-look))))))))

(defn lava-step?
  "Is cell lava, or does it have lava beside or under it (path/lava-near?)?"
  [world cell]
  (let [at (path/cells world)] (or (= :lava (at cell)) (path/lava-near? at cell))))

(defn lava-retreat?
  "Does backing straight away from entity e step into or beside lava: a cell one,
   two or three blocks along that line, at the feet or one up?"
  [world e]
  (let [[px _ pz] (:player/pos world)
        [ax _ az] (away-point world e)
        d (physics/horizontal-distance [px 0.0 pz] [ax 0.0 az])
        [_ fy _] (intent/feet world)]
    (boolean (when (pos? d)
               (let [ux (/ (- ax px) d)
                     uz (/ (- az pz) d)]
                 (some (fn [n]
                         (let [x (long (math/floor (+ px (* (double n) ux))))
                               z (long (math/floor (+ pz (* (double n) uz))))]
                           (or (lava-step? world [x fy z])
                               (lava-step? world [x (inc fy) z])
                               (lava-step? world [x (dec fy) z]))))
                       (range 1 (inc void-look))))))))

(defmethod intent/run :back-off
  ;; {:intent/kind :back-off :intent/target eid}: straight away from it, done once back-off-to
  ;; is between them, it is gone, or back-off-timeout-ms has passed (a cornered bot plans again,
  ;; and has the target kept in :plan/cornered: :defend fights what it could not get away from).
  ;; The same when the way away is a drop no landing survives (void-retreat?) or steps into or
  ;; near lava (lava-retreat?): the lip one cell out hides the void from ledge-look, and the jump
  ;; clears it. The first tick notes where it stood (noted). A back-off :defend chose, :intent/why
  ;; :hurt or :trade, also remembers the mob (fled) so the walk it interrupted is not resumed at
  ;; defend-radius. :intent/distance, when the intent carries one, is how far: flee-radius, not
  ;; back-off-to, which a creeper's back-off still uses.
  [world i _]
  (let [now (:time/now world)
        eid (:intent/target i)
        e (get-in world [:world/entities eid])
        why (if (< (:player/health world 20.0) retreat-health) :hurt (or (:intent/why i) :trade))
        world (cond-> world
                (and e (nil? (:intent/since i))) (noted eid e)
                (and e (nil? (:intent/since i)) (#{:hurt :trade} (:intent/why i))) (fled eid why))
        i (cond-> i (nil? (:intent/since i)) (assoc :intent/since now))
        world (assoc world :plan/intent i)
        far (or (:intent/distance i) back-off-to)]
    (cond (or (entity/gone? world eid) (>= (horizontal world e) far))
          (-> world
              (assoc :player/controls {})
              intent/done)
          (or (> (- now (:intent/since i)) back-off-timeout-ms)
              (and e (or (void-retreat? world e) (lava-retreat? world e))))
          (-> world
              (assoc :player/controls {})
              (cornered (:intent/target i))
              intent/done)
          :else (assoc world :player/controls (intent/toward world (away-point world e) 0.0)))))

;; ---------------------------------------------------------------- the rows

(def defend-radius 4.0)               ; a hostile this near preempts any plan
(def over-feet 3.0)                   ; blocks a mob's feet may be over the bot's and be fought (its body, aim-height up, comes within reach)
(def under-feet 2.0)                  ; blocks under the bot's feet a mob may be and be fought
(def sight-step 0.25)                 ; blocks between the samples of a line of sight
(def creeper-radius 6.0)              ; a creeper this near is backed away from, never fought
(def shun-after 2)                    ; back-offs from one creeper that has not moved (shun-drift) after which it is shunned
(def shunned-radius 3.0)              ; a shunned creeper is a threat only this near (its fuse starts at the mob's :swell, 3)
(def given-up-after 6)                ; back-offs from a creeper that never moved after which only one at the feet is a threat
(def given-up-radius 1.5)
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
   bot's nor under-feet under them, and in sight from the eye of a bot standing on its floor
   (floor-pos, clear?). Gym blaze-fortress-c (run 38895759229): from its tunnel beside a fortress
   the :defend row went after blazes through the walls and hovering over it, failed :unreachable,
   and switched every half second between one of them and the blaze the blaze-rods row was
   fighting, killing neither, three times until the plan failed. Sim race 39698814387 (issue
   #178, world R4/14): two zombies three over the shaft of a stairs up were in sight only at the
   top of each jump (y 1.2, not 1.0), so a fight began and ended every fourteen ticks, the stairs
   up preempted and restarted each time, for the last quarter hour."
  [world e]
  (let [feet (floor-pos world)
        dy (- (physics/y-of (:entity/pos e)) (physics/y-of feet))]
    (and (<= (- under-feet) dy over-feet) (clear? world (physics/eye feet) (aim e)))))

(defn reach
  "Blocks within which hostile eid (entity e) is a threat, base at most: base, less once the bot
   has backed away from it shun-after times and it still stands where it did (:plan/backed-off,
   noted): shunned-radius, and after given-up-after back-offs given-up-radius, neither more than
   base. A mob behind a ledge or in the rock that never closes in is no reason to go on backing
   away for the rest of the plan: issues #69, #62 and #161, a starving bot at one health,
   back-off after back-off from one that never moved, and the walk to the ore, the water or the
   stairs never finished. One that moves on is counted afresh."
  [world eid e base]
  (let [{:keys [pos times]} (get-in world [:plan/backed-off eid])
        still? (and pos (<= (physics/distance pos (:entity/pos e)) shun-drift))]
    (cond (not still?) base
          (>= times given-up-after) (min base given-up-radius)
          (>= times shun-after) (min base shunned-radius)
          :else base)))

;; ---------------------------------------------------------------- staying away

(def hand-speed 4.0)                  ; the player's attack speed with an empty hand: a swing-every hit is fully charged

(def attack-speed
  "Attacks a second, the item's attack speed attribute, by tool kind. An axe's is by tier:
   wooden, golden, stone and copper 0.8, iron 0.9, diamond and netherite 1. A sword's 1.6
   finishes its charge inside swing-every (625 ms); an axe's and a pickaxe's do not, so the
   blow that lands at that pace is less than mob/weapon-damage, which is the full attribute."
  {:sword 1.6
   :pickaxe 1.2
   :shovel 1.0
   :axe {:wooden 0.8 :golden 0.8 :stone 0.8 :copper 0.8 :iron 0.9 :diamond 1.0 :netherite 1.0}})

(defn item-speed
  "The attack speed of item id: a tool's (attack-speed), the hand's when it is not a tool."
  [id]
  (let [{:keys [tier kind]} (blocks/tool id)]
    (cond (nil? kind) hand-speed
          (= kind :axe) (get (:axe attack-speed) tier 0.8)
          :else (get attack-speed kind hand-speed))))

(defn charged-damage
  "The damage one hit with item id deals when the swings are swing-every apart: mob/weapon-damage
   scaled by the charge that gap reaches, 0.2 + 0.8·charge². The sim's hits are the full
   attribute; the server's are not, and a swing just begun dealt a point (gym attack-259a)."
  [id]
  (let [charge (min 1.0 (/ (* (double swing-every) (double (item-speed id))) 1000.0))]
    (* (mob/weapon-damage id) (+ 0.2 (* 0.8 charge charge)))))

(defn best-blow
  "The damage of one swing-every hit with the strongest weapon in the hotbar or the main
   inventory, or the hand's when nothing held hits harder."
  [world]
  (reduce
   (fn [best [slot stack]]
     (if (and stack (< -1 (long slot) store-slots)) (max best (charged-damage (:item stack))) best))
   (charged-damage nil)
   (:player/inventory world)))

(defn mob-hit
  "The [damage ms] of one hit entity e lands on Normal, its :melee or its :shoot and the ticks
   between them, or nil when it does not hit (a creeper, a passive)."
  [e]
  (let [k (mob/kinds (entity/kind e))
        d (or (:melee k) (:shoot k))]
    (when (and d (:every k)) [(double d) (* (long (:every k)) 50)])))

(defn losing-trade?
  "Would a fight with entity e from this health lose: the swings that land before health falls
   under retreat-health deal less than the health it has (or its kind's max, when the server
   has not said)? A mob that does not hit is not a losing trade. Equal damage wins: a stone
   sword's four blows are a full zombie's 20."
  [world e]
  (let [[dmg every-ms] (mob-hit e)
        health (:player/health world 20.0)
        mob-health (or (:entity/health e) (:health (mob/kinds (entity/kind e))))]
    (boolean (and dmg
                  mob-health
                  (let [gap (- (double health) (double retreat-health))
                        n (if (neg? gap) 0 (inc (long (math/floor (/ gap (double dmg))))))
                        swings (long (math/floor (/ (* (double n) (double every-ms))
                                                    (double swing-every))))]
                    (< (* swings (best-blow world)) (double mob-health)))))))

(defn fled?
  "Is eid still a mob to stay away from: a :hurt flee while health is under resume-health, or
   a :trade flee while losing-trade? still holds?"
  [world eid]
  (let [why (get-in world [:plan/fled eid :fled/why])
        e (get-in world [:world/entities eid])]
    (cond (= why :hurt) (< (:player/health world 20.0) resume-health)
          (= why :trade) (boolean (and e (losing-trade? world e)))
          :else false)))

(defn healing?
  "Did a fight with eid break off under retreat-health, with health still under resume-health?
   Regeneration past retreat-health does not clear it."
  [world eid]
  (and (= :hurt (get-in world [:plan/fled eid :fled/why]))
       (< (:player/health world 20.0) resume-health)))

(defn spent
  "The world with flees that no longer hold removed: a :hurt once health is at resume-health,
   a :trade once the trade wins, either once the mob is gone. A later dip under resume-health
   does not bring a cleared :hurt back. Nothing left removes :plan/fled."
  [world]
  (let [health (:player/health world 20.0)
        left (into {}
                   (remove (fn [[eid {:fled/keys [why]}]]
                             (or (entity/gone? world eid)
                                 (and (= why :hurt) (>= health resume-health))
                                 (and (= why :trade)
                                      (not (losing-trade? world
                                                          (get-in world [:world/entities eid])))))))
                   (:plan/fled world))]
    (cond (empty? left) (dissoc world :plan/fled)
          (= left (:plan/fled world)) world
          :else (assoc world :plan/fled left))))

(defmethod plan/keep-world :fled [world] (spent world))

(defmulti rearm
  "An intent that makes a sword before the next fight, or nil. :chain when this is a chained
   run (the sword row registers it); :none otherwise, and by default when that row is not loaded."
  {:arglists '([world])}
  (fn [world] (if (:plan/chain? world) :chain :none)))

(defmethod rearm :none [_] nil)

(defmethod rearm :default [_] nil)

(defn craft-first
  "The craft rearm returns for entity e (eid), when the trade loses, health is at
   retreat-health or more and not still healing from a break-off, e is no neutral, and e is
   at least craft-room away. Nil when the back-off should keep going: a zombie inside
   defend-radius is not stood beside while a sword is made."
  [world eid e]
  (when (and e
             (<= retreat-health (:player/health world 20.0))
             (not (healing? world eid))
             (not (contains? entity/neutral (entity/kind e)))
             (losing-trade? world e)
             (>= (horizontal world e) craft-room))
    (rearm world)))

(defn flee-intent
  "A :back-off from eid out to flee-radius, for why (:hurt or :trade)."
  [eid why]
  {:intent/kind :back-off :intent/target eid :intent/why why :intent/distance flee-radius})

(defn passes?
  "Does the straight line from point a to point b come within pass-by of point p on the plane,
   strictly between the two (the fraction along the line in (0.05, 0.98))?"
  [a b p]
  (let [ax (double (first a))
        az (double (nth a 2))
        bx (double (first b))
        bz (double (nth b 2))
        px (double (first p))
        pz (double (nth p 2))
        dx (- bx ax)
        dz (- bz az)
        len2 (+ (* dx dx) (* dz dz))]
    (and (> len2 1.0e-6)
         (let [t (/ (+ (* (- px ax) dx) (* (- pz az) dz)) len2)
               cx (+ ax (* t dx))
               cz (+ az (* t dz))
               off (math/sqrt (+ (* (- px cx) (- px cx)) (* (- pz cz) (- pz cz))))]
           (and (< 0.05 t 0.98) (<= off pass-by))))))

(defn nearer?
  "Is block target's centre nearer entity e than the feet are, on the plane?"
  [world e target]
  (< (physics/horizontal-distance (physics/centre target) (:entity/pos e))
     (physics/horizontal-distance (:player/pos world) (:entity/pos e))))

(defn blocking?
  "Is eid a fled mob a walk must not close on? One reach has shrunk, because it stood still,
   is not: the walk may pass it (issues #161 and #62)."
  [world eid e]
  (and e
       (fled? world eid)
       (< (physics/distance (:player/pos world) (:entity/pos e)) flee-keep)
       (>= (reach world eid e flee-radius) flee-radius)))

(defn toward-fled
  "The [eid entity] of a fled mob a walk to block target would close on, or nil. The line
   passes within pass-by of it, or the target's centre is nearer it than the feet are."
  [world target]
  (some (fn [[eid _]]
          (let [e (get-in world [:world/entities eid])]
            (when (and (blocking? world eid e)
                       (or (passes? (:player/pos world) (physics/centre target) (:entity/pos e))
                           (nearer? world e target)))
              [eid e])))
        (:plan/fled world)))

(defn away-cell
  "The block cell of a point straight away from entity e, retreat-distance and more out."
  [world e]
  (cell (away-point world e)))

(defn not-toward-fled
  "intent, or a back-off (inside flee-radius) or a walk away (past it) when intent is a :walk
   that would close on a mob the bot fled. Race 40466567590 Clj_r04 at 11.71m: back-off from
   the zombie and the walk to [2898 11 2236] took turns every tick, and the bot never left."
  [world intent]
  (let [target (:intent/target intent)
        hit (when (and (= :walk (:intent/kind intent)) (vector? target))
              (toward-fled world target))]
    (cond (nil? hit) intent
          (< (horizontal world (second hit)) flee-radius)
          {:intent/kind :back-off
           :intent/target (first hit)
           :intent/distance flee-radius
           :intent/for :flee}
          :else
          {:intent/kind :walk :intent/target (away-cell world (second hit)) :intent/for :flee})))

(defmethod plan/divert :fled [world intent] (not-toward-fled world intent))

(defn close?
  "Is entity e inside the radius :defend answers before a flee stretches it: creeper-radius,
   or defend-radius? A craft is interrupted by this, not by a fled mob still out at flee-radius."
  [world e]
  (<= (physics/distance (:player/pos world) (:entity/pos e))
      (if (= :creeper (entity/kind e)) creeper-radius defend-radius)))

(defmulti answers
  "The hostile kinds a goal row's act fights and hides from itself, by the row's :goal/plan: the
   :defend row leaves them to it while the row is worked (answered-kinds). None by default."
  {:arglists '([plan])}
  identity)

(defmethod answers :default [_] #{})

(defmulti answering
  "The hostile kinds a goal row's act answers itself at this moment, by the row's :goal/plan and
   the world: answers, grown by what the world says (the dragon row, every kind while a breath
   cloud reaches the feet). answers alone by default."
  {:arglists '([world plan])}
  (fn [_world plan] plan))

(defmethod answering :default [_ plan] (answers plan))

(defn answered-kinds
  "The hostile kinds (answering) of the targets in play that are worked here, not reached and
   not done. Gym blaze-fire-a run 1 (run 39131023637): the :defend row fought a spawner's burst
   at hand, six blazes, from 20 health to 7 in four seconds and died backing away, while the
   blaze-rods row, which takes cover from three, was never asked."
  [world]
  (into #{}
        (comp (filter #(seq (answering world (:goal/plan %))))
              (filter #(plan/here? world %))
              (remove #(contains? (:plan/reached world #{}) (:goal/id %)))
              (remove #(plan/done-by world %))
              (mapcat #(answering world (:goal/plan %))))
        (plan/in-play world plan/goals)))

(defn threats
  "{eid entity} of the hostiles (entity/hostiles) the :defend row answers: within defend-radius of
   the feet, a creeper within creeper-radius, a fled mob within flee-radius (fled?, strict, so
   the back-off that finishes at that distance is not started again), any of them less once
   shunned (reach); none the plan blacklisted (a ghost), none of a kind a row in play answers
   itself (answered-kinds), only what can be fought from here (hittable?) and come to
   (approachable?: a mob in water two blocks below is seen but never strikable and no route
   reaches it, and a fight on it ends done at once, re-picked for the whole stage, issues #69
   and #62), and none given up as unreachable a while ago (given-up?)."
  [world]
  (let [pos (:player/pos world)
        bl (:plan/blacklist world #{})
        left (answered-kinds world)]
    (into {}
          (filter (fn [[eid e]]
                    (let [kind (entity/kind e)
                          fled (fled? world eid)
                          base (cond (= :creeper kind) creeper-radius
                                     fled flee-radius
                                     :else defend-radius)
                          radius (reach world eid e base)
                          d (physics/distance pos (:entity/pos e))]
                      (and (not (contains? bl eid))
                           (not (contains? left kind))
                           (not (given-up? world eid e))
                           (if fled (< d radius) (<= d radius))
                           (hittable? world e)
                           (approachable? world e)))))
          (entity/hostiles world))))

(defmethod plan/done-by :safe? [world _] (or (nil? (:player/pos world)) (empty? (threats world))))

(defmethod plan/next-intent :defend
  ;; the nearest threat. A creeper is backed away from, out to back-off-to. Anything else under
  ;; retreat-health, or still healing from that break-off (healing?, health under resume-health),
  ;; is backed away from out to flee-radius: regeneration from 9.2 to 10.2 must not start the
  ;; fight again (race 40466567590, Clj_r04). A trade the blows lose (losing-trade?) is backed
  ;; away from too, or a sword is made once the mob is craft-room off (craft-first), even in a
  ;; corner: a fist against a full zombie is a death. A neutral turned on the bot (an angry
  ;; enderman) is fought to the end whatever the health: it is faster and teleports, so a
  ;; back-off is only hits taken (gym dragon-e run 1, 40 to 10, then dead in its hands). What a
  ;; back-off could not get away from (:plan/cornered) is fought to the end when the trade wins
  ;; and the health is at retreat-health or more (issue #563: at 7.3 two cornered zombies were
  ;; fought down to 1.3; issue #161, a quarter hour of back-offs at under one health).
  [world _ _]
  (when-let [[eid e] (entity/nearest (threats world) (:player/pos world))]
    (let [neutral? (contains? entity/neutral (entity/kind e))
          hurt? (< (:player/health world 20.0) retreat-health)
          craft (craft-first world eid e)]
      (cond (= :creeper (entity/kind e)) {:intent/kind :back-off :intent/target eid}
            (and (not neutral?) (or hurt? (healing? world eid))) (flee-intent eid :hurt)
            craft craft
            (and (losing-trade? world e) (not neutral?)) (flee-intent eid :trade)
            (or neutral? (contains? (:plan/cornered world #{}) eid))
            {:intent/kind :fight :intent/target eid :intent/to-the-end? true}
            :else {:intent/kind :fight :intent/target eid}))))

(defmethod plan/interrupts? :defend
  ;; a back-off gives way once craft-first has a craft (the mob is craft-room off and the trade
  ;; loses). Any other intent but a fight gives way when a threat is back inside its own close
  ;; radius, so a mob that walks up during the craft is backed away from. A fled mob still out
  ;; near flee-radius does not: close? is defend-radius, not the stretched one.
  [world _ i]
  (cond (not (#{:fight :back-off} (:intent/kind i)))
        (boolean (some (fn [[_ e]] (close? world e)) (threats world)))
        (= :back-off (:intent/kind i))
        (boolean
         (craft-first world (:intent/target i) (get-in world [:world/entities (:intent/target i)])))
        :else false))

(defn quarry
  "{eid entity} of the hostiles the gym's :attack row kills: within attack-radius, not
   blacklisted, none given up as unreachable a while ago (given-up?)."
  [world]
  (let [pos (:player/pos world)
        bl (:plan/blacklist world #{})]
    (into {}
          (filter (fn [[eid e]]
                    (and (not (contains? bl eid))
                         (not (given-up? world eid e))
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
