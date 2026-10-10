---
title: Stairs down
description: How :stairs-down cuts a staircase toward a height one stair at a time, which cells each stair opens and exposes, the refusal rules (water, lava, unloaded, a drop), the quarter turns and :boxed, the child :dig intents, and why a stair counts only when the feet went down.
type: reference
tags: [bot, plan, dig, mining, safety]
aliases: [:stairs-down, descend, child intent, :boxed, stair-cells, safe?, intents composing intents]
status: verified
lastUpdated: 2026-10-10
verifiedAgainst: 56441f5
sourceRefs:
  - src/clojurecraft/stairs.clj#defn stair-cells
  - src/clojurecraft/stairs.clj#defn exposed
  - src/clojurecraft/stairs.clj#defn safe?
  - src/clojurecraft/stairs.clj#defn run-child
  - src/clojurecraft/stairs.clj#defn feet
  - src/clojurecraft/stairs.clj#def max-turns
  - src/clojurecraft/stairs.clj#defmethod intent/run :stairs-down
  - test/clojurecraft/sim_test.clj#a-stone-hill-is-descended-five-stairs-with-a-pickaxe
  - test/clojurecraft/sim_test.clj#boxed-in-by-lava-on-every-side-fails-boxed
related:
  - "[[bot/plan/_moc|Plan]]"
  - "[[dig-timeline]]"
  - "[[sib-dig-down-safety]]"
  - "[[goals-and-intents]]"
---

# Stairs down

`{:intent/kind :stairs-down :intent/to-y n}` (optionally `:intent/dir [dx dz]`) cuts a staircase in its heading until the feet are at or below `to-y`. It never digs the block under the feet: a blind drop is how the siblings' bots died ([[sib-dig-down-safety]]).

## Key files
- `stairs.clj`, `stair-cells` - the three cells a stair ahead opens: head-up, head, and the new feet one block down.
- `stairs.clj`, `exposed` - what the stair exposes to the body: those three, the floor under the new feet, and every horizontal neighbour of the three.
- `stairs.clj`, `safe?` - none of the opened cells water, lava or unloaded; no lava in any exposed cell; a floor under the new feet.
- `stairs.clj`, `feet` - y a touch below the position, since physics jitter dips it under the integer.
- `stairs.clj`, `run-child` - the child `:dig` stands in as `:plan/intent` for one tick of the dig executor, then returns to `:intent/child`: intents composing intents, with one rule for breaking blocks ([[dig-timeline]]).
- `stairs.clj`, `max-turns` 4, `step-timeout-ticks` 200.

## How it works
1. `:plan`: with the current heading (`+x` first), compute the stair; `safe?` → `:open`; else turn a quarter and count; four refusals → fail `:boxed`.
2. `:open`: while any of the three cells is solid, run a child dig of the topmost; a failed child fails the stair with its reason.
3. `:step`: walk toward the new feet cell; done with the stair when the feet are half a block below where it began, which is the only proof the server broke what the bot asked for; 10 s without that fails `:stair-not-taken`.
4. Done when the feet are at or below `to-y`.

## Gotchas
- Keys that are over (`:intent/child`, `:intent/cells`) are removed, never nil: the world spec refuses a nil vector.
- Water beside a stair is allowed, lava is not: the siblings found refusing water wedged descents in wet biomes.
- No gravel or sand rule yet: a falling block above an opened head cell is an open question in issue #9.

## See also
- [[dig-timeline]] - the child.
- [[sib-dig-down-safety]] - the rules' origin.
