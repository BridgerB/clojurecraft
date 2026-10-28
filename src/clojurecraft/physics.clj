(ns clojurecraft.physics
  "Vanilla land and liquid movement as a pure function over the world's :player/* attributes.
   Full-cube collision, and the flat boxes of carpets, which the body stands on and steps onto
   (rise-onto); a one-block ledge needs a jump, as in vanilla.

   (step solid? world controls) → world'            dry: no fluid anywhere
   (step solid? fluid-at world controls) → world'   with fluids

   solid? is (fn [x y z] → nil | top) over block coordinates: nil or false for nothing, true or
   1.0 for a full cube, a number under 1 for a box that high (terrain/solid-fn, a carpet's
   0.0625); fluid-at is (fn [x y z] → nil |
   {:fluid/kind :water|:lava :fluid/level 0-15}); controls is {:control/forward? bool
   :control/jump? bool :control/sneak? bool :control/yaw deg}. In a liquid the step uses
   vanilla's liquid rules (the siblings' ports of the client): no buoyancy to speak of, a swim-up
   of 0.04 a tick while jumping, and the one real lift, an impulse of 0.3 when pressing into a
   bank whose top is free, asked while the box touches a fluid at all (touching-fluid?), a dry
   body in a fluid's last inch included. Every tick the step also writes what it found: :player/in-water?,
   :player/in-lava?, :player/eye-in-water? and :player/fall-distance."
  (:require [clojure.math :as math]))

(set! *warn-on-reflection* true)

(def ^:const tick-ms 50)               ; one game tick: the server runs twenty a second; per tick below means per one of these
(def gravity 0.08)                    ; blocks per tick², subtracted from vy each tick
(def vertical-drag 0.98)              ; vy is multiplied by this each tick
(def half-width 0.3)                  ; the player box is 0.6 wide
(def height 1.8)                      ; the player box height
(def eye-height 1.62)                 ; eye above the feet
(def face-offsets
  "Each block face by name, as the step from the block to the cell beyond that face."
  {:down [0 -1 0] :up [0 1 0] :north [0 0 -1] :south [0 0 1] :west [-1 0 0] :east [1 0 0]})
(def jump-velocity 0.42)              ; vy given by a jump
(def step-height 0.6)                 ; blocks a body on the ground walking into a low box rises onto it (vanilla's maxUpStep): a carpet, never a block
(def ground-inertia (* 0.6 0.91))                     ; slipperiness × 0.91
(def air-inertia 0.91)                ; horizontal velocity kept per tick in the air
(def air-acceleration 0.02)           ; horizontal input acceleration in the air
(def ground-acceleration (* 0.1 (/ 0.16277136 (math/pow ground-inertia 3)))) ; ≈ 0.1
(def negligible 0.003)                                ; velocities below this snap to zero
(def liquid-acceleration 0.02)        ; horizontal input acceleration in a liquid
(def water-inertia 0.8)               ; velocity kept per tick in water
(def lava-inertia 0.5)                ; velocity kept per tick in lava
(def water-gravity 0.005)             ; gravity in water (1/16 of land, 1.13+)
(def lava-gravity 0.02)               ; gravity in lava
(def swim-up 0.04)                    ; vy added per tick while jumping in a liquid
(def swim-down 0.04)                  ; vy taken per tick while sneaking in a liquid
(def out-of-liquid-impulse 0.3)       ; the lift when pressing into a bank whose top is free
(def bank-probe 0.6)                  ; how far up the box is moved to ask whether a bank's top is free, less what it rose this tick
(def forward-input 0.98)              ; the movement input a held forward key gives (vanilla scales input by 0.98)
(def jump-cooldown 10)                ; ticks after a jump before the next one
(def water-contract [0.001 0.401 0.001]) ; the box shrinks by this before asking "in water?"
(def lava-contract [0.1 0.4 0.1])     ; and by this for "in lava?"
(def touch-contract [0.001 0.001 0.001]) ; and by this for "touching a fluid at all?": vanilla's own test since 1.13, which the bank impulse is asked under

(defn aabb
  "[x0 y0 z0 x1 y1 z1] of a player whose feet centre is pos."
  [[x y z]]
  [(- x half-width) y (- z half-width) (+ x half-width) (+ y height) (+ z half-width)])

(defn eye "Eye position of a player whose feet centre is [x y z]." [[x y z]] [x (+ y eye-height) z])

(defn eye-cell
  "The cell [x y z] the eye position is in."
  [pos]
  (let [[x y z] (eye pos)] [(long (math/floor x)) (long (math/floor y)) (long (math/floor z))]))

(defn overlaps-column?
  "Does the player's body box at pos overlap column [cx cz]?"
  [[x _ z] [cx cz]]
  (and (< (- x half-width) (inc (long cx)))
       (> (+ x half-width) (long cx))
       (< (- z half-width) (inc (long cz)))
       (> (+ z half-width) (long cz))))

(defn y-of "The height of a point or a block position [x y z]: (y-of [3 64 5]) is 64." [[_ y _]] y)

(defn centre
  "The centre point of the block at integer position [x y z]."
  [[x y z]]
  [(+ x 0.5) (+ y 0.5) (+ z 0.5)])

(defn expand
  "The box grown along a velocity: the region a move may sweep through this tick."
  [[x0 y0 z0 x1 y1 z1] [vx vy vz]]
  [(+ x0 (min 0.0 vx)) (+ y0 (min 0.0 vy)) (+ z0 (min 0.0 vz)) (+ x1 (max 0.0 vx))
   (+ y1 (max 0.0 vy)) (+ z1 (max 0.0 vz))])

(defn solid-boxes
  "The boxes of every block touching the region that collides: a unit box for a full cube, one
   up to the top solid? gives (a carpet's 0.0625) for a flat shape."
  [solid? [x0 y0 z0 x1 y1 z1]]
  (for [x (range (long (math/floor x0)) (inc (long (math/floor x1))))
        y (range (long (math/floor y0)) (inc (long (math/floor y1))))
        z (range (long (math/floor z0)) (inc (long (math/floor z1))))
        :let [top (solid? x y z)]
        :when top]
    [(double x) (double y) (double z) (+ x 1.0) (+ y (if (number? top) (double top) 1.0))
     (+ z 1.0)]))

(defn overlaps?
  "Do boxes a and b overlap strictly along axis (0 x, 1 y, 2 z)? Touching faces do not."
  [a b axis]
  (and (< (double (a axis)) (double (b (+ axis 3)))) (> (double (a (+ axis 3))) (double (b axis)))))

(defn clip
  "Shrink the move d along axis so box does not enter any of boxes."
  [box boxes axis d]
  (let [others (remove #{axis} [0 1 2])]
    (reduce (fn [d c]
              (if (every? #(overlaps? box c %) others)
                (cond (and (pos? d) (>= (double (c axis)) (double (box (+ axis 3)))))
                      (min d (- (double (c axis)) (double (box (+ axis 3)))))
                      (and (neg? d) (<= (double (c (+ axis 3))) (double (box axis))))
                      (max d (- (double (c (+ axis 3))) (double (box axis))))
                      :else d)
                d))
            d
            boxes)))

(defn shift
  "The box moved d along axis."
  [box axis d]
  (-> box
      (update axis + d)
      (update (+ axis 3) + d)))

(defn squash
  "v, or 0.0 when it is below negligible (vanilla's velocity snap)."
  [v]
  (if (< (Math/abs (double v)) negligible) 0.0 v))

;; ---------------------------------------------------------------- fluids

(defn fluid-height
  "How high a fluid of level fills its block, 0-1 (vanilla's rendered depth): level 8 and
   above (falling) is full, else 1 - (level + 1) / 9, so a source (0) fills 8/9 of its block."
  [level]
  (if (>= level 8) 1.0 (- 1.0 (/ (inc level) 9.0))))

(defn in-fluid?
  "Does the player's box, contracted by [cx cy cz], overlap any block of fluid kind, counting a
   block only up to the height its level fills?"
  [fluid-at kind [cx cy cz] [x0 y0 z0 x1 y1 z1]]
  (let [x0 (+ x0 cx)
        y0 (+ y0 cy)
        z0 (+ z0 cz)
        x1 (- x1 cx)
        y1 (- y1 cy)
        z1 (- z1 cz)]
    (boolean (some
              (fn [[bx by bz]]
                (let [f (fluid-at bx by bz)]
                  (and f (= kind (:fluid/kind f)) (< y0 (+ by (fluid-height (:fluid/level f)))))))
              (for [bx (range (long (math/floor x0)) (inc (long (math/floor x1))))
                    by (range (long (math/floor y0)) (inc (long (math/floor y1))))
                    bz (range (long (math/floor z0)) (inc (long (math/floor z1))))]
                [bx by bz])))))

(defn eye-in-water?
  "Is the eye (y + 1.62, never feet + 1: a surface floater's eye is in air) inside water,
   counting the block only up to the height its level fills?"
  [fluid-at [x y z]]
  (let [ey (+ y eye-height)
        f (fluid-at (long (math/floor x)) (long (math/floor ey)) (long (math/floor z)))]
    (boolean (and f
                  (= :water (:fluid/kind f))
                  (< (- ey (math/floor ey)) (fluid-height (:fluid/level f)))))))

(defn no-fluid "A fluid oracle for a dry world." [_ _ _] nil)

(defn touching-water?
  "Does the player's box at pos overlap any water, however little (touch-contract)? Vanilla's
   test for being in water, since 1.13. The 0.4 rule of in-fluid? calls a body out of the water
   when under 0.4 of it is in: the bank impulse and a jump are still the water's then (issue #464:
   lifted to 0.1 under a one-block ledge, the body left the water and fell back)."
  [fluid-at pos]
  (in-fluid? fluid-at :water touch-contract (aabb pos)))

(defn touching-fluid?
  "Does the player's box at pos overlap any water or lava, however little (touch-contract)?"
  [fluid-at pos]
  (or (touching-water? fluid-at pos) (in-fluid? fluid-at :lava touch-contract (aabb pos))))

(def eye-reach
  "Half-width of the vanilla eye box: 0.8 of the body width. A full cube the body has only just
   entered intersects it; one the body merely touches does not."
  (* 0.8 half-width))

(defn full-cube?
  "Is solid?'s answer a full cube: true, or a number at least 1.0?"
  [top]
  (boolean (or (true? top) (and (number? top) (>= (double top) 1.0)))))

(defn eye-box-cells
  "The cells the eye box touches. The box is the eye point grown by eye-reach on x and z."
  [pos]
  (let [[x y z] (eye pos)
        y (long (math/floor y))]
    (for [cx (range (long (math/floor (- x eye-reach))) (inc (long (math/floor (+ x eye-reach)))))
          cz (range (long (math/floor (- z eye-reach))) (inc (long (math/floor (+ z eye-reach)))))]
      [cx y cz])))

(defn suffocation-cell
  "The full-cube cell the eye box touches: the point cell when that is one, otherwise another
   cell the box reaches. Nil when the box is clear. loaded?, when given, is (fn [x z] → bool);
   a cell whose chunk it rejects is skipped, so a column that has not arrived cannot count as
   a wall."
  ([solid? pos] (suffocation-cell solid? pos nil))
  ([solid? pos loaded?]
   (let [ok? (fn [[x y z]] (and (or (nil? loaded?) (loaded? x z)) (full-cube? (solid? x y z))))
         point (eye-cell pos)]
     (cond (ok? point) point
           :else (first (filter ok? (eye-box-cells pos)))))))

(defn suffocating?
  "Does a full cube (solid? collision-top >= 1.0) intersect the eye box?"
  ([solid? pos] (suffocating? solid? pos nil))
  ([solid? pos loaded?] (boolean (suffocation-cell solid? pos loaded?))))

(defn fluids-here
  "What the player at pos is in: {:in-water? :in-lava? :eye-in-water?}."
  [fluid-at pos]
  (let [box (aabb pos)]
    {:in-water? (in-fluid? fluid-at :water water-contract box)
     :in-lava? (in-fluid? fluid-at :lava lava-contract box)
     :eye-in-water? (eye-in-water? fluid-at pos)}))

;; ---------------------------------------------------------------- the step

(defn free?
  "Is the player's box, moved by [dx dy dz], clear of solids and of water? The test behind the
   out-of-liquid impulse: a bank the body can rise onto."
  [solid? fluid-at box [dx dy dz]]
  (let [moved (-> box
                  (shift 0 dx)
                  (shift 1 dy)
                  (shift 2 dz))]
    (and (empty? (solid-boxes solid? moved))
         (not (in-fluid? fluid-at :water [0.0 0.0 0.0] moved)))))

(defn rise-onto
  "The move [dx dy dz] of a body at box, on the ground, that hit something low walking
   [vx vy vz] and got only [hx hz] along: the box lifted by step-height (less where something is
   over it), moved along, then set down by what it rose and the fall it had; nil when that gets
   it no farther along than the hit left it. Vanilla's maxUpStep: a carpet is walked onto, a
   block never (its top, 1.0, is over step-height). boxes are the blocks round the plain move;
   the ones over it are added."
  [solid? box boxes [vx vy vz] [hx hz]]
  (let [boxes (into boxes (solid-boxes solid? (expand box [vx step-height vz])))
        up (clip box boxes 1 step-height)
        lifted (shift box 1 up)
        dx (clip lifted boxes 0 vx)
        lifted (shift lifted 0 dx)
        dz (clip lifted boxes 2 vz)
        lifted (shift lifted 2 dz)
        down (clip lifted boxes 1 (+ (- up) vy))]
    (when (> (+ (abs dx) (abs dz)) (+ (abs hx) (abs hz))) [dx (+ up down) dz])))

(defn land-tick
  "One tick of vanilla land movement for a player at pos with velocity [vx vy vz] after input:
   [pos' vel' landed? hit?]. The box is swept an axis at a time (y, then x, then z) against the
   blocks round the move; a body on the ground stopped by something low steps onto it (rise-onto)."
  [solid? pos [vx vy vz]]
  (let [box (aabb pos)
        boxes (vec (solid-boxes solid? (expand box [vx vy vz])))
        dy (clip box boxes 1 vy)
        moved (shift box 1 dy)
        dx (clip moved boxes 0 vx)
        moved (shift moved 0 dx)
        dz (clip moved boxes 2 vz)
        [dx dy dz] (or (when (and (or (not= dx vx) (not= dz vz)) (not= dy vy) (neg? vy))
                         (rise-onto solid? box boxes [vx vy vz] [dx dz]))
                       [dx dy dz])
        landed? (and (not= dy vy) (neg? vy))
        hit? (or (not= dx vx) (not= dz vz))
        [x y z] pos]
    [[(+ x dx) (+ y dy) (+ z dz)]
     [(if (not= dx vx) 0.0 vx) (if (not= dy vy) 0.0 vy) (if (not= dz vz) 0.0 vz)] landed? hit?]))

(defn step
  "One tick of movement. Reads :player/pos :player/vel :player/on-ground? :player/jump-ticks
   :player/fall-distance and writes them back plus :player/horizontal-collision?,
   :player/in-water?, :player/in-lava? and :player/eye-in-water?. Without a fluid oracle the
   world is dry."
  ([solid? world controls] (step solid? no-fluid world controls))
  ([solid? fluid-at
    {:player/keys [pos vel on-ground? jump-ticks fall-distance]
     :or {vel [0.0 0.0 0.0] jump-ticks 0 fall-distance 0.0}
     :as world} {:control/keys [forward? jump? sneak? yaw]}]
   (let [{:keys [in-water? in-lava?]} (fluids-here fluid-at pos)
         liquid? (or in-water? in-lava?)
         touching? (or liquid? (touching-fluid? fluid-at pos))
         [vx vy vz] (map squash vel)
         jump-ticks (max 0 (dec (long jump-ticks)))
         ;; vanilla jumps from the ground, or, in water under 0.4 deep, from the air: the hop that
         ;; takes a body out over a ledge it is in the last inch of the water under
         jumping?
         (and jump? (zero? jump-ticks) (not liquid?) (or on-ground? (touching-water? fluid-at pos)))
         vy (cond jumping? jump-velocity
                  (and liquid? jump?) (+ vy swim-up)
                  (and liquid? sneak?) (- vy swim-down)
                  :else vy)
         jump-ticks (if jumping? jump-cooldown jump-ticks)
         accel (cond liquid? liquid-acceleration
                     on-ground? ground-acceleration
                     :else air-acceleration)
         f (if forward? forward-input 0.0)
         rad (math/to-radians (double (or yaw 0.0)))
         vx (- vx (* f accel (math/sin rad)))
         vz (+ vz (* f accel (math/cos rad)))
         [pos' [vx vy vz] landed? hit?] (land-tick solid? pos [vx vy vz])
         inertia (cond in-lava? lava-inertia
                       in-water? water-inertia
                       landed? ground-inertia
                       :else air-inertia)
         g (cond in-lava? lava-gravity
                 in-water? water-gravity
                 :else gravity)
         vy' (if liquid? (- (* vy inertia) g) (* (- vy gravity) vertical-drag))
         ;; the one real lift out of a liquid: pressing into a bank whose top is free, while the
         ;; box touches the fluid at all; the probe is the 0.6 less what the body rose this tick,
         ;; as vanilla asks it (a body already lifted needs less room over its head)
         vy' (if (and touching?
                      hit?
                      (free? solid?
                             fluid-at
                             (aabb pos')
                             [vx (+ vy' bank-probe (- (y-of pos) (y-of pos'))) vz]))
               out-of-liquid-impulse
               vy')
         fall (cond (or liquid? landed?) 0.0
                    (neg? vy) (+ fall-distance (- (y-of pos) (y-of pos')))
                    :else fall-distance)]
     (assoc world
            :player/pos pos'
            :player/vel [(* vx inertia) vy' (* vz inertia)]
            :player/on-ground? landed?
            :player/horizontal-collision? hit?
            :player/jump-ticks jump-ticks
            :player/fall-distance fall
            :player/in-water? in-water?
            :player/in-lava? in-lava?
            :player/eye-in-water? (eye-in-water? fluid-at pos')
            :player/suffocating?
            (suffocating? solid?
                          pos'
                          (fn [x z]
                            (contains? (:world/chunks world)
                                       [(bit-shift-right (long x) 4)
                                        (bit-shift-right (long z) 4)])))))))

(defn look-at
  "[yaw pitch] in degrees from eye to target, Notchian convention (south = 0, west = +90)."
  [[ex ey ez] [tx ty tz]]
  (let [dx (- tx ex)
        dy (- ty ey)
        dz (- tz ez)
        horiz (math/sqrt (+ (* dx dx) (* dz dz)))]
    [(math/to-degrees (math/atan2 (- dx) dz)) (math/to-degrees (- (math/atan2 dy horiz)))]))

(defn direction
  "The unit vector the eyes look along at [yaw pitch] in degrees: the inverse of look-at."
  [[yaw pitch]]
  (let [y (math/to-radians yaw)
        p (math/to-radians pitch)]
    [(- (* (math/sin y) (math/cos p))) (- (math/sin p)) (* (math/cos y) (math/cos p))]))

(defn cells-along
  "The block cells a ray from point from along unit vector dir passes through within length,
   in order and without repeats, sampled every step."
  [from dir length step]
  (dedupe (for [k (range 0 (/ length step))
                :let [d (* k step)]]
            (mapv #(long (math/floor (+ %1 (* d %2)))) from dir))))

(defn distance
  "Euclidean distance between two points."
  [[ax ay az] [bx by bz]]
  (math/sqrt (+ (math/pow (- ax bx) 2) (math/pow (- ay by) 2) (math/pow (- az bz) 2))))

(defn horizontal-distance
  "Distance between two points ignoring y."
  [[ax _ az] [bx _ bz]]
  (math/sqrt (+ (math/pow (- ax bx) 2) (math/pow (- az bz) 2))))
