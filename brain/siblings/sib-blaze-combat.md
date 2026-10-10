---
title: ruststeve's blaze fight
description: How ruststeve killed blazes after zero-damage sessions: the 775 attack packet, 650 ms of real time between swings (a tick wait collapsed under the packet flood), defend first, break off below 10 hp, aim at y + 0.9, count only in-reach swings; and how a race goal is declared.
type: reference
tags: [siblings, ruststeve, combat, blaze, nether]
aliases: [kill_blaze, wait_real_ms, 650 ms swings, hurt invulnerability, RACE GOAL REACHED]
status: verified
lastUpdated: 2026-10-09
verifiedAgainst: ruststeve bc575e3
sourceRefs:
  - ruststeve:src/tasks/nether.rs#pub async fn kill_blaze(bot: &mut Bot<'_>, _mem: &mut WorldMemory, target_rods: i32) -> StepResult {
  - ruststeve:src/tasks/nether.rs#650ms of REAL time between swings: clears BOTH the mob's 10-tick (0.5s)
  - ruststeve:src/bot/mod.rs#pub async fn wait_real_ms(&mut self, ms: u64) -> std::io::Result<()> {
  - ruststeve:src/app.rs#println!("RACE GOAL REACHED: {goal}");
related:
  - "[[siblings/_moc|Siblings]]"
  - "[[sib-775-attack-packet]]"
  - "[[sib-dimension-change]]"
---

# ruststeve's blaze fight

Combat was the step where ruststeve's bots swung forty times and nothing died. Two bugs, then a set of fight rules.

## Key files
- ruststeve `src/tasks/nether.rs`, `kill_blaze` - the fight loop.
- ruststeve `src/bot/mod.rs`, `wait_real_ms` - a delay floored on the wall clock.
- ruststeve `src/app.rs` - `RACE_GOAL` checks (`nether`, `blaze` = at least one blaze rod, pickaxe tiers) and the `RACE GOAL REACHED` line.

## What they learned
1. **The packet.** Melee is the 775 `attack` packet ([[sib-775-attack-packet]]).
2. **Pacing in real time.** `wait_ticks(14)` returned in under 2 ms during combat because the packet backlog satisfied the ticks instantly; 40 swings landed in 66 ms, all but one inside the mob's 10-tick (0.5 s) hurt invulnerability. Swings are now 650 ms of real time apart, which also clears the iron sword's charge cooldown so each hit is full damage.
3. **Defend first.** Any other hostile within 4 blocks is fought before the blaze (3 of 10 gym deaths were wither skeletons the hunt ignored).
4. **Break off when hurt.** Below 12 hp between engagements the bot backs off and eats; below 10 hp mid-fight it breaks off (a run killed the blaze and died with it).
5. **Aim at the body.** Blazes hover; the look target is `y + 0.9`.
6. **Count only in-reach swings.** Out of reach (over 4.2) the bot closes in; four losses end the engagement; a blaze that survives many in-reach swings is blacklisted as a ghost entity.
7. **A dropped connection is a failure, not a retry.** A broken pipe during combat otherwise spun the task on errors forever.

## What it means here
Our loop has no tick-collapse bug (every deadline is `:time/now` from the tick event), so the 650 ms cadence is an absolute `:intent/next-swing` like the dig's swing schedule. The fight is an intent over `:world/entities`, which today keeps only item entities.

## Limits
Rod pickup, spawner pathing and fortress approach are not covered (see the issue corpus, docs/issues/10). The goal-reached check was read in `app.rs`; how races record it was not.

## See also
- [[sib-dimension-change]] - getting into the Nether first.
- [[sib-end-and-dragon]] - what comes after.
