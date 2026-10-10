---
title: Public by default
description: Why functions are public (defn) and defn- is kept only for one-line local aliases, so every domain function can be called and tested from the REPL, and how docs_test holds every public function to a docstring.
type: decision
tags: [bot, decision, style]
aliases: [defn-, private functions, sparingly, REPL reach, docstrings, docs_test]
status: verified
lastUpdated: 2026-10-10
verifiedAgainst: c0767ab
sourceRefs:
  - "docs/hickey.md#`defn-` sparingly, since hiding a function from the REPL hides it from you too."
  - src/clojurecraft/craft.clj#(defn- now [world] (:time/now world))
  - src/clojurecraft/plan.clj#defn run-intent
  - test/clojurecraft/docs_test.clj#every-public-function-has-a-docstring
related:
  - "[[bot/decisions/_moc|Decisions]]"
  - "[[goals-and-intents]]"
---

# Public by default

## The choice
Every function is a `defn`. `defn-` is kept for exactly one kind of thing: a one-line local alias that is plumbing, not a domain function (`now` reading `:time/now`, `set-intent` updating `:plan/intent`, `intent`, and `resource` loading an EDN file). On 2026-10-10 that left eight `defn-` out of about 120 private functions before.

A public function is a promise to a reader, so each one carries a docstring that states what it returns and what must already be true (`docs/hickey.md`: "Docstrings on every public function stating the invariant"). `docs_test.clj` checks every public function and multimethod under `src/clojurecraft`; removing one docstring makes it fail with the function's name.

## What was rejected
Private helpers by default, the habit typed-object code leaves behind (the first versions had about 110). `docs/hickey.md` asks for `defn-` sparingly because a private function cannot be called from the REPL or a test without `#'ns/var` tricks: `plan/run-intent`, `sim/sync-window` or `game/load-chunk` are exactly the functions you want to poke at when a recorded race goes wrong. Nothing outside depends on a namespace's private surface, so there is no API to protect; the namespace name already says where a function belongs.

## What would change the answer
A namespace published as a library for others, where the public surface becomes a promise (accretion rules from Spec-ulation would then apply to it).

## See also
- [[goals-and-intents]] - `run-intent`, one of the functions made reachable.
