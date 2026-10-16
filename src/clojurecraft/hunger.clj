(ns clojurecraft.hunger
  "Hunger as the server keeps it: the rules of vanilla's FoodData and FoodConstants (26.1.2), as
   values and pure functions, for the server model (sim.clj). What each food restores is
   clojurecraft.food's.

   A player's food is a map {:food/level 0-20 :food/saturation 0-level :food/exhaustion
   :food/timer}: the hunger bar, the hidden saturation that drains first, the exhaustion that
   drains it four at a time, and the tick counter of the regeneration or starvation clock. What
   the player does adds exhaustion (exhaust: a jump, a block broken, a hit, a hurt, a heal);
   each game tick (tick) spends it and heals or starves by the clock; eating (eat) adds a food's
   nutrition and saturation. Health is the caller's: tick says how much to heal or hurt, and the
   caller applies it, so the rules stay about food.

   The difficulty is an argument, as the server reads it: on peaceful nothing drains the bar; on
   easy starvation stops at 10 health, on normal at 1, on hard it kills.

   Not modelled: the hunger effect of raw chicken and rotten flesh, sprinting (the bot never
   sprints), and the natural_health_regeneration game rule (always on)."
  (:require [clojurecraft.food :as food]))

(set! *warn-on-reflection* true)

(def start-saturation 5.0)            ; saturation a spawn and a respawn give (FoodConstants.START_SATURATION)
(def exhaustion-per-point 4.0)        ; exhaustion that costs one saturation, else one food (FoodData.tick)
(def heal-level 18)                   ; food at or over which health regenerates (FoodConstants.HEAL_LEVEL)
(def slow-heal-ticks 80)              ; game ticks between slow heals, and between starvation hurts
(def fast-heal-ticks 10)              ; game ticks between saturated heals (a full bar with saturation)
(def max-fast-heal 6.0)               ; saturation one saturated heal spends at most, healing a sixth of it
(def heal-exhaustion 6.0)             ; exhaustion one slow heal costs (FoodConstants.EXHAUSTION_HEAL)
(def max-exhaustion 40.0)             ; exhaustion never grows past this (FoodData.addExhaustion)

(def exhaustion
  "What each act costs, as FoodConstants and the damage types' exhaustion say: a jump (0.05), a
   block broken (0.005), an attack (0.1), a hurt from a mob, an arrow or a blast (0.1). Drowning
   and starving cost nothing (their damage types' exhaustion is 0)."
  {:jump 0.05 :mine 0.005 :attack 0.1 :hurt 0.1})

(def swim-per-block 0.01)             ; exhaustion a block swum under water costs (FoodConstants.EXHAUSTION_SWIM)

(def fresh
  "A new life's food: a full bar, the start saturation, nothing spent."
  {:food/level food/max-food :food/saturation start-saturation :food/exhaustion 0.0 :food/timer 0})

(defn exhaust
  "food after amount more exhaustion, never past max-exhaustion."
  [food amount]
  (update food :food/exhaustion #(min max-exhaustion (+ % amount))))

(defn eat
  "food after eating the food named n (FoodData.eat): the level up by its nutrition to at most
   full, the saturation up by its saturation (food/saturation) to at most the new level."
  [food n]
  (let [level (min food/max-food (+ (:food/level food) (food/nutrition n 0)))]
    (assoc food
           :food/level level
           :food/saturation
           (min (double level) (+ (:food/saturation food) (food/saturation n 0.0))))))

(defn spend
  "food after spending its exhaustion (FoodData.tick's first part): past four, four go and cost
   one saturation, or one food once saturation is gone; on peaceful the bar never drops."
  [food difficulty]
  (cond (<= (:food/exhaustion food) exhaustion-per-point) food
        :else
        (let [food (update food :food/exhaustion - exhaustion-per-point)]
          (cond (pos? (:food/saturation food)) (update food :food/saturation #(max 0.0 (dec %)))
                (= difficulty :peaceful) food
                :else (update food :food/level #(max 0 (dec %)))))))

(defn starves?
  "Does a starvation hurt land on a player with health at difficulty: above 10 always, above 1
   on normal, any on hard (FoodData.tick)? So on normal hunger never kills."
  [health difficulty]
  (or (> health 10.0) (= difficulty :hard) (and (> health 1.0) (= difficulty :normal))))

(defn tick
  "One game tick of food for a player with health of max-health, at difficulty: {:food food'
   :heal h :hurt d}, the heal and the hurt the caller applies (0 when none). Exhaustion is spent
   first (spend); then, by the clock in :food/timer: a full bar with saturation heals every 10
   ticks by a sixth of the saturation it spends; a bar at 18 or more heals one every 80 ticks for
   6 exhaustion; an empty bar starves one every 80 ticks (starves?); otherwise the clock rests."
  [food health max-health difficulty]
  (let [food (spend food difficulty)
        hurt? (< health max-health)
        clock (fn [food every]
                (let [t (inc (:food/timer food))]
                  [(assoc food :food/timer (if (>= t every) 0 t)) (>= t every)]))]
    (cond (and hurt? (pos? (:food/saturation food)) (>= (:food/level food) food/max-food))
          (let [[food due?] (clock food fast-heal-ticks)
                spent (min (:food/saturation food) max-fast-heal)]
            (if due?
              {:food (exhaust food spent) :heal (/ spent 6.0) :hurt 0.0}
              {:food food :heal 0.0 :hurt 0.0}))
          (and hurt? (>= (:food/level food) heal-level))
          (let [[food due?] (clock food slow-heal-ticks)]
            (if due?
              {:food (exhaust food heal-exhaustion) :heal 1.0 :hurt 0.0}
              {:food food :heal 0.0 :hurt 0.0}))
          (<= (:food/level food) 0)
          (let [[food due?] (clock food slow-heal-ticks)]
            {:food food :heal 0.0 :hurt (if (and due? (starves? health difficulty)) 1.0 0.0)})
          :else {:food (assoc food :food/timer 0) :heal 0.0 :hurt 0.0})))
