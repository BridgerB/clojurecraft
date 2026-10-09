---
title: Craft intent
description: The :craft intent's four stages (settle, click, verify, take) in window 0 or an open crafting table, its timeouts and failure reasons, and the one-click-per-round-trip rule.
type: reference
tags: [bot, crafting, intents]
aliases: [:craft, craft.clj, craft stages, settle click verify take, stale-window, intent/window]
status: verified
lastUpdated: 2026-10-09
verifiedAgainst: 5c7d6c1
sourceRefs:
  - src/clojurecraft/craft.clj#defmethod intent/run :craft
  - src/clojurecraft/craft.clj#defmethod stage :settle
  - src/clojurecraft/craft.clj#defmethod stage :verify
  - src/clojurecraft/craft.clj#defmethod stage :take
  - src/clojurecraft/window.clj#defn waiting
  - src/clojurecraft/window.clj#def answer-timeout 3000
related:
  - "[[bot/crafting/_moc|Crafting]]"
  - "[[window-views]]"
  - "[[window-zero-model]]"
  - "[[recipe-clicks]]"
  - "[[phantom-cursor-stale-window]]"
---

# Craft intent

`{:intent/kind :craft :intent/recipe :stick :intent/window :inventory}` (or `:table`) performs one craft of a recipe, in the player's own 2x2 grid or in an open crafting table. It is an `intent/run` defmethod in `craft.clj`; a second multimethod `stage` dispatches on `:intent/stage`, each stage advanced one tick at a time against a **view** of the window ([[window-views]]). Every deadline is `:time/now` minus a stored `:intent/since` or `:intent/sent-at`.

## Key files
- `craft.clj`, `intent/run :craft` - defaults `:intent/window` to `:inventory`, starts at `:settle`, zeroes `:player/controls`, builds the view (nil → fails `:no-table-window`), and gates every stage on `window/waiting`.
- `window.clj`, `waiting` - a click is unanswered while `:intent/awaiting` equals the current state id of the window it was sent to; past `answer-timeout` (3000 ms) the intent fails `:stale-window`.
- `craft.clj`, `stage` methods - `:settle`, `:click`, `:verify`, `:take`.
- `window.clj`, `click` - sends a click into a view; see [[phantom-cursor-stale-window]].

## Stages
1. **`:settle`** (one action per tick, in order): unknown recipe fails `:unknown-recipe`; for `:inventory` only, an open container is closed first (`container-close`, `:window/open` dropped); no state id yet waits up to `stage-timeout` (5000 ms) then fails `:no-window`; a loaded `:window/cursor` is put down in a free inventory slot of the view (fails `:inventory-full`); any item left in the view's grid (slots 1..size²) is shift-clicked out; finally `recipe/clicks` plans the craft with the view's size and slot map (nil fails `:no-ingredient`) and records `:intent/result`, `:intent/makes` and `:intent/before` (held count of the result).
2. **`:click`**: sends the next planned click; when none remain, moves to `:verify`.
3. **`:verify`**: only when the view's slot 0 is exactly `{:item result :count makes}` does it shift-click slot 0 and move to `:take`. Any other item there fails `:wrong-result`; nothing for 5 s fails `:no-result`.
4. **`:take`**: done when the held count of the result reaches `before + makes`; a `:table` craft then emits `container-close` for the table window and drops `:window/open`. 5 s without fails `:not-taken`.

## Gotchas
- A click is never sent while the previous one is unanswered: the answer is detected only by the state id moving, so a dropped click is a 3 s `:stale-window`, never a blind next click ([[sim-faults]]).
- On failure the planner retries the goal, and the next `:settle` reclaims anything left in the grid or on the cursor; that is the recovery path.
- Slot 0 is never clicked on faith ([[junk-crafts]]).
- The intent forces `:player/controls {}` every tick, so the bot stands still while crafting.

## See also
- [[make-goals]] - who starts it, and in which window.
- [[sim-window-sync]] - how the model answers each click.
