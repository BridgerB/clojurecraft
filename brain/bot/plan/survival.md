---
title: Survival
description: How the bot lives through Normal difficulty (issue #259) - the food from set-health, the :eat always row and its executor (food in hand, one use-item, done when the server's food rises), the game's food as data, the sim's eating, and the eat gym row's hungry landing.
type: reference
tags: [bot, plan, survival, food, intents, goals]
aliases: [:eat, :fed?, :player/food, :player/saturation, hungry-below, edible, food-held, :not-eaten, food/nutrition, :sim/food, :hungry landing]
status: verified
lastUpdated: 2026-10-16
verifiedAgainst: HEAD
sourceRefs:
  - src/clojurecraft/eat.clj#defn food-held
  - src/clojurecraft/eat.clj#defn hungry?
  - src/clojurecraft/eat.clj#def hungry-below 14
  - src/clojurecraft/eat.clj#def unsafe
  - src/clojurecraft/eat.clj#defn deadline
  - src/clojurecraft/eat.clj#defmethod plan/done-by :fed?
  - src/clojurecraft/eat.clj#defmethod intent/run :eat
  - src/clojurecraft/food.clj#def nutrition
  - src/clojurecraft/food.clj#defn eating-ticks
  - src/clojurecraft/game.clj#defmethod on-packet [:play :set-health]
  - "resources/clojurecraft/goals.edn#{:goal/id :eat"
  - src/clojurecraft/sim.clj#defn tick-eating
  - src/clojurecraft/sim.clj#defn eats?
  - src/clojurecraft/gym.clj#defmethod land! :hungry
  - test/clojurecraft/eat_test.clj#a-hungry-bot-holding-bread-eats-before-anything-else
  - test/clojurecraft/eat_test.clj#fed-unknown-or-foodless-the-eat-row-is-done
  - test/clojurecraft/eat_test.clj#a-trap-comes-before-the-meal
  - test/clojurecraft/eat_test.clj#the-meal-is-the-food-in-hand-one-use-and-the-servers-word
  - test/clojurecraft/eat_test.clj#a-meal-the-server-never-gives-fails-not-eaten
  - test/clojurecraft/eat_test.clj#a-hungry-bot-eats-its-bread-against-the-model
related:
  - "[[bot/plan/_moc|Plan]]"
  - "[[water-escape]]"
  - "[[goals-and-intents]]"
  - "[[death-and-respawn]]"
  - "[[sib-blaze-combat]]"
---

# Survival

Every server and gym runs Normal since #257: hunger drains, night brings mobs. Issue #259 is the bot's answer, built in steps; this note grows with them. Eating is the first.

## The food
`set-health` always carried the food and the saturation; the handler kept only the health. Now it keeps `:player/food` (0-20) and `:player/saturation` beside it. Nothing is predicted: the food changes only when the server says so.

## The :eat row (goals.edn, eat.clj)
An always row (`:goal/always? true`) at priority 0, placed after the two escapes in the table, so `plan/in-play`'s stable sort keeps a trap first ([[water-escape]]) and every other target behind the meal. `--until eat` names it for the gym. It is done by `:fed?`: the food the server last told is at least `hungry-below` (14), or nothing edible is held (`food-held` nil), or the food was never told (`hungry?` is false before the first set-health). The second clause matters: a row with nothing to do must count as done, or a hungry bot with no food would wait in front of the whole run. Planned by `:eat` into `{:intent/kind :eat :intent/item <name>}`. Preemption is the planner's usual rule: a running intent for another target is abandoned `:preempted`.

`food-held` picks the most nourishing safe food in the hotbar or the main inventory (player slots under 36, never the armour or the offhand), the lower slot on a tie. `edible` is the game's food (`food/nutrition`) less `unsafe`: rotten flesh, spider eye, pufferfish, poisonous potato and raw chicken (poison or hunger), chorus fruit (it teleports), suspicious stew (a random effect) and golden apples (worth more than a meal).

## The :eat intent
Stages `:equip` → `:use` → `:eating`, run only when no window-0 click is unanswered (the swap of `place/equip`, as `:equip` and `:scoop` do; a lost click resyncs and restarts at `:equip`). `:equip` puts the food in the hand (hotbar: `set-carried-item`; main inventory: a swap click). `:use` sends one `use-item` along the current look, no swing, and records `:intent/food-before` and `:intent/eat-by` (`deadline`: the food's eating time, `food/eating-ticks`, 32 ticks for most, 16 for dried kelp, 40 for honey, times `intent/slowdown`, plus `answer-ms` 3000). `:eating` stands still: done when `:player/food` rises above the food before, failed `:not-eaten` past the deadline. Controls are `{}` throughout.

The server finishes eating on its own clock once a use starts; the client sends nothing more (selecting another slot would stop it). The eat gym row is the live check of that.

## The server model
`sim/init` takes `:food`: the model then tells the bar at login (`health`, set-health with the health and `:sim/food`) and eats: a `use-item` with food held and the bar not full (`eats?`) starts `:sim/eating`, and `tick-eating` finishes it after the food's eating time: one taken from the slot, the bar up by the nutrition (never over 20), the slot's update and set-health sent. A `set-carried-item` stops it. Without `:food` nothing is told and the bot's food stays unknown, so every older sim run is unchanged. Saturation is a constant 5 and a stew's bowl is not given back.

## The gym row
`eat`: four breads given, landed by `land! :hungry` (the surface landing, then `effect give <bot> minecraft:hunger 30 255` until RCON's `foodLevel` reads under `hungry-food` 10, at most 30 s, then the effect cleared). The wait is the point: hunger takes at best one point every three ticks, and a bot told to go while still fed is done at once. Judged by `data get entity <bot> foodLevel` at 14 or more and one to three breads left.

## See also
- [[water-escape]] - the other always rows, and the preemption they share.
- [[death-and-respawn]] - set-health's other reader.
- [[sib-blaze-combat]] - what the fight steps will inherit.
