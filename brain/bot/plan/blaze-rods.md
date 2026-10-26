---
title: Blaze rods
description: The :blaze-rods goal (issue #262) - seven rods in the Nether, from the blazes a fortress's spawner makes; the act's order (hurt, wary, outnumbered, a rod lying near, the nearest blaze, a fire by the spawner, the remembered spawner, a camp), the :camp and :rest intents, the gym rows blaze-1, blaze-7 and blaze-fortress, and the sim tests against the model's blazes.
type: reference
tags: [bot, plan, combat, nether, blaze, goals]
aliases: [:blaze-rods, :rods-held?, :camp, :rest, :no-blaze, :no-heal, :healing, rods-wanted, blaze-1, blaze-7, blaze-fortress, spawner, cover, volley]
status: verified
lastUpdated: 2026-10-18
verifiedAgainst: HEAD
sourceRefs:
  - src/clojurecraft/blaze.clj#def rods-wanted 7
  - src/clojurecraft/blaze.clj#def fight-radius 12.0
  - src/clojurecraft/blaze.clj#def camp-ms 60000
  - src/clojurecraft/blaze.clj#defn blazes
  - src/clojurecraft/blaze.clj#defn reachable?
  - src/clojurecraft/blaze.clj#defn quarry
  - src/clojurecraft/combat.clj#defn approachable?
  - src/clojurecraft/combat.clj#defmulti answers
  - test/clojurecraft/blaze_test.clj#the-defend-row-leaves-blazes-to-this-row
  - test/clojurecraft/sim_test.clj#three-of-the-models-blazes-at-hand-the-nearest-is-killed
  - src/clojurecraft/blaze.clj#defn at-hand-blaze
  - src/clojurecraft/blaze.clj#defmethod plan/interrupts? :blaze-rods
  - src/clojurecraft/blaze.clj#defn under-fire?
  - test/clojurecraft/blaze_test.clj#a-blaze-at-hand-is-fought-first
  - test/clojurecraft/blaze_test.clj#only-a-blaze-the-bot-can-reach-is-fought
  - src/clojurecraft/blaze.clj#defn rod-lying
  - src/clojurecraft/blaze.clj#defn nearest-spawner
  - src/clojurecraft/blaze.clj#defn healing
  - src/clojurecraft/blaze.clj#defn meal
  - src/clojurecraft/blaze.clj#defn wary?
  - src/clojurecraft/blaze.clj#defn to-spawner
  - src/clojurecraft/blaze.clj#defn walkable?
  - src/clojurecraft/blaze.clj#defn at-spawner?
  - src/clojurecraft/blaze.clj#defn stand
  - src/clojurecraft/blaze.clj#defn cells-beside
  - src/clojurecraft/eat.clj#defn scrap-held
  - test/clojurecraft/blaze_test.clj#rotten-flesh-is-eaten-when-it-is-all-there-is-to-heal-on
  - src/clojurecraft/blaze.clj#defn given-up?
  - src/clojurecraft/blaze.clj#defn search
  - test/clojurecraft/blaze_test.clj#a-blocked-tunnel-tries-another-stand-then-gives-the-spawner-up
  - test/clojurecraft/blaze_test.clj#with-no-spawner-known-the-bot-searches-for-one
  - src/clojurecraft/blaze.clj#defn level-with?
  - test/clojurecraft/blaze_test.clj#a-spawner-under-the-feet-is-tunnelled-to-its-floor
  - src/clojurecraft/blaze.clj#defmethod plan/next-intent :blaze-rods
  - src/clojurecraft/blaze.clj#defmethod intent/run :camp
  - "resources/clojurecraft/goals.edn#{:goal/id :blaze-rods"
  - src/clojurecraft/gym.clj#def invulnerable
  - src/clojurecraft/gym.clj#def iron-armour
  - src/clojurecraft/gym.clj#def no-fire
  - src/clojurecraft/gym.clj#def fire-resistance
  - src/clojurecraft/gym.clj#defn slow-blaze-spawner
  - src/clojurecraft/gym.clj#defn rods-held
  - test/clojurecraft/blaze_test.clj#seven-rods-held-is-done
  - test/clojurecraft/blaze_test.clj#a-rod-lying-near-then-the-nearest-blaze-then-the-spawner
  - test/clojurecraft/blaze_test.clj#by-the-spawner-the-bot-camps
  - test/clojurecraft/blaze_test.clj#hurt-the-bot-hides-backs-away-eats-or-waits
  - src/clojurecraft/blaze.clj#defn watchers
  - src/clojurecraft/blaze.clj#defn outnumbered?
  - src/clojurecraft/blaze.clj#defn cover
  - src/clojurecraft/blaze.clj#defn hide
  - src/clojurecraft/blaze.clj#defn warily
  - src/clojurecraft/blaze.clj#defn top-in-sight?
  - src/clojurecraft/blaze.clj#defn fires
  - src/clojurecraft/blaze.clj#defn put-out
  - src/clojurecraft/blaze.clj#defmethod intent/run :rest
  - src/clojurecraft/blaze.clj#defmethod path/goal-done? :unseen
  - test/clojurecraft/blaze_test.clj#watched-by-a-volley-the-bot-takes-cover
  - test/clojurecraft/blaze_test.clj#a-camp-ends-when-the-bot-is-hit
  - test/clojurecraft/blaze_test.clj#a-rest-ends-rested-hit-or-watched
  - test/clojurecraft/blaze_test.clj#a-tunnel-to-the-spawner-first-makes-the-pickaxe-it-lacks
  - test/clojurecraft/blaze_test.clj#a-fire-by-the-spawner-is-put-out
  - test/clojurecraft/sim_test.clj#watched-by-three-of-the-models-blazes-the-bot-hides
  - src/clojurecraft/intent.clj#def standing-walks
  - src/clojurecraft/combat.clj#defn clear?
  - test/clojurecraft/blaze_test.clj#the-seventh-rod-from-the-models-blazes
related:
  - "[[bot/plan/_moc|Plan]]"
  - "[[survival]]"
  - "[[collect-intent]]"
  - "[[fortress-tunnel]]"
  - "[[memory-sightings]]"
  - "[[sib-blaze-combat]]"
  - "[[gym-on-runners]]"
---

# Blaze rods

Issue #262, the first stage past the fortress and a child of the roadmap #10. Seven rods: six give the twelve blaze powder twelve eyes of ender take, one is spare. The row (`:blaze-rods`, priority 18, `--until blaze-rods`, `:goal/dimension` the Nether) is done by `:rods-held?`, `rods-wanted` 7 rods counted across every slot. It is an aside (`:goal/aside?`, as the pearls' rows are) until it passes from the fortress pad: `plan/current-until` stays the fortress, and no chained run takes it as a step yet.

## Key files
- `blaze.clj` - the row's rules and the `:camp` executor.
- `drops.clj` - `:collect-drops`, the pickup of a drop that may not come ([[collect-intent]]).
- `combat.clj` - `:fight`, `:back-off` and the `:defend` row ([[survival]]).
- `fortress.clj` - `:tunnel-to`, the enclosed way to a target ([[fortress-tunnel]]).
- `memory.clj` - a spawner seen in the Nether is remembered (`nether-landmark-kinds`, [[memory-sightings]]).

## The act, in order (`plan/next-intent :blaze-rods`)
1. Outside the Nether, a wait `:other-dimension` (the planner never chooses the row there anyway). Then, before everything below, a blaze at hand (`at-hand-blaze`: the nearest within `at-hand` 4, `combat/defend-radius`, that is `reachable?`) is fought to the end (`:intent/to-the-end?`: no break-off under 10), whatever the health or the number watching, and it ends any other running intent (`plan/interrupts?` `:blaze-rods`, a multimethod the planner asks each tick of the chosen row: true for anything but a `:fight` while a blaze is at hand, and for an intent that is not the answer (`under-fire?`: not a fight, meal, rest, back-off nor the cover walk) while the bot is hurt and watched and `healing` or `warily` has something to do: sim snapshot R4-31-1, issue #502, tunnelled 32 steps on from 16.8 health to none under blazes ten off). A blaze that loses sight of the bot flies to where it last saw it, and one within two blocks hits in melee (`mob_attack`, 3.12 through iron): gym blaze-fire-b run 3 (run 39136853644) tunnelled on with one hitting it from 20 to 13.8, then walked into four covers in eight seconds with blazes half a block to two off following it into each, and died without a swing.
2. Hurt (`:player/health` under `combat/retreat-health` 10): `healing` first hides (`hide`): when blazes within `cover-range` 16 have it in sight (`watchers`, `combat/seen?`), a walk into the nearest cell out of all their sight, stood in (`cover`: a route to path's `:unseen` goal, a `defmethod` here, a cell from whose eye height the line to the body of the blaze in `:goal/pos` and of each in `:goal/cells` is blocked; the walk's purpose `:cover` ends standing in the cell, `intent/standing-walks`, since a hiding place within reach round a corner hides only once stood in; blaze-fortress-f walked to a cell ten off on the plane that the blazes still saw, done at once in reach of it, twenty times a second); else eats (`meal`: `:eat`, the food held) when the food is under `hunger/heal-level` 18, where health comes back from (fast while the meal's saturation lasts); else backs away from the nearest watcher (`:back-off`), unless that line steps off a drop no landing survives (`combat/void-retreat?`, issue #514), in which case it rests on the lip; else, with none watching, a `:rest` (below). Cover before the meal where there is cover: a meal is a second and a half standing still, two hits from a burst, and gym blazefort-k run 3 (run 38913023560) ate at 9.4 by its spawner and was dead before the food came. The meal before the back-off where there is no cover: gym blaze-7a run 1 backed away three times round a room seven square with the food at 17, where no back-off gets away, and burned to death without eating. Gym blaze-fortress-e stood by its spawner under 10, waiting to heal while burning and shot, and every run died there. ruststeve broke off under 10 and ate ([[sib-blaze-combat]]): a run that fought on killed the blaze and died with it.
3. Wary (`wary?`: the health under `eat-under` 14): `warily`, the same cover when watched; else the `meal` when the food is under the healing level; else, with no blaze watching (in cover), a `:rest` until `rested` 18, so it does not walk back into sight at 10; watched with no cover and the food fine, nil, and it fights on. Gym blaze-7b run 2 fought on at 12.7 with the food at 17, and one blaze's volley took it to nothing in two seconds, under 10 only on the way down; blazefort-k run 3 fought on at 11.5 watched by the spawner's burst and was under 10 a second later.
4. Outnumbered (`outnumbered?`: `volley` 3 watchers or more), whatever the health: the same cover when there is any. A natural spawner's burst is four, and each hit is 3.12 through iron armour: blazefort-k runs 3 and 4 died from over 14 health in four seconds, a hit a second. In the open (the arena, the model's flat world) nothing hides, and the nearest is fought as before.
5. A rod lying near (`rod-lying`: a drop that may be a rod within `drops/drop-radius` 8 of the feet, its cell not blacklisted by a failed pickup, and `fetchable?`: its cell not solid, not hanging over a drop (`drops/over-void?`, issue #502: a rod over the edge of a bridge is left, the collect of it ended with the bot's momentum past the rim, in the lava sea), and when it lies under the floor's pickup reach (`drops/under-reach?`, issue #500) a route onto its cell): `:collect-drops` of `:blaze_rod` at its cell, or for a drop in a pit under the floor a `:walk` onto the cell (`fetch-walk`, `:intent/for :rod`, in `intent/standing-walks`). First, because it lies there now and the blazes stay.
6. The blaze to fight (`quarry`): of the `candidates` 3 nearest within `fight-radius` 12 (`blazes`: alive, not a blacklisted ghost), the first `reachable?` (gym blaze-fire-a runs 2 and 4, run 39131023637: three fights `:unreachable` in four seconds from the end of a tunnel, on blazes a walk's route came within 3 of while the fight's own approach route, within 2, found none; now `combat/approachable?`, the approach's own test): `combat/hittable?` (in sight, its feet no more than 3 over the bot's nor 2 under, the same rule the `:defend` row now keeps) and a route within a walk's range of its cell on the plane (`intent/routable?` judges the plane only). Gym blaze-fortress-a tunnelled to its spawner and failed three fights in a row `:unreachable` on blazes seen through the fortress's walls, every run. The engage walks on toward a blaze hovering out of reach (`combat/pressing?`). A blaze within approach-range on the plane whose aim the sword cannot reach from a cell the press can stand in, and no route walks there, is not approachable, and an engage already on it ends done rather than `:unreachable` (issue #564), so the tunnel down is not interrupted and the blaze is not blacklisted.
7. At the spawner (`at-spawner?`, below), a fire by it (`fires`: fire or soul fire within `fire-radius` 4 of it on the plane, from a block under its height to two over, none a dig failed in): `put-out`, a `:dig` of the nearest when its cell is within `intent/reach` of the eye (the server breaks fire on the first click), else a walk to it (`:intent/for :fire`) when a route reaches it. A fire lights its cells past 11 and a spawner's blaze spawns only at 11 or less: gym blaze-7a run 3 made none for three minutes once the floor by it burned, which is why the arena rows set mob griefing off ([[#gym-rows-gymclj]]). On a fortress's nether bricks a fire burns out by itself in seconds; on the arena's floor it stays.
8. The spawner on record nearest the eye within 256, not blacklisted: unless the bot is `at-spawner?` (the feet within `spawner-reach` 2 of it and `level-with?` it, within a block of its height, its blazes' floor; or the eye within `camp-range` 8 with the spawner `walkable?`, level with it and a route to it, and its top in sight, `top-in-sight?`: the line from the eye to a block over its centre clear, since a camp in the tunnel after a cover would wait on blazes hovering unseen in the room), `to-spawner` walks there when it is walkable, else tunnels by the fortress's steps, climbing first (`:intent/climb` 0), into its `stand` (the open cell beside it at its height nearest the feet, `:intent/reach` 0.5), or with none known to within 2 of it; the tunnel never opens a cell beside lava nor the spawner itself; at it, a `:camp`. blaze-fortress-c run 3 came up three under the spawner's floor (the tunnel climbed only past 3), killed eight blazes from there, and every rod lay on the floor over it. Gym blaze-fortress-b tunnelled to within 6 (the fortress tunnel's own reach), four over the spawner in the wall, and camped there out of reach of every blaze it made for ten minutes, every run.
8b. A stand whose tunnel ended `:blocked` is blacklisted (the failed intent's target), and `stand` skips blacklisted cells and tries the spawner's own height first, then one under and one over it (`stand-levels`, `cells-beside`): issue #502, four tunnels in a row to one stand. `stands-tried` 2 blacklisted cells beside a spawner and it is `given-up?`, `nearest-spawner` leaves it out, and the next remembered spawner is taken.
9. No spawner on record (or each `given-up?`): `search`, the next leg of the `:fortress` row's spiral (`fortress/exploring`, a `:tunnel-to` carrying `:intent/search`), issue #507: the captured Nethers of R2/47 and R4/2 hold spawners 117 and 134 blocks off, beyond what the server showed at the fortress's edge. Only when no heading has a way to go: a wait `:no-blaze` (the plan fails after `plan/wait-ticks`).

The `:defend` row (priority 0) still answers anything else hostile within 4 first, a wither skeleton beside the spawner; blazes it leaves to this row while the row is worked (`combat/answers` `:blaze-rods` is `#{:blaze}`, `combat/answered-kinds`). Gym blaze-fire-a run 1 (run 39131023637) opened its tunnel into the spawner's room as a burst came, six blazes within five blocks hitting in melee (`mob_attack`, 3.12 through iron), and the `:defend` row fought them from 20 health to 7 in four seconds and died backing away: nothing in this row's order, the cover from a volley included, was ever asked while a blaze was within 4. A fight this row starts carries `:intent/yield?`: under `combat/retreat-health` it ends, standing, and step 2 (cover, meal, back-off, rest) follows instead of combat's straight retreat.

## The :camp intent
`{:intent/kind :camp :intent/spawner cell}` stands still by the spawner: done when a blaze comes within `fight-radius` that was not there when it began (`:intent/blazes`: one there then could not be fought, or the act would have chosen it, and ending on it would start a camp a tick), a rod lies near, or the health falls under what it was when the camp began (`:intent/health`: something shoots the bot, and the act must look again; nothing ends a running intent but itself, `plan/plan-tick` plans only with none running, and blazefort-k run 3 camped on from 11.5 health to none in four seconds), failed `:no-blaze` after `camp-ms` 60 s. It is an intent and not a planner wait because a wait fails the plan after 20 s, shorter than a natural spawner's 10 to 40 s between spawns; and its spawner is not its `:intent/target`, so `plan/fail-intent` blacklists nothing for it: a camp that saw nothing in `camp-ms` blacklists its spawner itself as it fails, so the act goes to another spawner or on the search instead of camping the same silent one until the plan fails (issue #507, snapshots R1-35-2 and R1-36-1: two rests, then two camps at one spawner that made no blaze, and the plan failed `:no-blaze`).

## The :rest intent
`{:intent/kind :rest}` (`rest-intent`) stands still out of sight: done when the health is back to `rested` 18, or a blaze watches the bot that did not when it began (`:intent/blazes`, the watchers then), or the health falls under what it was then (`:intent/health`: shot from somewhere unseen), and the act looks again; failed `:no-heal` after `rest-ms` 90 s (no food in the bar, nothing comes back). An intent and not the planner wait `:healing` it replaced: the slow heal brings a point every four seconds, and a wait fails the plan after 20 s.

## The pickaxe on the way
Before a tunnel to the spawner (`to-spawner`) or on the search, the act makes the pickaxe the bot lacks from what it holds (`build/pickaxe-at-hand?`, `make/decide`: the `:fortress` row's rule, nothing gathered). The same R4-6-2 tunnelled by hand with no pickaxe, three sticks, 569 cobblestone and a table held: each step ended `:no-pickaxe`, the tunnel `done`, and the act asked again every 150 ms, 25 blocks in fifteen minutes.

## Gym rows (gym.clj)
Three rows play this one goal from three starts, each named by `:gym/name` (the registry finds a row by it first, and the trial matrix carries it, [[gym-on-runners]]):
- `blaze-1`: the nether arena, a blaze spawner in its corner (`blaze-spawner`, two at a time every two to four seconds) and one blaze summoned, and `no-fire` (`gamerule mob_griefing false`: a fireball sets no block alight; a fire lights the cells round it past 11, a spawner's blaze spawns only at light 11 or less, and in blaze-7a run 3 the spawner made no blaze for three minutes once the floor by it burned); the bot `invulnerable` (Resistance V and Fire Resistance: no command sets `Invulnerable` on a player) with an iron sword, food and six rods, so one more rod is the seventh. Judged by `rods-held` 7 (`clear <bot> minecraft:blaze_rod 0`).
- `blaze-7`: the same room, no rods given, the bot in iron armour (`iron-armour`, given with the kit after the dimension) and `fire-resistance` (as ruststeve's arena: no water puts a burning bot out in the Nether, and blaze-7a runs 1 and 2 burned to death inside a minute), hurt by the fireballs' hits: the break-off and the meal are played. Its spawner is `slow-blaze-spawner` (one blaze every three to six seconds, two at most near it): with ruststeve's two every two to four, blaze-7c runs 1 and 2 took five hits in two seconds from three blazes' volleys and died from 16 health before any break-off could act (3/5 passed). Fifteen minutes.
- `blaze-fortress`: the fortress row's pad 48 west of a located fortress, a pickaxe and cobblestone for the tunnel, the spawner found in the chunks loaded round it, iron armour and `fire-resistance` (blaze-fortress-f and -g burned to death by the spawner in four runs of six, its blazes' fire all round it). Thirty minutes.

## Against the model
`the-seventh-rod-from-the-models-blazes`: five of the model's blazes (`mob/kinds`: 20 health, they hover still and shoot 5 a hit on Normal every three seconds with a clear line) round a Nether landing at Easy with a full bar, six rods and an iron sword given; the plan is done with seven rods held and the bot alive. The model has no spawner: the rods come from the five, each dropping one in two by the model's seed. `watched-by-three-of-the-models-blazes-the-bot-hides` (sim_test): three blazes east of a Nether landing by the end of a wall three high; the bot walks round the wall into a cell none of them sees (`:intent/for :cover`), alive, with no watcher.

## Food for the Nether (issue #501)
Health comes back only from food 18 (`hunger/heal-level`), and the Nether has no game to hunt, so a bot that reaches the fortress hurt with nothing edible held fails the rest `:no-heal`. The overworld's `:hunt` row therefore also stocks `hunt/provision-wanted` 3 edible items (`hunt/provisioning?`: in the overworld, flint and steel and a water bucket held, the portal kit's goods) before the portal is lit; `meal` eats from them under 18. A bot already in the Nether without food cannot heal by resting: `heals?` (food at 18 or more, or edible food held) decides, and a `:rest` that cannot heal is held in cover (it fails `:no-heal` only when it could have healed), never ended by going back out to fight at that health: a death is no part of any plan.

## Limits
- Fireballs are not dodged; the damage-event log is where to look when they kill.
- A blaze hovering higher than the eye can reach from under it is never hit (`pressing?` stops within 0.8 on the plane); the fight has no engage timeout, only `:ghost` after 25 swings in reach.
- A blaze within approach-range below a wall the press would have to occupy, with no route down, is not fought (issue #564). One already engaged ends done.
- A back-off whose way away is a drop no landing survives stops on the lip (issue #514). A hurt bot rests there instead of jumping it.
- A rod on the spawner's top or in lava is left once its pickup fails (its cell blacklisted).
- The bot does not put itself out: no water works in the Nether, and every row gives Fire Resistance until potions exist. It does not dodge; it hides.
- Blazes that lose sight of a hidden bot fly to where they last saw it (BlazeAttackGoal: five ticks of moving toward the target once sight is lost, the move control carrying on) and forget the target after three seconds unseen; a rest out of sight is a rest, not an ambush.

## See also
- [[survival]] - the fight, the back-off and the defend row this goal leans on.
- [[sib-blaze-combat]] - what ruststeve learned killing blazes.
