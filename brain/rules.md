---
title: Rules
type: moc
tags: [brain, rules]
status: verified
lastUpdated: 2026-10-09
---

# Rules

## The verification bar
A note is `status: verified` only if someone opened the source at the pinned version and read it. Every claim carries a sourceRef that names what it cites (a symbol, or the text itself), so a checker and a reader can refute it. Never a line number. Research delivered by an agent that read the sibling code is written as `draft` until a person or agent re-opens the anchors; the Limits section says what is unverified.

## The four invariants (hold at zero)
0 broken links, 0 ambiguous links, 0 orphans, 0 unresolved sourceRefs. Run `clojure -M:brain` after every batch of edits. A resolving ref proves the cited text exists, not that it supports the claim; that judgment belongs to review.

## Maintenance
- A change that touches a subsystem updates its note in the same change and bumps `lastUpdated` and the pin.
- Capture a gotcha the moment it costs something, anchored to where it bit.
- Write a decision note whenever an option a reasonable person would propose is rejected, with the measurement or constraint that rejected it and what would change the answer.
- Downgrade to draft when you cannot re-verify. Delete what became false; draft means unverified, not untrue.
- Re-run [[questions]] when an area changes materially.

## Privacy
Never store secrets, credentials or private data anywhere in the brain.
