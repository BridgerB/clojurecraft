---
title: Hunger in the server model
description: How sim.clj drains, heals, starves and feeds the player by vanilla's FoodData (hunger.clj) at the difficulty it is given, beside the bar #259 added, and why a model with no difficulty drains nothing.
type: reference
tags: [bot, tooling, sim, survival]
aliases: [hunger.clj, FoodData, :sim/difficulty, :sim/saturation, :sim/exhaustion, starvation, exhaustion]
status: verified
lastUpdated: 2026-10-16
verifiedAgainst: HEAD
sourceRefs:
  - src/clojurecraft/hunger.clj#defn spend
  - src/clojurecraft/hunger.clj#defn starves?
  - src/clojurecraft/hunger.clj#defn eat
  - src/clojurecraft/food.clj#def saturation
  - src/clojurecraft/sim.clj#defn food-value
  - src/clojurecraft/sim.clj#defn moved
  - src/clojurecraft/sim.clj#defn feed
  - src/clojurecraft/sim.clj#defn tick-eating
  - src/clojurecraft/sim.clj#defn sync-health
  - src/clojurecraft/sim.clj#defmulti use-held
  - src/clojurecraft/simrace.clj#def difficulty :normal
  - test/clojurecraft/hunger_test.clj#eighty-one-jumps-cost-a-food-point-once-saturation-is-gone
  - test/clojurecraft/hunger_test.clj#starving-on-normal-stops-at-one-health
  - test/clojurecraft/hunger_test.clj#bread-eaten-for-thirty-two-ticks-restores-five-and-six-saturation
  - test/clojurecraft/hunger_test.clj#a-model-without-a-difficulty-has-no-hunger
related:
  - "[[bot/tooling/_moc|Tooling]]"
  - "[[server-model]]"
  - "[[death-and-respawn]]"
  - "[[survival]]"
  - "[[sim-race]]"
---

# Hunger in the server model

Every real server runs at Normal since #257, so a real bot gets hungry. #259 gave the model a bar (`:food`, `:sim/food`) that only eating moves, for the bot's `:eat` row ([[survival]]); #260 drains it as the server does. `hunger.clj` is vanilla's `FoodData` and `FoodConstants` (26.1.2) as values and pure functions; what each food restores is `food.clj`'s (nutrition, and now saturation); `sim.clj` applies them.

## The rules (`hunger.clj`)
- A player's food is a value `{:food/level 0-20 :food/saturation :food/exhaustion :food/timer}`; a new life is `fresh` (20, saturation 5).
- `exhaust` adds what an act costs (`exhaustion`: a jump 0.05, a block broken 0.005, an attack 0.1, a hurt from a mob, an arrow or a blast 0.1; drowning and starving cost nothing), capped at 40.
- `spend`: past four exhaustion, four go and cost one saturation, or one food point once saturation is gone; on peaceful the bar never drops.
- `tick` then runs the clock in `:food/timer`: a full bar with saturation heals every 10 ticks by a sixth of the saturation it spends; a bar at 18 or more heals one every 80 ticks for 6 exhaustion; an empty bar starves one every 80 ticks while `starves?` (above 10 health always, above 1 on Normal, any on Hard: hunger never kills on Normal). It returns the heal and the hurt; the caller applies them.
- `eat` adds a food's nutrition (to 20) and saturation (`food/saturation`, to the new level).

## The model (`sim.clj`)
- `init` takes `:difficulty` (`:peaceful :easy :normal :hard`) into `:sim/difficulty`, with the bar (`:sim/food`, `:food` or full) and, beside it, `:sim/saturation`, `:sim/exhaustion` and `:sim/food-timer`; `food-value` and `with-food-value` move between them and hunger's value. Without a difficulty nothing drains the bar, exactly as before, so a snapshot kept before #260 is judged the same (`a-model-without-a-difficulty-has-no-hunger`). `simrace/run` gives every sim lane run Normal (`simrace/difficulty`).
- Exhaustion comes from the client's own packets, as the server reads them: `moved` counts a jump when the last movement packet said on the ground and this one says airborne going up (the server's `handleMovePlayer` calls `jumpFromGround` on exactly that), and a swim for every block moved with the eye under water; `break-block` costs a dig.
- `feed` runs `hunger/tick` every game tick for a living player and applies its heal or its starvation hurt (`hurt`, which also holds the 10-tick hurt window: within 500 ms of a hurt only a harder one lands, for its excess); on peaceful it also gives a heart a second and a food point every half second (`Player.aiStep`).
- `use-item` dispatches on the held item (`held-use`, `use-held`: `:food`, `:bucket`, `:default`). A food starts eating when `eats?` (the bar not full, or a food eaten anyway: `food/always-edible`); `tick-eating` finishes it after the food's eating time with the food still in its slot: one taken, `ate` (with hunger, `hunger/eat`, saturation too), `entity-event` status 9 (`use-finished`) to the player, window 0 synced, `set-health` told. Another held slot or a `player-action` release (status 5, `release-use-item`) ends it uneaten. A respawn refills the bar and stops any eating.
- `sync-health` sends `set-health` at the end of a tick when the health, the food level or whether saturation is zero changed since the last one told (`ServerPlayer.doTick`'s rule); every `set-health` now carries the real saturation.
- Foods eaten count in `:sim/eaten`; `simrace/run` puts `:run/eaten` and `:run/respawns` on each run, which is how the sim lanes show eating and deaths.

## Limits
Not modelled: sprinting (the bot never sprints), the hunger effect of raw chicken and rotten flesh, and the `natural_health_regeneration` rule (always on). Mobs hurt the player through the same `hurt` ([[sim-mobs]]).

## See also
- [[server-model]] - the model this extends.
- [[survival]] - the bot's `:eat` row that reads the bar.
- [[death-and-respawn]] - set-health, death and the respawn.
