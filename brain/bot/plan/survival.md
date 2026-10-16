---
title: Survival
description: How the bot lives through Normal difficulty (issue #259) - the food from set-health, the :eat always row and its executor (food in hand, one use-item, done when the server's food rises), the game's food as data, the sim's eating, the eat gym row's hungry landing, armour (worn by two window-0 clicks, made from spare ingots), the food producer (:hunt) and the sword (:sword), and fighting (the 775 attack packet, :fight paced by the clock, :defend, :back-off, the gym's :attack, the sim's mobs).
type: reference
tags: [bot, plan, survival, food, intents, goals]
aliases: [:hunt, :provisioned?, :sword, :armed?, :intent/target-pos, :fight, :back-off, :defend, :attack, :safe?, :cleared?, attack packet, :intent/swings, ghost, :sim/mobs, :wear, :wear-armour, :armour, :dressed?, :armoured?, spare-ingots, :eat, :fed?, :player/food, :player/saturation, hungry-below, edible, food-held, :not-eaten, food/nutrition, :sim/food, :hungry landing]
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
  - src/clojurecraft/armour.clj#defn unworn
  - src/clojurecraft/armour.clj#defn spare-ingots
  - src/clojurecraft/armour.clj#defn table-at-hand?
  - src/clojurecraft/armour.clj#defn wanted
  - src/clojurecraft/armour.clj#defmethod intent/run :wear
  - src/clojurecraft/armour.clj#defmethod wear-stage :check
  - "resources/clojurecraft/goals.edn#{:goal/id :wear-armour"
  - "resources/clojurecraft/goals.edn#{:goal/id :armour"
  - test/clojurecraft/armour_test.clj#a-piece-goes-in-by-hand-two-answered-clicks
  - test/clojurecraft/armour_test.clj#a-refused-piece-goes-back-before-the-wear-fails
  - test/clojurecraft/armour_test.clj#only-spare-ingots-make-armour
  - test/clojurecraft/armour_test.clj#a-helmet-and-chestplate-are-made-and-worn-against-the-model
  - "src/clojurecraft/packet.clj#[:play :c2s :attack]"
  - src/clojurecraft/combat.clj#def swing-every 650
  - src/clojurecraft/combat.clj#def max-swings 25
  - src/clojurecraft/combat.clj#defn sword
  - src/clojurecraft/combat.clj#defn attack
  - src/clojurecraft/combat.clj#defmethod fight-stage :engage
  - src/clojurecraft/combat.clj#defmethod fight-stage :retreat
  - src/clojurecraft/combat.clj#defmethod intent/run :fight
  - src/clojurecraft/combat.clj#defmethod intent/run :back-off
  - src/clojurecraft/combat.clj#defn threats
  - src/clojurecraft/combat.clj#defmethod plan/next-intent :defend
  - src/clojurecraft/combat.clj#defn quarry
  - src/clojurecraft/combat.clj#def look-ms 3000
  - test/clojurecraft/combat_test.clj#an-attack-trial-looks-round-before-it-finds-nothing
  - src/clojurecraft/hunt.clj#defn game
  - src/clojurecraft/hunt.clj#defn fresh-kill
  - src/clojurecraft/hunt.clj#defmethod plan/next-intent :hunt
  - src/clojurecraft/hunt.clj#def strike-range 4.5
  - src/clojurecraft/sword.clj#defn wanted
  - src/clojurecraft/sword.clj#defn blade?
  - "resources/clojurecraft/goals.edn#{:goal/id :hunt"
  - "resources/clojurecraft/goals.edn#{:goal/id :sword"
  - src/clojurecraft/sim.clj#defn drop-item
  - test/clojurecraft/hunt_test.clj#hungry-with-nothing-to-eat-the-nearest-cow-is-walked-to-then-fought
  - test/clojurecraft/hunt_test.clj#a-kill-just-made-is-collected-where-it-fell
  - test/clojurecraft/hunt_test.clj#a-hungry-bot-kills-a-cow-picks-up-its-beef-and-eats-it-against-the-model
  - test/clojurecraft/sword_test.clj#the-best-sword-the-held-items-make-better-than-the-one-held
  - test/clojurecraft/sword_test.clj#iron-only-from-ingots-the-armour-leaves
  - "resources/clojurecraft/goals.edn#{:goal/id :defend"
  - "resources/clojurecraft/goals.edn#{:goal/id :attack"
  - src/clojurecraft/plan.clj#defn through
  - src/clojurecraft/sim.clj#defmethod on-packet [:play :attack]
  - src/clojurecraft/sim.clj#defn mob-packets
  - test/clojurecraft/combat_test.clj#an-attack-is-its-own-packet-the-entity-id-alone
  - test/clojurecraft/combat_test.clj#a-fight-faces-its-target-then-attacks-650-ms-apart
  - test/clojurecraft/combat_test.clj#a-target-that-takes-25-swings-is-a-ghost
  - test/clojurecraft/combat_test.clj#under-ten-health-the-fight-breaks-off-and-walks-away
  - test/clojurecraft/combat_test.clj#a-zombie-at-hand-is-fought-a-creeper-backed-away-from
  - test/clojurecraft/combat_test.clj#no-two-attacks-within-600-ms
  - test/clojurecraft/combat_test.clj#a-zombie-at-hand-is-killed-against-the-model
  - test/clojurecraft/combat_test.clj#the-attack-trial-walks-to-a-husk-and-kills-it-against-the-model
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

## Armour (armour.clj)
Two always rows. `:wear-armour` (priority 0, after the meal) is done by `:dressed?`: no piece held in the hotbar or the main inventory whose armour slot is empty (`unworn`; the slot comes from the name's end, `_helmet` to `_boots`). It is planned by `:wear` into a `:wear` intent: two clicks in window 0, as a hand does it, each predicting nothing and waiting for the server's slots ([[window-zero-model]]). `:pick` closes an open container first (the server ignores window-0 clicks while one is its menu), then picks the piece up; `:put` puts the cursor's piece in `inventory/slot [:armour n]`; `:check` is done when the server shows it there. A refused piece goes back into a free slot before the intent fails `:not-worn`, so nothing is left on the cursor. The inventory keeps the armour at player slots 36-39 (feet to head), so a worn piece still counts as held.

`:armour` (`--until armour`) sits at the portal kit's priority, 13, after it in the table: in a chained run it comes up the moment the iron the portal needs is in hand. Done by `:armoured?`: nothing `wanted`. `wanted` is the first of an iron helmet (5 ingots) and an iron chestplate (8) not held whose cost the `spare-ingots` pay, and only with a table at hand (`table-at-hand?`: one remembered near or held, or the planks or a log for one). Spare means every ingot once flint and steel, a water bucket and an empty bucket are all held; before that none is, so armour never takes the portal's iron and never sends the bot mining. The table rule keeps an always row from sending the bot after a tree it cannot find (in the Nether it would wait `:no-log` and fail the run). Planned by `:armour` through the needs planner (`make/decide` with the piece as the row's provides), so the table is placed and the craft laid as for any other item; the new piece is then worn by `:wear-armour`.

The issue's note that `inventory/left-click` "sends quick-move while claiming pickup" did not hold: it is mode 0 (`:pickup`) with button 0, and the sim classifies it `:left`.

Gym row `armour`: the portal's iron, a table and 13 ingots given; judged by `execute if items entity <bot> armor.head minecraft:iron_helmet` and the same for the chest.

## Fighting (combat.clj)
**The packet.** 775 split attacking out of `interact`, which is a right-click now: `[:play :c2s :attack]`, id 1, one field, the target's `:entity-id` as a varint ([[sib-775-attack-packet]]: ruststeve dealt no damage for a session with interact, and an interact with no hand field got it kicked). The server judges reach from the pose it last had, so the look is set a tick before the first attack: the next tick's `game/on-tick` applies `:control/look` and `movement-packets` sends it before the planner runs.

**`:fight`** (`{:intent/kind :fight :intent/target eid}`, the target an entity id since `:intent/target`, `:plan/blacklist` and `:intention/target` were widened to a position or an id). Stages `:arm` (the best `sword` held put in hand by `place/equip`, the hand when none), `:approach` (`intent/toward` the target's `aim`, `aim-height` 0.9 over its feet, until `approach-range` 3 on the plane; `:unreachable` after 20 s), `:engage` (face it every tick; when `:time/now` has passed `:intent/next-swing`, the eye is within `attack-reach` 3.2 of the aim and the eyes face it within 20° of yaw: `attack`, then `swing`, as the vanilla client sends them, the next swing `swing-every` 650 ms later, `:intent/swings` counted; back to `:approach` when it is more than 4 off). Done when the target is gone (`entity/gone?`: removed, or dead by the server's entity event, [[sib-blaze-combat]]); `:ghost` after `max-swings` 25 in reach, and the planner blacklists the id. Under `retreat-health` 10 it breaks off (`:retreat`: straight away until `retreat-distance` 6, or 10 s) and is done: a fight left is not a failure, and the `:defend` row then backs away instead. The pacing is a deadline on the intent, never a count of ticks: ruststeve's tick-paced swings collapsed under the packet flood into forty in 66 ms, all but one inside the mob's 10-tick hurt invulnerability. `no-two-attacks-within-600-ms` holds it over generated clocks. The order matters: gym attack-259a sent `set-carried-item`, `swing`, `attack` (the issue's order) and its stone sword took the husk's health a point a hit, twenty-two hits, each at the charge of a swing just begun; the recordings showed the attacks 650 ms apart (a minimum of 650 over 21 gaps).

**`:back-off`**: straight away from the target until `back-off-to` 8 is between them, it is gone, or 10 s have passed (a cornered bot plans again).

**`:defend`** (goals.edn): an always row at priority 0, inserted after the two escapes and so before the meal and the armour (`plan/in-play` sorts stably). Done by `:safe?`: no `threats`, the hostiles (`entity/hostiles`: the game's monsters, never an enderman or a zombified piglin) within `defend-radius` 4, a creeper within `creeper-radius` 6, none blacklisted. Planned by `:defend`: the nearest threat, backed away from when it is a creeper or the health is under 10, else fought. No sword is made first: a zombie at hand is answered with what is held.

**`:attack`** (`--until attack`): the gym's trial, every hostile within `attack-radius` 16 fought (`quarry`, done by `:cleared?`, and not before `look-ms` 3000 after the plan went active: the server shows a landing's entities a moment after its chunks, and gym attack-259a run 3 was done 0.2 s after the go with its husk unseen). It is the first row marked `:goal/aside?`: `plan/through`, `plan/current-until` and the results page's chain leave asides out, as they leave out the water trial, so no chained run ever takes it as a step.

**The model's mobs.** `sim/init` takes `:mobs {eid {:type :pos :health}}`: shown at login (`mob-packets`: add-entity, then the health as metadata index 9), standing where they are. An `attack` on one in reach (`mob-reach` 4 from the eye to its middle) and past its `hurt-ms` 500 takes the held sword's damage (`weapon-damage`, a fist 1) and tells the health; at none, its death event (status 3) and its removal. `:sim/attacks` and `:sim/hits` count them. The mobs do not move or hit back.

**Gym rows.** `attack`: a husk with `NoAI` (a husk does not burn by day) three east of the landing at noon, a stone sword; judged by no husk within 24 of the landing, and the attacks' spacing read from the recording. `defend`: three zombies two blocks round the landing at midnight, the bot in iron armour put on by the fixture (`item replace`, since a zombie at hand comes before dressing) with an iron sword; judged by a zombie killed (a kill counter the setup adds, `minecraft.killed:minecraft.zombie`: counting zombies round the landing counted the night's new ones too), health left and no death. Not all three: the row is done when none is within 4, and in gym defend-259a the others wandered off while the first was fought. Truths are asked at the landing, not `execute as` the bot: that answered RCON with nothing.

## The food producer (hunt.clj)
`:hunt` is an always row at priority 0 after the meal: done by `:provisioned?`, not hungry, or food held (the meal comes first), or no `game` and no `fresh-kill`. `game` is the nearest cow, mooshroom, pig, sheep or rabbit (`game-kinds`, never a chicken: raw chicken may make the eater hungry), alive, not blacklisted, within `hunt-radius` 24, the first of the three nearest a route reaches (`intent/routable?`). Planned by `:hunt`: a kill just made (the last intent a `:fight` for `:hunt`, done, with `:intent/target-pos`, where the fight last saw its target) is a `:collect` of the meat there; else a `:walk` to the animal's cell, and once within `strike-range` 4.5 on the plane (past a walk's reach, so a walk that arrived is never asked again) a `:fight` for `:hunt`. The meat is eaten raw by `:eat`. The model's mobs take an optional `:drop`, an item left where one dies (`drop-item`, shared with a broken block's drop).

## The sword (sword.clj)
`:sword` is an always row at the portal kit's priority after the armour, so a chained run comes to it once the portal's iron is in hand and the wood-hungry mining is behind it; only a chained run makes one (a gym stage measures its own target: with the row in play the kit stage placed its held table for a wooden sword, undid the kit, and dug the table back until it timed out). Done by `:armed?`: nothing `wanted`, the first of an iron, stone and wooden sword better than the best held (`combat/swords` order) whose blade the held items pay for (`blade?`: iron only from ingots spare once the armour wants nothing, cobblestone, planks; never a log) with a handle (a stick, or two planks for sticks) and a table at hand. Planned through the needs planner. Until it is made, fights are by hand: `:defend` never stops to make a sword with a zombie at hand.

## See also
- [[water-escape]] - the other always rows, and the preemption they share.
- [[death-and-respawn]] - set-health's other reader.
- [[sib-blaze-combat]] - what the fight steps will inherit.
