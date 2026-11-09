---
title: Race
description: How many bots race at once (issue #17) - one JVM per server, each bot its own atom and loop (main/run-bot!), slots refilled the moment their bot ends so a server is always full (issue #127), a fixture that lands each bot, a board that watches, and race.yml reporting each runner's ended bots every 20 minutes (bb churn).
type: reference
tags: [bot, tooling, race]
aliases: [race.clj, claims.clj, run-bot!, race.yml, ci/race.edn, claims channel, board]
status: verified
lastUpdated: 2026-11-08
verifiedAgainst: HEAD
sourceRefs:
  - src/clojurecraft/main.clj#defn run-bot!
  - src/clojurecraft/claims.clj#defn claim
  - src/clojurecraft/claims.clj#defn spiral
  - src/clojurecraft/race.clj#defn claims!
  - src/clojurecraft/race.clj#defn land!
  - src/clojurecraft/race.clj#defn land-bot!
  - src/clojurecraft/start.clj#defn kit-commands
  - src/clojurecraft/start.clj#defn goals-from
  - src/clojurecraft/land.clj#defn land-forest!
  - src/clojurecraft/land.clj#defn warped-floor-from!
  - src/clojurecraft/lanes.clj#defn lane-of
  - src/clojurecraft/race.clj#defn play-one!
  - src/clojurecraft/race.clj#defn slot!
  - src/clojurecraft/race_facts.clj#defn run-ms
  - src/clojurecraft/race_facts.clj#defn backoff-ms
  - test/clojurecraft/race_test.clj#a-slot-backs-off-while-its-bots-never-play
  - src/clojurecraft/race_facts.clj#defn health
  - src/clojurecraft/race_facts.clj#defn next-start
  - src/clojurecraft/race.clj#defn probe!
  - src/clojurecraft/failure.clj#defn unplayed?
  - bb/clojurecraft/failures.clj#defn unplayed?
  - test/clojurecraft/race_test.clj#a-server-whose-bots-never-play-is-down-until-it-accepts
  - test/clojurecraft/race_test.clj#a-bot-that-never-got-into-play-is-infrastructure
  - test/clojurecraft/race_test.clj#a-server-sees-at-most-one-new-bot-a-start-gap
  - src/clojurecraft/race_facts.clj#defn marked
  - src/clojurecraft/failure.clj#defn cut
  - src/clojurecraft/start.clj#defn cap-ms
  - src/clojurecraft/start.clj#defn cap-ticks
  - src/clojurecraft/game.clj#defn server-ticks
  - src/clojurecraft/race_facts.clj#defn bot-timeout-ms
  - bb/clojurecraft/churn.clj#defn batch!
  - bb/clojurecraft/fleet.clj#defn decide
  - bb/clojurecraft/inspect.clj#defn busiest-threads
  - test-bb/clojurecraft/inspect_test.clj#a-sample-names-the-busiest-processes-and-threads
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
  - src/clojurecraft/race_facts.clj#defn summary
  - src/clojurecraft/fixture.clj#defn land-at!
  - src/clojurecraft/race.clj#defn milestones!
  - test/clojurecraft/race_test.clj#a-milestone-is-a-fact-of-what-was-reached
  - test/clojurecraft/race_test.clj#no-cell-is-granted-to-two-bots
  - src/clojurecraft/land.clj#defn landing-of
  - bb/clojurecraft/page.clj#defn round
  - bb/clojurecraft/page.clj#defn wall
  - bb/clojurecraft/page.clj#defn speeds
  - bb/clojurecraft/page.clj#defn publish-rounds!
  - test/clojurecraft/race_test.clj#a-racer-lands-on-its-own-landing-of-the-set
  - src/clojurecraft/lanes.clj#defn lane-of
  - src/clojurecraft/start.clj#def table
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
- **The server is always full (issue #127).** A race is `:race/bots` slots (`slot!`, a thread each), each joining at its place in the schedule (`start-at`) and then playing bot after bot (`play-one!`): the moment a bot ends, its slot starts a fresh one with the next serial's name and the next landing, so no bot waits for another and nothing waits for the slowest. A bot has no cap of its own except on a start that has one (`start/cap-ticks`: the start's `:start/cap-min` of game time, 1200 server ticks a minute, 60 minutes for the pearl starts): `main/run-bot!` stops it when the server ticks it has seen (`game/server-ticks`, 20 for each set-time packet) reach it, and `bot-timeout-ms` bounds it by the clock only at the job's deadline, so a lane still ends. A bot that reaches it is a `:bot-timeout` (`capped`, from its ticks), filed like any failure of its stage. Game time, not the clock, because the real servers tick far slower than 20 a second: lane 3 of 1f1116c (rods start), 79 bots stopped by an hour of the clock had seen 11,240 to 16,340 server ticks (median 14,060, under 12 game minutes, about 256 ms a tick), where a sim run's hour (`simrace/cap-max-ms` on `start/cap-ms`, simulated ms at 50 a tick) is 72,000. Otherwise it plays until its goal, its plan failing, a crash or the job's end (`run-ms`: `:race/run-minutes`, 330, near GitHub's 6 h job cap). A slot starts no bot with less than `min-play-ms` (5 min) left. A bot that crashed or was cut off within `quick-ms` (30 s) never played: the slot waits `backoff-ms` before the next (1 s, doubling to a minute, none after a bot that played), so a server that stops or fails is not met by every slot starting bot after bot until the runner can fork no more (race 38658377110: a cancel stopped the servers under live race processes, and the runners failed to fork). On a cancel or at the job's end, `Stop the races` kills the race processes, with shell builtins only, before the servers stop. A bot the job's end stops is cut (`marked`, `failure/cut`: `:run/cut?` and `:run/infra?`): it says nothing about the bot's play and is never filed.
- **A dead server stops its slots (race 39817745908).** Backing off alone was not enough: that lane's server 2 crashed 25 s in (a vanilla 26.1.2 NPE generating a chunk, `MoonPhase` null in `ServerLevel.getMoonBrightness`, under `NaturalSpawner.spawnMobsForChunkGeneration`), and its 15 slots each started a bot a minute for three hours, 2,864 `[:wood :crashed]` runs that never connected, which the batches published as real runs (16% of real runs reached wood that day). Now a bot that never got into play (`failure/unplayed?`: no `:plan/status` in its RESULT, which `main` leaves out until its `:go`) is `:run/infra?` (`marked`); the server's health (`health`, an atom per race) counts them in a row and at `dead-after` (5) calls the server down, printed once as `BOARD SERVER DOWN`; while down no slot starts a bot, and every `probe-every-ms` (30 s) each asks whether the game port accepts (`probe!`), and the first to find it so prints `BOARD SERVER BACK` and sets the game rules again. Every bot start on a server also takes a turn on one clock (`next-start`, `start-gap-ms` 1 s apart), so however bots end, a server sees at most one new bot a second. `race.yml`'s Race step watches the servers every minute: one whose pid is gone is started again on its world, up to three times, its log kept as `server-died-<n>.log` beside its `race.log`; the crash reports go in the artifact.
- **One thing is shared: the landing cells.** `claims/claim` is a pure reducer, `(claim held request) → [held' answer]`, first come first served; `race/claims!` folds it over one bounded channel. `no-cell-is-granted-to-two-bots` holds it over generated claim sequences.
- **The fixture lands each bot on its claim.** `land-bot!` lands one bot as it starts, on its own RCON session; the game rules are set once (`set-gamerules!`). Cells are `claims/cell-size` (96) blocks square, counted from where the first bot joins (the world spawn). The spiral's cells, `search-stride` (3) apart, are where it searches from: the nearest forest of any kind (`#minecraft:is_forest`) to each is claimed by the cell it lies in, `claim-tries` (12) at most; the first granted is landed on (`fixture/land-at!`, the harness's own landing: teleport, ask the server whether the ground will do, try nearby). Its spawn is pinned there and its `:go` (the chain, `:go/chain? true`, `:go/at`) goes on its own bounded queue: the fixture never touches a world.
- **The board watches.** Once a minute, `board!` takes each playing bot's world as it is (the slots keep a map of who plays now) and prints a `BOARD` line (`standing`: the target it works, milestones reached, dimension, height, deaths), and the server's `tick query`, the measure of how many bots a server carries. It never touches a loop.
- **Every racer is a file.** Each bot records to `<record-dir>/<name>.edn`, which replays alone with `clojure -M:replay` (a two-bot local race to wood: both `:identical`). A bot's RESULT line is printed when it ends, with the server's `data get entity <name> Dimension` asked while it is still connected; the `RACE` line and `summary.edn` are `summary`, a fold over the results.

- **Milestones are facts.** `milestones!` watches every bot's atom and turns each target newly joining `:plan/reached` into one fact, `{:milestone/runner :milestone/seed :milestone/bot :milestone/goal :milestone/at :milestone/after-s}`, put on a bounded channel and written by its own thread as a `MILESTONE` line and a line of `milestones.edn`. The bot never knows; the race never posts anything.

## The results page
The round's facts go to one static site, not an issue (issue #116; the race board comment and its issue, #81, are retired). Each runner reports while it plays (issue #127): every 20 minutes, staggered by runner, its Race step runs `bb churn batch <kind> <data-dir> <artifact> <run> <sha> <code-at> <runner>` (`bb/clojurecraft/churn.clj`, `batch!`), which copies the whole lines each race.log or runs.edn gained since its last batch (`grown`, offsets in `<data-dir>/.churn.edn`) under `churn-batches/<n>/<artifact>/`, turns a real batch's failures into snapshots first (`convert!`: each failed bot's recording folded on the runner at the race's own commit, the ones the model reproduces kept under `<data-dir>/snapshots`, [[real-snapshots]]), files their failures (`bb failures`, a signature judged by the job's counts so far, so a few runs a batch add up), appends the wall to the job's summary, releases a win (`bb win`, a release named for the target the winners reached, `<until>-<run>-<bot>`) and publishes the batch (the last batch is a step of its own that runs even when a merge cancels the round, its conversions stopped after 120 s, and so do the pack and the upload when the cancelled job lives long enough; the snapshots the batches' issues name never depend on that: each batch uploads them to the hour's evidence release before its comments, [[sim-race]]). `page/round` (`bb/clojurecraft/page.clj`) makes the batch's facts from those runs (what `bb failures` reads: every RESULT line and runs.edn line): how many runs, how many won, how many died and how often, how many reached each milestone of the chain (`:plan/reached` in each RESULT; the chain is `goals.edn`'s targets by priority up to the race's own target, `ci/race.edn`'s `:race/until` (`race-end`; the fortress since issue #261), each read from its file), the fastest and median times (`speed`: the race logs' MILESTONE facts, a sim run's `:plan/reached-after`), the failures by signature with their issues, with `:round/runner` and `:round/batch`. `publish-rounds!` lays it out on the `gh-pages` branch as a batch in its run's day and rebuilds that day's runs (a run's batches combined into one row, `combine`: counts and failures summed, the fastest the least, medians weighted by runs, `speeds`) and the all-time summary; a push lost to another lane is recomputed on the new tip and retried, and a batch that never gets through is held for the lane's next ([[results-data]] has the layout, why it was chosen, and the site). Each publish dispatches `pages.yml`, which builds the site from main's code over the branch's data. `bb page wall <dir>` (`wall`) ranks the failures by the stage the bot was on, furthest first, then by count: the top of both report jobs' summaries, and what to work on next. `test.yml` and `gym.yml` ignore `gh-pages`: a push there starts nothing.

## Starts (stages 17 and 18)
A start is where and with what a bot begins, one table for the real and the sim race: `resources/clojurecraft/starts.edn`, read through `clojurecraft.start` (`start-of`, `goals`, `kit-commands`, `kit-map`, `cap-ms`). `:spawn` is a forest of the landing and the whole chain from the wood. `:fortress` puts the bot of landing i in the Nether on the open pad `land/fortress-pad-open` 48 blocks west of the fortress the server locates from the landing's eighth (`land/land-fortress!`; `land/place-at-fortress!` is the gym rows' plain version), its inventory cleared and the base kit given (`give`, the bag race bots really carry to a fortress, from the lane audits of 2026-10-31), its spawn pinned there, and its `:go` names only the stages from the start's goal (the blaze rods) to `:race/until` (`start/goals`: [:blaze-rods :ender-pearls]). A failure is signed at those stages only (`failure/stage` takes the targets the plan has in play), so a fortress lane files no issue at a stage before 17. `:rods` is stage 18's checkpoint (LOOP.md section 1): the same pad, the seven blaze rods stage 17 yields given ahead of the base kit (the kit fills all 36 slots, so the rods go first), and only `[:ender-pearls]` to play. `:forest` is the same kit and goal landed on a floor of the warped forest nearest the fortress pad (`land/land-forest!`: the forest located from the pad with `locate biome`, the bot spread onto a floor in it by the gym's `land/warped-floor-from!`, warped forest and never a carved cave's air). A start with `:start/cap-min` stops its bot after that much game time: a `:bot-timeout`, filed as the stage's failure (`race/capped`), where the job's end is a cut.

`ci/lanes.edn` says which start each lane plays and the page kind its results go under (`lanes/lane-of`; `bb lane real 3` prints it as workflow outputs, which `race.yml` and `simrace.yml` pass as `--start` and to `bb churn batch`): lanes 1 to 4 play `:rods` (page kinds `real-18`, `sim-18`) and 5 to 8 `:forest` (`real-18f`, `sim-18f`). `bb churn batch real-18` reports as real, published as its own kind, so stage 17's rate counts only the bots that played it. The sim race reads the same table (`simrace/race!`); its forest start is `simrace/forest-start`, and a world whose captured Nether shows no nylium is left out of a forest race.

## Starts, lanes and the race as values
Where and with what a bot begins is one table, `resources/clojurecraft/starts.edn`, read through `clojurecraft.start` by the real race and the sim race alike so the two cannot disagree. Which start and results-page kind a runner plays is `ci/lanes.edn`, read by the pure `lanes/lane-of` (Babashka and the workflows read the same file). `race_facts.clj` holds what a race says as pure functions (the board, samples, milestones, when a slot may start its next bot, what a server's words about an ending mean); `race.clj` is only the part that talks to the server.


## On the runners
`race.yml` and `simrace.yml` ([[sim-race]]) run as lanes: each run is one lane, the runner k of the old matrix (`race lane k`), dispatched by the fleet (`fleet.yml`, `bb/clojurecraft/fleet.clj`, `ci/fleet.edn`), one small job that looks every minute. GitHub has no job priority and no preemption (a job queued behind running ones waits until one ends), so the fleet does it (`decide`): a lane whose code main has replaced (a merge that touches more than `brain/`, `docs/` and `*.md`) is cancelled and replaced, so the races always run what is merged; while another workflow's job has waited `:fleet/patience-s` (90 s) with no runner free, the newest lanes are cancelled (`victims`), never a kind below `:fleet/floor` (2 and 2); while nothing waits, free runners get lanes on main, the kind with fewer first, at the lowest free lane number, up to `:fleet/max` (8 and 8) (`refills`); for `:fleet/cooldown-s` (1200) after a lane was given back for need, only up to the floor, because lanes dispatched into a one-minute gap were cancelled 3 to 20 minutes later having reported nothing (2026-10-28: 20 real and 12 sim lanes in three hours). A cancelled lane still stops its races, reports and uploads. The fleet runs `:fleet/minutes` (340) and dispatches its successor; the repository variable `RACE_CONTINUOUS=false` stops new lanes; `ci/race.edn` is 8 runners of 2 servers of 25 bots (capacity run 38348283421: the bots held 20 ticks a second up to 210 a runner, a server's p95 tick 67 ms at 30 bots and about 130 ms from 50). Each runner files its ended bots' signatures as issues while it plays (`bb churn`, `bb failures`, [[sim-race]]). One job reads `ci/race.edn`, then `:race/runners` jobs each restore one landing set from the cache (`:race/landing-sets`, `ci/gym.edn` R1-R4: each its own seed, kept in its `level.dat`, 48 forest landings 320 apart, 112 blocks pregenerated round each, built once by `world.yml`, dispatched by hand when a set's key changes; a miss fails the job at once, and no job looks the sets up first) (runner r races on set (r-1) mod 4) and start `:race/servers` servers (default 1) on copies of it, each with its own ports (game 25565+k-1, RCON 25575+k-1) and race process racing `:race/bots` bots, recording into `data/race/s<k>`. With `--landings`, the bot with serial i lands on the set's landing offset + i - 1, wrapping (`race/landing-of`; the offset counts the runners before it on its set and the servers before it on its runner), so no locate is run and no claims are made, and every commit races the same worlds from the same landings: two commits compare landing by landing. A vanilla server ticks on one thread, at about 2 ms of its 50 ms tick per bot (capacity run 38333898855), so one server stops near 25 bots however cheap the bots are; more servers use the runner's other cores. A runner stays the machine: with more than one server, the facts say which (`--server k`: `:milestone/server`, `:sample/server`, `:race/server`), and the board shows runner/server. `test.yml` and `gym.yml` skip the race branch so every runner races. Each job's summary holds each batch's wall and the RACE lines; its artifact (`race-<sha>-<runner>`, uploaded at the job's end, the name comments gave before the evidence releases) holds the recordings (zstd), the race logs and the server logs.

## Capacity
How many racers a runner carries was measured once, by ramping a race on all twenty runners and judging each load after a two-minute settle (healthy: server p95 under 50 ms, the slowest tenth of bots at 19 ticks/s or better, none cut off, no swap). The numbers it gave are the sizes in `ci/race.edn` (bots a server, servers a runner, heaps). The race's server runs with no compression (`COMPRESSION_THRESHOLD` -1: the bots are on the same machine) and `sync-chunk-writes` off (`bb/clojurecraft/server.clj`). The race's `sampler!` writes the race's health as a fact every 15 s (`sample`: bots joined, landed, playing, ended, cut off; the playing bots' ticks per second as median, p10 and least; milestones; the server's tick times; the bots' heap). The ramp tooling is gone; it is in the history before this change.

Where the runner's CPU goes is sampled beside the race, never touching play (`bb inspect`, `bb/clojurecraft/inspect.clj`; on 2026-11-08 the servers ticked 150 to 450 ms under ten bots each). `bb inspect sample data/race/inspect` writes a line every 30 s to `cpu.edn` (`sample`: the load and run queue, the CPU split with steal, the busiest processes' share of a core with their role in the lane, `server-k`, `race-k` or `convert`, and each such JVM's busiest thread pools by name) plus `machine.txt` and a `jstat -gcutil` log per server and race JVM; `bb inspect profile` profiles each server at minutes 5 and 30 of the race (server k two minutes after k-1): `debug start`/`debug stop`, `perf start`, a 60 s JFR recording with its `jfr view` summaries and the tick query before and after, under `data/race/inspect/s<k>-min<m>/`. Every server and race JVM also logs its GCs (`-Xlog:gc`: `s<k>/server-gc.log`, `s<k>/gc.log`).

## Per-server settings (an A/B inside a lane)
A runner carries `:race/servers` servers; `ci/race.edn`'s `:race/server-overrides` maps a server's number (the 1-based `--server k`) to settings it takes instead of the defaults in `clojurecraft.servers` (`settings`, `simulation-distance`): the default is a simulation distance of 3 for every server (it beat 4 in an A/B of server 2 against server 1) and no server overrides it. `race.yml` starts each server with `SERVER_INDEX=k`, and `bb server` writes the override into `server.properties` (`server/with-overrides`); the world caches are keyed by `ci/gym.edn` and `ci/world-key.edn` alone, so the setting never rebuilds a world. Each race sample carries `:sample/simulation-distance` with its `:sample/server-tick`, and a batch's row has `:servers` (`page/servers-under`): for each server its simulation distance and the mean tick (avg, p50, p95, p99) over the samples taken with bots playing, so a round's two servers can be compared.

## Per-lane sizes (an A/B across lanes)
`ci/race.edn`'s `:race/lane-overrides` maps a lane (race.yml's runner) to race keys it takes over the file's own (`servers/for-lane`). race.yml's plan job reads the lane's sizes with `bb race-sizes <lane>` (`servers/outputs`), and the race process applies the same entry from `--runner`. Today lanes 1, 3, 6 and 8 carry one server with 6 GB of heap (what two servers had) and ten bots, the others two servers of 3 GB: each start (rods 1-4, forest 5-8) and each landing set has both arms. A lane's landings are placed by the file's own sizes (`landing-stride`), so a lane plays the same landings in either arm. Each sample carries `:sample/runner-servers`, and a batch row (and a run's combined row) has `:runner-servers` beside `:servers`, to split the tick, the passes, pearls and deaths by arm.

## Gotchas
- The first race (run 38328518058) searched plain `minecraft:forest` 40 times per bot from cells near spawn: the nearest forests were 1,000 blocks off, every search found the same two, eight bots of ten got no landing and waited `:no-log` at spawn, and one search far from any forest took 60 s in one tick and the watchdog killed the server. The race's server now runs with `max-tick-time` -1 (`MAX_TICK_TIME`), and the search is the forest tag from points 288 blocks apart, twelve at most.
- Bots join `:race/stagger-ms` apart: fresh players generating chunks at once time each other out (steve's races 610 and 611).
- `keep_inventory` is set on, as the siblings race.
- A bot whose landing fails starts where it stands; a bot that throws is a `:crashed` RESULT and the others race on.
