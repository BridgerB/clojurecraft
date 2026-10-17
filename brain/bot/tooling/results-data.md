---
title: Results data and site
description: Why the results page's data is date-partitioned JSON on gh-pages with a small all-time summary (not one EDN file, SQLite over HTTP ranges or DuckDB over Parquet), how a publish survives eight writers, and how pages.yml builds the site from main.
type: decision
tags: [bot, tooling, race, page]
aliases: [results page, gh-pages, summary.json, data/days, data/batches, gyms.ndjson, agents.json, bb site, pages.yml, rounds.edn]
status: verified
lastUpdated: 2026-10-16
verifiedAgainst: HEAD
sourceRefs:
  - bb/clojurecraft/page.clj#defn changes
  - bb/clojurecraft/page.clj#defn publish-change
  - bb/clojurecraft/page.clj#defn with-batches
  - bb/clojurecraft/page.clj#defn totals
  - bb/clojurecraft/page.clj#defn with-day
  - bb/clojurecraft/page.clj#defn update-branch!
  - bb/clojurecraft/page.clj#defn publish-rounds!
  - bb/clojurecraft/churn.clj#defn read-held
  - bb/clojurecraft/site.clj#defn road
  - bb/clojurecraft/site.clj#defn gym-summary
  - bb/clojurecraft/site.clj#defn by-whom
  - bb/clojurecraft/site.clj#defn legacy!
  - test-bb/clojurecraft/page_test.clj#two-writers-in-either-order-keep-both-batches
  - test-bb/clojurecraft/page_test.clj#the-legacy-file-is-absorbed-whole-then-removed
related:
  - "[[race]]"
  - "[[sim-race]]"
  - "[[gym-on-runners]]"
  - "[[bot/tooling/_moc|Tooling]]"
---

# Results data and site

The results page (https://bridgerb.github.io/clojurecraft/) answers one question: are the bots getting closer to the dragon, and what is in the way. Its data is every batch every race lane reports (`bb churn`, every 20 minutes a lane, 16 lanes: about 1,000 to 1,500 batches a day, ~1.8 KB each).

## The decision: date-partitioned JSON with a small summary

On the `gh-pages` branch (`bb/clojurecraft/page.clj`):

- `data/batches/<day>.ndjson`: every batch of the runs that started that UTC day, a JSON line each, whole. The record; nothing is cut to a head.
- `data/days/<day>.json`: the day's runs (one row a lane run and kind, its batches combined) and its totals per kind, rebuilt from the day's batches.
- `data/summary.json`: every day's totals per kind (runs, reached per milestone, stops by stage, times, deaths, top failures with their issues), oldest first. The page's first load.
- `data/gyms.ndjson`: a line per gym run, from its summary artifact (`bb site gyms`).
- `orchestrator.json`: the Copilot orchestrator's state (`bb copilot dispatch`), unchanged.

A day belongs to the day its run started, so a lane run is never split across files. A publish rewrites one day's two files and the summary (`changes`), so a write's cost does not grow with the history.

Why not the others:

- **One big file (the old `rounds.edn`).** Every publish rewrote 3 MB and rebuilt the page from all of it; the page showed only the newest 40 rows because drawing more was too heavy; it grows by ~2 MB a day, and phones would load all of it. Its cost grows without end.
- **SQLite in the browser over HTTP range requests (sql.js-httpvfs).** Reads well (a query fetches only the pages it needs, and GitHub Pages serves ranges), but the database is a binary file rewritten by every writer: git cannot merge two lanes' writes, so a conflict means rebuilding the whole database, and a binary that changes in place deltas badly, so the branch grows by about a copy per push. It also needs a wasm runtime and a worker on the page, and the library has had no release since 2024.
- **DuckDB-WASM over Parquet.** Strong for analytics, but the runtime is ~2.5 MB of wasm before the first chart (slow first paint on a phone), and Babashka cannot write Parquet: the writers would need a JVM library or a build step the lanes do not have.
- **Partitioned JSON** is what Babashka writes with cheshire and the browser reads with `fetch`, text that git deltas well, small per write, mergeable by recomputing, and cheap to load: the summary is a few KB a day; the history loads one day's file (~100-200 KB) when it is opened.

Limits it lives within: a Pages site may be 1 GB and a pushed file 100 MB; Pages has a soft 100 GB a month of bandwidth; a custom Actions workflow is not held to the 10 builds an hour. At ~2-3 MB a day of batches the site reaches 1 GB in about a year; the valve then is to leave old `data/batches/` out of the site (`bb site build` copies `data/`) and keep them only on the branch, since the page reads `days/` and `summary.json`.

## Many writers

Up to sixteen lanes publish, and the deploy writes `gyms.ndjson`. A publish (`update-branch!`) works the branch in its own worktree from a depth-1 fetch (the lanes' checkouts are shallow), applies its change, commits and pushes; a push another writer won is fetched again and the change recomputed on the new tip (`publish-change` reads what the branch holds then), up to eight tries with a random wait growing between. A batch is kept by its key (run, kind, runner, batch), so a retry or a republish replaces rather than repeats it (`with-batches`), and the day files and the summary depend only on the batches, not on the order writers came (`two-writers-in-either-order-keep-both-batches`). A publish that never gets through hands its rounds back: `bb churn` keeps them in the lane's `.pages-held.edn` and sends them with its next batch (`read-held`), so a batch is never lost to a busy branch.

The old layout migrates itself: the first publish of this code finds `rounds.edn`, lays every line out as batches in their days, and removes it and the pages built beside it (`changes`, `the-legacy-file-is-absorbed-whole-then-removed`). A lane still on the old code may write `rounds.edn` again before the fleet replaces it; the next publish absorbs that too. The git history of `gh-pages` keeps the old file.

## The site

The branch holds only data. `pages.yml`, dispatched by every publish, checks out main, adds new gym runs (`bb site gyms`), checks out the branch's tip, builds the site (`bb site build`: `index.html` with the road as JSON in it, `site/app.js`, `site/style.css`, the branch's `data/`) and asks GitHub for the bot-failure issues and the pull requests that fix them (`bb site agents`, `data/agents.json` in the site only, with the workflow's token, so the page spends none of a viewer's 60 unauthenticated API calls an hour on it). The page is therefore always main's code. A branch still in the old layout is laid out in the site alone (`legacy!`).

The page (`site/app.js`, no library) has four tabs: the road (every stage from spawn to the dragon: the race's chain from `goals.edn` up to `ci/race.edn`'s `:race/until`, then `ci/road.edn`'s stages the race does not play yet; each stage's reach in the window, real and sim, its 14-day line, times, stops with their issues, open issues, gym proof; the wall), trends (reach per milestone by day, stops by phase, time to each milestone, runs and deaths a day, issues opened and closed), history (every day, its lane runs filterable by kind, commit and milestone, paginated, each with its funnel, stops, leader and links) and agents (the orchestrator's live state from `orchestrator.json`, then the issues and fix pull requests over time). The live lane count is one unauthenticated API call, kept two minutes in the viewer's session.

## Sources

- GitHub Pages limits (1 GB site, 100 GB/month soft bandwidth, 10 builds/hour not applying to Actions deploys): https://docs.github.com/en/pages/getting-started-with-github-pages/github-pages-limits
- sql.js-httpvfs, SQLite over HTTP ranges on GitHub Pages: https://phiresky.github.io/blog/2021/hosting-sqlite-databases-on-github-pages/ and https://github.com/phiresky/sql.js-httpvfs
- DuckDB-WASM's size and model: https://duckdb.org/2021/10/29/duckdb-wasm.html
- Parquet layout for DuckDB (row groups of 100K-1M rows): https://duckdb.org/docs/guides/performance/file_formats
