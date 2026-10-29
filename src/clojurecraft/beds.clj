(ns clojurecraft.beds
  "The bed as a weapon in the End, as data and pure functions (the speedrunners' kill, issue #10): a
   bed used there explodes (power blast-power, the game's BedBlock) where it lies. Placed on the
   ground under the perched dragon's head (head-point) and used from standoff blocks away
   (stand-point), it hurts the head's box. The explosion hurts the bot too (blast-damage: at 4.5
   blocks, in the open, a lethal 31); this namespace holds no decision to fire, only the facts. The
   gym row dragon-beds gives the bot Resistance V (gym/invulnerable) to measure the damage a bed
   deals, read from the boss bar's health (:world/boss-health) before and after each."
  (:require [clojure.math :as math]
            [clojure.string :as str]
            [clojurecraft.mob :as mob]
            [clojurecraft.recipe :as recipe]))

(set! *warn-on-reflection* true)

(def blast-power 5.0)                 ; a bed's explosion in the End or the Nether (BedBlock.useWithoutItem)
(def blast-range (* 2.0 blast-power)) ; blocks beyond which the explosion hurts nothing (Explosion: radius 2 * power)
(def head-out 6.5)                    ; blocks along its facing the perched dragon's head part lies (mob/bite-out less the box's inflation)
(def standoff 4.0)                    ; blocks beyond the head's ground point the bot stands to place the bed: within its 4.5 reach of the block it clicks

(defn blast-damage
  "The damage an explosion of blast-power deals an entity dist blocks from its middle that
   exposure (0.0 to 1.0, the share of rays the blocks do not stop) of it is open to, before armour
   and resistance (Explosion.hurtEntities): impact = (1 - dist / blast-range) * exposure, damage =
   ((impact^2 + impact) / 2 * 7 * blast-range + 1); zero past blast-range."
  [dist exposure]
  (if (>= dist blast-range)
    0.0
    (let [impact (* (- 1.0 (/ dist blast-range)) exposure)]
      (+ 1.0 (* 7.0 blast-range (/ (+ (* impact impact) impact) 2.0))))))

(defn held
  "The name of a bed held in the inventory (an item ending _bed), or nil."
  [world]
  (some (fn [[_ {:keys [item count]}]]
          (let [n (some-> item
                          recipe/item-name)]
            (when (and n (pos? (or count 0)) (str/ends-with? (name n) "_bed")) n)))
        (:player/inventory world)))

(defn head-point
  "[x z] on the plane under the head of the perched dragon e (an entity with :entity/pos and
   :entity/yaw): head-out out along its facing from its middle."
  [e]
  (mob/beside (:entity/pos e) (:entity/yaw e) [head-out 0.0]))

(defn stand-point
  "[x z] standoff blocks beyond point [hx hz] on the line from [fx fz] through it: where the bot
   stands to place a bed at that point."
  [[hx hz] [fx fz]]
  (let [dx (- hx fx)
        dz (- hz fz)
        d (math/sqrt (+ (* dx dx) (* dz dz)))]
    (if (< d 1.0e-6)
      [(+ hx standoff) hz]
      [(+ hx (* standoff (/ dx d))) (+ hz (* standoff (/ dz d)))])))
