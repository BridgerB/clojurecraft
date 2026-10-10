---
title: Packet name collision
description: Symptom: encoding the login hello throws a ClassCastException. Cause: a wire field named name overwrote the packet's own name key.
type: reference
tags: [bot, gotcha, protocol]
aliases: [hello packet crash, :name field, username field]
status: verified
lastUpdated: 2026-10-09
verifiedAgainst: 60b624e
sourceRefs:
  - src/clojurecraft/packet.clj#[:login :c2s :hello] [[:username :string] [:uuid :uuid]]
  - src/clojurecraft/packet.clj#defn decode
related:
  - "[[bot/gotchas/_moc|Gotchas]]"
  - "[[packet-specs]]"
---

# Packet name collision

Symptom: `ClassCastException: Keyword cannot be cast to String` while encoding the login `hello`.

## Root cause
The wire calls the player name field `name`. With packets keyed by a bare `:name`, the field and the packet's identity shared one key. The fix was two-fold: packets carry `:packet/name` (namespaced), and the hello field is spelled `:username` in the spec table.

## Fix
Never use a bare `:name` field in a spec; the packet identity is `:packet/name`.

## See also
- [[packet-specs]]
