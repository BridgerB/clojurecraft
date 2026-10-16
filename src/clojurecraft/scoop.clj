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
            [clojurecraft.stairs :as stairs]
            [clojurecraft.stone :as stone]
            [clojurecraft.terrain :as terrain]
            [clojurecraft.window :as window]))

(set! *warn-on-reflection* true)

(def search-radius 24)                ; blocks from the eye searched for water in the loaded terrain
(def pool-radius 96)                  ; blocks from the eye a remembered pool is walked to from
(def far-pool-radius 256)             ; blocks from the eye a remembered pool still steers a hunt toward it
(def settle-ms 300)                   ; standing still this long, looking at the source, before using the bucket
(def answer-ms 3000)                  ; ms a scoop waits for the water bucket before it is tried again
(def max-tries 3)                     ; scoops sent at one source before it fails :no-scoop
(def surface-height 0.85)             ; where a source's surface is aimed at: a source stands 8/9 of a block
(def ray-step 0.05)                   ; the length of one step along the eyes' ray to a source
(def ray-margin 0.03)                 ; how near a block's edge the game's exact ray counts as touching it
(def aim-offset 0.25)                 ; how far off a source's centre the eyes may aim when the centre's ray is not clear
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
  "Is target still a source of fluid with air over it (blocks/air?: not stone, not a plant), a
   surface the eyes look down into? Race 38477665442 (issue #83): seven bots at beaches sent
   three scoops each at sources the server acked and never filled; sugar cane grows on the sand
   by the water, and the game's ray stops at its outline as at a wall."
  [world [x y z :as target] fluid]
  (let [above (terrain/block-at world [x (inc y) z])]
    (and (= (get-in fluids [fluid :source]) (terrain/block-at world target))
         (some? above)
         (blocks/air? above))))

(defn source?
  "Is target still a source of fluid, whatever is over it? A scoop aimed through a cell's open
   side (:intent/aim, the cast's water over a hole, issue #124) needs no air above it: its ray
   enters from the side."
  [world target fluid]
  (= (get-in fluids [fluid :source]) (terrain/block-at world target)))

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

(defn ray-cells
  "The cells the ray from the eye to point passes through or within ray-margin of, sampled every
   ray-step, but the cell point is in. The game casts a bucket's ray exactly, so a ray that grazes
   a block's edge stops on it where samples of the line alone step past it: race 38759618225
   runner 4 (issue #83, at 76010d2) had bots Clj_r15, Clj_r38, Clj_r61 and Clj_r78 hold the
   bucket and send nine scoops each whose line passed within 0.03 of a block's edge (sand by a
   pond, dirt and sand under a lake, stone and granite in a cave); the server's ray stopped on
   it and filled nothing, while the model's sampled ray filled the bucket, so only real runs
   failed."
  [world point]
  (let [eye (game/eye world)
        d (physics/distance eye point)
        dir (mapv #(/ (- %2 %1) d) eye point)
        end (mapv #(long (math/floor %)) point)
        m ray-margin]
    (->> (for [k (range 0 (/ d ray-step))
               :let [p (mapv #(+ %1 (* k ray-step %2)) eye dir)]
               dx [(- m) m]
               dy [(- m) m]
               dz [(- m) m]]
           (mapv #(long (math/floor (+ %1 %2))) p [dx dy dz]))
         (remove #(= end %))
         distinct)))

(defn clear?
  "Is every cell of the ray from the eye to point (ray-cells) loaded and air or water (the
   game's ray stops at any block with an outline, a grass tuft on the bank as much as a wall; a
   water cell it passes, or fills the bucket from, which is as good)?"
  [world point]
  (every? #(when-let [id (terrain/block-at world %)]
             (or (blocks/air? id) (contains? blocks/water-states id)))
          (ray-cells world point)))

(defn surface-points
  "The points on the surface of the source at [x y z] the eyes may aim at: its aim-point, then
   the points aim-offset off it along x, z and the diagonals."
  [[x y z]]
  (for [[dx dz] [[0 0] [aim-offset 0] [(- aim-offset) 0] [0 aim-offset] [0 (- aim-offset)]
                 [aim-offset aim-offset] [aim-offset (- aim-offset)] [(- aim-offset) aim-offset]
                 [(- aim-offset) (- aim-offset)]]]
    [(+ x 0.5 dx) (+ y surface-height) (+ z 0.5 dz)]))

(defn surface-aim
  "Where on the surface of the water source at src the eyes can aim (clear?): the first of
   surface-points; nil when none is clear. Issue #83: a bot standing at a corner of its cell
   looked at the surface's centre along a line through block edges."
  [world src]
  (first (filter #(clear? world %) (surface-points src))))

(defn ray-walls
  "The cells of the ray from the eye to point (ray-cells) a bucket's ray stops at: not loaded, or
   neither air nor a fluid (the ray passes every fluid that is not a source), nearest first. The
   lava refill's line: race 38839564087 runner 1 (issue #225, at 3f836ff) had bots Clj_r12, Clj_r19
   and Clj_r21 send nine scoops each at a lava source from the station, every line within 0.03 of
   an edge of deepslate or granite that its samples stepped past, and the build failed :no-scoop."
  [world point]
  (remove #(when-let [id (terrain/block-at world %)] (or (blocks/air? id) (blocks/fluid id)))
          (ray-cells world point)))

(defn sees?
  "Would the eyes reach the surface of the water source at src: some point of it aimed at
   (surface-aim) with a clear ray?"
  [world src]
  (some? (surface-aim world src)))

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
                          (->> (chunk/find-blocks (stone/near-chunks (:world/chunks world)
                                                                     (stone/feet-chunk world))
                                                  #{blocks/water-base})
                               (map (fn [[x y z _]] [x y z]))
                               (remove blacklist)
                               (filter #(surface-source? world %))
                               (filter #(surface-water? world % (physics/y-of eye)))))))

(defn pools-near
  "The remembered water pools within pool-radius, not blacklisted, nearest first, leaving out one
   in a loaded chunk that is not surface water to scoop (surface-source? and surface-water?:
   memory's pool is the highest source with no water above it, which may be under stone or deep
   in a cave): what is loaded near it, the terrain search sees. With radius, those within it."
  ([world] (pools-near world pool-radius))
  ([world radius]
   (let [eye (game/eye world)
         blacklist (:plan/blacklist world #{})]
     (memory/nearest-first
      eye
      radius
      (->> (vals (memory/pools world :water))
           (remove blacklist)
           (remove #(and (terrain/chunk-loaded? world %)
                         (not (and (surface-source? world %)
                                   (surface-water? world % (physics/y-of eye)))))))))))

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
   that has some under the open sky (terrain/under-sky?: a floor in a cave is no place to look
   for surface water from, and the climb out of it leads back to the pit the hunt left; race
   38477665442 bot Clj_r15 went between the two for an hour), lowest first (water lies low),
   leaving out any nearer the landing (:plan/go-at) than the bot is now, so a hunt goes out
   and never doubles back.
   With homeward? true the targets nearer the landing are kept after the outward ones, ranked
   last: a hunt that knows what it is looking for (the wood hunt sorts its targets toward the
   nearest remembered log) may walk back a hop it regretted — race 38680745815 runner 5 (issue
   #57) bot Clj_r10's first hop left the landing with no log remembered, and once the valley's
   trunks were remembered below it, every bearing back toward them was refused as nearer the
   landing, the one bearing left had no route, and the plan waited :no-log out its clock."
  ([world] (hunt-targets world false))
  ([world homeward?]
   (let [[px _ pz] (:player/pos world)
         ey (physics/y-of (game/eye world))
         home (:plan/go-at world)
         out? (fn [[x _ z]]
                (or (nil? home)
                    (>= (physics/horizontal-distance [x 0 z] home)
                        (physics/horizontal-distance [px 0 pz] home))))
         found (->> bearings
                    (keep (fn [[dx dz]]
                            (ground world
                                    [(long (math/floor (+ px (* dx hunt-distance))))
                                     (long (math/floor (+ pz (* dz hunt-distance))))]
                                    ey)))
                    (filter #(terrain/under-sky? world %))
                    (remove (:plan/blacklist world #{})))
         {outward true inward false} (group-by out? found)]
     (concat (sort-by second outward) (when homeward? (sort-by second inward))))))

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

(defn hunt
  "A water hunt's walk (:intent/for :water-hunt) to the first of hunt-targets a route reaches
   (first-walk). With a surface pool remembered within far-pool-radius (pools-near: past the
   reach of a walk to it, or with no route there), a hunt that knows where the water is: of
   every target, homeward ones too, those that land nearer that pool than the feet are now,
   nearest it first, so each hop closes on it and none can undo the last. Sim race 38806318231
   runner 1 (issue #60, every seed of world R2/19 at 76010d2, 75 runs in 16 batches) mined
   180 blocks from its landing, then hunted outward and downhill, west, away from surface
   pools 100 blocks east it had remembered, to the captured world's edge, and waited :no-water
   there; sim race 38811426215 runner 8 (world R4/29) climbed out of its mine in the world's
   corner with pools 150 blocks toward its landing and no outward bearing left. nil when no
   such target has a route."
  [world]
  (let [[px _ pz] (:player/pos world)]
    (if-let [pool (first (pools-near world far-pool-radius))]
      (first-walk world
                  :water-hunt
                  (->> (hunt-targets world true)
                       (filter #(< (physics/horizontal-distance % pool)
                                   (physics/horizontal-distance [px 0 pz] pool)))
                       (sort-by #(physics/horizontal-distance % pool))))
      (first-walk world :water-hunt (hunt-targets world)))))

(defn deep?
  "Are the feet more than underground-depth under the landing (:plan/go-at)? false with no
   landing."
  [world]
  (let [home (:plan/go-at world)]
    (boolean
     (and home (< (physics/y-of (intent/feet world)) (- (physics/y-of home) underground-depth))))))

(defn boxed-here?
  "Did a climb out die :boxed standing where the feet are (:plan/boxed-at, stairs/box)? A climb
   from there is cut no more."
  [world]
  (contains? (:plan/boxed-at world #{}) (intent/feet world)))

(defn way-up
  "The way back up to the landing (:plan/go-at): a walk to it when a route reaches it, else a
   walk up the air already dug (intent/opening), else stairs cut up to its height, which with
   to-height? end there alone, not at the first open sky."
  [world to-height?]
  (let [home (:plan/go-at world)]
    (or (first-walk world :surface [(mapv #(long (math/floor %)) home)])
        (intent/opening world)
        (cond-> {:intent/kind :stairs-up
                 :intent/to-y (stairs/climb-to world (long (math/floor (physics/y-of home))))
                 :intent/for :surface}
          to-height? (assoc :intent/to-height? true)))))

(defn climb
  "Out of the ground when the feet are deep under the landing (deep?) and the sky is not open
   over them: the way-up; nil when not underground. A bot that needs water deep in its mine
   sees none and its hunt looks for ground near its own height, underground: gym batch iron-a1
   runs 3 and 5 got the bucket down there and failed :no-water. The way it came down is often
   no way back up: batch iron-a2 runs 2 and 5 had mined an ore out of the floor of a stair, or
   dug a niche under one, and waited :no-water at y 18 and 24 with no route out. The siblings
   climbed out first too."
  [world]
  (when (and (deep? world)
             (not (terrain/under-sky? world (intent/feet world)))
             (not (boxed-here? world)))
    (way-up world false)))

(defn pit-climb
  "Out of a pit under the open sky when the feet are deep under the landing (deep?) and no
   hunt has a route: the way-up, its stairs ending at the landing's height alone (the open sky
   over the pit's floor is not out). Race 38477665442 (issue #60): four bots stood at y 6 at
   the bottom of one ravine, sixty under their landing, the sky open over them, no hunt with a
   route and nothing to tunnel toward, and waited :no-water out. nil when not deep."
  [world]
  (when (and (deep? world) (not (boxed-here? world))) (way-up world true)))

(defn heading-to
  "Of the four headings (stairs/directions), the one from the feet most toward cell [x _ z]."
  [world [x _ z]]
  (let [[fx _ fz] (intent/feet world)]
    (apply max-key (fn [[dx dz]] (+ (* dx (- x fx)) (* dz (- z fz)))) stairs/directions)))

(defn centre-aim
  "Where in the source at src the eyes can aim through its open side (clear?): its centre, else
   the first point aim-offset off it along an axis; nil when none is clear (issue #83)."
  [world src]
  (let [c (physics/centre src)]
    (first (filter #(clear? world %)
                   (cons c
                         (for [axis [0 1 2] o [aim-offset (- aim-offset)]] (update c axis + o)))))))

(defn seen-source
  "A scoop of the nearest water source in reach, not blacklisted, that the eyes see into
   (centre-aim), what is over it notwithstanding, aimed there (:intent/aim, as the cast scoops
   its water from the side): deep under the landing, where no surface water is, the water of a
   flooded pocket or a cave beside the feet. nil when none. It read no blacklist: race
   38759618225 runner 4 (issue #83) had three of its bots scoop at one source three intents in a
   row, each failing :no-scoop and blacklisting it, until the plan failed."
  [world]
  (when (deep? world)
    (let [eye (game/eye world)]
      (->> (chunk/find-blocks (stone/near-chunks (:world/chunks world) (stone/feet-chunk world))
                              #{blocks/water-base})
           (map (fn [[x y z _]] [x y z]))
           (remove (:plan/blacklist world #{}))
           (filter #(and (in-reach? world %) (source? world % :water)))
           (sort-by #(physics/distance eye (physics/centre %)))
           (keep (fn [src]
                   (when-let [aim (centre-aim world src)]
                     {:intent/kind :scoop :intent/target src :intent/aim aim})))
           first))))

(def bed-depth 2)                      ; rock over the head a scoop from below digs through at most

(defn overhead
  "The water source over the feet's column, under at most bed-depth blocks of rock over the head
   and within the eyes' reach, flowing water between the rock and it passed, not blacklisted:
   {:source :rock}, the rock bottom first. nil when there is none."
  [world]
  (let [[x y z] (intent/feet world)
        cells (map (fn [dy] [x (+ y dy) z]) (range 2 8))
        [rock more] (split-with #(= :solid (path/classify world %)) cells)
        src (first (drop-while #(and (= :water (path/classify world %))
                                     (not (source? world % :water)))
                               more))]
    (when (and src
               (source? world src :water)
               (not (contains? (:plan/blacklist world #{}) src))
               (<= (count rock) bed-depth)
               (in-reach? world src)
               (not-any? #(contains? intent/never-dug (blocks/name-of (terrain/block-at world %)))
                         rock))
      {:source src :rock (vec rock)})))

(defn from-below
  "Deep under the landing with a lake or an aquifer over the head (overhead): dig up through its
   bed, the rock under the water bottom first (a dig that may let the water in, exposes-ok?),
   then scoop the source from below, aimed at the underside of its cell (:intent/aim): the
   bucket's ray passes the water falling in and takes the first source. nil when there is no
   water over the head. Sim race 38662908438 (issue #61, 115 runs): climbs for water from under
   a lake or the sea, no shore within 48 columns, stepped and dug until :boxed, a room of 150
   cells at y 43 dug under a lake bed two blocks thick, the water it needed over its head."
  [world]
  (when (deep? world)
    (when-let [{:keys [source rock]} (overhead world)]
      (if-let [r (first rock)]
        (assoc (intent/child-dig r) :intent/exposes-ok? true :intent/for :water-from-below)
        (let [[sx sy sz] source]
          {:intent/kind :scoop
           :intent/target source
           :intent/aim [(+ sx 0.5) (+ sy 0.05) (+ sz 0.5)]})))))

(defn to-bank
  "Stairs cut toward the bank of the nearest water seen that no route reaches (a surface source
   in sight or a remembered pool: sources, pools-near), the cell over it: up to its height when
   it is higher than the feet (to-height?: the open sky on the way is not the bank), down toward
   it when lower, along the heading most toward it, to its height, and at its height a tunnel
   along that heading; each climb-stairs, stair-depth or tunnel-out-length at a time, so the
   planner looks again between lengths. nil when no water is seen. Sim race 38657453715 (issue #60): bots stood in a trench of their
   own digging two below a pond's surface, the water in reach but over the eyes; on ravine
   floors at y 8 and y 32 under pools remembered at y 62; and on a mountain ledge at y 80 over
   pools at y 62 with no route down; and at y 63, forty blocks from pools at y 62, under a
   lip of rock. Every hunt and walk had no route, and each waited :no-water.
   The cut names the water it heads for (:intent/target): a cut that is :boxed gives that water
   up (stairs/box) and the next look chooses other water, or none and a climb; so does a tunnel
   that ends no nearer it than it began (:intent/from-feet, stairs/no-nearer?)."
  [world]
  (let [fy (physics/y-of (intent/feet world))]
    (when-let [[_ y _ :as w] (first (concat (sources world) (pools-near world)))]
      (let [bank (inc y)]
        (assoc (cond (> bank fy)
                     {:intent/kind :stairs-up
                      :intent/to-y (stairs/climb-to world bank)
                      :intent/to-height? true
                      :intent/for :bank}
                     (< bank fy)
                     {:intent/kind :stairs-down
                      :intent/to-y (max bank (- fy stone/stair-depth))
                      :intent/dir (heading-to world w)
                      :intent/for :bank}
                     :else
                     {:intent/kind :tunnel
                      :intent/dir (heading-to world w)
                      :intent/length tunnel-out-length
                      :intent/for :bank
                      :intent/from-feet (intent/feet world)})
               :intent/target
               w)))))

(defn dig-out
  "Out of the ground when the sky is not open over the feet, whatever the height: walk up the
   air already dug (intent/opening), else stairs cut up until out (stairs/out? ends them first;
   terrain/top-y is the top of the world), climb-stairs at a time (stairs/climb-to), so the
   planner looks again between lengths. nil under the open sky. The last resort before
   waiting: live run 17 climbed out of its mine to the landing's height, which 145 blocks east
   was inside a hill, found no route to a pond 22 blocks off nor to any hunt, and waited
   :no-water. Full run 4528031 cut that stair from y 17 with the only pickaxe and it broke."
  [world]
  (when-not (or (terrain/under-sky? world (intent/feet world)) (boxed-here? world))
    (or (intent/opening world)
        {:intent/kind :stairs-up
         :intent/to-y (stairs/climb-to world terrain/top-y)
         :intent/for :surface})))

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
   toward the nearest remembered pool when one is known (hunt), or climb out of a pit under the
   sky when deep under the landing (pit-climb), or up or down
   to the bank of water seen that no route reaches (to-bank), or cut out
   of the ground (dig-out), or tunnel out of a pit (tunnel-out); else wait
   :no-water. The climbs out (climb, pit-climb) are for water not seen: with water seen they
   give way to the cut toward its bank, which they would undo a stair at a time. Sim race
   38669750355 (issue #69) landed on hills at y 100 and y 73, cut a stair down toward a pond, was
   then deep under the landing with rock over the head, climbed back up its opening, and did it
   again, sixteen hundred times in ninety minutes."
  [world]
  (let [srcs (sources world)
        src (first (filter #(and (in-reach? world %) (sees? world %)) srcs))
        bank (delay (to-bank world))]
    (cond src {:intent/kind :scoop :intent/target src}
          :else
          (or (first-walk world :scoop srcs)
              (first-walk world :scoop (pools-near world))
              (seen-source world)
              (from-below world)
              (when-not @bank (climb world))
              (hunt world)
              (when-not @bank (pit-climb world))
              @bank
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
  ;; an explicit :intent/aim is a scoop through the cell's open side: the source must still be
  ;; there, with anything over it; else the surface from above, air over it (scoopable?)
  [world {:intent/keys [target since still fluid aim] :or {still 0 fluid :water}}]
  (let [look (physics/look-at (game/eye world)
                              (or aim (surface-aim world target) (aim-point target)))
        still (if (intent/still? world) (inc still) 0)
        world (assoc world :player/controls {:control/look look})
        there? (if aim (source? world target fluid) (scoopable? world target fluid))]
    (cond (not there?) (intent/fail world :target-gone)
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
      :overdue (window/lost world i :equip)
      :waiting world
      (if (:intent/resync i)
        (window/resync world (window/view world :inventory))
        (scoop-stage world i)))))
