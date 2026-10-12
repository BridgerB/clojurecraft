(ns clojurecraft.intent
  "Intents are values: {:intent/kind k :intent/status :active|:done|:failed ...}. The planner
   puts one in :plan/intent; `run` advances it by one tick, returning the world with the intent,
   :player/controls and effects updated. The set of kinds is open: a new kind is a defmethod in a
   new namespace. Every deadline is an absolute ms value compared against :time/now; the only
   randomness comes in on the tick event."
  (:require [clojure.math :as math]
            [clojurecraft.blocks :as blocks]
            [clojurecraft.dig :as dig]
            [clojurecraft.game :as game]
            [clojurecraft.inventory :as inventory]
            [clojurecraft.packet :as packet]
            [clojurecraft.path :as path]
            [clojurecraft.physics :as physics]
            [clojurecraft.terrain :as terrain]))

(set! *warn-on-reflection* true)

(def reach 4.0)                       ; eye → block centre; the server allows ~4.5
(def walk-timeout-ticks 1200)         ; 60 s
(def stuck-ticks 40)                  ; no waypoint reached for 2 s → plan again
(def max-replans 6)                   ; plans one walk may make before it fails :no-path
(def goal-range 3)                    ; a walk's route ends within this of its target
(def waypoint-reach 0.35)             ; horizontal distance to a waypoint's centre that reaches it
(def jump-near 1.3)                   ; a waypoint above the feet is jumped for from this close
(def pickup-range 1.5)               ; octile cells from a dug block's own cell where its drop is in the
                                      ; pickup box (1.3 each way): a neighbour, diagonals too
(def scoop-range 1)                   ; cells from a water source where a scoop walk ends: right beside it on its
                                      ; bank, where nothing stands between the eyes and its surface
(def pickup-rise 2)                   ; a drop resting more than 2 blocks above the feet is above
                                      ; the pickup box (1.8 tall, grown 0.5 up)
(def settle-ms 150)                   ; let the server catch up before START: three still ticks
(def dig-ms 3000)                     ; a log by hand: hardness 2 → 60 ticks (dig/ms derives it now)
(def finish-margin 1.15)              ; FINISH is sent this late: the server takes it at 70% of its own ticks, aborts it before
(def finish-slack 100)                ; ms added to the margin
(def confirm-ticks 10)                ; after FINISH, ticks to wait for the server's ack or its refusal

(defn penalties
  "The dig penalties in force for the player: off the ground, the eyes in water; each divides
   the dig speed by five, so an escape dig under water is slow as vanilla makes it."
  [world]
  {:off-ground? (not (:player/on-ground? world))
   :eyes-in-water? (boolean (:player/eye-in-water? world))})

(def never-dug #{:obsidian})          ; a portal frame is never a dig target (ruststeve dug its own frame)

(def falling-reach 16)                ; gravel or sand stacked over a cell that a fluid on top is looked for through

(defn fluid-over-falling?
  "Is there water or lava resting on gravel or sand stacked straight over pos: opening pos lets
   the stack fall and the fluid follow it down. Gym batch iron-a4 run 1 tunnelled under a
   riverbed, the gravel over a cell it cut fell into it, and the river came down the tunnel."
  [world [x y z]]
  (let [over (map (fn [dy] [x (+ y dy) z]) (range 1 (inc falling-reach)))
        stack (take-while #(some-> (terrain/block-at world %)
                                   blocks/falls?)
                          over)]
    (boolean (and (seq stack) (terrain/fluid-at world (nth over (count stack)))))))

(defn exposes-fluid?
  "Would breaking target let water or lava in: is any of its six neighbours a fluid (steve's
   digExposesWater), or does a fluid rest on gravel or sand over it (fluid-over-falling?)?"
  [world [x y z :as target]]
  (boolean (or (some (fn [[dx dy dz]] (terrain/fluid-at world [(+ x dx) (+ y dy) (+ z dz)]))
                     [[1 0 0] [-1 0 0] [0 1 0] [0 -1 0] [0 0 1] [0 0 -1]])
               (fluid-over-falling? world target))))

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
(def ledge-look 0.6)                  ; blocks ahead of the feet a collect looks for a floor before stepping

(defn face-toward
  "The face of the block at target nearest the eye, as the packets number it (packet/value :face):
   the eye above it sees :up, east of it :east, and so on along whichever axis it is farthest."
  [eye target]
  (let [[dx dy dz] (map - eye (physics/centre target))
        ax (abs dx)
        ay (abs dy)
        az (abs dz)]
    (packet/value :face
                  (cond (and (>= ay ax) (>= ay az)) (if (pos? dy) :up :down)
                        (>= ax az) (if (pos? dx) :east :west)
                        :else (if (pos? dz) :south :north)))))

(defn assoc-intent
  "The world with keys set on the running intent (:plan/intent)."
  [world & kvs]
  (apply update world :plan/intent assoc kvs))

(defn dissoc-intent
  "The world with keys removed from the running intent: what is over is absent, never nil."
  [world & ks]
  (apply update world :plan/intent dissoc ks))

(defn done "Mark the current intent done." [world] (assoc-intent world :intent/status :done))
(defn fail
  "Mark the current intent failed, with the reason the planner reports."
  [world reason]
  (assoc-intent world :intent/status :failed :intent/reason reason))
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
  {:arglists '([world intent event])}
  (fn [_world intent _event] (:intent/kind intent)))

(defmethod run :default [world i _] (fail world [:unknown-intent (:intent/kind i)]))

;; ---------------------------------------------------------------- intents composing intents

(defn feet
  "The cell the feet stand in: y a touch below the position, since physics jitter dips it
   just under the integer (ruststeve's (p.y - 0.5).floor())."
  [world]
  (let [[x y z] (:player/pos world)]
    [(long (math/floor x)) (long (math/floor (+ y 0.5))) (long (math/floor z))]))

(defn child-dig
  "A fresh :dig intent for one cell, to be run by the dig executor as another intent's child."
  [target]
  {:intent/kind :dig :intent/target target :intent/status :active})

(defn chain-next
  "The next link of a walk → dig → collect chain toward one drop, from the intent that just
   ended (last), or nil: after the walk, dig its target; after the dig, collect the drop
   (counting drops). Only a link of the chain for `for` continues, so a walk for another
   purpose (to a crafting table) is never taken for one. Every link carries extra (a trunk)."
  [last for drops extra]
  (when (= for (:intent/for last))
    (case (:intent/kind last)
      :walk (merge {:intent/kind :dig :intent/target (:intent/target last) :intent/for for} extra)
      :dig
      (merge {:intent/kind :collect
              :intent/target (:intent/target last)
              :intent/for for
              :intent/drops drops}
             extra)
      nil)))

(defn run-child
  "One tick of the child intent at :intent/child by its own executor: the child stands in as
   :plan/intent for the tick, then returns to its place. Returns the world."
  [world event]
  (let [parent (:plan/intent world)
        world (run (assoc world :plan/intent (:intent/child parent)) (:intent/child parent) event)
        child (:plan/intent world)]
    (assoc world :plan/intent (assoc parent :intent/child child))))

;; ---------------------------------------------------------------- walk

(defn walk-goal
  "Where a walk's route may end: for a block mined from beside (:intent/pickup?), any cell
   within pickup-range of it whose feet are where the drop lands in reach (path's :pickup goal:
   the block at or up to pickup-rise above them; no single cell beside it, which a wall may
   close off), and with :intent/step-down? a straight neighbour a block up too (the block in the
   floor beside the feet, path's :goal/step-down?); for water to scoop, its bank (arrived?: path's :pickup goal at the cell over the
   source, rise 1); for a build's stand, that cell itself; else anywhere within goal-range."
  [{:intent/keys [target for pickup? step-down?]}]
  (cond pickup?
        (cond->
          {:goal/kind :pickup :goal/pos target :goal/range pickup-range :goal/rise pickup-rise}
          step-down? (assoc :goal/step-down? true))
        (= :scoop for)
        {:goal/kind :pickup :goal/pos (update target 1 inc) :goal/range scoop-range :goal/rise 1}
        (= :build for) {:goal/kind :block :goal/pos target}
        :else {:goal/kind :near :goal/pos target :goal/range goal-range}))

(def centred-within 0.25)             ; how far from a cell's middle the feet may stand and count as on it

(defn centred?
  "Are the feet in cell, within centred-within of its middle on the plane?"
  [world cell]
  (and (= cell (feet world))
       (<= (physics/horizontal-distance (:player/pos world) (physics/centre cell)) centred-within)))

(defn arrived?
  "Is a walk over: the target within reach, and the feet where the drop will come to rest inside
   the pickup box: for a log, no more than pickup-rise below it (not on a bank above the box);
   for a block mined from beside (:intent/pickup?: stone, ore), its walk-goal: neither above it
   (the drop would lie under the box) nor more than pickup-rise below it, and within
   pickup-range of its cell on the plane, or a block up beside it with :intent/step-down?: the
   drop lands in the block's own cell, and a collect walks straight at it, so from farther,
   round a tunnel's corner, it walked into the wall until it failed. For water to
   scoop, on its bank: the feet level with the cell over the source or a block lower, within
   scoop-range on the plane, where the eyes look down on its surface; the tenth live run's walk
   ended in reach but in its own stair pit two below the surface, and saw no water. For a build's
   stand (#8: a pour is aimed from it), the feet in that cell near its middle (centred?)."
  [world {:intent/keys [target for pickup?] :as i} d]
  (let [rise (- (physics/y-of target) (long (math/floor (physics/y-of (:player/pos world)))))]
    (and (<= d reach)
         (cond pickup? (path/goal-done? (walk-goal i) (feet world))
               (= :scoop for)
               (and (<= -1 rise 0) (<= (path/octile (feet world) target) scoop-range))
               (= :log for) (<= rise pickup-rise)
               (= :build for) (centred? world target)
               :else true))))

(defn routable?
  "Is there a way to walk intent i's goal from the feet: the planner's route is :found, or
   :partial toward a target in a chunk not loaded yet (the walk plans again as chunks arrive).
   Toward a target in a chunk not loaded, a :none route with waypoints is progress too: the search
   ran out of loaded terrain, not of ways (gym batch solid-b1 run 1's own tunnel home ran to the
   edge of what was loaded). Not :none toward a loaded target, nor :partial toward a target in
   loaded terrain, the budget spent on it: walks to such targets failed :no-path again and
   again. An act asks before it chooses a target, so one with no way to it is passed over, not
   walked at: three such walks in a row ended live runs among ore and water in caves."
  [world {:intent/keys [target] :as i}]
  (let [{:path/keys [status waypoints]}
        (path/plan world (path/feet-cell (:player/pos world)) (walk-goal i))]
    (case status
      :found true
      (:partial :none) (boolean (and (seq waypoints) (not (terrain/chunk-loaded? world target))))
      false)))

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
  [world [wx wy wz]]
  (let [[px py pz] (:player/pos world)
        centre [(+ wx 0.5) (+ wy 0.5) (+ wz 0.5)]
        near? (< (physics/horizontal-distance [px 0 pz] centre) jump-near)
        up? (> wy (+ (math/floor py) 0.5))]
    (update (toward world centre 0.0) :control/jump? #(or % (and up? near?)))))

(defn route!
  "The intent with a fresh route from the feet toward its walk-goal, counting the plan and
   noting how many chunks were loaded when it was made."
  [world {:intent/keys [replans] :or {replans 0} :as i}]
  (let [r (path/plan world (path/feet-cell (:player/pos world)) (walk-goal i))]
    (assoc-intent world
                  :intent/waypoints (:path/waypoints r)
                  :intent/route (:path/status r)
                  :intent/at 0
                  :intent/replans (inc replans)
                  :intent/planned-chunks (:stats/chunks world 0)
                  :intent/best-tick (:time/tick world))))

(defn route-stale?
  "Should the walk plan again: a waypoint still ahead can no longer be stood on (a chunk that
   arrived or a block that changed broke the route), no waypoint was reached for stuck-ticks,
   or the route ended short of the target (:partial or :none) and every waypoint is behind. A
   chunk arriving is not by itself a reason: six arrived in the first seconds of a gym run and
   spent all six plans before the bot had walked a step, though every plan found the same route."
  [world {:intent/keys [waypoints at best-tick route]}]
  (let [ahead (drop at waypoints)]
    (or (some #(not (path/standable? world %)) ahead)
        (> (- (:time/tick world) best-tick) stuck-ticks)
        (and (empty? ahead) (not= :found route)))))

(defmethod run :walk
  [world {:intent/keys [target started waypoints at replans] :or {replans 0}} _]
  (let [tick (:time/tick world)
        started (or started tick)
        d (physics/distance (game/eye world) (physics/centre target))
        world (assoc-intent world :intent/started started)
        i (:plan/intent world)]
    (cond (arrived? world i d)
          (-> world
              (assoc :player/controls
                     {:control/look (physics/look-at (game/eye world) (physics/centre target))})
              done)
          (> (- tick started) walk-timeout-ticks)
          (-> world
              (assoc :player/controls {})
              (fail :stuck))
          (nil? waypoints) ; the first tick: plan
          (let [world (route! world i)]
            (if (and (= :none (get-in world [:plan/intent :intent/route]))
                     (empty? (get-in world [:plan/intent :intent/waypoints])))
              (-> world
                  (assoc :player/controls {})
                  (fail :no-path))
              (assoc world :player/controls {})))
          (route-stale? world i)
          (if (>= replans max-replans)
            (-> world
                (assoc :player/controls {})
                (fail :no-path))
            (assoc (route! world i) :player/controls {}))
          (nil? (get waypoints at)) ; route walked to its end, target still out of reach
          (assoc world :player/controls (toward world (physics/centre target) 0.0))
          (waypoint-reached? world (get waypoints at))
          (-> world
              (assoc-intent :intent/at (inc at) :intent/best-tick tick)
              (assoc :player/controls {}))
          :else (assoc world :player/controls (follow world (get waypoints at))))))

;; ---------------------------------------------------------------- dig

(defn still?
  "On the ground, or afloat in a liquid, and not moving horizontally (vertical velocity is
   never zero at rest)."
  [world]
  (let [[vx _ vz] (:player/vel world)]
    (and (or (:player/on-ground? world) (:player/in-water? world) (:player/in-lava? world))
         (< (abs vx) 0.05)
         (< (abs vz) 0.05))))

(defn hotbar
  "The hotbar slots of the inventory, {slot item}: the only slots a dig can hold a tool from."
  [world]
  (into {} (filter (fn [[s _]] (inventory/hotbar? s)) (:player/inventory world))))

(defn stowed-tool
  "[slot item-id] of the tool to dig block id with when it is only in the main inventory, nil
   when the hotbar holds one or none is held. A dig chooses from the hotbar, and with the hotbar
   full of what mining picked up, a crafted pickaxe lands in the main inventory: gym batch
   iron-a3 runs 1 and 3 held a stone pickaxe there and stopped for want of one."
  [world id]
  (when-not (dig/best-tool id (hotbar world) (penalties world))
    (when-let [[slot _ :as t] (dig/best-tool id (:player/inventory world) (penalties world))]
      (when-not (inventory/hotbar? slot) t))))

(defn choose-tool
  "[slot item-id] of the tool to dig block id with from the hotbar, [0 nil] for the hand, or
   :needs-tool when the block needs a tier no held tool of its kind reaches: digging it would
   break it and drop nothing, and the planner should plan for the tool instead."
  [world id]
  (let [best (dig/best-tool id (hotbar world) (penalties world))]
    (cond best best
          (and (blocks/needs-tier id) (not (dig/harvest? id nil))) :needs-tool
          :else [0 nil])))

(defmulti dig-stage
  "Advance a :dig intent one tick in its :intent/stage (:settle :digging :confirm), the stage and
   its start already on the intent."
  {:arglists '([world intent])}
  (fn [_world i] (:intent/stage i)))

(defmethod dig-stage :settle
  [world {:intent/keys [target since still exposes-ok?] :or {still 0}}]
  (let [now (:time/now world)
        eye (game/eye world)
        at-target (terrain/block-at world target)]
    (cond (and at-target (not (blocks/solid? at-target))) (fail world :target-gone)
          (> (physics/distance eye (physics/centre target)) (+ reach 0.5))
          (fail world :out-of-reach)
          (= :needs-tool (choose-tool world at-target)) (fail world :needs-tool)
          (or (contains? never-dug (blocks/name-of at-target))
              (and (not exposes-ok?) (exposes-fluid? world target)))
          (fail world :unsafe)
          (and (>= still 3) (> (- now since) settle-ms))
          (let [seq (inc (:bot/sequence world))
                face (face-toward eye target)
                [slot tool] (choose-tool world at-target)
                ms (dig-time at-target tool (penalties world))]
            (-> world
                (assoc :bot/sequence seq)
                (assoc :player/held-slot slot)
                (game/emit {:packet/name :set-carried-item :slot slot})
                (game/emit {:packet/name :player-action
                            :status (packet/value :dig :start-destroy)
                            :pos target
                            :face face
                            :sequence seq})
                (game/emit {:packet/name :swing :hand (packet/value :hand :main-hand)})
                (assoc-intent :intent/stage :digging
                              :intent/face face
                              :intent/started now
                              :intent/slot slot
                              :intent/tool tool
                              :intent/dig-ms ms
                              :intent/state at-target
                              :intent/next-swing (+ now swing-every)
                              :intent/finish-at (+ now (finish-delay ms)))
                (game/say (str "digging "
                               target
                               " face "
                               face
                               (when tool
                                 (str " with " (name (:kind (blocks/tool tool))) " in " slot))
                               " "
                               ms
                               "ms"))))
          :else
          (-> world
              (assoc :player/controls {:control/look (physics/look-at eye (physics/centre target))})
              (assoc-intent :intent/still (if (still? world) (inc still) 0))))))

(defmethod dig-stage :digging
  [world {:intent/keys [target face slot tool next-swing finish-at]}]
  (let [now (:time/now world)]
    (cond (and tool (not= tool (get-in world [:player/inventory slot :item])))
          (fail world :tool-broke) ; the slot no longer holds it: the next dig chooses again
          (>= now finish-at)
          (let [seq (inc (:bot/sequence world))]
            (-> world
                (assoc :bot/sequence seq)
                (game/emit {:packet/name :player-action
                            :status (packet/value :dig :stop-destroy)
                            :pos target
                            :face face
                            :sequence seq})
                (terrain/set-block target blocks/air) ; the server echoes a block-update and an ack; the overlay makes the bot independent of their timing
                (assoc-intent :intent/stage :confirm
                              :intent/since now
                              :intent/sequence seq
                              :intent/confirm-left confirm-ticks)))
          (>= now next-swing)
          (-> world
              (game/emit {:packet/name :swing :hand (packet/value :hand :main-hand)})
              (assoc-intent :intent/next-swing (+ now swing-every)))
          :else world)))

(defmethod dig-stage :confirm
  [world {:intent/keys [target state sequence confirm-left]}]
  (let [at-target (terrain/block-at world target)]
    (cond (and at-target (= at-target state)) (fail world :rejected) ; the server put the block back: the break was refused
          ;; the server acknowledged, or no word either way in time: trust the overlay
          (or (>= (:stats/last-ack world -1) sequence) (zero? confirm-left)) (done world)
          :else (assoc-intent world :intent/confirm-left (dec confirm-left)))))

(defmethod run :dig
  [world {:intent/keys [stage since] :or {stage :settle}} _]
  (let [world (assoc-intent world :intent/stage stage :intent/since (or since (:time/now world)))]
    (dig-stage world (:plan/intent world))))

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
  (boolean (and (some-> (terrain/block-at world [x y z])
                        blocks/solid?)
                (not (some-> (terrain/block-at world [x (inc y) z])
                             blocks/solid?)))))

(defn blocker
  "The leaf block in the way of walking from the player toward goal, at head or feet height,
   or, where the way steps up one block (step?), one above the head over the step and over the
   player: a jump onto a step needs headroom in both columns (recorded live 2026-10-10: a drop
   on a ledge under a canopy edge, leaves over the bot too). nil when none. Only leaves:
   anything else is a job for the pathfinder (#4)."
  [world goal]
  (let [[px py pz] (:player/pos world)
        [gx _ gz] goal
        fx (long (math/floor px))
        fz (long (math/floor pz))
        feet (long (math/floor py))
        cx (long (math/floor (+ px (* 0.8 (math/signum (double (- gx px)))))))
        cz (long (math/floor (+ pz (* 0.8 (math/signum (double (- gz pz)))))))
        ahead (remove #{[fx fz]} (distinct [[cx fz] [fx cz] [cx cz]]))
        steps (filter (fn [[x z]] (step? world [x feet z])) ahead)
        spots (concat (for [[x z] ahead y [(inc feet) feet]] [x y z])
                      (for [[x z] steps] [x (+ feet 2) z])
                      (when (seq steps) [[fx (+ feet 2) fz]]))]
    (first (filter #(some-> (terrain/block-at world %)
                            blocks/leaves?)
                   spots))))

(defn ledge-ahead?
  "Would a step toward goal take the body over a drop: the cell ledge-look ahead on the plane (not
   the feet's own) open, with no floor under it nor within path/max-fall below that (path/landing)? Gym batch
   iron-a9 run 3's collect walked after a drop at a ravine's edge at y -7 and the bot fell from
   a high place."
  [world goal]
  (let [[px _ pz] (:player/pos world)
        [gx _ gz] goal
        d (physics/horizontal-distance [px 0 pz] [gx 0 gz])
        [_ fy _ :as at] (feet world)
        cell (when (pos? d)
               [(long (math/floor (+ px (* ledge-look (/ (- gx px) d))))) fy
                (long (math/floor (+ pz (* ledge-look (/ (- gz pz) d)))))])]
    (boolean (and cell
                  (not= cell at)
                  (= :clear (path/classify world cell))
                  (not (path/floor? world (update cell 1 dec)))
                  (nil? (path/landing world cell))))))

(defmethod run :collect
  [world
   {:intent/keys [target since best-dist best-tick drops drops-before]
    :or {best-dist Double/MAX_VALUE drops blocks/log-items}} _] ; a collect made before :intent/drops collected a log
  (let [now (:time/now world)
        tick (:time/tick world)
        since (or since now)
        goal (or (:entity/pos (nearest-item world (physics/centre target))) (physics/centre target))
        d (physics/horizontal-distance (:player/pos world) goal)
        progressed? (< d (- best-dist 0.1))
        best-tick (if (or progressed? (nil? best-tick)) tick best-tick)
        drops-before (or drops-before (inventory/held world drops))
        world (assoc-intent world
                            :intent/since since
                            :intent/best-tick best-tick
                            :intent/drops-before drops-before
                            :intent/best-dist (if progressed? d best-dist))
        stalled? (and (:player/horizontal-collision? world)
                      (> (- tick best-tick) collect-stall-ticks))]
    (cond (> (inventory/held world drops) drops-before) ; one more of its drop than when it began
          (-> world
              (assoc :player/controls {})
              done)
          (and stalled? (blocker world goal))
          (-> world
              (assoc :player/controls {})
              (assoc-intent :intent/blocked-by (blocker world goal))
              done)
          (> (- now since) collect-timeout)
          (-> world
              (assoc :player/controls {})
              (fail :not-picked-up))
          (and (> d 0.4) (ledge-ahead? world goal))
          (-> world
              (assoc :player/controls {})
              (fail :ledge))
          (> d 0.4) (assoc world :player/controls (toward world goal 0.0))
          :else (assoc world :player/controls {}))))
