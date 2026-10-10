---
title: Information model
description: Where the written list of every attribute the bot knows lives (model.clj), how it is grouped, and the two tests that keep it true - every key a running bot writes is listed, every listed attribute has a spec.
type: explanation
tags: [bot, concepts, state, spec]
aliases: [attribute list, model.clj, model/attributes, what the bot knows, attribute meanings]
status: verified
lastUpdated: 2026-10-10
verifiedAgainst: c559708
sourceRefs:
  - src/clojurecraft/model.clj#def attributes
  - test/clojurecraft/model_test.clj#every-key-a-running-bot-writes-is-in-the-model
  - test/clojurecraft/model_test.clj#every-attribute-in-the-model-has-a-spec
  - docs/hickey.md#Write the attribute list with namespaced names and one-line meanings.
related:
  - "[[bot/concepts/_moc|Concepts]]"
  - "[[world-value]]"
  - "[[specs-and-instrumentation]]"
---

# Information model

`docs/hickey.md` begins the design with the information model in writing: every attribute, namespaced, with a one-line meaning. `model/attributes` is that list, as data: `[attribute meaning when]` rows, about 120 of them.

## How it is grouped
The essay's own grouping of what a bot knows: the bot and its connection (`bot/*`, `conn/*`), time (`time/*`), facts about itself (`player/*`, `control/*`), its screen (`window/*`), the world as seen and as remembered (`world/*`, `entity/*`, `sight/*`), the server's answers to its actions and counts (`stats/*`, `net/*`), its own intentions (`plan/*`, `intent/*`), and the events, effects and packets that carry all of it.

## How it stays true
- `every-key-a-running-bot-writes-is-in-the-model` walks every world, event and effect of a whole pickaxe run against the server model and collects every qualified key; any key the list lacks fails the test by name. Deleting the `:intent/next-swing` row fails it.
- `every-attribute-in-the-model-has-a-spec` requires a spec for every row. Writing the list added specs for 58 attributes that had none; every instrumented test and generated run now checks them, since `s/keys` checks any registered key a map carries.
- The second column is the meaning, the third says when the attribute exists; absent otherwise, never nil-filled ([[world-value]]).

## Gotchas
- The key walk does not enter the DataScript value, so the `sight/*` fact attributes are listed by hand; a test asserts they are there.
- Server-model (`sim/*`), recipe-row, window-view and DataScript schema keys are not the bot's knowledge and are excluded from the walk.
- A meaning never changes. A better representation is a new attribute beside the old one, added to this list in the same change.

## See also
- [[specs-and-instrumentation]] - the shapes behind each row.
