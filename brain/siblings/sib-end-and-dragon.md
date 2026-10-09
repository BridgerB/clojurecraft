---
title: Stronghold, End and dragon in the siblings
description: How far steve and ruststeve got past the blazes: steve's stronghold finder is a stub, both bots chose beds over a bow for the dragon, and steve's sword killDragon is a placeholder. The one stretch of the chain with no sibling proof.
type: reference
tags: [siblings, steve, ruststeve, stronghold, end, dragon]
aliases: [find stronghold, eyes of ender, bed dragon, dragon_beds, end fight]
status: verified
lastUpdated: 2026-10-09
verifiedAgainst: steve c28028b, ruststeve bc575e3
sourceRefs:
  - steve:src/lib/steve/tasks/stronghold/find.ts#TODO: Track the eye entity, calculate direction, triangulate
  - steve:src/lib/steve/tasks/end/bed.ts#export const bedDragon = async (bot: Bot, budgetMs: number): Promise<StepResult> => {
  - steve:src/lib/steve/tasks/end/bed.ts#Endermen turn hostile when looked at.
  - steve:src/lib/steve/tasks/end/main.ts#export const killDragon = async (bot: Bot): Promise<StepResult> => {
  - ruststeve:src/tasks/end.rs#v1 failure log (CHANGES, cycle 4 Phase 3): beds were placed on the lowest reachable floor, so each
related:
  - "[[siblings/_moc|Siblings]]"
  - "[[sib-blaze-combat]]"
  - "[[sib-steve-steps-to-goals]]"
---

# Stronghold, End and dragon in the siblings

Past the blaze rods the siblings have little proven code. This note says plainly what exists, so nobody hunts for a working implementation.

## Key files
- steve `src/lib/steve/tasks/stronghold/find.ts`, `findStronghold` - a stub.
- steve `src/lib/steve/tasks/stronghold/activate.ts`, `activateEndPortal` - places eyes on nearby frames.
- steve `src/lib/steve/tasks/end/bed.ts`, `bedDragon` - the bed attempt.
- steve `src/lib/steve/tasks/end/main.ts`, `destroyCrystals`, `killDragon`.
- ruststeve `src/tasks/end.rs` - `dragon_beds` v2.

## What exists
1. **Stronghold: a stub.** `findStronghold` throws one eye and returns success after 3 s; its TODOs say "Track the eye entity, calculate direction, triangulate". Nobody has located a stronghold by eyes.
2. **Beds, not a bow.** Beds explode in the End. steve's `bedDragon` waits for the perch, stands behind a 1-high shield block at S+d with the bed foot at S+3d, and logs every detonation with the dragon's position and the bot's hp. ruststeve's v2 builds its own geometry at perch height (pillar east of the fountain, a two-block bridge, a bed on the bridge end), because v1's beds on the lowest floor cratered the end stone and each next bed went off lower, 10-14 blocks under the dragon's head.
3. **Endermen.** Looking at an enderman turns it hostile; steve keeps the gaze down (pitch -1.2) except to place a bed, after three attempts died to endermen before a single bed.
4. **Dimension and inventory.** steve resyncs the inventory on arrival (the client view resets on the dimension change: "out of beds after 0" with 6 given) and treats leaving the End or dying as the end of the attempt (a loop once read "no dragon in view" in the overworld as a win).
5. **steve's sword `killDragon`** treats "dragon not found" as possible victory; it is a placeholder, not a strategy.

## What it means here
Eyes of ender become an entity-tracking query (throw, record the eye's positions from `:world/entities`, intersect bearings), which no sibling has written. The win condition must be read from server state (dragon entity removed, an advancement, or the credits game event), never from absence in view.

## Limits
`activate.ts`, `destroyCrystals` and ruststeve's crystal and bow code (`end.rs` beyond its header) were not read. Whether either bot has killed a dragon outside a harness is not established here.

## See also
- [[sib-steve-steps-to-goals]] - where these steps sit in steve's chain.
