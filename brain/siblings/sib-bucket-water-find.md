---
title: Finding scoopable water
description: Why both siblings stalled at the fill-water step with a full iron kit, and the rules they converged on: source blocks only, surface over aquifer, flush stands, roofed lakes, a long downhill hunt, and a blacklist that must be reset.
type: reference
tags: [siblings, steve, ruststeve, bucket, water]
aliases: [fill water bucket, water-find wall, aquifer refusal, dry biome, fillWaterBucket, fill_water_buckets]
status: verified
lastUpdated: 2026-10-09
verifiedAgainst: steve c28028b, ruststeve bc575e3
sourceRefs:
  - steve:src/lib/steve/tasks/bucket/main.ts#export const fillWaterBucket = async (bot: Bot): Promise<StepResult> => {
  - steve:src/lib/steve/tasks/bucket/main.ts#Prefer SURFACE ponds, never deep cave/aquifer water.
  - steve:src/lib/steve/tasks/bucket/main.ts#4b. Long-range DIRECTIONAL hunt.
  - steve:src/lib/steve/tasks/bucket/main.ts#const failedWater = new WeakMap<Bot, Set<string>>();
  - ruststeve:src/tasks/bucket.rs#fn find_water(bot: &Bot) -> Option<(i32, i32, i32)> {
  - ruststeve:src/tasks/portal.rs#Classify stand options against SOURCE blocks only (flowing scoops nothing).
related:
  - "[[siblings/_moc|Siblings]]"
  - "[[sib-water-traps]]"
  - "[[mc-bucket-use-item]]"
---

# Finding scoopable water

steve's code calls the water link "the confirmed #1 bottleneck for smelted bots": bots with iron and an empty bucket wandered for 10-20 minutes, or dove into cave water and drowned. Both siblings ended with the same set of rules.

## Key files
- steve `src/lib/steve/tasks/bucket/main.ts`, `fillWaterBucket` - the tiered search, the long hunt, the scoop.
- ruststeve `src/tasks/bucket.rs`, `find_water` and `fill_water_buckets` - a port of steve's task.
- ruststeve `src/tasks/portal.rs`, `fill_bucket` - the generic lava/water scoop with stand classification.

## What they learned
1. **Source blocks only.** Flowing water is named `water` too and never fills a bucket; steve's `isSource` keeps blocks whose `level` property is missing or 0, ruststeve classifies stands against sources only.
2. **Surface, never aquifer.** steve's `pickWater` tier 1 is a source with real `air` above, within 24 below the bot and within 3 of the terrain surface of its own column; tier 2 allows `cave_air` above within 12 below and 8 of the surface; anything deeper is refused so the search escalates ([[sib-water-traps]] item 8).
3. **Get to the surface first.** A bot well below its column's surface (or below y 45) climbs out before searching; hunting from a cave found cave water every time.
4. **The long hunt.** After short random explores, steve walks eight bearings (sorted downhill first, since water sits at y 63 or below) in three 60-block legs each, because the pathfinder's search radius is 64 and a 140-block goal was "No path found" every time.
5. **Blacklist, then reset.** A source that fails 5 scoops goes into `failedWater`; when every reachable source is blacklisted in a water-sparse biome, the blacklist is cleared and the nearest source retried, or the bot deadlocks.
6. **Where to stand.** ruststeve prefers a flush stand (feet one above the source); a recessed stand (feet two above) misses; a source roofed by diggable rock is scooped by digging its roof. Building a stand or pillaring beside a lake was removed after deaths.
7. **The scoop flag.** steve clears `usingHeldItem` by hand after each scoop and resyncs the inventory, because the client model briefly showed the wrong bucket counts ([[sib-steve-portal-cast]]).

## What it means here
Water is a sighting kind like logs: `memory/watched?` gains water sources, and a water query is "nearest remembered source with air above, near the surface of its column", answered from memory, never by diving. The hunt is an intent with absolute deadlines, and a blacklist with an expiry rather than a reset.

## Limits
ruststeve's `fill_water_buckets` roam loop and memory-first return were read only through its comments. The race numbers in the comments are steve's operator records, not reproduced here.

## See also
- [[mc-bucket-use-item]] - how a bucket is used on the wire.
- [[sib-water-traps]] - why deep water kills.
