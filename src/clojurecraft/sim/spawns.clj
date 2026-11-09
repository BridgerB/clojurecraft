(ns clojurecraft.sim.spawns
  "The mobs' tick in the model: the night rule's spawns and despawns, the Nether's own spawns (a warped forest, a fortress floor, a crimson floor, the blaze spawner), the animals a chunk is given when it loads, and tick-mobs, which runs those and then every mob's turn (sim.mobs/act-mob)."
  (:require [clojure.math :as math]
            [clojurecraft.blocks :as blocks]
            [clojurecraft.chunk :as chunk]
            [clojurecraft.dimension :as dimension]
            [clojurecraft.mob :as mob]
            [clojurecraft.physics :as physics]
            [clojurecraft.sim.mobs :as sim-mobs :refer
             [act-mob add-mob column-top face-player free-fn mob-roll overworld? populate-radius
              remove-mob wild-roll]]
            [clojurecraft.sim.world :as sim-world :refer [block-at column-key column-of]]))

(set! *warn-on-reflection* true)

(defn spawning?
  "Does the night rule spawn hostiles: a difficulty above peaceful, in the overworld?"
  [sim]
  (boolean (and (#{:easy :normal :hard} (:sim/difficulty sim)) (overworld? sim))))

(defn hostiles-near
  "How many living hostiles are within mob/despawn-far of the player (the cap's count)."
  [sim]
  (let [player (:sim/player-pos sim)]
    (count (filter (fn [[_ {:keys [type pos dead-at]}]]
                     (and (:hostile (mob/kinds type))
                          (not dead-at)
                          (<= (physics/distance pos player) mob/despawn-far)))
                   (:sim/mobs sim)))))

(defn try-spawn
  "Every mob/spawn-every ticks, one try at a hostile (spawning?, under mob/hostile-cap): a column
   rolled in the ring mob/spawn-near to mob/spawn-far round the player, its dark spots within
   mob/spawn-band of the player's feet (mob/spawn-spots: roofed, or any at night), one of them
   rolled, and a kind rolled by weight (mob/hostile-kinds)."
  [sim]
  (let [player (:sim/player-pos sim)]
    (cond (not (and player
                    (spawning? sim)
                    (zero? (mod (quot (:sim/now sim) physics/tick-ms) mob/spawn-every))
                    (< (hostiles-near sim) mob/hostile-cap)))
          sim
          :else
          (let [[sim r1] (mob-roll sim)
                [sim r2] (mob-roll sim)
                angle (* 2.0 math/PI r1)
                dist (+ mob/spawn-near (* r2 (- mob/spawn-far mob/spawn-near)))
                [px py pz] player
                x (long (math/floor (+ px (* dist (math/cos angle)))))
                z (long (math/floor (+ pz (* dist (math/sin angle)))))
                fy (long (math/floor py))
                spots (vec (mob/spawn-spots #(block-at sim %)
                                            (free-fn sim)
                                            x
                                            z
                                            (- fy mob/spawn-band)
                                            (+ fy mob/spawn-band)
                                            (column-top sim)
                                            (mob/night? (:sim/day-time sim 0))))]
            (cond (empty? spots) sim
                  :else
                  (let [[sim r3] (mob-roll sim)
                        [sim r4] (mob-roll sim)
                        [sx sy sz] (spots (min (dec (count spots)) (long (* r3 (count spots)))))]
                    (add-mob sim
                             (mob/pick mob/hostile-kinds r4)
                             [(+ sx 0.5) (double sy) (+ sz 0.5)]
                             {})))))))

(defn despawn
  "Hostiles not persistent: removed at once beyond mob/despawn-far of the player, and beyond
   mob/despawn-near by a roll of mob/despawn-chance each tick (Mob.checkDespawn)."
  [sim]
  (let [player (:sim/player-pos sim)]
    (reduce (fn [sim eid]
              (let [{:keys [type pos persistent? dead-at]} (get-in sim [:sim/mobs eid])
                    d (physics/distance pos player)]
                (cond (or persistent? dead-at (not (:hostile (mob/kinds type)))) sim
                      (> d mob/despawn-far) (remove-mob sim eid)
                      (> d mob/despawn-near)
                      (let [[sim r] (mob-roll sim)]
                        (cond-> sim (< r mob/despawn-chance) (remove-mob eid)))
                      :else sim)))
            sim
            (if player (sort (keys (:sim/mobs sim))) []))))

(defn in-nether?
  "Is the player in the Nether, where a fortress's spawners run?"
  [sim]
  (= dimension/nether (:sim/dimension sim)))

(defn wild-spawning?
  "Does the Nether's own spawning run: a difficulty above peaceful, in the Nether? Vanilla
   spawns the Nether's hostiles by biome; the model spawns a warped forest's (mob/warped-kinds)."
  [sim]
  (boolean (and (#{:easy :normal :hard} (:sim/difficulty sim)) (in-nether? sim))))

(defn spawn-pack
  "The model with a pack of n of kind walked out from [x y z]: each member mob/pack-step off
   the last on x and on z (four wild-rolls), at the pack's height, placed where it stands (spot?
   of the cell, mob/nether-spot? when none is given, and no nearer the player than mob/spawn-near)
   and skipped where it does not, as NaturalSpawner.spawnCategoryForPosition walks and skips them."
  ([sim kind pos n] (spawn-pack sim kind pos n mob/nether-spot?))
  ([sim kind [x y z] n spot?]
   (let [player (:sim/player-pos sim)
         at #(block-at sim %)
         free? (free-fn sim)]
     (first (reduce (fn [[sim x z] _]
                      (let [[sim a] (wild-roll sim)
                            [sim b] (wild-roll sim)
                            [sim c] (wild-roll sim)
                            [sim d] (wild-roll sim)
                            x (+ x (mob/pack-step a b))
                            z (+ z (mob/pack-step c d))
                            pos [(+ x 0.5) (double y) (+ z 0.5)]]
                        [(cond-> sim
                           (and (spot? at free? [x y z])
                                (>= (physics/distance pos player) mob/spawn-near))
                           (add-mob kind pos {})) x z]))
                    [sim x z]
                    (range n))))))

(defn try-nether-spawn
  "Every mob/spawn-every ticks in the Nether (wild-spawning?, under mob/hostile-cap), one try
   at a warped forest's pack: a column rolled in the ring mob/spawn-near to mob/spawn-far round
   the player as try-spawn rolls one, its spots within mob/spawn-band of the player's feet
   (mob/nether-spots: warped nylium under the feet, an enderman's three cells free, any light),
   one of them rolled, a kind rolled by weight (mob/warped-kinds) and a pack of
   mob/enderman-pack walked out from it (spawn-pack). A column with no warped floor spawns
   nothing: the Nether's other biomes' hostiles are not modelled. Every roll is a wild-roll,
   so the overworld's night rule rolls as it did, and a fortress's spawners too until an
   enderman spawns (its turns roll from mob-roll)."
  [sim]
  (let [player (:sim/player-pos sim)]
    (cond (not (and player
                    (wild-spawning? sim)
                    (zero? (mod (quot (:sim/now sim) physics/tick-ms) mob/spawn-every))
                    (< (hostiles-near sim) mob/hostile-cap)))
          sim
          :else
          (let [[sim r1] (wild-roll sim)
                [sim r2] (wild-roll sim)
                angle (* 2.0 math/PI r1)
                dist (+ mob/spawn-near (* r2 (- mob/spawn-far mob/spawn-near)))
                [px py pz] player
                x (long (math/floor (+ px (* dist (math/cos angle)))))
                z (long (math/floor (+ pz (* dist (math/sin angle)))))
                fy (long (math/floor py))
                spots (vec (mob/nether-spots #(block-at sim %)
                                             (free-fn sim)
                                             x
                                             z
                                             (- fy mob/spawn-band)
                                             (+ fy mob/spawn-band)))]
            (cond (empty? spots) sim
                  :else
                  (let [[sim r3] (wild-roll sim)
                        [sim r4] (wild-roll sim)
                        spot (spots (min (dec (count spots)) (long (* r3 (count spots)))))]
                    (spawn-pack sim (mob/pick mob/warped-kinds r4) spot mob/enderman-pack)))))))

(defn try-floor-spawn
  "One try, on try-nether-spawn's cadence and cap, at a pack of n from kinds on the floors
   spot? accepts: the same ring and the same wild-rolls, and nothing where the column has no
   such floor. The overworld rolls none of it (wild-spawning?)."
  [sim spot? kinds n]
  (let [player (:sim/player-pos sim)]
    (cond (not (and player
                    (wild-spawning? sim)
                    (zero? (mod (quot (:sim/now sim) physics/tick-ms) mob/spawn-every))
                    (< (hostiles-near sim) mob/hostile-cap)))
          sim
          :else
          (let [[sim r1] (wild-roll sim)
                [sim r2] (wild-roll sim)
                angle (* 2.0 math/PI r1)
                dist (+ mob/spawn-near (* r2 (- mob/spawn-far mob/spawn-near)))
                [px py pz] player
                x (long (math/floor (+ px (* dist (math/cos angle)))))
                z (long (math/floor (+ pz (* dist (math/sin angle)))))
                fy (long (math/floor py))
                at #(block-at sim %)
                spots (vec (for [y (range (- fy mob/spawn-band) (inc (+ fy mob/spawn-band)))
                                 :when (spot? at (free-fn sim) [x y z])]
                             [x y z]))]
            (cond (empty? spots) sim
                  :else
                  (let [[sim r3] (wild-roll sim)
                        [sim r4] (wild-roll sim)
                        spot (spots (min (dec (count spots)) (long (* r3 (count spots)))))]
                    (spawn-pack sim (mob/pick kinds r4) spot n spot?)))))))

(defn try-fortress-spawn
  "Every mob/spawn-every ticks in the Nether, one mob on fortress brick (mob/fortress-kinds:
   a wither skeleton or a magma cube, weights 8 and 3). A column with no brick spawns nothing."
  [sim]
  (try-floor-spawn sim mob/fortress-spot? mob/fortress-kinds 1))

(defn try-crimson-spawn
  "Every mob/spawn-every ticks in the Nether, one hoglin on crimson nylium (mob/crimson-kinds).
   A column with none spawns nothing. Piglins are not modelled."
  [sim]
  (try-floor-spawn sim mob/crimson-spot? mob/crimson-kinds 1))

(defn try-deltas-spawn
  "Every mob/spawn-every ticks in the Nether, a pack of mob/deltas-pack magma cubes of a size
   rolled (mob/deltas-kinds) on a basalt delta's floor (mob/deltas-spot?). A column with none
   spawns nothing."
  [sim]
  (try-floor-spawn sim mob/deltas-spot? mob/deltas-kinds mob/deltas-pack))

(defn spawner-cells
  "The set of cells holding a spawner block in the decoded columns within one chunk of the
   player's column (a spawner wakes within 16 blocks, never farther than the next column)."
  [sim]
  (let [[hx hz] (column-key (:sim/player-pos sim))
        near (into {}
                   (for [x (range (dec hx) (+ hx 2))
                         z (range (dec hz) (+ hz 2))
                         :let [c (column-of sim [x z])]
                         :when c]
                     [[x z] c]))]
    (set (for [[x y z] (chunk/find-blocks near #(= % mob/spawner-block))] [x y z]))))

(defn spawner-nearby
  "How many living blazes stand where the spawner at cell sp counts them (mob/spawner-crowded?)."
  [sim sp]
  (count (filter (fn [[_ {:keys [type pos dead-at]}]]
                   (and (= (:kind mob/spawner) type) (not dead-at) (mob/spawner-crowded? sp pos)))
                 (:sim/mobs sim))))

(defn spawn-batch
  "The model after the spawner at cell sp spawns: a batch rolled (mob/spawner-batch), cut to the
   room it has (mob/spawner-room). None when six of its kind already stand in the counting box:
   BaseSpawner.serverTick counts that class in AABB(pos, pos+1).inflate(spawnRange) and, at
   maxNearbyEntities (6), calls delay and returns, skipping the rest of the attempt. Each try
   takes five rolls of the mobs' stream, and a blaze is placed where one fits
   (mob/spawner-spawns). The caller rolls the next delay either way."
  [sim sp]
  (let [[sim rb] (mob-roll sim)
        tries (min (mob/spawner-batch rb) (mob/spawner-room (spawner-nearby sim sp)))
        [sim rolls] (reduce (fn [[sim acc] _] (let [[sim r] (mob-roll sim)] [sim (conj acc r)]))
                            [sim []]
                            (range (* 5 tries)))]
    (reduce (fn [sim [x y z]] (add-mob sim (:kind mob/spawner) [(+ x 0.5) (double y) (+ z 0.5)] {}))
            sim
            (mob/spawner-spawns (free-fn sim) sp tries (partition 5 rolls)))))

(defn due-at
  "The time in ms of the spawner's next spawn, a delay rolled (mob/spawner-delay) from now:
   [sim' ms]."
  [sim]
  (let [[sim r] (mob-roll sim)] [sim (+ (:sim/now sim) (* physics/tick-ms (mob/spawner-delay r)))]))

(defn tick-spawner
  "One tick of the spawner at cell sp: gone when its block is (broken); nothing with the player
   out of its range (mob/spawner-awake?); the first tick in range sets its delay; once the delay
   is over, a batch (spawn-batch) and the next delay."
  [sim sp]
  (let [due (get-in sim [:sim/spawners sp])]
    (cond (not= mob/spawner-block (block-at sim sp)) (update sim :sim/spawners dissoc sp)
          (not (mob/spawner-awake? sp (:sim/player-pos sim))) sim
          (nil? due) (let [[sim at] (due-at sim)] (assoc-in sim [:sim/spawners sp] at))
          (< (:sim/now sim) due) sim
          :else
          (let [[sim at] (due-at (spawn-batch sim sp))] (assoc-in sim [:sim/spawners sp] at)))))

(defn tick-spawners
  "A tick of the Nether's spawners, in a model with a difficulty above peaceful and a player in
   the Nether: the spawners round the player are found when it comes into another column
   (:sim/spawners {cell due-ms}, kept apart from the mobs' timers), then each ticks in order
   (tick-spawner)."
  [sim]
  (let [player (:sim/player-pos sim)
        here (when player (column-key player))]
    (cond (not (and player (#{:easy :normal :hard} (:sim/difficulty sim)) (in-nether? sim))) sim
          :else
          (let [sim (cond-> sim
                      (not= here (:sim/spawners-at sim))
                      (-> (assoc :sim/spawners-at here)
                          (update :sim/spawners
                                  #(merge (zipmap (spawner-cells sim) (repeat nil)) %))))]
            (reduce tick-spawner sim (sort (keys (:sim/spawners sim))))))))

(defn grass-top
  "The feet height on the highest grass block of column x z with room to stand over it, from 32
   over y down to 32 under it; nil when there is none."
  [sim x z y]
  (let [free? (free-fn sim)]
    (first (filter #(and (= :grass_block (blocks/name-of (block-at sim [x (dec %) z])))
                         (mob/stands? free? [x % z]))
                   (range (+ y 32) (- y 32) -1)))))

(defn populate-column
  "Chunk [cx cz] has its animals placed, once (:sim/populated): one time in ten (mob/animal-chance)
   a herd of one rolled kind on the grass at its middle (grass-top), as a server populates a
   chunk it generates."
  [sim [cx cz]]
  (let [sim (update sim :sim/populated (fnil conj #{}) [cx cz])
        [sim r] (mob-roll sim)]
    (cond (>= r mob/animal-chance) sim
          :else
          (let [[sim r2] (mob-roll sim)
                kind (nth mob/passive-kinds
                          (min (dec (count mob/passive-kinds))
                               (long (* r2 (count mob/passive-kinds)))))
                x (+ (* 16 cx) 8)
                z (+ (* 16 cz) 8)
                y (grass-top sim x z (long (math/floor (second (:sim/player-pos sim)))))]
            (cond (nil? y) sim
                  :else
                  (reduce
                   (fn [sim dx]
                     (cond-> sim
                       (mob/stands? (free-fn sim) [(+ x dx) y z])
                       (add-mob kind [(+ x dx 0.5) (double y) (+ z 0.5)] {:persistent? true})))
                   sim
                   (range mob/herd)))))))

(defn populate
  "The chunks within populate-radius of the player that never had their animals placed get them
   (populate-column), nearest the player's column first; only in the overworld of a model with a
   difficulty, and only when the player came into another column."
  [sim]
  (let [player (:sim/player-pos sim)
        [hx hz :as here] (when player (column-key player))]
    (cond (or (nil? player)
              (nil? (:sim/difficulty sim))
              (not (overworld? sim))
              (= here (:sim/populated-at sim)))
          sim
          :else
          (reduce populate-column
                  (assoc sim :sim/populated-at here)
                  (sort-by (fn [[x z]] [(+ (abs (- x hx)) (abs (- z hz))) x z])
                           (for [x (range (- hx populate-radius) (inc (+ hx populate-radius)))
                                 z (range (- hz populate-radius) (inc (+ hz populate-radius)))
                                 :when (and (contains? (:sim/columns sim) [x z])
                                            (not (contains? (:sim/populated sim) [x z])))]
                             [x z]))))))

(defn tick-mobs
  "One game tick of the mobs: the day moves on (:sim/day-time), chunks coming near get their
   animals (populate), a hostile is tried for (try-spawn), the Nether's warped forests spawn
   their endermen (try-nether-spawn), its fortresses a wither skeleton or a magma cube
   (try-fortress-spawn), its crimson forests a hoglin (try-crimson-spawn), its basalt deltas magma
   cubes (try-deltas-spawn), and its spawners run
   (tick-spawners), every mob acts in id order (act-mob), and far hostiles despawn (despawn). A
   model with no difficulty and no mobs only keeps its day."
  [sim]
  (let [sim (cond-> sim (:sim/day-time sim) (update :sim/day-time inc))]
    (cond (not (or (:sim/difficulty sim) (seq (:sim/mobs sim)))) sim
          :else
          (let [sim (-> sim
                        populate
                        try-spawn
                        try-nether-spawn
                        try-fortress-spawn
                        try-crimson-spawn
                        try-deltas-spawn
                        tick-spawners)]
            (despawn (reduce (fn [sim eid] (face-player (act-mob sim eid) eid))
                             sim
                             (sort (keys (:sim/mobs sim)))))))))
