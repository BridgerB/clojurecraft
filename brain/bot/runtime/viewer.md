---
title: Viewer
description: How --viewer <port> serves a browser 3D view of the bot's world and thoughts from a watch on the atom - the blocks round it, itself, the entities, the intent chain - with the game's own textures read from the local client jar into a gitignored cache.
type: reference
tags: [bot, runtime, viewer, watch, dashboard]
aliases: [--viewer, viewer.clj, serve!, frame, column-view, render-def, 3D view, thinking panel]
status: verified
lastUpdated: 2026-10-31
verifiedAgainst: 881f595
sourceRefs:
  - src/clojurecraft/viewer.clj#defn serve!
  - src/clojurecraft/viewer.clj#defn frame
  - src/clojurecraft/viewer.clj#defn column-view
  - src/clojurecraft/viewer.clj#defn render-def
  - src/clojurecraft/viewer.clj#defn read-assets!
  - src/clojurecraft/main.clj#(viewer/serve! world* (Long/parseLong viewer) (or viewer-jar viewer/default-jar))
  - test/clojurecraft/viewer_test.clj#a-column-is-its-blocks-with-the-overlay-over-them
  - resources/clojurecraft/viewer/index.html#function meshSection
related:
  - "[[bot/runtime/_moc|Runtime]]"
  - "[[telemetry-watch]]"
  - "[[main-loop]]"
---

# Viewer

`clojure -M:run ... --viewer 4600` serves `http://127.0.0.1:4600/`: a three.js view of the blocks within about 48 blocks of the bot (7x7 columns), the bot itself with its look ray, every entity within 64 blocks as a labelled box (red hostile, green passive, yellow item), wire boxes on the intent's and its children's target cells, and a panel of what it is thinking: the goal (or the interrupt that took the focus), the intent, each child down the chain, a wait, the last failure, the chain a fresh run plays with what is reached, the hotbar with item icons, health and food. Two cameras: an orbit that follows the bot, and the bot's own eyes.

It is an observer in the [[telemetry-watch]] sense: nothing in `game`, `plan` or `intent` mentions it and it never writes the atom.

## How it works
1. `serve!` adds a watch that offers each new world to a one-slot sliding channel (it never waits); a thread of its own turns the latest into a `frame` at most every 100 ms and keeps it with that world. HTTP threads (JDK `HttpServer`, four) answer only from what the thread kept.
2. `frame` is pure: pose, look, health, food, hotbar, entities, `intent-view` of `:plan/intent` (and `:plan/failed`), `:plan/reached`, the wait, and `columns`: a version per loaded column round the bot, the column's identity plus the hash of the block overlay over it, so it moves exactly when the column is replaced or a block in it changes.
3. The page polls `/frame` and fetches `/column?x=&z=` again when a version moves. `column-view` is pure: every section that is not all air, each cell its index into a palette of block names (one byte, two over 256 names), base64, with `:world/blocks` applied over the chunk.
4. `/defs` (once) is `render-def` of every block: the first blockstate variant's model, followed up its parents, as a box with a texture a face (the first element's faces over the box of all elements), a cross for plants, a liquid for water and lava, or nothing. Tinted faces take a fixed plains colour; the bot does not know biomes. `/meta` is the chain (`plan/through` the current stage) and the always rows with their docs.
5. The page meshes each 16-cube section, dropping a face against an opaque full cube or against the same liquid or glass, into one opaque (alpha-tested) and one water mesh, a few sections a frame. Textures are tiles in one canvas atlas, drawn as their PNGs arrive.

## Textures
`read-assets!` reads every blockstate, model and item definition from the client jar (`--viewer-jar`, by default Prism's `26.1.2` under `~/Library/Application Support/PrismLauncher/libraries/com/mojang/minecraft/`) and extracts the block and item textures to `cache/viewer/26.1.2/textures/`, which git ignores. No Mojang asset is committed. Without the jar the page draws grey tiles.

## Gotchas
- A tile whose PNG never loaded is transparent, and the opaque material's alpha test then discards the whole face: the first version drew nothing for that reason (a wrong cache path). A failed load now fills the tile grey.
- Logs are drawn upright whatever their axis, stairs as full cubes, a grass side without its tinted overlay: the first variant and the first element are a choice of simplicity.
- The page loads three.js 0.170.0 from jsDelivr; offline it shows the panel's HTML only.

## See also
- [[telemetry-watch]] - the other watch on the atom.
- [[main-loop]] - the swap both observe.
