(ns clojurecraft.dodge
  "Dodging a blaze's fireballs (issue #262): a small fireball flies straight, so its line is known
   from its position and velocity (the add-entity and set-entity-motion packets the world already
   keeps in :world/entities, :entity/pos and :entity/vel) the moment it spawns, and it takes 300 to
   750 ms to arrive.

   Read from blaze-race-l race 2's recording (volleys.ts): 212 small fireballs spawned within 30
   blocks of the bot, 24 hit it; every one that hit passed within 1.4 blocks of its body, none of
   the 76 whose line passed 1.5 or more away did. The velocity in the packet is a direction scaled
   about 5.5 times too small (|vel| 0.1 for 0.55 blocks a tick, 4.6 blocks in 450 ms). The blaze's
   charge flag (metadata index 16) is set 1.4 to 3.5 s before its fireballs spawn, with no
   direction in it.

   incoming lists the fireballs whose line passes the body within danger-radius; dodge-cell is the
   nearest standable cell within two steps where every one of them passes clear-radius away and the
   bot gets there before the first arrives; intent-for is the :dodge intent for it. The :dodge
   executor walks straight at the cell (a route is planned for no more than a step or two) and ends
   centred in it or after dodge-ms."
  (:require [clojure.math :as math]
            [clojurecraft.entity :as entity]
            [clojurecraft.intent :as intent]
            [clojurecraft.path :as path]
            [clojurecraft.physics :as physics]))

(set! *warn-on-reflection* true)

(def danger-radius 1.6)   ; blocks a fireball's line passes the body within to be a hit (none hit at 1.5 or more)
(def clear-radius 2.0)    ; blocks every line must pass the dodge cell's body at
(def horizon 24.0)        ; blocks from the bot within which a fireball is looked at
(def vel-scale 5.5)       ; blocks a tick a fireball flies per unit of its packet velocity (|vel| 0.1 is 0.55)
(def body-rise 0.9)       ; blocks over the feet the body's middle is
(def move-base-ms 120)    ; ms before the bot is on its way (a tick to see it, a tick to turn)
(def move-ms-per-block 230) ; ms a block of walking takes (4.3 blocks a second)
(def slack-ms 50)         ; ms a fireball may arrive before the bot is clear and still be missed
(def steps 2)             ; cells each way on the plane a dodge looks within
(def dodge-ms 700)        ; ms a :dodge lasts at the most

(defn fireballs
  "{eid entity} of the small fireballs the world tracks that carry a velocity."
  [world]
  (into {}
        (filter (fn [[_ e]] (and (= :small_fireball (entity/kind e)) (:entity/vel e))))
        (:world/entities world)))

(defn closest
  "{:miss blocks :eta-ms ms} of the straight line of fireball e past point b (its closest approach,
   and the time the fireball takes to reach it), or nil when it flies away from b or is past it or
   stands still."
  [e b]
  (let [p (:entity/pos e)
        v (:entity/vel e)
        w (mapv - b p)
        vv (reduce + (map * v v))
        t (when (pos? vv) (/ (reduce + (map * w v)) vv))]
    (when (and t (pos? t))
      (let [off (mapv (fn [wi vi] (- wi (* t vi))) w v)]
        {:miss (math/sqrt (double (reduce + (map * off off)))) :eta-ms (* 50.0 (/ t vel-scale))}))))

(defn body-at
  "The point of the body for feet in cell: its middle on the plane, body-rise over the cell's floor."
  [[x y z]]
  [(+ x 0.5) (+ y body-rise) (+ z 0.5)])

(defn incoming
  "[{:eid :miss :eta-ms}] of the fireballs within horizon of the feet whose line passes the body
   within danger-radius, the soonest first: the ones that would hit it where it stands."
  [world]
  (let [feet (intent/feet world)
        b (body-at feet)]
    (->> (fireballs world)
         (keep (fn [[eid e]]
                 (when (<= (physics/distance (:player/pos world) (:entity/pos e)) horizon)
                   (when-let [c (closest e b)]
                     (when (< (:miss c) danger-radius) (assoc c :eid eid))))))
         (sort-by :eta-ms)
         vec)))

(defn miss-at
  "How near the nearest line of the fireballs es (entities) passes the body standing in cell: a
   large number when none flies toward it."
  [es cell]
  (let [b (body-at cell)]
    (reduce (fn [m e]
              (if-let [c (closest e b)]
                (min m (:miss c))
                m))
            1.0e9
            es)))

(defn candidates
  "The cells within steps of the feet on the plane and one up or down the bot can stand in, the
   feet's own left out, nearest first."
  [world]
  (let [[x y z :as feet] (intent/feet world)
        at (path/cells world)]
    (->> (for [dx (range (- steps) (inc steps))
               dz (range (- steps) (inc steps))
               dy [0 -1 1]
               :let [c [(+ x dx) (+ y dy) (+ z dz)]]
               :when (and (not= c feet) (path/standable? at c))]
           c)
         (sort-by #(+ (path/octile feet %) (* 0.1 (abs (- (second %) y))))))))

(defn dodge-cell
  "The nearest cell (candidates) where every fireball's line passes the body at clear-radius or
   more and the bot is there before the first of them would arrive (move time against its eta, with
   slack-ms), or nil when none is: a tunnel one wide has none, and no dodge is tried."
  [world]
  (let [hits (incoming world)]
    (when (seq hits)
      (let [es (vals (fireballs world))
            first-eta (:eta-ms (first hits))
            feet (intent/feet world)]
        (some (fn [c]
                (when (and (>= (miss-at es c) clear-radius)
                           (<= (+ move-base-ms (* move-ms-per-block (path/octile feet c)))
                               (+ first-eta slack-ms)))
                  c))
              (candidates world))))))

(defn intent-for
  "The :dodge intent for the cell dodge-cell finds, or nil."
  [world]
  (when-let [c (dodge-cell world)] {:intent/kind :dodge :intent/target c}))

(defmethod intent/run :dodge
  ;; {:intent/kind :dodge :intent/target cell}: walk straight at the cell's middle, no route; done
  ;; when the feet are in it near its middle, or after dodge-ms
  [world {:intent/keys [target since]} _]
  (let [now (:time/now world)
        since (or since now)
        world (intent/assoc-intent world :intent/since since)]
    (if (or (intent/centred? world target) (> (- now since) dodge-ms))
      (-> world
          (assoc :player/controls {})
          intent/done)
      (assoc world :player/controls (intent/toward world (physics/centre target) 0.0)))))
