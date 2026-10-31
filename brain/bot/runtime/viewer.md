---
title: Viewer
description: How --viewer <port> serves a browser 3D view of the bot's world and thoughts from a watch on the atom, drawn by steve's renderer (vendored) from the game's own models and textures read out of the local client jar, with a thinking panel over it.
type: reference
tags: [bot, runtime, viewer, watch, dashboard]
aliases: [--viewer, viewer.clj, serve!, frame, column-bytes, assets-message, 3D view, thinking panel, typecraft viewer]
status: verified
lastUpdated: 2026-10-31
verifiedAgainst: 881f595
sourceRefs:
  - src/clojurecraft/viewer.clj#defn serve!
  - src/clojurecraft/viewer.clj#defn frame
  - src/clojurecraft/viewer.clj#defn column-bytes
  - src/clojurecraft/viewer.clj#defn assets-message
  - src/clojurecraft/viewer.clj#defn plain-textures
  - src/clojurecraft/viewer.clj#defn grass-colour
  - src/clojurecraft/viewer.clj#defn biome-names
  - src/clojurecraft/chunk.clj#defn read-section-with-biomes
  - src/clojurecraft/viewer.clj#defn read-assets!
  - src/clojurecraft/main.clj#(viewer/serve! world* (Long/parseLong viewer) (or viewer-jar viewer/default-jar))
  - dev/viewer/build.ts#const babylonGlobal
  - dev/clojurecraft/datagen.clj#defn block-states
  - test/clojurecraft/viewer_test.clj#a-column-goes-out-in-the-network-format
  - resources/clojurecraft/viewer/index.html#function applyAssets
related:
  - "[[bot/runtime/_moc|Runtime]]"
  - "[[telemetry-watch]]"
  - "[[main-loop]]"
---

# Viewer

`clojure -M:run ... --viewer 4600` serves `http://127.0.0.1:4600/`: the world within about 100 blocks of the bot (13x13 columns) as Minecraft draws it, through the bot's own eyes or an orbit round it (drag, wheel), the bot as a player model in the orbit, the other entities, wire boxes on the intent's and its children's target cells, and a panel of what it is thinking: the goal (or the interrupt that took the focus), the intent, each child down the chain, a wait, the last failure, the chain a fresh run plays with what is reached, the hotbar with item icons, health and food.

It is an observer in the [[telemetry-watch]] sense: nothing in `game`, `plan` or `intent` mentions it and it never writes the atom.

## The renderer is steve's
`resources/clojurecraft/viewer/web/viewer.js` and `worker.js` are typecraft's viewer, the one steve's dashboard draws with (vanilla block models and blockstates, a texture atlas with per-face UVs, biome tints, ambient occlusion, transparency, sky, clouds, entity boxes and the player model), bundled by `dev/viewer/build.ts` from a steve checkout without Babylon, which the page loads from jsDelivr (`babylonjs@9.13.0`, global `BABYLON`). A first, hand-written three.js mesher looked nothing like the game; the bar is parity with steve's view.

It is fed what steve's server fed it:
- `/assets.json` is `assets-message`: every blockstate and block model from the jar, every block texture as base64, the biomes by id (`biome-names`: the jar's biome files sorted, the server registry's order), each biome's sky colour, the tints (grass and leaves by biome as vanilla works them out, `grass-colour` and `foliage-colour`: the biome's own colour, else the jar's colormap at its temperature and downfall, then a swamp's or dark forest's modifier; water by biome; birch and spruce leaves fixed; redstone by power), and the block registry (`registry-blocks`: name, first state id, the properties from the generated `block-states.edn` in report order, whether it collides, and `transparent` from `opaque-cube?`, a guess from the models that differs from steve's flag on 11 of 1168 blocks).
- `/column?x=&z=` is `column-bytes`: the column in the network format the renderer's `loadChunkColumn` reads (two counts a section, the block container without array lengths, then a biome container), the block overlay applied, each section with its own biome container: `chunk/decode` keeps them as `:chunk/biomes`, which nothing in the bot reads. A column without them (the sim model's) is sent as plains.

## How it works
1. `serve!` adds a watch that offers each new world to a one-slot sliding channel (it never waits); a thread of its own turns the latest into a `frame` at most every 100 ms and keeps it with that world. HTTP threads (JDK `HttpServer`, four) answer only from what the thread kept.
2. `frame` is pure: pose, look, health, food, hotbar, entities, `intent-view` of `:plan/intent` (and `:plan/failed`), `:plan/reached`, the wait, and `columns`: a version per loaded column round the bot, the column's identity plus the hash of the block overlay over it, so it moves exactly when the column is replaced or a block in it changes.
3. The frame carries the biome id under the feet (`biome-at`); the page sets the sky to that biome's colour and a linear fog in it from 55% of the loaded radius to its edge, so the edge of the known world fades as it does in the game.
4. The page polls `/frame` every 100 ms, eases the camera and entities toward it as steve's embed does, and fetches a column again when its version moves, nearest first, four at a time, so the ground under the bot draws within seconds and the horizon after (all 169 columns at once drew the deep sections first and showed cut-away stone for most of a minute).

## Gotchas
- 26.x models write some texture variables as `{"sprite" ref "force_translucent" true}` (glass, panes, redstone dust); steve's renderer was made against older models and threw on them (`n.charAt is not a function`), drawing nothing. `plain-textures` flattens them.
- Yaw and pitch go to the renderer in typecraft's radians: yaw = pi - radians(degrees), pitch = -radians(degrees).
- The jar is read at start (`--viewer-jar`, by default Prism's `26.1.2`); item textures and the player skin go to `cache/viewer/26.1.2/textures/`, which git ignores. No Mojang asset is committed.
- To refresh the vendored renderer: `pnpm add esbuild@0.27.7 buffer@6.0.3` in a scratch dir, then `node dev/viewer/build.ts <steve checkout> <scratch>/node_modules`.

## See also
- [[telemetry-watch]] - the other watch on the atom.
- [[main-loop]] - the swap both observe.
