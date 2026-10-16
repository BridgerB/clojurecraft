(ns clojurecraft.hunger
  "Hunger as the server keeps it: the rules of vanilla's FoodData, FoodConstants and Foods
   (26.1.2), as values and pure functions, for the server model (sim.clj).

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
   sprints), saturation's own client sync (set-health carries it, the caller sends it), and the
   natural_health_regeneration game rule (always on)."
  (:require [clojurecraft.blocks :as blocks]
            [clojurecraft.recipe :as recipe]))

(set! *warn-on-reflection* true)

(def max-level 20)                    ; a full hunger bar (FoodConstants.MAX_FOOD)
(def start-saturation 5.0)            ; saturation a spawn and a respawn give (FoodConstants.START_SATURATION)
(def exhaustion-per-point 4.0)        ; exhaustion that costs one saturation, else one food (FoodData.tick)
(def heal-level 18)                   ; food at or over which health regenerates (FoodConstants.HEAL_LEVEL)
(def slow-heal-ticks 80)              ; game ticks between slow heals, and between starvation hurts
(def fast-heal-ticks 10)              ; game ticks between saturated heals (a full bar with saturation)
(def max-fast-heal 6.0)               ; saturation one saturated heal spends at most, healing a sixth of it
(def heal-exhaustion 6.0)             ; exhaustion one slow heal costs (FoodConstants.EXHAUSTION_HEAL)
(def eat-ticks 32)                    ; game ticks eating takes (Consumable's default 1.6 s)

(def exhaustion
  "What each act costs, as FoodConstants and the damage types' exhaustion say: a jump (0.05), a
   block broken (0.005), an attack (0.1), a hurt from a mob, an arrow or a blast (0.1). Drowning
   and starving cost nothing (their damage types' exhaustion is 0)."
  {:jump 0.05 :mine 0.005 :attack 0.1 :hurt 0.1})

(def swim-per-block 0.01)             ; exhaustion a block swum under water costs (FoodConstants.EXHAUSTION_SWIM)

(def foods
  "Every food the bot may hold, by item name → [nutrition saturation], as vanilla's Foods table
   gives them (saturation already nutrition × modifier × 2). Hand-written: datagen reads no
   food table yet."
  {:apple [4 2.4]
   :baked_potato [5 6.0]
   :beef [3 1.8]
   :beetroot [1 1.2]
   :bread [5 6.0]
   :carrot [3 3.6]
   :chicken [2 1.2]
   :cod [2 0.4]
   :cooked_beef [8 12.8]
   :cooked_chicken [6 7.2]
   :cooked_cod [5 6.0]
   :cooked_mutton [6 9.6]
   :cooked_porkchop [8 12.8]
   :cooked_rabbit [5 6.0]
   :cooked_salmon [6 9.6]
   :cookie [2 0.4]
   :golden_apple [4 9.6]
   :golden_carrot [6 14.4]
   :melon_slice [2 1.2]
   :mutton [2 1.2]
   :porkchop [3 1.8]
   :potato [1 0.6]
   :pumpkin_pie [8 4.8]
   :rabbit [3 1.8]
   :rotten_flesh [4 0.8]
   :salmon [2 0.4]
   :sweet_berries [2 0.4]})

(def always-edible "Foods eaten with a full bar (FoodProperties.canAlwaysEat)." #{:golden_apple})

(def food-items
  "item id → [nutrition saturation], for the foods the item table knows."
  (into {} (for [[n v] foods :let [id (get blocks/items n)] :when id] [id v])))

(defn food? "Is item id a food?" [id] (contains? food-items id))

(def fresh
  "A new life's food: a full bar, the start saturation, nothing spent."
  {:food/level max-level :food/saturation start-saturation :food/exhaustion 0.0 :food/timer 0})

(defn exhaust
  "food after amount more exhaustion (FoodData.addExhaustion: capped at 40)."
  [food amount]
  (update food :food/exhaustion #(min 40.0 (+ % amount))))

(defn can-eat?
  "May a player with food eat item id now: a food, and a bar below full or one eaten anyway?"
  [food id]
  (boolean (and (food? id)
                (or (< (:food/level food) max-level) (always-edible (recipe/item-name id))))))

(defn eat
  "food after eating item id (FoodData.eat): the level up by its nutrition to at most full, the
   saturation up by its saturation to at most the new level."
  [food id]
  (let [[nutrition saturation] (food-items id)
        level (min max-level (+ (:food/level food) nutrition))]
    (assoc food
           :food/level level
           :food/saturation (min (double level) (+ (:food/saturation food) saturation)))))

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
    (cond (and hurt? (pos? (:food/saturation food)) (>= (:food/level food) max-level))
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
