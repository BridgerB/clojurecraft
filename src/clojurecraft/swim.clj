(ns clojurecraft.swim
  "Getting out of water and away from lava (issue #5).

   Requiring this namespace registers the escape goals' rules: a goal is done when the planner's
   trap (trap/trapped-tick) is not of its kind, its next intent is :swim-out or :flee, and the
   two executors are defmethods of intent/run.

   :swim-out picks one bank and keeps it (the siblings re-picked every tick and swam in circles):
   the nearest cell the feet could stand in, within bank-radius on the plane and from bank-drop
   below the water's surface to bank-rise above it, chosen from the loaded terrain. It holds
   forward and jump toward it; the physics' out-of-liquid impulse fires when the body presses
   into the bank with free space above, which is the only real lift out of water. When no
   progress is made for stuck-ticks it digs what is in the way as a child :dig intent (a lid
   over the head first, a wall at head height, a cap when there is no bank at all), the dig
   penalties for the eyes in water and being off the ground slowing it as vanilla does. It never
   digs the block at feet height ahead: that is the bank the impulse needs to collide with. With
   no bank at all and no cap, a flooded cave whose rock walls rise straight from the water, it
   carves one: a notch two high in the nearest wall just over the float level (issue #73).

   :flee is the lava half: the nearest standable cell with no lava beside or under it, walked to
   with forward and jump; the planner ends it the moment the trap clears."
  (:require [clojure.math :as math]
            [clojurecraft.blocks :as blocks]
            [clojurecraft.chunk :as chunk]
            [clojurecraft.game :as game]
            [clojurecraft.intent :as intent]
            [clojurecraft.memory :as memory]
            [clojurecraft.path :as path]
            [clojurecraft.physics :as physics]
            [clojurecraft.plan :as plan]
            [clojurecraft.terrain :as terrain]
            [clojurecraft.trap :as trap]))

(set! *warn-on-reflection* true)

(def bank-radius 16)                  ; how far on the plane a bank is looked for (gym shore-w1 run 6: none within 12 from mid-sea)
(def bank-rise 1)                     ; a bank at most this far above the highest feet level afloat (steve's landing set W)
(def bank-drop 3)                     ; or this far below it: a roofed pool's air pocket is sideways and down
(def float-depth 1.03)                ; feet below an open surface when afloat with jump held (vanilla's equilibrium)
(def stuck-ticks 40)                  ; 2 s without progress toward the bank → dig what is in the way
(def progress 0.05)                   ; blocks nearer the bank that count as progress
(def escape-timeout-ticks 2400)       ; 120 s: an escape longer than this has failed
(def search-every-ticks 20)           ; with no bank in reach yet, look again this often while rising
(def notch-radius 4)                  ; how far on the plane a wall is notched into for a bank when there is none (a dig's reach)
(def flee-radius 6)                   ; how far a lava flee looks for footing
(def flee-timeout-ticks 200)          ; 10 s

(defn solid-cell?
  "Is the cell loaded and a solid block?"
  [world cell]
  (boolean (some-> (terrain/block-at world cell)
                   blocks/solid?)))

(defn surface-y
  "The y of the first cell above the feet cell that is not the fluid kind: the water's
   surface cell, or the roof over it."
  [world kind [x y z]]
  (or (first (filter #(not= kind (terrain/fluid-kind world [x % z])) (range y (+ y 16)))) (+ y 16)))

(defn float-top
  "The highest feet level the body reaches in this column: float-depth under an open surface
   cell, or the body's height under a roof; rounded to the cell the impulse can lift from."
  [world [x _ z] surface]
  (math/round
   (if (solid-cell? world [x surface z]) (- surface physics/height) (- surface float-depth))))

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
       (sort-by (fn [cell] [(physics/horizontal-distance (physics/centre feet)
                                                         (physics/centre cell))
                            (- (physics/y-of cell))]))
       first))

(defn ahead
  "The cell one step from feet toward bank on the plane, along the larger axis of the way."
  [[fx fy fz] [bx _ bz]]
  (let [dx (- bx fx)
        dz (- bz fz)]
    (if (>= (abs dx) (abs dz)) [(+ fx (Long/signum dx)) fy fz] [fx fy (+ fz (Long/signum dz))])))

(defn lid?
  "Is the block over the head a lily pad: a lid that stops the rise (steve's rule)?"
  [world [x y z]]
  (= :lily_pad
     (some-> (terrain/block-at world [x (+ y 2) z])
             blocks/type-of)))

(defn obstacle
  "The cell to dig when the swim makes no progress: a lid over the head, else the cell ahead at
   head height, else the cell over the head (the cap of a roofed pool); never the cell ahead at
   feet height, which is the bank. nil when none of them is solid."
  [world [x y z :as feet] bank]
  (let [over [x (+ y 2) z]
        head (when bank (update (ahead feet bank) 1 inc))]
    (cond (lid? world feet) over
          (and head (solid-cell? world head)) head
          (solid-cell? world over) over
          :else nil)))

(defn notch
  "With no bank in reach, the cell to dig toward one: a notch in the nearest wall, two cells
   over a solid at the float-top level (the floor the impulse lifts onto, as a pond's bank is
   one over the water's top), within notch-radius of feet on the plane and a dig's reach of
   the afloat eye; the lower cell while it is solid, then the upper. nil with no wall in reach
   (issue #73: an aquifer's rock walls rise straight from the water, so no cell within sixteen
   was standable and the escape rose until it timed out)."
  [world [fx _ fz :as feet] surface]
  (let [top (float-top world feet surface)
        eye [(+ fx 0.5) (+ top physics/eye-height) (+ fz 0.5)]]
    (->> (for [dx (range (- notch-radius) (inc notch-radius))
               dz (range (- notch-radius) (inc notch-radius))
               :let [lower [(+ fx dx) (inc top) (+ fz dz)]
                     upper [(+ fx dx) (+ top 2) (+ fz dz)]]
               :when (and (solid-cell? world [(+ fx dx) top (+ fz dz)])
                          (<= (physics/distance eye (physics/centre lower)) intent/reach)
                          (or (solid-cell? world lower) (solid-cell? world upper)))]
           [lower upper])
         (sort-by (fn [[lower _]] [(physics/horizontal-distance (physics/centre feet)
                                                                (physics/centre lower)) lower]))
         first
         (filter #(solid-cell? world %))
         first)))

(defn child-dig
  "A :dig child for cell that may expose water: the escape digs beside the water it is in."
  [cell]
  (assoc (intent/child-dig cell) :intent/exposes-ok? true))

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
    (<= (physics/distance [(+ x 0.5) (+ fy physics/eye-height) (+ z 0.5)] (physics/centre cell))
        intent/reach)))

(defn start-dig
  "Dig cell as a child intent now."
  [world cell why]
  (-> world
      (intent/assoc-intent :intent/child (child-dig cell) :intent/best-tick (:time/tick world))
      (intent/dissoc-intent :intent/dig-when-down)
      (game/say (str "swim-out: " why ", digging " cell))))

(defn dig-or-sink
  "Dig cell as a child intent now, or, afloat with the floor in reach of it, remember it as
   :intent/dig-when-down and sink: the dig starts from the floor, where it is not off the ground."
  [world feet cell why]
  (if (and (:player/in-water? world)
           (not (:player/on-ground? world))
           (dig-from-floor? world feet cell))
    (-> world
        (assoc :player/controls {:control/sneak? true})
        (intent/assoc-intent :intent/dig-when-down cell)
        (game/say (str "swim-out: " why ", sinking to the floor to dig " cell)))
    (start-dig world cell why)))

(defn dig-afloat
  "One tick of the child dig, with jump held when the water's surface is open: the dig looks
   and swings but holds nothing else, and a body on the floor for the length of a dig under
   water drowns (issue #73: the notch's first cell took the air). Afloat under an open surface
   the eye is in air and the dig is no slower than from the floor (off the ground is the same
   five times as the eye under water); under a roof (a capped well) the floor is where the
   dig is from and jump is held off."
  [world event]
  (let [world (intent/run-child world event)
        [x _ z :as feet] (intent/feet world)]
    (cond-> world
      (and (:player/in-water? world) (not (solid-cell? world [x (surface-y world :water feet) z])))
      (update :player/controls assoc :control/jump? true))))

(defn swim-controls
  "Toward the bank's centre: in the water, forward with jump held (the swim-up and, pressed
   into the bank, the impulse); out of it, a plain walk, and on dry land nothing at all, so the
   planner can see the land held still (a bot that kept jumping on the bank never counted as
   out)."
  [world bank]
  (cond (trap/dry-land? world) {}
        (:player/in-water? world)
        (assoc (intent/toward world (physics/centre bank) 0.0) :control/jump? true)
        :else (intent/toward world (physics/centre bank) 0.0)))

(defmethod intent/run :swim-out
  ;; an escape's swim-out is ended by its goal, when the trap clears; one for a :retreat (out of a
  ;; flooded tunnel) ends itself on dry land
  [world {:intent/keys [bank started best-dist best-tick child next-search dig-when-down for]}
   event]
  (let [tick (:time/tick world)
        started (or started tick)
        feet (intent/feet world)
        world (intent/assoc-intent world :intent/started started)
        dist (when bank (physics/horizontal-distance (:player/pos world) (physics/centre bank)))
        nearer? (and dist (< dist (- (or best-dist Double/MAX_VALUE) progress)))
        best-tick (if (or nearer? (nil? best-tick)) tick best-tick)]
    (cond (and (= :retreat for) (trap/dry-land? world))
          (-> world
              (assoc :player/controls {})
              intent/done)
          (> (- tick started) escape-timeout-ticks)
          (-> world
              (assoc :player/controls {})
              (intent/fail :no-escape))
          (and child (intent/done? child))
          (intent/dissoc-intent world :intent/child :intent/best-tick)
          (and child (intent/failed? child))
          (-> world
              (assoc :player/controls {})
              (intent/fail (:intent/reason child)))
          child (dig-afloat world event)
          (and dig-when-down (or (:player/on-ground? world) (not (:player/in-water? world))))
          (start-dig world dig-when-down "down")
          dig-when-down (assoc world :player/controls {:control/sneak? true})
          (and (nil? bank) next-search (< tick next-search))
          (assoc world :player/controls {:control/jump? true}) ; rise until the next look
          (nil? bank)
          (let [surface (surface-y world (if (:player/in-lava? world) :lava :water) feet)
                b (banks world feet surface (:plan/blacklist world #{}))
                cap (when-not b (obstacle world feet nil))
                wall (when-not (or b cap) (notch world feet surface))]
            (cond b
                  (-> world
                      (intent/assoc-intent :intent/bank b :intent/target b)
                      (game/say (str "swim-out: bank " b)))
                  cap (dig-or-sink world feet cap "no bank")
                  wall (start-dig world wall "no bank, notching the wall")
                  ;; nothing in reach: rise (the roof or a bank comes into reach, or the timeout ends it)
                  :else
                  (-> world
                      (assoc :player/controls {:control/jump? true})
                      (intent/assoc-intent :intent/next-search (+ tick search-every-ticks)))))
          (> (- tick best-tick) stuck-ticks)
          (if-let [cell (obstacle world feet bank)]
            (dig-or-sink world feet cell "stuck")
            (-> world
                (assoc :player/controls {})
                (intent/fail :stuck)))
          :else
          (-> world
              (assoc :player/controls (swim-controls world bank))
              (intent/assoc-intent :intent/best-dist (if nearer? dist (or best-dist dist))
                                   :intent/best-tick best-tick)))))

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
        feet (intent/feet world)
        world (intent/assoc-intent world :intent/started started)]
    (cond (> (- tick started) flee-timeout-ticks)
          (-> world
              (assoc :player/controls {})
              (intent/fail :no-footing))
          (nil? target)
          (if-let [cell (footing world feet)]
            (-> world
                (intent/assoc-intent :intent/target cell)
                (game/say (str "flee: to " cell)))
            (-> world
                (assoc :player/controls {})
                (intent/fail :no-footing)))
          :else
          (assoc world
                 :player/controls
                 (assoc (intent/toward world (physics/centre target) 0.0) :control/jump? true)))))

;; ---------------------------------------------------------------- the goals' rules

(defmethod plan/done-by :not-trapped-in [world goal] (not= (:goal/trap goal) (:plan/trap world)))

(defmethod plan/next-intent :escape
  [world goal _]
  ;; nothing to plan unless the bot is in the row's trap, like a needs row with nothing needed
  (when (= (:goal/trap goal) (:plan/trap world)) {:intent/kind (:goal/act goal)}))

;; ---------------------------------------------------------------- walking in on purpose (the gym's :shore)

(def wade-radius 64)                  ; how far a remembered pool may be to wade into
(def wade-below 16)                   ; a pool this far under the eye is still waded to (the bucket's 5 is for scooping; a dune over the sea is fine)
(def wade-beyond 4)                   ; blocks ahead of the feet the wade keeps heading, away from its bank: a shelf is crossed into depth however wide (shore-w3: 8 past the entry was not enough)
(def wade-timeout-ticks 2400)         ; 120 s to find water and get the eye under it

(defn water-source?
  "Is the cell the top of a pool at least two deep: a water source with water under it and
   none over it (steve's landing set W: swimmable water 2+ deep; a one-deep puddle walked
   across on foot was gym shore-w5 run 1)."
  [world [x y z :as cell]]
  (and (= :water (terrain/fluid-kind world cell))
       (blocks/source? (terrain/block-at world cell))
       (= :water (terrain/fluid-kind world [x (dec y) z]))
       (not= :water (terrain/fluid-kind world [x (inc y) z]))))

(defn shallow?
  "Is the cell one-deep water: water with no water over it and a solid under it, which the feet
   stand in with the eye in air."
  [world [x y z :as cell]]
  (and (= :water (terrain/fluid-kind world cell))
       (not= :water (terrain/fluid-kind world [x (inc y) z]))
       (solid-cell? world [x (dec y) z])))

(defn wadeable?
  "Could the feet rest in cell on the way into deep water: a cell they can stand in, or
   one-deep water with a clear head cell (a beach's shelf; the bank beside the first deep
   cell is usually that, not sand)."
  [world [x y z :as cell]]
  (or (path/standable? world cell)
      (and (shallow? world cell) (= :clear (path/classify world [x (inc y) z])))))

(defn entries
  "Where the bot could walk into the water of chunk key: [bank water] pairs, the bank a cell
   the feet can stand in or wade through (wadeable?) beside a deep source of the column no more
   than wade-below under the feet (cave water under a beach is not the sea: gym shore-w2 runs 1, 3, 5, 7), nearest
   the feet first; blacklist removes banks a wade failed to reach (shore-w3 run 5: the nearest
   bank lay under a cliff and was chosen three times)."
  [world key [_ fy _ :as feet] blacklist]
  (let [column (get-in world [:world/chunks key])
        sources (for [[x y z] (chunk/find-blocks {key column} blocks/fluid)
                      :when (and (>= y (- fy wade-below)) (water-source? world [x y z]))]
                  [x y z])]
    (->> (for [[x y z :as water] sources
               [dx _ dz] path/around
               by [(inc y) y]
               :let [bank [(+ x dx) by (+ z dz)]]
               :when (and (not (blacklist bank)) (wadeable? world bank))]
           [bank water])
         (sort-by (fn [[bank _]]
                    (physics/horizontal-distance (physics/centre feet) (physics/centre bank))))
         distinct)))

(defn entry
  "The nearest way into water: over the remembered pools within wade-radius and no more than
   wade-below under the eye, nearest first, the first chunk with an entry gives its nearest.
   [bank water], or nil."
  [world feet]
  (let [eye (physics/eye (:player/pos world))
        ey (physics/y-of eye)]
    (->> (memory/pools world :water)
         (filter (fn [[_ [_ y _]]] (>= y (- ey wade-below))))
         (filter (fn [[_ pos]] (<= (physics/distance eye (physics/centre pos)) wade-radius)))
         (sort-by (fn [[_ pos]] (physics/distance eye (physics/centre pos))))
         (some (fn [[key _]] (first (entries world key feet (:plan/blacklist world #{}))))))))

(defn beyond
  "Where the wade heads: the water cell's centre until the body has been in water, then a
   point wade-beyond blocks ahead of pos on the plane in the direction from bank to water, so
   a shallow shelf is crossed into depth however wide it is. Heading past the water before
   reaching it walked a bot that came at the bank from the far side away from the sea for two
   minutes (gym shore-w5 run 9)."
  [[bx _ bz] [wx wy wz :as water] [px _ pz] wet?]
  (let [dx (- wx bx)
        dz (- wz bz)]
    (if wet?
      [(+ px (* wade-beyond dx)) (+ wy 0.5) (+ pz (* wade-beyond dz))]
      (physics/centre water))))

(defmethod intent/run :wade
  [world {:intent/keys [target water started child wet?]} event]
  (let [tick (:time/tick world)
        started (or started tick)
        wet? (or wet? (boolean (:player/in-water? world)))
        world (intent/assoc-intent world :intent/started started :intent/wet? wet?)]
    (cond (> (- tick started) wade-timeout-ticks)
          (-> world
              (assoc :player/controls {})
              (intent/fail :no-water))
          ;; was in the water and walked out the far side on land: a puddle, not a pool (gym
          ;; shore-w4 run 9); the bank is blacklisted by the failure and the next entry is tried
          (and wet? (not (:player/in-water? world)) (:player/on-ground? world) (nil? child))
          (-> world
              (assoc :player/controls {})
              (intent/fail :shallow))
          (nil? target)
          (if-let [[bank w] (entry world (intent/feet world))]
            (-> world
                (intent/assoc-intent
                 :intent/target bank
                 :intent/water w
                 :intent/child {:intent/kind :walk :intent/target bank :intent/status :active})
                (game/say (str "wade: to the bank " bank " into " w)))
            (-> world
                (assoc :player/controls {})
                (intent/fail :no-water)))
          (and child (intent/done? child)) (intent/dissoc-intent world :intent/child)
          (and child (intent/failed? child))
          (-> world
              (assoc :player/controls {})
              (intent/fail (:intent/reason child)))
          child (intent/run-child world event)
          ;; on the bank: straight in and on past the entry, jumping only when blocked with the
          ;; feet on the ground (a seabed step, shore-w4 runs 1 and 6), never afloat (a jump there
          ;; is a swim-up that keeps the eye in air against a wall), so it sinks as it deepens and
          ;; the planner's trap takes over
          ;; afloat and pressed against something: stop pushing and sink (pressing into a bank
          ;; fires the out-of-liquid impulse, which kept the eye in air for good)
          (and (:player/in-water? world)
               (:player/horizontal-collision? world)
               (not (:player/on-ground? world)))
          (assoc world :player/controls {:control/sneak? true})
          :else
          (let [controls (intent/toward world (beyond target water (:player/pos world) wet?) 0.0)]
            (assoc world
                   :player/controls
                   (assoc controls
                          :control/jump?
                          (boolean (and (:control/jump? controls) (:player/on-ground? world)))))))))

(defmethod plan/done-by :escaped-water?
  [world _]
  (boolean (some #(= :water (:trap/kind %)) (:world/traps world))))

(defmethod plan/next-intent :wade
  [world _ _]
  ;; already under the water (a landing in the sea): wait, the trap and the escape are a second
  ;; away, and a wade that fails three routes first would end the plan (gym shore-w2 runs 6, 9);
  ;; otherwise a wade needs a way into water it remembers, and with none it waits, as a gather
  ;; with no tree does
  (cond (:player/eye-in-water? world) {:plan/wait :in-water}
        (entry world (intent/feet world)) {:intent/kind :wade}
        :else {:plan/wait :no-water}))
