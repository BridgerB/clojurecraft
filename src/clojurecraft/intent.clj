(ns clojurecraft.intent
  "Intents are values: {:intent/kind k :intent/status :active|:done|:failed ...}. The planner
   puts one in :plan/intent; `run` advances it by one tick, returning the world with the intent,
   :player/controls and effects updated. The set of kinds is open: a new kind is a defmethod in a
   new namespace. Every deadline is an absolute ms value compared against :time/now; the only
   randomness comes in on the tick event."
  (:require [clojurecraft.blocks :as blocks]
            [clojurecraft.dig :as dig]
            [clojurecraft.game :as game]
            [clojurecraft.terrain :as terrain]
            [clojurecraft.inventory :as inventory]
            [clojurecraft.path :as path]
            [clojurecraft.physics :as physics]))

(def reach 4.0)                       ; eye → block centre; the server allows ~4.5
(def walk-timeout-ticks 1200)         ; 60 s
(def stuck-ticks 40)                  ; no waypoint reached for 2 s → plan again
(def max-replans 6)                   ; plans one walk may make before it fails :no-path
(def goal-range 3)                    ; a walk's route ends within this of its target
(def waypoint-reach 0.35)             ; horizontal distance to a waypoint's centre that reaches it
(def jump-near 1.3)                   ; a waypoint above the feet is jumped for from this close
(def pickup-rise 2)                   ; a drop resting more than 2 blocks above the feet is above
                                      ; the pickup box (1.8 tall, grown 0.5 up)
(def settle-ms 500)                   ; let the server catch up before START
(def dig-ms 3000)                     ; a log by hand: hardness 2 → 60 ticks (dig/ms derives it now)
(def finish-margin 1.35)              ; FINISH is sent this late: an early one aborts the break
(def finish-slack 200)                ; ms added to the margin
(def finish-after (+ (* dig-ms finish-margin) finish-slack)) ; a log by hand, for the tests that pin it
(def confirm-ticks 10)                ; after FINISH, ticks to wait for the server's ack or its refusal

(defn penalties
  "The dig penalties in force for the player: off the ground (the eyes in water is issue #5)."
  [world]
  {:off-ground? (not (:player/on-ground? world))})

(defn dig-time
  "ms to break block id with held (an item id, or nil for the hand) under the world's penalties:
   dig/ms, with 3000 for an unknown block so a stale sighting is still dug at a log's pace."
  ([id] (dig-time id nil {}))
  ([id held pen] (or (dig/ms id held pen) dig-ms)))

(defn finish-delay
  "ms from START to FINISH when a dig takes ms: with margin, since an early FINISH aborts the
   break and a late one is accepted."
  [ms]
  (+ (* ms finish-margin) finish-slack))
(def swing-every 350)                 ; ms between arm swings while digging
(def collect-timeout 10000)           ; ms a collect may take before it fails
(def collect-stall-ticks 20)          ; blocked this long with no progress → name the blocker

(defn face-toward
  "Block face nearest the eye: 0 down 1 up 2 north 3 south 4 west 5 east."
  [eye target]
  (let [[dx dy dz] (map - eye (physics/centre target))
        ax (abs dx) ay (abs dy) az (abs dz)]
    (cond (and (>= ay ax) (>= ay az)) (if (pos? dy) 1 0)
          (>= ax az) (if (pos? dx) 5 4)
          :else (if (pos? dz) 3 2))))

(defn- intent [world f & args] (apply update world :plan/intent f args))
(defn done "Mark the current intent done." [world] (intent world assoc :intent/status :done))
(defn fail
  "Mark the current intent failed, with the reason the planner reports."
  [world reason]
  (intent world assoc :intent/status :failed :intent/reason reason))
(defn done? "Did intent i finish?" [i] (= :done (:intent/status i)))
(defn failed? "Did intent i fail?" [i] (= :failed (:intent/status i)))

(defn toward
  "Controls that walk toward a point, jumping when blocked."
  [world point yaw-offset]
  (let [[yaw pitch] (physics/look-at (game/eye world) point)
        yaw (+ yaw yaw-offset)]
    {:control/forward? true
     :control/jump? (boolean (:player/horizontal-collision? world))
     :control/yaw yaw
     :control/look [yaw pitch]}))

(defmulti run
  "Advance the current intent by one tick."
  (fn [_world intent _event] (:intent/kind intent)))

(defmethod run :default [world i _] (fail world [:unknown-intent (:intent/kind i)]))

;; ---------------------------------------------------------------- walk

(defn arrived?
  "Is a walk over: the target within reach, and, for a log, the feet no more than pickup-rise
   below it, so the drop comes to rest inside the pickup box rather than on a bank above it."
  [world {:intent/keys [target for]} d]
  (and (<= d reach)
       (or (not= :log for)
           (<= (- (second target) (long (Math/floor (second (:player/pos world))))) pickup-rise))))

(defn waypoint-reached?
  "Is the player at waypoint [x y z]: within waypoint-reach of its centre on the plane, and the
   feet within half a block of its height. A climb counts only once the feet are up and a drop
   only once they are down: a looser gate let the siblings advance past a step they had not
   climbed, and let a bot stand on the lip of a ledge counting the cell below as reached."
  [world [wx wy wz]]
  (let [[px py pz] (:player/pos world)]
    (and (<= (physics/horizontal-distance [px 0 pz] [(+ wx 0.5) 0 (+ wz 0.5)]) waypoint-reach)
         (<= (abs (- py wy)) 0.5))))

(defn follow
  "Controls that walk to a waypoint: face its centre, forward, and jump only when it is above
   the feet and near (jumping from afar bounces in open air), or when blocked."
  [world [wx wy wz :as wp]]
  (let [[px py pz] (:player/pos world)
        centre [(+ wx 0.5) (+ wy 0.5) (+ wz 0.5)]
        near? (< (physics/horizontal-distance [px 0 pz] centre) jump-near)
        up? (> wy (+ (Math/floor py) 0.5))]
    (update (toward world centre 0.0) :control/jump? #(or % (and up? near?)))))

(defn route!
  "The intent with a fresh route from the feet toward the target (goal :near target within
   goal-range), counting the plan and noting how many chunks were loaded when it was made."
  [world {:intent/keys [target replans] :or {replans 0}}]
  (let [r (path/plan world (path/feet-cell (:player/pos world)) {:goal/kind :near :goal/pos target :goal/range goal-range})]
    (intent world assoc :intent/waypoints (:path/waypoints r) :intent/route (:path/status r)
            :intent/at 0 :intent/replans (inc replans) :intent/planned-chunks (:stats/chunks world 0)
            :intent/best-tick (:time/tick world))))

(defn route-stale?
  "Should the walk plan again: the next waypoint can no longer be stood on, a chunk arrived
   since the route was made, no waypoint was reached for stuck-ticks, or the route ended short
   of the target (:partial or :none) and every waypoint is behind."
  [world {:intent/keys [waypoints at planned-chunks best-tick route]}]
  (let [wp (get waypoints at)]
    (or (and wp (not (path/standable? world wp)))
        (not= planned-chunks (:stats/chunks world 0))
        (> (- (:time/tick world) best-tick) stuck-ticks)
        (and (nil? wp) (not= :found route)))))

(defmethod run :walk
  [world {:intent/keys [target started waypoints at replans route] :or {replans 0} :as i} _]
  (let [tick (:time/tick world)
        started (or started tick)
        d (physics/distance (game/eye world) (physics/centre target))
        world (intent world assoc :intent/started started)
        i (:plan/intent world)]
    (cond
      (arrived? world i d)
      (-> world (assoc :player/controls {:control/look (physics/look-at (game/eye world) (physics/centre target))}) done)

      (> (- tick started) walk-timeout-ticks)
      (-> world (assoc :player/controls {}) (fail :stuck))

      (nil? waypoints)                                  ; the first tick: plan
      (let [world (route! world i)]
        (if (and (= :none (get-in world [:plan/intent :intent/route])) (empty? (get-in world [:plan/intent :intent/waypoints])))
          (-> world (assoc :player/controls {}) (fail :no-path))
          (assoc world :player/controls {})))

      (route-stale? world i)
      (if (>= replans max-replans)
        (-> world (assoc :player/controls {}) (fail :no-path))
        (assoc (route! world i) :player/controls {}))

      (nil? (get waypoints at))                         ; route walked to its end, target still out of reach
      (assoc world :player/controls (toward world (physics/centre target) 0.0))

      (waypoint-reached? world (get waypoints at))
      (-> world (intent assoc :intent/at (inc at) :intent/best-tick tick) (assoc :player/controls {}))

      :else
      (assoc world :player/controls (follow world (get waypoints at))))))

;; ---------------------------------------------------------------- dig

(defn still?
  "On the ground and not moving horizontally (vertical velocity is never zero at rest)."
  [world]
  (let [[vx _ vz] (:player/vel world)]
    (and (:player/on-ground? world) (< (abs vx) 0.05) (< (abs vz) 0.05))))

(defn hotbar
  "The hotbar slots of the inventory, {slot item}: the only slots a dig can hold a tool from."
  [world]
  (into {} (filter (fn [[s _]] (<= 0 s 8)) (:player/inventory world))))

(defn choose-tool
  "[slot item-id] of the tool to dig block id with from the hotbar, [0 nil] for the hand, or
   :needs-tool when the block needs a tier no held tool of its kind reaches: digging it would
   break it and drop nothing, and the planner should plan for the tool instead."
  [world id]
  (let [best (dig/best-tool id (hotbar world) (penalties world))]
    (cond
      best best
      (and (blocks/needs-tier id) (not (dig/harvest? id nil))) :needs-tool
      :else [0 nil])))

(defmethod run :dig
  [world {:intent/keys [target stage since still next-swing finish-at face slot tool state]
          :or {stage :settle still 0}} _]
  (let [now (:time/now world)
        since (or since now)
        eye (game/eye world)
        world (intent world assoc :intent/stage stage :intent/since since)
        at-target (terrain/block-at world target)]
    (case stage
      :settle
      (cond
        (and at-target (not (blocks/solid? at-target)))
        (fail world :target-gone)

        (> (physics/distance eye (physics/centre target)) (+ reach 0.5))
        (fail world :out-of-reach)

        (= :needs-tool (choose-tool world at-target))
        (fail world :needs-tool)

        (and (>= still 3) (> (- now since) settle-ms))
        (let [seq (inc (:bot/sequence world))
              face (face-toward eye target)
              [slot tool] (choose-tool world at-target)
              ms (dig-time at-target tool (penalties world))]
          (-> world
              (assoc :bot/sequence seq)
              (assoc :player/held-slot slot)
              (game/emit {:packet/name :set-carried-item :slot slot})
              (game/emit {:packet/name :player-action :status 0 :pos target :face face :sequence seq})
              (game/emit {:packet/name :swing :hand 0})
              (intent assoc :intent/stage :digging :intent/face face :intent/started now
                      :intent/slot slot :intent/tool tool :intent/dig-ms ms :intent/state at-target
                      :intent/next-swing (+ now swing-every) :intent/finish-at (+ now (finish-delay ms)))
              (game/say (str "digging " target " face " face (when tool (str " with " (name (:kind (blocks/tool tool))) " in " slot)) " " ms "ms"))))

        :else
        (-> world
            (assoc :player/controls {:control/look (physics/look-at eye (physics/centre target))})
            (intent assoc :intent/still (if (still? world) (inc still) 0))))

      :digging
      (cond
        (and tool (not= tool (get-in world [:player/inventory slot :item])))
        (fail world :tool-broke)                       ; the slot no longer holds it: the next dig chooses again

        (>= now finish-at)
        (let [seq (inc (:bot/sequence world))]
          (-> world
              (assoc :bot/sequence seq)
              (game/emit {:packet/name :player-action :status 2 :pos target :face face :sequence seq})
              (terrain/set-block target blocks/air)   ; the server echoes a block-update and an ack; the overlay makes the bot independent of their timing
              (intent assoc :intent/stage :confirm :intent/since now :intent/sequence seq :intent/confirm-left confirm-ticks)))

        (>= now next-swing)
        (-> world (game/emit {:packet/name :swing :hand 0}) (intent assoc :intent/next-swing (+ now swing-every)))

        :else world)

      :confirm
      (let [left (:intent/confirm-left (:plan/intent world))
            restored? (and at-target (= at-target state))]
        (cond
          restored? (fail world :rejected)             ; the server put the block back: the break was refused
          (>= (:stats/last-ack world -1) (:intent/sequence (:plan/intent world))) (done world)
          (zero? left) (done world)                    ; no word either way in time: trust the overlay
          :else (intent world assoc :intent/confirm-left (dec left)))))))

;; ---------------------------------------------------------------- collect

(defn nearest-item
  "The tracked entity nearest the point near, within 6 blocks, or nil."
  [world near]
  (->> (:world/entities world)
       vals
       (map (fn [e] [(physics/distance near (:entity/pos e)) e]))
       (filter (fn [[d _]] (<= d 6.0)))
       (sort-by first)
       first
       second))

(defn step?
  "Is the block at pos a one-block step: solid, with no solid block on top of it? A wall (two
   solid blocks, e.g. a trunk) is not a step."
  [world [x y z]]
  (boolean (and (some-> (terrain/block-at world [x y z]) blocks/solid?)
                (not (some-> (terrain/block-at world [x (inc y) z]) blocks/solid?)))))

(defn blocker
  "The leaf block in the way of walking from the player toward goal, at head or feet height,
   or, where the way steps up one block (step?), one above the head over the step and over the
   player: a jump onto a step needs headroom in both columns (recorded live 2026-10-10: a drop
   on a ledge under a canopy edge, leaves over the bot too). nil when none. Only leaves:
   anything else is a job for the pathfinder (#4)."
  [world goal]
  (let [[px py pz] (:player/pos world)
        [gx _ gz] goal
        fx (long (Math/floor px)) fz (long (Math/floor pz)) feet (long (Math/floor py))
        cx (long (Math/floor (+ px (* 0.8 (Math/signum (double (- gx px)))))))
        cz (long (Math/floor (+ pz (* 0.8 (Math/signum (double (- gz pz)))))))]
    (let [ahead (remove #{[fx fz]} (distinct [[cx fz] [fx cz] [cx cz]]))
          steps (filter (fn [[x z]] (step? world [x feet z])) ahead)
          spots (concat (for [[x z] ahead, y [(inc feet) feet]] [x y z])
                        (for [[x z] steps] [x (+ feet 2) z])
                        (when (seq steps) [[fx (+ feet 2) fz]]))]
      (first (filter #(some-> (terrain/block-at world %) blocks/leaves?) spots)))))

(defmethod run :collect
  [world {:intent/keys [target since best-dist best-tick logs-before] :or {best-dist Double/MAX_VALUE}} _]
  (let [now (:time/now world)
        tick (:time/tick world)
        since (or since now)
        goal (or (:entity/pos (nearest-item world (physics/centre target))) (physics/centre target))
        d (physics/horizontal-distance (:player/pos world) goal)
        progressed? (< d (- best-dist 0.1))
        best-tick (if (or progressed? (nil? best-tick)) tick best-tick)
        logs-before (or logs-before (inventory/logs-held world))
        world (intent world assoc :intent/since since :intent/best-tick best-tick :intent/logs-before logs-before
                      :intent/best-dist (if progressed? d best-dist))
        stalled? (and (:player/horizontal-collision? world) (> (- tick best-tick) collect-stall-ticks))]
    (cond
      (> (inventory/logs-held world) logs-before)          ; one more log than when the collect began
      (-> world (assoc :player/controls {}) done)

      (and stalled? (blocker world goal))
      (-> world (assoc :player/controls {}) (intent assoc :intent/blocked-by (blocker world goal)) done)

      (> (- now since) collect-timeout)
      (-> world (assoc :player/controls {}) (fail :not-picked-up))

      (> d 0.4)
      (assoc world :player/controls (toward world goal 0.0))

      :else (assoc world :player/controls {}))))
