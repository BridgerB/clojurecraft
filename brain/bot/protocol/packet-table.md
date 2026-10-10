---
title: Packet table
description: Every packet in packet/specs in one table - phase, direction, fields, and which namespace handles (s2c) or emits (c2s) it - so you can see coverage at a glance.
type: reference
tags: [bot, protocol, packets, index]
aliases: [packet index, which packets are modelled, specs table, who sends this packet]
status: verified
lastUpdated: 2026-10-09
verifiedAgainst: 5c7d6c1
sourceRefs:
  - src/clojurecraft/packet.clj#def specs
  - src/clojurecraft/game.clj#defmulti on-packet
  - src/clojurecraft/window.clj#defn click
  - src/clojurecraft/place.clj#defn use-item-on
  - src/clojurecraft/intent.clj#defmethod run :dig
related:
  - "[[bot/protocol/_moc|Protocol]]"
  - "[[packet-specs]]"
  - "[[add-a-packet]]"
---

# Packet table

Every row of `packet/specs` at 18afddc (58 specs; paired s2c/c2s rows share a line). Ids are not listed: they come from `packets.edn` ([[packet-specs]]). "Handled by" for s2c is the `on-packet` method's namespace (`game` unless noted; "-" means decoded and ignored); for c2s it is who emits it.

## Handshake and login
| Packet | Dir | Fields | Handled by |
|---|---|---|---|
| intention | c2s | protocol-version varint, host string, port u16, next-state varint | game `:start` |
| hello | c2s | username string, uuid | game `:start` |
| login-compression | s2c | threshold varint | conn (sets threshold); game - |
| login-finished | s2c | uuid, username | game → login-acknowledged |
| login-disconnect | s2c | reason string | game `:bot/disconnected` |
| login-acknowledged | c2s | - | game |

## Configuration
| Packet | Dir | Fields | Handled by |
|---|---|---|---|
| select-known-packs | s2c / c2s | - / packs [namespace id version] | game replies with `[]` |
| keep-alive | s2c / c2s | id i64 | game echoes |
| ping / pong | s2c / c2s | id i32 | game echoes |
| finish-configuration | s2c / c2s | - | game echoes (phase → play) |
| disconnect | s2c | reason rest | game `:bot/disconnected` |

## Play, server to client
| Packet | Fields | Handled by |
|---|---|---|
| login | entity-id i32 (rest ignored) | game → client-information |
| keep-alive, ping | id | game echoes |
| player-position | teleport-id, x y z f64, dx dy dz f64, yaw pitch f32, flags u32 | game `apply-teleport` |
| chunk-batch-finished | batch-size | game → chunk-batch-received 20.0 |
| level-chunk-with-light | x z i32, heightmaps, data bytes (light ignored) | game `load-chunk` |
| forget-level-chunk | pos i64 | game |
| block-update | pos position, state varint | game `set-block` |
| section-blocks-update | section i64, blocks [varlong] | game `section-update` |
| set-health | health f32, food varint, saturation f32 | game `:player/health` |
| container-set-content | window-id, state-id, items [slot], carried slot | game (window 0 or the open window) |
| container-set-slot | window-id, state-id, slot i16, item slot | game (window 0 or the open window) |
| set-player-inventory | slot varint, item slot | game |
| set-cursor-item | item slot | game |
| open-screen | window-id, menu-type, title rest | game `:window/open` |
| container-close | window-id | game |
| set-held-slot | slot | game `:player/held-slot` (0-8 only) |
| add-entity | entity-id, uuid, type, x y z (rest ignored) | game (items only) |
| remove-entities | ids [varint] | game |
| move-entity-pos | entity-id, dx dy dz i16, on-ground | game |
| move-entity-pos-rot | entity-id, dx dy dz i16 (rest ignored) | game |
| entity-position-sync | entity-id, x y z (rest ignored) | game |
| take-item-entity | collected, collector, count | game `:stats/pickups` |
| block-changed-ack | sequence | game `:stats/last-ack` |
| start-configuration | - | game → configuration-acknowledged |
| disconnect | reason rest | game |

## Play, client to server
| Packet | Fields | Emitted by |
|---|---|---|
| keep-alive, pong | id | game |
| accept-teleportation | teleport-id | game |
| chunk-batch-received | chunks-per-tick f32 | game |
| configuration-acknowledged | - | game |
| client-information | locale, view-distance, chat-mode, chat-colors, skin-parts, main-hand, text-filtering, server-listing, particle-status | game on `login` |
| player-loaded | - | game, once after the first teleport |
| move-player-pos-rot, move-player-status-only | x y z f64, yaw pitch f32, flags u8 / flags | game movement |
| move-player-pos, move-player-rot | as above | nobody yet |
| set-carried-item | slot i16 | intent `:dig` (slot 0), `:place` (equip) |
| use-item-on | hand, pos, face varint, cursor-x/y/z f32, inside-block, world-border-hit, sequence | `:place`, `:open-container` |
| player-action | status, pos, face i8, sequence | intent `:dig` |
| swing | hand | intent `:dig` |
| container-click | window-id, state-id, slot i16, button i8, mode, changed [slot hashed-slot], cursor hashed-slot | `window/click` (craft, place equip) |
| container-close | window-id | craft (`:settle` for window 0, `:take` for a table) |

## Gotchas
- A spec decodes only its listed fields; "rest ignored" rows rely on that.
- A `:slot` with data components truncates the rest of its packet (`:truncated true`); a damaged tool in `container-set-content` loses every later slot today.
- Not modelled yet but in `packets.edn`: `use-item` 67, `attack` 1, `place-recipe` 39, `respawn` (s2c 82).

## See also
- [[add-a-packet]] - adding a row.
- [[bytes-primitives]] - the field types.
