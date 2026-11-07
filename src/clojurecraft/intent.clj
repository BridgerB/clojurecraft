(ns clojurecraft.intent
  "Intents are values: {:intent/kind k :intent/status :active|:done|:failed ...}. The planner
   puts one in :plan/intent; `run` advances it by one tick, returning the world with the intent,
   :player/controls and effects updated. The set of kinds is open: a new kind is a defmethod in a
   new namespace. Every deadline is an absolute ms value compared against :time/now; the only
   randomness comes in on the tick event."
  (:require [clojure.math :as math]
            [clojure.spec.alpha :as s]
            [clojurecraft.avoided :as avoided]
            [clojurecraft.blocks :as blocks]
            [clojurecraft.dig :as dig]
            [clojurecraft.entity :as entity]
            [clojurecraft.game :as game]
            [clojurecraft.inventory :as inventory]
            [clojurecraft.packet :as packet]
            [clojurecraft.path :as path]
            [clojurecraft.physics :as physics]
            [clojurecraft.recipe :as recipe]
            [clojurecraft.terrain :as terrain]))

(set! *warn-on-reflection* true)

;; read by name (ns-resolve) in scratch_test and model_test, so the unused-var lint excludes it
(def scratch
  "The intent attributes only this namespace's executor reads and writes: {attribute {:doc meaning
   :when lifetime}}. They are not world information, so they are not in clojurecraft.model;
   scratch_test holds that no other namespace uses one."
  {:intent/face {:doc "block face it acts on" :when "dig"}
   :intent/finish-at {:doc "ms after which FINISH may be sent" :when "dig"}
   :intent/slot {:doc "hotbar slot the dig holds its tool in (0 for the hand)"
                 :when "dig, from START"}
   :intent/tool {:doc "item id of the tool the dig holds, nil for the hand" :when "dig, from START"}
   :intent/dig-ms {:doc "ms the server needs to break the target with that tool"
                   :when "dig, from START"}
   :intent/state
   {:doc
    "the target's state id at START; the cell still in it when the ack of FINISH comes means the server did not break it"
    :when "dig, from START"}
   :intent/confirm-left {:doc "ticks left to wait for the server's word after FINISH"
                         :when "dig, :confirm stage"}
   :intent/at {:doc "index of the next waypoint" :when "walk, once planned"}
   :intent/replans {:doc "routes planned for this walk; more than intent/max-replans fails :no-path"
                    :when "walk, once planned"}
   :intent/planned-chunks
   {:doc
    "chunk columns loaded when the route was made (kept for the record; a new chunk no longer replans by itself)"
    :when "walk, once planned"}
   :intent/drops-before {:doc "items of :intent/drops held when collecting began" :when "collect"}})

(s/def :intent/face int?)
(s/def :intent/finish-at number?)
(s/def :intent/slot (s/int-in 0 9))
(s/def :intent/tool (s/nilable int?))
(s/def :intent/dig-ms nat-int?)
(s/def :intent/state int?)
(s/def :intent/confirm-left nat-int?)
(s/def :intent/at nat-int?)
(s/def :intent/replans nat-int?)
(s/def :intent/planned-chunks nat-int?)
(s/def :intent/drops-before nat-int?)

(def reach 4.0)                       ; eye → block centre; the server allows ~4.5
(def walk-timeout-ticks 1200)         ; 60 s
(def waypoint-ticks 10)               ; and half a second more for each waypoint of the route walked
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
(def settle-deadline 2000)            ; not still by then, START anyway: the server does not ask for stillness
(def dig-ms 3000)                     ; a log by hand: hardness 2 → 60 ticks (dig/ms derives it now)
(def finish-margin 1.15)              ; FINISH is sent this late: the server takes it at 70% of its own ticks, aborts it before
(def finish-slack 100)                ; ms added to the margin
(def confirm-ticks 60)                ; after FINISH, ticks to wait for the server's block update: then the ack alone is a refusal, and silence trusts the overlay

(defn penalties
  "The dig penalties in force for the player: off the ground, the eyes in water; each divides
   the dig speed by five, so an escape dig under water is slow as vanilla makes it."
  [world]
  {:off-ground? (not (:player/on-ground? world))
   :eyes-in-water? (boolean (:player/eye-in-water? world))})

(def never-dug #{:obsidian})          ; a portal frame is never a dig target (ruststeve dug its own frame)

(def falling-reach 16)                ; gravel or sand stacked over a cell that a fluid on top is looked for through

(defn fluid-over-falling?
  "Is there water or lava over or beside gravel or sand stacked straight over pos: opening pos lets the stack fall and the fluid follow it down."
  [world [x y z]]
  (let [over (map (fn [dy] [x (+ y dy) z]) (range 1 (inc falling-reach)))
        stack (take-while #(some-> (terrain/block-at world %)
                                   blocks/falls?)
                          over)]
    (boolean (some (fn [cell]
                     (some #(terrain/fluid-at world (mapv + cell %)) (vals physics/face-offsets)))
                   stack))))

(defn bed-over-fluid?
  "Is a horizontal neighbour of pos gravel or sand resting, through any stack of such under it, on water or lava? Breaking pos updates its neighbours (vanilla's neighbour update), so that bed drops into the fluid, and what lies over and beside it follows into the cut."
  [world [x y z]]
  (boolean (some (fn [[dx dz]]
                   (let [[nx _ nz :as n] [(+ x dx) y (+ z dz)]]
                     (when (some-> (terrain/block-at world n)
                                   blocks/falls?)
                       (let [under (->> (range 1 (inc falling-reach))
                                        (map (fn [d] [nx (- y d) nz]))
                                        (drop-while #(some-> (terrain/block-at world %)
                                                             blocks/falls?))
                                        first)]
                         (and under (terrain/fluid-at world under))))))
                 [[1 0] [-1 0] [0 1] [0 -1]])))

(defn exposes-fluid?
  "Would breaking target let water or lava in: is any of its six neighbours a fluid (steve's
   digExposesWater), does a fluid rest on gravel or sand over it (fluid-over-falling?), or does
   gravel or sand beside it rest on a fluid (bed-over-fluid?)?"
  [world [x y z :as target]]
  (boolean (or (some (fn [[dx dy dz]] (terrain/fluid-at world [(+ x dx) (+ y dy) (+ z dz)]))
                     [[1 0 0] [-1 0 0] [0 1 0] [0 -1 0] [0 0 1] [0 0 -1]])
               (fluid-over-falling? world target)
               (bed-over-fluid? world target))))

(defn dig-time
  "ms to break block id with held (an item id, or nil for the hand) under the world's penalties:
   dig/ms, with 3000 for an unknown block so a stale sighting is still dug at a log's pace."
  ([id] (dig-time id nil {}))
  ([id held pen] (or (dig/ms id held pen) dig-ms)))

(defn slowdown
  "How much longer than at 20 ticks a second the server's ticks take now (:server/tick-ms over
   game/server-tick-ms, at least 1): what it counts in ticks, a dig's progress, takes that much
   longer by the clock."
  [world]
  (max 1.0 (/ (:server/tick-ms world game/server-tick-ms) game/server-tick-ms)))

(defn finish-delay
  "ms from START to FINISH when a dig takes ms (at the server's slowdown s, 1 by default): with
   margin, since an early FINISH aborts the break and a late one is accepted."
  ([ms] (finish-delay ms 1.0))
  ([ms s] (+ (* ms finish-margin s) finish-slack)))
(def swing-every 350)                 ; ms between arm swings while digging
(def collect-timeout 10000)           ; ms a collect may take before it fails
(def leaf-drop 0.7)                   ; blocks a drop may lie under the feet and still be in the pickup box (grown 0.5 down, the drop 0.25 tall)
(def centre-tolerance 0.05)           ; horizontal distance from a drop below the feet at which a collect stops walking: over its cell, to fall in
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

(defn why-of
  "The planner's branch that chose intent i (why), or nil: a label on the intent's metadata, so an
   intent compares equal with or without one."
  [i]
  (:intent/why (meta i)))

(defn why
  "Intent i labelled with the planner's branch that chose it (label: a keyword, its function and
   its test, e.g. :hunt/and-near?-hurt?-not-in?), unless i is already labelled (an inner planner
   named its own branch) or is no intent (nil, a {:plan/wait ...}). The label rides on the map's
   metadata: it is about the choice, not the intent, so it never changes what an intent equals, and
   it follows the intent through every assoc. A reader of a run sees it as data where it is read:
   the intention facts (:intention/why), the senses line, the ledger and the moment (the
   observability plan, part B)."
  [label i]
  (cond (and (map? i) (:intent/kind i) (nil? (why-of i))) (vary-meta i assoc :intent/why label)
        :else i))

(defn default-why
  "The label an intent gets when its planner named no branch (why): :<goal>/<kind> for an intent
   chosen for a goal row (:intent/goal), :planner/<kind> for one chosen outside any (the leave-area
   and regroup starts), :planner/unknown for a map with no kind. It names the row and the kind, not
   the cond branch: a planner that tags its own branches (why) keeps the finer label."
  [i]
  (let [kind (some-> (:intent/kind i)
                     name)
        goal (some-> (:intent/goal i)
                     name)]
    (keyword (or goal "planner") (or kind "unknown"))))

(defn why-or-default
  "Intent i with a why: its own label if a planner tagged it (why), else default-why. Nil and a
   non-intent (a {:plan/wait ...}) pass through. Applied in one place, plan/start-intent, so every
   intent that becomes the running one carries a why whatever planner chose it."
  [i]
  (cond (and (map? i) (:intent/kind i) (nil? (why-of i))) (why (default-why i) i)
        :else i))

(defn child-why
  "The label of a child intent started by an executor under parent: the parent's own why when it
   has one, else :child/<parent kind>. One rule, applied in run-child, so a :walk or :dig inside a
   :tunnel-to, :pocket, :stairs-down or :mine is labelled by what it serves."
  [parent]
  (or (why-of parent) (keyword "child" (name (:intent/kind parent :unknown)))))

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
        labelled (cond->> (:intent/child parent)
                   (map? (:intent/child parent)) (why (child-why parent)))
        world (run (assoc world :plan/intent labelled) labelled event)
        child (:plan/intent world)]
    (assoc world :plan/intent (assoc parent :intent/child child))))

;; ---------------------------------------------------------------- walk

(def standing-walks
  "The purposes of a walk that ends standing in its target cell, not near it, because a cell next to it does not do: a build's stand (:build), the pearl hunt's burrow (:shelter), a hiding place out of a blaze's sight (:cover), a fight's stand round a wall (:fight), a retreat's new ground (:retreat), the surface over stone no route reaches (:over-stone), the neighbour cell that stands from feet on a rim over a gap (:footing), a drop in a pit under the floor (:rod, only the feet in its cell are in the pickup box), the cell out of a fire (:fire-out) and the blaze nook (:nook, which counts only the exact cell)."
  #{:build :shelter :cover :fight :retreat :over-stone :footing :rod :fire-out :nook})

(defn walk-goal
  "Where a walk's route may end: for a block mined from beside (:intent/pickup?), any cell within pickup-range of it whose feet are where the drop lands in reach (path's :pickup goal: the block at or up to pickup-rise above them), and with :intent/step-down? a straight neighbour a block up too; for water to scoop, its bank (path's :pickup goal at the cell over the source, rise 1); for a purpose in standing-walks, that cell itself; else anywhere within goal-range."
  [{:intent/keys [target for pickup? step-down?]}]
  (cond pickup?
        (cond->
          {:goal/kind :pickup :goal/pos target :goal/range pickup-range :goal/rise pickup-rise}
          step-down? (assoc :goal/step-down? true))
        (= :scoop for)
        {:goal/kind :pickup :goal/pos (update target 1 inc) :goal/range scoop-range :goal/rise 1}
        (contains? standing-walks for) {:goal/kind :block :goal/pos target}
        :else {:goal/kind :near :goal/pos target :goal/range goal-range}))

(def centred-within 0.25)             ; how far from a cell's middle the feet may stand and count as on it

(defn centred?
  "Are the feet in cell, within centred-within of its middle on the plane?"
  [world cell]
  (and (= cell (feet world))
       (<= (physics/horizontal-distance (:player/pos world) (physics/centre cell)) centred-within)))

(defn goal-of
  "The path goal a walk is planned toward. A walk out (:intent/for :opening) has no cell it must
   stand in: :surface climbs the air already dug to ground the bot can leave."
  [i]
  (if (= :opening (:intent/for i)) {:goal/kind :surface} (walk-goal i)))

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
   stand (#8: a pour is aimed from it), the hunt's burrow (:shelter) or a retreat's new ground,
   the feet in that cell near its middle (centred?). For a
   walk out (:intent/for :opening), the surface goal itself: sky over the feet alone may still
   be a shaft or a pit, and within reach of the mouth the bot is still in the shaft."
  [world {:intent/keys [target for pickup?] :as i} d]
  (if (= :opening for)
    (path/goal-done? world (goal-of i) (feet world))
    (let [rise (- (physics/y-of target) (long (math/floor (physics/y-of (:player/pos world)))))]
      (and (<= d reach)
           (cond pickup? (path/goal-done? world (walk-goal i) (feet world))
                 (= :scoop for)
                 (and (<= -1 rise 0) (<= (path/octile (feet world) target) scoop-range))
                 (= :log for) (<= rise pickup-rise)
                 (contains? standing-walks for) (centred? world target)
                 :else true)))))

(defn routable?
  "Is there a way to walk intent i's goal from the feet: the planner's route is :found, or :partial toward a target in a chunk not loaded yet (the walk plans again as chunks arrive). Toward a target in a chunk not loaded, a :none route with waypoints is progress too: the search ran out of loaded terrain, not of ways. Not :none toward a loaded target, nor :partial toward a target in loaded terrain, the budget spent on it. Uses :intent/max-nodes when given, as route! does. An act asks before it chooses a target, so one with no way to it is passed over, not walked at."
  [world {:intent/keys [target max-nodes] :as i}]
  (let [{:path/keys [status waypoints]} (path/plan world
                                                   (path/feet-cell (:player/pos world))
                                                   (goal-of i)
                                                   (cond-> {}
                                                     max-nodes (assoc :path/max-nodes max-nodes)))]
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
   the feet and near (jumping from afar bounces in open air), or when blocked. Stop horizontal
   input once the body is wholly over a descending waypoint, so momentum does not carry the
   feet beyond its floor and stopping on the lip cannot prevent the fall."
  [world [wx wy wz]]
  (let [[px py pz] (:player/pos world)
        centre [(+ wx 0.5) (+ wy 0.5) (+ wz 0.5)]
        near? (< (physics/horizontal-distance [px 0 pz] centre) jump-near)
        up? (> wy (+ (math/floor py) 0.5))]
    (cond (and (> py wy)
               (<= (physics/horizontal-distance [px 0 pz] [(+ wx 0.5) 0 (+ wz 0.5)])
                   (- 0.5 physics/half-width)))
          {}
          :else (update (toward world centre 0.0) :control/jump? #(or % (and up? near?))))))

(defn opening
  "A walk out to the surface, or nil. The way already dug is open air: :surface climbs it before filling a mine and the walk follows that to the mouth. nil when the feet are already out, and nil when no way out is reached. The mouth must be out of the ground (stairs/out?), not merely under the sky, and never a mouth in :plan/blacklist, where a walk to it that failed :no-path left it."
  [world]
  (let [feet (feet world)
        goal {:goal/kind :surface}]
    (when-not (path/goal-done? world goal feet)
      (let [r (path/plan world feet goal)
            mouth (last (:path/waypoints r))]
        (when (and (= :found (:path/status r))
                   (seq (:path/waypoints r))
                   (not (contains? (avoided/all-of world :blacklist) mouth)))
          {:intent/kind :walk :intent/target mouth :intent/for :opening})))))

(defn route!
  "The intent with a fresh route from the feet toward its walk-goal, counting the plan and
   noting how many chunks were loaded when it was made. Uses :intent/max-nodes when given;
   otherwise the pathfinder's default budget."
  [world {:intent/keys [replans max-nodes] :or {replans 0} :as i}]
  (let [r (path/plan world
                     (path/feet-cell (:player/pos world))
                     (goal-of i)
                     (cond-> {} max-nodes (assoc :path/max-nodes max-nodes)))]
    (assoc-intent world
                  :intent/waypoints (:path/waypoints r)
                  :intent/route (:path/status r)
                  :intent/at 0
                  :intent/replans (inc replans)
                  :intent/planned-chunks (:stats/chunks world 0)
                  :intent/best-tick (:time/tick world))))

(defn route-stale?
  "Should the walk plan again: a waypoint still ahead can no longer be stood on (a chunk that arrived or a block that changed broke the route), no waypoint was reached for stuck-ticks, or the route ended short of the target (:partial or :none) and every waypoint is behind. A chunk arriving is not by itself a reason."
  [world {:intent/keys [waypoints at best-tick route]}]
  (let [ahead (drop at waypoints)]
    (or (some #(not (path/standable? world %)) ahead)
        (> (- (:time/tick world) best-tick) stuck-ticks)
        (and (empty? ahead) (not= :found route)))))

(defn walk-deadline
  "The ticks a walk may take: walk-timeout-ticks, and waypoint-ticks more for each waypoint of the route it walks, so a long route is not cut short of its end."
  [{:intent/keys [waypoints]}]
  (+ walk-timeout-ticks (* waypoint-ticks (count waypoints))))

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
          (> (- tick started) (walk-deadline i))
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
  "[slot item-id] of the tool to dig block id with when it is only in the main inventory, nil when the hotbar holds one or none is held. A dig chooses from the hotbar, and a crafted pickaxe lands in the main inventory when the hotbar is full."
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

(defn breaker
  "[slot item-id] of the fastest tool of block id's own kind among slots ({slot item}), whether it
   harvests the block or not (dig/ms: a tier too low breaks it at a hundredth of its speed, for
   nothing); nil when none is of its kind. What breaks a block whose drop is not wanted
   (:intent/drop-ok?) when no held tool harvests it."
  [id slots penalties]
  (let [kind (blocks/tool-of id)]
    (when-let [[_ slot item] (first (sort (for [[slot {:keys [item]}] slots
                                                :when (and kind (= kind (:kind (blocks/tool item))))
                                                :let [m (dig/ms id item penalties)]
                                                :when m]
                                            [m slot item])))]
      [slot item])))

(defn breaking-tool
  "[slot item-id] a dig of block id holds (choose-tool), or for a dig whose drop is not wanted (drop-ok?) of a block no held tool harvests, the fastest of its kind in the hotbar (breaker), else the hand, [0 nil]: the cell is what such a dig is for. :needs-tool otherwise, as choose-tool."
  [world id drop-ok?]
  (let [t (choose-tool world id)]
    (if (and drop-ok? (= :needs-tool t))
      (or (breaker id (hotbar world) (penalties world)) [0 nil])
      t)))

(defn stowed-breaker
  "[slot item-id] of the tool a dig of block id whose drop is not wanted should first put in hand from the main inventory: stowed-tool's, or when no held tool harvests the block and the hotbar has none of its kind, the fastest of its kind (breaker) stowed there; nil when the hotbar holds it already or there is none."
  [world id]
  (or (stowed-tool world id)
      (when (and (= :needs-tool (choose-tool world id))
                 (nil? (breaker id (hotbar world) (penalties world))))
        (when-let [[slot _ :as t] (breaker id (:player/inventory world) (penalties world))]
          (when-not (inventory/hotbar? slot) t)))))

(defn open?
  "Is block state id nothing a dig breaks, the cell already open: air, water or lava (path/classify-id)? Anything else is broken, solid or not: an awkward block (a wall, a slab, pointed dripstone) is in a route's way and a clear digs it (build/diggable?), a plant stops a bucket's ray (build/sight-block)."
  [id]
  (or (blocks/air? id) (contains? #{:water :lava} (path/classify-id id))))

(defmulti dig-stage
  "Advance a :dig intent one tick in its :intent/stage (:settle :digging :confirm), the stage and
   its start already on the intent."
  {:arglists '([world intent])}
  (fn [_world i] (:intent/stage i)))

(defmethod dig-stage :settle
  [world {:intent/keys [target since still exposes-ok? drop-ok?] :or {still 0}}]
  (let [now (:time/now world)
        eye (game/eye world)
        at-target (terrain/block-at world target)]
    (cond (and at-target (open? at-target)) (fail world :target-gone)
          (> (physics/distance eye (physics/centre target)) (+ reach 0.5))
          (fail world :out-of-reach)
          (= :needs-tool (breaking-tool world at-target drop-ok?)) (fail world :needs-tool)
          (or (contains? never-dug (blocks/name-of at-target))
              (and (not exposes-ok?) (exposes-fluid? world target)))
          (fail world :unsafe)
          (or (and (>= still 3) (> (- now since) settle-ms)) (> (- now since) settle-deadline))
          (let [seq (inc (:bot/sequence world))
                face (face-toward eye target)
                [slot tool] (breaking-tool world at-target drop-ok?)
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
                              :intent/finish-at (+ now (finish-delay ms (slowdown world))))
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

(defn finish-due
  "The ms at which a dig may be finished now: the earlier of its :intent/finish-at, set at the start from the penalties then, and what the penalties in force now allow (the block's state and the tool it began with, dig-time and finish-delay from its start). The server weighs a FINISH by the block's destroy progress at the moment it arrives (70% needed), so a dig begun with the eyes under water and finished at the surface needs only the surface's time."
  [world {:intent/keys [state tool started finish-at]}]
  (min finish-at
       (+ started (finish-delay (dig-time state tool (penalties world)) (slowdown world)))))

(defmethod dig-stage :digging
  [world {:intent/keys [target face slot tool next-swing] :as i}]
  (let [now (:time/now world)]
    (cond (and tool (not= tool (get-in world [:player/inventory slot :item])))
          (fail world :tool-broke) ; the slot no longer holds it: the next dig chooses again
          (>= now (finish-due world i))
          (let [seq (inc (:bot/sequence world))]
            (-> world
                (assoc :bot/sequence seq)
                (game/emit {:packet/name :player-action
                            :status (packet/value :dig :stop-destroy)
                            :pos target
                            :face face
                            :sequence seq})
                (assoc-intent :intent/stage :confirm
                              :intent/since now
                              :intent/sequence seq
                              :intent/confirm-left
                              (long (math/ceil (* confirm-ticks (slowdown world)))))))
          (>= now next-swing)
          (-> world
              (game/emit {:packet/name :swing :hand (packet/value :hand :main-hand)})
              (assoc-intent :intent/next-swing (+ now swing-every)))
          :else world)))

(defmethod dig-stage :confirm
  ;; the server breaks the block and sends its block update before the ack of FINISH (the vanilla
  ;; client's own prediction relies on it): the cell open is the break. A loaded server acks first
  ;; and breaks later, by its own clock, so an ack with the block still standing is waited on; the
  ;; block still standing when confirm-ticks run out after it is the server's word that it did not
  ;; break it (a FINISH it found early and dropped, or a refusal it answered with the block put back)
  [world {:intent/keys [target state sequence confirm-left]}]
  (let [at-target (terrain/block-at world target)
        acked (>= (:stats/last-ack world -1) sequence)]
    (cond (and at-target (not= at-target state)) (done world) ; the server's block update: open, or what fell into it
          (and (zero? confirm-left) acked) (fail world :rejected)
          ;; no word either way in confirm-ticks: trust the overlay
          (zero? confirm-left) (done (terrain/predict world target blocks/air)) ; a guess, unconfirmed until the server speaks
          :else (assoc-intent world :intent/confirm-left (dec confirm-left)))))

(defmethod run :dig
  ;; before the dig itself, a child :equip puts the tool in hand when the best one is only in
  ;; the main inventory (stowed-breaker): the dig chooses from the hotbar only (choose-tool),
  ;; so a stone pickaxe stowed there left a nether-brick dig at 10000 ms by hand against
  ;; 750 ms held (brain: the-nine-second-dig-is-a-hand-dig-on-nether-brick). Executors that
  ;; start digs through build/tool-first or stairs/dig-next already equip; a bare :dig did not.
  [world {:intent/keys [stage since child target]} event]
  (cond (and child (= :equip (:intent/kind child)) (done? child))
        (let [world (dissoc-intent world :intent/child)]
          (run (assoc-intent world :intent/stage :settle :intent/since (:time/now world))
               (:plan/intent world)
               event))
        (and child (failed? child)) (fail world (:intent/reason child))
        child (run-child world event)
        :else
        (let [[_ item :as stowed] (some->> (terrain/block-at world target)
                                           (stowed-breaker world))]
          (if stowed
            (run-child (assoc-intent world
                                     :intent/child
                                     {:intent/kind :equip
                                      :intent/item (recipe/item-name item)
                                      :intent/status :active})
                       event)
            (let [world (assoc-intent world
                                      :intent/stage (or stage :settle)
                                      :intent/since (or since (:time/now world)))]
              (dig-stage world (:plan/intent world)))))))

;; ---------------------------------------------------------------- collect

(defn nearest-item
  "The tracked item entity nearest the point near, within 6 blocks, or nil. Only items: every entity is tracked, and a pig beside the drop is not the drop."
  [world near]
  (->> (:world/entities world)
       vals
       (filter entity/item?)
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

(defn leaf-under
  "The leaf block the player stands on, when goal, a point, lies in its column more than leaf-drop below the feet: a drop dug out of a trunk from the top of the canopy rests under the leaves the bot stands on, out of the pickup box and in no wall ahead (blocker). nil otherwise. A bot on a leaf beside the hole, its box over the edge, is not this: collect centres over a drop below the feet (centre-tolerance) to fall in."
  [world goal]
  (let [[px py pz] (:player/pos world)
        [gx gy gz] goal
        cell [(long (math/floor px)) (dec (long (math/floor py))) (long (math/floor pz))]]
    (when (and (< (physics/horizontal-distance [px 0 pz] [gx 0 gz]) 1.0)
               (< leaf-drop (- py gy))
               (some-> (terrain/block-at world cell)
                       blocks/leaves?))
      cell)))

(defn unsupported?
  "Would a body standing at feet level fy with its feet centre at [x _ z] have nothing under its
   box: every cell under the cells the box overlaps at that level clear (path/classify :clear: air,
   a plant)? A floor, a slab, a fluid or an unloaded cell is something to stand on or in."
  [world [x _ z] fy]
  (let [half physics/half-width
        cells (for [cx (distinct [(long (math/floor (- x half))) (long (math/floor (+ x half)))])
                    cz (distinct [(long (math/floor (- z half))) (long (math/floor (+ z half)))])]
                [cx (dec fy) cz])]
    (every? #(= :clear (path/classify world %)) cells)))

(defn off-the-rim?
  "Would moving the body [dx dz] blocks on the plane leave it on the ground with nothing under its
   box (unsupported?) and no landing within path/max-fall below the cell its feet centre is then
   in?"
  [world [dx dz]]
  (let [[px py pz] (:player/pos world)
        [_ fy _] (feet world)
        edge [(+ px dx) py (+ pz dz)]
        cell [(long (math/floor (first edge))) fy (long (math/floor (nth edge 2)))]]
    (boolean (and (:player/on-ground? world)
                  (= :clear (path/classify world cell))
                  (unsupported? world edge fy)
                  (nil? (path/landing world cell))))))

(defn straddling-ahead?
  "Would a half-width step toward unit direction [ux uz], or along either axis of it a block in the
   way would slide the body (a wall the diagonal runs into), leave the body off the rim it
   stands on (off-the-rim?): the bot stands on a floor whose own cell has none (its box overlaps
   the cell beside it), and the first step toward the drop takes it off the edge. The cell
   ahead is the feet's own, which ledge-ahead? skips."
  [world [ux uz]]
  (let [w physics/half-width]
    (boolean (some #(off-the-rim? world %)
                   (cond-> [[(* w ux) (* w uz)]]
                     (> (abs ux) 0.1) (conj [(* w (math/signum ux)) 0.0])
                     (> (abs uz) 0.1) (conj [0.0 (* w (math/signum uz))]))))))

(defn ledge-ahead?
  "Would a step toward goal take the body over a drop: the cell ledge-look ahead on the plane (not the feet's own) open, with no floor under it nor within path/max-fall below that (path/landing), or the body on the rim of a floor whose own cell has none, about to step off it (straddling-ahead?)?"
  [world goal]
  (let [[px _ pz] (:player/pos world)
        [gx _ gz] goal
        d (physics/horizontal-distance [px 0 pz] [gx 0 gz])
        [_ fy _ :as at] (feet world)
        unit (when (pos? d) [(/ (- gx px) d) (/ (- gz pz) d)])
        cell (when unit
               [(long (math/floor (+ px (* ledge-look (first unit))))) fy
                (long (math/floor (+ pz (* ledge-look (second unit)))))])]
    (boolean (and cell
                  (or (and (not= cell at)
                           (= :clear (path/classify world cell))
                           (not (path/floor? world (update cell 1 dec)))
                           (nil? (path/landing world cell)))
                      (straddling-ahead? world unit))))))

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
          (and (> (- tick best-tick) collect-stall-ticks) (leaf-under world goal))
          (-> world
              (assoc :player/controls {})
              (assoc-intent :intent/blocked-by (leaf-under world goal))
              done)
          (> (- now since) collect-timeout)
          (-> world
              (assoc :player/controls {})
              (fail :not-picked-up))
          (and (> d 0.4) (ledge-ahead? world goal))
          (-> world
              (assoc :player/controls {})
              (game/say (str "collect: a ledge ahead, the drop at " (mapv long goal) " is left"))
              done)
          (> d
             (if (< leaf-drop (- (physics/y-of (:player/pos world)) (nth goal 1)))
               centre-tolerance
               0.4))
          (assoc world :player/controls (toward world goal 0.0))
          :else (assoc world :player/controls {}))))
