(ns clojurecraft.food
  "The game's food as data (26.1.2's food properties): what each food item restores and how long
   it takes to eat. Hand-written, as the game's own numbers; the bot's choice of what to eat is
   clojurecraft.eat's, and the server model eats by these numbers too.")

(set! *warn-on-reflection* true)

(def max-food 20)                     ; a full hunger bar

(def nutrition
  "Every food item by name, with the hunger it restores (FoodProperties' nutrition)."
  {:apple 4
   :baked_potato 5
   :beef 3
   :beetroot 1
   :beetroot_soup 6
   :bread 5
   :carrot 3
   :chicken 2
   :chorus_fruit 4
   :cod 2
   :cooked_beef 8
   :cooked_chicken 6
   :cooked_cod 5
   :cooked_mutton 6
   :cooked_porkchop 8
   :cooked_rabbit 5
   :cooked_salmon 6
   :cookie 2
   :dried_kelp 1
   :enchanted_golden_apple 4
   :glow_berries 2
   :golden_apple 4
   :golden_carrot 6
   :honey_bottle 6
   :melon_slice 2
   :mushroom_stew 6
   :mutton 2
   :poisonous_potato 2
   :porkchop 3
   :potato 1
   :pufferfish 1
   :pumpkin_pie 8
   :rabbit 3
   :rabbit_stew 10
   :rotten_flesh 4
   :salmon 2
   :spider_eye 2
   :suspicious_stew 6
   :sweet_berries 2
   :tropical_fish 1})

(def eat-ticks 32)                    ; the eating time of most food, in game ticks

(def eat-ticks-of
  "The foods whose eating time is not eat-ticks (Consumable's consume seconds, in ticks)."
  {:dried_kelp 16 :honey_bottle 40})

(defn eating-ticks "Game ticks food n takes to eat." [n] (get eat-ticks-of n eat-ticks))
