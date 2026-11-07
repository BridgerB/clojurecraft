---
title: Observability
description: Every ended run, real and sim, leaves in its batch what killed or stopped it, what it was doing and why, what it saw and what the server knew, and its recording to replay; a case file reads it all in ten seconds and a batch table ranks the endings.
type: decision
tags: [bot, tooling, race, sim, senses]
aliases: [observability, case file, cases.edn, ledger, events.edn, moment, ring, server truth, intent/why, bb probe why, hear.clj]
status: verified
lastUpdated: 2026-11-06
verifiedAgainst: HEAD
sourceRefs:
  - src/clojurecraft/hear.clj#defn attributes-of
  - src/clojurecraft/game.clj#defn hit-of
  - src/clojurecraft/registry.clj#defn damage-type
  - src/clojurecraft/senses.clj#defn events
  - src/clojurecraft/senses.clj#defn ledger!
  - src/clojurecraft/senses.clj#defn moment
  - src/clojurecraft/senses.clj#defn senses!
  - "src/clojurecraft/intent.clj#Intent i labelled with the planner's branch that chose it"
  - src/clojurecraft/intent.clj#defn why-of
  - src/clojurecraft/intent.clj#defn why-or-default
  - src/clojurecraft/intent.clj#defn child-why
  - "src/clojurecraft/memory.clj#The fact for intent i reaching event"
  - src/clojurecraft/race.clj#defn near-truth!
  - src/clojurecraft/race.clj#defn play-one!
  - bb/clojurecraft/botlogs.clj#defn case-of
  - bb/clojurecraft/botlogs.clj#defn case-text
  - bb/clojurecraft/botlogs.clj#defn keep-recording?
  - bb/clojurecraft/botlogs.clj#defn upload!
  - bb/clojurecraft/probe_run.clj#defn why-command
  - dev/clojurecraft/why.clj#defn answers
related:
  - "[[race]]"
  - "[[sim-race]]"
  - "[[record-replay]]"
  - "[[real-snapshots]]"
---

# Observability

The bot's whole mind is one world value, so everything a reader needs about a run is either in it or heard by it; this is how it is kept and read, the same for real and sim runs (Nov 6, issue #263: deaths on stage 18 were counted, never explained).

## What is heard

- **Hits:** each damage-event on the bot is kept in `:player/hits`, the last 20, named by damage type (`registry/damage-type` over the generated `damage-types.edn`: `:mob_attack`, `:lava`, `:in_fire`, `:fall` ...) with the attacker's id and kind (`game/hit-of`).
- **hear.clj:**
  - hurt flashes (`:entity/hurt-at`: our swing landed);
  - mob swings (`:entity/swung-at`);
  - the fight bracket (`:player/in-combat?`);
  - effects (`:player/effects`);
  - attributes (`:player/armor`, `:player/max-health`: the server's word on worn armour).

## What is kept, per run

- **The ledger:** `senses/events` names every change between two worlds; `senses/ledger!` keeps them all, unsampled, and counts any it drops. Written to `<name>.events.edn`.
- **The senses:** one line a second (`senses/of`), and the ring: a moment a minute for the last five (`senses/senses!`).
- **The moment:** the whole mind at the ending (`senses/moment`): facts, the blocks round the feet, the intent chain, the plan, the stages' keys, entities near, hits, effects, armour, and the newest 40 intentions.
- **Why:** every running intent carries a why (`intent/why`, on metadata, so equality is unchanged): a planner that tags its branches gives the finer label (the stuck stage's do), else `plan/start-intent` labels it `:<goal>/<kind>` (`intent/why-or-default`), and a child an executor starts inherits its parent's (`intent/child-why`, in `run-child`); intention facts keep `:intention/why`.
- **Server truth** (real): every entity within 32 blocks of the ending and the bot's own entity, whole (`race/near-truth!`), as `<name>.server.txt`.
- **The recording** of every failed run (`botlogs/keep-recording?`).

## How it is read

- **Each batch** writes `<stem>.case.txt` and `.case.edn` for every ended run (`botlogs/case-of`, `case-text`): the ending, the killer, the last minute's first hit and what the bot did then and why, what it did at the end, hits by attacker, swings sent and landed, and the replay command. It also writes `cases.edn`, every case's signature `[ending killer doing why]`.
- **The routine** (LOOP.md section 4): `T/cases.ts` first, three case texts of the top signature, and `bb probe why <tag> <asset> <stem> <ms>` only when a case cannot answer (`probe-run/why-command`, `dev/clojurecraft/why.clj`: the recording folded on a runner, the moment and the planner's answers printed).
