---
title: How the siblings get out of water
description: steve's priority-0 escape_water step with a stable trap signal and a progress-aware trigger, and ruststeve's per-tick breath watchdog; the two designs for "the bot is drowning mid-step".
type: reference
tags: [siblings, steve, ruststeve, water, survival]
aliases: [escape_water, breath watchdog, breath_alarm, isInWaterTrap, isOnDryLand, preempt for water]
status: verified
lastUpdated: 2026-10-09
verifiedAgainst: steve c28028b, ruststeve bc575e3
sourceRefs:
  - steve:src/lib/steve/steps.ts#id: "escape_water",
  - steve:src/lib/steve/lib/bot-utils.ts#export const isOnDryLand = (bot: Bot): boolean => {
  - steve:src/lib/steve/lib/bot-utils.ts#export const isInWaterTrap = (bot: Bot): boolean => {
  - steve:src/lib/steve/lib/bot-utils.ts#export const needsWaterEscape = (bot: Bot): boolean => {
  - ruststeve:CHANGES.md#Breath watchdog in the SDK tick driver
  - ruststeve:src/bot_utils.rs#pub fn head_in_water(bot: &Bot) -> bool {
related:
  - "[[siblings/_moc|Siblings]]"
  - "[[sib-water-traps]]"
  - "[[goals-and-intents]]"
---

# How the siblings get out of water

Two answers to the same failure (a bot whose head goes under while some other step is running). steve makes escape a step that preempts by priority; ruststeve makes it a reflex in the tick driver that can end any step.

## Key files
- steve `src/lib/steve/steps.ts`, the `escape_water` row - `priority: 0`, `canExecute: (s) => s.inWaterTrap`, `isComplete: (s) => !s.inWaterTrap`; `execute` clears controls, stops digging, calls `escapeWater`.
- steve `src/lib/steve/lib/bot-utils.ts` - `isOnDryLand`, `isInWaterTrap`, `needsWaterEscape`, `escapeWaterInner`.
- ruststeve `CHANGES.md`, "Breath watchdog in the SDK tick driver" - the design and its root cause.
- ruststeve `src/bot_utils.rs`, `head_in_water` - eye-height submersion.

## What they learned
1. **Two signals, deliberately asymmetric.** `isOnDryLand` is strict: on ground, not `isInWater`, no water at feet+1, not standing in or on a lily pad, a non-water support block. `isInWaterTrap` is sticky: in water, or airborne with water within 2 below, or on ground with water or a lily at feet or feet-1, so "a momentary bob above the surface still reads as trapped" and the override does not flicker off.
2. **Do not escape while crossing.** `needsWaterEscape` demands an escape only when the head is under (typecraft has no buoyancy, so a submerged head sinks) or when trapped head-up with no horizontal progress for about 4 s; a plain trap signal dragged bots back to shore every time they waded toward trees on the far side of a lake.
3. **Confirm the exit.** The escape settles 300 ms and re-checks both signals before returning success; a single dry tick in a 1-wide pocket otherwise produced escape→mine→preempt→escape forever ([[sib-water-traps]] item 6).
4. **ruststeve: survive inside the step.** Every tick, if the eye block (y + 1.62) has been water for at least 1 s and the step has not set `allow_underwater`, the driver holds jump; still under after 6 s raises `breath_alarm`, which makes `follow_path`, `dig` and the long loops return so the main loop's `leave_water` runs. A 4 s synchronous A* search once froze the tick loop and with it the watchdog (CHANGES, "Breath-alarm latency").
5. **steve's portal exception.** `getNextStep` lets only `escape_water` or `enter_nether` run while a lit portal waits in the overworld, so a regressed kit step cannot steal the bot from its own portal.

## What it means here
Our planner already re-derives the goal every tick, but it never preempts an active intent ([[goals-and-intents]]). The water issue (docs/issues/06) needs both halves: a priority-zero goal whose done-ness is a sticky trap signal, and preemption so an intent in flight yields when the eye is under. Because `:time/now` is an event field, the 1 s / 6 s breath timers are absolute deadlines in the world, not wall-clock waits.

## Limits
The watchdog's per-tick code in ruststeve `src/bot/mod.rs` was not opened; item 4 rests on the CHANGES entry. steve's `escapeWaterInner` route-to-shore and notch-dig strategies are not summarised.

## See also
- [[sib-water-traps]] - the full failure list.
- [[goals-and-intents]] - where preemption would go.
