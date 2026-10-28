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
  - src/clojurecraft/blaze.clj#defn corridor-walk
  - src/clojurecraft/blaze.clj#defn fortress-search
  - src/clojurecraft/blaze.clj#defn in-open?
  - test/clojurecraft/blaze_test.clj#with-no-spawner-known-and-bricks-on-record-the-bot-walks-the-corridor
  - test/clojurecraft/blaze_test.clj#hurt-or-wary-bot-takes-cover-from-active-spawner-and-never-rests-in-the-open
  - test/clojurecraft/blaze_test.clj#rest-completes-when-healthy-and-food-cannot-heal-further
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
1. Outside the Nether, a wait `:other-dimension` (the planner never chooses the row there anyway).
2. Hurt (`:player/health` under `combat/retreat-health` 10): `@heal` evaluated before any fight. The bot breaks off and takes cover, never swinging under danger-health (#577). `healing` first hides (`hide`): when blazes or the active spawner within `cover-range` 16 have it in sight (`in-open?`), a walk into the nearest cell out of all their sight (`cover`: up to 800 nodes to find cover behind walls or down approach tunnels); else eats (`meal`: `:eat`, the food held) when the food is under `hunger/heal-level` 18; else backs away from whoever last struck (`combat/striker`, a damage-event) and from the nearest watcher (`:back-off`), unless that line steps off a drop no landing survives (`combat/void-retreat?`, issue #514), in which case it rests on the lip; else, with none watching and not in the open, a `:rest`. A hurt bot never rests in the open beside an active spawner, and never rests beside a fresh strike whose retreat has a landing.
3. Healthy blaze at hand (`at-hand-blaze`: the nearest within `at-hand` 4 that is `reachable?` when `(not (hurt? world))`): fought before wary, outnumbered, or meal, with `:intent/yield? true`. If health drops under 10 during the fight, it yields immediately to take cover. When healthy, ends running camps (`plan/interrupts?`); never interrupts cover or healing when hurt (#577).
4. Wary (`wary?`: health under `eat-under` 14): `warily`, the same cover when watched; else `meal`; else back away from a fresh strike; else `:rest` until `rested` 18 when nothing has struck and not in the open; else fights on. A rest at 13 health is how blaze-wall-b run 1 died to the piglins: wary is not yet hurt, so this step, not step 2, was the one that chose `:rest`.
5. Outnumbered (`outnumbered?`: `volley` 3 watchers or more): cover when there is any.
6. A rod lying near (`rod-lying`): `:collect-drops` or `fetch-walk`.
7. The blaze to fight (`quarry` when not hurt): `reachable?` blaze fought with `:intent/yield? true`.
8. At the spawner, fire put out (`put-out`).
9. Spawner on record: `to-spawner` when not at it; at it, `:camp` only when not hurt.
10. No spawner on record: `search` explores known fortress corridors first (`corridor-walk`: the nearest unvisited standable brick within `corridor-reach` 48, one walk, at most `corridor-nodes` 800, the cells read once). It does not ask `intent/routable?` for a handful of bricks on the way to choosing: that was a 6000-node search per brick every time the act was chosen, and probe 40624590128 hit the 60-minute probe limit still in its first replay. A walk that finds no route fails `:no-path` and blacklists that stand cell. Then a tunnel/bridge to the nearest unvisited brick still within `corridor-reach` (`fortress-search`), not out to `fortress/sight-radius`. Then `corridor-extension` along the fortress axes from the bounds when every known brick is visited. Never falls back to the Nether spiral search (`fortress/exploring`) once a fortress is reached or any brick is on record. `fortress/exploring` itself, the search used before any brick is seen, is unchanged.

The `:defend` row (priority 0) still answers anything else hostile within 4 first, a wither skeleton beside the spawner; blazes it leaves to this row while the row is worked (`combat/answers` `:blaze-rods` is `#{:blaze}`, `combat/answered-kinds`). Gym blaze-fire-a run 1 (run 39131023637) opened its tunnel into the spawner's room as a burst came, six blazes within five blocks hitting in melee (`mob_attack`, 3.12 through iron), and the `:defend` row fought them from 20 health to 7 in four seconds and died backing away: nothing in this row's order, the cover from a volley included, was ever asked while a blaze was within 4. A fight this row starts carries `:intent/yield?`: under `combat/retreat-health` it ends, standing, and step 2 (cover, meal, back-off, rest) follows instead of combat's straight retreat.

## The :camp intent
`{:intent/kind :camp :intent/spawner cell}` stands still by the spawner: done when a blaze comes within `fight-radius` that was not there when it began (`:intent/blazes`: one there then could not be fought, or the act would have chosen it, and ending on it would start a camp a tick), a rod lies near, or the health falls under what it was when the camp began (`:intent/health`: something shoots the bot, and the act must look again; nothing ends a running intent but itself, `plan/plan-tick` plans only with none running, and blazefort-k run 3 camped on from 11.5 health to none in four seconds), failed `:no-blaze` after `camp-ms` 60 s. It is an intent and not a planner wait because a wait fails the plan after 20 s, shorter than a natural spawner's 10 to 40 s between spawns; and its spawner is not its `:intent/target`, so `plan/fail-intent` blacklists nothing for it: a camp that saw nothing in `camp-ms` blacklists its spawner itself as it fails, so the act goes to another spawner or on the search instead of camping the same silent one until the plan fails (issue #507, snapshots R1-35-2 and R1-36-1: two rests, then two camps at one spawner that made no blaze, and the plan failed `:no-blaze`).

## The :rest intent
`{:intent/kind :rest}` (`rest-intent`) stands still out of sight: done when the health is back to `rested` 18, or healthy enough to fight when food cannot heal further (`eat-under` 14), or a blaze watches the bot that did not when it began (`:intent/blazes`, the watchers then), or the health falls under what it was then (`:intent/health`: shot from somewhere unseen), or someone who has struck is still there and the way away has a landing (`combat/striker`), or exposed in the open (`in-open?`) (done, and the act looks again); failed `:no-heal` after `rest-ms` 90 s when food could heal. An intent and not the planner wait `:healing` it replaced: the slow heal brings a point every four seconds, and a wait fails the plan after 20 s. Never rests in the open beside an active spawner, and never beside a zombified piglin that has just struck. The `:defend` row backs away from that piglin (`combat/pack-neutral?`), full health or hurt, and never swings: a swing at one angers the pack. An angry enderman is still fought to the end.

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
