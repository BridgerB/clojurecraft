---
title: ruststeve's portal mold
description: The template mold that cast portals reliably: fixed layer order, the stance on the cell's own cup wall, lava aimed at the cup's north wall face, water at the bowl's north wall, scoop the tick the cup reads obsidian, and shift the origin until no lava is under the footprint.
type: reference
tags: [siblings, ruststeve, portal]
aliases: [portal_mold.rs, cast template, lava sea rule, footprint_lava, cast_frame_mold]
status: verified
lastUpdated: 2026-10-09
verifiedAgainst: ruststeve bc575e3
sourceRefs:
  - "ruststeve:src/tasks/portal_mold.rs#const LAYERS: [&[(i32, i32)]; 5]"
  - ruststeve:src/tasks/portal_mold.rs#fn footprint_lava(bot: &Bot, (bx, by, bz): (i32, i32, i32)) -> usize {
  - ruststeve:src/tasks/portal_mold.rs#Aim at the cup's NORTH WALL face (low), not the cup floor.
  - "ruststeve:src/tasks/portal_mold.rs#Aim at the bowl's NORTH WALL inner face, not the bowl floor: a filled bucket's raycast"
  - ruststeve:src/tasks/portal_mold.rs#rejects because the player is inside it left a locally-predicted GHOST block the bot then
related:
  - "[[siblings/_moc|Siblings]]"
  - "[[mc-bucket-use-item]]"
  - "[[sib-steve-portal-cast]]"
---

# ruststeve's portal mold

`src/tasks/portal_mold.rs` replaced ten independent per-cell constructions with one fixed template. Its header gives the geometry; the comments record why each choice was made.

## Key files
- ruststeve `src/tasks/portal_mold.rs`, `LAYERS` - the 10 frame cells as `(dx, dy)` in five layers bottom-up: `(1,0) (2,0)`, `(0,1) (3,1)`, `(0,2) (3,2)`, `(0,3) (3,3)`, `(1,4) (2,4)`.
- `footprint_lava` - counts lava over dx -2..6, dy -1..1, dz -2..3 (pad, frame plane, platform rows and a rim ring).
- `cast_frame_mold` - shifts the origin, then casts layer by layer.

## How the mold works
1. Frame cells are `(bx+dx, by+dy, bz)` in the X/Y plane; the bot works from +Z.
2. Per cell `(x, Y)`: floor `(x, Y-1, bz)`, cup walls N `(x, Y, bz-1)` and E/W `(x±1, Y, bz)`, the S wall is the platform block `(x, Y, bz+1)`; the bowl above has N/E/W walls at Y+1 and the bot's own body as its S wall. Walls are ensured (natural rock counts), never dug.
3. Every cell is poured from feet = cell.y+1 standing on that cell's own +Z cup wall, so the platform rises one block per layer; from one block higher the lava ray clips the stand and lands at the feet.
4. **Lava** aims at the cup's north wall face low (`y + 0.3`), not the floor: the floor aim from the sneaking eye (1.27) cleared the stand by about 0.2 and any drift put lava in the bot's feet (hp 20 to 6, bucket lost).
5. **Water** aims at the bowl's north wall inner face: a filled bucket's ray ignores fluids, so a floor aim went through the lava source and replaced it with water.
6. **Scoop at once.** Poll per tick and scoop the water back the moment the cup reads obsidian; water spreads a block per 5 ticks and a 60-tick wait let it run into the pool and convert 25 sources.
7. **Keep the footprint off the lava.** A block placed into a lava source destroys it; the pad ate the gym pool's sources. Before any obsidian exists, the origin is shifted up to 6 blocks in each direction until `footprint_lava` is 0, and the shifted anchor is persisted so resumes agree.
8. **No ghost blocks under yourself.** The platform is built in two passes from the previous row, never into the bot's own cell: a placement the server rejected because the player was inside it left a client-side ghost the bot collided with.

## What it means here
Build plans as data with an idempotent `:build` intent, a site query that requires no lava under the footprint, and pour ops that carry an aim point, not a face ([[mc-bucket-use-item]]). steve's extra lessons (cell order, aim verification, lighting) are in [[sib-steve-portal-cast]].

## Limits
The 7-of-8 pool result and the natural cast come from ruststeve's operator notes, not from this file. `portal_mold.rs` was read in its header, `LAYERS`, `footprint_lava`, the origin shift, the pour and scoop sections and `ensure_platform`; the refill and resume logic was not.

## See also
- [[sib-steve-portal-cast]] - steve's cast and entry.
- [[sib-lava-safety]] - where the bot may stand near lava.
