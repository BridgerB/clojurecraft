(ns clojurecraft.build
  "Building from a plan that is data (issue #8, the portal cast).

   A plan is a vector of ops, flat maps. Every op says what it does (:build/op :place :dig :pour
   :scoop :use-on :stand-in), where (:build/pos, and :build/stand where the bot stands to do it),
   with what (:build/item), and when it is done (:build/done, a predicate over the world as data:
   [:solid p] [:not-solid p] [:filled p] [:block p name] [:lava-source p] [:no-water p]
   [:dimension name] [:or & preds] [:and & preds]). `satisfied?` reads a predicate against the
   world. Nothing about progress is stored: the op to work on is the first one not satisfied, so
   a build resumes after a death, a chunk reload or a relaunch by reading the world again.

   `portal-plan` is ruststeve's template mold (portal_mold.rs: 7 of 8 pool gyms, a natural cast in
   1374 s) as data. The frame is ten obsidian in the plane z = bz of the origin [bx by bz], cast
   layer by layer in LAYERS order (never sorted by height); the bot works from the facing side,
   standing on a platform that rises one block a layer, and pours each cell from the cell's own
   stance (x, Y+1, bz+1): lava aimed at the cup's north wall low, water at the bowl's north wall,
   the bowl scooped once the cup reads obsidian. Positions are written for the bot working from
   +z (:south) and turned for the other facings."
  (:require [clojure.math :as math]
            [clojurecraft.blocks :as blocks]
            [clojurecraft.chunk :as chunk]
            [clojurecraft.game :as game]
            [clojurecraft.intent :as intent]
            [clojurecraft.inventory :as inventory]
            [clojurecraft.memory :as memory]
            [clojurecraft.packet :as packet]
            [clojurecraft.path :as path]
            [clojurecraft.physics :as physics]
            [clojurecraft.place :as place]
            [clojurecraft.plan :as plan]
            [clojurecraft.recipe :as recipe]
            [clojurecraft.scoop :as scoop]
            [clojurecraft.terrain :as terrain]
            [clojurecraft.window :as window]))

(set! *warn-on-reflection* true)

(def layers
  "The frame cells [dx dy] from the origin, in the order they are cast: the bottom pair, the two
   columns a block a layer, the top pair. Never sorted by height: a sort interleaves the columns and
   the bot crosses the frame for every block (steve cast.ts 2510-2521)."
  [[[1 0] [2 0]] [[0 1] [3 1]] [[0 2] [3 2]] [[0 3] [3 3]] [[1 4] [2 4]]])

(def frame-cells "Every frame cell [dx dy], in cast order." (vec (apply concat layers)))
(def interior
  "The portal's inside, [dx dy]: two wide, three high."
  (for [dx [1 2] dy [1 2 3]] [dx dy]))
(def facings #{:south :north :east :west}) ; the side of the frame the bot works from
(def lava-aim [0.5 0.3 -0.02])        ; in the cup's cell: low on the north wall's inner face, not the floor (lava at the feet)
(def water-aim [0.5 1.5 -0.02])       ; in the cup's cell: the bowl's north wall; a filled bucket's ray goes through lava
(def bowl-aim [0.5 1.5 0.5])          ; in the cup's cell: the middle of the bowl, to scoop the water back

;; ---------------------------------------------------------------- predicates

(defmulti holds?
  "Does predicate pred (a vector, its first element the kind) hold in world? An open set: a new
   kind is a defmethod."
  {:arglists '([world pred])}
  (fn [_world pred] (first pred)))

(defmethod holds? :solid
  [world [_ p]]
  (boolean (some-> (terrain/block-at world p)
                   blocks/solid?)))
(defmethod holds? :not-solid
  [world [_ p]]
  (boolean (some-> (terrain/block-at world p)
                   blocks/solid?
                   not)))
(defmethod holds? :filled
  [world [_ p]]
  (let [id (terrain/block-at world p)] (boolean (and id (not= :air (blocks/name-of id))))))
(defmethod holds? :block
  [world [_ p n]]
  (= n
     (some-> (terrain/block-at world p)
             blocks/name-of)))
(defmethod holds? :lava-source [world [_ p]] (= blocks/lava-base (terrain/block-at world p)))
(defmethod holds? :no-water
  [world [_ p]]
  (not (contains? blocks/water-states (terrain/block-at world p))))
(defmethod holds? :dimension [world [_ d]] (= d (:player/dimension world)))
(defmethod holds? :feet [world [_ p]] (= p (intent/feet world)))
(defmethod holds? :no-lava
  [world [_ p]]
  (not (contains? blocks/lava-states (terrain/block-at world p))))
(defmethod holds? :or [world [_ & ps]] (boolean (some #(holds? world %) ps)))
(defmethod holds? :and [world [_ & ps]] (every? #(holds? world %) ps))

(defn satisfied?
  "Is op done in world (its :build/done holds)?"
  [world op]
  (holds? world (:build/done op)))

;; ---------------------------------------------------------------- positions

(defn turn
  "A point [x z] on the plane, written for the bot working from +z, turned for facing."
  [facing [x z]]
  (case facing
    :south [x z]
    :north [(- x) (- z)]
    :east [z (- x)]
    :west [(- z) x]))

(defn point
  "The absolute point at [x y z] from origin (real coordinates, written for :south), turned for
   facing about the middle of the origin's cell, so the origin's cell stays where it is."
  [[bx by bz] facing [x y z]]
  (let [[tx tz] (turn facing [(- x 0.5) (- z 0.5)])] [(+ bx 0.5 tx) (+ by y) (+ bz 0.5 tz)]))

(defn cell
  "The absolute cell at [dx dy dz] from origin (written for :south), turned for facing: the cell
   its centre turns into, so a cell and the points inside it turn alike."
  [origin facing [dx dy dz]]
  (mapv #(long (math/floor %)) (point origin facing [(+ dx 0.5) dy (+ dz 0.5)])))

(defn aim
  "The absolute point at offset [ax ay az] inside the cell [dx dy dz] from origin, turned for
   facing."
  [origin facing [dx dy dz] [ax ay az]]
  (point origin facing [(+ dx ax) (+ dy ay) (+ dz az)]))

;; ---------------------------------------------------------------- the mold

(defn cast?
  "The predicate of a frame cell [dx dy] being cast: obsidian."
  [at [dx dy]]
  [:block (at [dx dy 0]) :obsidian])

(defn layer-cast?
  "The predicate of every cell of a layer being cast."
  [at cells]
  (into [:and] (map #(cast? at %) cells)))

(defn place-op
  "Place a block at pos from stand, done once something solid is there or guard holds."
  [pos stand guard step]
  {:build/op :place
   :build/item :cobblestone
   :build/pos pos
   :build/stand stand
   :build/step step
   :build/done (if guard [:or guard [:solid pos]] [:solid pos])})

(defn dig-op
  "Dig a solid at pos from stand, done once nothing solid is there, guard holds, or it is obsidian:
   a dig never takes obsidian (ruststeve dug its own frame and restarted forever)."
  [pos stand guard step]
  {:build/op :dig
   :build/pos pos
   :build/stand stand
   :build/step step
   :build/done (cond-> [:or [:block pos :obsidian] [:not-solid pos]] guard (conj guard))})

(defn pad-ops
  "The pad: a floor under the whole work area (dx -1..5, dz -1..2), each cell from the one in
   front of it."
  [at]
  (for [dz (range -1 3) dx (range -1 6)] (place-op (at [dx -1 dz]) (at [dx 0 (inc dz)]) nil :pad)))

(defn rim-ops
  "The rim: a ring at pad level round the footprint (dx -2..6, dz -2..3), only where it is air, so
   spilled water never reaches a pool."
  [at]
  (for [dz (range -2 4)
        dx (range -2 7)
        :when (or (#{-2 3} dz) (#{-2 6} dx))
        :let [p (at [dx 0 dz])]]
    {:build/op :place
     :build/item :cobblestone
     :build/pos p
     :build/stand (at [(max -1 (min 5 dx)) 0 (max -1 (min 2 dz))])
     :build/step :rim
     :build/done [:filled p]}))

(defn stair-ops
  "The stair up to layer li's work level: steps east of the platform, the top one beside the
   platform's back row, each a block lower going east (the rim's cell at dx 6 is one of them),
   placed bottom up, each from the step below it. The lowest is placed from the ground, or for
   layer 1, whose lowest step is beside the rim, from on top of the rim."
  [at li guard]
  (for [j (range (dec li) -1 -1)
        :let [stand (if (= [li j] [1 0]) [6 1 2] [(+ 6 j) (- li 1 j) 2])]]
    (place-op (at [(+ 5 j) (- li 1 j) 2]) (at stand) guard :stair)))

(defn platform-ops
  "The platform at layer li: two rows (dz 1 and 2) from dx 4 down to -1 at height li, the front
   row placed standing on the level below, then the back row standing on the front row, so the
   bot never places into its own cell (ruststeve: a ghost block)."
  [at li guard]
  (concat (for [dx (range 4 -2 -1)] (place-op (at [dx li 1]) (at [dx li 2]) guard :platform))
          (for [dx (range 4 -2 -1)]
            (place-op (at [dx li 2]) (at [dx (inc li) 1]) guard :platform))))

(defn cell-ops
  "Cast frame cell [dx dy] of layer cells: from its stance (dx, dy+1, +1) the cup's floor and
   walls (north, east, west; the south wall is the platform), the bowl's north wall and the bowl's
   east and west where no cell of the same layer is, clear the cup and the bowl, pour lava into
   the cup, water into the bowl, scoop the bowl back. Every op but the scoop is done once the cup
   is obsidian."
  [at origin facing cells [dx dy :as c]]
  (let [stand (at [dx (inc dy) 1])
        cup (at [dx dy 0])
        bowl (at [dx (inc dy) 0])
        done (cast? at c)
        sibling? (set cells)
        walls (concat [[dx (dec dy) 0] [dx dy -1] [(inc dx) dy 0] [(dec dx) dy 0] [dx (inc dy) -1]]
                      (when-not (sibling? [(inc dx) dy]) [[(inc dx) (inc dy) 0]])
                      (when-not (sibling? [(dec dx) dy]) [[(dec dx) (inc dy) 0]]))]
    (concat (for [w walls] (place-op (at w) stand done :wall))
            [(dig-op cup stand done :cup) (dig-op bowl stand done :bowl)
             {:build/op :pour
              :build/item :lava_bucket
              :build/stand stand
              :build/aim (aim origin facing [dx dy 0] lava-aim)
              :build/into cup
              :build/cell c
              :build/step :lava
              :build/done [:or done [:lava-source cup]]}
             {:build/op :pour
              :build/item :water_bucket
              :build/stand stand
              :build/aim (aim origin facing [dx dy 0] water-aim)
              :build/into bowl
              :build/cell c
              :build/step :water
              :build/done done}
             {:build/op :scoop
              :build/item :bucket
              :build/pos bowl
              :build/stand stand
              :build/aim (aim origin facing [dx dy 0] bowl-aim)
              :build/step :scoop
              :build/done [:no-water bowl]}])))

(defn layer-ops
  "Everything layer li needs: its stair, its platform and its cells in order. The stair and the
   platform are done only once the whole frame is cast (the front-ops dig them then): every
   later layer stands on them, so a cast resumed with its lower layers already obsidian still
   builds them."
  [at origin facing li]
  (let [cells (nth layers li)
        guard (layer-cast? at frame-cells)]
    (concat (stair-ops at li guard)
            (platform-ops at li guard)
            (mapcat #(cell-ops at origin facing cells %) cells))))

(defn front-ops
  "After the frame: the front cleared (the platform and anything else solid in dx 0..3, dz 1..2,
   dy 0..5, never obsidian), standing on the rim; then the interior scooped and dug out."
  [at]
  (concat (for [dx (range 4)
                dz [2 1]
                dy (range 5 -1 -1)]
            (dig-op (at [dx dy dz]) (at [dx 1 3]) nil :front))
          (mapcat (fn [[dx dy]]
                    (let [p (at [dx dy 0])
                          stand (at [dx 0 2])]
                      [{:build/op :scoop
                        :build/item :bucket
                        :build/pos p
                        :build/stand stand
                        :build/step :interior
                        :build/done [:no-water p]} (dig-op p stand nil :interior)]))
           interior)))

(defn light-op
  "Flint and steel on the top of the frame's bottom cell (1, 0), from two blocks in front of it,
   out of the fire's reach: done when the portal is there."
  [at]
  {:build/op :use-on
   :build/item :flint_and_steel
   :build/pos (at [1 0 0])
   :build/face :up
   :build/stand (at [1 0 2])
   :build/step :light
   :build/done [:block (at [1 1 0]) :nether_portal]})

(defn enter-op
  "Stand in the portal until the dimension is the Nether."
  [at]
  {:build/op :stand-in
   :build/pos (at [1 1 0])
   :build/stand (at [1 1 0])
   :build/step :enter
   :build/done [:dimension "minecraft:the_nether"]})

(defn mold
  "The ops shared by every plan from origin facing: the pad and the rim."
  [at]
  (concat (pad-ops at) (rim-ops at)))

(defn cast-one-plan
  "The mold from origin facing up to one cast cell, the first: the stage the gym's cast-one row
   judges."
  [origin facing]
  (let [at #(cell origin facing %)
        [first-cell] (first layers)]
    (vec (concat (mold at)
                 (platform-ops at 0 (cast? at first-cell))
                 (cell-ops at origin facing (first layers) first-cell)
                 [{:build/op :verify
                   :build/stand (at [1 1 1])
                   :build/step :verify
                   :build/done [:and (cast? at first-cell)
                                [:no-water
                                 (at [(first first-cell) (inc (second first-cell)) 0])]]}]))))

(defn frame-plan
  "The mold from origin facing to a complete frame, then a lit portal, and with enter? the step
   through it."
  [origin facing enter?]
  (let [at #(cell origin facing %)]
    (vec (concat (mold at)
                 (mapcat #(layer-ops at origin facing %) (range (count layers)))
                 (front-ops at)
                 [(light-op at)]
                 (when enter? [(enter-op at)])))))

(defn portal-plan
  "The whole cast from origin [bx by bz] facing (one of facings): the mold, ten cells, the front
   opened, the portal lit and stepped through. A pure function: equal arguments, an equal plan."
  [origin facing]
  (frame-plan origin facing true))

;; ---------------------------------------------------------------- the executors

(def settle-ms 300)                   ; standing still this long, looking at the aim, before using the item
(def answer-ms 3000)                  ; ms an action waits for the world to show it worked
(def await-ms 10000)                  ; ms an :await holds before it fails
(def station-radius 16)               ; blocks from the origin a lava station may be

(defn- now [world] (:time/now world))

(defn held?
  "Does the inventory hold item (a name)?"
  [world item]
  (pos? (inventory/item-count world (recipe/item-id item))))

(defmulti act-stage
  "Advance a :pour or :use-on one tick in its :intent/stage (:equip :aim :sent): put the item in
   hand, look at the aim and hold still, use it, then wait for the world to show the op done
   (:intent/expect)."
  {:arglists '([world intent])}
  (fn [_world i] (:intent/stage i)))

(defmethod act-stage :equip [world {:intent/keys [item]}] (place/equip world item :aim))

(defn use!
  "Use the held item at the intent's aim: a pour sends use-item with the look (a bucket is used by
   where the eyes point), a use-on sends use-item-on at the clicked block's top."
  [world {:intent/keys [kind target]} look]
  (case kind
    :pour (scoop/use-item world look)
    :use-on (place/use-item-on world target (packet/value :face :up) (place/face-cursors :up))))

(defmethod act-stage :aim
  [world {:intent/keys [aim since still] :or {still 0} :as i}]
  (let [look (physics/look-at (game/eye world) aim)
        still (if (intent/still? world) (inc still) 0)
        world (assoc world :player/controls {:control/look look})]
    (if (and (>= still 3) (> (- (now world) since) settle-ms))
      (-> world
          (use! i look)
          (intent/assoc-intent :intent/stage :sent))
      (intent/assoc-intent world :intent/still still))))

(defmethod act-stage :sent
  [world {:intent/keys [expect sent-at]}]
  (cond (holds? world expect) (intent/done world)
        (> (- (now world) sent-at) answer-ms) (intent/fail world :no-effect)
        :else world))

(defn act-run
  "Run a :pour or :use-on: its stage once no inventory click is unanswered."
  [world i]
  (let [i (cond-> i (nil? (:intent/stage i)) (assoc :intent/stage :equip :intent/since (now world)))
        world (assoc world :plan/intent i)]
    (case (window/waiting world i)
      :overdue (intent/fail world :stale-window)
      :waiting world
      (act-stage world i))))

(defmethod intent/run :pour [world i _] (act-run world i))
(defmethod intent/run :use-on [world i _] (act-run world i))

(defmethod intent/run :await
  ;; hold still until :intent/expect holds, await-ms at most
  [world {:intent/keys [expect started] :as i} _]
  (let [started (or started (now world))
        world (assoc world :plan/intent (assoc i :intent/started started) :player/controls {})]
    (cond (holds? world expect) (intent/done world)
          (> (- (now world) started) await-ms) (intent/fail world :not-yet)
          :else world)))

(def footprint
  "[origin facing] → the set of cells a frame there works in: the box round the mold (dx -2..6,
   dy -1..1, dz -2..3) and every cell an op of its plan places, digs or pours into, since the
   stair to the top layer runs east past the box. No lava may be in it, and no station's source
   under it. Memoized: the plan is pure."
  (memoize
   (fn [origin facing]
     (into (set
            (for [dx (range -2 7) dy (range -1 2) dz (range -2 4)] (cell origin facing [dx dy dz])))
           (keep #(or (:build/pos %) (:build/into %)))
           (portal-plan origin facing)))))

(defn footprint?
  "Is column [x z] under any cell of the footprint at origin facing, where a station must not be?"
  [origin facing [x z]]
  (boolean (some (fn [[cx _ cz]] (and (= x cx) (= z cz))) (footprint origin facing))))

(defn lava-sources
  "Every lava source in the loaded terrain, as the block overlay says now."
  [world]
  (->> (chunk/find-blocks (:world/chunks world) #(= % blocks/lava-base))
       (map (fn [[x y z _]] [x y z]))
       (filter #(= blocks/lava-base (terrain/block-at world %)))))

(defn station-among
  "[stand source] to refill lava from, of sources: a source outside the footprint within
   station-radius of origin, and a cell to stand in beside the cell over it (the source diagonally
   below the feet, never beside them), nearest the origin first; nil when none."
  [world sources origin facing]
  (->> sources
       (filter #(<= (physics/horizontal-distance (physics/centre %) (physics/centre origin))
                    station-radius))
       (remove (fn [[x _ z]] (footprint? origin facing [x z])))
       (sort-by #(physics/distance (physics/centre %) (physics/centre origin)))
       (mapcat (fn [[x y z :as src]]
                 (for [[dx dz] [[1 0] [-1 0] [0 1] [0 -1]]
                       :let [stand [(+ x dx) (inc y) (+ z dz)]]
                       :when (path/standable? world stand)]
                   [stand src])))
       first))

(defn station
  "[stand source] to refill lava from for the build at origin facing (station-among the lava
   sources loaded), or nil."
  [world origin facing]
  (station-among world (lava-sources world) origin facing))

(def plan-for
  "[build origin facing] → the plan: :cast-one, :portal (lit) or :nether (stepped through).
   Memoized: the plans are pure, and a build reads its plan every tick."
  (memoize (fn [build origin facing]
             (case build
               :cast-one (cast-one-plan origin facing)
               :portal (frame-plan origin facing false)
               :nether (portal-plan origin facing)))))

(defn refill
  "The child that refills lava: a walk to the station, then a scoop of its source; or the reason
   it cannot (:no-station, :no-bucket)."
  [world {:intent/keys [origin facing]}]
  (let [[stand src] (station world origin facing)]
    (cond (nil? stand) :no-station
          (not (held? world :bucket)) :no-bucket
          (not (intent/centred? world stand))
          {:intent/kind :walk :intent/target stand :intent/for :build}
          :else {:intent/kind :scoop :intent/target src :intent/fluid :lava})))

(def foundation-depth 4)               ; air cells under a placement a foundation is built up through

(defn foundation
  "The cell to place a block in now so that cell can have one: cell itself when a solid neighbour
   holds it up (place/support), else the lowest of the air cells straight under it (foundation-depth
   at most) that the block under it holds up, building up to it from below (ruststeve's
   ensure_solid); nil when there is none. A resumed cast meets this: a wall whose cell below was
   another cell's, already cast before, was never placed."
  [world [x y z :as cell]]
  (if (place/support world cell)
    cell
    (some (fn [dy]
            (let [c [x (- y dy) z]]
              (when (and (holds? world [:not-solid c]) (place/support world c)) c)))
          (range 1 (inc foundation-depth)))))

(defn place-cells
  "Every cell the plan places a block in."
  [build origin facing]
  (set (keep #(when (= :place (:build/op %)) (:build/pos %)) (plan-for build origin facing))))

(defn diggable?
  "Is pos a solid or awkward block a clear may dig: not unloaded, not obsidian, and not
   cobblestone the plan places (the mold, already stood up). Stone or dirt where a wall will
   go later is still dug: the place is satisfied by whatever is solid, and the hill would
   stay in the way."
  [world pos placed?]
  (when-let [id (terrain/block-at world pos)]
    (and (not (and (placed? pos) (= :cobblestone (blocks/name-of id))))
         (not (contains? intent/never-dug (blocks/name-of id)))
         (contains? #{:solid :awkward} (path/classify-id id)))))

(defn stance-block
  "The first solid cell in the way of standing at stand: from the feet toward it, one cell
   at a time, the body at the stand's height or the head over that. nil when that line is
   already clear. Full run 4e59246 stood at a pond and the pad's stand, four blocks east, was
   stone inside the hill; the walk at it was :none."
  [world stand build origin facing]
  (let [placed? (place-cells build origin facing)
        [fx _ fz] (intent/feet world)
        [sx sy sz] stand]
    (loop [x fx
           z fz]
      (let [body [x sy z]]
        (cond (diggable? world body placed?) body
              (diggable? world (update body 1 inc) placed?) (update body 1 inc)
              (and (= x sx) (= z sz)) nil
              :else
              (recur (cond (< x sx) (inc x)
                           (> x sx) (dec x)
                           :else x)
                     (if (= x sx)
                       (cond (< z sz) (inc z)
                             (> z sz) (dec z)
                             :else z)
                       z)))))))

(defn within-reach?
  "Can a dig of pos be started from here: the eye within reach of its centre, the same gate
   the dig fails :out-of-reach on."
  [world pos]
  (<= (physics/distance (game/eye world) (physics/centre pos)) (+ intent/reach 0.5)))

(defn approach
  "A walk to beside pos, where a dig of it can start, or nil when no route reaches a cell
   beside it."
  [world pos]
  (when (intent/routable?
         world
         {:intent/kind :walk :intent/target pos :intent/for :build :intent/pickup? true})
    {:intent/kind :walk :intent/target pos :intent/for :build :intent/pickup? true}))

(defn clear-stand
  "The child that opens a way to stand, or nil when the way is clear. The block in the way is
   dug when it is in reach (a pond beside the hill makes the face expose water, which a mold
   dig is allowed to), else walked to beside."
  [world stand build origin facing]
  (when-let [b (stance-block world stand build origin facing)]
    (if (within-reach? world b)
      (assoc (intent/child-dig b) :intent/exposes-ok? true)
      (approach world b))))

(defn reaches-from?
  "Would the eye, standing in cell, be within reach of pos? The feet use the real eye; any
   other cell, the eye at its middle."
  [world cell pos]
  (let [[x y z] cell
        eye (if (= cell (intent/feet world))
              (game/eye world)
              [(+ x 0.5) (+ y physics/eye-height) (+ z 0.5)])]
    (<= (physics/distance eye (physics/centre pos)) intent/reach)))

(defn reachable-stand
  "A cell the feet can occupy from which pos is within reach. The feet, when they already
   can; else the standable cell around pos nearest the designated stand. nil when none, and
   never the cell being worked. Full run 61abf8c stood on the shore of a pond: the pad cell
   was water and the stand was air over the next water cell, so a walk there was :partial
   and six replans failed :no-path."
  [world stand pos]
  (let [feet (intent/feet world)
        ok? (fn [cell]
              (and (not= cell pos) (path/standable? world cell) (reaches-from? world cell pos)))]
    (if (ok? feet)
      feet
      (->> (for [dy [0 -1 1]
                 dx (range -4 5)
                 dz (range -4 5)
                 :let [c (mapv + pos [dx (inc dy) dz])]
                 :when (ok? c)]
             c)
           (sort-by #(physics/distance (physics/centre %) (physics/centre stand)))
           first))))

(defn approach-stand
  "The child that gets the feet centred for op, or nil when they already are: on the
   designated stand, or, for a place or a dig whose stand cannot be occupied, on a cell that
   already reaches the block. A keyword is why no such cell exists. Ground in the way is dug
   only for a :build, whose mold says which cobblestone to keep; a :tunnel-to step (fortress)
   works the same ops with no build of its own, and opens its own way."
  [world i {:build/keys [op stand pos]}]
  (cond (intent/centred? world stand) nil
        :else
        (let [blocked
              (when (:intent/build i)
                (clear-stand world stand (:intent/build i) (:intent/origin i) (:intent/facing i)))]
          (cond blocked blocked
                (or (path/standable? world stand) (not (#{:place :dig} op)))
                {:intent/kind :walk :intent/target stand :intent/for :build}
                :else
                (let [work (cond (= :place op) (or (foundation world pos) pos)
                                 :else pos)
                      alt (reachable-stand world stand work)]
                  (cond (nil? alt) :no-stand
                        (intent/centred? world alt) nil
                        :else {:intent/kind :walk :intent/target alt :intent/for :build}))))))

(defn op-child
  "The child intent that works op next: lava to refill first when a lava pour has none, else
   whatever gets the feet centred for the op (a dig of the ground in the way, a walk to the
   stand, or a place already in reach from the shore), else the op's own action; or a
   keyword, why it cannot be worked."
  [world i {:build/keys [op pos item aim into done] :as this}]
  (cond (and (= op :pour) (= item :lava_bucket) (not (held? world :lava_bucket))) (refill world i)
        :else
        (or (approach-stand world i this)
            (case op
              :place
              (if-let [c (foundation world pos)]
                {:intent/kind :place :intent/item item :intent/cell c}
                :no-support)
              :dig (assoc (intent/child-dig pos) :intent/exposes-ok? true)
              :pour
              {:intent/kind :pour
               :intent/item item
               :intent/aim aim
               :intent/target into
               :intent/expect done}
              :scoop {:intent/kind :scoop :intent/target pos}
              :use-on
              {:intent/kind :use-on
               :intent/item item
               :intent/target pos
               :intent/aim (update (physics/centre pos) 1 + 0.5)
               :intent/expect done}
              :stand-in {:intent/kind :await :intent/expect done}
              :verify :unverified))))

(defn next-op
  "The op of plan to work now: the first not satisfied, or nil when the plan's last op holds (its
   goal: past it, as in another dimension, the earlier ops' cells are not even loaded)."
  [world plan]
  (when-not (satisfied? world (peek plan)) (first (remove #(satisfied? world %) plan))))

(defn work-op
  "One tick of the running intent i working op next through a child (op-child): a new op drops
   the child of the old one (the world moved on), a child that failed fails i, one that is done
   makes way for the next. Shared by every intent that works a plan of ops."
  [world {:intent/keys [child op] :as i} next event]
  (cond (not= next op)
        (intent/dissoc-intent (intent/assoc-intent world :intent/op next) :intent/child)
        (and child (intent/done? child)) (intent/dissoc-intent world :intent/child)
        (and child (intent/failed? child))
        (-> world
            (assoc :player/controls {})
            (intent/fail (:intent/reason child)))
        child (intent/run-child world event)
        :else
        (let [c (op-child world i next)]
          (cond (keyword? c) (intent/fail world c)
                :else
                (intent/run-child
                 (intent/assoc-intent world :intent/child (assoc c :intent/status :active))
                 event)))))

(defmethod intent/run :build
  ;; work the first op of the plan not satisfied (next-op, work-op); done when the last op holds.
  ;; The site is remembered once, so a new life resumes the build there
  [world {:intent/keys [origin facing] :as i} event]
  (let [world (cond-> world
                (not= {:site/origin origin :site/facing facing} (memory/site world))
                (memory/remember-site origin facing))
        next (next-op world (plan-for (:intent/build i) origin facing))]
    (if (nil? next)
      (-> world
          (assoc :player/controls {})
          intent/done)
      (work-op world i next event))))

;; ---------------------------------------------------------------- the site and the targets

(def site-radius 4)                   ; blocks from the feet a frame origin is looked for

(defn site-ok?
  "Can a frame be cast at origin facing: every cell of the footprint loaded and no lava in it (a
   block placed into lava destroys the source, and lava under the mold is no stance: ruststeve's
   lava sea), the ground under the origin solid, and a station among sources?"
  [world sources origin facing]
  (let [cells (footprint origin facing)]
    (and (every? #(some? (terrain/block-at world %)) cells)
         (not-any? #(contains? blocks/lava-states (terrain/block-at world %)) cells)
         (holds? world [:solid (update origin 1 dec)])
         (some? (station-among world sources origin facing)))))

(defn choose-site
  "[origin facing] for a new frame: the origin nearest the feet (within site-radius on the plane,
   at the feet's height) and the first facing that site-ok? accepts; nil when none."
  [world]
  (let [[fx fy fz] (intent/feet world)
        sources (lava-sources world)]
    (first (for [[dx dz] (sort-by (fn [[dx dz]] (+ (* dx dx) (* dz dz)))
                                  (for [dx (range (- site-radius) (inc site-radius))
                                        dz (range (- site-radius) (inc site-radius))]
                                    [dx dz]))
                 facing [:south :west :north :east]
                 :let [origin [(+ fx dx) fy (+ fz dz)]]
                 :when (site-ok? world sources origin facing)]
             [origin facing]))))

(defmethod plan/next-intent :build
  ;; build the row's plan (:goal/build) at the site remembered, else at one chosen now (the
  ;; :build intent remembers it); with none, wait :no-site
  [world {:goal/keys [build]} _]
  (let [{:site/keys [origin facing]} (memory/site world)
        [origin facing] (if origin [origin facing] (choose-site world))]
    (if origin
      {:intent/kind :build :intent/build build :intent/origin origin :intent/facing facing}
      {:plan/wait :no-site})))

(defmethod plan/done-by :built?
  [world {:goal/keys [build]}]
  (let [{:site/keys [origin facing]} (memory/site world)]
    (boolean (and origin (satisfied? world (peek (plan-for build origin facing)))))))
