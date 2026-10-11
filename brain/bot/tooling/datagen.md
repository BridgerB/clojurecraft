---
title: Datagen
description: How bb datagen and datagen.clj produce the six EDN tables - four from the vanilla --reports output, two (recipes, item tags) from the inner server jar - and how to regenerate them.
type: reference
tags: [bot, tooling, datagen, data]
aliases: [datagen.clj, bb datagen, gen_tables.clj, scripts/datagen.sh, reports, inner jar, regenerate edn]
status: verified
lastUpdated: 2026-10-11
verifiedAgainst: HEAD
sourceRefs:
  - dev/clojurecraft/datagen.clj#defn inner-jar-entries
  - dev/clojurecraft/datagen.clj#defn item-tags
  - dev/clojurecraft/datagen.clj#defn recipes
  - dev/clojurecraft/datagen.clj#defn blocks
  - bb/clojurecraft/gen_tables.clj#"-DbundlerMainClass=net.minecraft.data.Main"
  - bb/clojurecraft/gen_tables.clj#defn jars!
  - dev/clojurecraft/jar_probe.clj#defn -main
  - dev/clojurecraft/datagen.clj#defn hardness
related:
  - "[[bot/tooling/_moc|Tooling]]"
  - "[[mc-recipes-in-jar]]"
  - "[[recipe-table]]"
  - "[[blocks-tables]]"
---

# Datagen

`bb datagen` downloads the 26.1.2 server jar (sha1-checked) to `data/local-server/`, runs the vanilla data generator once (`java -DbundlerMainClass=net.minecraft.data.Main -jar server.jar --reports`) into `data/reports/`, then runs `clojure -M:datagen <reports> resources/clojurecraft <jar>`.

## Outputs
| File | Source | Function |
|---|---|---|
| `packets.edn` | `reports/packets.json` | `packets`: `{state {:s2c {kebab-name id} :c2s {...}}}` |
| `blocks.edn` | `reports/blocks.json` | `blocks`: `[name type min-state max-state]` sorted by state id |
| `items.edn` | `reports/registries.json`, `minecraft:item` | `registry`: `{name protocol-id}` |
| `entity-types.edn` | `reports/registries.json`, `minecraft:entity_type` | `registry` |
| `item-tags.edn` | inner jar `data/minecraft/tags/item/*.json` | `item-tags`: nested `#tag` references resolved recursively, cycles cut |
| `recipes.edn` | inner jar `data/minecraft/recipe/*.json` | `recipes`: shaped and shapeless crafting only, ingredients resolved to item sets |
| `harvest.edn` | inner jar `data/minecraft/tags/block/{mineable/*,needs_*_tool}.json` | `harvest`: `{block {:tool kind :needs tier}}` |
| `hardness.edn` | the game's classes, by `clojurecraft.jar-probe` | `hardness`: `BlockState.getDestroySpeed` per block; throws when a block has none |
| `materials.edn` | the game's classes, by `clojurecraft.jar-probe` | `materials`: every `ToolMaterial`'s speed, durability and harvest tag |
| `fuel.edn` | the game's classes, by `clojurecraft.jar-probe` | `fuel`: `FuelValues`' burn ticks per item, with the vanilla data pack's item tags bound first (fuel is partly given by tag) |
| `smelting.edn` | inner jar `data/minecraft/recipe/*.json` of type smelting | `smelting`: input item set, result, count, cooking ticks |
| `drops.edn` | inner jar `data/minecraft/loot_table/blocks/*.json` | `drops`: per block, the `[item chance]` tries of its first pool for a breaker with no enchanted tool (tool-gated entries and pools skipped) |
| `menus.edn` | `reports/registries.json`, `minecraft:menu` | `registry`: menu type ids (12 crafting, 14 furnace) |

## How it works
0. `bb datagen` (`bb/clojurecraft/gen_tables.clj`) first unzips the inner jar and the bundler's `META-INF/libraries/*` and runs `clojurecraft.jar-probe` in a JVM with them on the classpath: it boots the game's registries (`Bootstrap/bootStrap`) and writes hardness and materials as JSON for the step below. The inner jar is not obfuscated, so the classes are named as in the source.
1. `inner-jar-entries` streams the outer jar until `META-INF/versions/<v>/server-<v>.jar`, reads it into memory, and keeps every entry matching `data/minecraft/(recipe/*|tags/(item|block)/**).json`.
2. Packet names become kebab-case keywords; block, item and tag names keep their underscores.
3. Each file is written one row or entry per line under a "generated ... do not edit" header.

## Gotchas
- Recipes, tags, hardness and materials are **not** in the `--reports` output; without the jar argument only the four report tables are written, and without the probe's JSON hardness and materials are not.
- The data generator is run only when `data/reports/reports/blocks.json` is missing; delete `data/reports` to force a rerun after a version bump.
- `data/` is gitignored; the EDN tables in `resources/` are the committed artefact. Never hand-edit them.

## See also
- [[mc-recipes-in-jar]] - where vanilla keeps them.
