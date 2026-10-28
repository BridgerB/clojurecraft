---
title: Survival
description: How the bot lives through Normal difficulty (issue #259) - the food from set-health, the :eat always row and its executor (food in hand, one use-item, done when the server's food rises), the game's food as data, the sim's eating, the eat gym row's hungry landing, armour (worn by two window-0 clicks, made from spare ingots), the food producer (:hunt) and the sword (:sword), and fighting (the 775 attack packet, :fight paced by the clock, :defend, :back-off, the gym's :attack, the sim's mobs).
type: reference
tags: [bot, plan, survival, food, intents, goals]
aliases: [:hunt, :provisioned?, :sword, :armed?, :intent/target-pos, :fight, :back-off, :defend, :attack, :safe?, :cleared?, attack packet, :intent/swings, ghost, :wear, :wear-armour, :armour, :dressed?, :armoured?, spare-ingots, :eat, :fed?, :player/food, :player/saturation, hungry-below, edible, food-held, :not-eaten, food/nutrition, :sim/food, :hungry landing]
status: verified
lastUpdated: 2026-10-18
verifiedAgainst: HEAD
sourceRefs:
  - src/clojurecraft/eat.clj#defn food-held
  - src/clojurecraft/eat.clj#defn hungry?
  - src/clojurecraft/eat.clj#defn portal-kit?
  - src/clojurecraft/eat.clj#defn hungry-under
  - test/clojurecraft/eat_test.clj#with-the-portal-kit-held-the-meal-is-eaten-up-to-food-18
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
  - src/clojurecraft/combat.clj#defn pressing?
  - src/clojurecraft/combat.clj#defn route-walk
  - src/clojurecraft/combat.clj#defn walk-on
  - test/clojurecraft/combat_test.clj#a-target-behind-a-wall-is-reached-along-a-route
  - test/clojurecraft/combat_test.clj#a-hovering-target-out-of-reach-is-pressed-in-on
  - src/clojurecraft/combat.clj#defmethod fight-stage :retreat
  - src/clojurecraft/combat.clj#defmethod intent/run :fight
  - src/clojurecraft/combat.clj#defmethod intent/run :back-off
  - src/clojurecraft/combat.clj#defn threats
  - src/clojurecraft/combat.clj#defmulti answers
  - src/clojurecraft/combat.clj#defn answered-kinds
  - src/clojurecraft/combat.clj#defn approachable?
  - src/clojurecraft/combat.clj#defn under-cell
  - test/clojurecraft/combat_test.clj#a-yielding-fight-ends-under-ten-health
  - src/clojurecraft/plan.clj#defmulti interrupts?
  - test/clojurecraft/combat_test.clj#a-target-the-approach-cannot-come-to-is-not-approachable
  - src/clojurecraft/combat.clj#defn seen?
  - src/clojurecraft/combat.clj#defn hittable?
  - src/clojurecraft/combat.clj#defn floor-pos
  - test/clojurecraft/combat_test.clj#a-threat-is-judged-from-the-floor-not-the-top-of-a-jump
  - test/clojurecraft/combat_test.clj#only-a-hostile-in-sight-and-in-reach-of-the-floor-is-a-threat
  - src/clojurecraft/combat.clj#defmethod plan/next-intent :defend
  - src/clojurecraft/combat.clj#defn quarry
  - src/clojurecraft/combat.clj#defn reach
  - src/clojurecraft/combat.clj#defn noted
  - test/clojurecraft/combat_test.clj#a-creeper-that-stands-still-is-shunned-not-fled-from-for-ever
  - src/clojurecraft/combat.clj#def look-ms 3000
  - test/clojurecraft/combat_test.clj#an-attack-trial-looks-round-before-it-finds-nothing
  - src/clojurecraft/hunt.clj#defn game
  - test/clojurecraft/hunt_test.clj#an-animal-whose-cell-a-walk-failed-at-is-not-hunted-again
  - src/clojurecraft/hunt.clj#defn fightable-from-route?
  - test/clojurecraft/hunt_test.clj#an-animal-up-a-tree-is-not-game-from-beneath-it
  - src/clojurecraft/hunt.clj#defn reach
  - test/clojurecraft/hunt_test.clj#a-starving-bot-hunts-further-afield
  - src/clojurecraft/hunt.clj#defn fresh-kill
  - src/clojurecraft/hunt.clj#defn hunting?
  - test/clojurecraft/hunt_test.clj#a-hunt-running-is-never-done-under-it
  - test/clojurecraft/hunt_test.clj#a-fight-that-left-the-animal-alive-is-no-kill
  - src/clojurecraft/hunt.clj#defmethod plan/next-intent :hunt
  - src/clojurecraft/hunt.clj#defn foraging?
  - src/clojurecraft/hunt.clj#defn afloat?
  - src/clojurecraft/hunt.clj#defn provision-phase
  - src/clojurecraft/hunt.clj#defn forage-state
  - src/clojurecraft/hunt.clj#defn forage-leg
  - src/clojurecraft/hunt.clj#defn forage-reaches
  - src/clojurecraft/hunt.clj#defn walkable-end
  - src/clojurecraft/hunt.clj#defmethod intent/run :forage
  - test/clojurecraft/hunt_test.clj#the-search-is-bounded-and-never-held-against-a-build-or-the-mine
  - test/clojurecraft/hunt_test.clj#with-the-stone-pickaxe-food-is-provisioned-before-the-descent
  - test/clojurecraft/hunt_test.clj#the-early-search-is-short-and-leaves-the-kits-its-own-budget
  - test/clojurecraft/hunt_test.clj#a-forage-leg-turns-a-quarter-right-and-falls-back-to-the-next-way
  - test/clojurecraft/hunt_test.clj#the-forage-intent-becomes-the-walk-it-chose-or-ends-the-search
  - src/clojurecraft/hunt.clj#def strike-range 4.5
  - src/clojurecraft/sword.clj#defn payable
  - src/clojurecraft/sword.clj#defn serviceable
  - src/clojurecraft/sword.clj#defn wanted
  - src/clojurecraft/sword.clj#defn blade?
  - "resources/clojurecraft/goals.edn#{:goal/id :hunt"
  - "resources/clojurecraft/goals.edn#{:goal/id :sword"
  - test/clojurecraft/hunt_test.clj#hungry-with-nothing-to-eat-the-nearest-cow-is-walked-to-then-fought
  - test/clojurecraft/hunt_test.clj#a-kill-just-made-is-collected-where-it-fell
  - test/clojurecraft/hunt_test.clj#a-hungry-bot-kills-a-cow-picks-up-its-beef-and-eats-it-against-the-model
  - test/clojurecraft/sword_test.clj#the-best-sword-the-held-items-make-better-than-the-one-held
  - test/clojurecraft/sword_test.clj#iron-only-from-ingots-the-armour-leaves
  - "resources/clojurecraft/goals.edn#{:goal/id :defend"
  - "resources/clojurecraft/goals.edn#{:goal/id :attack"
  - src/clojurecraft/plan.clj#defn through
  - src/clojurecraft/sim.clj#defmethod on-packet [:play :attack]
  - test/clojurecraft/combat_test.clj#an-attack-is-its-own-packet-the-entity-id-alone
  - test/clojurecraft/combat_test.clj#a-fight-faces-its-target-then-attacks-650-ms-apart
  - test/clojurecraft/combat_test.clj#a-target-that-takes-25-swings-is-a-ghost
  - test/clojurecraft/combat_test.clj#under-ten-health-the-fight-breaks-off-and-walks-away
  - test/clojurecraft/combat_test.clj#a-zombie-at-hand-is-fought-a-creeper-backed-away-from
  - test/clojurecraft/combat_test.clj#no-two-attacks-within-600-ms
  - test/clojurecraft/combat_test.clj#a-zombie-at-hand-is-killed-against-the-model
  - test/clojurecraft/combat_test.clj#the-attack-trial-kills-a-zombie-coming-from-afar-against-the-model
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

**`:fight`** (`{:intent/kind :fight :intent/target eid}`, the target an entity id since `:intent/target`, `:plan/blacklist` and `:intention/target` were widened to a position or an id). Stages `:arm` (the best `sword` held put in hand by `place/equip`, the hand when none), `:approach` (`intent/toward` the target's `aim`, `aim-height` 0.9 over its feet, until `approach-range` 3 on the plane, while that brings it 0.1 nearer; after `stall-ticks` 20 without, along a route: `route-walk`, a child `:walk` the `:fight` runs to the end of path's `:near` route within `route-range` 2 of the target's cell (`under-cell`: brought down to the feet's height when it is over them, since `:near` counts the height apart in its range and a blaze two over its floor was near only from the cell under it), stood in (`:fight` is one of `intent/standing-walks`: a walk near a cell ended in reach of it through the wall), `walk-on` dropping it when done or when the target is more than 6 from that stand, its failure the fight's `:unreachable`; `:here` when the feet already stand there, and the fight engages (blaze-fortress-j run 3: a fence kept the straight approach just past 3); `:unreachable` at once when no route reaches it, and after 20 s in all; an engage that sends no attack for 20 s fails `:unreachable` too, one that holds its ground after `hold-timeout-ms` 60 s (gym pearls-warped-b run 3 held twenty minutes on an enderman fourteen blocks below); gym blaze-fortress-h walked straight into the fortress's fences for twenty seconds a fight and failed three in a row, issue #262), `:engage` (face it every tick; when `:time/now` has passed `:intent/next-swing`, the eye is within `attack-reach` 3.2 of the aim and the eyes face it within 20° of yaw: `attack`, then `swing`, as the vanilla client sends them, the next swing `swing-every` 650 ms later, `:intent/swings` counted; back to `:approach` when it is more than 4 off; while the aim is out of reach and the target `press-within` 0.8 or more away on the plane it walks on toward it, `pressing?`: a blaze hovering two over the floor had its aim 3.3 from the eye from 3 on the plane, past reach, and the engage faced it and never attacked, issue #262). Done when the target is gone (`entity/gone?`: removed, or dead by the server's entity event, [[sib-blaze-combat]]); `:ghost` after `max-swings` 25 in reach, and the planner blacklists the id. Under `retreat-health` 10 it breaks off (`:retreat`: straight away until `retreat-distance` 6, or 10 s) and is done: a fight left is not a failure, and the `:defend` row then backs away instead; with `:intent/yield?` it ends there (done, standing) and its row plans the retreat itself (the blaze-rods row's cover, issue #262); with `:intent/to-the-end?` it never breaks off (a blaze at hand follows any retreat). Nothing ends a running intent but itself, a preempting row, or the chosen row's `plan/interrupts?` (keyed by `:goal/plan`, false by default; the intent is abandoned `:interrupted` and the act plans again in the same tick). `approachable?` (within `approach-range` on the plane, or a `route-walk`) is what an act asks before it chooses a target, so it never starts a fight the approach can only fail: gym blaze-fire-a runs 2 and 4 chose blazes a walk's route (within 3) came to from the end of a tunnel and failed three fights `:unreachable` in four seconds, the approach's route (within 2) finding none. With `:intent/hold?` (issue #263, the pearl hunt's burrow, [[ender-pearls]]) it never walks: the approach stands facing the target until it comes within 3 (`hold-timeout-ms` 60 s at most, an enderman stared at from far off takes that long), the engage never presses, and it never breaks off. The pacing is a deadline on the intent, never a count of ticks: ruststeve's tick-paced swings collapsed under the packet flood into forty in 66 ms, all but one inside the mob's 10-tick hurt invulnerability. `no-two-attacks-within-600-ms` holds it over generated clocks. The order matters: gym attack-259a sent `set-carried-item`, `swing`, `attack` (the issue's order) and its stone sword took the husk's health a point a hit, twenty-two hits, each at the charge of a swing just begun; the recordings showed the attacks 650 ms apart (a minimum of 650 over 21 gaps).

**`:back-off`**: straight away from the target until `back-off-to` 8 is between them, it is gone, or 10 s have passed (a cornered bot plans again). A back-off that ran out its clock with the target still nearer than `back-off-to` (the bot in a dead end) keeps the target in `:plan/cornered` (`cornered`), and `:defend` fights it to the end from then on, unless the health is under `retreat-health` and it is no neutral, when it is backed away from again (issue #563, probe 40033104245: two zombies cornered by a void back-off were fought to the end at 7.3 health down to 1.3; sim race 39593219246, issue #161, R3/41: a zombie two blocks off at under one health, backed away from every ten seconds for a quarter hour from the end of a one-wide tunnel; `a-back-off-that-ran-out-its-clock-fights-the-next-time`).

**`:defend`** (goals.edn): an always row at priority 0, inserted after the two escapes and so before the meal and the armour (`plan/in-play` sorts stably). Done by `:safe?`: no `threats`, the hostiles (`entity/hostiles`: the game's monsters, an enderman or a zombified piglin only once the server says it has a target, `entity/creepy?`: gym dragon-b run 1 and dragon-c runs 2 and 3 were slain by an enderman the bot had stared at and never fought back; the pearls row answers `:enderman` itself, from its burrow) within `defend-radius` 4, a creeper within `creeper-radius` 6, none blacklisted, none of a kind a target in play answers itself (`answered-kinds`: the `answers` multimethod on the row's `:goal/plan`, for the rows worked here, not reached and not done; the blaze-rods row answers `:blaze`, gym blaze-fire-a run 1 having fought a spawner's burst of six at hand from 20 health to 7 in four seconds while that row would have taken cover), and each `hittable?`: in sight (`seen?`, the line from the eye to its aim clear of solid cells, an unloaded one solid) with its feet no more than `over-feet` 3 over the bot's nor `under-feet` 2 under. Gym blaze-fortress-c, from a tunnel beside a fortress, went after blazes through the walls and hovering over it, failed `:unreachable`, and switched every half second between one of them and the blaze the blaze-rods row was fighting until the plan failed (issue #262). Planned by `:defend`: the nearest threat, backed away from when it is a creeper or the health is under 10, else fought; a neutral mob turned on the bot (an angry enderman) is fought to the end (`:intent/to-the-end?`) whatever the health, since it is faster than the bot and teleports after it (gym dragon-e run 1 struck one five times, 40 to 10, then backed away under 10 health and died in its hands). No sword is made first: a zombie at hand is answered with what is held. A creeper that stands still is not backed away from for ever (issues #69, #62, #161: a bot at food 0 and one health, a creeper five blocks off in a pocket or behind a ledge that never closed in, and the walk to the ore or the water preempted by `:back-off` every ten seconds, or every half second as the walk crossed the six-block line, for the rest of the run): the first tick of a `:back-off` notes its target in `:plan/backed-off` (`noted`: where it stood, how many times), and `reach` shrinks the radius it is a threat within, from `creeper-radius` 6 (`defend-radius` 4 for any other kind) to `shunned-radius` 3 after `shun-after` 2 back-offs from it standing within `shun-drift` 1 of where it first stood, and to `given-up-radius` 1.5 after `given-up-after` 6. Any hostile is noted, not only a creeper: sim race 39076908669 (issue #62, world R1/11) backed away at a third of a health from a zombie stuck in the rock three blocks off and climbed back toward it, seven thousand times. A mob that moves on is counted afresh; a creeper's fuse (the model's `:swell` 3) is what the smaller radius leaves the bot to answer.

**`:attack`** (`--until attack`): the gym's trial, every hostile within `attack-radius` 16 fought (`quarry`, done by `:cleared?`, and not before `look-ms` 3000 after the plan went active: the server shows a landing's entities a moment after its chunks, and gym attack-259a run 3 was done 0.2 s after the go with its husk unseen). It is the first row marked `:goal/aside?`: `plan/through`, `plan/current-until` and the results page's chain leave asides out, as they leave out the water trial, so no chained run ever takes it as a step.

A target no fight can strike or reach is not picked again for a while (`combat/given-up`, `:plan/given-up`, `give-up-ms` 60 s): an `:engage` that finds its target not `strikable?` with no `route-walk` still ends done (issue #564: a blaze is not blacklisted, and its row's tunnel starts), but it records the target given up at that moment, and `threats`, `quarry` and `hunt/game` pass it over while its aim stays out of `in-reach?` and the minute has not run out. `threats` also asks `approachable?` now, as the acts that choose a blaze already did. Without this, a zombie in water two blocks below the bot (seen, `hittable?`, never strikable, no route) was fought and given up every 150 ms for the whole stage, and the water bucket, the ingots, the furnace and the portal kit each timed out behind it (issues #69, #62, #582 and #161; probe 40084779455 played both #69 snapshots 3000 ticks in that loop, alive at full health).

**The model's mobs** are issue #258's ([[sim-mobs]]): `sim/init` takes `:mobs [{:type :pos}]`, zombies walk at the bot and hit it, and an `attack` in reach hurts by the held item. The fight tests kill a zombie at hand with `:defend` and one coming from afar with the `:attack` trial against it.

**Gym rows.** `attack`: a husk with `NoAI` (a husk does not burn by day) three east of the landing at noon, a stone sword; judged by no husk within 24 of the landing, and the attacks' spacing read from the recording. `defend`: three zombies two blocks round the landing at midnight, the bot in iron armour put on by the fixture (`item replace`, since a zombie at hand comes before dressing) with an iron sword; judged by a zombie killed (a kill counter the setup adds, `minecraft.killed:minecraft.zombie`: counting zombies round the landing counted the night's new ones too), health left and no death. Not all three: the row is done when none is within 4, and in gym defend-259a the others wandered off while the first was fought. Truths are asked at the landing, not `execute as` the bot: that answered RCON with nothing.

`hittable?` judges a mob from `floor-pos`, the feet at the floor the bot stands on or jumped from (the cell under them when nothing solid is under the cell they are in), not from a jump's height: a stairs up lifts the bot a block and more at the top of every step, and two zombies three over its shaft were in sight only there, so `:defend` began a fight and dropped it every fourteen ticks, the stairs up preempted and restarted each time (issue #178, world R4/14).

## The food producer (hunt.clj)
`:hunt` is an always row at priority 0 after the meal: done by `:provisioned?`, no meat wanted (`hunt/wants-meat?`: hungry with nothing edible held, or `hunt/provisioning?`: in a `provision-phase`, `:early` with a stone pickaxe held (before the descent for coal and iron, while the bot is still among the animals, issue #504) or `:kit` with flint and steel and a water bucket held, and under `provision-wanted` 6 edible items, so the Nether is entered with meals, issue #501), (the meal comes first), or no `game` and no `fresh-kill` while no hunt intent runs (`hunting?`: the first run against the model killed its cow, the row found nothing left, and the planner abandoned the fight `:goal-met` the tick the cow died, before it was done, so its beef was never collected). `game` is the nearest cow, mooshroom, pig, sheep or rabbit (`game-kinds`, never a chicken: raw chicken may make the eater hungry), alive, not blacklisted, within `reach` (`hunt-radius` 24, `starving-radius` 48 once the food is at or under `starving-below` 6: issues #69, #62, #135, bots at food 0 and one health with game just past 24 blocks, and the model places animals three chunks round the player), the first of the three nearest a route reaches (`intent/routable?`). Planned by `:hunt`: a kill just made (the last intent a `:fight` for `:hunt`, done, with `:intent/target-pos`, where the fight last saw its target, and the target gone: `entity/gone?`, for a fight that ended with the animal alive is no kill. A `:fight` for `:hunt` never breaks off under `retreat-health`, an animal not striking back: a starving bot at 0.7 health used to retreat, end the fight done, collect at the live cow, fail `:not-picked-up` and fight again for the whole run, issue #178) is a `:collect` of the meat there; else a `:walk` to the animal's cell, and once within `strike-range` 4.5 on the plane (past a walk's reach, so a walk that arrived is never asked again) a `:fight` for `:hunt`. The meat is eaten raw by `:eat`. Against the model a cow drops one to three beef by its loot table ([[sim-mobs]]).

An animal is game only where a blow can reach it from the end of the walk (`hunt/fightable-from-route?`: its feet no more than `intent/pickup-rise` over the route's end, so a blow reaches it and the meat can be picked up): sheep on a spruce crown five over the walk's end were fought three times from beneath, `:unreachable`, and the third ended the plan (issue #515, R2/30).

With none in reach while provisioning, the bot looks for animals on foot (`hunt/foraging?`, issue #501: herds are about one chunk in ten, and three raw meats were eaten to the bar's 18 before the portal, so the Nether leg began with none): a `:forage` intent, planned by `:hunt` when no `game` and no kill stands, picks the next leg (`hunt/forage-leg`: the end of the first route (`walkable-end`: found, or partial and going 12 blocks on) to a cell `forage-reaches` out, 48 blocks for the first two legs and 48 more for each two after up to 96, then 48, 32 or 16, along a `forage-headings` heading, a quarter turn right of the last leg's, the others after it when it has no route) and becomes the `:walk` to it. `:hunt/forage` ({:forage/since :forage/leg :forage/retry-at :forage/phase}) keeps the search across legs, one search a `provision-phase` (`forage-state`: another phase's is not this one's): it ends `forage-budget` after its first leg, `early-forage-ms` (two minutes) before the descent and `forage-ms` (six minutes) with the kit (issue #504: the kit is often finished at y -29, where there is nothing to find, so the stock is made on the surface after the stone pickaxe, and topped up if the bot surfaces with the kit); a look that finds no route at any heading waits `forage-retry-ms` (`:forage/retry-at`) before the next; it pauses after a failed intent (`:plan/attempts` and `:plan/unwalkable`, so failures in a row never end the plan), with health under 14 or a hostile within `forage-wary` 32 blocks (sim run R1/5 searched at night through creepers and zombies until it died), while a `:build` runs (a cast is never left half made), a `:dig` already begun (it is finished; a stairs or a tunnel is the descent and is not paused, so the search still wins before they start) or a `:swim-out` or `:flee` already begun (an escape is finished: the box leaves the water before the swim is done), while `afloat?` (the flag, or the box on water: a `:go` plans before a tick writes the flag, and a pond's first intent was a forage ahead of the swim out) and with the feet under a roof (a bot in its mine has no animals to find). Then the row is done and the portal goes on with what is held: the search never holds it up. Cooked meat (a furnace is in the kit) and the crops and apples the overworld gives are not yet looked for; they count when held (`edible-held`).

`game` skips an animal whose cell is in `:plan/blacklist` (a walk that failed `:no-path` blacklists the cell, not the animal) and chooses none while the body is in water, where no route is walked from (issue #162, R1-41-1 and real-2-s2-Clj_r05: a sheep across the sea and an animal above a tunnel were each walked at every twelve seconds until twelve walks failed the plan).

## The sword (sword.clj)
`:sword` is an always row at the portal kit's priority after the armour, so a chained run comes to it once the portal's iron is in hand and the wood-hungry mining is behind it; only a chained run makes one (a gym stage measures its own target: with the row in play the kit stage placed its held table for a wooden sword, undid the kit, and dug the table back until it timed out). Done by `:armed?`: nothing `wanted`. `payable` is the first of an iron, stone and wooden sword better than the best `serviceable` sword (`combat/swords` order; a sword under `window/spare-uses` swings is not serviceable, `window/swings-left`) whose blade the held items pay for (`blade?`: iron only from ingots spare once the armour wants nothing, cobblestone, planks; never a log) with a handle (a stick, or two planks for sticks). `wanted` is that sword when `table-ready?` (a table near, or `pieces?`: a table, its planks or a log counted by `armour/counts`, so a log laid into the grid still counts). The always row never goes looking for a tree. Planned through the needs planner on `for-decide`, which hides a worn sword from `make/counts` (the stack keeps its slot), shows the grid, and does not continue a log chain once the pieces are held. A hunt whose sword is worn or gone makes `payable` the same way when the craft can start from here (a table already held, or a remembered trunk), and waits `:no-sword` only with nothing held and no craft ([[ender-pearls]]). Of two swords of one name, `combat/sword` arms the one with more swings left. Until a sword is made, fights are by hand: `:defend` never stops to make a sword with a zombie at hand.

## See also
- [[water-escape]] - the other always rows, and the preemption they share.
- [[death-and-respawn]] - set-health's other reader.
- [[sib-blaze-combat]] - what the fight steps will inherit.
