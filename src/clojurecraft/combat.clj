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
            [clojurecraft.physics :as physics]
            [clojurecraft.place :as place]
            [clojurecraft.plan :as plan]
            [clojurecraft.recipe :as recipe]
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
  "Send one attack on eid with the held slot restated (set-carried-item), the arm swung, the next
   swing swing-every from now and the swing counted."
  [world eid]
  (let [now (:time/now world)]
    (-> world
        (game/emit {:packet/name :set-carried-item :slot (:player/held-slot world)})
        (game/emit {:packet/name :swing :hand (packet/value :hand :main-hand)})
        (game/emit {:packet/name :attack :entity-id eid})
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

(defmethod fight-stage :approach
  [world {:intent/keys [since]} e]
  (let [now (:time/now world)]
    (cond (<= (horizontal world e) approach-range)
          (-> world
              (assoc :player/controls {:control/look (physics/look-at (game/eye world) (aim e))})
              (intent/assoc-intent :intent/stage :engage))
          (> (- now since) approach-timeout-ms) (intent/fail world :unreachable)
          :else (assoc world :player/controls (intent/toward world (aim e) 0.0)))))

(defmethod fight-stage :engage
  ;; face the target every tick; attack when the deadline has passed, the eyes face it (the look
  ;; went out a tick before, with the movement packet) and it is in reach
  [world {:intent/keys [target next-swing swings] :or {next-swing 0 swings 0}} e]
  (let [now (:time/now world)
        eye (game/eye world)
        [yaw _ :as look] (physics/look-at eye (aim e))
        world (assoc world :player/controls {:control/look look})]
    (cond (> (horizontal world e) (inc approach-range))
          (intent/assoc-intent world :intent/stage :approach :intent/since now)
          (>= swings max-swings) (intent/fail world :ghost)
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
  ;; retreat-health it breaks off and walks away (done once away: a fight left is not a failure)
  [world i _]
  (let [now (:time/now world)
        i (cond-> i (nil? (:intent/stage i)) (assoc :intent/stage :arm :intent/since now))
        world (assoc world :plan/intent i)
        e (get-in world [:world/entities (:intent/target i)])]
    (cond (entity/gone? world (:intent/target i))
          (-> world
              (assoc :player/controls {})
              intent/done)
          (and (not= :retreat (:intent/stage i)) (< (:player/health world 20.0) retreat-health))
          (intent/assoc-intent world :intent/stage :retreat :intent/since now)
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
(def creeper-radius 6.0)              ; a creeper this near is backed away from, never fought
(def attack-radius 16.0)              ; the gym's :attack kills every hostile this near

(defn threats
  "{eid entity} of the hostiles (entity/hostiles) the :defend row answers: within defend-radius of
   the feet, a creeper within creeper-radius; none the plan blacklisted (a ghost)."
  [world]
  (let [pos (:player/pos world)
        bl (:plan/blacklist world #{})]
    (into {}
          (filter (fn [[eid e]]
                    (and (not (contains? bl eid))
                         (<= (physics/distance pos (:entity/pos e))
                             (if (= :creeper (entity/kind e)) creeper-radius defend-radius)))))
          (entity/hostiles world))))

(defmethod plan/done-by :safe? [world _] (or (nil? (:player/pos world)) (empty? (threats world))))

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
  [world _ _]
  (when-let [[eid _] (entity/nearest (quarry world) (:player/pos world))]
    {:intent/kind :fight :intent/target eid}))
