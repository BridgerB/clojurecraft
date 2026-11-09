(ns clojurecraft.pearls.cover
  "Cover from shooters and swarms: the cell out of sight, the block between, the wall."
  (:require [clojure.math :as math]
            [clojurecraft.avoided :as avoided]
            [clojurecraft.blaze :as blaze]
            [clojurecraft.combat :as combat]
            [clojurecraft.entity :as entity]
            [clojurecraft.filler :as filler]
            [clojurecraft.game :as game]
            [clojurecraft.intent :as intent]
            [clojurecraft.memory :as memory]
            [clojurecraft.mob :as mob]
            [clojurecraft.path :as path]
            [clojurecraft.pearls.params :as pparams]
            [clojurecraft.pearls.place :as pplace]
            [clojurecraft.pearls.sense :as psense]
            [clojurecraft.pearls.upkeep :as pupkeep]
            [clojurecraft.physics :as physics]
            [clojurecraft.place :as place]))

(set! *warn-on-reflection* true)

(defn cell-toward
  "The cell beside the feet, dy above them, on the side point to lies on (the heading of the larger
   offset on the plane), or nil when to is over the feet."
  [world to dy]
  (let [[fx fy fz] (intent/feet world)
        [ex _ ez] to
        dx (- ex (+ fx 0.5))
        dz (- ez (+ fz 0.5))
        [hx hz] (if (>= (Math/abs dx) (Math/abs dz))
                  [(long (math/signum dx)) 0]
                  [0 (long (math/signum dz))])]
    (when (not= [0 0] [hx hz]) [(+ fx hx) (+ fy dy) (+ fz hz)])))

(defn wall-toward
  "A :place of one block of the filler held (filler/pick) in the first open cell of levels (dy from
   the feet) on the side of the feet that point to lies on (cell-toward), or nil when all are solid
   or none is held. Marked :intent/wall?, so the hunt's interrupt does not cut it."
  [world to levels]
  (when-let [item (filler/pick world 2)]
    (some (fn [dy]
            (when-let [c (cell-toward world to dy)]
              (when-not (or (pplace/solid-or-unloaded? world c)
                            (mob/occupied? (:world/entities world) c)
                            (nil? (place/support world c)))
                {:intent/kind :place
                 :intent/item item
                 :intent/cell c
                 :intent/for :pearls
                 :intent/wall? true})))
          levels)))

(defn unseal
  "A :dig of the block toward the mouth, the head's level and then the feet's, when it is solid and
   no route walks to the mouth: the seal a swarm put there (wall-toward). Nil otherwise."
  [world mouth]
  (when-not (intent/routable? world {:intent/kind :walk :intent/target mouth :intent/for :shelter})
    (some (fn [dy]
            (let [c (cell-toward world (physics/centre mouth) dy)]
              (when (and c (pplace/solid-or-unloaded? world c))
                {:intent/kind :dig :intent/target c :intent/for :pearls})))
          [1 0])))

(defn wall-off
  "A wall-toward enderman e, the feet's level and then the head's: an enderman cannot reach through
   two full blocks. The hunt never fights under danger-health, so what is left is to eat, or to
   put a block between."
  [world e]
  (wall-toward world (:entity/pos e) [0 1]))

(defn room-boxes
  "The boxes [x0 y0 z0 x1 y1 z1], one for each spawner on record in the bot's dimension
   (memory/positions-now), room-margin round it. Pure."
  [world]
  (vec (for [[x y z] (memory/positions-now world [blaze/spawner])]
         [(- x pparams/room-margin) (- y pparams/room-margin) (- z pparams/room-margin)
          (+ x pparams/room-margin) (+ y pparams/room-margin) (+ z pparams/room-margin)])))

(defn in-room?
  "Is point [x y z] (a cell or a position) inside one of boxes?"
  [boxes [x y z]]
  (boolean (some (fn [[x0 y0 z0 x1 y1 z1]] (and (<= x0 x x1) (<= y0 y y1) (<= z0 z z1))) boxes)))

(defn crosses-room?
  "Does the straight line from point a to point b pass through one of boxes, sampled every two
   blocks? Pure."
  [boxes a b]
  (let [d (physics/distance a b)
        n (max 1 (quot (inc (long d)) 2))]
    (boolean (some (fn [k]
                     (let [t (/ k (double n))] (in-room? boxes (mapv #(+ %1 (* t (- %2 %1))) a b))))
                   (range (inc n))))))

(defn trail-cells
  "The trail cells available to leave the spawner or blaze room: the cells of the fortress tunnel
   trail (:fortress/trail, newest first), then the cells of the plan trail (:plan/trail, newest
   first) which records where the bot actually walked from the fortress entrance to the spawner.
   Pure."
  [world]
  (concat (reverse (:trail/cells (:fortress/trail world))) (rseq (:plan/trail world []))))

(defn out-of-sight-cell
  "The newest cell of the fortress trail (:fortress/trail, then :plan/trail) outside every blaze
   room from which no entity of shooters has a clear line to a standing eye, or nil.
   The way back out of a blaze room is the way the bot tunnelled or walked in: the race's rod bots
   had no other walk and died in it."
  [world]
  (let [ss (vals (psense/shooters world))
        boxes (room-boxes world)]
    (when (seq ss)
      (->> (trail-cells world)
           (remove #(in-room? boxes %))
           (filter #(psense/unseen-from? world ss %))
           first))))

(defn near-cover-cell
  "The nearest cell within cover-reach (the plane, two up or down) that the feet could stand on
   (path/standable?) and that no entity of shooters has a clear line to, or nil: the way out of a
   shooter's sight when the trail has no cell for it (a bot that tunnelled in a different way,
   or a trail reset by another tunnel)."
  [world]
  (let [ss (vals (psense/shooters world))
        feet (intent/feet world)
        [fx fy fz] feet]
    (when (seq ss)
      (->> (for [dx (range (- pparams/cover-reach) (inc pparams/cover-reach))
                 dz (range (- pparams/cover-reach) (inc pparams/cover-reach))
                 dy (range -2 3)]
             [(+ fx dx) (+ fy dy) (+ fz dz)])
           (filter #(path/standable? world %))
           (sort-by #(path/octile feet %))
           (filter #(psense/unseen-from? world ss %))
           first))))

(declare cover-from)

(defn leave-sight
  "The intent that gets the bot out of the shooters' sight, or nil: with a shooter in view
   (combat/seen?) and no enderman tracked within seek-radius, a routable walk to the newest
   fortress trail cell out of sight (out-of-sight-cell), else to the nearest cell round the feet out
   of sight (near-cover-cell) when outside every blaze room, else cover-from, else, at under
   danger-health, a back-off from the nearest shooter in view: never a dig or a pillar in its
   sight. Pure."
  [world]
  (let [shot (into {} (filter (fn [[_ e]] (combat/seen? world e))) (psense/shooters world))]
    (when (and (seq shot) (empty? (psense/endermen world pparams/seek-radius)))
      (let [walk (fn [c]
                   (when c
                     (let [i {:intent/kind :walk :intent/target c :intent/for :cover}]
                       (when (intent/routable? world i) i))))
            in-blaze-room? (in-room? (room-boxes world) (intent/feet world))]
        (or (walk (out-of-sight-cell world))
            (when-not in-blaze-room? (walk (near-cover-cell world)))
            (when-not in-blaze-room? (cover-from world))
            (when (< (:player/health world 20.0) pparams/danger-health)
              (let [[eid _] (entity/nearest shot (:player/pos world))]
                {:intent/kind :back-off :intent/target eid :intent/for :pearls})))))))

(defn line-cell
  "The first cell from the eye toward entity e's aim, a block to six along the line, that is open,
   neither the feet's nor the head's, within place/place-reach, empty of mobs and not refused
   before, and with a block the server lets it be laid against (place/support: loaded, solid, not
   one a click opens), or nil. A fireball travels that line."
  [world e]
  (let [eye (game/eye world)
        aim (combat/aim e)
        d (physics/distance eye aim)
        [fx fy fz] (intent/feet world)
        own #{[fx fy fz] [fx (inc fy) fz]}]
    (when (pos? d)
      (some (fn [t]
              (let [c (combat/cell (mapv (fn [a b] (+ a (* t (/ (- b a) d)))) eye aim))]
                (when (and (not (own c))
                           (not (pplace/solid-or-unloaded? world c))
                           (not (mob/occupied? (:world/entities world) c))
                           (not (avoided/noted? world :blacklist c))
                           (<= (physics/distance eye (physics/centre c)) place/place-reach)
                           (some? (place/support world c)))
                  c)))
            (range 1.0 (min 6.0 d) 0.5)))))

(defn hide-cell
  "A cell beside the feet that can be stood in (floor under it, open itself and over it) and from
   whose eye the line to entity e's aim is blocked (combat/clear?), or nil: where a walk takes the
   bot out of a ghast's sight."
  [world e]
  (let [[fx fy fz] (intent/feet world)]
    (some (fn [[dx dz]]
            (let [c [(+ fx dx) fy (+ fz dz)]
                  eye [(+ (first c) 0.5) (+ fy 1.62) (+ (last c) 0.5)]]
              (when (and (pplace/solid-or-unloaded? world [(first c) (dec fy) (last c)])
                         (not (pplace/solid-or-unloaded? world c))
                         (not (pplace/solid-or-unloaded? world [(first c) (inc fy) (last c)]))
                         (not (combat/clear? world eye (combat/aim e))))
                c)))
          [[1 0] [-1 0] [0 1] [0 -1]])))

(defn dig-away
  "A :dig of the first solid cell, the feet's level and then the head's, on the side of the feet
   away from entity e, with a pickaxe held, or nil: a hole to stand in out of its sight."
  [world e]
  (when (pupkeep/pickaxe? world)
    (let [[fx fy fz] (intent/feet world)
          [ex _ ez] (:entity/pos e)
          dx (- (+ fx 0.5) ex)
          dz (- (+ fz 0.5) ez)
          [hx hz] (if (>= (Math/abs dx) (Math/abs dz))
                    [(long (math/signum dx)) 0]
                    [0 (long (math/signum dz))])]
      (some (fn [dy]
              (let [c [(+ fx hx) (+ fy dy) (+ fz hz)]]
                (when (pplace/solid-or-unloaded? world c)
                  {:intent/kind :dig :intent/target c :intent/for :pearls})))
            [0 1]))))

(defn placing?
  "May cover lay a block now: no :place failed within cover-place-hold-ms, and fewer than
   cover-place-max begun in it? A block the server refuses is not tried again under fire, nor one
   more at the same spot while fireballs land: the rungs that dig, walk out of the line, back off
   or fight answer instead."
  [world]
  (and (not (memory/failed-within? world :place pparams/cover-place-hold-ms))
       (< (memory/started-count-within? world :place pparams/cover-place-hold-ms)
          pparams/cover-place-max)))

(defn open-striker
  "[eid entity] of the nearest angry enderman (entity/creepy?) at hand, within strike-range and
   the sword's reach (combat/in-reach?), blacklisted or not and not fought out (psense/fought-out?:
   struck-by answers that one once it has hurt the bot), while the bot stands farther than
   answer-home-reach from its burrow's end or has none (pplace/shelter); nil otherwise. The hunt
   covers from it where the bot stands (combat/cover), never with a fight, a walk home or a
   back-off: an enderman walks as fast as the bot and never teleports from a player's hit, so a
   walk with one at hand takes its hits with no swing back, and a sword in the open needs seven
   swings, four and a half seconds, against its hit a second."
  [world]
  (let [home (pplace/shelter world)
        pos (:player/pos world)
        at-hand (fn [[eid e]]
                  (and (entity/creepy? e)
                       (not (psense/fought-out? world eid))
                       (<= (physics/distance pos (:entity/pos e)) pparams/strike-range)
                       (combat/in-reach? world e)))]
    (when-not (and home (<= (physics/distance (intent/feet world) home) pparams/answer-home-reach))
      (entity/nearest (into {}
                            (filter at-hand)
                            (psense/endermen (avoided/forget world :blacklist)
                                             pparams/strike-range))
                      pos))))

(defn cover-from
  "The intent that covers the bot from the swarm: from a shooter in sight (shot-at?, never fought,
   never out-ranged) a walk to a cell beside the feet out of its sight (hide-cell; :intent/for
   :cover, so the walk ends only centred in the cell), else a block of the filler held on the
   line from the eye to it (line-cell), else, on open ground where no cell on the line has
   anything to lay against, a block at the feet's level and one over it on its side (wall-toward:
   the line to a shooter a little above is at the head's height), else a dig into the solid side
   away from it (dig-away) to stand in; from the others what combat/cover gives for the nearest
   (meal, a block between, a pillar, a nook dug under the feet). No block is laid for cover-place-hold-ms
   after a :place failed (placing?). Nil when it only waits (cover's :plan/wait ends the plan) or
   nothing can be done."
  [world]
  (if (psense/shot-at? world)
    (when-let [[_ e] (entity/nearest (into {}
                                           (filter (fn [[_ e]]
                                                     (and (contains? pparams/ranged-kinds
                                                                     (entity/kind e))
                                                          (combat/seen? world e))))
                                           (psense/swarm world))
                                     (:player/pos world))]
      (let [item (filler/pick world 1)
            hide (hide-cell world e)
            placing (placing? world)
            line (when (and item placing) (line-cell world e))]
        (cond hide {:intent/kind :walk :intent/target hide :intent/for :cover :intent/wall? true}
              line
              {:intent/kind :place
               :intent/item item
               :intent/cell line
               :intent/for :pearls
               :intent/wall? true}
              :else
              (or (when placing (wall-toward world (:entity/pos e) [0 1]))
                  (dig-away world e)
                  (when-let [c (combat/nook-cell world)]
                    {:intent/kind :dig :intent/target c :intent/for :pearls})))))
    (when-let [[_ e] (entity/nearest (psense/swarm world) (:player/pos world))]
      (let [c (combat/cover world e)]
        (when (and (:intent/kind c) (or (placing? world) (not= :place (:intent/kind c))))
          (assoc c :intent/for :pearls))))))
