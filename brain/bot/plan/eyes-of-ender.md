---
title: Eyes of ender
description: How the bot gets twelve eyes of ender (issue #264) - blaze powder from the rods and each eye from a powder and a pearl, both 2x2 craft rows the needs planner already has; the :eyes planner whose :stuck names the short stage (:no-pearls, :no-rods); the aside row at priority 20; the sim test and the gym row eyes.
type: reference
tags: [bot, plan, crafting, goals]
aliases: [:eyes, :eyes-of-ender, eyes, :no-pearls, :no-rods, lacking, powder-per-rod]
status: verified
lastUpdated: 2026-10-16
verifiedAgainst: HEAD
sourceRefs:
  - src/clojurecraft/eyes.clj#def powder-per-rod
  - src/clojurecraft/eyes.clj#defn lacking
  - src/clojurecraft/eyes.clj#defmethod plan/next-intent :eyes
  - "resources/clojurecraft/goals.edn#{:goal/id :eyes-of-ender"
  - "src/clojurecraft/gym.clj#{:gym/goal :eyes-of-ender"
  - test/clojurecraft/sim_test.clj#eyes-of-ender-from-rods-and-pearls
  - test/clojurecraft/eyes_test.clj#the-planner-crafts-or-waits-for-its-stage
  - test/clojurecraft/eyes_test.clj#what-is-short-is-named
  - test/clojurecraft/gym_test.clj#the-eyes-row-gives-rods-and-pearls-and-counts-the-eyes
related:
  - "[[bot/plan/_moc|Plan]]"
  - "[[make-goals]]"
  - "[[blaze-rods]]"
  - "[[ender-pearls]]"
---

# Eyes of ender

Twelve eyes of ender, one for each frame of the end portal, are the stage after the rods ([[blaze-rods]]) and the pearls ([[ender-pearls]]). Nothing new is needed to make them: the two recipes are vanilla shapeless rows already generated into the goal table ([[make-goals]], `craft-rows`). `blaze_powder` is one rod into two powder; `ender_eye` is a pearl and a powder. Both fit the 2x2 grid, so no table and no trip.

## Key files
- `goals.edn`, `:eyes-of-ender` - a target and an aside (`:goal/aside? true`) at priority 20 after the pearls, `--until eyes`, providing `{:item/ender_eye 12}`, done by `:provided?`, planned by `:eyes`. As an aside it is never a step of a chained run and never the current stage: `plan/current-until` stays the fortress until the stages before it are proven.
- `eyes.clj`, `next-intent :eyes` - the needs planner (`make/decide`) toward the twelve. Its walk through the craft rows crafts a powder whenever none is held and eyes are still wanted, then an eye (`craft-now`: one run of the eye is made from what is held before more powder), so seven rods and twelve pearls end as twelve eyes and one rod: six powder crafts, twelve eye crafts. No producer row makes a rod or a pearl (each is its own stage, with its own rules), so a short inventory resolves to `:stuck`; the planner turns that into a wait that names the stage, `lacking`: `:no-pearls` when the pearls held do not cover the eyes still to make, else `:no-rods` when the powder held and `powder-per-rod` (2, from the recipe) a rod do not.

## Count
Twelve, not fourteen. The issue asked for fourteen when the pearls allow (an eye breaks about one throw in five); the pearl stage makes twelve, so the throws that find the stronghold are that stage's to count, from pearls it asks for then.

## Proof
- `sim_test eyes-of-ender-from-rods-and-pearls`: seven rods and twelve pearls given on bare ground, against the model: done, twelve eyes, one rod left, nothing in the grid, no failed intent, no violation.
- `eyes_test`: the waits and the first crafts from a few inventories; the row is an aside after the pearls.
- Gym rows `eyes-nether` and `eyes-nether-full` (`gym.clj`, plan eyes-a): the same craft in the sealed Nether room of the pearl rows, and with all 36 slots taken (netherrack, warped stems, a sword) before the first powder: the race bot's pack when it reaches this stage; the craft has nowhere to put its result until junk is tossed (craft.clj fails `:inventory-full`).
- Gym row `eyes` (`gym.clj`): a surface landing at Normal, seven rods and twelve pearls given, judged by `clear <bot> minecraft:ender_eye 0` finding twelve or more. Batch eyes-264a (plan run 38920108562, from branch gym/264) passed 6/6, each run done 20 to 30 s after landing.

## See also
- [[make-goals]] - the needs planner, `craft-now`, producers.
- [[craft-intent]] - one craft in window 0.
- [[gym-on-runners]] - the gym registry.
