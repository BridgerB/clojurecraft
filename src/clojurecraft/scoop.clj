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
            [clojurecraft.packet :as packet]
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
(def tunnel-out-length 8)             ; level steps cut out of a pit no route leaves, before looking again

(defn- now [world] (:time/now world))

(def water-bucket (recipe/item-id :water_bucket))

(def fluids
  "What a scoop of each fluid takes and gives: the source state and the filled bucket. Water is
   the default; a build refills lava from a station (#8)."
  {:water {:source blocks/water-base :bucket water-bucket}
   :lava {:source blocks/lava-base :bucket (recipe/item-id :lava_bucket)}})

;; ---------------------------------------------------------------- where the water is

(defn scoopable?
  "Is target still a source of fluid with air over it, a surface the eyes look down into?"
  [world [x y z :as target] fluid]
  (let [above (terrain/block-at world [x (inc y) z])]
    (and (= (get-in fluids [fluid :source]) (terrain/block-at world target))
         (some? above)
         (not (blocks/solid? above))
         (nil? (blocks/fluid above)))))

(defn surface-source?
  "Is pos a water source block (plain water at level 0, not a waterlogged block) open to the
   air above it, a surface the eyes can look down into? A source under stone is a pocket in an
   aquifer wall: the live run that scooped at three of them got nothing from any."
  [world pos]
  (scoopable? world pos :water))

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

(defn surface-water?
  "Is the source at src surface water, not cave water: no more than memory/surface-water-below
   under the eye, or deeper but under the open sky (terrain/under-sky?). Gym batch solid-b1 run
   9 stood on a hill at y 70 over ponds at y 62, and the depth rule alone refused every one."
  [world [_ y _ :as src] eye-y]
  (or (>= y (- eye-y memory/surface-water-below)) (terrain/under-sky? world src)))

(defn sources
  "The surface water sources in the loaded terrain within search-radius of the eye
   (surface-water?), not blacklisted, nearest first. Asked of the terrain through the block
   overlay, so a source just scooped is air."
  [world]
  (let [eye (game/eye world)
        blacklist (:plan/blacklist world #{})]
    (memory/nearest-first eye
                          search-radius
                          (->> (chunk/find-blocks (stone/near-chunks world) #{blocks/water-base})
                               (map (fn [[x y z _]] [x y z]))
                               (remove blacklist)
                               (filter #(surface-source? world %))
                               (filter #(surface-water? world % (physics/y-of eye)))))))

(defn pools-near
  "The remembered water pools within pool-radius, not blacklisted, nearest first, leaving out one
   in a loaded chunk that is not surface water to scoop (surface-source? and surface-water?:
   memory's pool is the highest source with no water above it, which may be under stone or deep
   in a cave): what is loaded near it, the terrain search sees."
  [world]
  (let [eye (game/eye world)
        blacklist (:plan/blacklist world #{})]
    (memory/nearest-first eye
                          pool-radius
                          (->> (vals (memory/pools world :water))
                               (remove blacklist)
                               (remove
                                #(and (terrain/chunk-loaded? world %)
                                      (not (and (surface-source? world %)
                                                (surface-water? world % (physics/y-of eye))))))))))

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
        ey (physics/y-of (game/eye world))
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
(def underground-depth 8)             ; feet this far under the landing are underground: climb out before a hunt

(defn first-walk
  "A walk tagged for to the first of targets (the max-candidates first that it has not already
   arrived at) a route reaches (intent/routable?), or nil. A walk already arrived would end
   before it began, and start again every tick (gym batch water-a3 run 5 did, for four minutes,
   at a remembered pool under stone)."
  [world for targets]
  (->> targets
       (map (fn [t] {:intent/kind :walk :intent/target t :intent/for for}))
       (remove #(intent/arrived? world
                                 %
                                 (physics/distance (game/eye world)
                                                   (physics/centre (:intent/target %)))))
       (take max-candidates)
       (filter #(intent/routable? world %))
       first))

(defn climb
  "Out of the ground when the feet are more than underground-depth under the landing
   (:plan/go-at) and the sky is not open over them: a walk back to the landing when a route
   reaches it, else a walk up the air already dug (intent/opening), else stairs cut up to its
   height; nil when not underground. A bot that needs water deep in its mine sees none and its
   hunt looks for ground near its own height, underground: gym batch iron-a1 runs 3 and 5 got the
   bucket down there and failed :no-water. The way it came down is often no way back up: batch
   iron-a2 runs 2 and 5 had mined an ore out of the floor of a stair, or dug a niche under one,
   and waited :no-water at y 18 and 24 with no route out. The siblings climbed out first too."
  [world]
  (let [home (:plan/go-at world)
        feet (intent/feet world)]
    (when (and home
               (< (physics/y-of feet) (- (physics/y-of home) underground-depth))
               (not (terrain/under-sky? world feet)))
      (or (first-walk world :surface [(mapv #(long (math/floor %)) home)])
          (intent/opening world)
          {:intent/kind :stairs-up
           :intent/to-y (long (math/floor (physics/y-of home)))
           :intent/for :surface}))))

(defn dig-out
  "Out of the ground when the sky is not open over the feet, whatever the height: walk up the
   air already dug (intent/opening), else stairs cut up until out (stairs/out? ends them first;
   terrain/top-y is the top of the world). nil under the open sky. The last resort before
   waiting: live run 17 climbed out of its mine to the landing's height, which 145 blocks east
   was inside a hill, found no route to a pond 22 blocks off nor to any hunt, and waited
   :no-water. Full run 4528031 cut that stair from y 17 with the only pickaxe and it broke."
  [world]
  (when-not (terrain/under-sky? world (intent/feet world))
    (or (intent/opening world)
        {:intent/kind :stairs-up :intent/to-y terrain/top-y :intent/for :surface})))

(defn tunnel-out
  "A tunnel of tunnel-out-length level steps at the feet's height along stone/tunnel-heading (one
   it can cut, away from the landing), for a hunt that has no route anywhere; nil when no
   heading can be cut. Gym batch solid-b1 run 13 came up its stairs into a pit under the open
   sky, leaves and rock round it with no room to jump out, and waited :no-water."
  [world]
  (when-let [d (stone/tunnel-heading world)]
    {:intent/kind :tunnel :intent/dir d :intent/length tunnel-out-length :intent/for :water-hunt}))

(defn scoop-next
  "The next step toward a filled bucket: scoop the nearest surface source in reach and in sight,
   else walk to the bank of the nearest one a route reaches, or to such a remembered pool, or climb
   out (to the landing, or by stairs cut up) when deep under it, or hunt for one (hunt-targets),
   or cut out of the ground (dig-out), or tunnel out of a pit (tunnel-out); else wait
   :no-water."
  [world]
  (let [srcs (sources world)
        src (first (filter #(and (in-reach? world %) (sees? world %)) srcs))]
    (cond src {:intent/kind :scoop :intent/target src}
          :else
          (or (first-walk world :scoop srcs)
              (first-walk world :scoop (pools-near world))
              (climb world)
              (first-walk world :water-hunt (hunt-targets world))
              (dig-out world)
              (tunnel-out world)
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
        (game/emit {:packet/name :use-item
                    :hand (packet/value :hand :main-hand)
                    :sequence seq
                    :y-rot yaw
                    :x-rot pitch})
        (game/emit {:packet/name :swing :hand (packet/value :hand :main-hand)})
        (intent/assoc-intent :intent/sequence seq :intent/sent-at (now world)))))

(defmethod scoop-stage :aim
  [world {:intent/keys [target since still fluid] :or {still 0 fluid :water}}]
  (let [look (physics/look-at (game/eye world) (aim-point target))
        still (if (intent/still? world) (inc still) 0)
        world (assoc world :player/controls {:control/look look})]
    (cond (not (scoopable? world target fluid)) (intent/fail world :target-gone)
          (and (>= still 3) (> (- (now world) since) settle-ms))
          (-> world
              (use-item look)
              (intent/assoc-intent :intent/stage :sent))
          :else (intent/assoc-intent world :intent/still still))))

(defmethod scoop-stage :sent
  [world {:intent/keys [before sent-at tries fluid] :or {tries 1 fluid :water}}]
  (cond (> (inventory/item-count world (get-in fluids [fluid :bucket])) before) (intent/done world)
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
                   :intent/before
                   (inventory/item-count world (get-in fluids [(:intent/fluid i :water) :bucket]))))
        world (assoc world :plan/intent i)]
    (case (window/waiting world i)
      :overdue (intent/fail world :stale-window)
      :waiting world
      (scoop-stage world i))))
