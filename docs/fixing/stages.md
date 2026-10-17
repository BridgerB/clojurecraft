# Reference: the stage chain and every failure reason (sections 21-22)

Part of the bot-failure manual; [AGENTS.md](../../AGENTS.md) is its entry and index. Read only the section a question needs.

## 21. Stage chain

`--until <stage>` plays every target up to it in priority order (`plan/through`; races use `nether`). A signature `[target reason]` names the target being worked: `[:ingots :boxed]` had every earlier target and stopped on the way to the ingots.

| Pri | Target (`--until`) | Done when | Plan / holds |
|---|---|---|---|
| 0 | `:escape-water` (always) | `:not-trapped-in` | `:escape` → `:swim-out` |
| 0 | `:escape-lava` (always) | `:not-trapped-in` | `:escape` → `:flee` |
| 0 | `:defend` (always, after the escapes; `defend`) | `:safe?`: no hostile within 4, no creeper within 6 (blacklisted ghosts aside) | `:defend` → `:fight`, or `:back-off` from a creeper or under 10 health (issue #259) |
| 0 | `:eat` (always, after `:defend`; `eat`) | `:fed?`: food ≥ 14, unknown, or nothing safe to eat held | `:eat` → `:eat` (issue #259) |
| 0 | `:hunt` (always, after `:eat`) | `:provisioned?`: fed, food held, or no animal a route reaches within 24 and no kill to pick up | `:hunt` → `:walk`, `:fight`, `:collect` the meat |
| 0 | `:wear-armour` (always, after `:hunt`) | `:dressed?`: no piece held over an empty armour slot | `:wear` → `:wear` |
| 13 | `:sword` (always, after `:armour`) | `:armed?`: no better sword the held items make with a handle and a table at hand | `:sword` → needs planner (craft) |
| 5 | `:attack` (aside: gym only, never chained; `attack`) | `:cleared?`: no hostile within 16 | `:attack` → `:fight` |
| 13 | `:armour` (always, after `:portal-kit`; `armour`) | `:armoured?`: no iron helmet or chestplate the spare ingots pay for, table at hand | `:armour` → needs planner (craft) |
| 1 | `:wood` | `{:tag/logs 1}` | needs |
| 2 | `:kit` (`table`) | `{:item/crafting_table 1 :item/stick 4}` | needs |
| 3 | `:pickaxe` | wooden pickaxe | needs |
| 4 | `:cobblestone` | 3 cobblestone | needs |
| 5 | `:shore` | `:escaped-water?` | `:wade` (gym only) |
| 6 | `:stone-pickaxe` | stone pickaxe | needs |
| 7 | `:furnace` | `{:block/furnace :near}` | needs |
| 8 | `:ingots` | 4 iron ingots | needs |
| 9 | `:bucket` | 1 bucket | needs |
| 10 | `:water-bucket` | 1 water bucket (surface water only) | needs |
| 11 | `:flint-and-steel` | flint and steel | needs |
| 12 | `:iron-kit` | flint and steel + water bucket | needs |
| 13 | `:portal-kit` | water bucket + empty bucket (#49) | needs |
| 14 | `:cast-one` | `:built?` | `:build :cast-one`, holds a pickaxe (#70) |
| 15 | `:portal` | `:built?` | `:build :portal`, holds a pickaxe |
| 16 | `:nether` | `:built?` (stood in until the Nether) | `:build :nether`, holds a pickaxe |
| 17 | `:fortress` | `:at-fortress?` | `:fortress` |
| 18 | `:blaze-rods` (aside for now; `blaze-rods`) | 7 blaze rods (Nether) | `:blaze-rods` → `:fight`, `:collect-drops`, `:camp`, `:tunnel-to` the spawner (issue #262) |
| 19 | `:ender-pearls` (aside for now; `pearls-12`) | 12 ender pearls | `:pearls` → `:collect` the drop, `:fight`, `:provoke` by a stare from range, `:walk` (issue #263) |
| 19 | `:ender-pearl` (aside: gym only; `pearls-1`) | 1 ender pearl | `:pearls`, as above |
| 20 | `:eyes-of-ender` (aside for now; `eyes`) | 12 eyes of ender | `:eyes` → needs planner (craft blaze powder, then eyes, 2x2), waits `:no-pearls` / `:no-rods` (issue #264) |

- `:iron-kit` takes flint and steel first so all iron is mined in one trip down and the climb out ends at the water. The cast's few placed blocks are counted from the plan (`build/held-first`, `blocks-needed`), never a constant.

| Producer | Act | Needs → provides | Holds / spares |
|---|---|---|---|
| `:gather-log` | `:gather` | → `{:tag/logs 1}` | |
| `:mine-stone` | `:mine` | → cobblestone | holds wooden pickaxe |
| `:mine-coal` | `:mine` | → coal (depth 40) | holds + spares `:tag/pickaxes` (#36) |
| `:mine-iron` | `:mine` | → raw iron (depth 16) | holds stone pickaxe; spares stone pickaxe + 4 sticks (#39) |
| `:dig-gravel` | `:mine` | → gravel (depth 40) | holds + spares `:tag/pickaxes` (#67) |
| `:flint-from-gravel` | `:redig` | gravel → flint (1 in 10) | |
| `:fill-bucket` | `:scoop` | bucket → water bucket | holds + spares `:tag/pickaxes` |
| `:place-table` | `:place` | table → `{:block/crafting_table :near}` (passage allowed; taken along) | |
| `:place-furnace` | `:place` | furnace → `{:block/furnace :near}` | |

- Tool budget that drove the spares: stairs down, tunnels and the climb out dig about 390 blocks, three stone pickaxes' worth; a stone pickaxe lasts 131 blocks; fifty stairs up dig about 150.
- To change a row: add the fixed row under a new id, move `:goal/until` to it, set `:goal/superseded-by` on the old one. Never edit or delete. The hand-written stage problem statements are in `problems.edn`; update a stage's row when it fails a new way.

---

## 22. Failure reasons

Signature = `[target reason]`. Meaning is the common case; read the raising code for your run. Past issues per reason: `gh issue list --label bot-failure --state all`; a regressed issue's closing PR is the first read.

| Reason | Usually means | Raised in | First look |
|---|---|---|---|
| `:no-path` | no route after 6 replans, or 12 unwalkable walks in a row; target chosen without asking `routable?` | `intent/run :walk`, `intent/routable?`, `plan/fail-intent`, `wood/near-walk`, `wood/climb-out`, `build/reachable-stand`, `stairs/seal` | terrain the moves lack (step beside water, snow, ledge); does the act call `routable?` |
| `:no-log` | wait ran out: no trunk to walk to, climb for or hunt toward | `wood/hunt`, `wood/gather-next`, `wood/climb-out`, `stairs/pit-rim` | treeless landing, trunks behind water, hunt bearings refused (#57) |
| `:boxed` | stairs/tunnel/climb has no safe next cell | `stairs/boxed?`, `stairs/box`, `stairs/duck`, `stone/climb`, `stone/deeper`, `stone/mine-next`, `scoop/boxed-here?` | under a lake/aquifer/sea; `:plan/boxed-at`, `duck` (#37, #61) |
| `:no-pickaxe` | dig needs a pickaxe, none held or makeable | `stairs/tool-missing`, `stone/tool-missing`, `intent/opening`, `make/spare-worth?` | spare not made in time, tools worn deep (#36, #39, #67) |
| `:rejected` | server left the block unchanged 3 times on one cell | `intent/dig-stage :confirm`, `place/place-stage :sent`, `place/give-up`, `stairs/rejected` | slow server (`intent/slowdown`), block into a body (#44), protected cell |
| `:timeout` | ran out of time busy but not failing (sim race: 330 simulated min since #252, a real bot's horizon; 90 before); real-race cuts are never filed | `main/result`, `simrace/judged` | back-and-forth loops; `probe story` on the recording |
| `:no-spot` | nowhere to put a block: no spot, niche, passage or fresh rock | `place/place-stage :spot`, `place/niche`, `place/dig-niche`, `place/ashore`, `stone/afloat-no-spot?` | aquifer dead ends, sand round the bot (#94, #148) |
| `:stair-not-taken` | stair cut but feet never reached it in 10 s | `stairs/stairs-stage :step`/`:level`, `wood/climb-out` | unbroken block, mid-jump start (`airborne?`) |
| `:no-shovel` | a staircase block needs a tool not makeable there | `stairs/tool-missing` | snow should be by hand (#42, #72) |
| `:stuck` | walk no progress 60 s; needs planner found no producer (`make/max-depth` 12, cycle); swim-out no obstacle to dig | `intent/run :walk`, `make/decide`, `make/resolve-need`, `swim` `:swim-out` | a wait `:stuck` is the needs planner (missing or cyclic producer); an intent `:stuck` is a walk or swim-out |
| `:stale-window` | third unanswered click in one window intent | `window/lost`, `window/resync` | server load (#131, #132) |
| `:no-site` | no lava site whose pool can finish the frame; lava hunt exhausted | `build/lava-hunt`, `build/site-ok?`, `plan/next-intent :build` | `choose-site`, `spent?` |
| `:no-escape` | swim-out past 2400 ticks: no bank, no floor to dig from | `swim` `:swim-out` | notch/cap rules, `dig-or-sink` |
| `:needs-tool` | no held tool harvests the block; build without a held pickaxe | `intent/choose-tool`, `intent/dig-stage :settle`, `build/held-first`, `stairs/too-hard?` | `:goal/holds` (#70) |
| `:unsafe` | dig would open water/lava (six neighbours or fluid over falling blocks) or obsidian | `intent/dig-stage :settle`, `stairs/refusals`, `stairs/seal` | was `:intent/exposes-ok?` due |
| `:target-gone` | dig/scoop target already open (air, water, lava) or no longer a source | `intent/dig-stage :settle`, `scoop/scoop-stage :aim`, `stairs/fell?` | flowed away, fell; build counts it done (`found-open?`) |
| `:not-picked-up` | collect timed out (10 s) | `intent/run :collect`, `plan/fail-intent` | drop round a corner, in water, behind a wall |
| `:no-water` | no surface water in sight/remembered, no way to cut to any | `scoop/scoop-next`, `scoop/to-bank`, `scoop/climb`, `scoop/dig-out`, `scoop/tunnel-out` | dry landing, deep under landing (#60) |
| `:no-station` | lava pour has no stand reaching a source | `build/refill`, `build/spent?`, `build/work-op` | spent pool (#142) |
| `:no-stand` | no reachable stand for a build op | `build/approach-stand`, `build/reachable-stand` | `nearest-stand-cell`, `toward-stand` (#74) |
| `:no-scoop` | bucket did not fill after 3 uses | `scoop/scoop-stage :sent` | ray blocked by a plant, not a real surface source (#83) |
| `:no-landing` | not standing at the landing within 30 s | `plan/landing-tick` | fixture or server load, not bot logic |
| `:no-ingredient` | craft/smelt could not plan clicks from the inventory | `craft` `:settle`, `smelt` `:settle`, `recipe/sources` | split stacks (#86) |
| `:no-bucket` | lava pour has no empty bucket | `build/refill` | `:portal-kit` counting (#49) |
| `:inventory-full` | no free slot to put the cursor down, and no junk to throw | `craft` `:settle`, `smelt/put-down`, `window/resync` | RESULT `:held`: what filled the inventory; `window/junk` (#172, #192) |
| `:crashed` | the run threw; RESULT has `:error` | `race/play-one!`, `simrace/play!` | read `:error` first |
| `:ledge` | no longer raised (collect over a ledge is done now) | none | closing PR of #93 |
| `:unreachable` | a fight's target has no route, or 20 s without an attack | `combat` `:fight` | fences, walls, hovering blazes; `combat/route-walk` (#262) |
| `:ghost` | 25 swings in reach and the target lives | `combat` `:fight` | stale entity, out-of-sight target |
| `:no-footing` | lava flee found no footing | `swim` `:flee` | lava sea beside the bot (#231, #230) |
| `:not-eaten` | food did not rise after eating | `eat` `:eat` | held slot changed, inedible item |
| `:not-worn` | the armour click left nothing in the slot | `armour` `:wear` | window answers, cursor |
| `:no-blaze` | no blaze in sight and no spawner remembered | `blaze` | fortress not found, spawner not seen |
| `:no-enderman` | no enderman in sight within 64 | `pearls` | wrong biome, endermen out of reach (#263) |
| `:no-shelter` | nowhere to burrow | `pearls` | soft or fluid ground |
| `:unprovoked` | a stare did not turn the enderman | `pearls` `:provoke` | line of sight, distance ≤ 4 |
| `:no-pearls` / `:no-rods` | eyes wanted with an input short | `eyes` | the stage before it |

Other build/fortress reasons: `:stray-lava`, `:no-effect`, `:not-yet`, `:unverified`, `:no-support`, `:blocked`, `:no-footing`, `:shallow`, `:tool-broke`, `:out-of-reach`, `:not-held`, `:no-answer`, `:no-window`, `:no-table-window`, `:wrong-result`, `:no-result`, `:not-taken`, `:not-loaded`, `:out-of-fuel`, `:not-cooked` (see section 19 and 20.7).

---
