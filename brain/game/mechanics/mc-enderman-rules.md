---
title: Enderman rules
description: What the 26.1.2 jar says about endermen - the pearl loot table, where they spawn, health and attack, and the stare and teleport rules that decide how a bot may provoke and fight one.
type: reference
tags: [game, mobs, endermen, ender-pearls]
aliases: [enderman, ender pearl loot, warped forest, isBeingStaredBy, EndermanLookForPlayerGoal]
status: draft
lastUpdated: 2026-11-07
verifiedAgainst: 26.1.2
related:
  - "[[game/mechanics/_moc|Mechanics]]"
  - "[[mc-dimensions]]"
---

# Enderman rules

Read from the jar by probe in October 2026 (issue #263). The bot's choices built on them live in `pearls.clj` and its tests, not here.

- **Loot.** `loot_table/entities/enderman.json`: one roll of `ender_pearl`, `set_count` uniform 0 to 1, then `enchanted_count_increase` uniform 0 to 1 a level of looting. A pearl one kill in two: about twenty-four kills for twelve.
- **Where.** `worldgen/biome/warped_forest.json`: the only monster spawner is the enderman (weight 1, groups of four; spawn costs charge 1.0, budget 0.12). Crimson forest spawns zombified piglins, hoglins and piglins instead.
- **The enderman.** 40 health, 7 attack (Normal), speed 0.3, follow 64 (`EnderMan.createAttributes`). `isBeingStaredBy` is `isLookingAtMe` with 0.025 and its eye height. `EndermanLookForPlayerGoal`: an angry one stared at within 4 blocks teleports away; one farther than 16 teleports toward its target after 30 ticks. `hurtServer`: a projectile or a potion sends it away; a hit whose source is a living entity never does, so a sword never makes it teleport.
- **Decision.** Pearls are hunted, never bartered: a stack of gold for twelve pearls is a mine the run never makes.
- **The stare takes server ticks, not seconds.** An enderman turns only after the stare has held over several of its goal passes (a selector pass runs every other tick, then a wait of five ticks adjusted to the pass rate), and the stare must still hold at each pass. On a loaded server (ticks of 250 to 400 ms, the real races of 2026-11) the answer came 1.7 to 5.1 s after the stare began (median 3.1 s), where the model turns it on the first stared tick. A bot that gives up after a fixed 3 s, and looks away, resets the wait: `stare-budget` gives the stare `stare-ticks` of the server's own ticks (`:server/tick-ms`), at least `stare-ms`, at most `stare-max-ms`. Stares from past 36 blocks (`provoke-range`) angered none in the same races (the cone there is 1 - 0.025 / distance, about 2 degrees at 40 blocks; why is not established).
