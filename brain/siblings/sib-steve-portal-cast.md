---
title: steve's portal cast and entry
description: What steve's cast.ts and enter.ts learned on top of the mold: frame cell order (never y-sort), reliableUse and the stuck usingHeldItem flag, aim verification with ghost-cell clearing, safe lighting, and the 4 s stand inside the portal.
type: reference
tags: [siblings, steve, portal, buckets]
aliases: [cast.ts, reliableUse, usingHeldItem, never y-sort, enterPortal, light the portal]
status: verified
lastUpdated: 2026-10-09
verifiedAgainst: steve c28028b
sourceRefs:
  - steve:src/lib/steve/tasks/portal/cast.ts#Key 26.1.2 fix: bot.activateItem() leaves bot.usingHeldItem stuck `true`, which
  - steve:src/lib/steve/tasks/portal/cast.ts#const reliableUse = async (bot: Bot, look: Vec3, expect?: Vec3): Promise<boolean> => {
  - steve:src/lib/steve/tasks/portal/cast.ts#frame.push(at(1, 4), at(2, 4)); // top
  - steve:src/lib/steve/tasks/portal/cast.ts#const clearOfFrame = () => bot.entity.position.z - 0.3 > bz + 1.05;
  - steve:src/lib/steve/tasks/portal/enter.ts#A nether portal only teleports after ~4s STANDING inside it.
related:
  - "[[siblings/_moc|Siblings]]"
  - "[[sib-portal-mold]]"
  - "[[mc-bucket-use-item]]"
---

# steve's portal cast and entry

steve casts obsidian rather than mining it: a fully enclosed 1-block cup holds a lava source, water poured into the block above flows down and converts it, and the frame is cast bottom-up so each block sits on a solid one. ruststeve's mold ([[sib-portal-mold]]) is the version that cast reliably; these are the extra lessons in steve's `cast.ts` and `enter.ts`.

## Key files
- steve `src/lib/steve/tasks/portal/cast.ts` - the cast, `reliableUse`, `useTargetCell`, frame order, lighting.
- steve `src/lib/steve/tasks/portal/enter.ts`, `enterPortal` - walk in and hold.

## What they learned
1. **The stuck use flag.** In 26.1.2, `bot.activateItem()` leaves typecraft's `usingHeldItem` true, which silently blocks every later bucket use. `reliableUse` looks, waits 150 ms, activates, waits 750 ms, deactivates and clears the flag by hand. The bucket task does the same after a scoop ([[sib-bucket-water-find]]). This is a client-library bug, not a server rule.
2. **Verify the aim before pouring.** `useTargetCell` ray-casts from the eye (1.27 when sneaking, 1.62 otherwise, as the server does) and `reliableUse` refuses the use (`aim_fail`) when the cell is not the expected one; a lava pour that grazed the stand wall emptied into the bot's own feet and killed it.
3. **Ghost cells.** A ray that stops in the bot's own feet or head cell is a client ghost (a rejected placement the client kept; the server has air there); it is cleared locally and the aim retried.
4. **Never y-sort the frame.** The order is bottom row, left column bottom-up, right column bottom-up, top. Sorting by height interleaves the columns and makes the bot cross the whole frame on every block.
5. **Light from outside.** Fire lands in the interior; a bot whose hitbox was inside the fire cell burned to death with the frame complete. Light only with the hitbox clear of z = bz+1, then step back. The interior 2x3 must be pure air (leftover water or dirt blocks ignition).
6. **Entering takes about 4 s standing inside.** `enterPortal` disables digging (so pathing cannot break obsidian), re-centres on the portal column each pass, and polls up to about 12 s; success is reaching the nether or end, not merely any dimension change.

## What it means here
Our use packet is not typecraft's, so the stuck flag does not apply, but its lesson does: every bucket use is confirmed by an inventory or block change, never assumed. A cast is a build plan as data with a fixed op order; aim checks and ghost handling become pure functions of the world value.

## Limits
`cast.ts` is 2687 lines; only the header, `useTargetCell`/`reliableUse`, the frame order and lighting sections were read. Site selection and refills are in [[sib-portal-mold]] and the issue corpus.

## See also
- [[sib-portal-mold]] - ruststeve's template that cast 7 of 8.
- [[mc-bucket-use-item]] - the 775 use-item packet.
