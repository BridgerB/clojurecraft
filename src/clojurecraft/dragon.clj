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
   fireballs (area_effect_cloud, dragon_fireball) within their breath-reach are backed away from
   (combat's :back-off). The sitting dragon's head and neck hurt what they touch, so the wait is
   behind its body (its yaw, :entity/yaw), re-chosen as it turns, and a walk goes round the head
   (around); while the dragon sits or lands (grounded?) the bot stays on the basin's floor, under
   the head's and neck's reach: a breath near the feet is answered inside the basin (holds?,
   the walk to a stand out of its reach), any other breath by the walk to the nearest cell out
   of its reach (escape, never straight away into the fountain's pillar), it holds still
   through the dragon's scanning turn
   (scanning?), and the :defend row leaves every hostile to this row while a breath reaches the
   feet (combat/answering); every look of the rows' intents is turned from an enderman's eyes
   (look-away, plan/steer), and a carved pumpkin held is worn on the head first
   (wear-pumpkin), through which no look provokes one; in a lull, the bot eats for health
   back (the :heal row). From the End's entry platform the bot bridges to the main island
   before any crystal (toward-island). Not done: the cages round two spikes (the strike
   through their bars is unproven; the gym takes them away), and the exit portal."
  (:require [clojure.math :as math]
            [clojurecraft.armour :as armour]
            [clojurecraft.blocks :as blocks]
            [clojurecraft.combat :as combat]
            [clojurecraft.dimension :as dimension]
            [clojurecraft.eat :as eat]
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
(def landing-phases #{3})             ; its phase coming down onto the perch (DragonLandingPhase), its head already where a sitting one's is
(def scanning-phase 6)                ; its phase turning on the perch toward a player (DragonSittingScanningPhase): the head sweeps the basin
(def walk-in-to 3.0)                  ; blocks on the plane from the fountain a perch walks in to for reach, no nearer
(def perch-hold-ms 60000)             ; a :perch hands back to the planner this often
(def breath-radius 5.0)               ; a breath cloud's radius when its metadata has not said: the sitting dragon's flame cloud (DragonSittingFlamingPhase)
(def breath-margin 1.5)               ; blocks beyond a breath cloud's radius (:entity/radius: a fireball's grows from 3 to 7) the bot keeps
(def breath-kinds #{:area_effect_cloud :dragon_fireball})
(def behind-by 1.0)                   ; blocks behind the sitting dragon's body (along its facing, mob/ahead) a stand must be: out of its head's and neck's way
(def leave-by 0.5)                    ; blocks behind it the feet may come to, the dragon turning, before the :perch hands back for another stand
(def clear-across 3.3)                ; blocks across its facing a way must keep: the neck's box (mob/bite-across), the body's half width, a half block
(def way-step 0.5)                    ; blocks between the samples of a way tested for the head
(def side-out 5.0)                    ; blocks across its facing a way round the head goes out to
(def side-clear 3.8)                  ; blocks across its facing the feet must be at before the way round goes along
(def side-margin 1.0)                 ; blocks past the head's reach and behind the stands' line the way round turns at
(def escape-from 4)                   ; blocks from the fountain the nearest cell out of a breath's reach is looked for from: past the basin's stands
(def escape-to 12)                    ; and out to: a fireball's cloud grown to 7 and the margin, from a cloud on the basin
(def escape-ways 16)                  ; directions round the fountain the cells are looked for in
(def heal-below 18)                  ; food under this stops natural regeneration (FoodData.tick): eat in the End's lulls
(def calm-radius 8.0)                 ; blocks from the feet no hostile may be for a lull
(def full-health 20.0)                ; the player's health when nothing is left to heal
(def platform-west 98)                ; western x of the End's 5x5 obsidian spawn platform (x 98..102, z -2..2)
(def island-search-east 95)           ; easternmost x scanned westward at z 0 for the main island's shore

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
  "Do the feet stand on a pillar, a pillar block under them: a way down the bot dug up? One
   block, not two: the fight's dragon smashes what it flies through (cobblestone is not
   dragon-immune), and gym dragon-c run 1 fell from its third pillar to a stump with a gap
   under it, two pillar blocks under the feet no more, and walked for the next crystal's foot
   from up there (:no-path) until the plan failed."
  [world]
  (under-feet? world pillar-block? 1))

(defn obsidian?
  "Is the state (or nil) obsidian, a spike's?"
  [id]
  (= :obsidian
     (some-> id
             blocks/name-of)))

(defn on-spike?
  "Do the feet stand on a spike's top, three obsidian under them (the End's platform is one
   thick)?"
  [world]
  (under-feet? world obsidian? 3))

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
   head there, or no ground. The spike's edge is the first cell of its top layer that is not
   obsidian: gym dragon-b's bot pillared beside a wide spike, and its own pillar's top block,
   solid, moved the edge a column out, so the feet on top were in no approach's column and the
   way down began at once. The spike's top is read off the block under the crystal (its bedrock),
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
               (first (remove #(obsidian? (terrain/block-at world (at % (dec h))))
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

(def island-kinds
  "Natural blocks of the End's main island, never the bridge laid across the void."
  #{:end_stone :obsidian :bedrock})

(defn island-ground
  "The feet height of the highest natural End block in column x z down from sky, or nil when
   the column is void or holds only placed bridge blocks."
  [world x z]
  (some #(let [id (terrain/block-at world [x % z])]
           (when (and id (contains? island-kinds (blocks/name-of id))) (inc %)))
        (range sky -1 -1)))

(defn island-shore
  "The easternmost natural ground cell [x y 0] of the main island west of the spawn platform,
   scanning from island-search-east down to 0, or nil before the island's chunk loads."
  [world]
  (some #(when-let [y (island-ground world % 0)] [% y 0]) (range island-search-east -1 -1)))

(defn off-island?
  "Do the feet stand on the End's entry platform or the bridge from it across the void to the
   main island: no natural ground immediately west of platform-west at z 0, and the feet still
   east of island-shore?"
  [world]
  (let [[fx _ _] (intent/feet world)
        shore (island-shore world)]
    (and (nil? (island-ground world (dec platform-west) 0))
         (if shore (> fx (first shore)) (>= fx platform-west)))))

(defn toward-island
  "The next intent to cross from the End's entry platform (100, 49, 0) to the main island's
   eastern shore (island-shore): walk to the platform's western edge [platform-west y 0], pillar
   up to the shore's height when lower, then lay a bridge of pillar-block westward along z 0
   (two cells ahead of the feet so the body always steps onto a supported span) and walk it onto
   the shore."
  [world]
  (let [[fx fy fz] (intent/feet world)
        [ix iy _ :as shore] (island-shore world)
        item (pillar-block world)
        edge [platform-west (or (ground world platform-west 0 sky) fy) 0]
        next-floor [(dec fx) (dec fy) 0]
        ahead-floor [(- fx 2) (dec fy) 0]]
    (cond (nil? shore) {:plan/wait :no-crystals}
          (or (> fx platform-west) (not (zero? fz))) (standing-walk edge)
          (and (< fy iy) item) {:intent/kind :pillar :intent/item item :intent/top iy}
          (< fy iy) {:plan/wait :no-blocks}
          (and (not (place/solid-at? world next-floor)) item)
          {:intent/kind :place :intent/item item :intent/cell next-floor}
          (not (place/solid-at? world next-floor)) {:plan/wait :no-blocks}
          (and (> (dec fx) ix) (not (place/solid-at? world ahead-floor)) item)
          {:intent/kind :place :intent/item item :intent/cell ahead-floor}
          (and (> (dec fx) ix) (not (place/solid-at? world ahead-floor))) {:plan/wait :no-blocks}
          (= (dec fx) ix) (standing-walk shore)
          :else (standing-walk [(dec fx) fy 0]))))

(defn toward-crystals
  "The next intent toward no crystal standing and the feet off every pillar, for the crystal
   nearest the feet and its approach: on the End's entry platform or its bridge across the void,
   the way to the main island first (toward-island); at a crystal's stand, a block into the
   wall's gap (:place into the given cell), then the strike; in its pillar's column under the top,
   the :pillar up; on the pillar's top short of the stand, the walk onto the spike to it; else, on
   a spike's top, the walk back to the pillar's top, on a pillar the :dig-down, on the ground the
   walk to the pillar's foot. A wait :no-blocks with no pillar block held where one is needed,
   :no-approach for a crystal no axis reaches, :stranded on a spike with no pillar back down; nil
   with no crystal and the feet down."
  [world]
  (let [[fx fy fz :as feet] (intent/feet world)
        [eid c] (entity/nearest (crystals world) (:player/pos world))
        a (when c (best-approach world (:entity/pos c)))
        [cx _ cz] (:column a)
        item (pillar-block world)
        gap (when a (wall-gap world a))]
    (cond (off-island? world) (toward-island world)
          (and a (= feet (:stand a)) gap item)
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
      (and (not (held-item? world item)) (place/held-slot-of world (recipe/item-id item)))
      (intent/assoc-intent world :intent/stage :equip)
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

(defn equipped
  "world after an equip step of a :pillar: when it went on to the :climb, the window click's
   leftovers cleared (:intent/awaiting, :intent/awaiting-window, :intent/clicks), and its send
   time (:intent/sent-at) too while no placement is pending (no :intent/under), since the climb
   reads a send time as a block put and unanswered. Sim snapshot R1-11-5 (issue #178): the swap
   of a fresh stack into the hand left its send time, and three seconds on the climb failed
   :no-answer with nothing placed, the shaft was given up and the climb cut stairs at twice the
   time a level."
  [world]
  (let [i (:plan/intent world)]
    (cond-> world
      (= :climb (:intent/stage i))
      (intent/dissoc-intent :intent/awaiting :intent/awaiting-window :intent/clicks)
      (and (= :climb (:intent/stage i)) (nil? (:intent/under i)))
      (intent/dissoc-intent :intent/sent-at))))

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
            (= :equip (:intent/stage i)) (equipped (place/equip world (:intent/item i) :climb))
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

(defn grounded?
  "Is the dragon e on its perch or coming down onto it: its phase one of sitting-phases or
   landing-phases when the server has said it, else perched? by place? Its head and neck are then
   over the basin and its rim. Gym dragon2-b run 2: out on the rim 8 from the fountain, touched
   by the landing dragon's head (phase 3, which perched? does not count), then walked in under
   it."
  [e]
  (let [phase (:entity/phase e)]
    (cond phase (or (contains? sitting-phases phase) (contains? landing-phases phase))
          :else (perched? e))))

(defn scanning?
  "Is the dragon e turning on its perch toward a player (scanning-phase, about 25 ticks): its
   head sweeps round the basin and comes onto whoever is in it, faster than a walk away. Gym
   dragon2-a run 1 struck it twenty times unhurt from the basin's floor through that turn, then
   walked out beside the head up the rim and died."
  [e]
  (= scanning-phase (:entity/phase e)))

(defn turned?
  "Is the dragon e on its perch or landing on it (grounded?) with a yaw the server said
   (:entity/yaw): then its head and neck are on one side of it (mob/facing)."
  [e]
  (boolean (and e (:entity/yaw e) (grounded? e))))

(defn behind?
  "Is point p at least by blocks behind the sitting dragon e's body along its facing (mob/ahead):
   out of its head's and neck's way, whatever their pitch? True whenever e is not turned?: a
   flying dragon's head is not at its perch. Gym dragon-e run 2 and dragon-f run 2: slain two
   blocks from the fountain in front of the sitting dragon's head, four touches of 6 in two
   seconds."
  [e p by]
  (or (not (turned? e)) (<= (first (mob/ahead (:entity/pos e) (:entity/yaw e) p)) (- by))))

(defn in-bite?
  "Has the sitting dragon e turned its head onto point p: p not leave-by behind its body
   (behind?) and within mob/bite-out of it on the plane?"
  [e p]
  (and (not (behind? e p leave-by)) (< (mob/flat-distance (:entity/pos e) p) mob/bite-out)))

(defn before-head?
  "Is point p where the sitting dragon e's head and neck may come (turned?): along its facing
   from leave-by behind its body out to mob/bite-out, within clear-across of it?"
  [e p]
  (and (turned? e)
       (let [[along across] (mob/ahead (:entity/pos e) (:entity/yaw e) p)]
         (and (<= (- leave-by) along mob/bite-out) (<= (abs across) clear-across)))))

(defn bite-on-way?
  "Does the straight way on the plane from point a to point b pass before the sitting dragon e's
   head (before-head?, sampled every way-step)?"
  [e a b]
  (let [n (max 1 (long (math/ceil (/ (physics/horizontal-distance a b) way-step))))]
    (boolean (and (turned? e)
                  (some (fn [k]
                          (let [t (/ (double k) n)]
                            (before-head? e (mapv #(+ %1 (* t (- %2 %1))) a b))))
                        (range (inc n)))))))

(defn around
  "The point [x z] a walk from pos to point b goes by first while the straight way passes before
   the sitting dragon e's head (bite-on-way?), or nil when it does not: with the feet less than
   side-clear beside its facing, side-out out on their side, no nearer along it than side-margin
   past its head's reach (straight out when nearer); else side-out beside it, side-margin behind
   the stands' line (behind-by), from where the way to a stand behind the body is clear. A bot
   coming back from a back-off or its arrival in front of the perched dragon walked straight
   under its head to a stand behind it."
  [e pos b]
  (when (bite-on-way? e pos b)
    (let [[along across] (mob/ahead (:entity/pos e) (:entity/yaw e) pos)
          side (if (neg? across) (- side-out) side-out)]
      (mob/beside (:entity/pos e)
                  (:entity/yaw e)
                  (cond (< (abs across) side-clear) [(min along (+ mob/bite-out side-margin)) side]
                        :else [(- (+ behind-by side-margin)) side])))))

(defn ground-cell
  "The feet cell on the ground of the column under point [x z], or nil when none is loaded."
  [world [x z]]
  (let [cx (long (math/floor x))
        cz (long (math/floor z))]
    (when-let [y (ground world cx cz sky)] [cx y cz])))

(defn breath-reach
  "Blocks on the plane from breath e's centre the bot keeps out of: its radius (:entity/radius,
   breath-radius when its metadata has not said) and breath-margin. Gym dragon-d runs 1 and 3
   walked through a fireball's cloud grown to 5.7, six a second of magic through iron, and died."
  [e]
  (+ (:entity/radius e breath-radius) breath-margin))

(defn breath-near
  "[eid entity] of the breath cloud or fireball nearest the feet within its breath-reach, or
   nil."
  [world]
  (let [pos (:player/pos world)
        off (fn [[_ e]] (physics/horizontal-distance pos (:entity/pos e)))]
    (->> (breaths world)
         (filter (fn [[_ e :as b]] (<= (off b) (breath-reach e))))
         (sort-by off)
         first)))

(defn segment-distance
  "Blocks on the plane from point p to the nearest point of the segment from a to b."
  [[px _ pz] [ax _ az] [bx _ bz]]
  (let [dx (- bx ax)
        dz (- bz az)
        l2 (+ (* dx dx) (* dz dz))
        t (if (< l2 1.0e-9) 0.0 (min 1.0 (max 0.0 (/ (+ (* (- px ax) dx) (* (- pz az) dz)) l2))))]
    (math/hypot (- px (+ ax (* t dx))) (- pz (+ az (* t dz))))))

(defn breath-on-way?
  "Does a breath cloud or fireball reach (breath-reach) the straight way on the plane from point
   a to point b? A walk crosses it: gym dragon-d runs 1 and 3 walked back to the fountain's stand
   through a fireball's cloud lying between and died in it, nothing ending a walk but itself."
  [world a b]
  (boolean (some #(<= (segment-distance (:entity/pos %) a b) (breath-reach %))
                 (vals (breaths world)))))

(defn from-fountain
  "Blocks on the plane from the feet to the fountain."
  [world]
  (let [[fx fz] fountain] (physics/horizontal-distance (:player/pos world) [fx 0.0 fz])))

(defn endermen
  "The endermen the server shows, alive."
  [world]
  (filter #(and (= :enderman (entity/kind %)) (not (entity/dead? %)))
          (vals (:world/entities world))))

(defn look-away
  "The look [yaw pitch], unless from the eye it stares at an enderman shown (mob/stared?, the
   server's own cone and line test), then straight down at the same yaw: the wait for the dragon
   follows it round the sky and over the island's rim, and gym dragon-c runs 2 and 3 stared at
   an enderman on the rim that way (its stared-at flag, then its target, then four hits) and
   died."
  [world look]
  (let [eye (game/eye world)
        s (terrain/solid-fn world)
        solid? (fn [[x y z]] (s x y z))]
    (if (some #(mob/stared? solid? eye look (:entity/pos %)) (endermen world))
      [(first look) 90.0]
      look)))

(defn pumpkin-worn?
  "Is the carved pumpkin on the head: no look of the bot's can have provoked an enderman
   since it went on (wear-pumpkin), so one the server calls creepy here is after the
   dragon that hit it, not the bot."
  [world]
  (= :carved_pumpkin (armour/worn world 0)))

(defn wear-pumpkin
  "The :wear intent that puts a carved pumpkin held in the store slots on the head, or nil
   when one is worn there already or none is held: a player's look through a worn carved
   pumpkin never provokes an enderman (EnderMan.isBeingStaredBy), so the End's endermen
   stay calm through the crystal walks and the perch whatever the looks cross (gym
   chain-a3's dragon row: three runs slain by endermen, at 48, 424 and 506 s). The helmet
   swapped off stays held, and look-away still turns the row's looks. A pumpkin an
   earlier wear left on the cursor counts as held, so that wear is asked again instead
   of the row going on bare-headed (gym chain-w1's dragon run 3)."
  [world]
  (when (and (not (pumpkin-worn? world))
             (or (armour/cursor-holds? world :carved_pumpkin)
                 (some (fn [[p {i :item}]]
                         (and (< -1 p armour/store-slots) (= :carved_pumpkin (recipe/item-name i))))
                       (:player/inventory world))))
    {:intent/kind :wear :intent/item :carved_pumpkin}))

(declare fountain-stand holds? in-basin?)

(defmethod intent/run :perch
  ;; {:intent/kind :perch :intent/target eid}: the best sword in hand, then by the fountain facing
  ;; the dragon (look-away: never into an enderman's eyes); once it has held perched (perched?)
  ;; for perch-ticks, hit it while its box is in reach, walking in toward the fountain while it
  ;; is not (no nearer than walk-in-to, and never under its head: bite-on-way?). Done when it is
  ;; gone (dead), a breath is near (the planner backs off) unless the bot holds through it in the
  ;; basin (holds?), its head has turned onto the feet (in-bite?) with a stand behind it to go
  ;; to (the planner walks there) unless the dragon is in its scanning turn (scanning?: the head
  ;; sweeps on past a bot that holds still low), outside the basin once a stand is open without
  ;; breath on the way, or after perch-hold-ms (the planner looks again)
  [world {:intent/keys [target stage perched] :or {perched 0} :as i} _]
  (let [now (:time/now world)
        e (get-in world [:world/entities target])
        i (cond-> i (nil? stage) (assoc :intent/stage :arm :intent/since now))
        perched (if (and e (perched? e)) (inc perched) 0)
        world (assoc world :plan/intent (assoc i :intent/perched perched))
        sat? (>= perched perch-ticks)
        stand (fountain-stand world)
        health (:player/health world full-health)]
    (cond (or (< health combat/retreat-health)
              (entity/gone? world target)
              (and (breath-near world) (not (holds? world)))
              (and (in-bite? e (:player/pos world)) (not (scanning? e)) (some? stand))
              (and (not (in-basin? world))
                   stand
                   (not (breath-on-way? world (:player/pos world) (physics/centre stand))))
              (> (- now (:intent/since i)) perch-hold-ms))
          (-> world
              (assoc :player/controls {})
              intent/done)
          :else
          (case (window/waiting world i)
            :overdue (window/lost world i :arm)
            :waiting world
            (cond
              (:intent/resync i) (window/resync world (window/view world :inventory))
              (= :arm (:intent/stage i)) (arm world)
              (and sat? (<= (reach world e) strike-reach)) (hit world target e)
              (and sat?
                   (> (from-fountain world) walk-in-to)
                   (not
                    (breath-on-way? world (:player/pos world) (let [[fx fz] fountain] [fx 0.0 fz])))
                   (not (bite-on-way? e (:player/pos world) (let [[fx fz] fountain] [fx 0.0 fz]))))
              (let [[fx fz] fountain]
                (assoc world
                       :player/controls
                       (update (intent/toward world [fx (physics/y-of (game/eye world)) fz] 0.0)
                               :control/look
                               #(look-away world %))))
              :else
              (assoc world
                     :player/controls
                     {:control/look
                      (look-away world (physics/look-at (game/eye world) (:entity/pos e)))}))))))

(defn fountain-stand
  "The feet cell on the ground stand-from out from the fountain along the axis nearest the feet,
   none within breath-reach of a breath cloud or fireball and, while the dragon sits there turned
   (turned?), none short of behind-by behind its body (behind?), where the bot waits for the
   dragon to perch, or nil when no such column has ground loaded. In the exit portal's basin
   beside the pillar: the perched dragon turns its face to the bot, its head 6.5 out from the
   pillar and its neck 5.5, each hurting what it touches; gym dragon-a stood five out, under the
   head, and died in two seconds of its perch, its five hits 2.5 each on the body; dragon-e and
   dragon-f stood two out in front of the head lowered to them, and died the same way. Behind
   the body only the tail is, which hurts nothing; the nearest stand behind is re-chosen as the
   dragon turns (the :perch hands back when its head comes onto the feet, in-bite?)."
  [world]
  (let [[fx fz] fountain
        [_ d] (dragon world)
        clouds (vals (breaths world))]
    (->> [[1 0] [-1 0] [0 1] [0 -1]]
         (keep (fn [[dx dz]]
                 (let [x (long (math/floor (+ fx (* dx stand-from))))
                       z (long (math/floor (+ fz (* dz stand-from))))]
                   (when-let [y (ground world x z sky)] [x y z]))))
         (remove (fn [c]
                   (some #(<= (physics/horizontal-distance (physics/centre c) (:entity/pos %))
                              (breath-reach %))
                         clouds)))
         (filter #(behind? d (physics/centre %) behind-by))
         (sort-by #(physics/horizontal-distance (:player/pos world) (physics/centre %)))
         first)))

(defn in-basin?
  "Are the feet within walk-in-to of the fountain on the plane: in the exit portal's basin, under
   the sitting dragon's head and neck, which reach down to its rim?"
  [world]
  (<= (from-fountain world) walk-in-to))

(defn in-breath?
  "Are the feet within a breath cloud's or fireball's own radius (:entity/radius, breath-radius
   when unsaid), where it hurts, not only within its breath-reach?"
  [world]
  (let [pos (:player/pos world)]
    (boolean (some #(<= (physics/horizontal-distance pos (:entity/pos %))
                        (:entity/radius % breath-radius))
                   (vals (breaths world))))))

(defn at-stand?
  "Are the feet within stand-near of stand's middle on the plane?"
  [world stand]
  (<= (physics/horizontal-distance (:player/pos world) (physics/centre stand)) stand-near))

(defn holds?
  "Does the bot hold where its feet are through a breath near them (breath-near): in the basin
   (in-basin?) with the dragon grounded (grounded?), the feet out of the breath itself
   (in-breath?) and no stand out of its reach to go to (fountain-stand, or the feet at it)? A
   back-off straight from the breath goes up the basin's rim, where the head and neck reach: gym
   dragon2-a run 3 and dragon2-b runs 1 and 2 died there. In the breath itself the back-off
   stands, the lesser hurt."
  [world]
  (let [[_ d] (dragon world)
        stand (fountain-stand world)]
    (boolean (and d
                  (grounded? d)
                  (in-basin? world)
                  (not (in-breath? world))
                  (or (nil? stand) (at-stand? world stand))))))

(defn breathed?
  "Does a breath cloud or fireball reach (breath-reach) cell's middle?"
  [world cell]
  (boolean (some #(<= (physics/horizontal-distance (physics/centre cell) (:entity/pos %))
                      (breath-reach %))
                 (vals (breaths world)))))

(defn shelter-walk
  "A walk to cell that ends standing in it (:intent/for :shelter), which a breath near the feet
   does not end (plan/interrupts?): the cell is out of every breath's reach."
  [cell]
  {:intent/kind :walk :intent/target cell :intent/for :shelter})

(defn escape
  "The feet cell on the ground nearest the feet (other than the feet's own cell), of those
   escape-from to escape-to out from the fountain in escape-ways directions, that no breath
   reaches (breathed?) and that is not before the sitting dragon's head (before-head?), or nil
   when none has ground loaded. Combat's :back-off walks straight away from the breath: gym
   dragon3-a run 2 backed off from a fireball and its cloud on the stand into the fountain's
   pillar, jumped against it for six seconds inside the cloud and died; a walk to a cell goes
   round the pillar by its route."
  [world]
  (let [[_ d] (dragon world)
        [fx fz] fountain
        pos (:player/pos world)
        feet (intent/feet world)]
    (->> (for [r (range escape-from (inc escape-to))
               k (range escape-ways)
               :let [a (* k (/ (* 2.0 math/PI) escape-ways))]]
           [(+ fx (* r (math/cos a))) (+ fz (* r (math/sin a)))])
         (keep #(ground-cell world %))
         (remove #{feet})
         (remove #(breathed? world %))
         (remove #(before-head? d (physics/centre %)))
         (remove #(bite-on-way? d pos (physics/centre %)))
         (sort-by #(physics/horizontal-distance pos (physics/centre %)))
         first)))

(defn sheltered?
  "Is the bot standing in a safe shelter cell outside the fountain basin: at least escape-from
   out from the fountain, no breath reaching the feet (breathed?), and not before the sitting
   dragon's head (before-head?)?"
  [world]
  (let [[_ d] (dragon world)
        [fx fz] fountain
        pos (:player/pos world)
        feet (intent/feet world)]
    (and (>= (physics/horizontal-distance pos [fx 0.0 fz]) escape-from)
         (not (breathed? world feet))
         (not (before-head? d (physics/centre feet))))))

(defn recover
  "The intent while hurt under combat/retreat-health at the dragon, where the perch and
   the walk to its stand are never chosen (gym chain-w3's dragon runs 2 and 3 fought on
   at ten, then four and a half health, and the dragon slew them): a meal first when
   food is held and the bar can take one, else, while not yet in shelter outside the
   basin (sheltered?), the shelter walk to a cell out of every breath's reach (escape),
   else the wait :low-health where the feet are for the bar's own healing.
   The :heal row's meal needs a lull (heal-food), which a sitting dragon never is, so
   the row eats here on its own line."
  [world]
  (let [food (when (< (:player/food world 20) 20) (eat/food-held world))]
    (cond food {:intent/kind :eat :intent/item food}
          (sheltered? world) {:plan/wait :low-health}
          :else
          (if-let [flight (escape world)]
            (shelter-walk flight)
            {:plan/wait :low-health}))))

(defn toward-dragon
  "The next intent toward the dragon dead: while a crystal stands or the feet are up a pillar or
   a spike, the crystals' (toward-crystals); a breath near, combat's :back-off from it; away from
   the fountain's stand (fountain-stand), the walk there; there, the :perch, and with every stand
   under a breath, or one on the straight way to the stand (breath-on-way?), the :perch where
   the feet are (gym dragon-b: a fireball's cloud on the basin after the dragon took off, and
   the plan waited :no-fountain until it failed; gym dragon-d: the walk back to the stand went
   through a fireball's cloud lying between and died in it). A wait :no-fountain with no
   ground round the fountain loaded, :no-dragon with no dragon shown. From outside the basin
   (in-basin?), a walk to the stand while the sitting dragon's head is on the straight way goes
   round it first (around); inside it, straight, low under the head: gym dragon2-a runs 1 and 3,
   the head turned onto them, walked out of the basin (a way round, a back-off from the dragon),
   up its rim into the neck, and died there. While the dragon is grounded (grounded?) and the
   feet are in the basin, a breath near them is answered inside it: the walk to a stand out of
   its reach, or the :perch held where the feet are (holds?); any other breath near is left by
   the walk (shelter-walk) to the nearest cell out of every breath's reach and not before the
   head (escape), combat's straight :back-off only with no such cell loaded (gym dragon3-a run
   2 backed straight off into the fountain's pillar and died in the cloud against it); and
   through its scanning turn (scanning?) the :perch holds whatever stand the feet are at, the
   stand re-chosen only in the phases the dragon does not turn in. Hurt under
   combat/retreat-health, none of the perch or the stands: recover's meal, shelter
   walk or wait until the health is back over that line."
  [world]
  (let [[eid d] (dragon world)
        pos (:player/pos world)
        breath (breath-near world)
        stand (fountain-stand world)
        far? (and stand (> (physics/horizontal-distance pos (physics/centre stand)) stand-near))
        blocked? (and far? (breath-on-way? world pos (physics/centre stand)))
        flight (when breath (escape world))]
    (cond (or (off-island? world) (seq (crystals world)) (on-pillar? world) (on-spike? world))
          (toward-crystals world)
          (< (:player/health world full-health) combat/retreat-health) (recover world)
          (and breath eid (holds? world)) {:intent/kind :perch :intent/target eid}
          (and breath eid d (grounded? d) (or (in-basin? world) (not blocked?)) far?)
          (shelter-walk (or (when-not (in-basin? world)
                              (some->> (around d pos (physics/centre stand))
                                       (ground-cell world)))
                            stand))
          (and breath flight) (shelter-walk flight)
          breath {:intent/kind :back-off :intent/target (first breath)}
          (and (nil? stand) eid (some? (ground world 0 0 sky)))
          {:intent/kind :perch :intent/target eid}
          (nil? stand) {:plan/wait :no-fountain}
          (and eid (or blocked? (and (scanning? d) (in-basin? world))))
          {:intent/kind :perch :intent/target eid}
          far?
          (standing-walk (or (when-not (in-basin? world)
                               (some->> (around d pos (physics/centre stand))
                                        (ground-cell world)))
                             stand))
          (nil? eid) {:plan/wait :no-dragon}
          :else {:intent/kind :perch :intent/target eid})))

;; ---------------------------------------------------------------- a lull

(defn lull?
  "Is the End quiet round the feet: no breath near (breath-near), the dragon not on its perch
   nor landing on it (grounded?), and no hostile (entity/hostiles) within calm-radius?"
  [world]
  (let [[_ d] (dragon world)]
    (and (nil? (breath-near world))
         (not (and d (grounded? d)))
         (nil? (entity/nearest (entity/hostiles world) (:player/pos world) calm-radius)))))

(defn heal-food
  "The name of the food to eat now for health back in the End (eat/food-held), or nil: the bot
   hurt (under full-health), its food under heal-below, where the body stops healing by itself
   and the :eat row (under eat/hungry-below) does not yet eat, in a lull (lull?). The hand-off
   of gym dragon-f: a bot with sixteen steak fought on hurt and unhealed."
  [world]
  (when (and (:player/pos world)
             (= dimension/end (dimension/of world))
             (< (:player/health world full-health) full-health)
             (some-> (:player/food world)
                     (< heal-below))
             (lull? world))
    (eat/food-held world)))

;; ---------------------------------------------------------------- the goals

(defmethod plan/done-by :crystals-gone?
  ;; in the End, on the main island (not still on the entry platform or the bridge across the
  ;; void), no end crystal shown and the feet down off every pillar and spike, standing on a
  ;; block (the tick a dig-down's block breaks, the feet are over air and not yet down), once
  ;; look-ms have passed since the plan began or entered the End (the entities come a moment
  ;; after the chunks)
  [world _]
  (let [since (+ (:plan/since world 0) (get-in world [:plan/reached-after :enter-end] 0))]
    (boolean (and (:player/pos world)
                  (= dimension/end (dimension/of world))
                  (empty? (crystals world))
                  (:player/on-ground? world)
                  (place/solid-at? world (update (intent/feet world) 1 dec))
                  (not (off-island? world))
                  (not (on-pillar? world))
                  (not (on-spike? world))
                  (>= (- (:time/now world) since) look-ms)))))

(defmethod plan/done-by :dragon-dead? [world _] (boolean (:world/dragon-dead? world)))

(defmethod plan/next-intent :crystals
  ;; outside the End, a wait :other-dimension; in it, a held carved pumpkin on the head
  ;; first (wear-pumpkin), then the crystals'
  [world _ _]
  (if (= dimension/end (dimension/of world))
    (or (wear-pumpkin world) (toward-crystals world))
    {:plan/wait :other-dimension}))

(defmethod plan/interrupts? :dragon
  ;; a :perch ends at once hurt under combat/retreat-health (recover takes over: never
  ;; fight the dragon under that line); any running intent but a :back-off ends when a breath is near (breath-near) and the feet are
  ;; down off the pillars, spikes and entry bridge: gym dragon-d runs 1 and 3 walked into a
  ;; fireball's cloud and died in it, six a second, nothing else ending a walk; unless the intent
  ;; is the walk from the breath to a cell out of its reach (shelter-walk, :intent/for :shelter)
  ;; or the :perch held through it in the basin (holds?); and a walk from outside the basin whose
  ;; destination is before the sitting dragon's head (before-head?) or whose straight way passes
  ;; before it (bite-on-way?) while the feet are not already there (the walk out from under the
  ;; head to a safe cell continues; gym dragon3-b run 2 had the scanning head turn onto both the
  ;; feet and the waypoint ahead, and walked into the head)
  [world _ i]
  (let [[_ d] (dragon world)
        pos (:player/pos world)
        shelter? (and (= :walk (:intent/kind i)) (= :shelter (:intent/for i)))]
    (or (and (= :perch (:intent/kind i))
             (< (:player/health world full-health) combat/retreat-health))
        (and (not= :back-off (:intent/kind i))
             (not shelter?)
             (not (off-island? world))
             (not (on-pillar? world))
             (not (on-spike? world))
             (some? (breath-near world))
             (not (and (= :perch (:intent/kind i)) (holds? world))))
        (and (= :walk (:intent/kind i))
             (not shelter?)
             (:intent/target i)
             (not (in-basin? world))
             (let [goal (physics/centre (:intent/target i))]
               (or (before-head? d goal)
                   (and (not (before-head? d pos)) (bite-on-way? d pos goal))))))))

(defmethod combat/answers :dragon
  ;; the dragon is the row's own: gym dragon2-a run 1's :defend fought the perched dragon at hand
  ;; by :fight's aim point, off its stand
  [_]
  #{:ender_dragon})

(defmethod combat/answering :dragon
  ;; and every hostile shown while a breath cloud or fireball reaches the feet (breath-near),
  ;; while the dragon sits or lands on its perch (grounded?: gym dragon3-b runs 1 and 2 had
  ;; :defend chase an enderman the dragon had hit out of the basin up the rim into the sitting
  ;; dragon's head), or while crossing from the entry platform (off-island?),
  ;; unless health is under retreat-health where the dragon is not fought (recover takes over)
  [world _]
  (cond-> #{:ender_dragon}
    (and (:player/pos world)
         (= dimension/end (dimension/of world))
         (>= (:player/health world full-health) combat/retreat-health)
         (or (breath-near world)
             (off-island? world)
             (some-> (second (dragon world))
                     grounded?)))
    (into (map entity/kind (vals (entity/hostiles world))))))

(defmethod combat/answering :crystals [_ _] #{})

(defn looks-away
  "The world with its controls' look turned from an enderman's eyes (look-away), or as it
   is when no look, set or standing, meets one: every look an End row's intents make, a
   walk's and a back-off's as well as the perch's (gym dragon-f run 3 stared at three
   endermen in turn on its walks to and from the stand and was slain by the third,
   dragon-e run 1 at one on its first walk there); a walk steers by :control/yaw, so
   the look's pitch is free. With no look set this tick the standing look
   (:player/look) is turned too, by setting the controls' look to its turned form:
   an intent that sends no look (the :wear that puts the pumpkin on) otherwise leaves
   the landing look standing into whatever it faces for the whole wear, and gym
   chain-w5's dragon run 1 was slain by an enderman at its stand eight seconds in,
   the pumpkin on by then and no fight ever chosen."
  [world]
  (let [set (:control/look (:player/controls world))
        turned (look-away world (or set (:player/look world [0.0 0.0])))]
    (if (or set (not= turned (:player/look world [0.0 0.0])))
      (assoc-in world [:player/controls :control/look] turned)
      world)))

(defmethod plan/steer :dragon [world _] (looks-away world))

(defmethod plan/steer :crystals
  ;; the crystal walks and pillars cross the whole island under the endermen's eyes, the
  ;; chain's stage of them before the dragon's: the same turned looks as the dragon row's
  [world _]
  (looks-away world))

(defmethod plan/steer :wear
  ;; in the End, wearing armour or the carved pumpkin on the head must turn looks away
  ;; from endermen so the bot never stares during the clicks
  [world _]
  (if (= dimension/end (dimension/of world)) (looks-away world) world))

(defmethod plan/done-by :healed? [world _] (nil? (heal-food world)))

(defmethod plan/next-intent :heal
  [world _ _]
  (when-let [n (heal-food world)] {:intent/kind :eat :intent/item n}))

(defmethod plan/next-intent :dragon
  [world _ _]
  (if (= dimension/end (dimension/of world))
    (or (wear-pumpkin world) (toward-dragon world))
    {:plan/wait :other-dimension}))
