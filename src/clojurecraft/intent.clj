(ns clojurecraft.intent
  "Intents are values: {:intent/kind k :intent/status :active|:done|:failed ...}. The planner
   puts one in :plan/intent; `run` advances it by one tick, returning the world with the intent,
   :player/controls and effects updated. The set of kinds is open: a new kind is a defmethod in a
   new namespace. Every deadline is an absolute ms value compared against :time/now; the only
   randomness comes in on the tick event."
  (:require [clojurecraft.blocks :as blocks]
            [clojurecraft.game :as game]
            [clojurecraft.physics :as physics]))

(def reach 4.0)                       ; eye → block centre; the server allows ~4.5
(def walk-timeout-ticks 1200)         ; 60 s
(def stuck-ticks 40)                  ; no progress for 2 s → detour
(def detour-ticks 20)                 ; how long one detour lasts (1 s)
(def max-detours 4)                   ; detours before a walk fails :stuck
(def settle-ms 500)                   ; let the server catch up before START
(def dig-ms 3000)                     ; a log by hand: hardness 2 → 60 ticks
(def finish-after (+ (* dig-ms 1.35) 200)) ; an early FINISH aborts the break; a late one is accepted

(defn dig-time
  "ms to break a block by hand. Only the two kinds the bot digs today; real hardness and tools
   are issue #9. Leaves: hardness 0.2 → 6 ticks."
  [id]
  (if (and id (blocks/leaves? id)) 300 dig-ms))

(defn finish-delay
  "ms from START to FINISH when digging block id: its dig time with margin, since an early
   FINISH aborts the break and a late one is accepted."
  [id]
  (+ (* (dig-time id) 1.35) 200))
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

(defmethod run :walk
  [world {:intent/keys [target best-dist best-tick started detours detour-until detour-yaw]
          :or {best-dist Double/MAX_VALUE detours 0}} {:event/keys [rand]}]
  (let [tick (:time/tick world)
        started (or started tick)
        best-tick (or best-tick tick)
        d (physics/distance (game/eye world) (physics/centre target))
        progressed? (< d (- best-dist 0.25))
        best-dist (if progressed? d best-dist)
        best-tick (if progressed? tick best-tick)
        stuck? (> (- tick best-tick) stuck-ticks)
        detouring? (and detour-until (< tick detour-until))
        world (intent world assoc :intent/started started :intent/best-dist best-dist :intent/best-tick best-tick)]
    (cond
      (<= d reach)
      (-> world (assoc :player/controls {:control/look (physics/look-at (game/eye world) (physics/centre target))}) done)

      (or (> (- tick started) walk-timeout-ticks) (>= detours max-detours))
      (-> world (assoc :player/controls {}) (fail :stuck))

      (and stuck? (not detouring?))
      (let [yaw (if (< rand 0.5) -70.0 70.0)]
        (-> world
            (intent assoc :intent/detour-until (+ tick detour-ticks) :intent/detour-yaw yaw
                    :intent/detours (inc detours) :intent/best-tick tick)
            (assoc :player/controls (assoc (toward world (physics/centre target) yaw) :control/jump? true))))

      :else
      (assoc world :player/controls (toward world (physics/centre target) (if detouring? detour-yaw 0.0))))))

;; ---------------------------------------------------------------- dig

(defn still?
  "On the ground and not moving horizontally (vertical velocity is never zero at rest)."
  [world]
  (let [[vx _ vz] (:player/vel world)]
    (and (:player/on-ground? world) (< (abs vx) 0.05) (< (abs vz) 0.05))))

(defmethod run :dig
  [world {:intent/keys [target stage since still next-swing finish-at face] :or {stage :settle still 0}} _]
  (let [now (:time/now world)
        since (or since now)
        eye (game/eye world)
        world (intent world assoc :intent/stage stage :intent/since since)
        at-target (game/block-at world target)]
    (case stage
      :settle
      (cond
        (and at-target (not (blocks/solid? at-target)))
        (fail world :target-gone)

        (> (physics/distance eye (physics/centre target)) (+ reach 0.5))
        (fail world :out-of-reach)

        (and (>= still 3) (> (- now since) settle-ms))
        (let [seq (inc (:bot/sequence world))
              face (face-toward eye target)]
          (-> world
              (assoc :bot/sequence seq)
              (assoc :player/held-slot 0)
              (game/emit {:packet/name :set-carried-item :slot 0})
              (game/emit {:packet/name :player-action :status 0 :pos target :face face :sequence seq})
              (game/emit {:packet/name :swing :hand 0})
              (intent assoc :intent/stage :digging :intent/face face :intent/started now
                      :intent/next-swing (+ now swing-every) :intent/finish-at (+ now (finish-delay at-target)))
              (game/say (str "digging " target " face " face))))

        :else
        (-> world
            (assoc :player/controls {:control/look (physics/look-at eye (physics/centre target))})
            (intent assoc :intent/still (if (still? world) (inc still) 0))))

      :digging
      (cond
        (>= now finish-at)
        (let [seq (inc (:bot/sequence world))]
          (-> world
              (assoc :bot/sequence seq)
              (game/emit {:packet/name :player-action :status 2 :pos target :face face :sequence seq})
              (game/set-block target blocks/air)   ; do not wait for the server's echo
              done))

        (>= now next-swing)
        (-> world (game/emit {:packet/name :swing :hand 0}) (intent assoc :intent/next-swing (+ now swing-every)))

        :else world))))

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

(defn blocker
  "The leaf block in the way of walking from the player toward goal, at head or feet height,
   or nil. Only leaves: anything else is a job for the pathfinder (#4)."
  [world goal]
  (let [[px py pz] (:player/pos world)
        [gx _ gz] goal
        fx (long (Math/floor px)) fz (long (Math/floor pz)) feet (long (Math/floor py))
        cx (long (Math/floor (+ px (* 0.8 (Math/signum (double (- gx px)))))))
        cz (long (Math/floor (+ pz (* 0.8 (Math/signum (double (- gz pz)))))))]
    (first (for [[x z] (distinct [[cx fz] [fx cz] [cx cz]])
                 :when (not= [x z] [fx fz])
                 y [(inc feet) feet]
                 :let [id (game/block-at world [x y z])]
                 :when (and id (blocks/leaves? id))]
             [x y z]))))

(defmethod run :collect
  [world {:intent/keys [target since best-dist best-tick logs-before] :or {best-dist Double/MAX_VALUE}} _]
  (let [now (:time/now world)
        tick (:time/tick world)
        since (or since now)
        goal (or (:entity/pos (nearest-item world (physics/centre target))) (physics/centre target))
        d (physics/horizontal-distance (:player/pos world) goal)
        progressed? (< d (- best-dist 0.1))
        best-tick (if (or progressed? (nil? best-tick)) tick best-tick)
        logs-before (or logs-before (game/logs-held world))
        world (intent world assoc :intent/since since :intent/best-tick best-tick :intent/logs-before logs-before
                      :intent/best-dist (if progressed? d best-dist))
        stalled? (and (:player/horizontal-collision? world) (> (- tick best-tick) collect-stall-ticks))]
    (cond
      (> (game/logs-held world) logs-before)          ; one more log than when the collect began
      (-> world (assoc :player/controls {}) done)

      (and stalled? (blocker world goal))
      (-> world (assoc :player/controls {}) (intent assoc :intent/blocked-by (blocker world goal)) done)

      (> (- now since) collect-timeout)
      (-> world (assoc :player/controls {}) (fail :not-picked-up))

      (> d 0.4)
      (assoc world :player/controls (toward world goal 0.0))

      :else (assoc world :player/controls {}))))
