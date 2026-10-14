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
  - src/clojurecraft/race.clj#defn landing-of
  - bb/clojurecraft/page.clj#defn round
  - bb/clojurecraft/page.clj#defn wall
  - bb/clojurecraft/page.clj#defn chart
  - bb/clojurecraft/page.clj#defn publish!
  - test/clojurecraft/race_test.clj#a-racer-lands-on-its-own-landing-of-the-set
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

## The results page
The round's facts go to one static page, not an issue (issue #116; the race board comment and its issue, #81, are retired). The report job of `race.yml` and of `simrace.yml` runs `bb page round <dir> <kind> <run> <commit> <at>` (`bb/clojurecraft/page.clj`, `round`) over the runs it downloaded (what `bb failures` reads: every RESULT line and runs.edn line): how many runs, how many won, how many reached each milestone of the chain (`:plan/reached` in each RESULT; the chain is `goals.edn`'s targets by priority up to the Nether, read from the file), and the failures by signature. `bb page publish` appends that map as one line to `rounds.edn` on the `gh-pages` branch, rebuilds `index.html` beside it (hiccup2, bundled in Babashka: a table of rounds newest first, one SVG line chart per kind of the share of runs reaching each milestone round by round, `chart`, and the wall of the newest round) and pushes; a push lost to the other report's is retried on top of it. `bb page wall <dir>` (`wall`) ranks the failures by the stage the bot was on, furthest first, then by count: the top of both report jobs' summaries, and what to work on next. `test.yml` and `gym.yml` ignore `gh-pages`: a push there starts nothing. `bb page build rounds.edn out/` rebuilds the page from the data anywhere, which is the point of keeping the data as data.

## On the runners
`race.yml` runs on every push to main but a docs-only one (a merge cancels the race running: the races always run what is merged, and no second branch is kept in step), beside the sim race (`simrace.yml`, [[sim-race]]), which has eight more runners, four of the twenty staying free for the suite, a fix's checks and the gym; `ci/race.edn` is 8 runners of 2 servers of 25 bots (capacity run 38348283421: the bots held 20 ticks a second up to 210 a runner, a server's p95 tick 67 ms at 30 bots and about 130 ms from 50). A report job files the failed bots' signatures as issues (`bb failures`, [[sim-race]]). one job reads `ci/race.edn`, `world.yml` builds or finds each of its landing sets (`:race/landing-sets`, `ci/gym.edn` R1-R4: each its own seed, kept in its `level.dat`, 48 forest landings 320 apart, 112 blocks pregenerated round each, built once and cached), then `:race/runners` (20) jobs each restore one set (runner r races on set (r-1) mod 4) and start `:race/servers` servers (default 1) on copies of it, each with its own ports (game 25565+k-1, RCON 25575+k-1) and race process racing `:race/bots` bots, recording into `data/race/s<k>`. With `--landings`, racer i lands on the set's landing offset + i (`race/landing-of`; the offset counts the runners before it on its set and the servers before it on its runner), so no locate is run and no claims are made, and every commit races the same worlds from the same landings: two commits compare landing by landing. A vanilla server ticks on one thread, at about 2 ms of its 50 ms tick per bot (capacity run 38333898855), so one server stops near 25 bots however cheap the bots are; more servers use the runner's other cores. A runner stays the machine: with more than one server, the facts say which (`--server k`: `:milestone/server`, `:sample/server`, `:race/server`), and the board shows runner/server. `test.yml`, `gym.yml` and `full.yml` skip the race branch so every runner races. Each job's summary lists its RESULT and RACE lines; its artifact holds the recordings (zstd), the race log and the server log.

## Capacity (the race-capacity branch)
How many racers a runner carries is measured, not guessed. `ci/capacity.edn` ramps the race (`:race/ramp`, `start-at`: ten bots, then twenty more every four minutes, to 210; the first ramp stepped ten every five minutes to 150), every bot stopping at one deadline (`:race/deadline-ms`). Runners 1 and 2 also keep a flight recording of the server's JVM and the bots' (`server.jfr`, `bots.jfr` in their artifacts), so a load's numbers come with what the CPU was spent on. The race's server runs with no compression (`COMPRESSION_THRESHOLD` -1: the bots are on the same machine) and `sync-chunk-writes` off (`bb/clojurecraft/server.clj`). Two observers write facts every 15 s: the race's `sampler!` (`sample`: bots joined, landed, playing, ended, cut off; the playing bots' ticks per second as median, p10 and least, since a bot short of CPU falls below 20 first; milestones; the server's `tick query`, `tick-times`; the bots' heap) and `bb capacity sample` (each JVM's resident memory, CPU and heap from jstat, the machine's free memory, swap and load). `bb capacity report` judges every runner at every load after a two-minute settle (healthy: server p95 under 50 ms, slowest tenth of bots at 19 ticks/s or better, none cut off, no swap, memory to spare), takes each runner's knee (the most bots with every load up to it healthy), recommends the lower quartile of the knees, fits each JVM's live heap per bot over every healthy load, sizes each heap at twice its live set, and keeps 1.5 GB free. `capacity.yml` runs it on all twenty runners and posts the report on the pull request.

## Gotchas
- The first race (run 38328518058) searched plain `minecraft:forest` 40 times per bot from cells near spawn: the nearest forests were 1,000 blocks off, every search found the same two, eight bots of ten got no landing and waited `:no-log` at spawn, and one search far from any forest took 60 s in one tick and the watchdog killed the server. The race's server now runs with `max-tick-time` -1 (`MAX_TICK_TIME`), and the search is the forest tag from points 288 blocks apart, twelve at most.
- Bots join `:race/stagger-ms` apart: fresh players generating chunks at once time each other out (steve's races 610 and 611).
- `keep_inventory` is set on, as the siblings race.
- A bot whose landing fails starts where it stands; a bot that throws is a `:crashed` RESULT and the others race on.
