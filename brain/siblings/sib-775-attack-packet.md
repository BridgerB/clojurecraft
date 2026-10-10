---
title: 775 attack packet
description: Protocol 775 split melee into a dedicated attack packet carrying only the target entity id; interact is right-click only, and an interact without a hand fails to decode and gets the bot kicked. Our id table already has attack at 1.
type: reference
tags: [siblings, ruststeve, combat, protocol]
aliases: [attack packet, interact right-click only, blaze fight packet, zero damage]
status: verified
lastUpdated: 2026-10-09
verifiedAgainst: ruststeve bc575e3
sourceRefs:
  - resources/clojurecraft/packets.edn#:attack 1
  - ruststeve:src/bot/mod.rs#pub async fn attack(&mut self, entity_id: i32) -> std::io::Result<()> {
  - ruststeve:src/bot/mod.rs#775 (26.1.2) split attacking into a dedicated `attack` packet (0x01) — just the target
  - ruststeve:src/bot/mod.rs#Sync our current position+look to the server BEFORE attacking.
related:
  - "[[siblings/_moc|Siblings]]"
  - "[[packet-specs]]"
  - "[[sib-blaze-combat]]"
---

# 775 attack packet

ruststeve spent a session dealing zero damage. In 26.1.2 attacking is its own serverbound packet, `attack` (id 0x01), whose only field is the target entity id. `interact` (0x1a) is now right-click only: an interact with the attack mouse flag decoded fine and dealt no damage, and one without a `hand` field failed to decode and the server kicked the bot.

## Key files
- ruststeve `src/bot/mod.rs`, `Bot::attack` - look at the entity (y + 0.7), send the current pose, swing, then write `attack` with `entityId`.
- `resources/clojurecraft/packets.edn` - our generated table lists `:attack 1` in play c2s.

## What they learned
- The attack is judged against the pose the server holds. `attack` sends position and look *before* the packet, because the normal position send fires on the next physics tick, after the attack, and the server rejected the hit against a stale pose.
- Damage also depends on pacing; see [[sib-blaze-combat]].

## What it means here
Add `[:play :c2s :attack] [[:entity-id :varint]]` to `packet/specs` ([[packet-specs]]); an attack intent emits `move-player-pos-rot` then `swing` then `attack` in one tick.

## Limits
The id is verified in our table; the field list is from ruststeve's write call, not from the vanilla report (packets.json gives ids, not fields).

## See also
- [[sib-blaze-combat]] - the fight that needed it.
