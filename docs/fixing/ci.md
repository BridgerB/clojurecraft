# Reference: tests you cannot run, the CI map, Hickey patterns (sections 23-25)

Part of the bot-failure manual; [AGENTS.md](../../AGENTS.md) is its entry and index. Read only the section a question needs.

## 23. Tests you cannot run

You never run a test locally; the PR's `unit / unit` runs them all in 2-4 minutes. Read the neighbouring tests first and copy their shape.

| Test namespace | Holds |
|---|---|
| `<ns>_test.clj` | one per source namespace |
| `plan_test.clj` | planner end to end over canned events; scenarios `shaft`, `quarry`, `crevice`, `canopy-pocket`, `sky-shaft` |
| `sim_test.clj` | whole bot against the model in small built worlds; largest; holds the forest property |
| `props_test.clj`, `make_test.clj` | test.check properties (keep-alives, phase, `step` total, needs never pick unmet goals, FINISH never early) |
| `issues_test.clj` | `every-snapshot-fits-on-github`, `an-issue-keeps-two-snapshots-at-most` (judging is the PR check's `--judge`) |
| `recordings_test.clj` | promoted recordings replay with every effect verified |
| `model_test`, `docs_test`, `style_test` | keys in `model.clj`; docstrings; no `if` nested in `if` |
| `packet_test`, `packet_specs_ever.edn` | decoding; `packet-specs-only-grow` |
| `problems_test` | `problems.edn` a row per stage |
| `fixtures.clj`, `world.clj`, `runner.clj` | helpers, `fixtures/instrument!`, the eftest runner |

- Build the smallest world: `clojurecraft.world/column` takes `{[x y z] state}`; states from `(blocks/default-state :stone)`, `blocks/air`, `blocks/water-base`, `blocks/lava-base`. Reuse each test ns's builders (`scoop_test`: `standing`, `pond`, `cave-pool`, `mine`; `build_test`: `open-ground`, `standing-at`, `ops-of`, generators `gen-origin`/`gen-facing`/`gen-height`; `plan_test`: `world-state`, `run`, `remembered`, `walking`).
- A decision function's world needs only the keys its selection `fdef` lists (`spec/requires`). Missing chunks read as solid.
- Name the test as a sentence of the behaviour; top comment names the issue and run; assert the decision (intent kind/target, wait reason, site or nil), not which helper ran. It must fail without the fix: reason it against the old code, or push the test alone first and let CI show it red. Add it to the brain note's `sourceRefs` (`test/clojurecraft/<ns>_test.clj#<name>`).
- **CI's context is not a scoped probe's.** test.yml's forest job runs `clojure -M:test -v <var>` with no `-n`, so every test namespace loads and `fixtures/instrument!` instruments the reducers' fdefs; a probe run with `-n` (or a script) runs uninstrumented and can pass where CI fails. Reproduce a red CI job with the job's exact command (read it from `.github/workflows/test.yml`), not a narrower one.
- The forest job's seed is fresh every push (`FOREST_SEED` = run id + shard, printed on failure); reproduce with that seed.
- Red on something you did not touch: another test asserts the behaviour you changed (update it only if it asserts the old wrong behaviour, and say so in the PR); an older issue's snapshot recurs (your rule conflicts with its fix: read its PR); `model_test`/`docs_test` (missing model row, spec or docstring).
- The sim is deterministic by seed in the same context; a failure that does not reproduce in a probe is usually a context difference (instrumentation, worlds restored, namespaces loaded), not flakiness.

---

## 24. CI map

| Workflow | Trigger | Does | Artifacts |
|---|---|---|---|
| `fleet.yml` | always on (hands over every 340 min), hourly backstop | keeps lanes in every free runner, cancels the newest when another job waits 90 s (floor 2+2), replaces lanes on replaced code | log lines `FLEET ...` |
| `race.yml` | dispatched by the fleet, one lane a run (`race lane k`) | up to 8 lanes × 2 servers × 15 slots on pregenerated landings, refilled, 330 min; `bb churn batch` every 20 min | `race-<sha>-<runner>`: `s1/`,`s2/` with `race.log`, `server.log`, `samples.edn.zst`, `milestones.edn.zst`, a recording per bot; `snapshots/` with the real snapshots, `gaps/` and `convert-<n>.log` |
| `simrace.yml` | same (`simrace lane k`) | up to 8 lanes, bot against the model in captured worlds, every core | `simrace-<sha>-<runner>`: `runs.edn`, `simrace.log`, `snapshots/` |
| `test.yml` | every push, any branch | `unit` (via `unit.yml`), one forest shard of 250 (dispatch: 8), actionlint | none |
| `unit.yml` | called by `test.yml`, `issue.yml` | every test but the forest property (`bb test`), then `bb check` | none |
| `issue.yml` | PR from `fix/*` | `check`: the issues the PR names; `sim` issues must have snapshots; `--judge` on base (must recur) and head (must not), only their worlds restored (15 min cap, a few in practice); plus `unit / unit` | step summary: JUDGE and STORY lines |
| `probe.yml` | hand | one command on a branch with the sim worlds asked for | `probe-<id>`: files under `out/` |
| `gym.yml` | hand | N trials of one goal on real servers, judged by RESULT + truth commands | recordings and logs |
| `world.yml`, `simworld.yml` | hand when a key changes | build a landing set's world / capture sim worlds and each set's Nether once | caches |
| `pages.yml` | each report | builds the results site from main over `gh-pages`'s data, adds new gym runs, deploys | Pages |
| `plan.yml`, `timer.yml`, `capacity.yml`, `full.yml` | hand or schedule | plan files, capacity study, one fresh-world attempt | their own |

- A push does not judge snapshots; the PR's check does, or a probe with `--judge` before the PR.
- A code merge to `main` cancels and restarts the rounds (they keep artifacts and run a last report).
- Artifacts last 7 days; job logs are readable only when the job ends.
- Required on `main`: `unit / unit` and `check`; `check` "skipping" on a non-`fix/*` PR counts as passing. A PR with merge conflicts runs no checks.
- 20 runners: the fleet holds one, the lanes up to 16, three stay free; anything more waits 90 s, then the newest lane is given back. Queued is not failed.

---

## 25. Hickey patterns

- One value, one writer: only `main` swaps the atom. A fix never adds an atom, a changing `def`, or a side table.
- Reducers compose: `game/step` then `plan/step` (`game/compose`); tests compose them the same way (`plan_test`).
- Effects are data on `:bot/effects`, logging too (`game/say`). Time and randomness are event inputs; deadlines are absolute ms in the world (`:intent/finish-at`) against `:time/now`, or tick counts. Never sleep.
- Open dispatch: `game/on-packet` `[phase name]`, `intent/run` `:intent/kind`, stage multimethods (`intent/dig-stage`, `stairs/stairs-stage`, `scoop/scoop-stage`, `place/place-stage`), `plan/done-by`/`act`/`next-intent`. New behaviour = new `defmethod`, usually in the namespace owning the subject.
- Goals and builds are data; nothing records build progress, the first unsatisfied op is worked. Remember via a fact or a namespaced world key (`:plan/boxed-at`), never a hidden field.
- Rules as small functions whose docstring states the rule and the run that taught it; constants are top-level `def`s with a comment (`(def max-turns 4) ; refusals before a level step, or :boxed`).
- Accretion: supersede goal rows, add attributes beside old ones, append packet fields.
- I/O fenced last (`conn`, `rcon`, `main`, `record`, `watch`, `replay`, `race`, `harness`); a bot fix touches none of them.
