---
title: Blaze rods
description: The :blaze-rods goal (issue #262) - seven rods in the Nether, from the blazes a fortress's spawner makes; the act's order (hurt, a rod lying near, the nearest blaze, the remembered spawner, a camp), the :camp intent, the gym rows blaze-1, blaze-7 and blaze-fortress, and the sim test against the model's blazes.
type: reference
tags: [bot, plan, combat, nether, blaze, goals]
aliases: [:blaze-rods, :rods-held?, :camp, :no-blaze, :healing, rods-wanted, blaze-1, blaze-7, blaze-fortress, spawner]
status: verified
lastUpdated: 2026-10-16
verifiedAgainst: HEAD
sourceRefs:
  - src/clojurecraft/blaze.clj#def rods-wanted 7
  - src/clojurecraft/blaze.clj#def fight-radius 12.0
  - src/clojurecraft/blaze.clj#def camp-ms 60000
  - src/clojurecraft/blaze.clj#defn blazes
  - src/clojurecraft/blaze.clj#defn reachable?
  - src/clojurecraft/blaze.clj#defn quarry
  - test/clojurecraft/blaze_test.clj#only-a-blaze-the-bot-can-reach-is-fought
  - src/clojurecraft/blaze.clj#defn rod-lying
  - src/clojurecraft/blaze.clj#defn nearest-spawner
  - src/clojurecraft/blaze.clj#defn healing
  - src/clojurecraft/blaze.clj#defn meal
  - src/clojurecraft/blaze.clj#defn wary?
  - src/clojurecraft/blaze.clj#defn to-spawner
  - src/clojurecraft/blaze.clj#defn walkable?
  - src/clojurecraft/blaze.clj#defn at-spawner?
  - test/clojurecraft/blaze_test.clj#a-spawner-under-the-feet-is-tunnelled-to-its-floor
  - src/clojurecraft/blaze.clj#defmethod plan/next-intent :blaze-rods
  - src/clojurecraft/blaze.clj#defmethod intent/run :camp
  - "resources/clojurecraft/goals.edn#{:goal/id :blaze-rods"
  - src/clojurecraft/gym.clj#def invulnerable
  - src/clojurecraft/gym.clj#def iron-armour
  - src/clojurecraft/gym.clj#def no-fire
  - src/clojurecraft/gym.clj#def fire-resistance
  - src/clojurecraft/gym.clj#defn rods-held
  - test/clojurecraft/blaze_test.clj#seven-rods-held-is-done
  - test/clojurecraft/blaze_test.clj#a-rod-lying-near-then-the-nearest-blaze-then-the-spawner
  - test/clojurecraft/blaze_test.clj#by-the-spawner-the-bot-camps
  - test/clojurecraft/blaze_test.clj#hurt-the-bot-backs-away-eats-or-waits
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

Issue #262, the first stage past the fortress and a child of the roadmap #10. Seven rods: six give the twelve blaze powder twelve eyes of ender take, one is spare. The row (`:blaze-rods`, priority 18, `--until blaze-rods`, `:goal/dimension` the Nether) is done by `:rods-held?`, `rods-wanted` 7 rods counted across every slot. With it the current stage (`plan/current-until`) is the blaze rods; the race's own `:race/until` in `ci/race.edn` stays the fortress until it is raised.

## Key files
- `blaze.clj` - the row's rules and the `:camp` executor.
- `drops.clj` - `:collect-drops`, the pickup of a drop that may not come ([[collect-intent]]).
- `combat.clj` - `:fight`, `:back-off` and the `:defend` row ([[survival]]).
- `fortress.clj` - `:tunnel-to`, the enclosed way to a target ([[fortress-tunnel]]).
- `memory.clj` - a spawner seen in the Nether is remembered (`nether-landmark-kinds`, [[memory-sightings]]).

## The act, in order (`plan/next-intent :blaze-rods`)
1. Outside the Nether, a wait `:other-dimension` (the planner never chooses the row there anyway).
2. Hurt (`:player/health` under `combat/retreat-health` 10): `healing` eats (`:eat`, the food held) when the food is under `hunger/heal-level` 18, where health comes back from (fast while the meal's saturation lasts), else backs away from a blaze within `combat/back-off-to` 8, else waits `:healing`. ruststeve broke off under 10 and ate ([[sib-blaze-combat]]): a run that fought on killed the blaze and died with it. The meal comes first: gym blaze-7a run 1 backed away three times round a room seven square with the food at 17, where no back-off gets away, and burned to death without eating.
3. Wary (`wary?`: the health under `eat-under` 14) with the food under the healing level: the `meal` first. Gym blaze-7b run 2 fought on at 12.7 with the food at 17, and one blaze's volley took it to nothing in two seconds, under 10 only on the way down.
4. A rod lying near (`rod-lying`: a drop that may be a rod within `drops/drop-radius` 8 of the feet, its cell not blacklisted by a failed pickup): `:collect-drops` of `:blaze_rod` at its cell. First, because it lies there now and the blazes stay.
5. The blaze to fight (`quarry`): of the `candidates` 3 nearest within `fight-radius` 12 (`blazes`: alive, not a blacklisted ghost), the first `reachable?`: its feet no more than `over-feet` 3 over the bot's nor `under-feet` 2 under, and a route within a walk's range of its cell on the plane (`intent/routable?` judges the plane only). Gym blaze-fortress-a tunnelled to its spawner and failed three fights in a row `:unreachable` on blazes seen through the fortress's walls, every run. The engage walks on toward a blaze hovering out of reach (`combat/pressing?`).
6. The spawner on record nearest the eye within 256, not blacklisted: unless the bot is `at-spawner?` (the feet within `spawner-reach` 2 of it, or the eye within `camp-range` 8 and the spawner `walkable?`: the feet within a block of its height, its blazes' floor, and a route to it), `to-spawner` walks there when it is walkable, else tunnels by the fortress's steps (`:tunnel-to` with `:intent/reach` 2, never opening a cell beside lava nor the spawner itself); at it, a `:camp`. Gym blaze-fortress-b tunnelled to within 6 (the fortress tunnel's own reach), four over the spawner in the wall, and camped there out of reach of every blaze it made for ten minutes, every run.
7. Nothing: a wait `:no-blaze` (the plan fails after `plan/wait-ticks`).

The `:defend` row (priority 0) still answers anything hostile within 4 first, a blaze too, and a wither skeleton beside the spawner.

## The :camp intent
`{:intent/kind :camp :intent/spawner cell}` stands still by the spawner: done when a blaze comes within `fight-radius` that was not there when it began (`:intent/blazes`: one there then could not be fought, or the act would have chosen it, and ending on it would start a camp a tick) or a rod lies near, failed `:no-blaze` after `camp-ms` 60 s. It is an intent and not a planner wait because a wait fails the plan after 20 s, shorter than a natural spawner's 10 to 40 s between spawns; and its spawner is not its `:intent/target`, so a camp that saw nothing never blacklists the spawner: three in a row fail the plan.

## Gym rows (gym.clj)
Three rows play this one goal from three starts, each named by `:gym/name` (the registry finds a row by it first, and the trial matrix carries it, [[gym-on-runners]]):
- `blaze-1`: the nether arena, a blaze spawner in its corner (`blaze-spawner`, two at a time every two to four seconds) and one blaze summoned, and `no-fire` (`gamerule mob_griefing false`: a fireball sets no block alight; a fire lights the cells round it past 11, a spawner's blaze spawns only at light 11 or less, and in blaze-7a run 3 the spawner made no blaze for three minutes once the floor by it burned); the bot `invulnerable` (Resistance V and Fire Resistance: no command sets `Invulnerable` on a player) with an iron sword, food and six rods, so one more rod is the seventh. Judged by `rods-held` 7 (`clear <bot> minecraft:blaze_rod 0`).
- `blaze-7`: the same room, no rods given, the bot in iron armour (`iron-armour`, given with the kit after the dimension) and `fire-resistance` (as ruststeve's arena: no water puts a burning bot out in the Nether, and blaze-7a runs 1 and 2 burned to death inside a minute), hurt by the fireballs' hits: the break-off and the meal are played. Fifteen minutes.
- `blaze-fortress`: the fortress row's pad 48 west of a located fortress, a pickaxe and cobblestone for the tunnel, the spawner found in the chunks loaded round it. Thirty minutes.

## Against the model
`the-seventh-rod-from-the-models-blazes`: five of the model's blazes (`mob/kinds`: 20 health, they hover still and shoot 5 a hit on Normal every three seconds with a clear line) round a Nether landing at Easy with a full bar, six rods and an iron sword given; the plan is done with seven rods held and the bot alive. The model has no spawner: the rods come from the five, each dropping one in two by the model's seed.

## Limits
- Fireballs are not dodged; the damage-event log is where to look when they kill.
- A blaze hovering higher than the eye can reach from under it is never hit (`pressing?` stops within 0.8 on the plane); the fight has no engage timeout, only `:ghost` after 25 swings in reach.
- A rod on the spawner's top or in lava is left once its pickup fails (its cell blacklisted).
- A fire by a real spawner stops its blazes (light over 11); the bot does not put fires out yet (a left click on the fire would).

## See also
- [[survival]] - the fight, the back-off and the defend row this goal leans on.
- [[sib-blaze-combat]] - what ruststeve learned killing blazes.
