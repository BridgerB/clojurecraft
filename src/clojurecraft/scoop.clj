(ns clojurecraft.scoop
  "Filling a bucket with water: the :scoop act of the goal table and the :scoop intent.

     {:intent/kind :scoop :intent/target [x y z]}   ; a water source within reach

   The act looks for a surface water source in the loaded terrain near the bot (never deeper
   than memory/surface-water-below under the eye: cave water is the aquifer trap the siblings
   drowned in), scoops it when it is in reach, else walks to it, else walks to the nearest
   remembered pool (one per chunk, memory/pools), else hunts: a walk of hunt-distance along
   one of eight bearings, downhill first and never back toward the landing, so new chunks load
   and their water is remembered. With nowhere left to hunt it waits :no-water.

   The intent puts the bucket in hand, looks at the source, and sends use-item with that look:
   a bucket is used by where the eyes point, not on a block face (use-item-on with a bucket does
   nothing). It is done only when the inventory holds one more water bucket than when it began;
   an unanswered scoop is tried again, and the last try fails :no-scoop, which blacklists the
   source. Nothing is remembered that the inventory can tell."
  (:require [clojure.math :as math]
            [clojurecraft.blocks :as blocks]
            [clojurecraft.chunk :as chunk]
            [clojurecraft.game :as game]
            [clojurecraft.intent :as intent]
            [clojurecraft.inventory :as inventory]
            [clojurecraft.memory :as memory]
            [clojurecraft.path :as path]
            [clojurecraft.physics :as physics]
            [clojurecraft.place :as place]
            [clojurecraft.plan :as plan]
            [clojurecraft.recipe :as recipe]
            [clojurecraft.stone :as stone]
            [clojurecraft.terrain :as terrain]
            [clojurecraft.window :as window]))

(set! *warn-on-reflection* true)

(def search-radius 24)                ; blocks from the eye searched for water in the loaded terrain
(def pool-radius 96)                  ; blocks from the eye a remembered pool is walked to from
(def settle-ms 300)                   ; standing still this long, looking at the source, before using the bucket
(def answer-ms 3000)                  ; ms a scoop waits for the water bucket before it is tried again
(def max-tries 3)                     ; scoops sent at one source before it fails :no-scoop
(def surface-height 0.85)             ; where a source's surface is aimed at: a source stands 8/9 of a block
(def ray-step 0.05)                   ; the length of one step along the eyes' ray to a source
(def hunt-distance 40)                ; blocks a water hunt walks along a bearing before looking again
(def surface-above 16)                ; how far above the eye a hunt looks for the ground to stand on
(def surface-below 24)                ; and how far below it

(defn- now [world] (:time/now world))

(def water-bucket (recipe/item-id :water_bucket))

;; ---------------------------------------------------------------- where the water is

(defn surface-source?
  "Is pos a water source block (plain water at level 0, not a waterlogged block) open to the
   air above it, a surface the eyes can look down into? A source under stone is a pocket in an
   aquifer wall: the live run that scooped at three of them got nothing from any."
  [world [x y z :as pos]]
  (let [above (terrain/block-at world [x (inc y) z])]
    (and (= blocks/water-base (terrain/block-at world pos))
         (some? above)
         (not (blocks/solid? above))
         (nil? (blocks/fluid above)))))

(defn aim-point
  "Where the eyes look to scoop the source at [x y z]: its surface's centre. Aimed at the block's
   centre from the bank, the ray clipped the corner of the bank block first (the sim's pond test)."
  [[x y z]]
  [(+ x 0.5) (+ y surface-height) (+ z 0.5)])

(defn sees?
  "Would the eyes reach the surface of the source at src: no solid or unloaded cell on the ray
   from the eye to its aim-point before the source itself?"
  [world src]
  (let [eye (game/eye world)
        aim (aim-point src)
        d (physics/distance eye aim)
        dir (mapv #(/ (- %2 %1) d) eye aim)]
    (every? #(some-> (terrain/block-at world %)
                     blocks/solid?
                     not)
            (take-while #(not= src %) (physics/cells-along eye dir d ray-step)))))

(defn in-reach?
  "Is src within the eyes' reach, eye to centre?"
  [world src]
  (<= (physics/distance (game/eye world) (physics/centre src)) intent/reach))

(defn sources
  "The surface water sources in the loaded terrain within search-radius of the eye, no more than
   memory/surface-water-below under it, not blacklisted, and, when in reach, in sight (sees?:
   one in reach but hidden is no target, or the walk to it would end at once), nearest first.
   Asked of the terrain through the block overlay, so a source just scooped is air."
  [world]
  (let [eye (game/eye world)
        lo (- (second eye) memory/surface-water-below)
        blacklist (:plan/blacklist world #{})]
    (memory/nearest-first eye
                          search-radius
                          (->> (chunk/find-blocks (stone/near-chunks world) #{blocks/water-base})
                               (map (fn [[x y z _]] [x y z]))
                               (filter (fn [[_ y _]] (>= y lo)))
                               (remove blacklist)
                               (filter #(surface-source? world %))
                               (remove #(and (in-reach? world %) (not (sees? world %))))))))

(defn pools-near
  "The remembered water pools within pool-radius, no deeper than surface water, not blacklisted,
   nearest first, leaving out one in a loaded chunk that is not a surface source to scoop
   (memory's pool is the highest source with no water above it, which may be under stone):
   what is loaded near it, the terrain search sees."
  [world]
  (let [eye (game/eye world)
        lo (- (second eye) memory/surface-water-below)
        blacklist (:plan/blacklist world #{})]
    (memory/nearest-first eye
                          pool-radius
                          (->> (vals (memory/pools world :water))
                               (filter (fn [[_ y _]] (>= y lo)))
                               (remove blacklist)
                               (remove #(and (terrain/chunk-loaded? world %)
                                             (not (surface-source? world %))))))))

(defn ground
  "The highest cell of column [x z] the feet could stand in, from surface-above over the eye down
   to surface-below under it, or nil (unloaded, or no floor there)."
  [world [x z] eye-y]
  (let [top (long (+ eye-y surface-above))]
    (when (terrain/chunk-loaded? world [x 0 z])
      (first (filter #(path/standable? world %)
                     (for [y (range top (- top surface-above surface-below) -1)] [x y z]))))))

(def bearings
  "The eight directions a water hunt can take, as unit [dx dz]."
  (for [k (range 8) :let [a (* k (/ math/PI 4))]] [(math/cos a) (math/sin a)]))

(defn hunt-targets
  "Where a water hunt may walk next: the ground hunt-distance along each of the eight bearings
   that has some, lowest first (water lies low), leaving out any nearer the landing
   (:plan/go-at) than the bot is now, so a hunt goes out and never doubles back."
  [world]
  (let [[px _ pz] (:player/pos world)
        ey (second (game/eye world))
        home (:plan/go-at world)
        out? (fn [[x _ z]]
               (or (nil? home)
                   (>= (physics/horizontal-distance [x 0 z] home)
                       (physics/horizontal-distance [px 0 pz] home))))]
    (->> bearings
         (keep (fn [[dx dz]]
                 (ground world
                         [(long (math/floor (+ px (* dx hunt-distance))))
                          (long (math/floor (+ pz (* dz hunt-distance))))]
                         ey)))
         (filter out?)
         (remove (:plan/blacklist world #{}))
         (sort-by second))))

(def max-candidates 3)                ; the nearest of each kind a route is planned to

(defn first-walk
  "A walk tagged for to the first of targets (the max-candidates first, out of reach) a route
   reaches (intent/routable?), or nil. A target already in reach is no walk: it would end
   before it began, and start again every tick (gym batch water-a3 run 5 did, for four
   minutes, at a remembered pool under stone)."
  [world for targets]
  (->> targets
       (remove #(in-reach? world %))
       (take max-candidates)
       (map (fn [t] {:intent/kind :walk :intent/target t :intent/for for}))
       (filter #(intent/routable? world %))
       first))

(defn scoop-next
  "The next step toward a filled bucket: scoop the nearest surface source when it is in reach,
   else walk to the nearest one in sight a route reaches, or to such a remembered pool, or hunt
   for one (hunt-targets); else wait :no-water."
  [world]
  (let [srcs (sources world)
        src (first srcs)]
    (cond (and src (in-reach? world src)) {:intent/kind :scoop :intent/target src}
          :else
          (or (first-walk world :scoop srcs)
              (first-walk world :scoop (pools-near world))
              (first-walk world :water-hunt (hunt-targets world))
              {:plan/wait :no-water}))))

(defmethod plan/act :scoop [world _] (scoop-next world))

;; ---------------------------------------------------------------- the scoop

(defmulti scoop-stage
  "Advance a :scoop intent one tick in its :intent/stage (:equip :aim :sent). Called only when
   no inventory click is unanswered."
  {:arglists '([world intent])}
  (fn [_world i] (:intent/stage i)))

(defmethod scoop-stage :equip [world _] (place/equip world :bucket :aim))

(defn use-item
  "Use the held item where the eyes look: emit use-item with a fresh :bot/sequence and the look
   it is aimed by, and record the sequence and the send time on the intent."
  [world [yaw pitch]]
  (let [seq (inc (:bot/sequence world))]
    (-> world
        (assoc :bot/sequence seq)
        (game/emit {:packet/name :use-item :hand 0 :sequence seq :y-rot yaw :x-rot pitch})
        (game/emit {:packet/name :swing :hand 0})
        (intent/assoc-intent :intent/sequence seq :intent/sent-at (now world)))))

(defmethod scoop-stage :aim
  [world {:intent/keys [target since still] :or {still 0}}]
  (let [look (physics/look-at (game/eye world) (aim-point target))
        still (if (intent/still? world) (inc still) 0)
        world (assoc world :player/controls {:control/look look})]
    (cond (not (surface-source? world target)) (intent/fail world :target-gone)
          (and (>= still 3) (> (- (now world) since) settle-ms))
          (-> world
              (use-item look)
              (intent/assoc-intent :intent/stage :sent))
          :else (intent/assoc-intent world :intent/still still))))

(defmethod scoop-stage :sent
  [world {:intent/keys [before sent-at tries] :or {tries 1}}]
  (cond (> (inventory/item-count world water-bucket) before) (intent/done world)
        (<= (- (now world) sent-at) answer-ms) world
        (>= tries max-tries) (intent/fail world :no-scoop)
        :else
        (intent/assoc-intent world
                             :intent/stage :aim
                             :intent/since (now world)
                             :intent/tries (inc tries))))

(defmethod intent/run :scoop
  [world i _event]
  (let [i (cond-> i
            (nil? (:intent/stage i))
            (assoc :intent/stage :equip
                   :intent/since (now world)
                   :intent/before (inventory/item-count world water-bucket)))
        world (assoc world :plan/intent i)]
    (case (window/waiting world i)
      :overdue (intent/fail world :stale-window)
      :waiting world
      (scoop-stage world i))))
