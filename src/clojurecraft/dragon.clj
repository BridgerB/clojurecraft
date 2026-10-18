(ns clojurecraft.dragon
  "The dragon (issue #267): the End's crystals broken by pillaring, the perched dragon fought by
   sword, and the win. Two goals: :crystals, done when no end crystal is shown and the bot stands
   off its pillars, and :dragon, done when the server says the dragon died (:world/dragon-dead?,
   game.clj: its death event, its removal near the bot with no boss bar shown, or the credits).

   A crystal: a hit of any kind blows it up, a blast of power 6 that kills a player within about
   eight blocks with a clear line to it, so the bot never strikes in the open. It strikes from the
   cell stand-off out from the crystal's column along an axis, its feet level with the spike's top
   (where the crystal's bedrock sits), with two blocks put between in the cell wall-off out, the
   eye's height and the feet's: the blast's line to the body is shut, the attack, which the server
   judges by reach alone, goes past them. The way up is a pillar beside the spike along the same
   axis (the :pillar intent: a block put under the feet at the top of each jump), the first
   column out from the spike's top layer; a wide spike's stand is on its top, walked onto from the
   pillar. The way down is the pillar dug back from on top of it (:dig-down). Everything is read
   from the world each tick: which crystal is nearest, where its approach is, whether the feet
   stand on a pillar (two pillar blocks under them) or a spike (three obsidian), so a death or a
   reload resumes where the world says and nothing records progress.

   The dragon: the bot waits by the fountain (the exit portal's pillar at the End's 0 0, where the
   dragon lands to perch); once the dragon has held within perch-radius of it under perch-below
   for perch-ticks, it is struck every combat/swing-every while its box is in strike-reach (a
   16 by 8 box whose feet are on the fountain's pillar: :fight's reach to an aim point over its
   feet is never met from a floor, the server's own test is to the box). The dragon's breath and
   fireballs (area_effect_cloud, dragon_fireball) within breath-radius are backed away from
   (combat's :back-off). Not done: the cages round two spikes (the strike through their bars is
   unproven; the gym takes them away), the bridge from the End's platform to the main island,
   and the exit portal."
  (:require [clojure.math :as math]
            [clojurecraft.blocks :as blocks]
            [clojurecraft.combat :as combat]
            [clojurecraft.dimension :as dimension]
            [clojurecraft.entity :as entity]
            [clojurecraft.game :as game]
            [clojurecraft.intent :as intent]
            [clojurecraft.inventory :as inventory]
            [clojurecraft.mob :as mob]
            [clojurecraft.packet :as packet]
            [clojurecraft.physics :as physics]
            [clojurecraft.place :as place]
            [clojurecraft.plan :as plan]
            [clojurecraft.recipe :as recipe]
            [clojurecraft.terrain :as terrain]
            [clojurecraft.window :as window]))

(set! *warn-on-reflection* true)

(def pillar-blocks
  "The blocks the bot pillars and walls with, the first held first: what mining leaves in the
   bag. Never a block that is ground where a pillar stands (end stone, the End's, which the way
   down would dig on into; stone), nor obsidian, a spike's."
  [:cobblestone :cobbled_deepslate :netherrack :dirt :blackstone])

(def stand-off 3)                     ; cells along an axis from the crystal's column the strike stands in: the box 2 off
(def wall-off 2)                      ; cells out the two blocks between go, shutting the blast's line
(def spike-search 9)                  ; cells out from the crystal a spike's top layer is followed for its edge
(def strike-reach 3.0)                ; eye to a target's box within which a strike attacks: the client's entity reach
(def clear-rise 1.05)                 ; blocks the feet rise over the cell a jump left before its block goes in
(def block-ms 4000)                   ; ms a pillar may take over one block before it fails :stuck
(def max-refusals 3)                  ; placements under the feet refused before a pillar fails :rejected
(def answer-ms 3000)                  ; ms a placement waits for the server's ack
(def pillar-look 8)                   ; cells round the feet the top of the pillar back down is looked for in
(def look-ms 3000)                    ; ms the crystals row looks round before none shown is none there
(def sky 127)                         ; the height a column's ground is looked for down from

(def fountain [0.5 0.5])              ; [x z] of the exit portal's pillar, the dragon's perch (EndPodiumFeature at 0 0)
(def perch-radius 6.0)                ; blocks on the plane from the fountain the dragon perches within
(def perch-below 70.0)                ; and under this height
(def perch-ticks 10)                  ; ticks it holds there before it counts as perched
(def stand-from 2)                    ; blocks from the fountain along an axis the bot waits, in the exit portal's basin
(def stand-near 1.0)                  ; blocks on the plane from that stand a wait counts as there
(def sitting-phases #{5 6 7})         ; the dragon's phases on its perch (EnderDragonPhase: sitting flaming, scanning, attacking)
(def walk-in-to 3.0)                  ; blocks on the plane from the fountain a perch walks in to for reach, no nearer
(def perch-hold-ms 60000)             ; a :perch hands back to the planner this often
(def breath-radius 6.0)               ; a breath cloud (radius 5, DragonSittingFlamingPhase) or a fireball this near is backed away from
(def breath-kinds #{:area_effect_cloud :dragon_fireball})

;; ---------------------------------------------------------------- what is shown

(defn crystals
  "{eid entity} of the end crystals the server shows."
  [world]
  (into {} (filter (fn [[_ e]] (= :end_crystal (entity/kind e)))) (:world/entities world)))

(defn dragon
  "[eid entity] of the ender dragon the server shows, alive by its word, or nil."
  [world]
  (first (filter (fn [[_ e]] (and (entity/dragon? e) (not (entity/dead? e))))
                 (:world/entities world))))

(defn breaths
  "{eid entity} of the dragon's breath clouds and fireballs the server shows."
  [world]
  (into {} (filter (fn [[_ e]] (contains? breath-kinds (entity/kind e)))) (:world/entities world)))

(defn pillar-block?
  "Is the state one of the pillar blocks?"
  [id]
  (contains? (set pillar-blocks) (blocks/name-of id)))

(defn pillar-block
  "The name of the first pillar block held, or nil."
  [world]
  (some #(when (pos? (inventory/item-count world (recipe/item-id %))) %) pillar-blocks))

(defn under-feet?
  "Do the n cells under the feet all hold a state pred? says yes to?"
  [world pred n]
  (let [[x y z] (intent/feet world)]
    (every? #(some-> (terrain/block-at world [x (- y %) z])
                     pred)
            (range 1 (inc n)))))

(defn on-pillar?
  "Do the feet stand on a pillar, two pillar blocks under them: a way down the bot dug up?"
  [world]
  (under-feet? world pillar-block? 2))

(defn on-spike?
  "Do the feet stand on a spike's top, three obsidian under them (the End's platform is one
   thick)?"
  [world]
  (under-feet? world #(= :obsidian (blocks/name-of %)) 3))

(defn ground
  "The feet height of the first ground in column x z down from y top (a solid block, the feet over
   it), or nil when none is loaded there (the void)."
  [world x z top]
  (some #(let [id (terrain/block-at world [x % z])] (when (and id (blocks/solid? id)) (inc %)))
        (range top -1 -1)))

;; ---------------------------------------------------------------- a crystal's approach

(defn approach-along
  "The way to strike the crystal standing at pos along axis [dx dz], or nil: {:column the pillar's
   foot (the feet cell on the ground of the first column out from the spike's top layer, stand-off
   at the nearest) :top the feet height level with the spike's top :stand the feet cell stand-off
   out at :top :wall the cells wall-off out at :top and over it}; nil when the spike's edge is not
   found within spike-search, or that column has something over its ground at the top or over the
   head there, or no ground. The spike's top is read off the block under the crystal (its bedrock),
   not the crystal's height: a crystal stands in the fire it lights, and gym dragon-a's, put on the
   highest block of the column, stood on that fire a block over the bedrock, its approach a block
   too high, and every walk to its pillar's foot (on the spike's top) failed :no-path."
  [world [cx cy cz] [dx dz]]
  (let [x (long (math/floor cx))
        z (long (math/floor cz))
        h (some-> (ground world x z (long (math/floor cy)))
                  dec)
        at (fn [k y] [(+ x (* k dx)) y (+ z (* k dz))])
        edge (when h
               (first (remove #(place/solid-at? world (at % (dec h)))
                              (range 1 (inc spike-search)))))
        out (when edge (max edge stand-off))
        [px _ pz] (when out (at out h))
        base (when out (ground world px pz (+ h 2)))]
    (when (and base (<= base h))
      {:column [px base pz]
       :top h
       :stand (at stand-off h)
       :wall [(at wall-off h) (at wall-off (inc h))]})))

(defn best-approach
  "The approach to the crystal at pos whose pillar's foot is nearest the feet on the plane, of the
   four along the axes (approach-along), or nil when there is none."
  [world pos]
  (let [feet (:player/pos world)]
    (->> [[1 0] [-1 0] [0 1] [0 -1]]
         (keep #(approach-along world pos %))
         (sort-by #(physics/horizontal-distance feet (physics/centre (:column %))))
         first)))

(defn wall-gap
  "The first cell of approach a's wall that a block may still go into, or nil when both are
   filled (a block of any kind: a cage's bars shut the line too)."
  [world a]
  (first (filter #(some-> (terrain/block-at world %)
                          blocks/replaceable?)
                 (:wall a))))

(defn pillar-top
  "The nearest cell at the feet's height within pillar-look of them whose cell under is a pillar
   block and that the body stands in (it and the cell over it open), or nil: the way back down
   from a spike's top."
  [world]
  (let [[fx fy fz] (intent/feet world)
        open? #(some-> (terrain/block-at world %)
                       blocks/replaceable?)]
    (->> (for [dx (range (- pillar-look) (inc pillar-look))
               dz (range (- pillar-look) (inc pillar-look))
               :let [c [(+ fx dx) fy (+ fz dz)]]
               :when (and (some-> (terrain/block-at world [(first c) (dec fy) (last c)])
                                  pillar-block?)
                          (open? c)
                          (open? [(first c) (inc fy) (last c)]))]
           c)
         (sort-by #(physics/horizontal-distance (:player/pos world) (physics/centre %)))
         first)))

(defn standing-walk
  "A walk that ends standing in cell (intent/standing-walks: :build)."
  [cell]
  {:intent/kind :walk :intent/target cell :intent/for :build})

(defn toward-crystals
  "The next intent toward no crystal standing and the feet off every pillar, for the crystal
   nearest the feet and its approach: at its stand, a block into the wall's gap (:place into the
   given cell), then the strike; in its pillar's column under the top, the :pillar up; on the
   pillar's top short of the stand, the walk onto the spike to it; else, on a spike's top, the walk
   back to the pillar's top, on a pillar the :dig-down, on the ground the walk to the pillar's foot.
   A wait :no-blocks with no pillar block held where one is needed, :no-approach for a crystal no
   axis reaches, :stranded on a spike with no pillar back down; nil with no crystal and the feet
   down."
  [world]
  (let [[fx fy fz :as feet] (intent/feet world)
        [eid c] (entity/nearest (crystals world) (:player/pos world))
        a (when c (best-approach world (:entity/pos c)))
        [cx _ cz] (:column a)
        item (pillar-block world)
        gap (when a (wall-gap world a))]
    (cond (and a (= feet (:stand a)) gap item)
          {:intent/kind :place :intent/item item :intent/cell gap}
          (and a (= feet (:stand a)) gap) {:plan/wait :no-blocks}
          (and a (= feet (:stand a))) {:intent/kind :strike :intent/target eid}
          (and a (= [fx fz] [cx cz]) (< fy (:top a)) item)
          {:intent/kind :pillar :intent/item item :intent/top (:top a)}
          (and a (= [fx fz] [cx cz]) (< fy (:top a))) {:plan/wait :no-blocks}
          (and a (= feet [cx (:top a) cz])) (standing-walk (:stand a))
          (on-spike? world)
          (if-let [t (pillar-top world)]
            (standing-walk t)
            {:plan/wait :stranded})
          (on-pillar? world) {:intent/kind :dig-down}
          a (standing-walk (:column a))
          c {:plan/wait :no-approach}
          :else nil)))

;; ---------------------------------------------------------------- the pillar

(defn held-item?
  "Does the held slot hold item (a name)?"
  [world item]
  (= (recipe/item-id item) (get-in world [:player/inventory (:player/held-slot world) :item])))

(defn climb
  "One tick of a :pillar's climb: a jump from the ground (the feet cell kept as :intent/under),
   and once the body has risen clear-rise over that cell, a block into it against the top of the
   one under (place/use-item-on), looking straight down; the block shown, the next jump. Done on
   the ground at :intent/top; failed :boxed with no room over the head, :not-held with the block
   gone from the hand, :rejected after max-refusals placements the server answered with the cell
   empty, :no-answer when one goes unacked, :stuck after block-ms with no block put."
  [world {:intent/keys [item top under refusals since sent-at sequence] :or {refusals 0}}]
  (let [now (:time/now world)
        [fx fy fz :as feet] (intent/feet world)
        py (physics/y-of (:player/pos world))
        ground? (:player/on-ground? world)
        down {:control/look [(first (:player/look world [0.0 0.0])) 90.0]}
        world (assoc world :player/controls down)]
    (cond
      (and ground? (>= fy top))
      (-> world
          (assoc :player/controls {})
          intent/done)
      (>= refusals max-refusals) (intent/fail world :rejected)
      (> (- now since) block-ms) (intent/fail world :stuck)
      (not (held-item? world item)) (intent/fail world :not-held)
      (and under (place/solid-at? world under))
      (intent/dissoc-intent (intent/assoc-intent world :intent/since now)
                            :intent/under
                            :intent/sent-at
                            :intent/sequence)
      (and under sent-at ground? (<= fy (second under)) (>= (:stats/last-ack world -1) sequence))
      (-> world
          (intent/assoc-intent :intent/refusals (inc refusals))
          (intent/dissoc-intent :intent/under :intent/sent-at :intent/sequence))
      (and sent-at (> (- now sent-at) answer-ms)) (intent/fail world :no-answer)
      sent-at world
      (and under (> py (+ (second under) clear-rise)))
      (place/use-item-on world
                         (update under 1 dec)
                         (packet/value :face :up)
                         (place/face-cursors :up))
      (and under ground?) (assoc world :player/controls (assoc down :control/jump? true))
      under world
      (not (some-> (terrain/block-at world [fx (+ fy 2) fz])
                   blocks/replaceable?))
      (intent/fail world :boxed)
      ground?
      (-> world
          (assoc :player/controls (assoc down :control/jump? true))
          (intent/assoc-intent :intent/under feet))
      :else world)))

(defmethod intent/run :pillar
  ;; {:intent/kind :pillar :intent/item :cobblestone :intent/top y}: the block in hand
  ;; (place/equip), then the climb, a block under the feet at the top of each jump
  [world {:intent/keys [stage] :as i} _]
  (let [i (cond-> i (nil? stage) (assoc :intent/stage :equip :intent/since (:time/now world)))
        world (assoc world :plan/intent i)]
    (case (window/waiting world i)
      :overdue (window/lost world i :equip)
      :waiting world
      (cond (:intent/resync i) (window/resync world (window/view world :inventory))
            (= :equip (:intent/stage i)) (place/equip world (:intent/item i) :climb)
            :else (climb world i)))))

(defmethod intent/run :dig-down
  ;; the block under the feet dug (a child :dig, its drop wanted or not), the fall into its cell
  ;; waited out, again, until the feet stand on less than a pillar (on-pillar?)
  [world {:intent/keys [child]} event]
  (let [[x y z] (intent/feet world)]
    (cond (and child (intent/failed? child)) (intent/fail world (:intent/reason child))
          (and child (not (intent/done? child))) (intent/run-child world event)
          (not (and (:player/on-ground? world) (place/solid-at? world [x (dec y) z])))
          (assoc world :player/controls {})
          (not (on-pillar? world))
          (-> world
              (assoc :player/controls {})
              intent/done)
          :else
          (intent/assoc-intent world
                               :intent/child
                               (assoc (intent/child-dig [x (dec y) z]) :intent/drop-ok? true)))))

;; ---------------------------------------------------------------- the strike

(defn aim-point
  "The point of entity e's box nearest the eye, a tenth inside it: where a strike looks."
  [eye e]
  (let [[w h] (:box (mob/kinds (entity/kind e)) [0.6 1.8])
        [x y z] (:entity/pos e)
        lo [(- x (/ w 2)) y (- z (/ w 2))]
        hi [(+ x (/ w 2)) (+ y h) (+ z (/ w 2))]]
    (mapv (fn [v a b] (min (- b 0.1) (max (+ a 0.1) v))) eye lo hi)))

(defn reach
  "Blocks from the eye to entity e's box (mob/box-distance; e a crystal or the dragon, whose
   boxes the mob table has), zero inside it."
  [world e]
  (mob/box-distance (game/eye world) (entity/kind e) (:entity/pos e)))

(defn arm
  "One step toward the best sword in hand (place/equip), then the :hit stage; the hand when none
   is held."
  [world]
  (if-let [[_ n] (combat/sword world)]
    (place/equip world n :hit)
    (intent/assoc-intent world :intent/stage :hit)))

(defn hit
  "The world with the eyes on entity eid's box (aim-point) and, when the intent's
   :intent/next-swing has passed, the eyes faced it the tick before and its box is within
   strike-reach, one attack (combat/attack: the next combat/swing-every on)."
  [world eid e]
  (let [eye (game/eye world)
        [yaw _ :as look] (physics/look-at eye (aim-point eye e))
        world (assoc world :player/controls {:control/look look})]
    (cond (and (>= (:time/now world) (:intent/next-swing (:plan/intent world) 0))
               (<= (reach world e) strike-reach)
               (<= (combat/yaw-off yaw (first (:player/look world [0.0 0.0]))) combat/aimed-within))
          (combat/attack world eid)
          :else world)))

(defmethod intent/run :strike
  ;; {:intent/kind :strike :intent/target eid}: the best sword in hand, then hit it while its box
  ;; is in reach; done when it is gone (a crystal blown up), failed :out-of-reach when its box is
  ;; not in strike-reach (the planner brings the feet to the stand first)
  [world {:intent/keys [target stage] :as i} _]
  (let [e (get-in world [:world/entities target])
        i (cond-> i (nil? stage) (assoc :intent/stage :arm))
        world (assoc world :plan/intent i)]
    (cond (entity/gone? world target)
          (-> world
              (assoc :player/controls {})
              intent/done)
          (> (reach world e) strike-reach) (intent/fail world :out-of-reach)
          :else
          (case (window/waiting world i)
            :overdue (window/lost world i :arm)
            :waiting world
            (cond (:intent/resync i) (window/resync world (window/view world :inventory))
                  (= :arm (:intent/stage i)) (arm world)
                  :else (hit world target e))))))

;; ---------------------------------------------------------------- the perched dragon

(defn perched?
  "Does the dragon e sit on the exit portal's pillar: its phase one of sitting-phases when the
   server has said it (:entity/phase), else within perch-radius of the fountain on the plane and
   under perch-below (the model says no phase)?"
  [e]
  (let [[x y z] (:entity/pos e)
        [fx fz] fountain
        phase (:entity/phase e)]
    (cond phase (contains? sitting-phases phase)
          :else (and (<= (math/hypot (- x fx) (- z fz)) perch-radius) (< y perch-below)))))

(defn breath-near
  "[eid entity] of the breath cloud or fireball nearest the feet within breath-radius, or nil."
  [world]
  (entity/nearest (breaths world) (:player/pos world) breath-radius))

(defn from-fountain
  "Blocks on the plane from the feet to the fountain."
  [world]
  (let [[fx fz] fountain] (physics/horizontal-distance (:player/pos world) [fx 0.0 fz])))

(defmethod intent/run :perch
  ;; {:intent/kind :perch :intent/target eid}: the best sword in hand, then by the fountain facing
  ;; the dragon; once it has held perched (perched?) for perch-ticks, hit it while its box is in
  ;; reach, walking in toward the fountain while it is not (no nearer than walk-in-to). Done when
  ;; it is gone (dead), a breath is near (the planner backs off) or after perch-hold-ms (the
  ;; planner looks again)
  [world {:intent/keys [target stage perched] :or {perched 0} :as i} _]
  (let [now (:time/now world)
        e (get-in world [:world/entities target])
        i (cond-> i (nil? stage) (assoc :intent/stage :arm :intent/since now))
        perched (if (and e (perched? e)) (inc perched) 0)
        world (assoc world :plan/intent (assoc i :intent/perched perched))
        sat? (>= perched perch-ticks)]
    (cond (or (entity/gone? world target)
              (breath-near world)
              (> (- now (:intent/since i)) perch-hold-ms))
          (-> world
              (assoc :player/controls {})
              intent/done)
          :else
          (case (window/waiting world i)
            :overdue (window/lost world i :arm)
            :waiting world
            (cond (:intent/resync i) (window/resync world (window/view world :inventory))
                  (= :arm (:intent/stage i)) (arm world)
                  (and sat? (<= (reach world e) strike-reach)) (hit world target e)
                  (and sat? (> (from-fountain world) walk-in-to))
                  (let [[fx fz] fountain]
                    (assoc world
                           :player/controls
                           (intent/toward world [fx (physics/y-of (game/eye world)) fz] 0.0)))
                  :else
                  (assoc world
                         :player/controls
                         {:control/look (physics/look-at (game/eye world) (:entity/pos e))}))))))

(defn fountain-stand
  "The feet cell on the ground stand-from out from the fountain along the axis nearest the feet,
   none within breath-radius of a breath cloud or fireball, where the bot waits for the dragon to
   perch, or nil when no such column has ground loaded. In the exit portal's basin beside the
   pillar: the perched dragon turns its face to the bot, its head 6.5 out from the pillar and its
   neck 5.5, each hurting what it touches; gym dragon-a stood five out, under the head, and died
   in two seconds of its perch, its five hits 2.5 each on the body."
  [world]
  (let [[fx fz] fountain
        clouds (vals (breaths world))]
    (->> [[1 0] [-1 0] [0 1] [0 -1]]
         (keep (fn [[dx dz]]
                 (let [x (long (math/floor (+ fx (* dx stand-from))))
                       z (long (math/floor (+ fz (* dz stand-from))))]
                   (when-let [y (ground world x z sky)] [x y z]))))
         (remove (fn [c]
                   (some #(<= (physics/horizontal-distance (physics/centre c) (:entity/pos %))
                              breath-radius)
                         clouds)))
         (sort-by #(physics/horizontal-distance (:player/pos world) (physics/centre %)))
         first)))

(defn toward-dragon
  "The next intent toward the dragon dead: while a crystal stands or the feet are up a pillar or
   a spike, the crystals' (toward-crystals); a breath near, combat's :back-off from it; away from
   the fountain's stand (fountain-stand), the walk there; there, the :perch. A wait :no-fountain
   with no ground round the fountain loaded, :no-dragon with no dragon shown."
  [world]
  (let [[eid] (dragon world)
        breath (breath-near world)
        stand (fountain-stand world)]
    (cond (or (seq (crystals world)) (on-pillar? world) (on-spike? world)) (toward-crystals world)
          breath {:intent/kind :back-off :intent/target (first breath)}
          (nil? stand) {:plan/wait :no-fountain}
          (> (physics/horizontal-distance (:player/pos world) (physics/centre stand)) stand-near)
          (standing-walk stand)
          (nil? eid) {:plan/wait :no-dragon}
          :else {:intent/kind :perch :intent/target eid})))

;; ---------------------------------------------------------------- the goals

(defmethod plan/done-by :crystals-gone?
  ;; in the End, no end crystal shown and the feet down off every pillar and spike, standing on
  ;; a block (the tick a dig-down's block breaks, the feet are over air and not yet down), once
  ;; look-ms have passed since the plan began (the entities come a moment after the chunks)
  [world _]
  (boolean (and (:player/pos world)
                (= dimension/end (dimension/of world))
                (empty? (crystals world))
                (:player/on-ground? world)
                (place/solid-at? world (update (intent/feet world) 1 dec))
                (not (on-pillar? world))
                (not (on-spike? world))
                (>= (- (:time/now world) (:plan/since world 0)) look-ms))))

(defmethod plan/done-by :dragon-dead? [world _] (boolean (:world/dragon-dead? world)))

(defmethod plan/next-intent :crystals
  ;; outside the End, a wait :other-dimension
  [world _ _]
  (if (= dimension/end (dimension/of world)) (toward-crystals world) {:plan/wait :other-dimension}))

(defmethod plan/next-intent :dragon
  [world _ _]
  (if (= dimension/end (dimension/of world)) (toward-dragon world) {:plan/wait :other-dimension}))
