---
title: Race
description: How many bots race at once (issue #17) - one JVM per runner, each bot its own atom and loop (main/run-bot!), one bounded claims channel for landing cells, a fixture that lands each bot on its claim, a board that watches, and race.yml putting a server and a world seed on every runner.
type: reference
tags: [bot, tooling, race]
aliases: [race.clj, claims.clj, run-bot!, race.yml, ci/race.edn, claims channel, board]
status: verified
lastUpdated: 2026-10-13
verifiedAgainst: HEAD
sourceRefs:
  - src/clojurecraft/main.clj#defn run-bot!
  - src/clojurecraft/claims.clj#defn claim
  - src/clojurecraft/claims.clj#defn spiral
  - src/clojurecraft/race.clj#defn claims!
  - src/clojurecraft/race.clj#defn land!
  - src/clojurecraft/race.clj#defn fixture!
  - src/clojurecraft/race.clj#defn board!
  - src/clojurecraft/race.clj#defn summary
  - src/clojurecraft/fixture.clj#defn land-at!
  - src/clojurecraft/race.clj#defn milestones!
  - test/clojurecraft/race_test.clj#a-milestone-is-a-fact-of-what-was-reached
  - test/clojurecraft/race_test.clj#no-cell-is-granted-to-two-bots
related:
  - "[[bot/tooling/_moc|Tooling]]"
  - "[[gym-on-runners]]"
  - "[[harness-landing]]"
  - "[[record-replay]]"
---

# Race

`clojure -M:race --config ci/race.edn --port P --rcon-port R --rcon-pass S [--record-dir d] [--commit sha] [--seed s]` races `:race/bots` bots on one server to `:race/until`, each playing every step up to it from empty hands (a chained run: reached steps stay reached).

## Shape (docs/hickey.md, Conveyance)
- **Each bot is its own atom and loop.** `main/run-bot!` is one bot's whole life as a function: connect, fold the socket, the clock and its event queue into its own world, record, RESULT. `clojure -M:run` is one call of it; a race is many, a thread each, in one JVM. Nothing is shared between them.
- **One thing is shared: the landing cells.** `claims/claim` is a pure reducer, `(claim held request) → [held' answer]`, first come first served; `race/claims!` folds it over one bounded channel. `no-cell-is-granted-to-two-bots` holds it over generated claim sequences.
- **The fixture lands each bot on its claim.** Cells are `claims/cell-size` (96) blocks square, counted from where the first bot joins (the world spawn). The spiral's cells, `search-stride` (3) apart, are where it searches from: the nearest forest of any kind (`#minecraft:is_forest`) to each is claimed by the cell it lies in, `claim-tries` (12) at most; the first granted is landed on (`fixture/land-at!`, the harness's own landing: teleport, ask the server whether the ground will do, try nearby). Its spawn is pinned there and its `:go` (the chain, `:go/chain? true`, `:go/at`) goes on its own bounded queue: the fixture never touches a world.
- **The board watches.** Once a minute, `board!` takes each bot's world as it is and prints a `BOARD` line (`standing`: the target it works, milestones reached, dimension, height, deaths), and the server's `tick query`, the measure of how many bots a server carries. It never touches a loop.
- **Every racer is a file.** Each bot records to `<record-dir>/<name>.edn`, which replays alone with `clojure -M:replay` (a two-bot local race to wood: both `:identical`). A bot's RESULT line is printed when it ends, with the server's `data get entity <name> Dimension` asked while it is still connected; the `RACE` line and `summary.edn` are `summary`, a fold over the results.

- **Milestones are facts.** `milestones!` watches every bot's atom and turns each target newly joining `:plan/reached` into one fact, `{:milestone/runner :milestone/seed :milestone/bot :milestone/goal :milestone/at :milestone/after-s}`, put on a bounded channel and written by its own thread as a `MILESTONE` line and a line of `milestones.edn`. The bot never knows; the race never posts anything.

## The race's comment
`bb race-board` (`bb/clojurecraft/race_board.clj`) is the consumer: the plan job creates one comment on the race's pull request, and each runner pipes its `MILESTONE` and `RACE` lines into `follow`, which adds them to the comment's set of facts (an `edn` block, one map per line). Twenty runners write the one comment with no lock: read, add, write, read again, start over until its facts are there; facts only accumulate and the merge is a union, so every order of writes ends with every fact. A runner's end (`:race/runner :race/ok :race/reasons`) carries no time, so it is one fact however often it is posted. Above the facts the comment shows what is rendered from them: the Nether arrivals, the first bot to each milestone, each runner's furthest bot, the runners ended.

## On the runners
`race.yml` runs on every push to the `race` branch (a new push cancels the race running): one job reads `ci/race.edn`, then `:race/runners` (20) jobs each start a server with its own seed (`race-<run id>-<runner>`, peaceful) and race `:race/bots` bots on it. `test.yml`, `gym.yml` and `full.yml` skip the race branch so every runner races. Each job's summary lists its RESULT and RACE lines; its artifact holds the recordings (zstd), the race log and the server log.

## Gotchas
- The first race (run 38328518058) searched plain `minecraft:forest` 40 times per bot from cells near spawn: the nearest forests were 1,000 blocks off, every search found the same two, eight bots of ten got no landing and waited `:no-log` at spawn, and one search far from any forest took 60 s in one tick and the watchdog killed the server. The race's server now runs with `max-tick-time` -1 (`MAX_TICK_TIME`), and the search is the forest tag from points 288 blocks apart, twelve at most.
- Bots join `:race/stagger-ms` apart: fresh players generating chunks at once time each other out (steve's races 610 and 611).
- `keep_inventory` is set on, as the siblings race.
- A bot whose landing fails starts where it stands; a bot that throws is a `:crashed` RESULT and the others race on.
