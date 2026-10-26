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
   digs the block at feet height ahead: that is the bank the impulse needs to collide with. When
   the only water cell a block would make a bank of intersects the player's box, it backs off
   the bank and lays once the box has left that cell, instead of failing :stuck (issue #351). With
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
            [clojurecraft.place :as place]
            [clojurecraft.plan :as plan]
            [clojurecraft.stairs :as stairs]
            [clojurecraft.terrain :as terrain]
            [clojurecraft.trap :as trap]))

(set! *warn-on-reflection* true)

(def bank-radius 16)                  ; how far on the plane a bank is looked for (gym shore-w1 run 6: none within 12 from mid-sea)
(def bank-rise 1)                     ; a bank at most this far above the highest feet level afloat (steve's landing set W)
(def bank-drop 3)                     ; or this far below it: a roofed pool's air pocket is sideways and down
(def float-depth 1.03)                ; feet below an open surface when afloat with jump held (vanilla's equilibrium)
(def stuck-ticks 40)                  ; 2 s without progress toward the bank → dig what is in the way
(def backoff-ticks 100)               ; 5 s to drift off a lay cell the body is covering before :stuck
(def progress 0.05)                   ; blocks nearer the bank that count as progress
(def escape-timeout-ticks 2400)       ; 120 s: an escape longer than this has failed
(def dig-credit-ticks 6000)           ; 300 s: the most the escape's finished digs add to that, so a wall dug by hand is not timed out
(def search-every-ticks 20)           ; with no bank in reach yet, look again this often while rising
(def notch-radius 4)                  ; how far on the plane a wall is notched into for a bank when there is none (a dig's reach)
(def approach-radius 8)                ; how far on the plane a wall's bank-block cell is swum toward when it is out of the place reach
(def rise-clear 2)                    ; feet under a rise's top where it is still in its shaft: no bank is looked for deeper
(def centre-slack 0.15)               ; how far off its column's centre a body rising up a shaft may drift
(def shore-reach 64)                  ; how far along eight bearings a swim with no bank in reach looks for dry ground
(def cross-timeout-ticks 2400)        ; 120 s to swim a crossing to the trees
(def cross-stuck-ticks 100)           ; 5 s without coming nearer the far side: a crossing is :stuck
(def cross-gain 8)                    ; blocks nearer the trunk than at the start before land ends a crossing
(def flee-radius 6)                   ; how far a lava flee looks for footing
(def flee-timeout-ticks 200)          ; 10 s

(defn solid-cell?
  "Is the cell loaded and a solid block?"
  [world cell]
  (boolean (some-> (terrain/block-at world cell)
                   blocks/solid?)))

(defn diggable-cell?
  "Is the cell a solid block a dig can take: not ore above every held pickaxe's tier
   (stairs/too-hard?), which a dig refuses :needs-tool? A real race's bot Clj_r10 (issue #163), in a flooded shaft at 2 health with no pickaxe, chose iron ore for its notch three times, each
   dig refused, and the escape ended the plan."
  [world cell]
  (and (solid-cell? world cell) (not (stairs/too-hard? world cell))))

(defn surface-y
  "The y of the first cell above the feet cell that is not the fluid kind: the water's
   surface cell, or the roof over it."
  [world kind [x y z]]
  (or (first (filter #(not= kind (terrain/fluid-kind world [x % z])) (range y (+ y 16)))) (+ y 16)))

(defn in-the-open?
  "Is pos under the open sky with no water or lava over it (terrain/under-sky? alone lets water
   through: a cell on the sea floor is under it)?"
  [world [x y z :as pos]]
  (and (terrain/under-sky? world pos)
       (not-any? #(terrain/fluid-kind world [x % z]) (range (inc y) (+ y 32)))))

(defn float-top
  "The highest feet level the body reaches in this column: float-depth under an open surface
   cell, or the body's height under a roof; rounded to the cell the impulse can lift from."
  [world [x _ z] surface]
  (math/round
   (if (solid-cell? world [x surface z]) (- surface physics/height) (- surface float-depth))))

(defn bank?
  "Could the feet rest in cell once out of the water: a cell they could stand in
   (path/standable?), or one over a lake's ice (stairs/lake-ice) with the body and the head clear.
   Neither body cell may contain fluid: a passable water plant is not dry land.
   A route never stands on ice (path/awkward-types: the physics does not slide), but the impulse
   lifts the body onto it as onto any solid, and on it the bot is on dry land (trap/dry-land?).
   Sim race 38806318231 world R2/43 (issue #177, 83 runs): the bot came up under a frozen sea,
   dug the ice over its head and floated in the hole, the ice round it a block over the water's
   top, and with no other bank within sixteen it pressed toward a far shore under the ice until
   its escape timed out, three times, :no-escape."
  [world [x y z :as cell]]
  (and (or (path/standable? world cell)
           (boolean (and (some-> (terrain/block-at world [x (dec y) z])
                                 blocks/name-of
                                 stairs/lake-ice)
                         (path/clear? world cell)
                         (path/clear? world [x (inc y) z]))))
       (not (terrain/fluid-kind world cell))
       (not (terrain/fluid-kind world [x (inc y) z]))))

(defn banks
  "Cells the feet could stand in, nearest first, within bank-radius of feet on the plane and
   between bank-drop under the float-top and bank-rise over it (a ledge higher than that is
   out of the impulse's lift); blacklist removes cells that failed. Under the open sky (the feet
   with nothing over them but water and air, terrain/under-sky?), only banks in the open
   (in-the-open?): a pocket under the rock beside the water, or a ledge under it, is no way out
   of the sea over it. Issue #89 (sim race 38811426215 runner 8, worlds R4/21 and R1/32): a bot that dug up into the sea from its tunnel
   under the sea floor swam back into the tunnel's dry end, the trap cleared, and it climbed and
   dug again, until its run's time ran out."
  ([world feet surface blacklist]
   (banks world
          feet
          surface
          blacklist
          (if (terrain/under-sky? world feet) #(in-the-open? world %) any?)))
  ([world [fx _ fz :as feet] surface blacklist keep?]
   (->> (for [dx (range (- bank-radius) (inc bank-radius))
              dz (range (- bank-radius) (inc bank-radius))
              :let [top (float-top world feet surface)]
              y (range (- top bank-drop) (+ top bank-rise 1))
              :let [cell [(+ fx dx) y (+ fz dz)]]
              :when (and (not (blacklist cell)) (bank? world cell) (keep? cell))]
          cell)
        (sort-by (fn [cell] [(physics/horizontal-distance (physics/centre feet)
                                                          (physics/centre cell))
                             (- (physics/y-of cell))]))
        first)))

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
   head height, else the cell ahead at feet height when the bank is no higher than the feet (a
   wall between them, not a bank to climb), else the cell above the head ahead (clearance for
   the lift onto a bank), else the cell over the head (the cap of a roofed pool);
   both axes, their corner and the body's edges are checked.
   Never the cell ahead at feet height under a higher bank, which is the step the impulse
   needs to collide with. nil when none of them is solid. Race 38805758078 runner 1 (issues
   #156, #157) bot Clj_r44, in a flooded tunnel at y 16, had its bank two blocks off at its own
   height with a block between at the feet; the swim dug the ceiling over its head again and
   again, then nothing was named and it failed :stuck. Race 38999008545 bot Clj_r06 (issue #156)
   reached a step with air above it but packed ice one higher, which stopped the lift."
  [world [x y z :as feet] bank]
  (let [over [x (+ y 2) z]
        [x0 _ z0 x1 _ z1] (physics/aabb (get world :player/pos (physics/centre feet)))
        levels (when-let [[bx _ bz] bank]
                 (let [nx (+ x (Long/signum (- bx x)))
                       nz (+ z (Long/signum (- bz z)))
                       xs (distinct [x nx (long (math/floor x0)) (long (math/floor x1))])
                       zs (distinct [z nz (long (math/floor z0)) (long (math/floor z1))])]
                   (remove #{feet}
                           (distinct (concat [(ahead feet bank) [nx y z] [x y nz] [nx y nz]]
                                             (for [cx xs cz zs] [cx y cz]))))))
        head (first (filter #(diggable-cell? world %) (map #(update % 1 inc) levels)))
        level (when (and bank (<= (second bank) y))
                (first (filter #(diggable-cell? world %) levels)))
        upper (first (filter #(diggable-cell? world %) (map #(update % 1 + 2) levels)))]
    (cond (lid? world feet) over
          head head
          level level
          upper upper
          (diggable-cell? world over) over
          :else nil)))

(defn iced?
  "Is cell a lake's ice (stairs/lake-ice) that is loaded: the roof a rise through the water under a
   frozen lake ends at, and digs?"
  [world cell]
  (boolean (some-> cell
                   (->> (terrain/block-at world))
                   blocks/name-of
                   stairs/lake-ice)))

(defn notch
  "With no bank in reach, the cell to dig toward one: a notch in the nearest wall, two cells
   over a solid at the float-top level (the floor the impulse lifts onto, as a pond's bank is
   one over the water's top), within notch-radius of feet on the plane and a dig's reach of
   the current eye; the lower cell while it is solid, then the upper. nil with no wall in reach
   (issue #73: an aquifer's rock walls rise straight from the water, so no cell within sixteen
   was standable and the escape rose until it timed out), and nil under a roof on the water
   (the surface cell solid): a capped well is left through its cap, which the escape digs once
   it has risen to it, and a notch cut there from the floor opened the well's own wall under the
   cap and drowned the bot (sim-test a-capped-well-is-dug-out-through-its-cap-with-the-pickaxe,
   red since #113, issue #116)."
  [world [fx _ fz :as feet] surface]
  (let [top (float-top world feet surface)
        eye (game/eye world)
        roofed? (solid-cell? world [fx surface fz])]
    (->> (for [dx (if roofed? [] (range (- notch-radius) (inc notch-radius)))
               dz (range (- notch-radius) (inc notch-radius))
               :let [lower [(+ fx dx) (inc top) (+ fz dz)]
                     upper [(+ fx dx) (+ top 2) (+ fz dz)]
                     cell (first (filter #(diggable-cell? world %) [lower upper]))]
               :when (and (solid-cell? world [(+ fx dx) top (+ fz dz)])
                          cell
                          (<= (physics/distance eye (physics/centre cell)) intent/reach))]
           cell)
         (sort-by (fn [cell] [(physics/horizontal-distance (physics/centre feet)
                                                           (physics/centre cell)) cell]))
         first)))

(defn lay-candidates
  "The water cells a filler would make a bank of, nearest first, the higher one first at a
   tie, including a cell the player's box intersects: at the float-top, and one under it
   (the body lifted into the air over the water sits a cell above the surface block), within
   radius on the plane and reach of the eye, a solid neighbour to place against, two clear
   cells over it in the open. nil unless the feet are under the sky."
  [world [fx _ fz :as feet] surface radius reach]
  (when (terrain/under-sky? world feet)
    (let [top (float-top world feet surface)
          eye (game/eye world)]
      (->> (for [dx (range (- radius) (inc radius))
                 dz (range (- radius) (inc radius))
                 y [top (dec top)]
                 :let [x (+ fx dx)
                       z (+ fz dz)
                       cell [x y z]
                       above [x (inc y) z]]
                 :when (and (= :water (terrain/fluid-kind world cell))
                            (path/clear? world above)
                            (path/clear? world [x (+ y 2) z])
                            (in-the-open? world above)
                            (place/support world cell)
                            (<= (physics/distance eye (physics/centre cell)) reach))]
             cell)
           (sort-by (fn [[_ y _ :as cell]] [(physics/horizontal-distance (physics/centre feet)
                                                                         (physics/centre cell))
                                            (- y) cell]))))))

(defn bank-cells
  "The water cells at the float-top level to lay a filler into that makes a bank (see
   bank-block), within radius on the plane and reach of the eye, nearest first; none unless the
   feet are under the sky. A cell the player's box intersects is left out: the server refuses
   a block placed there."
  [world feet surface radius reach]
  (some->> (lay-candidates world feet surface radius reach)
           (remove #(place/inside-player? world %))))

(defn off-bank
  "Controls that carry the body back off bank: forward toward the point as far past the
   position from the bank's centre, jump held so the body stays at the surface. The control map."
  [world bank]
  (let [pos (:player/pos world)
        away (mapv (fn [p c] (- (* 2.0 p) c)) pos (physics/centre bank))]
    (assoc (intent/toward world away 0.0) :control/jump? true)))

(defn bank-block
  "With no bank in reach and the water open to the sky over the feet, the water cell at the
   float-top level to lay a filler into that makes one: within notch-radius on the plane and the
   place reach of the eye, with a solid neighbour to place against (place/support: the wall the
   water meets), and over it two cells of open air with nothing wet or roofed over them
   (in-the-open?), so the block is a bank the lift reaches (bank-rise 1) and the body stands on.
   Nearest first. nil when none. A notch cut into a wall under the sky is never a bank (banks keeps
   only the open ones), so a sea that meets a cliff is left by a block, not a hole: race
   39109674329 runner 4 bot Clj_r16 (issue #92) swam 73 blocks to a cliff three blocks over the
   water with 489 cobblestone and no pickaxe, dug a notch in it by hand (75 s a block under water)
   and was :no-escape three times in six minutes."
  [world feet surface]
  (first (bank-cells world feet surface notch-radius place/place-reach)))

(defn occupied-bank
  "The water cell at the float-top bank-block would lay a filler into, except the player's box
   intersects it so the server would refuse the block. nil when a free cell is in reach, or
   when none of the lay cells is occupied."
  [world feet surface]
  (when-not (bank-block world feet surface)
    (first (filter #(place/inside-player? world %)
                   (or (lay-candidates world feet surface notch-radius place/place-reach) ())))))

(defn wall-toward
  "With no bank-block in reach, the nearest cell of one within approach-radius on the plane out of
   the place reach (bank-cells), for the swim to head for until it is in reach; nil when none.
   Sim race 39550831606 (issue #92, world R4/9): the sea met a cliff that leaned over the water
   along the bot's reach, the cells under the lean were no bank, the open ones lay a block past
   the place reach, and the escape dug a notch in the wall by hand, 37 s a block, until it timed
   out, three times."
  [world feet surface]
  (first (bank-cells world feet surface approach-radius ##Inf)))

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

(defn lift-bank
  "[cell item] to lay for a bank (bank-block) with a filler carried, nil when none is carried, one
   was refused (no-lay) or no cell does."
  [world feet surface no-lay]
  (when-not no-lay
    (when-let [item (stairs/filler world 1)]
      (when-let [cell (bank-block world feet surface)] [cell item]))))

(defn start-lay
  "Lay item into cell as a child :place now."
  [world [cell item] why]
  (-> world
      (intent/assoc-intent
       :intent/child
       {:intent/kind :place :intent/item item :intent/cell cell :intent/status :active}
       :intent/best-tick (:time/tick world))
      (intent/dissoc-intent :intent/dig-when-down)
      (game/say (str "swim-out: " why ", laying " (name item) " into " cell))))

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
   dig is from and jump is held off, unless the floor is out of the dig's reach (the ice a rise
   under a frozen lake digs, ten over the sea floor: sim race 39088700340 bot Clj_r02, issue #71,
   sank three blocks during each dig of 4.7 s and dug a new hole each time until it drowned):
   there jump is held, and the body stays against the roof."
  [world event]
  (let [world (intent/run-child world event)
        [x _ z :as feet] (intent/feet world)]
    (cond-> world
      (and (:player/in-water? world)
           (or (not (solid-cell? world [x (surface-y world :water feet) z]))
               (not (some->> (:intent/target (:intent/child (:plan/intent world)))
                             (dig-from-floor? world feet)))))
      (update :player/controls assoc :control/jump? true))))

(defn dug-credit
  "The :intent/dug a :swim-out has after its child dig finished: dug, plus the ticks the dig
   took (from its start to now), at most dig-credit-ticks in all. A dig is progress toward the
   bank, not time lost afloat, and by hand it is slow: sim race 39598424082 (issue #344, world
   R4/16) swam fifty blocks to a cliff with no pickaxe, dug a notch in it at 37 s a block, and
   its 120 s ran out between the second and the third cell, three times."
  [world dug {:intent/keys [started]}]
  (min dig-credit-ticks
       (+ (or dug 0)
          (max 0 (quot (- (:time/now world) (or started (:time/now world))) physics/tick-ms)))))

(defn flooded?
  "Has the bank a swim-out keeps been flooded since it was picked: is it no longer a cell the
   feet could stand in (bank?), water now in it or over its floor? Reached, it holds
   the body in water: the trap never clears, no step brings it nearer, and stuck-ticks later
   the swim-out digs over its head and fails :stuck. Race 38805758078 runner 1 bot Clj_r44
   (issue #157) and race 38709226128 runner 8 bot Clj_r31 (issue #156), replayed in probe
   38830884734: in a flooded tunnel the bank picked a block off was reached at distance 0 with
   water flowed into it, and the escape failed :stuck there, the third failure of the plan."
  [world bank]
  (not (bank? world bank)))

(def shore-clearance 3)               ; cells over the water's surface a shore column is first looked at: a roof higher is not the ground
(def shore-depth 4)                   ; and cells under it the look goes on to find the ground

(defn shore-ground?
  "Is column [x z] ground at the water's surface: scanning down from shore-clearance over surface
   to shore-depth under it, the first cell that is water or solid is solid and not a lake's ice;
   false when it is water, or when the column is not loaded. Not the top of the column
   (stairs/dry-top?): a block high over the sea made every column under it ground. Sim race
   39550831606 runner 4 (issue #177, world R4/1): a row of cobblestone at y 105 over the sea made
   the sea out to y 62 the 'shore' of the swim, and the escape swam to the middle of the sea
   and floated there."
  [world [x z] surface]
  (true? (first (keep (fn [y]
                        (let [cell [x y z]]
                          (cond (terrain/fluid-kind world cell) false
                                (solid-cell? world cell) (not (iced? world cell))
                                :else nil)))
                      (range (+ surface shore-clearance) (- surface shore-depth 1) -1)))))

(defn far-shore
  "With no bank in reach and the water open to the sky over the feet: the nearest column, along
   eight bearings from bank-radius out to shore-reach, whose top at the water's surface is dry
   ground (shore-ground?), as the point at the water's surface to swim toward. Each pass
   that looks for a bank looks again, so the bank found near the shore takes over. Before it, an
   escape at sea rose to the surface and floated there until it timed out: sim race
   38811426215 runner 8 (issue #89, worlds R4/21 and R1/32 at 76010d2) had bots under the sea floor
   with land 33 and 40 blocks off, the only way out up through the water. With none, the point
   over the landing (:plan/go-at), which is land: world R4/30's bot stood under the sea 180
   blocks from it, no shore within reach. nil with neither. The shore chosen before (kept, the
   swim-out's :intent/shore) is kept while its column is dry ground still: sim race 39550831606
   runner 4 (issue #177, world R4/1) floated sixteen blocks from a shore on each side, and the
   first bearing at that distance changed with the cell the feet were in, so each stroke west
   made the east shore the nearer and each stroke east the west: it dithered about one cell
   for the escape's 120 s, three times, :no-escape."
  ([world feet surface] (far-shore world feet surface nil))
  ([world [fx _ fz] surface kept]
   (or (when (and kept (shore-ground? world [(first kept) (nth kept 2)] surface)) kept)
       (->> (range 8)
            (keep (fn [k]
                    (let [a (* k (/ math/PI 4))
                          [dx dz] [(math/cos a) (math/sin a)]]
                      (some (fn [d]
                              (let [x (long (math/floor (+ fx 0.5 (* d dx))))
                                    z (long (math/floor (+ fz 0.5 (* d dz))))]
                                (when (shore-ground? world [x z] surface) [d [x surface z]])))
                            (range bank-radius (inc shore-reach))))))
            (sort-by first)
            (map second)
            first)
       (when-let [[hx _ hz] (:plan/go-at world)]
         [(long (math/floor hx)) surface (long (math/floor hz))]))))

(defn shore-controls
  "Rising with jump held: while the feet are more than two under top (the water's top), drawn to
   the centre of their column when off it by more than centre-slack, so a body in a shaft one
   wide rises clear of its rim; nearer the top, toward shore (far-shore) when there is one.
   Issue #89 (world R1/32): a rise begun 0.3 off its shaft's centre caught the sea floor's edge
   with its head and drowned there."
  [world shore top]
  (let [[px _ pz] (:player/pos world)
        [fx fy fz] (intent/feet world)
        centre [(+ fx 0.5) (+ fy 1.0) (+ fz 0.5)]
        deep? (< fy (- top rise-clear))]
    (cond (and deep? (> (physics/horizontal-distance [px 0 pz] centre) centre-slack))
          (assoc (intent/toward world centre 0.0) :control/jump? true)
          (and shore (not deep?))
          (assoc (intent/toward world (physics/centre shore) 0.0) :control/jump? true)
          :else {:control/jump? true})))

(defn swim-controls
  "Toward the bank's centre: in the water, forward with jump held (the swim-up and, pressed
   into the bank, the impulse); out of it, a plain walk, and on dry land nothing at all, so the
   planner can see the land held still (a bot that kept jumping on the bank never counted as
   out). A bank below the feet (a dry pocket under a thin sheet of water, the floor of a
   flooded room) is sunk to, not swum over: jump is let go so the body drops onto it. Sim
   race 40014095820 (issue #222, world R1/2) and the real snapshot real-1-s1-Clj_r02 bobbed
   at the surface over such a bank three blocks down and failed :stuck three times."
  [world bank]
  (cond (trap/dry-land? world) {}
        (:player/in-water? world)
        (if (< (second bank) (second (intent/feet world)))
          (intent/toward world (physics/centre bank) 0.0)
          (assoc (intent/toward world (physics/centre bank) 0.0) :control/jump? true))
        :else (intent/toward world (physics/centre bank) 0.0)))

(defmethod intent/run :swim-out
  ;; an escape's swim-out is ended by its goal, when the trap clears; one for a :retreat (out of a
  ;; flooded tunnel) ends itself on dry land, and one for a :rise (up a shaft dug into the sea,
  ;; wood/into-water) on dry land in the open
  [world
   {:intent/keys [bank started best-dist best-tick child next-search dig-when-down for shore to-y
                  no-lay dug]} event]
  (let [tick (:time/tick world)
        started (or started tick)
        feet (intent/feet world)
        surface (surface-y world (if (:player/in-lava? world) :lava :water) feet)
        world (intent/assoc-intent world :intent/started started)
        dist (when bank
               (if (< (second bank) (second feet))
                 (physics/distance (:player/pos world) (physics/centre bank))
                 (physics/horizontal-distance (:player/pos world) (physics/centre bank))))
        nearer? (and dist (< dist (- (or best-dist Double/MAX_VALUE) progress)))
        best-tick (if (or nearer? (nil? best-tick)) tick best-tick)]
    (cond
      (or (and (= :retreat for) (trap/dry-land? world))
          (and (= :rise for) (trap/dry-land? world) (in-the-open? world feet)))
      (-> world
          (assoc :player/controls {})
          intent/done)
      (> (- tick started) (+ escape-timeout-ticks (or dug 0)))
      (-> world
          (assoc :player/controls {})
          (intent/fail :no-escape))
      (and child (intent/done? child))
      (cond-> (intent/dissoc-intent world :intent/child :intent/best-tick)
        (= :dig (:intent/kind child))
        (intent/assoc-intent :intent/dug (dug-credit world dug child)))
      (and child (intent/failed? child) (= :out-of-reach (:intent/reason child)))
      (-> world
          (assoc :player/controls {:control/jump? true})
          (intent/dissoc-intent :intent/child :intent/best-tick :intent/next-search))
      (and child (intent/failed? child) (= :place (:intent/kind child)))
      (-> world
          (intent/dissoc-intent :intent/child :intent/best-tick :intent/next-search)
          (intent/assoc-intent :intent/no-lay true))
      (and child (intent/failed? child))
      (-> world
          (assoc :player/controls {})
          (intent/fail (:intent/reason child)))
      child (dig-afloat world event)
      (and dig-when-down (or (:player/on-ground? world) (not (:player/in-water? world))))
      (start-dig world dig-when-down "down")
      dig-when-down (assoc world :player/controls {:control/sneak? true})
      (and bank
           (or (flooded? world bank)
               (and (> (- tick best-tick) stuck-ticks)
                    (< (second bank) (- (float-top world feet surface) bank-drop)))))
      (-> world
          (intent/dissoc-intent :intent/bank :intent/target :intent/best-dist :intent/best-tick)
          (game/say (str "swim-out: bank " bank " no longer usable, looking again")))
      (and (nil? bank) next-search (< tick next-search))
      ;; rise until the next look
      (assoc world
             :player/controls
             (shore-controls world shore (or to-y (inc (float-top world feet surface)))))
      (nil? bank)
      (let [rise? (= :rise for)
            deep? (and rise?
                       (< (second feet)
                          (- (or to-y (inc (float-top world feet surface))) rise-clear)))
            b (cond deep? nil
                    rise?
                    (banks world feet surface (:plan/blacklist world #{}) #(in-the-open? world %))
                    :else (banks world feet surface (:plan/blacklist world #{})))
            cap (when-not b
                  (let [c (obstacle world feet nil)] (when (or (not rise?) (iced? world c)) c)))
            laid (when-not (or b cap rise?) (lift-bank world feet surface no-lay))
            toward (when-not (or b cap laid rise? no-lay)
                     (when (stairs/filler world 1) (wall-toward world feet surface)))
            wall (when-not (or b cap laid toward rise?) (notch world feet surface))]
        (cond b
              (-> world
                  (intent/assoc-intent :intent/bank b :intent/target b)
                  (game/say (str "swim-out: bank " b)))
              cap (dig-or-sink world feet cap "no bank")
              laid (start-lay world laid "no bank")
              ;; a wall's laid bank is just out of the place reach: swim along to it
              toward
              (-> world
                  (assoc
                   :player/controls
                   (shore-controls world toward (or to-y (inc (float-top world feet surface)))))
                  (intent/assoc-intent :intent/next-search (+ tick search-every-ticks)
                                       :intent/shore toward)
                  (game/say (str "swim-out: no bank, toward the wall at " toward)))
              wall (start-dig world wall "no bank, notching the wall")
              ;; nothing in reach: rise, toward a shore when the water is open over the feet
              ;; (the roof or a bank comes into reach, or the timeout ends it)
              :else
              (let [[fx _ fz] feet
                    shore (when-not (solid-cell? world [fx surface fz])
                            (far-shore world feet surface shore))]
                (-> world
                    (assoc
                     :player/controls
                     (shore-controls world shore (or to-y (inc (float-top world feet surface)))))
                    (intent/assoc-intent :intent/next-search (+ tick search-every-ticks))
                    (cond-> shore (intent/assoc-intent :intent/shore shore))
                    (cond-> (nil? shore) (intent/dissoc-intent :intent/shore))))))
      (> (- tick best-tick) stuck-ticks)
      (let [cell (obstacle world feet bank)
            laid (when-not cell (lift-bank world feet surface no-lay))
            blocked (when (and (nil? cell) (nil? laid) (not no-lay) (stairs/filler world 1))
                      (occupied-bank world feet surface))
            ;; under a roof a block cannot be laid (bank-block wants the open sky), so a bank
            ;; the lift cannot reach is made the notch's way instead: carve a stand at the
            ;; body's own level and let the next search pick it (issue #222, world R1/2)
            carve
            (when (and (nil? cell) (nil? laid) (nil? blocked) (not (terrain/under-sky? world feet)))
              (notch world feet surface))
            stall (- tick best-tick)]
        (cond cell (dig-or-sink world feet cell "stuck")
              ;; nothing to dig and the lift peaks under the bank's top: a block laid into the water
              ;; beside the feet is a step the walk climbs from (issue #45 R1-17-2)
              laid (start-lay world laid "lift short of the bank")
              ;; the only such cell intersects the body, so a placement would be refused: drift
              ;; back off the bank until the cell is free, then the same check lays (issue #351)
              (and blocked (<= stall (+ stuck-ticks backoff-ticks)))
              (-> world
                  (assoc :player/controls (off-bank world bank))
                  (game/say (str "swim-out: lay cell " blocked " is inside the body, backing off")))
              carve
              (-> world
                  (intent/dissoc-intent :intent/bank :intent/target
                                        :intent/best-dist :intent/best-tick)
                  (start-dig carve "stuck, carving a bank"))
              :else
              (-> world
                  (assoc :player/controls {})
                  (intent/fail :stuck))))
      :else
      (-> world
          (assoc :player/controls (swim-controls world bank))
          (intent/assoc-intent :intent/best-dist (if nearer? dist (or best-dist dist))
                               :intent/best-tick best-tick)))))

(defmethod intent/run :cross
  ;; over open water toward a remembered trunk across it (wood/crossing): straight at it, on
  ;; land jumping only when blocked, in the water with jump held, so the body swims at the
  ;; surface with the eye in air and the trap never calls the escape back to the near bank;
  ;; done on dry land once it has been in the water and come cross-gain nearer the trunk, so a
  ;; wade along the near shore is not the far side. Stuck in the water (a far bank too high
  ;; for the swim's lift), a child :swim-out for :retreat takes it to the nearest bank and the
  ;; crossing ends on dry land: sim race 38844589684 runner 6 (issue #57, world R1/30 seed 4,
  ;; probe 38853120299) swam forty blocks, stuck eight short against the far bank, and failed
  ;; :stuck three times between other intents before a climb and an escape got it out
  ;; A solid wall is cleared by child digs before retreating: race 39060308623 bot Clj_r14
  ;; (issue #156) met packed ice beside its starting bank and never crossed it.
  [world {:intent/keys [target started wet? from-dist best-dist best-tick child dig-when-down]}
   event]
  (let [tick (:time/tick world)
        started (or started tick)
        wet? (or wet? (boolean (:player/in-water? world)))
        dist (physics/horizontal-distance (:player/pos world) (physics/centre target))
        from-dist (or from-dist dist)
        nearer? (< dist (- (or best-dist Double/MAX_VALUE) progress))
        best-tick (if (or nearer? (nil? best-tick)) tick best-tick)
        feet (intent/feet world)
        cell (when (> (- tick best-tick) cross-stuck-ticks) (obstacle world feet target))
        world (intent/assoc-intent world
                                   :intent/started started
                                   :intent/wet? wet?
                                   :intent/from-dist from-dist
                                   :intent/best-dist (if nearer? dist best-dist)
                                   :intent/best-tick best-tick)]
    (cond (and child (intent/done? child) (= :dig (:intent/kind child)))
          (-> world
              (assoc :player/controls {})
              (intent/dissoc-intent :intent/child :intent/best-tick))
          (and child (intent/done? child))
          (-> world
              (assoc :player/controls {})
              (intent/dissoc-intent :intent/child)
              intent/done)
          (and child (intent/failed? child))
          (-> world
              (assoc :player/controls {})
              (intent/fail (:intent/reason child)))
          (> (- tick started) cross-timeout-ticks)
          (-> world
              (assoc :player/controls {})
              (intent/fail :no-crossing))
          child
          (if (= :dig (:intent/kind child)) (dig-afloat world event) (intent/run-child world event))
          (and dig-when-down (or (:player/on-ground? world) (not (:player/in-water? world))))
          (start-dig world dig-when-down "down")
          dig-when-down (assoc world :player/controls {:control/sneak? true})
          (and wet? (trap/dry-land? world) (<= dist (- from-dist cross-gain)))
          (-> world
              (assoc :player/controls {})
              intent/done)
          cell (dig-or-sink world feet cell "crossing blocked")
          (and (> (- tick best-tick) cross-stuck-ticks) (:player/in-water? world))
          (-> world
              (intent/assoc-intent
               :intent/child
               {:intent/kind :swim-out :intent/for :retreat :intent/status :active})
              (game/say "cross: stuck in the water, to the nearest bank")
              (intent/run-child event))
          (> (- tick best-tick) cross-stuck-ticks)
          (-> world
              (assoc :player/controls {})
              (intent/fail :stuck))
          :else
          (assoc world
                 :player/controls
                 (cond-> (intent/toward world (physics/centre target) 0.0)
                   (:player/in-water? world) (assoc :control/jump? true))))))

(defn lava-free?
  "Is there no lava in cell, in the head's cell over it, nor in the cell under it: a place the body
   is out of lava once it stands there?"
  [world [x y z]]
  (not-any? #(= :lava (terrain/fluid-kind world %)) [[x (dec y) z] [x y z] [x (inc y) z]]))

(defn lava-beside?
  "Does lava touch cell on any of its four sides?"
  [world [x y z]]
  (boolean (some #(= :lava (terrain/fluid-kind world %))
                 [[(inc x) y z] [(dec x) y z] [x y (inc z)] [x y (dec z)]])))

(defn footing
  "The nearest cell within flee-radius of feet, at most one up or down, the feet could stand in,
   those out of the lava first (lava-free?: none in it, over it or under it), then those with no
   lava beside them (lava-beside?); a cell the flow covers only when there is no other; nil when
   none is standable. A cell the flowing lava covers is standable to a route, and a
   flee to it ends still in the lava: sim race 38844683780 runner 7 (issue #230, world R2/9)
   smelted in a pocket at y 38 when lava from a source ten over flowed in, fled to a cell the
   flow covered, and timed out :no-footing, three times."
  [world [fx fy fz :as feet]]
  (->> (for [dx (range (- flee-radius) (inc flee-radius))
             dz (range (- flee-radius) (inc flee-radius))
             dy [0 -1 1]
             :let [cell [(+ fx dx) (+ fy dy) (+ fz dz)]]
             :when (and (not= cell feet) (path/standable? world cell))]
         cell)
       (sort-by (juxt #(if (lava-free? world %) 0 1)
                      #(if (lava-beside? world %) 1 0)
                      #(physics/horizontal-distance (physics/centre feet) (physics/centre %))))
       first))

(defn flee-controls
  "The controls a flee holds toward target: forward with jump while in the lava or against a
   step, forward alone out of it, nothing once centred in the target, so the body lands and the
   trap clears on dry land (trap/dry-land? held dry-ticks-to-clear). Jump held over dry ground
   bounces the body off it every tick, and the trap never clears: sim races 38844683780 runner
   7 and 38843557186 runner 3 (issues #230 and #231, worlds R2/9 and R1/3) came out of the lava
   onto the footing and bounced on it until flee-timeout-ticks failed them :no-footing."
  [world target]
  (cond (intent/centred? world target) {}
        (or (:player/in-lava? world) (:player/horizontal-collision? world))
        (assoc (intent/toward world (physics/centre target) 0.0) :control/jump? true)
        :else (intent/toward world (physics/centre target) 0.0)))

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
            ;; no footing from where the body is now: it may have sunk past the level the
            ;; footing stands at (footing looks one up or down), so rise in the lava and
            ;; look again next tick; only the timeout gives up. Sim race 40023362147
            ;; (issue #231, world R1/3): the body fell into the lava under its tunnel,
            ;; sank a cell below the tunnel's floor, and three flees failed :no-footing
            ;; in three ticks without moving.
            (assoc world :player/controls {:control/jump? true}))
          :else (assoc world :player/controls (flee-controls world target)))))

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
