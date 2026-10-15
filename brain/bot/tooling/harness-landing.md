---
title: Harness landing
description: The fixture as its own process - it waits for the bot over RCON, lands it in a forest, judges the landing by asking the server, and speaks to the bot only through one EDN :go event on a pipe; the planner then waits in :landing until the bot is there.
type: reference
tags: [bot, tooling, harness, rcon]
aliases: [clojure -M:harness, --events stdin, go/at, plan/go-at, :landing, landed?, land!, locate-forest, teleport!, checks, bad-landing, parse-pos, go-event, landing-offsets, wait-online, read-events!, locate biome forest, forceload, forest landing, landed in water, fixture process]
status: verified
lastUpdated: 2026-10-11
verifiedAgainst: HEAD
sourceRefs:
  - src/clojurecraft/harness.clj#defn -main
  - src/clojurecraft/fixture.clj#defn wait-online!
  - src/clojurecraft/harness.clj#defn locate-forest!
  - src/clojurecraft/fixture.clj#defn teleport!
  - src/clojurecraft/fixture.clj#defn with-forceload
  - src/clojurecraft/fixture.clj#def checks
  - src/clojurecraft/fixture.clj#defn bad-landing
  - src/clojurecraft/fixture.clj#def landing-offsets
  - src/clojurecraft/harness.clj#defn land!
  - src/clojurecraft/fixture.clj#defn go-event
  - src/clojurecraft/main.clj#defn read-events!
  - src/clojurecraft/plan.clj#defn landed?
  - src/clojurecraft/plan.clj#defn landing-tick
  - test/clojurecraft/plan_test.clj#a-go-with-a-landing-waits-until-the-bot-is-there
  - docs/RICH-HICKEY.md#He would make the fixture its own process that speaks to the bot through a queue
related:
  - "[[bot/tooling/_moc|Tooling]]"
  - "[[natural-tree-not-fixture]]"
  - "[[rcon-client]]"
  - "[[main-loop]]"
---

# Harness landing

The harness is the test fixture, and it is its own process (`clojure -M:harness`). It shares nothing with the bot but data: it reaches the server only over RCON and reaches the bot only through a queue, one EDN line on its stdout piped into the bot's stdin (`--events stdin`). This is what `docs/RICH-HICKEY.md` asks for; the earlier harness was a thread inside the bot that watched the world atom.

```bash
clojure -M:harness --rcon-port 25581 --rcon-pass "$P" --name Clj_wood --until wood 2> harness.log \
  | clojure -M:run --port 25571 --name Clj_wood --until wood --events stdin
```

## Key files
- `harness.clj`, `-main` - the sequence below; logs go to stderr because stdout is the queue. `locate-forest!` and `land!` are the forest search; everything it asks the server is `fixture.clj`'s, shared with the gym's `land` and the landing-set builder.
- `fixture.clj`, `checks` - the landing tests as data: `[reason "if|unless block ..."]`, each run as `execute at <bot> <test>`; `bad-landing` names the first that passed.
- `fixture.clj`, `landing-offsets` - 17 points: the located point, then rings of eight at 24 and 48 blocks.
- `fixture.clj`, `with-forceload` - every column a fixture loads is released in a `finally`.
- `fixture.clj`, `go-event` - `{:event/kind :go :go/goals (plan/goals-for until) :go/at [x y z]}`.
- `main.clj`, `read-events!` - reads EDN lines from stdin onto the events channel until end of input.
- `plan.clj`, `landed?` and `landing-tick` - the bot side of the wait.

## Sequence
1. `wait-online!`: poll `data get entity <name> Pos` every 250 ms (up to 120 s) until the server knows the player.
2. `locate-forest!`: `gamemode survival`, `clear` (an empty inventory every run), `execute at <name> run locate biome minecraft:forest`, parse `[x, y, z]`.
3. For each offset: `teleport!` forceloads the column, polls `execute if loaded x 0 z`, runs `execute positioned x 0 z positioned over motion_blocking_no_leaves run tp <name> ~0.5 ~ ~0.5` (the highest non-leaf motion-blocking block), and unforceloads. After 1 s it runs every check; a passing one logs "bad landing <reason> - trying another spot" and moves on. The last offset is used whatever it says.
4. Read the final position from `data get entity <name> Pos` and print the `:go` event. On any failure it logs "harness failed:" and still prints `:go` without `:go/at`, so the bot plans where it stands.
5. In the bot, `plan/begin` with `:go/at` sets `:plan/status :landing` and `:plan/go-at`. Each tick `landing-tick` turns it `:active` (logging "landed") once the bot is within 2 blocks horizontally of `:go/at`, loaded, in a loaded chunk; after 30 s it fails with `:no-landing`.

## Gotchas
- The checks replace bot-side predicates over the bot's own chunks. The fixture now asks the server, which is the authority on what is at the bot's feet, and needs no access to the bot at all.
- Three bad landings were each seen in CI before the checks existed: a pond (the heightmap counts water as a surface), a log inside an oak canopy (the heightmap skips leaves but not a trunk top), and inside stone (the heightmap was not ready). The first live run of this process tried water, then buried, then landed. A fourth came with the beach set (gym shore-w1 run 6): the heightmap stops on seagrass and kelp, which are water to the physics, so the checks also refuse `#minecraft:underwater_bonemeals` at the feet or under them and water one above. A fifth was the fixture's own: `landings/answers!` ran the checks after the column's forceload was released, and on an unloaded column every `if block` test fails, so two open-sea landings read as fine (shore-w2 runs 6 and 9); the checks now hold the column loaded while they ask.
- That run read y 57 one second after a teleport to y 63; the bot still walked to and dug a log at y 64. The checks only look at the feet and the block under them.
- Without `--events stdin` and with a planned `--until`, `main` sends `:go` itself once the player is loaded (`go-when-loaded!`): no fixture, no landing.
- The `:go` event, `:go/at` included, is recorded like any other, so a replay needs neither RCON nor the harness ([[record-replay]]).
- `clear` means a `--until table` run always starts from nothing; prerequisites over RCON are issue #3.

## See also
- [[natural-tree-not-fixture]] - why a real forest.
- [[goals-in-play-from-go]] - what `:go/goals` selects.
