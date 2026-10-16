---
title: Race
description: How many bots race at once (issue #17) - one JVM per server, each bot its own atom and loop (main/run-bot!), slots refilled the moment their bot ends so a server is always full (issue #127), a fixture that lands each bot, a board that watches, and race.yml reporting each runner's ended bots every 20 minutes (bb churn).
type: reference
tags: [bot, tooling, race]
aliases: [race.clj, claims.clj, run-bot!, race.yml, ci/race.edn, claims channel, board]
status: verified
lastUpdated: 2026-10-16
verifiedAgainst: HEAD
sourceRefs:
  - src/clojurecraft/main.clj#defn run-bot!
  - src/clojurecraft/claims.clj#defn claim
  - src/clojurecraft/claims.clj#defn spiral
  - src/clojurecraft/race.clj#defn claims!
  - src/clojurecraft/race.clj#defn land!
  - src/clojurecraft/race.clj#defn land-bot!
  - src/clojurecraft/race.clj#defn play-one!
  - src/clojurecraft/race.clj#defn slot!
  - src/clojurecraft/race.clj#defn run-ms
  - src/clojurecraft/race.clj#defn backoff-ms
  - test/clojurecraft/race_test.clj#a-slot-backs-off-while-its-bots-never-play
  - src/clojurecraft/race.clj#defn marked
  - src/clojurecraft/failure.clj#defn cut
  - bb/clojurecraft/churn.clj#defn batch!
  - bb/clojurecraft/fleet.clj#defn decide
  - bb/clojurecraft/fleet.clj#defn victims
  - bb/clojurecraft/fleet.clj#defn refills
  - bb/clojurecraft/churn.clj#defn grown
  - bb/clojurecraft/churn.clj#defn convert!
  - bb/clojurecraft/page.clj#defn combine
  - bb/clojurecraft/page.clj#defn deploy!
  - bb/clojurecraft/page.clj#defn race-end
  - bb/clojurecraft/win.clj#defn winners
  - test/clojurecraft/race_test.clj#a-bot-the-job-ends-is-cut-never-filed
  - src/clojurecraft/race.clj#defn board!
  - src/clojurecraft/race.clj#defn summary
  - src/clojurecraft/fixture.clj#defn land-at!
  - src/clojurecraft/race.clj#defn milestones!
  - test/clojurecraft/race_test.clj#a-milestone-is-a-fact-of-what-was-reached
  - test/clojurecraft/race_test.clj#no-cell-is-granted-to-two-bots
  - src/clojurecraft/race.clj#defn landing-of
  - bb/clojurecraft/page.clj#defn round
  - bb/clojurecraft/page.clj#defn wall
  - bb/clojurecraft/page.clj#defn speeds
  - bb/clojurecraft/page.clj#defn publish-rounds!
  - test/clojurecraft/race_test.clj#a-racer-lands-on-its-own-landing-of-the-set
related:
  - "[[real-snapshots]]"
  - "[[results-data]]"
  - "[[bot/tooling/_moc|Tooling]]"
  - "[[gym-on-runners]]"
  - "[[harness-landing]]"
  - "[[record-replay]]"
---

# Race

`clojure -M:race --config ci/race.edn --port P --rcon-port R --rcon-pass S [--record-dir d] [--commit sha] [--seed s]` keeps `:race/bots` bots playing on one server toward `:race/until` for the job's length, each playing every step up to it from empty hands (a chained run: reached steps stay reached).

## Shape (docs/RICH-HICKEY.md, Conveyance)
- **Each bot is its own atom and loop.** `main/run-bot!` is one bot's whole life as a function: connect, fold the socket, the clock and its event queue into its own world, record, RESULT. `clojure -M:run` is one call of it; a race is many, a thread each, in one JVM. Nothing is shared between them.
- **The server is always full (issue #127).** A race is `:race/bots` slots (`slot!`, a thread each), each joining at its place in the schedule (`start-at`) and then playing bot after bot (`play-one!`): the moment a bot ends, its slot starts a fresh one with the next serial's name and the next landing, so no bot waits for another and nothing waits for the slowest. A bot has no cap of its own: it plays until its goal, its plan failing, a crash or the job's end (`run-ms`: `:race/run-minutes`, 330, near GitHub's 6 h job cap; the capacity ramp's `:race/deadline-ms` instead). A slot starts no bot with less than `min-play-ms` (5 min) left. A bot that crashed or was cut off within `quick-ms` (30 s) never played: the slot waits `backoff-ms` before the next (1 s, doubling to a minute, none after a bot that played), so a server that stops or fails is not met by every slot starting bot after bot until the runner can fork no more (race 38658377110: a cancel stopped the servers under live race processes, and the runners failed to fork). On a cancel or at the job's end, `Stop the races` kills the race processes, with shell builtins only, before the servers stop. A bot the job's end stops is cut (`marked`, `failure/cut`: `:run/cut?` and `:run/infra?`): it says nothing about the bot's play and is never filed.
- **One thing is shared: the landing cells.** `claims/claim` is a pure reducer, `(claim held request) → [held' answer]`, first come first served; `race/claims!` folds it over one bounded channel. `no-cell-is-granted-to-two-bots` holds it over generated claim sequences.
- **The fixture lands each bot on its claim.** `land-bot!` lands one bot as it starts, on its own RCON session; the game rules are set once (`set-gamerules!`). Cells are `claims/cell-size` (96) blocks square, counted from where the first bot joins (the world spawn). The spiral's cells, `search-stride` (3) apart, are where it searches from: the nearest forest of any kind (`#minecraft:is_forest`) to each is claimed by the cell it lies in, `claim-tries` (12) at most; the first granted is landed on (`fixture/land-at!`, the harness's own landing: teleport, ask the server whether the ground will do, try nearby). Its spawn is pinned there and its `:go` (the chain, `:go/chain? true`, `:go/at`) goes on its own bounded queue: the fixture never touches a world.
- **The board watches.** Once a minute, `board!` takes each playing bot's world as it is (the slots keep a map of who plays now) and prints a `BOARD` line (`standing`: the target it works, milestones reached, dimension, height, deaths), and the server's `tick query`, the measure of how many bots a server carries. It never touches a loop.
- **Every racer is a file.** Each bot records to `<record-dir>/<name>.edn`, which replays alone with `clojure -M:replay` (a two-bot local race to wood: both `:identical`). A bot's RESULT line is printed when it ends, with the server's `data get entity <name> Dimension` asked while it is still connected; the `RACE` line and `summary.edn` are `summary`, a fold over the results.

- **Milestones are facts.** `milestones!` watches every bot's atom and turns each target newly joining `:plan/reached` into one fact, `{:milestone/runner :milestone/seed :milestone/bot :milestone/goal :milestone/at :milestone/after-s}`, put on a bounded channel and written by its own thread as a `MILESTONE` line and a line of `milestones.edn`. The bot never knows; the race never posts anything.

## The results page
The round's facts go to one static site, not an issue (issue #116; the race board comment and its issue, #81, are retired). Each runner reports while it plays (issue #127): every 20 minutes, staggered by runner, its Race step runs `bb churn batch <kind> <data-dir> <artifact> <run> <sha> <code-at> <runner>` (`bb/clojurecraft/churn.clj`, `batch!`), which copies the whole lines each race.log or runs.edn gained since its last batch (`grown`, offsets in `<data-dir>/.churn.edn`) under `churn-batches/<n>/<artifact>/`, turns a real batch's failures into snapshots first (`convert!`: each failed bot's recording folded on the runner at the race's own commit, the ones the model reproduces kept under `<data-dir>/snapshots`, [[real-snapshots]]), files their failures (`bb failures`, a signature judged by the job's counts so far, so a few runs a batch add up), appends the wall to the job's summary, releases a win (`bb win`, a release named for the target the winners reached, `<until>-<run>-<bot>`) and publishes the batch (the last batch is a step of its own that runs even when a merge cancels the round, and so do the pack and the upload, so the recordings and snapshots the batches' issues name always reach the artifact). `page/round` (`bb/clojurecraft/page.clj`) makes the batch's facts from those runs (what `bb failures` reads: every RESULT line and runs.edn line): how many runs, how many won, how many died and how often, how many reached each milestone of the chain (`:plan/reached` in each RESULT; the chain is `goals.edn`'s targets by priority up to the race's own target, `ci/race.edn`'s `:race/until` (`race-end`; the fortress since issue #261), each read from its file), the fastest and median times (`speed`: the race logs' MILESTONE facts, a sim run's `:plan/reached-after`), the failures by signature with their issues, with `:round/runner` and `:round/batch`. `publish-rounds!` lays it out on the `gh-pages` branch as a batch in its run's day and rebuilds that day's runs (a run's batches combined into one row, `combine`: counts and failures summed, the fastest the least, medians weighted by runs, `speeds`) and the all-time summary; a push lost to another lane is recomputed on the new tip and retried, and a batch that never gets through is held for the lane's next ([[results-data]] has the layout, why it was chosen, and the site). Each publish dispatches `pages.yml`, which builds the site from main's code over the branch's data. `bb page wall <dir>` (`wall`) ranks the failures by the stage the bot was on, furthest first, then by count: the top of both report jobs' summaries, and what to work on next. `test.yml` and `gym.yml` ignore `gh-pages`: a push there starts nothing.

## On the runners
`race.yml` and `simrace.yml` ([[sim-race]]) run as lanes: each run is one lane, the runner k of the old matrix (`race lane k`), dispatched by the fleet (`fleet.yml`, `bb/clojurecraft/fleet.clj`, `ci/fleet.edn`), one small job that looks every minute. GitHub has no job priority and no preemption (a job queued behind running ones waits until one ends), so the fleet does it (`decide`): a lane whose code main has replaced (a merge that touches more than `brain/`, `docs/` and `*.md`) is cancelled and replaced, so the races always run what is merged; while another workflow's job has waited `:fleet/patience-s` (90 s) with no runner free, the newest lanes are cancelled (`victims`), never a kind below `:fleet/floor` (2 and 2); while nothing waits, free runners get lanes on main, the kind with fewer first, at the lowest free lane number, up to `:fleet/max` (8 and 8) (`refills`). A cancelled lane still stops its races, reports and uploads. The fleet runs `:fleet/minutes` (340) and dispatches its successor; the repository variable `RACE_CONTINUOUS=false` stops new lanes; `ci/race.edn` is 8 runners of 2 servers of 25 bots (capacity run 38348283421: the bots held 20 ticks a second up to 210 a runner, a server's p95 tick 67 ms at 30 bots and about 130 ms from 50). Each runner files its ended bots' signatures as issues while it plays (`bb churn`, `bb failures`, [[sim-race]]). One job reads `ci/race.edn`, then `:race/runners` jobs each restore one landing set from the cache (`:race/landing-sets`, `ci/gym.edn` R1-R4: each its own seed, kept in its `level.dat`, 48 forest landings 320 apart, 112 blocks pregenerated round each, built once by `world.yml`, dispatched by hand when a set's key changes; a miss fails the job at once, and no job looks the sets up first) (runner r races on set (r-1) mod 4) and start `:race/servers` servers (default 1) on copies of it, each with its own ports (game 25565+k-1, RCON 25575+k-1) and race process racing `:race/bots` bots, recording into `data/race/s<k>`. With `--landings`, the bot with serial i lands on the set's landing offset + i - 1, wrapping (`race/landing-of`; the offset counts the runners before it on its set and the servers before it on its runner), so no locate is run and no claims are made, and every commit races the same worlds from the same landings: two commits compare landing by landing. A vanilla server ticks on one thread, at about 2 ms of its 50 ms tick per bot (capacity run 38333898855), so one server stops near 25 bots however cheap the bots are; more servers use the runner's other cores. A runner stays the machine: with more than one server, the facts say which (`--server k`: `:milestone/server`, `:sample/server`, `:race/server`), and the board shows runner/server. `test.yml`, `gym.yml` and `full.yml` skip the race branch so every runner races. Each job's summary holds each batch's wall and the RACE lines; its artifact (`race-<sha>-<runner>`, the name a batch's reproductions give) holds the recordings (zstd), the race logs and the server logs.

## Capacity (the race-capacity branch)
How many racers a runner carries is measured, not guessed. `ci/capacity.edn` ramps the race (`:race/ramp`, `start-at`: ten bots, then twenty more every four minutes, to 210; the first ramp stepped ten every five minutes to 150), every bot stopping at one deadline (`:race/deadline-ms`). Runners 1 and 2 also keep a flight recording of the server's JVM and the bots' (`server.jfr`, `bots.jfr` in their artifacts), so a load's numbers come with what the CPU was spent on. The race's server runs with no compression (`COMPRESSION_THRESHOLD` -1: the bots are on the same machine) and `sync-chunk-writes` off (`bb/clojurecraft/server.clj`). Two observers write facts every 15 s: the race's `sampler!` (`sample`: bots joined, landed, playing, ended, cut off; the playing bots' ticks per second as median, p10 and least, since a bot short of CPU falls below 20 first; milestones; the server's `tick query`, `tick-times`; the bots' heap) and `bb capacity sample` (each JVM's resident memory, CPU and heap from jstat, the machine's free memory, swap and load). `bb capacity report` judges every runner at every load after a two-minute settle (healthy: server p95 under 50 ms, slowest tenth of bots at 19 ticks/s or better, none cut off, no swap, memory to spare), takes each runner's knee (the most bots with every load up to it healthy), recommends the lower quartile of the knees, fits each JVM's live heap per bot over every healthy load, sizes each heap at twice its live set, and keeps 1.5 GB free. `capacity.yml` runs it on all twenty runners and posts the report on the pull request.

## Gotchas
- The first race (run 38328518058) searched plain `minecraft:forest` 40 times per bot from cells near spawn: the nearest forests were 1,000 blocks off, every search found the same two, eight bots of ten got no landing and waited `:no-log` at spawn, and one search far from any forest took 60 s in one tick and the watchdog killed the server. The race's server now runs with `max-tick-time` -1 (`MAX_TICK_TIME`), and the search is the forest tag from points 288 blocks apart, twelve at most.
- Bots join `:race/stagger-ms` apart: fresh players generating chunks at once time each other out (steve's races 610 and 611).
- `keep_inventory` is set on, as the siblings race.
- A bot whose landing fails starts where it stands; a bot that throws is a `:crashed` RESULT and the others race on.
