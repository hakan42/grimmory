# Suggested PR description: Perrypedia metadata provider

Drafted against `AI_POLICY.md`'s disclosure requirements. This is a
**suggestion for the human (you) to review, edit, and own** before
opening the PR — per the policy, AI-assisted content must reflect your
own understanding and be something you can stand behind, not something
pasted verbatim. Adjust the "Testing" and "AI usage" sections in
particular to match your own words and what you personally verified.

---

## Suggested title

`feat(metadata): add Perrypedia metadata provider`

## Suggested body

```markdown
## Summary

Adds a new metadata provider for Perrypedia (perrypedia.de), a
MediaWiki-based fan encyclopedia covering Perry Rhodan (classic and
Neo) and Atlan — the long-running German pulp-SF serial. No existing
provider covers this content.

## Why action=parse instead of a raw wikitext fetch

Perrypedia has no structured query API (no Semantic MediaWiki), and the
infobox template name/field set both vary by series (`Roman Zyklus 42`
for classic, `Handlungszusammenfassung Neo Staffel 38` for Neo,
`Handlungszusammenfassung Atlan <cycle name>` for Atlan). The provider
resolves a `Quelle:<id>` source page via `action=parse` in a single
request — redirect-following, raw wikitext, and rendered infobox HTML
together. Literal template parameters (title, subtitle, author, issue
number, publication date) are parsed from the wikitext with a new
brace-depth-aware parser (`InfoboxWikitextParser`). The story cycle
("Zyklus") isn't a literal parameter — it's template-expanded from the
cycle number — so it's read from the rendered infobox HTML instead, via
`jsoup` (already a project dependency).

## What's included

- `PerrypediaParser` + `InfoboxWikitextParser`, registered as a normal
  provider (enum, parser map, settings) alongside the existing ones.
- An identifier-only `perrypediaId` field (mirrors `comicvineId`'s
  shape — no rating, since Perrypedia isn't a rating source), threaded
  through the same plumbing every other provider ID goes through:
  persistence (manual edit + save), metadata refresh, bookdrop import,
  duplicate detection, sidecar round-tripping, and the corresponding
  frontend UI (search, settings, editor, magic shelf, lock/unlock).
- Flyway migration for the new `perrypedia_id`/`perrypedia_id_locked`
  columns.
- Unit tests (`PerrypediaParserTest`, `InfoboxWikitextParserTest`) using
  real wikitext fixtures captured from live Perrypedia articles across
  all three series families, plus a regression test asserting that a
  search matching no real article returns nothing rather than
  fabricating a result.

## Testing

<!-- Replace with what you actually did — this is a starting point. -->
- Full backend suite (`./gradlew test`): 3910 tests, 0 failures.
- Frontend: typecheck, lint, and full test suite (306 files / 1709
  tests) all green.
- Built and ran the actual production image locally end-to-end against
  a real database, exercised the feature by hand: fetched real Perry
  Rhodan/Neo/Atlan issues, edited and saved a `perrypediaId` manually
  and via the search-result "copy" action, confirmed persistence
  directly in the database.
- Deliberately caught and fixed two real bugs this way that automated
  tests didn't catch on their own: the series name was derived from the
  wrong field, and manual edits to the Perrypedia ID silently failed to
  persist (a missing wire-up in the save path, found by testing the
  actual UI flow, not by reading the code).

## AI usage disclosure

Developed with substantial assistance from **Claude Code**
(claude.ai/code). Claude Code researched the Perrypedia data source live
(confirming API shape, infobox structure, and per-series quirks against
the real site rather than assuming), wrote the implementation and tests,
and ran the automated test suites. I drove and verified this myself
throughout: reviewed the generated spec and code, ran the app locally
against a real database, tested the actual feature end-to-end in the
UI, and found/directed the fixes for both real bugs described above
through my own manual testing rather than relying on the AI to catch
them. I understand what this code does and how it fits into the
existing metadata-provider architecture.
```

## Notes for you before opening the PR

- The "Testing" and "AI usage" sections above are written in first
  person as a starting draft — reword them to match how *you* actually
  worked through this, not how I've guessed it. `AI_POLICY.md` is clear
  that this needs to reflect your own understanding, not be a
  rubber-stamped AI summary.
- Consider whether you want to mention the deferred items from
  [[TASK-metadata-perrypedia.md]] §4 (cover-image fetching,
  `DetailedMetadataProvider`, other-locale translations not added) in
  the PR body, or leave them for a maintainer to ask about.
- This PR description is for `perrypedia-metadata-source` (the clean
  branch), not `perrypedia-metadata-source-wip` — this TASK file itself
  lives only on `-wip`, per [[wip-then-clean-pr-branch]].
