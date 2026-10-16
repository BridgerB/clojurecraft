---
title: Water escape
description: How the bot gets out of water and away from lava (issue #5) - the planner's trap with hysteresis, the two always-on priority-zero goals that preempt whatever runs, the :swim-out executor (one bank kept, forward and jump, dig what is in the way from the floor), :flee for lava, the :wade intent the gym uses to walk in on purpose, the trap facts routes keep away from, and the dig refusals beside water and lava.
type: reference
tags: [bot, plan, water, lava, survival, intents, goals]
aliases: [:escape-water, :escape-lava, :swim-out, :flee, :wade, :shore, trapped-tick, dry-land?, :plan/trap, :world/traps, :goal/always?, bank, roofed pool, capped well, exposes-fluid?, never-dug]
status: verified
lastUpdated: 2026-10-15
verifiedAgainst: HEAD
sourceRefs:
  - src/clojurecraft/trap.clj#defn dry-land?
  - src/clojurecraft/trap.clj#defn trapped-tick
  - src/clojurecraft/trap.clj#def trap-after-ticks 20
  - src/clojurecraft/trap.clj#def dry-ticks-to-clear 6
  - src/clojurecraft/plan.clj#defn choose
  - "resources/clojurecraft/goals.edn#{:goal/id :escape-water :goal/priority 0 :goal/target? true :goal/always? true"
  - src/clojurecraft/swim.clj#defn banks
  - src/clojurecraft/swim.clj#defn float-top
  - src/clojurecraft/swim.clj#defn obstacle
  - src/clojurecraft/swim.clj#defn notch
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
1. **One bank, kept.** `banks` lists cells the feet could stand in (`path/standable?`) within `bank-radius` 16 on the plane (12 found nothing from a mid-sea pool cell in gym batch shore-w1), between `bank-drop` 3 under the `float-top` and `bank-rise` 1 over it, nearest first; the first not blacklisted is `:intent/bank` and `:intent/target`, and it is never re-picked (re-picking every tick swam the siblings in circles). `float-top` is the highest feet level the body reaches in the column: `float-depth` 1.03 under an open surface, the body's height under a roof, rounded; a ledge higher than that is beyond the impulse's lift.
2. **No bank in reach:** dig the cap over the head if there is one; else carve a bank: `notch` names the nearest wall within `notch-radius` 4 on the plane and a dig's reach of the afloat eye, a solid at the float-top level with the two cells over it (the lower while it is solid, then the upper), and `start-dig` digs it, so the next search finds the notch standable (issue #73: an aquifer's rock walls rise straight from the water, and the escape rose until it timed out, 217 sim runs). With no wall in reach either, hold jump and rise, looking again every `search-every-ticks` (20); a roof or a bank comes into reach, or `escape-timeout-ticks` (2400, 120 s) fails it `:no-escape`.
3. **Swim:** `swim-controls` holds forward and jump toward the bank's centre while in water, walks without jump once out, and does nothing on dry land, so the planner can see the land held (a bot that kept jumping on the bank never counted as out). A bank that is no longer standable (`flooded?`: water flowed into it after it was picked) is dropped and another looked for the next tick: race 38805758078 runner 1 bot Clj_r44 (issue #157) and race 38709226128 runner 8 bot Clj_r31 (issue #156), replayed in probe 38830884734, reached a bank a block off in a flooded tunnel at distance 0 with water in it, stayed trapped, dug over the head and failed `:stuck` (`a-bank-flooded-since-it-was-picked-is-dropped-for-another`).
4. **Stuck** (`stuck-ticks` 40 without `progress` 0.05 toward the bank): `obstacle` names what to dig: a lily pad over the head first (a lid, steve's rule), else the cell ahead at head height, else the cell over the head; never the cell ahead at feet height, which is the bank the impulse needs to collide with. The dig is a child `:dig` with `:intent/exposes-ok?` run by `intent/run-child` (intents composing intents, as [[stairs-down]]).
5. **Afloat.** A child dig runs through `dig-afloat`: under an open surface (the cell over the water not solid) jump is held through the dig, so the eye stays in air and the dig is off the ground (five times slower, the same as the eye under water from the floor); a body left on the floor for a dig's length drowned (issue #73). Under a roof the floor is where the dig is from.
6. **From the floor.** `dig-or-sink`: off the ground a dig is five times slower and under water five times again, so a cap dug from the water takes 28 s with a wooden pickaxe and the breath lasts 25. When the cap is within reach of the eye standing on the floor (`dig-from-floor?`), the escape holds sneak, remembers the cell as `:intent/dig-when-down`, and starts the dig once on the ground: 5.75 s.

A `:swim-out` started by the mine act's retreat (`:intent/for :retreat`, out of a flooded tunnel the bot is wading in) ends itself on dry land (`trap/dry-land?`); an escape's is ended by its goal when the trap clears ([[mine-act]]).

## :flee
The lava half: `footing` is the nearest standable cell with no lava beside or under it within `flee-radius` 6, at most one up or down; forward and jump toward it; `flee-timeout-ticks` (200) fails it `:no-footing`. The planner ends it the moment the trap clears.

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

## See also
- [[fluid-physics]] - the flags this reads.
- [[death-and-respawn]] - what happens when it fails.
- [[sib-lava-safety]] - the lava rules still to port (footing veto for every step).
