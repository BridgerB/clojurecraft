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
  - src/clojurecraft/viewer.clj#defn dig-view
  - src/clojurecraft/viewer.clj#defn care
  - src/clojurecraft/viewer.clj#defn route-view
  - src/clojurecraft/viewer.clj#defn held-view
  - src/clojurecraft/viewer.clj#defn looked-at
  - src/clojurecraft/viewer.clj#defn arm-moves
  - dev/viewer/loop.sh#one bot plays the whole chain from spawn
  - src/clojurecraft/viewer.clj#defn biome-names
  - src/clojurecraft/chunk.clj#defn read-section-with-biomes
  - src/clojurecraft/viewer.clj#defn read-assets!
  - src/clojurecraft/main.clj#(viewer/serve! world*
  - src/clojurecraft/viewer.clj#defn note-focus
  - src/clojurecraft/viewer.clj#defn act!
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

`clojure -M:run ... --viewer 4600` serves `http://127.0.0.1:4600/`: the world within about 100 blocks of the bot (13x13 columns) as Minecraft draws it, through the bot's own eyes and nothing else (an orbit only behind `?orbit=1`, for debugging; `?embed=1` hides the panel), with the first-person hand or the held item, its swing, the cracks on the block it breaks and a thin outline on the block under the crosshair, the other entities, and a panel of what it is thinking: the goal (or the interrupt that took the focus), the intent, each child down the chain, a wait, the last failure, the chain a fresh run plays with what is reached, the hotbar with item icons, health and food.

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

## The panel
The right 40% is the panel `data/local-runs/panel.ts` was, read from the frame instead of RCON and logs: its mind top to bottom (safety first, the plan's stage with the chain as dots, why this action, doing, this step), the focus changes newest first with "changed its mind: new target" (`note-focus`, kept by the frame thread, the last 60), armour, nearby entities, and the flagged notes; over the view, the game's hotbar and inventory grids from `:inventory` (every slot the bot holds). Pause and Resume freeze and unfreeze the server's ticks over RCON (`--viewer-rcon-port`, `--viewer-rcon-pass-file`; `act!`); Flag appends a note with the bot's state to the recording's `.notes.ndjson` (`data/viewer-notes.ndjson` without one).

## First person, from the bot's own data
- The held item is `hotbar[held]`, the bot's own inventory, drawn as vanilla's ItemInHandRenderer draws it, op for op in Minecraft's view space (this camera's local space): the swing's bob, applyItemArmTransform, applyItemArmAttackTransform, the item model's `display.firstperson_righthand` from the jar (`held-view`, `/held`), then -0.5; a sprite unmirrored, a block a cube. The empty hand is renderPlayerArm with the model's right arm. The hand has its own camera framed as a 16:9 window at 70 degrees: in the 60%-wide view the item otherwise sat off the right edge and showed only a sliver of handle, which read as "backwards".
- A swing plays (300 ms, vanilla's 6 ticks) for each arm packet the bot sends: the watch counts `:swing`, `:attack`, `:use-item` and `:use-item-on` among the effects the loop is about to perform (`arm-moves`), the frame's `:swings`. A dig swings every `swing-every`, so digging swings continuously as in the game.
- The cracks are `destroy_stage_0..9` from the jar, multiplied over the block, at the stage `floor(10 x progress)`, where `dig-view`'s progress is the time since the dig's START over its own `:intent/dig-ms` (dig/dig-time with the held tool and the penalties then).
- The outline is the first cell along the eyes' ray within 4.5 blocks that is neither air nor a fluid (`looked-at`).

## What it cares about, and its route
Each entity in the frame carries `:cares`, why the bot cares about it by its own rules (`care`): an intent's target, a threat within combat's defend or creeper radius, a hostile within hunt's forage-wary, prey of hunt's game kinds while hungry, endermen on the pearls stage, blazes on the rods stage, drops at a collect's cell. The radar shows only those. `:route` and `:route-kind` come from the innermost intent with a route (`route-view`): a walk's waypoints from `:intent/at` to its target (cyan); a staircase's stairs ahead as its executor cuts them, a block along `:intent/dir` and one down or up each, to `:intent/to-y`, at most 24 (orange); a tunnel's step from `:intent/from` by `:intent/move`, then its target (orange: beyond the step the tunnel chooses as it goes). Drawn on the radar and as a line through the cells' feet in the world.

## The loop
`bb viewer-loop` (`dev/viewer/loop.sh`): a fresh local world each run, one bot from spawn through the whole chain at normal difficulty (`--until` the current stage, `--chain true`, 5.5 hours) with `--viewer 4600` and `--record` under `data/viewer-runs/`, again when it ends. Between runs the page says the new world is loading, then drops the old world's columns and entities and follows the new bot; an open page reloads itself when the page file changes.

## Gotchas
- 26.x models write some texture variables as `{"sprite" ref "force_translucent" true}` (glass, panes, redstone dust); steve's renderer was made against older models and threw on them (`n.charAt is not a function`), drawing nothing. `plain-textures` flattens them.
- Yaw and pitch go to the renderer in typecraft's radians: yaw = pi - radians(degrees), pitch = -radians(degrees).
- The jar is read at start (`--viewer-jar`, by default Prism's `26.1.2`); item textures and the player skin go to `cache/viewer/26.1.2/textures/`, which git ignores. No Mojang asset is committed.
- To refresh the vendored renderer: `pnpm add esbuild@0.27.7 buffer@6.0.3` in a scratch dir, then `node dev/viewer/build.ts <steve checkout> <scratch>/node_modules`.

## See also
- [[telemetry-watch]] - the other watch on the atom.
- [[main-loop]] - the swap both observe.
