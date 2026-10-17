(ns clojurecraft.trap
  "Whether the player is trapped in water or lava, re-derived every tick from the physics' flags
   and the terrain. The planner calls trapped-tick before it chooses a goal, so the escape goals
   (clojurecraft.swim) can preempt whatever runs; swim reads dry-land? to know when the bot is
   out. The trap is plan state (:plan/trap and its counters) because the planner owns what the
   bot is doing; an escaped trap is a fact in :world/traps that routes keep away from."
  (:require [clojure.math :as math]
            [clojurecraft.blocks :as blocks]
            [clojurecraft.game :as game]
            [clojurecraft.terrain :as terrain]))

(set! *warn-on-reflection* true)

(def trap-after-ticks 20)             ; the eye under water this long is a trap (ruststeve's 1 s watchdog); wading is not
(def dry-ticks-to-clear 6)            ; dry land held this long ends a trap (steve's settle: a one-tick bob is not out)

(defn dry-land?
  "Is the player standing on dry land: on the ground, no part of the body in water, the eye
   in air, and the block under the feet a solid that is not a lily pad (steve's isOnDryLand)."
  [world]
  (let [[x y z] (:player/pos world)
        support (terrain/block-at world
                                  [(long (math/floor x)) (long (math/floor (- y 0.5)))
                                   (long (math/floor z))])]
    (boolean (and (:player/on-ground? world)
                  (not (:player/in-water? world))
                  (not (:player/eye-in-water? world))
                  support
                  (blocks/solid? support)
                  (not= :lily_pad (blocks/type-of support))))))

(defn trapped-tick
  "The trap the player is in, re-derived with hysteresis every tick: lava on any contact, water
   once the eye has been under for trap-after-ticks, and either held until dry land has been
   held for dry-ticks-to-clear. :plan/wet-ticks and :plan/dry-ticks are the counters; :plan/trap
   is the kind or absent; :plan/trap-at is where the trap began. A trap that clears leaves a
   fact in :world/traps, so later routes keep away from the place."
  [world]
  (let [wet (if (:player/eye-in-water? world) (inc (:plan/wet-ticks world 0)) 0)
        dry (if (dry-land? world) (inc (:plan/dry-ticks world 0)) 0)
        was (:plan/trap world)
        trap (cond (:player/in-lava? world) :lava
                   (>= wet trap-after-ticks) :water
                   (and was (< dry dry-ticks-to-clear)) was
                   :else nil)
        [x _ z] (:player/pos world)]
    (cond-> (assoc world :plan/wet-ticks wet :plan/dry-ticks dry)
      (and trap (not was))
      (assoc :plan/trap trap :plan/trap-at [(long (math/floor x)) (long (math/floor z))])
      (and was (nil? trap))
      (-> (dissoc :plan/trap :plan/trap-at)
          (update :world/traps
                  (fnil conj [])
                  {:trap/pos (:plan/trap-at world) :trap/kind was :trap/at (:time/now world)})
          (game/say (str "out of the " (name was) " at " (:plan/trap-at world)))))))
