---
title: Water escape
description: How the bot gets out of water and away from lava (issue #5) - the planner's trap with hysteresis, the two always-on priority-zero goals that preempt whatever runs, the :swim-out executor (one bank kept, forward and jump, dig what is in the way from the floor), :flee for lava, the :wade intent the gym uses to walk in on purpose, the trap facts routes keep away from, and the dig refusals beside water and lava.
type: reference
tags: [bot, plan, water, lava, survival, intents, goals]
aliases: [:escape-water, :escape-lava, :swim-out, :flee, :wade, :shore, trapped-tick, dry-land?, :plan/trap, :world/traps, :goal/always?, bank, roofed pool, capped well, exposes-fluid?, never-dug]
status: verified
lastUpdated: 2026-10-17
verifiedAgainst: HEAD
sourceRefs:
  - src/clojurecraft/trap.clj#defn dry-land?
  - src/clojurecraft/trap.clj#defn trapped-tick
  - src/clojurecraft/trap.clj#def trap-after-ticks 20
  - src/clojurecraft/trap.clj#def dry-ticks-to-clear 6
  - test/clojurecraft/swim_test.clj#a-flee-clears-its-trap-on-a-placed-floor
  - src/clojurecraft/plan.clj#defn choose
  - "resources/clojurecraft/goals.edn#{:goal/id :escape-water :goal/priority 0 :goal/target? true :goal/always? true"
  - src/clojurecraft/swim.clj#defn banks
  - src/clojurecraft/swim.clj#defn bank?
  - test/clojurecraft/swim_test.clj#a-water-plant-is-not-a-dry-bank
  - test/clojurecraft/swim_test.clj#a-kept-bank-containing-seagrass-is-dropped
  - test/clojurecraft/sim_test.clj#a-pond-with-seagrass-is-left-for-a-dry-bank
  - test/clojurecraft/sim_test.clj#a-rise-enters-the-water-above-an-opened-seagrass-shaft
  - test/clojurecraft/sim_test.clj#under-a-frozen-sea-the-bot-digs-out-through-the-ice-and-stands-on-it
  - src/clojurecraft/swim.clj#defn float-top
  - src/clojurecraft/swim.clj#defn obstacle
  - test/clojurecraft/swim_test.clj#a-wall-between-the-swim-and-a-level-bank-is-dug
  - test/clojurecraft/swim_test.clj#a-bank-overhang-is-cleared-without-digging-its-step
  - test/clojurecraft/swim_test.clj#a-diagonal-bank-approach-clears-the-side-wall
  - test/clojurecraft/swim_test.clj#a-bank-approach-clears-a-wall-touching-the-bodys-edge
  - test/clojurecraft/swim_test.clj#a-bank-left-below-a-rising-swim-is-dropped
  - src/clojurecraft/swim.clj#defn notch
  - test/clojurecraft/swim_test.clj#a-deep-swim-rises-into-reach-before-notching
  - src/clojurecraft/swim.clj#defn far-shore
  - src/clojurecraft/swim.clj#defn in-the-open?
  - test/clojurecraft/swim_test.clj#at-sea-with-no-bank-in-reach-the-escape-swims-for-the-shore
  - src/clojurecraft/swim.clj#defn dig-afloat
  - src/clojurecraft/swim.clj#def notch-radius 4
  - test/clojurecraft/sim_test.clj#a-flooded-cave-with-no-bank-is-left-by-a-notch-dug-in-its-wall
  - test/clojurecraft/sim_test.clj#a-retreat-from-a-scooped-ponds-edge-notches-the-bank
  - src/clojurecraft/swim.clj#defn dig-or-sink
  - src/clojurecraft/swim.clj#defn dig-from-floor?
  - src/clojurecraft/swim.clj#defn swim-controls
  - src/clojurecraft/swim.clj#defn flooded?
  - test/clojurecraft/swim_test.clj#a-bank-flooded-since-it-was-picked-is-dropped-for-another
  - src/clojurecraft/swim.clj#defmethod intent/run :swim-out
  - src/clojurecraft/swim.clj#defmethod intent/run :flee
  - src/clojurecraft/swim.clj#defn footing
  - src/clojurecraft/swim.clj#defn flee-controls
  - test/clojurecraft/swim_test.clj#a-flee-lands-on-its-footing-instead-of-bouncing
  - src/clojurecraft/swim.clj#defn lava-free?
  - src/clojurecraft/swim.clj#defn lava-beside?
  - test/clojurecraft/swim_test.clj#a-flee-never-ends-in-the-lava-it-flees
  - src/clojurecraft/swim.clj#defmethod intent/run :cross
  - test/clojurecraft/swim_test.clj#a-crossing-swims-at-the-surface-and-ends-on-the-far-side
  - test/clojurecraft/swim_test.clj#a-crossing-stuck-in-the-water-retreats-to-a-bank
  - test/clojurecraft/swim_test.clj#a-crossing-clears-a-wall-and-continues-after-the-dig
  - src/clojurecraft/swim.clj#defmethod intent/run :wade
  - src/clojurecraft/swim.clj#defn entry
  - src/clojurecraft/swim.clj#defn entries
  - test/clojurecraft/sim_test.clj#a-dune-over-a-shelving-beach-is-waded-from-and-escaped
  - src/clojurecraft/swim.clj#defmethod plan/done-by :not-trapped-in
  - src/clojurecraft/path.clj#defn trap-factor
  - src/clojurecraft/intent.clj#defn exposes-fluid?
  - src/clojurecraft/intent.clj#def never-dug
  - test/clojurecraft/sim_test.clj#dropped-in-a-pond-the-bot-swims-out-onto-the-bank
  - test/clojurecraft/sim_test.clj#a-roofed-pool-is-left-through-its-side-pocket
  - test/clojurecraft/sim_test.clj#a-capped-well-is-dug-out-through-its-cap-with-the-pickaxe
  - test/clojurecraft/sim_test.clj#the-shore-goal-wades-in-and-comes-out
  - test/clojurecraft/props_test.clj#water-over-the-eye-preempts-whatever-runs
related:
  - "[[bot/plan/_moc|Plan]]"
  - "[[fluid-physics]]"
  - "[[goals-and-intents]]"
  - "[[memory-sightings]]"
  - "[[death-and-respawn]]"
  - "[[sib-water-escape]]"
  - "[[sib-water-traps]]"
  - "[[sib-lava-safety]]"
---

# Water escape

Survival is two goal rows, a trap rule the planner applies every tick, and the executors, not a watchdog thread. The siblings' lessons are in [[sib-water-escape]] and [[sib-water-traps]]; this is what the bot does with them.

## The trap (trap.clj)
`trap/trapped-tick` runs at the top of every planning tick (`plan/plan-tick` calls it) and re-derives `:plan/trap` with hysteresis. Lava is a trap on any contact (`:player/in-lava?`). Water is a trap once `:plan/wet-ticks` (consecutive ticks with `:player/eye-in-water?`) reaches `trap-after-ticks` (20, ruststeve's one-second watchdog): wading a stream never preempts a walk. Either trap holds until `dry-land?` has been true for `dry-ticks-to-clear` (6) consecutive ticks (`:plan/dry-ticks`): a one-tick bob above the surface is not out (steve's 300 ms settle). `dry-land?` is steve's `isOnDryLand`: on the ground, no part of the body in water, the eye in air, the block under the feet a solid that is not a lily pad. When a trap clears, `{:trap/pos [x z] :trap/kind :trap/at}` is appended to `:world/traps` (the column it began in, `:plan/trap-at`), and `path/trap-factor` makes a route into any column within two of it ten times dearer for ten minutes, so a resumed goal does not walk straight back in ([[sib-water-traps]] item 10).

## The goals (goals.edn)
`:escape-water` and `:escape-lava` are targets at priority 0 with `:goal/always? true`: `plan/choose` puts them in play whatever `--until` asked for. Each is done by `:not-trapped-in` (the trap is not its kind) and planned by `:escape` into `:swim-out` or `:flee`, only while the trap is its kind. Preemption is the planner's existing rule ([[goals-and-intents]]): the running intent's `:intent/goal` is not the goal chosen now, so it is abandoned `:preempted`, controls cleared, `:plan/last` dropped (a chain must not continue from it), and the escape starts in the same tick. The property `water-over-the-eye-preempts-whatever-runs` holds it for any running walk or dig.

## :swim-out (swim.clj)
1. **One bank, kept.** `banks` lists cells the feet could stand in (`bank?`: `path/standable?`, or a clear cell over a lake's ice, `stairs/lake-ice`, which a route never stands on but the impulse lifts onto and dry land accepts; sim race 38806318231 world R2/43, issue #177, 83 runs: come up under a frozen sea, the bot dug the ice over its head and floated in the hole with the ice the only bank, and pressed toward a far shore under the ice until it timed out, three times, `under-a-frozen-sea-the-bot-digs-out-through-the-ice-and-stands-on-it`) within `bank-radius` 16 on the plane (12 found nothing from a mid-sea pool cell in gym batch shore-w1), between `bank-drop` 3 under the `float-top` and `bank-rise` 1 over it, nearest first; the first not blacklisted is `:intent/bank` and `:intent/target`, and it is never re-picked (re-picking every tick swam the siblings in circles). `float-top` is the highest feet level the body reaches in the column: `float-depth` 1.03 under an open surface, the body's height under a roof, rounded; a ledge higher than that is beyond the impulse's lift.
2. **No bank in reach:** dig the cap over the head if there is one; else carve a bank: `notch` names the nearest wall within `notch-radius` 4 on the plane and a dig's reach of the afloat eye, a solid at the float-top level with the two cells over it (the lower while it is solid, then the upper), and `start-dig` digs it, so the next search finds the notch standable (issue #73: an aquifer's rock walls rise straight from the water, and the escape rose until it timed out, 217 sim runs). With no wall in reach either, hold jump and rise, looking again every `search-every-ticks` (20); a roof or a bank comes into reach, or `escape-timeout-ticks` (2400, 120 s) fails it `:no-escape`. With the water open over the feet, the rise also swims toward `far-shore`: the nearest column whose top is dry ground along eight bearings from `bank-radius` out to `shore-reach` (64), else the point over the landing, which is land; a `:rise` (`shore-controls` with its `:intent/to-y`) goes straight up its shaft first. Under the open sky (water and air over the feet, `terrain/under-sky?`) a bank must be `in-the-open?` too, no water or rock over it: a pocket under the sea floor is no way out of the sea. Issue #89 (sim races 38806318231 runner 1 and 38811426215 runner 8): bots that dug up into the sea from their tunnels swam back into the tunnels' dry ends, or floated at the surface until `:no-escape`, land 33, 40 and 180 blocks off (`at-sea-with-no-bank-in-reach-the-escape-swims-for-the-shore`).
3. **Swim:** `swim-controls` holds forward and jump toward the bank's centre while in water, walks without jump once out, and does nothing on dry land, so the planner can see the land held (a bot that kept jumping on the bank never counted as out). A bank that is no longer standable (`flooded?`: water flowed into it after it was picked) is dropped and another looked for the next tick: race 38805758078 runner 1 bot Clj_r44 (issue #157) and race 38709226128 runner 8 bot Clj_r31 (issue #156), replayed in probe 38830884734, reached a bank a block off in a flooded tunnel at distance 0 with water in it, stayed trapped, dug over the head and failed `:stuck` (`a-bank-flooded-since-it-was-picked-is-dropped-for-another`).
4. **Stuck** (`stuck-ticks` 40 without `progress` 0.05 toward the bank): `obstacle` names what to dig: a lily pad over the head first (a lid, steve's rule), else the cell ahead at head height, else the cell ahead at feet height when the bank is no higher than the feet (a wall between them, not a step to climb), else the cell over the head; never the cell ahead at feet height under a higher bank, which is the step the impulse needs to collide with. Race 38805758078 runner 1 (issues #156, #157) bot Clj_r44, in a flooded tunnel at y 16, had its bank two blocks off at its own height with a block between at the feet: the swim dug the ceiling over its head every twenty seconds, then nothing was named and it failed `:stuck` (`a-wall-between-the-swim-and-a-level-bank-is-dug`). The dig is a child `:dig` with `:intent/exposes-ok?` run by `intent/run-child` (intents composing intents, as [[stairs-down]]).
5. **Afloat.** A child dig runs through `dig-afloat`: under an open surface (the cell over the water not solid) jump is held through the dig, so the eye stays in air and the dig is off the ground (five times slower, the same as the eye under water from the floor); a body left on the floor for a dig's length drowned (issue #73). Under a roof the floor is where the dig is from.
6. **From the floor.** `dig-or-sink`: off the ground a dig is five times slower and under water five times again, so a cap dug from the water takes 28 s with a wooden pickaxe and the breath lasts 25. When the cap is within reach of the eye standing on the floor (`dig-from-floor?`), the escape holds sneak, remembers the cell as `:intent/dig-when-down`, and starts the dig once on the ground: 5.75 s.

Notch reach is measured from the current eye to the solid cell that will actually be dug, not from the predicted float level. Issue #366 (R1/38 seed 1) started an escape at y 39 and chose a notch at y 55, nearly fifteen blocks from its eye, failing `:out-of-reach` three times. With no reachable notch the escape keeps rising; a child already failed `:out-of-reach` is dropped so the escape can search again instead of propagating that failure.

Issue #156 recurred in race 38999008545 bot Clj_r06: an escape kept a dry bank at y 49 while rising to y 62 directly above it, then failed at horizontal distance zero. Once progress stalls, a bank below the current float-top's `bank-drop` band is dropped too; while still making progress, the rise keeps its heading rather than turning sideways deep underwater. At the surface, packed ice above a step and along the other axis of a diagonal approach blocked the lift. `obstacle` checks both axes, their corner and columns touched by the body's bounding box, including the cell two above the feet ahead, without digging away a higher bank's step.

A `:swim-out` started by the mine act's retreat (`:intent/for :retreat`, out of a flooded tunnel the bot is wading in) ends itself on dry land (`trap/dry-land?`); one started by the gather's `into-water` (`:intent/for :rise`, up a shaft dug into the sea, [[gather-chain]]) on dry land in the open, cap and notch skipped; an escape's is ended by its goal when the trap clears ([[mine-act]]).

`bank?` also requires no fluid in the feet or head cell, using `terrain/fluid-kind` rather than a route's collision classification. Passable water plants (seagrass, tall seagrass, kelp) and bubble columns still contain water. Race 39007354638 runner 3 batch 2 bot Clj_r22 (issue #348) retreated to seagrass at [3204 62 3520], reached its centre and bobbed until `:stuck` at flint-and-steel. Rejecting that cell both keeps new escapes off submerged plants and lets `flooded?` discard a bank already kept in an older world value.

A rise also relies on aquatic sources flowing into the shaft when its roof is dug. Sim race 39007350790 runner 2 (issue #344, R3/4 seed 1) opened the sea bed under tall seagrass, but the model left that water suspended four blocks above the floor. Jump alone never reached it, so three rises timed out `:no-escape`. The correction is in the [[server-model]]: aquatic sources schedule and spread like water, without a fluid tick erasing the plant. The isolated rise test reaches dry land alive.

## :flee
`dry-land?` reads the support cell with integer block coordinates, so a placed floor in `:world/blocks` wins over the original chunk. Issue #339 (Sim race 39005324315, R2/16 seed 1) landed on cobblestone over lava but kept the lava trap until `:no-footing`: floating-point coordinates missed the overlay's integer keys and read the old lava instead (`a-flee-clears-its-trap-on-a-placed-floor`).

The lava half: `footing` is the nearest standable cell within `flee-radius` 6, at most one up or down, with no lava in it, over it or under it (`lava-free?`), those with no lava beside them first (`lava-beside?`): a cell flowing lava covers is standable to a route, and sim race 38844683780 runner 7 (issue #230, world R2/9) fled to one three times and timed out (`a-flee-never-ends-in-the-lava-it-flees`); forward toward it, jumping only in the lava or against a step and still once centred in it (`flee-controls`: jump held over dry ground bounced the body off its footing until the flee timed out, issues #230 and #231, `a-flee-lands-on-its-footing-instead-of-bouncing`); `flee-timeout-ticks` (200) fails it `:no-footing`. The planner ends it the moment the trap clears.

## :wade and :shore
The gym's water trial. `:shore` (priority 5, `--until shore`) is done by `:escaped-water?` (a water trap in `:world/traps`) and planned by `:wade`. `entry` finds the way in: over the remembered pools within `wade-radius` 64 and no more than `wade-below` 16 under the eye (the bucket's 5 is for scooping; a dune over the sea is a fine start), nearest first, the first chunk whose column holds a surface water source with a standable cell beside it gives `[bank water]` (`entries`, nearest the feet). A child `:walk` goes to the bank; then the wade heads `wade-beyond` 8 blocks past the water cell with jump off, sinking as it deepens, until the trap preempts it. With no entry, `{:plan/wait :no-water}`. RESULT carries `:escaped true` once a trap was escaped. Gym batch shore-w1 (set W, 0/10) taught all three rules: from a dune the sea was "more than 5 under the eye"; a pool fact (the chunk's centre-most source) sat mid-sea with no route within 3 of it; a one-deep shelf at the first water cell never put the eye under.

## Dig refusals
`:dig` fails `:unsafe` in `:settle` when any of the target's six neighbours is a fluid (`exposes-fluid?`, steve's `digExposesWater`) unless the intent says `:intent/exposes-ok?`, or when the target is in `never-dug` (obsidian: ruststeve dug its own portal frame). `intent/still?` accepts a body afloat as still, so an escape dig can start under water.

## What the tests hold
Four model fixtures from the issue, the siblings' deaths and the sim race: a two-deep pond with a bank three blocks away is left within 10 s with the air never under 200; ruststeve's roofed pool (water six deep under stone, the only air a pocket one block west and one down) is left through the pocket; a capped well is dug out through its cap with the pickaxe from the floor; a flooded cave with no bank (water three deep under three of air and a roof, walls straight from the water) is left by a notch dug in its wall, with no death. The shore goal wades in, is preempted, escapes, and ends done with `:escaped true`.

## Gotchas
- With `:go/goals []` nothing is in play but the escapes, and an untrapped bot's plan is done at once; the escape tests keep `:wood` in play (no tree, so it only waits).
- A deep well whose floor is out of reach of its cap is still a death by vanilla arithmetic; there is no faster answer than the floor.
- Lava deaths are modelled in the trap only: fire and the burning state are not in the server model yet.
- Bank search is a terrain scan (33 × 33 × 5 cells) once per pick and every 20 ticks while rising; memory is not consulted, because what is beside the body now is the terrain's question ([[memory-sightings]]).
- A notch is dug at the water's top level plus one, never at the top itself: a cell opened at the water's level fills from the pool (the model does not flow water; a server does), and a ledge one over the float level is what the impulse lifts onto, as a pond's bank is.
- **No notch under a roof on the water.** When the cell over the water's surface is solid (a capped well), `notch` is nil: the escape rises to the cap and digs it, as before #73. Cut from the floor under the cap, the notch opened the well's own wall and the bot drowned (`a-capped-well-is-dug-out-through-its-cap-with-the-pickaxe`, red on main from #113 until issue #116). A flooded cave's water has air over it and the roof higher up, so it is still notched.
- **A scooped pond's edge is a flooded cave to the escape.** The bucket filled from the edge, the bot stands in the one or two of water its source left, with the bank one over the water's top and so two over the float-top: outside the band, and no cap over the head. Sim race 38477665389 world R2/23 (issue #92, 48 runs at 1d31d21, before the notch): the mine for the portal's cobblestone retreated by a `:swim-out` that held jump until it timed out, three times, `:no-escape`. The notch takes the shore as it takes a cave wall (`a-retreat-from-a-scooped-ponds-edge-notches-the-bank`); the place acts get ashore the same way before they look for a spot ([[place-intent]]).

## A crossing (`:cross`)
The gather's deliberate swim (`wood/crossing`, issue #87, world R3/4: a sand island with the trees across a lake): straight at the remembered trunk, jumping on land only when blocked and holding jump in the water, so the body swims at the surface with the eye in air and the trap never calls the escape back to the near bank. Done on dry land once the body has been in the water (`:intent/wet?`) and come `cross-gain` (8) nearer the trunk than it started (`:intent/from-dist`), so wading the near shore is not the far side; stuck `cross-stuck-ticks` (100) without coming nearer while in the water (a far bank too high for the lift), a child `:swim-out` for `:retreat` takes it to the nearest bank and the crossing ends there; stuck on land it fails `:stuck`; `:no-crossing` after `cross-timeout-ticks` (2400). Sim race 38844589684 runner 6 (issue #57, world R1/30 seed 4) swam forty blocks and stuck eight short against the far bank; it failed `:stuck` three times between other intents before a climb and an escape got it out (probe 38853120299; `a-crossing-stuck-in-the-water-retreats-to-a-bank`).

A crossing blocked by solid terrain first clears `obstacle` with a child dig, using the same sinking and afloat digging rules as an escape. A finished dig clears the child and progress timer, not the crossing; a finished retreat still ends the crossing. Race 39060308623 bot Clj_r14 (issue #156) repeatedly pressed into packed ice beside its starting bank, sometimes bobbing out of the water when its stall was judged, and spent three attempts on `:stuck`. The crossing now clears the wall and continues instead.

## See also
- [[fluid-physics]] - the flags this reads.
- [[death-and-respawn]] - what happens when it fails.
- [[sib-lava-safety]] - the lava rules still to port (footing veto for every step).
