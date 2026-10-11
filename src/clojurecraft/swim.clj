(ns clojurecraft.swim
  "Getting out of water and away from lava (issue #5).

   Requiring this namespace registers the escape goals' rules: a goal is done when the planner's
   trap (plan/trapped-tick) is not of its kind, its next intent is :swim-out or :flee, and the
   two executors are defmethods of intent/run.

   :swim-out picks one bank and keeps it (the siblings re-picked every tick and swam in circles):
   the nearest cell the feet could stand in, within bank-radius on the plane and from bank-drop
   below the water's surface to bank-rise above it, chosen from the loaded terrain. It holds
   forward and jump toward it; the physics' out-of-liquid impulse fires when the body presses
   into the bank with free space above, which is the only real lift out of water. When no
   progress is made for stuck-ticks it digs what is in the way as a child :dig intent (a lid
   over the head first, a wall at head height, a cap when there is no bank at all), the dig
   penalties for the eyes in water and being off the ground slowing it as vanilla does. It never
   digs the block at feet height ahead: that is the bank the impulse needs to collide with.

   :flee is the lava half: the nearest standable cell with no lava beside or under it, walked to
   with forward and jump; the planner ends it the moment the trap clears."
  (:require [clojurecraft.blocks :as blocks]
            [clojurecraft.chunk :as chunk]
            [clojurecraft.game :as game]
            [clojurecraft.intent :as intent]
            [clojurecraft.memory :as memory]
            [clojurecraft.path :as path]
            [clojurecraft.physics :as physics]
            [clojurecraft.plan :as plan]
            [clojurecraft.stairs :as stairs]
            [clojurecraft.terrain :as terrain]))

(def bank-radius 16)                  ; how far on the plane a bank is looked for (gym shore-w1 run 6: none within 12 from mid-sea)
(def bank-rise 1)                     ; a bank at most this far above the highest feet level afloat (steve's landing set W)
(def bank-drop 3)                     ; or this far below it: a roofed pool's air pocket is sideways and down
(def float-depth 1.03)                ; feet below an open surface when afloat with jump held (vanilla's equilibrium)
(def stuck-ticks 40)                  ; 2 s without progress toward the bank → dig what is in the way
(def progress 0.05)                   ; blocks nearer the bank that count as progress
(def escape-timeout-ticks 2400)       ; 120 s: an escape longer than this has failed
(def search-every-ticks 20)           ; with no bank in reach yet, look again this often while rising
(def flee-radius 6)                   ; how far a lava flee looks for footing
(def flee-timeout-ticks 200)          ; 10 s

(defn solid-cell?
  "Is the cell loaded and a solid block?"
  [world cell]
  (boolean (some-> (terrain/block-at world cell) blocks/solid?)))

(defn surface-y
  "The y of the first cell above the feet cell that is not the fluid kind: the water's
   surface cell, or the roof over it."
  [world kind [x y z]]
  (let [fluid-kind (fn [cy] (:fluid/kind (blocks/fluid (terrain/block-at world [x cy z]))))]
    (or (first (filter #(not= kind (fluid-kind %)) (range y (+ y 16)))) (+ y 16))))

(defn float-top
  "The highest feet level the body reaches in this column: float-depth under an open surface
   cell, or the body's height under a roof; rounded to the cell the impulse can lift from."
  [world [x _ z] surface]
  (Math/round (if (solid-cell? world [x surface z]) (- surface physics/height) (- surface float-depth))))

(defn banks
  "Cells the feet could stand in, nearest first, within bank-radius of feet on the plane and
   between bank-drop under the float-top and bank-rise over it (a ledge higher than that is
   out of the impulse's lift); blacklist removes cells that failed."
  [world [fx _ fz :as feet] surface blacklist]
  (->> (for [dx (range (- bank-radius) (inc bank-radius))
             dz (range (- bank-radius) (inc bank-radius))
             :let [top (float-top world feet surface)]
             y (range (- top bank-drop) (+ top bank-rise 1))
             :let [cell [(+ fx dx) y (+ fz dz)]]
             :when (and (not (blacklist cell)) (path/standable? world cell))]
         cell)
       (sort-by (fn [cell] [(physics/horizontal-distance (physics/centre feet) (physics/centre cell)) (- (second cell))]))
       first))

(defn ahead
  "The cell one step from feet toward bank on the plane, along the larger axis of the way."
  [[fx fy fz] [bx _ bz]]
  (let [dx (- bx fx) dz (- bz fz)]
    (if (>= (abs dx) (abs dz))
      [(+ fx (Long/signum dx)) fy fz]
      [fx fy (+ fz (Long/signum dz))])))

(defn lid?
  "Is the block over the head a lily pad: a lid that stops the rise (steve's rule)?"
  [world [x y z]]
  (= :lily_pad (some-> (terrain/block-at world [x (+ y 2) z]) blocks/type-of)))

(defn obstacle
  "The cell to dig when the swim makes no progress: a lid over the head, else the cell ahead at
   head height, else the cell over the head (the cap of a roofed pool); never the cell ahead at
   feet height, which is the bank. nil when none of them is solid."
  [world [x y z :as feet] bank]
  (let [over [x (+ y 2) z]
        head (when bank (update (ahead feet bank) 1 inc))]
    (cond
      (lid? world feet) over
      (and head (solid-cell? world head)) head
      (solid-cell? world over) over
      :else nil)))

(defn child-dig
  "A :dig child for cell that may expose water: the escape digs beside the water it is in."
  [cell]
  (assoc (stairs/child-dig cell) :intent/exposes-ok? true))

(defn floor-y
  "The feet level of the floor under the feet cell: one above the first solid cell below it
   within 16, or nil."
  [world [x y z]]
  (some (fn [cy] (when (solid-cell? world [x cy z]) (inc cy))) (range (dec y) (- y 17) -1)))

(defn dig-from-floor?
  "Could cell be dug standing on the floor under the feet: is its centre within a dig's reach
   of the eye there? Off the ground a dig is five times slower, and under water five times
   again: a cap dug from the bottom of a four-deep well takes 6 s, from the water 28 s, and the
   breath lasts 25."
  [world [x _ z :as feet] cell]
  (when-let [fy (floor-y world feet)]
    (<= (physics/distance [(+ x 0.5) (+ fy physics/eye-height) (+ z 0.5)] (physics/centre cell)) intent/reach)))

(defn start-dig
  "Dig cell as a child intent now."
  [world cell why]
  (-> world
      (update :plan/intent assoc :intent/child (child-dig cell) :intent/best-tick (:time/tick world))
      (update :plan/intent dissoc :intent/dig-when-down)
      (game/say (str "swim-out: " why ", digging " cell))))

(defn dig-or-sink
  "Dig cell as a child intent now, or, afloat with the floor in reach of it, remember it as
   :intent/dig-when-down and sink: the dig starts from the floor, where it is not off the ground."
  [world feet cell why]
  (if (and (:player/in-water? world) (not (:player/on-ground? world)) (dig-from-floor? world feet cell))
    (-> world
        (assoc :player/controls {:control/sneak? true})
        (update :plan/intent assoc :intent/dig-when-down cell)
        (game/say (str "swim-out: " why ", sinking to the floor to dig " cell)))
    (start-dig world cell why)))

(defn swim-controls
  "Toward the bank's centre: in the water, forward with jump held (the swim-up and, pressed
   into the bank, the impulse); out of it, a plain walk, and on dry land nothing at all, so the
   planner can see the land held still (a bot that kept jumping on the bank never counted as
   out)."
  [world bank]
  (cond
    (plan/dry-land? world) {}
    (:player/in-water? world) (assoc (intent/toward world (physics/centre bank) 0.0) :control/jump? true)
    :else (intent/toward world (physics/centre bank) 0.0)))

(defmethod intent/run :swim-out
  [world {:intent/keys [bank started best-dist best-tick child next-search dig-when-down]} event]
  (let [tick (:time/tick world)
        started (or started tick)
        feet (stairs/feet world)
        set-i (fn [w & kvs] (apply update w :plan/intent assoc kvs))
        drop-i (fn [w & ks] (apply update w :plan/intent dissoc ks))
        world (set-i world :intent/started started)
        dist (when bank (physics/horizontal-distance (:player/pos world) (physics/centre bank)))
        nearer? (and dist (< dist (- (or best-dist Double/MAX_VALUE) progress)))
        best-tick (if (or nearer? (nil? best-tick)) tick best-tick)]
    (cond
      (> (- tick started) escape-timeout-ticks)
      (-> world (assoc :player/controls {}) (intent/fail :no-escape))

      (and child (intent/done? child))
      (drop-i world :intent/child :intent/best-tick)

      (and child (intent/failed? child))
      (-> world (assoc :player/controls {}) (intent/fail (:intent/reason child)))

      child
      (stairs/run-child world event)

      (and dig-when-down (or (:player/on-ground? world) (not (:player/in-water? world))))
      (start-dig world dig-when-down "down")

      dig-when-down
      (assoc world :player/controls {:control/sneak? true})

      (and (nil? bank) next-search (< tick next-search))
      (assoc world :player/controls {:control/jump? true})   ; rise until the next look

      (nil? bank)
      (let [surface (surface-y world (if (:player/in-lava? world) :lava :water) feet)
            b (banks world feet surface (:plan/blacklist world #{}))
            cap (when-not b (obstacle world feet nil))]
        (cond
          b (-> world (set-i :intent/bank b :intent/target b) (game/say (str "swim-out: bank " b)))
          cap (dig-or-sink world feet cap "no bank")
          ;; nothing in reach: rise (the roof or a bank comes into reach, or the timeout ends it)
          :else (-> world (assoc :player/controls {:control/jump? true}) (set-i :intent/next-search (+ tick search-every-ticks)))))

      (> (- tick best-tick) stuck-ticks)
      (if-let [cell (obstacle world feet bank)]
        (dig-or-sink world feet cell "stuck")
        (-> world (assoc :player/controls {}) (intent/fail :stuck)))

      :else
      (-> world
          (assoc :player/controls (swim-controls world bank))
          (set-i :intent/best-dist (if nearer? dist (or best-dist dist)) :intent/best-tick best-tick)))))

(defn footing
  "The nearest cell within flee-radius of feet, at most one up or down, the feet could stand
   in with no lava beside or under it, or nil."
  [world [fx fy fz :as feet]]
  (->> (for [dx (range (- flee-radius) (inc flee-radius))
             dz (range (- flee-radius) (inc flee-radius))
             dy [0 -1 1]
             :let [cell [(+ fx dx) (+ fy dy) (+ fz dz)]]
             :when (and (not= cell feet) (path/standable? world cell))]
         cell)
       (sort-by #(physics/horizontal-distance (physics/centre feet) (physics/centre %)))
       first))

(defmethod intent/run :flee
  [world {:intent/keys [target started]} _]
  (let [tick (:time/tick world)
        started (or started tick)
        feet (stairs/feet world)
        world (update world :plan/intent assoc :intent/started started)]
    (cond
      (> (- tick started) flee-timeout-ticks)
      (-> world (assoc :player/controls {}) (intent/fail :no-footing))

      (nil? target)
      (if-let [cell (footing world feet)]
        (-> world (update :plan/intent assoc :intent/target cell) (game/say (str "flee: to " cell)))
        (-> world (assoc :player/controls {}) (intent/fail :no-footing)))

      :else
      (assoc world :player/controls (assoc (intent/toward world (physics/centre target) 0.0) :control/jump? true)))))

;; ---------------------------------------------------------------- the goals' rules

(defmethod plan/done-by :not-trapped-in [world goal]
  (not= (:goal/trap goal) (:plan/trap world)))

(defmethod plan/next-intent :escape [world goal _]
  ;; nothing to plan unless the bot is in the row's trap, like a needs row with nothing needed
  (when (= (:goal/trap goal) (:plan/trap world))
    {:intent/kind (:goal/act goal)}))

;; ---------------------------------------------------------------- walking in on purpose (the gym's :shore)

(def wade-radius 64)                  ; how far a remembered pool may be to wade into
(def wade-below 16)                   ; a pool this far under the eye is still waded to (the bucket's 5 is for scooping; a dune over the sea is fine)
(def wade-beyond 8)                   ; blocks past the entry the wade keeps heading: a one-deep shelf never puts the eye under
(def wade-timeout-ticks 2400)         ; 120 s to find water and get the eye under it

(defn water-source?
  "Is the state id a water source with no water over it: the top of a pool."
  [world [x y z :as cell]]
  (let [f (blocks/fluid (terrain/block-at world cell))]
    (and (= :water (:fluid/kind f)) (= 0 (:fluid/level f))
         (not= :water (:fluid/kind (blocks/fluid (terrain/block-at world [x (inc y) z])))))))

(defn entries
  "Where the bot could walk into the water of chunk key: [bank water] pairs, the bank a cell
   the feet can stand in beside a surface source of the column, nearest the feet first."
  [world key feet]
  (let [column (get-in world [:world/chunks key])
        sources (for [[x y z id] (chunk/find-blocks {key column} blocks/fluid)
                      :when (water-source? world [x y z])]
                  [x y z])]
    (->> (for [[x y z :as water] sources
               [dx _ dz] path/around
               by [(inc y) y]
               :let [bank [(+ x dx) by (+ z dz)]]
               :when (path/standable? world bank)]
           [bank water])
         (sort-by (fn [[bank _]] (physics/horizontal-distance (physics/centre feet) (physics/centre bank))))
         distinct)))

(defn entry
  "The nearest way into water: over the remembered pools within wade-radius and no more than
   wade-below under the eye, nearest first, the first chunk with an entry gives its nearest.
   [bank water], or nil."
  [world feet]
  (let [eye (physics/eye (:player/pos world))
        ey (second eye)]
    (->> (memory/pools world :water)
         (filter (fn [[_ [_ y _]]] (>= y (- ey wade-below))))
         (filter (fn [[_ pos]] (<= (physics/distance eye (physics/centre pos)) wade-radius)))
         (sort-by (fn [[_ pos]] (physics/distance eye (physics/centre pos))))
         (some (fn [[key _]] (first (entries world key feet)))))))

(defn beyond
  "A point wade-beyond blocks past water, away from bank on the plane: where the wade heads
   so a shallow shelf is crossed into depth."
  [[bx _ bz] [wx wy wz]]
  (let [dx (- wx bx) dz (- wz bz)]
    [(+ wx 0.5 (* wade-beyond dx)) (+ wy 0.5) (+ wz 0.5 (* wade-beyond dz))]))

(defmethod intent/run :wade
  [world {:intent/keys [target water started child]} event]
  (let [tick (:time/tick world)
        started (or started tick)
        world (update world :plan/intent assoc :intent/started started)]
    (cond
      (> (- tick started) wade-timeout-ticks)
      (-> world (assoc :player/controls {}) (intent/fail :no-water))

      (nil? target)
      (if-let [[bank w] (entry world (stairs/feet world))]
        (-> world
            (update :plan/intent assoc :intent/target bank :intent/water w
                    :intent/child {:intent/kind :walk :intent/target bank :intent/status :active})
            (game/say (str "wade: to the bank " bank " into " w)))
        (-> world (assoc :player/controls {}) (intent/fail :no-water)))

      (and child (intent/done? child))
      (update world :plan/intent dissoc :intent/child)

      (and child (intent/failed? child))
      (-> world (assoc :player/controls {}) (intent/fail (:intent/reason child)))

      child
      (stairs/run-child world event)

      ;; on the bank: straight in and on past the entry with jump off, sinking as it deepens;
      ;; the planner's trap takes over
      :else
      (assoc world :player/controls (assoc (intent/toward world (beyond target water) 0.0) :control/jump? false)))))

(defmethod plan/done-by :escaped-water? [world _]
  (boolean (some #(= :water (:trap/kind %)) (:world/traps world))))

(defmethod plan/next-intent :wade [world _ _]
  ;; a wade needs a way into water it remembers; with none it waits, as a gather with no tree does
  (if (entry world (stairs/feet world))
    {:intent/kind :wade}
    {:plan/wait :no-water}))
