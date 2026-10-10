---
title: Gotchas
type: moc
tags: [bot, gotchas]
status: verified
lastUpdated: 2026-10-09
verifiedAgainst: 5c7d6c1
related:
  - "[[bot/_moc|Bot pillar]]"
---

# Gotchas

- [[grass-type-is-grass]] - the ground is passable if you copy ruststeve's non-solid list.
- [[resting-vertical-velocity]] - a standing player's vy is never zero, so stillness is horizontal-only.
- [[packet-name-collision]] - a wire field called `name` collides with the packet's name.
- [[early-finish-aborts]] - FINISH before the server is done cancels the break silently.
- [[drop-under-the-trunk]] - digging the bottom log from above drops the item where the bot cannot stand.
- [[leaf-canopy-over-the-drop]] - collect times out three times because leaves at head height block the drop.
- [[phantom-cursor-stale-window]] - a no-op click is never answered because the model kept a cursor the server emptied.
- [[junk-crafts]] - a dirty grid crafts a pressure plate or a button.
- [[memory-edge-cases]] - logs broken while unloaded stay logs in memory.

- [[recipe-patterns-shrink]] - a recipe never matches, or a 1x2 recipe seems to need a table: vanilla trims pattern padding.

## See also
- [[bot/_moc|Bot pillar]]
