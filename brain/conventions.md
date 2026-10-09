---
title: Conventions
type: moc
tags: [brain, conventions]
status: verified
lastUpdated: 2026-10-09
---

# Conventions

## Frontmatter schema
`title`, `description` (one line: what the note answers, shown by its MOC), `type` (index | moc | reference | explanation | decision | how-to | glossary), `tags`, `aliases`, `status` (verified | draft), `lastUpdated`, `verifiedAgainst` (see pins), `sourceRefs`, `related` (at least two, including the area MOC).

## sourceRefs
`path#symbolName` for code, `path#the text the line says` for prose, config and EDN. Never a line number. Each ref must resolve to exactly one place; quote a whole symbol, line or sentence, spelled as the source spells it. Paths are relative to the repository root. Two prefixes reach the sibling checkouts: `steve:` resolves under `/Users/bridger/Developer/mc/upstream/steve`, `ruststeve:` under `/Users/bridger/Developer/mc/upstream/ruststeve` (overridable with `STEVE_DIR` / `RUSTSTEVE_DIR` for the checker).

## Pins
- `bot` pillar: the clojurecraft commit the note was read at (short SHA).
- `game` pillar: the Minecraft version, `26.1.2` (protocol 775), because vanilla facts change by game version, not by our commits.
- `siblings` pillar: the sibling's commit SHA the note was read at.

## Body template
summary → key files → how it works → gotchas → limits → see also. Gotchas are written symptom-first. Decisions carry: the choice, what was rejected and the measured reason, what would change the answer.

## Naming
kebab-case, globally unique stems. `bot` pillar uses bare names; `game` pillar notes start with `mc-`; `siblings` pillar notes start with `sib-`. Hubs are `_moc.md` / `_index.md` and are linked by full path.

## Linking
Bare names when unique; full path for hubs. Link generously; no orphans.

## Size
One concept per note, roughly 1 to 6 KB. The discovery tier (index plus every MOC hook) stays small enough to load without thinking.

## Privacy
No secrets. RCON passwords live in `data/local-server/rcon.pass` and CI env; never quote them.
