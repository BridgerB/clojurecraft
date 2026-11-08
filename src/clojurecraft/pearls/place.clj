(ns clojurecraft.pearls.place
  "The burrow and the shelter geometry: where an enderman cannot reach the bot, the sites for one, and the intents that cut it, provoke from it and wait in it."
  (:require [clojure.spec.alpha :as s]
            [clojurecraft.avoided :as avoided]
            [clojurecraft.blocks :as blocks]
            [clojurecraft.combat :as combat]
            [clojurecraft.dimension :as dimension]
            [clojurecraft.entity :as entity]
            [clojurecraft.game :as game]
            [clojurecraft.intent :as intent]
            [clojurecraft.memory :as memory]
            [clojurecraft.mob :as mob]
            [clojurecraft.path :as path]
            [clojurecraft.pearls.params :as pparams]
            [clojurecraft.pearls.sense :as psense]
            [clojurecraft.physics :as physics]
            [clojurecraft.shapes]
            [clojurecraft.stairs :as stairs]
            [clojurecraft.stone :as stone]
            [clojurecraft.terrain :as terrain]))

(set! *warn-on-reflection* true)

;; read by name (ns-resolve) in scratch_test and model_test, so the unused-var lint excludes it
(def scratch
  "The intent attributes only this namespace's executor reads and writes: {attribute {:doc meaning
   :when lifetime}}. They are not world information, so they are not in clojurecraft.model;
   scratch_test holds that no other namespace uses one."
  {:intent/mouth {:doc "where a :burrow began: the stairs go down from it"
                  :when ":burrow, from its first tick"}})

(s/def :intent/mouth :clojurecraft.spec/block-pos)

(defmethod intent/run :provoke
  ;; {:intent/kind :provoke :intent/target eid}: a stare at its head until the server says it has
  ;; a target (entity/creepy?), or it comes nearer than stare-from on its own: done, and the plan
  ;; takes the bot to its burrow to meet it; stare-ms with neither, failed :unprovoked (the
  ;; planner blacklists it). Done at once when it is gone. The look leaves its head the moment it
  ;; is provoked: stared at within 16 an angry enderman stands frozen, within 4 it teleports away
  [world i _]
  (let [now (:time/now world)
        i (cond-> i (nil? (:intent/since i)) (assoc :intent/since now))
        world (assoc world :plan/intent i)
        e (get-in world [:world/entities (:intent/target i)])]
    (cond (entity/gone? world (:intent/target i))
          (-> world
              (assoc :player/controls {})
              intent/done)
          (or (entity/creepy? e)
              (< (physics/distance (game/eye world) (psense/head e)) pparams/stare-from))
          (-> world
              (assoc :player/controls (psense/look-at world (combat/aim e)))
              intent/done)
          (> (- now (:intent/since i)) pparams/stare-ms)
          (-> world
              (assoc :player/controls {})
              (intent/fail :unprovoked))
          :else (assoc world :player/controls (psense/look-at world (psense/head e))))))

;; ---------------------------------------------------------------- the burrow
(defn solid-or-unloaded?
  "Is cell c solid, or not loaded (what the bot does not know is not a place to stand in)?"
  [world c]
  (let [id (terrain/block-at world c)] (or (nil? id) (blocks/solid? id))))

(defn stand?
  "Could an enderman stand with its feet in cell [x y z]: a solid floor under it and the three
   cells it fills (mob/tall: 2.9 high) open?"
  [world [x y z]]
  (and (solid-or-unloaded? world [x (dec y) z])
       (not-any? #(solid-or-unloaded? world [x (+ y %) z]) (range (mob/tall :enderman)))))

(defn shelter?
  "Can the bot standing with its feet in cell [x y z] not be hit by an enderman: its feet and head
   cells open, and no cell an enderman could stand in (stand?) in the nine columns round it at the
   heights whose body would overlap the bot's (feet two under to one over). A mob's melee reaches
   its own box grown 0.83 sideways and not at all up or down (Mob.getAttackBoundingBox), so an
   enderman two cells off on the plane, or wholly above or below, cannot reach; one cell off it
   always can."
  [world [x y z]]
  (and (not (solid-or-unloaded? world [x y z]))
       (not (solid-or-unloaded? world [x (inc y) z]))
       (not-any? #(stand? world %)
                 (for [dx [-1 0 1] dz [-1 0 1] dy [-2 -1 0 1]] [(+ x dx) (+ y dy) (+ z dz)]))))

(defn perched?
  "Do the feet stand on a lone column two or more blocks high, open air on all four sides of the
   feet's cell and of the two cells under it: a pillar the bot raised (combat/cover against a
   teleporter) and never came down? From one no walk has a route and no level tunnel a floor."
  [world]
  (let [[x y z] (intent/feet world)
        open? (fn [c] (= :clear (path/classify world c)))
        sides (fn [[cx cy cz]]
                (for [[dx dz] [[1 0] [-1 0] [0 1] [0 -1]]] [(+ cx dx) cy (+ cz dz)]))]
    (boolean (and (= :solid (path/classify world [x (dec y) z]))
                  (= :solid (path/classify world [x (- y 2) z]))
                  (every? open? (mapcat sides [[x y z] [x (dec y) z] [x (- y 2) z]]))))))

(defn come-down
  "The dig of the block under the feet while perched?, else nil: the bot drops a block a dig
   (no fall damage) until it stands on ground again."
  [world]
  (when (perched? world)
    (let [[x y z] (intent/feet world)]
      {:intent/kind :dig :intent/target [x (dec y) z] :intent/drop-ok? true :intent/for :pearls})))

(defn shelter
  "The hunt's burrow (:pearls/shelter) while it is within shelter-reach of the feet, still a
   shelter? by the terrain and not given up after a minute with nothing to do from it (stale?),
   else nil."
  [world]
  (let [s (:pearls/shelter world)]
    (when (and s
               (<= (physics/distance (intent/feet world) s) pparams/shelter-reach)
               (shelter? world s)
               ;; with an angry one within hold-radius the bot waits inside
               (or (not (psense/stale? world)) (psense/angry-within? world pparams/hold-radius)))
      s)))

(defn away
  "The heading [dx dz] of the four straight ones nearest to the way from entity e to the feet: a
   burrow cut that way opens its stairs toward e, which comes down them to the tunnel's mouth."
  [world e]
  (let [[px _ pz] (:player/pos world)
        [ex _ ez] (:entity/pos e)
        dx (- px ex)
        dz (- pz ez)]
    (if (>= (abs dx) (abs dz)) [(if (neg? dx) -1 1) 0] [0 (if (neg? dz) -1 1)])))

(defn level?
  "Does enderman e stand on the bot's level: less than descend-drop above or below the feet?"
  [world e]
  (< (abs (- (physics/y-of (combat/cell (:entity/pos e)))
             (physics/y-of (path/feet-cell (:player/pos world)))))
     pparams/descend-drop))

(defn within-cut-reach?
  "Does enderman e stand within cut-reach of the feet on the plane (path/octile), near enough
   for stairs or a tunnel to be cut toward it?"
  [world e]
  (<= (path/octile (path/feet-cell (:player/pos world)) (combat/cell (:entity/pos e)))
      pparams/cut-reach))

(defn lava-floor
  "The height in the Nether under which no stair, tunnel or burrow is cut: the lava sea's top and
   lava-margin over it (a cell of lava or a dug cell beside it floods the cut). 0 elsewhere."
  [world]
  (if (= dimension/nether (dimension/of world)) (+ pparams/lava-sea-top pparams/lava-margin) 0))

(defn burrow-steps
  "The cells a burrow cut from feet along dir opens, a step at a time: burrow-depth stairs an
   enderman walks down (stairs/tall-stair-cells for mob/tall, four high: the feet a block down
   and on at each), then burrow-length level steps (stairs/level-cells, two high); the last cell
   of the last step is where the bot ends."
  [feet dir]
  (let [n (mob/tall :enderman)
        stairs (reductions (fn [cells _] (stairs/tall-stair-cells (last cells) dir n))
                           (stairs/tall-stair-cells feet dir n)
                           (range (dec pparams/burrow-depth)))
        tunnel (reductions (fn [cells _] (stairs/level-cells (last cells) dir))
                           (stairs/level-cells (last (last stairs)) dir)
                           (range (dec pparams/burrow-length)))]
    (vec (concat stairs tunnel))))

(defn mouth-cells
  "The cells over a burrow's mouth [x y z] (the feet it is cut from) that an enderman standing
   in it fills and the bot does not: from two over the feet to one under mob/tall over them,
   one cell, [x y+2 z], for its 2.9. The bot stands in two open cells and an enderman needs
   three, so a burrow whose mouth it cannot stand in (stand?) it never enters, however its
   stairs are cut: :burrow digs these first."
  [[x y z]]
  (mapv (fn [dy] [x (+ y dy) z]) (range 2 (mob/tall :enderman))))

(defn burrowable?
  "Would a burrow cut from feet along dir end in a shelter?: the cells over its mouth an
   enderman needs (mouth-cells) may be opened (stairs/clear-way?: no water or lava), each of
   its steps (burrow-steps) may be cut (stairs/safe?: no water, lava or drop, not a cell a
   staircase was boxed on) once the steps before it are open, and its end, every step open, is
   a shelter?. The cells are opened on the world's block overlay (:world/blocks), as the digs
   would open them."
  [world feet dir]
  (let [roof (mouth-cells feet)
        steps (burrow-steps feet dir)
        open (fn [w cells] (reduce #(assoc-in %1 [:world/blocks %2] blocks/air) w cells))
        dug (when (and (>= (- (second feet) pparams/burrow-depth) (lava-floor world))
                       (stairs/clear-way? world roof))
              (reduce (fn [w cells] (if (stairs/safe? w cells) (open w cells) (reduced nil)))
                      (open world roof)
                      steps))]
    (boolean (and dug (shelter? dug (last (last steps)))))))

(defn standable?
  "Could the bot stand with its feet in cell [x y z]: the feet and head cells clear and a solid
   floor under them (path/classify)."
  [world [x y z]]
  (and (= :clear (path/classify world [x y z]))
       (= :clear (path/classify world [x (inc y) z]))
       (= :solid (path/classify world [x (dec y) z]))))

(defn burrow-site
  "The cell nearest the feet, within site-reach on the plane and a block up or down at most,
   not the feet, that the bot could stand in (standable?), cut a burrow along dir from
   (burrowable?), and walk to (intent/routable?), and that a walk has not failed toward
   (:plan/blacklist); nil when there is none. The route is asked last and one cell at a time
   (some, not a chunked filter): it is the costly test."
  [world dir]
  (let [[x y z :as feet] (intent/feet world)
        bl (avoided/all-of world :blacklist)]
    (->> (for [dx (range (- pparams/site-reach) (inc pparams/site-reach))
               dz (range (- pparams/site-reach) (inc pparams/site-reach))
               dy [0 1 -1]]
           [(+ x dx) (+ y dy) (+ z dz)])
         (remove #{feet})
         (sort-by #(physics/distance feet %))
         (some (fn [c]
                 (when (and (not (contains? bl c))
                            (standable? world c)
                            (burrowable? world c dir)
                            (intent/routable?
                             world
                             {:intent/kind :walk :intent/target c :intent/for :shelter}))
                   c))))))

; blocks from the burrow's end within which an enderman is provoked from outside the mouth: a provoked one is at the bot in about a second
(defn vantage
  "A walk to look from within the burrow's reach, with none of ms provokable from here: a cell
   on the plane between stare-from and vantage-reach from the nearest of the candidates nearest
   endermen in ms, within vantage-rise of its height and shelter-reach of the burrow
   (:pearls/shelter) and no farther from the burrow than from the enderman (provoked, it comes
   at the bot's pace and more; the bot must be home first), that the bot could stand in
   (standable?), from which the enderman is provokable?, past a walk's reach of the eye (nearer,
   the walk arrives where it starts) and not where a walk already arrived (memory/arrived); of
   those, nearest the feet first and vantage-tries at most, the first a route reaches
   (intent/routable?) with a route from it home; nil when none. Provoked from a vantage, the
   enderman follows the bot home, and the fight is the burrow's. Only within provoke-home-reach
   of the burrow's end."
  [world ms]
  (let [pos (:player/pos world)
        feet (intent/feet world)
        home (:pearls/shelter world)
        spent (memory/arrived world)
        eye (game/eye world)
        way-home (fn [c]
                   (intent/routable? (assoc world :player/pos (psense/feet-at c))
                                     {:intent/kind :walk
                                      :intent/target home
                                      :intent/for :shelter
                                      :intent/max-nodes pparams/vantage-nodes}))
        cells
        (fn [e]
          (let [[ex ey ez :as ec] (combat/cell (:entity/pos e))]
            (for [dx (range (- pparams/vantage-reach) (inc pparams/vantage-reach))
                  dz (range (- pparams/vantage-reach) (inc pparams/vantage-reach))
                  dy (range (- pparams/vantage-rise) (inc pparams/vantage-rise))
                  :let [c [(+ ex dx) (+ ey dy) (+ ez dz)]
                        on-plane (path/octile ec c)]
                  :when (and (<= pparams/stare-from on-plane pparams/vantage-reach)
                             (<= (physics/distance c home) pparams/provoke-home-reach)
                             (<= (physics/distance c home) (physics/distance c (:entity/pos e)))
                             (not (contains? spent c))
                             (> (physics/distance eye (physics/centre c)) intent/reach)
                             (standable? world c)
                             (psense/provokable? (assoc world :player/pos (psense/feet-at c)) e))]
              c)))]
    (->> ms
         (sort-by (fn [[_ e]] (physics/distance pos (:entity/pos e))))
         (take pparams/candidates)
         (some (fn [[_ e]]
                 (->> (cells e)
                      (sort-by #(physics/distance feet %))
                      (take pparams/vantage-tries)
                      (some (fn [c]
                              (let [i {:intent/kind :walk
                                       :intent/target c
                                       :intent/for :shelter
                                       :intent/max-nodes pparams/vantage-nodes}]
                                (when (and (intent/routable? world i) (way-home c))
                                  (dissoc i :intent/max-nodes)))))))))))

(defn crowder
  "[eid entity] of the nearest calm enderman (not entity/creepy?) crowding the burrow's mouth
   (crowd) that is not blacklisted (one that would not provoke is no reason to move), or nil."
  [world]
  (let [bl (avoided/all-of world :blacklist)]
    (entity/nearest (into {}
                          (remove (fn [[eid e]] (or (contains? bl eid) (entity/creepy? e))))
                          (psense/crowd world))
                    (:player/pos world))))

(defn stare-site
  "The cell to cut a burrow at for enderman e, from whose mouth the stare at it goes: within
   crowd-reach of it on the plane and vantage-rise of its height, its head stare-from and
   crowd-margin or more from the eye standing there and provokable? from it, a cell the bot could
   stand in (standable?) and cut a burrow from heading from it (burrowable?, along away), past a
   walk's reach of the eye, not one a walk failed toward (:plan/blacklist), and of those, nearest
   the feet first and vantage-tries at most, the first a route reaches (intent/routable?); nil
   when there is none. A walk to the enderman itself ends beside it, and a look while digging
   there stares at it point blank."
  [world e]
  (let [feet (intent/feet world)
        eye (game/eye world)
        bl (avoided/all-of world :blacklist)
        [ex ey ez] (combat/cell (:entity/pos e))
        from (fn [c] (assoc world :player/pos (psense/feet-at c)))]
    (->> (for [dx (range (- pparams/crowd-reach) (inc pparams/crowd-reach))
               dz (range (- pparams/crowd-reach) (inc pparams/crowd-reach))
               dy (range (- pparams/vantage-rise) (inc pparams/vantage-rise))]
           [(+ ex dx) (+ ey dy) (+ ez dz)])
         (filter (fn [c]
                   (and (not (contains? bl c))
                        (> (physics/distance eye (physics/centre c)) intent/reach)
                        (standable? world c))))
         (sort-by #(physics/distance feet %))
         (filter (fn [c]
                   (let [at (from c)]
                     (and (>= (physics/distance (game/eye at) (psense/head e))
                              (+ pparams/stare-from pparams/crowd-margin))
                          (psense/provokable? at e)
                          (burrowable? world c (away at e))))))
         (take pparams/vantage-tries)
         (some (fn [c]
                 (when (intent/routable? world
                                         {:intent/kind :walk
                                          :intent/target c
                                          :intent/for :shelter
                                          :intent/max-nodes pparams/vantage-nodes})
                   c))))))

(defn crowd-site
  "The stare-site of the calm enderman crowding the burrow's mouth (crowder), where a new burrow
   is cut when the old one is given up (stale?) over it; nil when there is none, or no crowder.
   Beside the crowder the old burrow would be cut again, its end and mouth the cells they had
   been."
  [world]
  (when-let [[_ e] (crowder world)] (stare-site world e)))

(defn floored-length
  "How many of the first n level steps from feet along dir stand on a floor once the steps
   before them are open (stairs/level-safe? a step at a time on the block overlay, as
   burrowable? opens a burrow's steps): the length a tunnel toward an enderman may cut before
   a step out over a drop, as dig-out refuses a cut from a brink (brink?)."
  [world feet dir n]
  (let [steps (reductions (fn [cells _] (stairs/level-cells (last cells) dir))
                          (stairs/level-cells feet dir)
                          (range (dec n)))
        open (fn [w cells] (reduce #(assoc-in %1 [:world/blocks %2] blocks/air) w cells))]
    (second (reduce (fn [[w k] cells]
                      (if (stairs/level-safe? w cells) [(open w cells) (inc k)] (reduced [w k])))
                    [world 0]
                    steps))))

(defn solid-cut?
  "Does a cut of kind (:stairs-up, :stairs-down or :tunnel) from feet along dir have at least
   one solid block to cut within its length?"
  ([world kind feet dir] (solid-cut? world kind feet dir 1))
  ([world kind feet dir n]
   (case kind
     :tunnel
     (let [steps (take n
                       (iterate (fn [cells] (stairs/level-cells (last cells) dir))
                                (stairs/level-cells feet dir)))]
       (boolean (some (fn [cells] (some #(solid-or-unloaded? world %) cells)) steps)))
     (boolean (some #(solid-or-unloaded? world %) ((pparams/first-cut kind) feet dir))))))

(defn brink?
  "Does one of the eight cells beside feet [x y z] at its height stand open over brink-drop or more
   open cells: a drop the bot can be pushed or step off into?"
  [world [x y z]]
  (boolean (some (fn [[dx dz]]
                   (let [cx (+ x dx)
                         cz (+ z dz)]
                     (not-any? #(solid-or-unloaded? world [cx (- y %) cz])
                               (range 0 (inc pparams/brink-drop)))))
                 (for [dx [-1 0 1] dz [-1 0 1] :when (not= [0 0] [dx dz])] [dx dz]))))

(defmethod intent/run :burrow
  ;; {:intent/kind :burrow :intent/dir [dx dz]}: stairs down burrow-depth (stairs.clj, refusing
  ;; water, lava and drops, turning where refused), then a tunnel burrow-length on along the
  ;; heading the stairs ended on: the stairs are four high (:intent/tall, mob/tall), so an
  ;; enderman follows down them, and the tunnel two high, so it stops at its mouth, two cells
  ;; from the bot (three-high stairs under a hill would hold it at the first).
  ;; First, while a cell over the mouth that an enderman standing in it would fill is solid
  ;; (mouth-cells), a child :dig of it: under a fungus's cap the mouth is two high and the
  ;; enderman never enters. Done when the feet stand in a shelter?, kept as :pearls/shelter, with
  ;; where it began as :pearls/mouth; failed :no-shelter when they do not, or with a child's
  ;; reason
  [world {:intent/keys [stage dir mouth] :as i} event]
  (let [mouth (or mouth (intent/feet world))
        roof (first (filter #(solid-or-unloaded? world %) (mouth-cells mouth)))
        i (assoc i :intent/mouth mouth :intent/stage (or stage (if roof :roof :down)))
        world (assoc world :plan/intent i)]
    (case (:intent/stage i)
      :roof
      (cond (nil? roof) (intent/assoc-intent world :intent/stage :down)
            :else
            (stone/link world
                        i
                        event
                        (intent/child-dig roof)
                        (fn [w _] (intent/assoc-intent w :intent/stage :down))))
      :down
      (stone/link
       world
       i
       event
       {:intent/kind :stairs-down
        :intent/to-y (- (physics/y-of mouth) pparams/burrow-depth)
        :intent/dir dir
        :intent/tall (mob/tall :enderman)}
       (fn [w child]
         (intent/assoc-intent w :intent/stage :along :intent/dir (:intent/dir child dir))))
      :along
      (stone/link world
                  i
                  event
                  {:intent/kind :tunnel :intent/dir dir :intent/length pparams/burrow-length}
                  (fn [w _]
                    (let [feet (intent/feet w)]
                      (cond (shelter? w feet)
                            (-> w
                                (assoc :pearls/shelter feet :pearls/mouth mouth :player/controls {})
                                intent/done)
                            :else (intent/fail w :no-shelter))))))))

;; ---------------------------------------------------------------- the lookout
(defmethod intent/run :lookout
  ;; {:intent/kind :lookout}: stand, the eyes level and turning (lookout-turn), for lookout-ms,
  ;; then done: the hunt with nothing to go after waits as an intent, not a planner wait, whose
  ;; 400 ticks fail the plan. A stare it turns onto by chance provokes one, which is what the hunt
  ;; wants
  [world i _]
  (let [now (:time/now world)
        since (or (:intent/since i) now)
        world (intent/assoc-intent world :intent/since since)]
    (cond (>= (- now since) pparams/lookout-ms)
          (-> world
              (assoc :player/controls {})
              intent/done)
          :else
          (assoc world
                 :player/controls
                 {:control/look [(mod (* pparams/lookout-turn (- now since)) 360.0) 0.0]}))))
