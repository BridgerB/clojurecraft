(ns clojurecraft.physics
  "Vanilla land movement as a pure function over the world's :player/* attributes. Full-cube
   collision only; no fluids, no step-up (a one-block ledge needs a jump, as in vanilla).

   (step solid? world controls) → world'   where solid? is (fn [x y z] bool) over block
   coordinates and controls is {:control/forward? bool :control/jump? bool :control/yaw deg}.")

(def gravity 0.08)
(def vertical-drag 0.98)
(def half-width 0.3)
(def height 1.8)
(def eye-height 1.62)
(def jump-velocity 0.42)
(def ground-inertia (* 0.6 0.91))                     ; slipperiness × 0.91
(def air-inertia 0.91)
(def air-acceleration 0.02)
(def ground-acceleration (* 0.1 (/ 0.16277136 (Math/pow ground-inertia 3)))) ; ≈ 0.1
(def negligible 0.003)                                ; velocities below this snap to zero

(defn aabb
  "[x0 y0 z0 x1 y1 z1] of a player whose feet centre is pos."
  [[x y z]]
  [(- x half-width) y (- z half-width) (+ x half-width) (+ y height) (+ z half-width)])

(defn eye [[x y z]] [x (+ y eye-height) z])

(defn expand [[x0 y0 z0 x1 y1 z1] [vx vy vz]]
  [(+ x0 (min 0.0 vx)) (+ y0 (min 0.0 vy)) (+ z0 (min 0.0 vz))
   (+ x1 (max 0.0 vx)) (+ y1 (max 0.0 vy)) (+ z1 (max 0.0 vz))])

(defn solid-boxes
  "Unit boxes of every solid block touching the region."
  [solid? [x0 y0 z0 x1 y1 z1]]
  (for [x (range (long (Math/floor x0)) (inc (long (Math/floor x1))))
        y (range (long (Math/floor y0)) (inc (long (Math/floor y1))))
        z (range (long (Math/floor z0)) (inc (long (Math/floor z1))))
        :when (solid? x y z)]
    [(double x) (double y) (double z) (+ x 1.0) (+ y 1.0) (+ z 1.0)]))

(defn overlaps? [a b axis]
  (and (< (double (a axis)) (double (b (+ axis 3))))
       (> (double (a (+ axis 3))) (double (b axis)))))

(defn clip
  "Shrink the move d along axis so box does not enter any of boxes."
  ^double [box boxes axis ^double d]
  (let [others (remove #{axis} [0 1 2])]
    (reduce (fn [^double d c]
              (if (every? #(overlaps? box c %) others)
                (cond
                  (and (pos? d) (>= (double (c axis)) (double (box (+ axis 3)))))
                  (min d (- (double (c axis)) (double (box (+ axis 3)))))
                  (and (neg? d) (<= (double (c (+ axis 3))) (double (box axis))))
                  (max d (- (double (c (+ axis 3))) (double (box axis))))
                  :else d)
                d))
            d boxes)))

(defn shift [box axis d]
  (-> box (update axis + d) (update (+ axis 3) + d)))

(defn squash ^double [^double v] (if (< (Math/abs v) negligible) 0.0 v))

(defn step
  "One tick of movement. Reads :player/pos :player/vel :player/on-ground? :player/jump-ticks and
   writes them back plus :player/horizontal-collision?."
  [solid? {:player/keys [pos vel on-ground? jump-ticks] :or {vel [0.0 0.0 0.0] jump-ticks 0} :as world}
   {:control/keys [forward? jump? yaw]}]
  (let [[vx vy vz] (map squash vel)
        jump-ticks (max 0 (dec (long jump-ticks)))
        jumping? (and jump? on-ground? (zero? jump-ticks))
        vy (if jumping? jump-velocity vy)
        jump-ticks (if jumping? 10 jump-ticks)
        accel (if on-ground? ground-acceleration air-acceleration)
        f (if forward? 0.98 0.0)
        rad (Math/toRadians (double (or yaw 0.0)))
        vx (- vx (* f accel (Math/sin rad)))
        vz (+ vz (* f accel (Math/cos rad)))
        box (aabb pos)
        boxes (vec (solid-boxes solid? (expand box [vx vy vz])))
        dy (clip box boxes 1 vy) box (shift box 1 dy)
        dx (clip box boxes 0 vx) box (shift box 0 dx)
        dz (clip box boxes 2 vz) box (shift box 2 dz)
        landed? (and (not= dy vy) (neg? vy))
        hit? (or (not= dx vx) (not= dz vz))
        vx (if (not= dx vx) 0.0 vx)
        vy (if (not= dy vy) 0.0 vy)
        vz (if (not= dz vz) 0.0 vz)
        inertia (if landed? ground-inertia air-inertia)
        [x y z] pos]
    (assoc world
           :player/pos [(+ x dx) (+ y dy) (+ z dz)]
           :player/vel [(* vx inertia) (* (- vy gravity) vertical-drag) (* vz inertia)]
           :player/on-ground? landed?
           :player/horizontal-collision? hit?
           :player/jump-ticks jump-ticks)))

(defn look-at
  "[yaw pitch] in degrees from eye to target, Notchian convention (south = 0, west = +90)."
  [[ex ey ez] [tx ty tz]]
  (let [dx (- tx ex) dy (- ty ey) dz (- tz ez)
        horiz (Math/sqrt (+ (* dx dx) (* dz dz)))]
    [(Math/toDegrees (Math/atan2 (- dx) dz))
     (Math/toDegrees (- (Math/atan2 dy horiz)))]))

(defn distance [[ax ay az] [bx by bz]]
  (Math/sqrt (+ (Math/pow (- ax bx) 2) (Math/pow (- ay by) 2) (Math/pow (- az bz) 2))))

(defn horizontal-distance [[ax _ az] [bx _ bz]]
  (Math/sqrt (+ (Math/pow (- ax bx) 2) (Math/pow (- az bz) 2))))
