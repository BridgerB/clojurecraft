---
title: Placement judged by the server
description: Why :place never writes the placed block locally and counts as done only when the server's block-update shows it, failing :rejected when the ack arrives without it.
type: decision
tags: [bot, decision, placement]
aliases: [no ghost blocks, no local placement prediction, rejected placement]
status: verified
lastUpdated: 2026-10-09
verifiedAgainst: 5c7d6c1
sourceRefs:
  - src/clojurecraft/place.clj#Nothing is predicted; a rejected placement comes back as a block update with the old state
  - src/clojurecraft/place.clj#defmethod place-stage :sent
  - test/clojurecraft/place_test.clj#a-rejected-placement-fails-instead-of-believing
related:
  - "[[bot/decisions/_moc|Decisions]]"
  - "[[place-intent]]"
  - "[[sib-ghost-block-placement]]"
---

# Placement judged by the server

## The choice
`use-item-on` is sent and the world is left alone. The placed block exists for the bot only when the server's `block-update` writes it; an ack at or past our sequence with no such update, after 500 ms, fails the intent `:rejected`.

## What was rejected
Writing the block locally on send (what the dig intent does with air). ruststeve did that for placements and got ghost blocks its own physics collided with, plus a held stack that was never decremented; steve measured 15-30% of placements rejected by the server ([[sib-ghost-block-placement]]). A placement can be refused for reasons the client cannot see (an entity in the cell, a rule it does not model), so a predicted block is a guess that outlives the refusal.

## What would change the answer
Nothing for correctness. If placement latency ever matters (building a portal frame quickly), a predicted block could be written as a separate, provisional attribute beside `:world/blocks` and dropped on the ack, never mixed into it.

## See also
- [[spot-two-blocks-away]] - the other half of making placements succeed.
