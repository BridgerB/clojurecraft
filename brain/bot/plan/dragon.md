---
title: Dragon
description: How the bot breaks the End's crystals and kills the dragon (issue #267) - a pillar beside each spike, a two-block wall between the stand and the crystal's blast, the strike, the pillar dug back down; the perched dragon struck by its box from the ground by the fountain; the win from the server's death event, the dragon's removal with no boss bar, or the credits.
type: reference
tags: [bot, plan, end, dragon, combat, goals]
aliases: [dragon.clj, dragon, :crystals, :dragon, :crystals-gone?, :dragon-dead?, :pillar, :dig-down, :strike, :perch, :world/dragon-dead?, :world/boss-bars, boss-event, game-event, win_game, spike-cages, spike-crystals]
status: verified
lastUpdated: 2026-10-21
verifiedAgainst: HEAD
sourceRefs:
  - src/clojurecraft/dragon.clj#defn approach-along
  - src/clojurecraft/dragon.clj#defn best-approach
  - src/clojurecraft/dragon.clj#defn wall-gap
  - src/clojurecraft/dragon.clj#defn on-pillar?
  - src/clojurecraft/dragon.clj#defn on-spike?
  - src/clojurecraft/dragon.clj#defn off-island?
  - src/clojurecraft/dragon.clj#defn island-shore
  - src/clojurecraft/dragon.clj#defn toward-island
  - src/clojurecraft/dragon.clj#defn pillar-top
  - src/clojurecraft/dragon.clj#defn toward-crystals
  - src/clojurecraft/dragon.clj#defn climb
  - src/clojurecraft/dragon.clj#defmethod intent/run :pillar
  - src/clojurecraft/dragon.clj#defn equipped
  - test/clojurecraft/dragon_test.clj#a-climb-reads-no-click-as-a-placement-and-refills-an-emptied-hand
  - src/clojurecraft/dragon.clj#defmethod intent/run :dig-down
  - src/clojurecraft/dragon.clj#defn hit
  - src/clojurecraft/dragon.clj#defmethod intent/run :strike
  - src/clojurecraft/dragon.clj#defn perched?
  - src/clojurecraft/dragon.clj#defn breath-reach
  - src/clojurecraft/dragon.clj#defn breath-on-way?
  - src/clojurecraft/dragon.clj#defmethod plan/interrupts? :dragon
  - src/clojurecraft/dragon.clj#defn look-away
  - src/clojurecraft/dragon.clj#defn wear-pumpkin
  - src/clojurecraft/dragon.clj#defn looks-away
  - src/clojurecraft/dragon.clj#defmethod plan/steer :crystals
  - src/clojurecraft/armour.clj#def parts
  - test/clojurecraft/dragon_test.clj#a-carved-pumpkin-held-is-worn-in-the-end-before-anything-else
  - test/clojurecraft/armour_test.clj#a-carved-pumpkin-is-headwear-the-armour-row-leaves-alone
  - src/clojurecraft/dragon.clj#defmethod intent/run :perch
  - src/clojurecraft/entity.clj#defn hostile?
  - src/clojurecraft/dragon.clj#defn fountain-stand
  - src/clojurecraft/dragon.clj#defn sheltered?
  - src/clojurecraft/dragon.clj#defn recover
  - src/clojurecraft/dragon.clj#defn toward-dragon
  - test/clojurecraft/dragon_test.clj#hurt-under-retreat-health-the-dragon-is-not-fought
  - test/clojurecraft/armour_test.clj#a-wear-waits-for-the-cursor-the-clicks-answer-has-not-shown-yet
  - src/clojurecraft/dragon.clj#defn turned?
  - src/clojurecraft/dragon.clj#defn behind?
  - src/clojurecraft/dragon.clj#defn in-bite?
  - src/clojurecraft/dragon.clj#defn before-head?
  - src/clojurecraft/dragon.clj#defn bite-on-way?
  - src/clojurecraft/dragon.clj#defn around
  - src/clojurecraft/dragon.clj#defn in-basin?
  - src/clojurecraft/dragon.clj#defn grounded?
  - src/clojurecraft/dragon.clj#defn scanning?
  - src/clojurecraft/dragon.clj#defn in-breath?
  - src/clojurecraft/dragon.clj#defn holds?
  - src/clojurecraft/dragon.clj#defn breathed?
  - src/clojurecraft/dragon.clj#defn shelter-walk
  - src/clojurecraft/dragon.clj#defn escape
  - src/clojurecraft/dragon.clj#defmethod combat/answers :dragon
  - src/clojurecraft/dragon.clj#defn pumpkin-worn?
  - src/clojurecraft/dragon.clj#defmethod combat/answering :dragon
  - src/clojurecraft/dragon.clj#defmethod combat/answering :crystals
  - src/clojurecraft/combat.clj#defmulti answering
  - src/clojurecraft/dragon.clj#defmethod plan/steer :dragon
  - src/clojurecraft/dragon.clj#defn lull?
  - src/clojurecraft/dragon.clj#defn heal-food
  - src/clojurecraft/plan.clj#defmulti steer
  - src/clojurecraft/plan.clj#defn steered
  - src/clojurecraft/mob.clj#defn facing
  - src/clojurecraft/mob.clj#defn ahead
  - src/clojurecraft/mob.clj#defn bitten?
  - src/clojurecraft/sim.clj#defn bite
  - src/clojurecraft/game.clj#defmethod on-packet [:play :move-entity-rot]
  - "resources/clojurecraft/goals.edn#{:goal/id :heal"
  - src/clojurecraft/dragon.clj#defmethod plan/done-by :crystals-gone?
  - src/clojurecraft/dragon.clj#defmethod plan/done-by :dragon-dead?
  - src/clojurecraft/entity.clj#defn dragon-gone?
  - src/clojurecraft/game.clj#defmethod on-packet [:play :entity-event]
  - src/clojurecraft/game.clj#defmethod on-packet [:play :remove-entities]
  - src/clojurecraft/game.clj#defmethod on-packet [:play :boss-event]
  - src/clojurecraft/game.clj#defmethod on-packet [:play :game-event]
  - src/clojurecraft/combat.clj#defn attack
  - src/clojurecraft/end.clj#def spikes
  - src/clojurecraft/sim.clj#defn blast
  - src/clojurecraft/sim.clj#defn perch
  - "resources/clojurecraft/goals.edn#{:goal/id :crystals"
  - "resources/clojurecraft/goals.edn#{:goal/id :dragon"
  - "resources/clojurecraft/problems.edn#{:problem/stage :dragon"
  - src/clojurecraft/gym.clj#def spike-cages
  - src/clojurecraft/gym.clj#def spike-crystals
  - "src/clojurecraft/gym.clj#{:gym/goal :crystals"
  - "src/clojurecraft/gym.clj#{:gym/goal :dragon"
  - "src/clojurecraft/gym.clj#:gym/goals [:eyes-of-ender"
  - src/clojurecraft/gym.clj#defn go-event
  - test/clojurecraft/dragon_test.clj#the-platform-in-the-end-bridges-to-the-island-before-crystals
  - test/clojurecraft/dragon_test.clj#the-approach-stands-three-out-behind-a-wall
  - test/clojurecraft/dragon_test.clj#the-planner-walks-pillars-walls-strikes-and-digs-down
  - test/clojurecraft/dragon_test.clj#a-breath-on-the-way-to-the-stand-is-waited-out-where-the-feet-are
  - test/clojurecraft/dragon_test.clj#a-pillar-with-a-gap-under-it-is-still-the-way-down
  - test/clojurecraft/dragon_test.clj#the-wait-never-looks-an-enderman-in-the-eyes
  - test/clojurecraft/dragon_test.clj#the-climb-jumps-and-puts-the-block-in-once-clear
  - test/clojurecraft/dragon_test.clj#the-dragon-is-dead-by-the-servers-word
  - test/clojurecraft/dragon_test.clj#the-dragon-perches-on-the-fountain
  - test/clojurecraft/dragon_test.clj#a-crystal-is-broken-from-behind-a-wall-against-the-model
  - test/clojurecraft/dragon_test.clj#the-perched-dragon-is-killed-against-the-model
  - test/clojurecraft/dragon_test.clj#the-dragon-turns-by-the-servers-word
  - test/clojurecraft/dragon_test.clj#the-sitting-dragons-head-is-along-its-yaw
  - test/clojurecraft/dragon_test.clj#the-wait-is-behind-the-sitting-dragons-head
  - test/clojurecraft/dragon_test.clj#the-bot-keeps-the-basins-floor-while-the-dragon-sits-or-lands
  - test/clojurecraft/dragon_test.clj#every-look-of-the-dragon-row-is-away-from-an-endermans-eyes
  - test/clojurecraft/dragon_test.clj#in-a-lull-the-bot-eats-for-health-back
  - test/clojurecraft/dragon_test.clj#the-sitting-dragon-is-killed-from-behind-its-head-against-the-model
related:
  - "[[bot/plan/_moc|Plan]]"
  - "[[end-portal]]"
  - "[[sib-end-and-dragon]]"
---

# Dragon

The last stage (issue #267, split from the roadmap's `:end-and-dragon` into `problems.edn`'s `:dragon`, goals `:crystals` and `:dragon`). It starts where [[end-portal]]'s `:enter-end` ends, in the End. Both siblings went beds-not-bow for the dragon and bows for the crystals; clojurecraft has neither, so the crystals are reached by pillaring and the dragon is struck with the sword while it perches.

## The crystals

A crystal stands on the bedrock over each of the ten obsidian spikes (`end/spikes`: radius 2 to 5, 76 to 103 high). Any hit blows it up: a blast of power 6, about 56 damage three blocks off with a clear line (`mob/blast-damage`), so a bot that strikes in the open dies. The blast's damage scales with how much of the body the explosion sees, and the server computes it before the blast breaks any block, so blocks between are a shield. An attack is judged by reach alone, through any block.

`approach-along` works out, for one axis out from the crystal's column: the stand, the cell `stand-off` (3) out with the feet level with the spike's top (the crystal's height minus one), from which the crystal's 2x2 box is two away; the wall, the two cells `wall-off` (2) out at the feet's and the eye's height, which every line from the blast to the body crosses (along an axis only, so the two cells cover the body); and the pillar, the first column out whose cell in the spike's top layer is not obsidian (the bot's own pillar block is no spike: dragon-b's moved the edge a column out once built, and the bot dug back down from the top), never nearer than the stand, from its ground up. The top is read off the block under the crystal, its bedrock: a crystal stands in the fire it lights. On a spike of radius 2 the pillar's top is the stand; on a wider one the stand is on the spike's top, walked onto from the pillar. `best-approach` takes the axis whose pillar foot is nearest the feet.

`toward-crystals` reads the world each tick for the crystal nearest the feet: at the stand, a `:place` into the wall's first gap (`wall-gap`; any block counts, a cage's bars too), then the `:strike`; in the pillar's column under the top, the `:pillar`; at the pillar's top short of the stand, the walk to it; else on a spike (`on-spike?`, three obsidian under the feet; the arrival platform is one thick) the walk back to the nearest pillar top (`pillar-top`), on a pillar (`on-pillar?`, a pillar block under the feet: one, not two, since the fight's dragon smashes what it flies through, cobblestone included, and dragon-c run 1 fell to a stump with a gap under it and walked for the next foot from up there) the `:dig-down`, on the ground the walk to the pillar's foot. Waits `:no-blocks` (nothing to pillar with), `:no-approach`, `:stranded`. Nothing records progress.

- `:pillar` (`climb`): the block in hand, then a jump from the ground, the feet cell kept as `:intent/under`; once the body has risen `clear-rise` over it (the server refuses a block into a body), a `use-item-on` against the top of the block under, looking straight down; the block shown, the next jump. Refusals counted (`:intent/refusals`), `:stuck` after `block-ms` with no block. Pillar blocks are cobblestone and the like, never end stone (the End's ground, which the way down would dig on into). After a swap into the hand `equipped` clears the click's leftovers (`:intent/awaiting`, `:intent/sent-at` while no placement is pending), which the climb would read as a block put and unanswered (:no-answer, issue #178, R1-11-5); a stack used up in the hand goes back to the equip while more is in the pack, and fails :not-held only with none.
- `:dig-down`: a child `:dig` of the block under the feet (its drop wanted or not), the fall waited out, until the feet stand on less than two pillar blocks.
- `:strike`: the best sword in hand (`combat/sword`), then `hit`: the eyes on the nearest point of the target's box, and an attack (`combat/attack`, 650 ms apart) once the look went out a tick before and the box is within `strike-reach` (3, the client's entity reach) of the eye; done when the target is gone, failed `:out-of-reach`.

Not done: the cages round two spikes (radius 2: the stand beside one is outside its bars and would strike through them, unproven; the gym takes them away), the bridge from the End's platform at x 100 over the void to the main island, the exit portal.

## The dragon

`toward-dragon`: while a crystal stands or the feet are up a pillar or spike, the crystals'; a breath cloud or fireball (`area_effect_cloud`, `dragon_fireball`) within its `breath-reach` (its radius, `:entity/radius` from metadata index 8, a fireball's cloud growing from 3 to 7, `breath-radius` 5 when unsaid, plus `breath-margin` 1.5), combat's `:back-off`; away from the fountain's stand (`fountain-stand`, the ground `stand-from` (2) out from the exit portal's pillar at 0 0 along the axis nearest the feet, in the portal's empty basin, none within a breath's reach), the standing walk there, unless a breath reaches the straight way to it (`breath-on-way?`, `segment-distance`: dragon-d runs 1 and 3 walked back through a fireball's cloud and died in it); there, or with every stand under a breath or one on the way, the `:perch` where the feet are (dragon-b waited `:no-fountain` under a fireball's cloud on the basin until the plan failed). `plan/interrupts?` for `:dragon`: any running intent but a `:back-off` ends when a breath is near and the feet are down (a walk ends only by itself otherwise). The `:perch` faces the dragon (`look-away`: never a look that stares at an enderman shown by the server's own cone and line test, `mob/stared?`; straight down instead. The wait followed the dragon over the island's rim, and dragon-c runs 2 and 3 stared at an enderman there, which came and killed the bot; an angry enderman, `entity/creepy?`, is now a hostile the `:defend` row fights) and counts the ticks it holds `perched?` (its phase, `:entity/phase` from metadata index 16, one of the sitting phases 5 to 7; with no phase said, as in the model, within 6 of the fountain on the plane and under y 70); after `perch-ticks` (10) it hits the dragon while its box is in reach, walking in toward the fountain (no nearer than `walk-in-to`) while it is not; done when the dragon is gone, a breath is near, or after `perch-hold-ms`.

The stand is beside the pillar because the perched dragon turns its face to the bot: its head is 6.5 out from the pillar and its neck 5.5, and each hurts what it touches whenever the dragon is not in its hurt time (10 damage, about 6 through iron). Batch dragon-a stood five out, under the head: run 1 landed five hits of 2.5 on the body and died in four seconds of the perch. Two out in front is not enough either (dragon-e run 2, dragon-f run 2: the head lowered to the bot brings the neck over the stand).

## The head

The dragon's yaw is tracked (`:entity/yaw`, degrees, from `add-entity`'s byte, `move-entity-pos-rot` and `move-entity-rot` (their specs grown by the rotation and on-ground), `entity-position-sync` (grown by the rest of its PositionMoveRotation) and `teleport-entity`, relative by its flag 8). Its head is along `mob/facing`, (sin yaw, −cos yaw), the opposite of any other mob's look: `EnderDragon.aiStep` puts the head part there, and `DragonSittingScanningPhase` turns the dragon by the same vector toward its target, then holds it through the attacking and flaming phases. `mob/ahead` gives a point's [along across] in that frame, `mob/beside` the reverse. While the dragon is `turned?` (sitting, yaw known), `fountain-stand` keeps only the stands `behind?` its body by `behind-by` (1) and takes the nearest; the `:perch` hands back when the head comes onto the feet (`in-bite?`: not `leave-by` 0.5 behind, within `mob/bite-out`) and a stand behind it is there to go to, and the planner walks to it; with none (every one under a breath) the perch holds where the feet are. From outside the basin (`in-basin?`, within `walk-in-to` of the fountain), a walk whose straight way passes before the head (`before-head?`: along from 0.5 behind out to 8, within `clear-across` 3.3; `bite-on-way?`) goes round it (`around`): out `side-out` (5) beside the facing on the feet's side, short of its reach, then along to behind the stands' line, then in; `plan/interrupts?` ends such a walk, unless the feet are before the head already (the walk out). Inside the basin the walk to the new stand is straight and low: the head and neck reach down to the basin's rim, and dragon2-a runs 1 and 3, the head turned onto them, walked out up the rim (a way round, a back-off from the dragon) into the neck and died there, where run 1 had struck the dragon twenty times unhurt from behind it in the basin. The dragon is the row's own kind (`combat/answers`): dragon2-a run 1's `:defend` fought the perched dragon at hand by `:fight`'s aim point, off the stand. The `:perch`'s walk in toward the fountain never goes under the head. Behind the body only the tail is, which hurts nothing; the wings only push.

## The basin's floor

Every head death of dragon2-a and dragon2-b was a bot whose feet were up on the basin's rim (y 63 to 65): a way round, a back-off from a breath straight away to 8 out, the landing dragon's head (phase 3) catching a bot out there; the bot on the floor (feet 62) behind the body struck twenty times unhurt through phases 6, 7 and 5. So while the dragon is `grounded?` (its phase one of `sitting-phases` or `landing-phases`, 3; by place when none is said, as in the model; `turned?` and so the stand behind, `in-bite?`, `before-head?` and the way round all count the landing dragon), the bot in the basin (`in-basin?`) answers a breath near its feet inside it: `toward-dragon` walks straight to the stand behind out of the breath's reach (`fountain-stand`; a `shelter-walk`, `:intent/for :shelter`, which `plan/interrupts?` lets run), or with none, the feet out of the breath's own radius (`in-breath?`), the `:perch` holds where the feet are (`holds?`: the perch does not end on that breath, nor does `interrupts?` end it). Every other breath near the feet, in the breath itself, under a flying dragon or outside the basin, is left by a `shelter-walk` to `escape`: the nearest feet cell on the ground, of the cells `escape-from` (4) to `escape-to` (12) out from the fountain in `escape-ways` (16) directions, that no breath reaches (`breathed?`) and that is not `before-head?`; combat's straight `:back-off` only with no such cell loaded. The straight back-off is what killed dragon3-a: run 2 backed off from a fireball and then its cloud on the stand toward −x, into the fountain's pillar, and jumped against it at x 1.3 for six seconds inside the cloud until it died (`move-player-pos-rot` in the recording); runs 1 and 3 died the same way in clouds while backing off. A walk's route (A*) goes round the pillar and the rim's steps. Through the scanning turn (`scanning?`, phase 6, about 25 ticks, the head sweeping round the basin faster than a walk) the `:perch` holds whatever stand the feet are at (no hand-back on `in-bite?`, no new stand from the planner); the stand is re-chosen in phases 7 and 5, when the dragon holds its yaw. The `:heal` row's lull (`lull?`) counts a landing dragon as no lull.

## Endermen and the meal

`plan/steer`, a registry keyed by `:goal/plan` that `plan/steered` applies each tick to the running intent's row, turns every look of the `:dragon` and `:crystals` rows' intents with `look-away` (`looks-away`): dragon-f run 3 stared at three endermen in turn on its walks to and from the stand and was slain by the third; dragon-e run 1 at one on its first walk. With no look set this tick (e.g. during the wear of the carved pumpkin), `looks-away` turns the standing look (`:player/look`) down as well, so a bot landing facing an enderman never stares into its eyes before the pumpkin is on (gym chain-w5 dragon run 1). And before anything else in the End, both rows put a held carved pumpkin on the head (`wear-pumpkin`, the `:wear` intent; `armour/parts` knows it as headwear the `:wear-armour` row never dresses the bot in): a look through a worn carved pumpkin never provokes an enderman, whatever it crosses. Gym chain-a3's dragon row lost all three runs to endermen (48, 424 and 506 s) with looks alone turned; the pumpkin is in the dragon and chain kits. The wear itself can lose the pumpkin on the cursor: gym chain-w1's dragon run 3 failed its wear `:not-picked-up` (the click's answer moved the state id before the set-cursor packet), `wear-pumpkin` then saw no pumpkin in the store slots and never asked again, and an enderman slew the bot at the perch; `wear-stage :put` now waits out `window/answer-timeout` for the cursor, `:pick` goes straight to `:put` with the piece on the cursor, and a pumpkin on the cursor counts as held. Hurt under `combat/retreat-health` the dragon is not fought at all: `toward-dragon` chooses `recover` (a meal the bar can take, else, while not yet in shelter outside the basin (`sheltered?`), the shelter walk to `escape`'s cell, else the wait `:low-health` where the feet are for the bar's own healing) and `interrupts?` ends a running `:perch` at that line; gym chain-w3's dragon runs 2 and 3 perched on at ten, then four and a half health, and the dragon slew both (the `:heal` row's lull never comes under a sitting dragon), while gym chain-w5's dragon run 3 oscillated between adjacent cells because `escape` excluded the feet's cell even once outside the basin. A walk steers by `:control/yaw`, so a look straight down changes nothing of the way. While a breath cloud or fireball reaches the feet, the row answers every hostile shown itself (`combat/answering`, a registry by `:goal/plan` over the world that `answered-kinds` reads, `answers` alone by default), so the `:defend` row fights nothing and the back-off from the breath runs: dragon2-a run 2 and dragon2-b runs 1 and 3 died in breath clouds, their back-offs cut by `:defend`'s fights with an enderman in the cloud. With the pumpkin worn (`pumpkin-worn?`) both End rows answer endermen at all times: through the pumpkin no look has provoked one, so a creepy one is after the dragon that hit it, and fighting it (a turned neutral is fought to the end at any health) only turns it on the bot; gym chain-w4's dragon run 1 died that way eight seconds in, walking to its stand past one at hand. The `:heal` row (always, priority 0 after `:eat`, the End only) eats (`eat/food-held`, the `:eat` intent) when the bot is hurt, its food under `heal-below` (18, where natural regeneration stops; the `:eat` row eats only under 14) and in a lull (`lull?`: no breath near, the dragon not perched, no hostile within `calm-radius` 8).

The dragon is struck by its box, not through `:fight`: its box is 16 by 8 with its feet on the fountain's pillar, four over the portal, and `:fight`'s reach to an aim point 0.9 over the feet is never met from a floor; the server's own test is the box. The body takes a quarter of a hit plus one (the head takes it all; its part's position is the client's to compute, not done), and a perched dragon takes off after 50 damage in one perch, so a kill takes several perches.

## The win

`:world/dragon-dead?`, set in `game.clj` by the server's word: `entity-event` status 3 on the dragon (a sitting dragon dies through the living entity's death), the dragon removed (`remove-entities`) within `entity/dragon-seen-radius` of the bot with no boss bar shown (`entity/dragon-gone?`; `:world/boss-bars` from the new `boss-event` spec, uuid and action only; a summoned dragon has no fight and no bar; one flown out of tracking is not dead), or `game-event` win_game (4), the credits after the exit portal, answered with `client-command` 0 as the vanilla client does.

## Goals

- `:crystals` (priority 26, aside, `--until crystals`, the End only): done `:crystals-gone?`, no crystal shown and the feet off every pillar and spike, once `look-ms` have passed.
- `:dragon` (priority 27, aside, `--until dragon`, the End only): done `:dragon-dead?`. The race's `:until` stays where it is: raising it is the lead's call.

## Gym

Rows `crystals` and `dragon`, both `land! :end` (the main island, ruststeve's End start) at difficulty normal, 25 minutes. `crystals`: `spike-cages` (setup, the island forceloaded) kills every crystal (a crystal lights fire in its own cell each tick) and takes the iron bars and the fire off each spike's top; `spike-crystals` (summons) kills the dragon and the crystals again (the setup's kill misses those whose entities have not loaded: dragon-a's killed five of ten), clears the fire again and summons a crystal on each spike's top through `positioned over world_surface`, `~ ~ ~` (an integer x and z `positioned` names is a block's middle already), on its bedrock as the fight puts them; two stacks of cobblestone, an iron pickaxe and a sword given; judged by `execute in minecraft:the_end unless entity @e[type=minecraft:end_crystal]`. The bot takes no harm in this row (`invulnerable`): the fight's own dragon flies over the pillars and dragon-b's three runs died to it, the row measuring the dragon and not the crystals; the wall's worth is in the recordings (dragon-b run 1: its first crystal struck from behind the wall for 1 damage). `dragon`: the crystals killed (setup and summons), the dragon killed and the exit portal its death opens filled back to air (when the End's fight scanned the world before the kill, the dragon killed is the fight's own and the portal opens in the basin where the bot waits: dragon-c run 1 walked into it 25 s in and got the credits, a pass with nothing fought), then a dragon summoned at 0 80 0 with `DragonPhase:0` and 200 health, a sword, iron armour, cobblestone and a pickaxe given; judged by `unless entity @e[type=minecraft:ender_dragon]`. The End's fight starts on the bot's arrival, after the summons: it takes the summoned dragon for its own (its boss bar shown), and in the crystals row it makes a dragon of its own that flies while the bot pillars.

## Proof

- `dragon_test`: the approach on a spike of radius 2 (the pillar's top is the stand) and 4 (the stand on the top); the planner's walk, pillar, wall, strike and way down, and the walk back off a wide spike; the climb's jump, nothing sent while the body is in the cell, the block against the one under once clear; the win from the death event, the removal with no bar, not with a bar, not far off, and the credits answered; the perch by phase and by place; the yaw from every packet that carries it; the head's frame and the model's bite; the stand behind the sitting dragon, the way round its head from outside the basin and the straight walk inside it, the perch handing back or holding with no stand, the walk cut under the head; the landing dragon's head counted as a sitting one's, a breath in the basin answered inside it (the walk to the stand out of its reach that the breath does not end, the perch held with no stand, the walk to the nearest cell out of every breath's reach behind the head in the breath itself, outside the basin or under a flying dragon, the straight back-off only with no ground loaded), the scanning turn held still through, and `:defend` left with nothing to fight while a breath reaches the feet; the dragon row's looks away from an enderman's eyes; the meal in a lull; and against the model's End, a crystal on a spike of radius 2 broken from behind the wall with no blast taken and the bot back down, the perched dragon killed, and the dragon facing the bot's way in (the model's head and neck hurting what is before them, `sim/bite`) killed from behind with no hurt taken.
- Batch dragon-a (plan run 39152590801, at 03421f2): `crystals` 0/3, every run walking at once for the foot of a pillar on the spike's top (:no-path): the crystals stood on the fire a block over their bedrock and on the corner of their column, so the approach was a block high; `dragon` 0/3: run 1 struck the perched dragon five times (2.5 each, 200 to 187.5) from five out, under its head, and died; runs 2 and 3 waited :no-blocks for crystals the gym's kill missed.
- Batch dragon-b (plan run 39154171045, at 2183057): `crystals` 0/3, every run dead to the fight's own dragon (its fireballs' magic, a knock off a pillar), though no crystal was left at the end; run 1 broke the first crystal by pillar, wall and strike for 1 damage and dug its pillar back down, the first crystal a clojurecraft bot broke, then dug down from the second's pillar at once (its own block moved the spike's edge). `dragon` 0/3: from the basin two out, run 2 struck the perched dragon 21 times (200 to 147.5) with no damage taken until it took off after 50, then waited `:no-fountain` under a fireball's cloud until the plan failed (run 3 the same); run 1 was slain by an enderman.
- Batch dragon-c (plan run 39156110792, at 71a04c9): `crystals` 2/3, runs 2 and 3 breaking all ten by pillar, wall and strike (869 and 700 s); run 1 broke three, then the fight's dragon smashed its third pillar under it (cobblestone is not dragon-immune), it fell to a stump with a gap under it, `on-pillar?` wanting two blocks said no, and it walked for the next foot from up there (`:no-path`) until the plan failed. `dragon` 1/3: run 1 a false pass, the gym's kill of the fight's own dragon having opened the exit portal in the basin, which the bot walked into (the credits, 25 s); runs 2 and 3 slain by an enderman the wait had stared at over the rim (its stared-at flag, then its target, four hits of 3.78 through iron), never fought back, the enderman neutral.
- Batch dragon-d (plan run 39164095788, at cd2dbdd): `crystals` 3/3 (989, 989 and 580 s). `dragon` 1/3: run 2 killed the dragon in 339 s, fighting four angry endermen on the way (the first kill of the dragon by a clojurecraft bot); runs 1 and 3 backed off from a breath to 8 out and walked back to the stand straight through a fireball's cloud lying between (its radius grown to 5.7, past the 6 the stand check allowed for), four hits of 6 a second apart (magic, through iron), the walk ending only by itself.
- Batch dragon-e (plan run 39178268421, at 25d6279): `crystals` 3/3 (990, 992 and 990 s). `dragon` 1/3: run 3 killed the dragon in 368 s; run 1 struck an angry enderman five times (40 to 10), backed away under 10 health (the `:defend` row's retreat) and was slain by it 53 s in; run 2 stood at the stand two out with the dragon sitting (phases 6 then 7) and was touched four times for 6 in two seconds: the head looks down at a bot that near, and the neck's box (3 by 3, 5.5 out along the head's direction, which the pitch shortens) comes to about 3 out, over the stand. The stand is safe only while the head is level or turned away (dragon-b run 2's 21 hits, dragon-d run 2's and dragon-e run 3's kills).
- Batch dragon-f (plan run 39183632770, at 3b49427): `crystals` 3/3 (699, 862 and 928 s). `dragon` 1/3: run 1 killed the dragon in 466 s; run 2 was touched by the sitting dragon at the stand again and slain by it 175 s in; run 3 fought three angry endermen in turn, to the end, and was slain by the third at 270 s. One kill a batch over d, e and f: the stand under the dragon's face and the endermen the wait and the walks stare at are what is left.
- Batch dragon2-a (plan run 39529601511, 136c7f3: the stand behind the sitting dragon by its yaw, the way round its head, the looks away, the meal): crystals 3/3 (987, 711 and 983 s). Dragon 0/3: run 1 struck the sitting dragon twenty times unhurt from the stand behind it (phases 6, 7, 5), then as the scanning dragon turned its head onto the bot it walked out beside the head (the way round) and was touched three times for 6 in a second; its :defend had fought the perched dragon at hand before. Run 3, the head turned onto it at the stand, backed off from the dragon up the basin's rim into the neck, four touches. Run 2 died in a breath cloud, its back-off cut by :defend's fights with an enderman.
- Batch dragon2-b (plan run 39534976957, 0cbfe30: the walk in the basin straight and low, the dragon left to its row): crystals 1/3 (run 1 :no-path for one foot until the plan failed, run 2 out of time with one crystal left, run 3 982 s). Dragon 0/3: runs 1 and 3 killed in breath clouds (magic), run 3's back-offs again cut by :defend's fights; run 2, out on the rim 8 from the fountain at y 65, was touched by the landing dragon's head (phase 3, not a sitting phase, so no stand or way round was chosen against it), then walked in toward the stand under the head it had just turned there, four touches. The crystals failures were both a `:defend` fight with an enderman during a `:dig-down` (the fight walks off the pillar's column), after which the dig-down hung: a path round 2 did not touch.
- Batch dragon3-a (plan run 39543030347, 6883aa1: the basin's floor kept while the dragon sits or lands, the scanning turn held, :defend put off while a breath reaches the feet): crystals 3/3. Dragon 0/3, every run killed by the dragon "using magic" (a breath cloud) while backing off from it (355, 88 and 328 s, longer than round 2's 135 to 219): run 2 backed off straight from a fireball and then its cloud on the stand into the fountain's pillar and jumped against it at x 1.3 inside the cloud for six seconds; runs 1 and 3 died during back-offs the same way, after many perches with the stand re-chosen as the landing dragon turned (walks between opposite stands every second and a half, unhurt). No head death.
- Batch dragon3-b (plan run 39593702551, ff8a0aa: a breath near the feet left by a `shelter-walk` to `escape`, never combat's straight back-off): crystals 3/3 (644, 947 and 899 s). Dragon 3/3, the dragon killed in 413, 656 and 468 s, the first batch every run of which killed it.
