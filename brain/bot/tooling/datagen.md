---
title: Datagen
description: How scripts/datagen.sh and datagen.clj produce the six EDN tables - four from the vanilla --reports output, two (recipes, item tags) from the inner server jar - and how to regenerate them.
type: reference
tags: [bot, tooling, datagen, data]
aliases: [datagen.clj, scripts/datagen.sh, reports, inner jar, regenerate edn]
status: verified
lastUpdated: 2026-10-09
verifiedAgainst: 5c7d6c1
sourceRefs:
  - dev/clojurecraft/datagen.clj#defn- inner-jar-entries
  - dev/clojurecraft/datagen.clj#defn item-tags
  - dev/clojurecraft/datagen.clj#defn recipes
  - dev/clojurecraft/datagen.clj#defn blocks
  - scripts/datagen.sh#-DbundlerMainClass=net.minecraft.data.Main
related:
  - "[[bot/tooling/_moc|Tooling]]"
  - "[[mc-recipes-in-jar]]"
  - "[[recipe-table]]"
  - "[[blocks-tables]]"
---

# Datagen

`scripts/datagen.sh` downloads the 26.1.2 server jar (sha1-checked) to `data/local-server/`, runs the vanilla data generator once (`java -DbundlerMainClass=net.minecraft.data.Main -jar server.jar --reports`) into `data/reports/`, then runs `clojure -M:datagen <reports> resources/clojurecraft <jar>`.

## Outputs
| File | Source | Function |
|---|---|---|
| `packets.edn` | `reports/packets.json` | `packets`: `{state {:s2c {kebab-name id} :c2s {...}}}` |
| `blocks.edn` | `reports/blocks.json` | `blocks`: `[name type min-state max-state]` sorted by state id |
| `items.edn` | `reports/registries.json`, `minecraft:item` | `registry`: `{name protocol-id}` |
| `entity-types.edn` | `reports/registries.json`, `minecraft:entity_type` | `registry` |
| `item-tags.edn` | inner jar `data/minecraft/tags/item/*.json` | `item-tags`: nested `#tag` references resolved recursively, cycles cut |
| `recipes.edn` | inner jar `data/minecraft/recipe/*.json` | `recipes`: shaped and shapeless crafting only, ingredients resolved to item sets |

## How it works
1. `inner-jar-entries` streams the outer jar until `META-INF/versions/<v>/server-<v>.jar`, reads it into memory, and keeps every entry matching `data/minecraft/(recipe/*|tags/item/**).json`.
2. Packet names become kebab-case keywords; block, item and tag names keep their underscores.
3. Each file is written one row or entry per line under a "generated ... do not edit" header.

## Gotchas
- Recipes and item tags are **not** in the `--reports` output; without the jar argument only the four report tables are written.
- The data generator is run only when `data/reports/reports/blocks.json` is missing; delete `data/reports` to force a rerun after a version bump.
- `data/` is gitignored; the EDN tables in `resources/` are the committed artefact. Never hand-edit them.

## See also
- [[mc-recipes-in-jar]] - where vanilla keeps them.
