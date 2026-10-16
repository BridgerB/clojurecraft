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

(def saturation
  "Every food item by name, with the saturation it gives (FoodProperties' saturation: nutrition ×
   the food's modifier × 2), which the server model's hunger spends before the bar (issue #260)."
  {:apple 2.4
   :baked_potato 6.0
   :beef 1.8
   :beetroot 1.2
   :beetroot_soup 7.2
   :bread 6.0
   :carrot 3.6
   :chicken 1.2
   :chorus_fruit 2.4
   :cod 0.4
   :cooked_beef 12.8
   :cooked_chicken 7.2
   :cooked_cod 6.0
   :cooked_mutton 9.6
   :cooked_porkchop 12.8
   :cooked_rabbit 6.0
   :cooked_salmon 9.6
   :cookie 0.4
   :dried_kelp 0.6
   :enchanted_golden_apple 9.6
   :glow_berries 0.4
   :golden_apple 9.6
   :golden_carrot 14.4
   :honey_bottle 1.2
   :melon_slice 1.2
   :mushroom_stew 7.2
   :mutton 1.2
   :poisonous_potato 1.2
   :porkchop 1.8
   :potato 0.6
   :pufferfish 0.2
   :pumpkin_pie 4.8
   :rabbit 1.8
   :rabbit_stew 12.0
   :rotten_flesh 0.8
   :salmon 0.4
   :spider_eye 3.2
   :suspicious_stew 7.2
   :sweet_berries 0.4
   :tropical_fish 0.2})

(def always-edible
  "The foods eaten with a full bar (FoodProperties.canAlwaysEat)."
  #{:chorus_fruit :enchanted_golden_apple :golden_apple :suspicious_stew})
