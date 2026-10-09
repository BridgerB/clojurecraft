---
title: 775 attack packet
description: Protocol 775 split attacking into a dedicated attack packet carrying only the target entity id; the old interact packet is right-click only. Our id table already has it.
type: reference
tags: [siblings, ruststeve, combat, protocol]
aliases: [attack packet, interact right-click only, blaze fight packet]
status: draft
lastUpdated: 2026-10-09
verifiedAgainst: ruststeve bc575e3
sourceRefs:
  - resources/clojurecraft/packets.edn#:attack 1
related:
  - "[[siblings/_moc|Siblings]]"
  - "[[packet-specs]]"
---

# 775 attack packet

ruststeve's combat code comments that 775 (26.1.2) split attacking into `attack` (id 0x01, just the entity id) and that sending `interact` without a hand failed to decode and got the bot kicked. Research for issue #10 also recorded a 650 ms swing cadence as the pacing that killed blazes. `packets.edn` lists `:attack 1`; no spec exists yet.

## Limits
Draft: sibling anchor not re-opened here; the id is verified in our table.

## See also
- [[packet-specs]]
