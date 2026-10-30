---
title: Blaze rods
description: The :blaze-rods goal (issue #262) - seven rods in the Nether, from the blazes a fortress's spawner makes; the act's order (hurt, wary, outnumbered, a rod lying near, the nearest blaze, a fire by the spawner, the remembered spawner, a camp), the :camp and :rest intents, the gym rows blaze-1, blaze-7 and blaze-fortress, and the sim tests against the model's blazes.
type: reference
tags: [bot, plan, combat, nether, blaze, goals]
aliases: [:blaze-rods, :rods-held?, :camp, :rest, :no-blaze, :no-heal, :healing, rods-wanted, blaze-1, blaze-7, blaze-fortress, blaze-fortress-fireproof, spawner, cover, volley]
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
  - src/clojurecraft/pocket.clj#defn pocket?
  - src/clojurecraft/pocket.clj#defn dig-in
  - src/clojurecraft/pocket.clj#defn site-near
  - src/clojurecraft/blaze.clj#defn hold-intent
  - src/clojurecraft/dodge.clj#defn incoming
  - src/clojurecraft/pocket.clj#defn pickaxe-held?
  - src/clojurecraft/plan.clj#def target-scoped-failures
  - test/clojurecraft/blaze_test.clj#a-fight-that-cannot-reach-and-a-pickup-that-found-nothing-do-not-end-the-plan
  - test/clojurecraft/blaze_test.clj#sealed-in-with-no-pickaxe-is-never-chosen
  - src/clojurecraft/blaze.clj#defn near-rod
  - src/clojurecraft/blaze.clj#defn at-spawner?
  - test/clojurecraft/blaze_test.clj#a-rod-within-a-step-is-picked-up-before-the-next-swing
  - test/clojurecraft/blaze_test.clj#at-low-health-the-refuge-comes-before-a-dodge-and-no-dodge-cuts-it
  - test/clojurecraft/blaze_test.clj#a-walk-to-a-spawner-already-in-reach-is-not-chosen-every-tick
  - src/clojurecraft/dodge.clj#defn dodge-cell
  - src/clojurecraft/blaze.clj#defn dodge-intent
  - test/clojurecraft/dodge_test.clj#the-bot-steps-clear-of-the-line-before-it-arrives
  - test/clojurecraft/blaze_test.clj#a-fireball-in-flight-is-dodged-whatever-the-bot-was-doing
  - src/clojurecraft/blaze.clj#defn punch-fire-here
  - src/clojurecraft/blaze.clj#defn reignited?
  - src/clojurecraft/blaze.clj#defn refuge-now
  - test/clojurecraft/blaze_test.clj#the-walk-out-of-a-fire-ends-standing-in-its-cell-not-near-it
  - test/clojurecraft/blaze_test.clj#ignitions-are-counted-and-a-second-within-thirty-seconds-sends-the-bot-to-its-pocket
  - test/clojurecraft/blaze_test.clj#a-burning-bot-in-the-tunnel-is-cut-for-its-refuge
  - src/clojurecraft/blaze.clj#defn refuge-needed?
  - test/clojurecraft/pocket_test.clj#a-pocket-has-one-opening-and-a-roof
  - src/clojurecraft/pocket.clj#defn seal-cells
  - src/clojurecraft/pocket.clj#defn here-site
  - src/clojurecraft/blaze.clj#defn prepare-intent
  - src/clojurecraft/blaze.clj#defn pressed?
  - test/clojurecraft/blaze_test.clj#an-open-pocket-under-fire-is-sealed-and-a-sealed-one-is-held-then-opened
  - test/clojurecraft/blaze_test.clj#a-pocket-that-cannot-be-reached-is-sealed-where-the-bot-stands-not-failed
  - test/clojurecraft/blaze_test.clj#the-pocket-is-dug-ahead-of-the-tunnels-last-blocks
  - test/clojurecraft/blaze_test.clj#burning-or-outnumbered-the-bot-takes-the-pocket
  - test/clojurecraft/blaze_test.clj#in-the-pocket-it-holds-until-the-fire-is-out-and-the-health-is-up
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
- `blaze-fortress-fireproof` (named `blaze-fortress` until 2026-10-29; it measures the fight, not survival: Fire Resistance, so its passes are no evidence for the race bot): the fortress row's pad 48 west of a located fortress, a pickaxe and cobblestone for the tunnel, the spawner found in the chunks loaded round it, iron armour and `fire-resistance` (blaze-fortress-f and -g burned to death by the spawner in four runs of six, its blazes' fire all round it). Thirty minutes.

## Against the model
`the-seventh-rod-from-the-models-blazes`: five of the model's blazes (`mob/kinds`: 20 health, they hover still and shoot 5 a hit on Normal every three seconds with a clear line) round a Nether landing at Easy with a full bar, six rods and an iron sword given; the plan is done with seven rods held and the bot alive. The model has no spawner: the rods come from the five, each dropping one in two by the model's seed. `watched-by-three-of-the-models-blazes-the-bot-hides` (sim_test): three blazes east of a Nether landing by the end of a wall three high; the bot walks round the wall into a cell none of them sees (`:intent/for :cover`), alive, with no watcher.

## Food for the Nether (issue #501)
Health comes back only from food 18 (`hunger/heal-level`), and the Nether has no game to hunt, so a bot that reaches the fortress hurt with nothing edible held fails the rest `:no-heal`. The overworld's `:hunt` row therefore also stocks `hunt/provision-wanted` 3 edible items (`hunt/provisioning?`: in the overworld, flint and steel and a water bucket held, the portal kit's goods) before the portal is lit; `meal` eats from them under 18. A bot already in the Nether without food cannot heal by resting: `heals?` (food at 18 or more, or edible food held) decides, and a `:rest` that cannot heal is held in cover (it fails `:no-heal` only when it could have healed), never ended by going back out to fight at that health: a death is no part of any plan.

## Food before the stage, stalled tunnels, the per-tick ask (issue #262)
`stocking` is asked before the spawner is gone to: fewer than `food-reserve` (4) edible items held, no blaze watching and a source at hand (an edible drop in sight, a meal craftable from what is held, a hoglin `hunt/game` allows at or above retreat-health) is a pickup, a craft or a hoglin hunt. A hurt bot that cannot heal (`heals?` false) with nothing to take goes home: `food-trip` is a `:portal-home` intent (`home-portal`, the nearest remembered Nether portal block, stood in until the dimension is the overworld), where the hunt row stocks `hunt/provision-wanted` (16) and `:return-nether` comes back; never a terminal wait (probe 40740961123 ended `:no-food` at 60 min). Why: sim R1/17 reached the blazes at 9.3 health with food 17 and nothing to eat, where cover, rest and walk looped for hours. A done `:tunnel-to` carries `:intent/ended-at` (the feet cell) into its fact (`:intention/ended-at`); `stalled` is the set of targets of the newest done tunnels that ended on the cell the feet stand in, and `to-spawner` and the brick search do not choose them again (sim R1/1 tunnelled to one stand from one place until the window closed). `healing` and `warily` take `provide?`: `under-fire?`, asked every tick through `plan/interrupts?`, passes false, since looking for food scans 33 by 33 cells and routes.

A bot back in the overworld after a death with the Nether reached has `hunt/provision-phase` `:return`: the hunt row stocks `provision-wanted` before `:return-nether` goes back (sim R1/1 went back twelve times with nothing to eat). `crowded?` (outnumbered, health under `rested`) puts the cover before the swing at a blaze at hand, and a cover walk is not interrupted by it. The sim run prints `TRACE` lines (`simrace/trace-note`: portal-home, fight start and end, dimension, a sample every 30 s in the Nether).

A search of an earlier life does not end this life's (`:forage/life` is `:stats/deaths` at its start). In cover with a volley of blazes about (`swarm`, in sight or not) and none seeing the bot, `ambush` holds the cell for `ambush-ms` so the swarm comes to it one at a time (a blaze that loses sight flies to where it last saw its target), then lets the act go on; `ambush-cooldown` between. Sim R1/1 cycled cover and spawner room for 25 minutes, shot to 4 health each time it went out. The sim run also prints `:hit` TRACE lines (a drop of 2 health or more in the Nether, with the blazes about and watching).

## The gear the stage is entered with (issue #262)
The gym rows that get seven rods land with an iron sword, iron armour, Fire Resistance and cooked beef; the race bot arrived with a stone sword, nothing worn and no food. `gear.clj` adds the chain step `:gear` (priority 14, after the portal kit): `gear-cost` is the ingots the missing pieces of `pieces` cost (helmet 5, chestplate 8, leggings 7, sword 2); `gear-intent` mines and smelts that many through the needs planner (`make/decide` providing iron ingots), and the `:armour` and `:sword` rows craft each piece the moment its ingots are spare (`armour/spare-ingots`, now all ingots once the Nether was reached, since a death took the kit). The step is given up `gear-ms` (50 min) after the portal kit. `:regear` is the always row for the lives after a death, in the overworld only; `:cook` smelts the raw meat the hunt holds the most of. The gym row `blaze-fortress-race` lands with that kit and no Fire Resistance; `blaze-fortress` stays as the mechanics check.

`:gear` is undone until the gear is held, given up or the run is no chain (`plan/reach` would mark it reached at once if it were done before the portal kit: probe 40779151074); its `gear-ms` runs from `gear/started-at`, the later of the portal kit and the last death. A rod by the spawner is gone for only when no blaze has the bot in sight or the health is at `rested` (`collectable?`).

`:return-nether` waits (`fortress/ready-for-nether?`) until the health is at `return-health`, with no timer: the meal (`eat/returning?` eats up to food 18), the hunt and the regear come first, then a `:rest`; with no food to be had the wait is `:no-food-to-return`, its own reason.

The gear's iron: `stone/stairs` cuts nothing under a row's depth (iron 16), so below it a bot tunnels at its level or, when no tunnel can be cut, `stone/deeper` goes six lower; `deeper` now stops `deeper-slack` (24) under the row's depth (sim R1/1 reached y -37 for iron). `gear/without-stick-spares` drops the iron row's twenty-four stick spare from the table the gear is decided on, so the gear is not a climb to the trees (`make/spare-worth?`).

The Nether is not entered, and the way back not begun, without `hunt/entry-food-min` (4) edible items (`fortress/fed-for-nether?`): the hunt row works first, since `foraging?` is never out of a `:build`; the unfed bot rests until its search has run (`hunt/search-spent?`), then the wait is `:no-food-to-return`. A running `:smelt` for `:cook` keeps the row undone (`gear/cooking?`), or the planner preempts it with the food in the furnace. `stone/deeper`'s depth bound applies once the portal kit is reached (snapshot #62 R1-12-1 needs the iron kit's search deep).

The camp is in the tunnel the bot dug (`blaze/nook`, searched by walking from the feet, `nook-steps` 12: the nearest standable cell with `exposure` 2 or less, outside the spawner's volume (`in-spawn-volume?`: `spawn-range` 4 on x and z, `spawn-height` 1 on y, vanilla's BaseSpawner defaults as `mob/spawner` has them) and within its wake (`spawner-wake` 16)), not in the room: gym blaze-fortress-race run 4 had two blazes at 1.4 and 1.9 blocks and lost 18.3 health in five seconds. It is also the first cover (`nook-cover`) when it is out of every watcher's sight. Real blaze-race-b never used the trail-based nook: the camp clause was never reached (fights, cover and rest came first), and the trail is kept for one tunnel target and restarted by every re-tunnel. Cover that churns (`churning?`: `cover-churn` 3 walks done within `cover-churn-ms` 8 s) is not asked again for a spell: the bot walked between [2344 57 2279] and [2344 57 2294] every half second under fire. Gym rows: `blaze-fortress-race` (unarmoured race kit) and `blaze-fortress-geared` (what the gear step makes and the entry gate requires, no Fire Resistance). `:player/on-fire?` is bit 0x01 of the own entity's flags byte; burning, the bot leaves a fire block (`leave-fire`) and neither camps, rests nor ambushes.

A fresh fortress tunnel step onto a solid floor with nothing solid under it for `deep-drop` (3) cells (`fortress/thin-floor?`) is not taken in `next-move` unless a filler is carried and the cell under can be placed against (`underlay-possible?`); then `underlay-ops` fills it first. A retreat onto a cell already stood in, an air floor (the cave bridge) and an unloaded cell under are not asked. Blaze-race-c geared run 5: ghast 202's fireball 239 exploded by the bot, the next section-blocks-update set the floor under it to air, and it fell 46 blocks onto lava. The `:explode` packet and the registry are still undecoded.

## The fire at the feet, and the shield dropped (issue #262, blaze-race-f to k)
Damage by type (the damage_type registry is alphabetical: fireball 14, in_fire 21, mob_attack 28, on_fire 31) in the rounds f and g, both 10/10 deaths: on_fire 7 to 45 hits a run, in_fire up to 26, fireball only 3 to 10. The burning after a fireball and standing in fire killed more than the hits. Fire in the feet or head cell is left before anything (`burning-here?`, flag or not), by a walk that ends standing in its cell (`:fire-out` is one of `intent/standing-walks`: blaze-race-k race 1 walked to a cell two off, within reach of the eye, `:walk [2346 57 2286]` ×190 in 9.5 s in the fire, 30 in_fire hits), or, with no clear cell to walk to (sealed in), by a `:dig` of the fire (`punch-fire-here`). No route goes through a fire block (`path/classify-id`).

The shield was tried in blaze-race-i and j (`armour/part` gives `:offhand`, `block`, `:block`, `:lower`) and dropped after k: in the five geared deaths of k 0 of 16 fireball hits landed with the use flag set, `:block` ran 4 to 13 s a run, and the geared rows died faster than the race rows (median about 5 min against about 10). It cost an ingot and planks and a pair of intents the planner could cut and restart; it blocked nothing that mattered. `armour/part` still gives `:offhand` for a shield, and `:player/using-item?` (the living flags byte) is still read.

## Fight from a pocket (issue #262, after blaze-race-g and -i)
Twenty deaths read from the recordings (lead's hand-off `handoff-blaze-rods.md`): three stood frozen in a fire or a blaze's reach while one intent was cut and re-issued every tick (fixed in `plan/same-choice?`), nine fought or backed off in the open while burning or under a volley, one walked into fire, seven burned or were killed by another mob after an ignition. A fire block was at the feet in only three. So the bot fights from cover. `pocket.clj`: a pocket is a cell with exactly one opening (`openings`, a side shut only when both cells beside it at the feet and the head are solid), a firm roof (`roofed?`: not gravel or sand) and a standable floor (`pocket?`); `site-near` walks outward from the feet (12 steps, 400 cells) and takes the first cell that is a pocket, else a cell it can dig into a wall beside a standable cell (`dig-in`: the two cells to dig solid and not undiggable, their other three sides shut, the roof firm, the floor solid and not lava, so digging lets nothing in); `pocket-ok?` in blaze.clj keeps it outside the spawner's volume, within its wake and in sight of it, so the blazes that come see the bot through the opening only. The site is the fact `:pocket/site` ({:pocket/cell :pocket/stand :pocket/dig}), set by the first tick of the `:pocket` intent (the dig ops of `pocket/ops` worked through `build/work-op`, then the walk in) and dropped when it stops being valid. In the pocket (`pocket/in?`): a blaze within `combat/attack-reach` of the eye is the only one fought (`fightable-blaze`; the quarry further off is not walked out to); burning or under `engage-health` it eats (`meal`) or `:pocket-hold`s (`hold-intent`, until the fire is out and the health is back to `hold-to`); the shield is raised only here (`block`). Out of it, a burning bot, a volley (`outnumbered?`) or a hurt or wary one watched (`refuge-needed?`) goes to a site first (`pocket-go`), and the camp at the spawner is taken in a pocket in place of the nook. Fire is no way: `path/classify-id` gives it `:awkward`, so no route goes through a fire block.

## Sealing, the fallback and the pocket dug ahead (issue #262, after blaze-race-j)
Blaze-race-j: 8 of 10 died, two of them held open in the pocket (race 3: 18 s, the nearest blaze at 4.0 against a reach of 3.2, 5 fireball and 12 on_fire hits; race 1: 37 s, nearest 5.4, 5 and 13): a blaze hovers in front of the opening, in line and out of reach, and shoots. The gym row `blaze-fortress-fireproof` (Fire Resistance, four armour pieces; named `blaze-fortress` until then) passed because a fireball costs it nothing, so it measures the fight only. Now: an open pocket whose bot burns or is `pressed?` (a blaze in line, none in reach, struck within `press-ms`) and holds filler (`pocket/can-seal?`) gets a `:seal` (`pocket/seal-cells`: every open side at the feet and the head, over the head when it has no roof; the cells are noted in `:pocket/placed` of the held site, and the pocket is sealed while every one is solid, `pocket/sealed?`); sealed it eats or `:pocket-hold`s until the fire is out, the health back to `hold-to` and the last hit older than `press-ms`, then `:unseal` digs the placed cells, the highest first; a blaze at the block is no target (`fightable-blaze`) and only a blaze within reach of an open pocket is struck. A `:pocket` is never a failure: when the site stops being one or the work fails (blaze-race-j geared 4 at 17:14:57 and race 2 at 17:35:38 failed it three times in 0.4 s, then the plan failed `:no-path` at 17 hp) the intent is done and the cell the feet stand in is held as a `:pocket/here?` site, sealed in place on all its open sides by the `:seal` that follows. `:seal` and `:unseal` also end done, not failed. Within `pocket-approach` (24) of the spawner, with no valid site held, the tunnel is cut (`plan/interrupts?`) for a `:pocket` with `:intent/prepare?` (`prepare-intent`: dug in the tunnel's wall, not entered), so the pocket is ready before the last blocks open; a refuge asks no line to the spawner (`pocket-ok?` see? false), the camp does.

## Leave to heal (issue #262, after blaze-race-k)
Blaze-race-k, 10 of 10 dead at 4 to 25 minutes, on_fire 5 to 64 a run, the two that lived longest held 2 rods each: the bot kills blazes and fights until it dies. `refuge-needed?` is now also true under `low-health` (12) and after a second ignition within `reignite-ms` (30 s; `:player/ignitions`, the last three, kept by `game/ignite`), whoever is in sight. The refuge is the pocket or, when no site is found, the cell stood in sealed in place (`here-intent`); it is held (`hold-to`) until `full-health` (20) and the fire is out, then the seal is opened. `refuge-now` also cuts a running tunnel, walk or fight-less intent for it (`plan/interrupts?`), which the old under-fire test did not (geared 4 burned 35 s in a running `:tunnel-to`). A pocket dug ahead (`prepare-intent`) is never one toward the spawner: that was the tunnel's own next blocks, dug through again and again (3 s a step, 85 s lost). A hurt bot (under `low-health`) with a blaze in reach seals (the opening) instead of backing off: `:pocket` and `:back-off` alternated every 0.4 s for 8 s in geared 2.

## Dodging the fireballs (issue #262, after blaze-race-l)
The two runs of l and k that collected rods (l race 2, k race 1) killed 7 and 5 blazes at 23 and 30 hp lost per kill: 3.5 to 4 fireball hits a kill, each lighting the bot, half of all damage the burning after (`blazekills.ts`). Seven rods need about fourteen kills, over 300 hp. So the number to move is fireball hits per kill. `dodge.clj` reads the lines from what the world keeps of every entity (`:entity/pos`, `:entity/vel` of a `:small_fireball`). In l race 2's recording (`volleys.ts`) 212 fireballs spawned within 30 blocks and 24 hit; all 24 passed within 1.4 blocks of the body, none of the 76 that passed 1.5 or more away did; they take 300 to 750 ms to arrive; the velocity of the packet is about 5.5 times too small. `incoming` is the fireballs whose line passes the body within `danger-radius` (1.6); `dodge-cell` the standable cell within two steps, reached before the first fireball arrives (`move-base-ms` + `move-ms-per-block` per block against its ETA), where the nearest line passes the body farthest and at `worth-radius` (1.15) at least (a shot aimed dead at the body cannot be cleared by a step or two: the best diagonal passes 1.3 away, where one in ten of l race 2's still hit); `blaze/dodge-intent` is that as a `:dodge` intent, chosen after fire at the feet and before everything else, and cutting any running intent but a dodge (`plan/interrupts?`); none when sealed in the pocket. The executor walks straight at the cell and ends centred in it or after `dodge-ms`. A tunnel one wide has no cell: no dodge. The blaze's charge flag (metadata index 16) is set 1.4 to 3.5 s before its fireballs spawn and carries no direction, so it is not used yet. Not built: strike between volleys, and a spot where one blaze at a time has a line.

## Collecting the rods, and the order at low health (issue #262, after blaze-race-m)
The dodge works where the bot gets to fight: m race 4 lived 22 min, 45 blazes seen, 26 killed at 7 hp a kill (under the bar of 10), 28 `:dodge`. The wall moved to collection: about 11 rods dropped, at most 4 held. Eight lay 2 to 3 blocks from the camp `[2347 60 2280]` until they despawned at 300 s (`rods.ts`: min distance to the bot 2.1 to 2.9, removed without a pickup), with 39 `:collect-drops` begun and each cut inside a second: six to eight blazes hover in reach, `plan/interrupts?` cuts any intent but a fight for a blaze in reach, and `:fight` also comes before a rod in the act's order. `near-rod` is a rod within `grab-range` (3) on the plane, the health at `low-health` or more, blazes watching or not; `rod-intent` picks it up before the next swing (`@grab`, after the heal and before the fight), and a running `:collect-drops` is not cut for a blaze while one is near. At low health the order was `:fight`, `:dodge`, `:pocket`, `:seal`, a `:dodge` two cells out cutting the seal, then `:back-off` and a `:pillar` that failed `:boxed` (m race 4, 17:30:30 to 36): `dodge-intent` is now nil under `low-health` or alight (the refuge first), and a dodge never cuts the refuge's own intents or a meal (`no-dodge-kinds`). A spawner within `intent/reach` of the eye with it walkable counts as where the bot camps (`at-spawner?`): a walk to it ended in its first tick with `at-spawner?` still false and was chosen again every tick (snapshot test/issues/514/b10231346-R4-21-1); skipping that walk for a tunnel to the stand (blaze-race-n) was worse, `:tunnel-to` ×23 for 11 minutes in jobs 1 and 3. When a seal cannot be laid (no support, the body in the cell) the site is `:pocket/unsealable?` and not tried again, and a bot hurt under `low-health` with a blaze in reach it cannot seal out takes `combat/cover` (a block laid toward it, a pillar, a meal out of reach, a nook dug), never a fight under danger-health (#577): backing off flipped with the seal every 0.2 s in blaze-race-n jobs 2, 4 and 6.

## Sealed in with no pickaxe, and strikes that end the plan (issue #262, after blaze-race-p)
Blaze-race-p job 2 died in an `:unseal` of 44 s: `digging [2389 67 2324] ... 10000ms` four times, the placed cobblestone dug by hand with no pickaxe held, while fireballs came through the half-open pocket (the 22:30:24 to 22:31:08 window of its recording; 4 fireball hits and 19 on_fire in 28 s at one cell). A seal is opened by digging, so `pocket/can-seal?` now needs a pickaxe in the inventory (`pickaxe-held?`). Job 3 sealed, failed `:place :no-support`, walked 26 blocks to a new here-site and sealed again: the failure is also kept in the world (`:pocket/seal-failed-at`), and no seal is tried within `seal-retry-ms` (a minute). Pressed, with no seal to be had, the bot takes `combat/cover` from the nearest watcher, not a hold in the open. Job 6 ended at 6m24s with 16 hp, 15 beef and full iron: two `:collect-drops :not-picked-up` and a `:fight 537 :unreachable` were the three strikes (`plan/max-attempts`). A fight that cannot reach its mob and a pickup that found nothing (`plan/target-scoped-failures`, `[kind reason]` pairs, for any goal) blacklist their target and count for nothing: the blacklist bounds the retry.

## Two flips from blaze-race-p job 4 (issue #262)
The best run so far: 14 kills, 13 hp a kill, 22.5 min, rods held at most 3 (never near 7) and 5 of 7 rod entities seen despawned within 1.8 to 2.6 blocks of the bot (`rods.ts`). Its 533 `:pocket` intents were mostly one flip: at 22:41:40 `:collect-drops [2346 58 2285]` and `:pocket` alternated every 0.35 s for 13 s, 37 times, at 12 to 18 hp with blazes watching: `refuge-needed?` counted `wary?` (under engage-health), the pocket was reached, the hold had nothing to do, the pickup walked out, the refuge walked back. `refuge-needed?` now wants a watched `hurt?` bot (under retreat-health), not a wary one, and the hold gives way to a `near-rod` when the bot does not burn and is not pressed (a sealed one opens for it too). The second flip, 22:42:01 to 22:42:06: `:unseal` at 10.5 hp, `:seal` at once, four times 0.6 s apart: a rest that cannot heal held to `combat/retreat-health` (10) and sealed out a blaze in reach under `low-health` (12). `hold-to` is now `unseal-health` (13) when it cannot heal.

## Limits
- Fireballs are not dodged or blocked: the bot is out of their line, in a pocket or sealed in one.
- A blaze hovering higher than the eye can reach from under it is never hit (`pressing?` stops within 0.8 on the plane); the fight has no engage timeout, only `:ghost` after 25 swings in reach.
- A blaze within approach-range below a wall the press would have to occupy, with no route down, is not fought (issue #564). One already engaged ends done.
- A back-off whose way away is a drop no landing survives stops on the lip (issue #514). A hurt bot rests there instead of jumping it.
- A rod on the spawner's top or in lava is left once its pickup fails (its cell blacklisted).
- The bot does not put itself out: no water works in the Nether, and every row gives Fire Resistance until potions exist. It does not dodge; it hides.
- Blazes that lose sight of a hidden bot fly to where they last saw it (BlazeAttackGoal: five ticks of moving toward the target once sight is lost, the move control carrying on) and forget the target after three seconds unseen; a rest out of sight is a rest, not an ambush.

## See also
- [[survival]] - the fight, the back-off and the defend row this goal leans on.
- [[sib-blaze-combat]] - what ruststeve learned killing blazes.
