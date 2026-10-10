---
title: Question set
type: moc
tags: [brain, questions]
status: verified
lastUpdated: 2026-10-09
---

# Question set

Real questions, the traversal that answers each, and how many notes it took. Re-run when an area changes.

| Question | Traversal | Notes |
|---|---|---|
| How does the bot decide what to do each tick? | [[bot/plan/_moc]] → [[goals-and-intents]] | 1 |
| Why does FINISH wait 4.25 s for a 3 s dig? | [[bot/gotchas/_moc]] → [[early-finish-aborts]] → [[dig-timeline]] | 2 |
| Where do packet ids come from and how do I add a packet? | [[bot/protocol/_moc]] → [[packet-specs]] → [[add-a-packet]] | 2 |
| Why did the bot sink into the ground / walk through grass? | [[grass-type-is-grass]] | 1 |
| How do I run the bot locally and judge it? | [[bot/how-to/_moc]] → [[run-locally]] → [[local-server]] | 2 |
| Why is `:player/vel` never zero while standing? | [[resting-vertical-velocity]] | 1 |
| Why not MCProtocolLib / a Java library? | [[from-scratch-protocol]] | 1 |
| Why a natural tree instead of a flat-world fixture? | [[natural-tree-not-fixture]] | 1 |
| What did steve/ruststeve learn about crafting that we must not repeat? | [[siblings/_moc]] → [[sib-wood-lock]] | 1 |
| How do I debug a failed CI run without a server? | [[record-replay]] | 1 |
| How tall is the Nether chunk column? | [[game/_moc]] → [[mc-dimensions]] | 1 |
| How does a bucket pour in 775? | [[mc-bucket-use-item]] | 1 |
| A craft failed `:stale-window` on a click that should have been a no-op. Why? | [[bot/gotchas/_moc]] → [[phantom-cursor-stale-window]] → [[mc-state-ids-prediction]] | 2 |
| Why did the bot craft a button / pressure plate? | [[junk-crafts]] → [[craft-intent]] | 2 |
| How does the bot decide whether to gather a log or craft planks next? | [[bot/crafting/_moc]] → [[recipe-graph]] → [[make-goals]] | 2 |
| Which window-0 slot is the hotbar, and how does it map to `set-player-inventory`? | [[game/windows/_moc]] → [[mc-window-zero-slots]] | 1 |
| Why not use the recipe book (`place-recipe`) instead of clicks? | [[bot/decisions/_moc]] → [[manual-clicks-not-place-recipe]] | 1 |
| How do I make the sim lose a click, and what does a test then expect? | [[sim-faults]] → [[craft-intent]] | 2 |
| Does the server send the breaker a block-update after FINISH? | [[mc-dig-and-pickup]] (yes, then the ack) → [[sib-dig-stop-timing]] (why ruststeve thought not) | 2 |
| What does each field of the RESULT line mean, and which `--until` values exist? | [[bot/runtime/_moc]] → [[result-line]] | 1 |
| Where do recipes come from, and why are they not in `--reports`? | [[mc-recipes-in-jar]] → [[datagen]] | 2 |
| What does the walk intent do when it is stuck, and where does the randomness come from? | [[bot/plan/_moc]] → [[walk-intent]] → [[randomness-on-the-tick]] | 2 |
| Which GitHub issue is water survival, and which draft has its research? | [[roadmap-issues]] | 1 |
| What did steve do for each of its 31 steps, and what covers it here? | [[siblings/_moc]] → [[sib-steve-steps-to-goals]] | 1 |
| How does the bot get a wooden pickaxe when it has no table? | [[bot/crafting/_moc]] → [[make-goals]] → [[place-intent]] → [[open-container-intent]] | 3 |
