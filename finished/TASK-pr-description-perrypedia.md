# Suggested PR description: Perrypedia metadata provider

Drafted against `AI_POLICY.md`'s disclosure requirements. This is a
**suggestion for the human (you) to review, edit, and own** before
opening the PR — per the policy, AI-assisted content must reflect your
own understanding and be something you can stand behind, not something
pasted verbatim.

---

## Suggested title

`feat(metadata): add Perrypedia metadata provider`

## Suggested body

```markdown
## Summary

Adds a Perrypedia (perrypedia.de) metadata provider — Perry Rhodan
(classic + Neo) and Atlan, the long-running German pulp-SF serial.
Nothing else covers this.

## Why action=parse instead of a raw wikitext fetch

Perrypedia has no Semantic MediaWiki / structured query API, and the
infobox template differs per series (`Roman Zyklus 42` for classic,
`Handlungszusammenfassung Neo Staffel 38` for Neo,
`Handlungszusammenfassung Atlan <cycle name>` for Atlan). One
`action=parse` request gets the redirect resolution, raw wikitext, and
rendered infobox HTML together. Title/subtitle/author/issue
number/publication date come straight out of the wikitext params
(`InfoboxWikitextParser`, brace-depth aware since the template name
itself isn't something you can regex for). The story cycle ("Zyklus")
isn't a literal param — it's expanded from the cycle number by the wiki
itself — so that one field comes from the rendered HTML instead, via
jsoup.

## What's included

- `PerrypediaParser` + `InfoboxWikitextParser`, wired in the same way
  as every other provider (enum, parser map, settings).
- `perrypediaId` — id-only like `comicvineId`, no rating field, wired
  through the same places every other provider ID touches: save/edit,
  refresh, bookdrop, duplicate detection, sidecar, and the matching
  frontend bits.
- Migration for `perrypedia_id` / `perrypedia_id_locked`.
- Tests using real wikitext pulled from live Perrypedia articles for
  all three series, plus a test that a search with no real match
  returns nothing instead of making something up.

## Testing

- `./gradlew test`: 3910 tests, 0 failures. Frontend typecheck/lint/test
  suite green too (306 files / 1709 tests).
- Built the real image, ran it against my own dev instance with an
  actual database, and used the feature — fetched real PR/Neo/Atlan
  issues, edited the Perrypedia ID by hand and via the search-result
  copy button, reloaded the book, checked the DB directly.
- That's how I caught the two real bugs in here: series name was
  pulling the wrong thing (whole franchise name instead of the actual
  story cycle — noticed from the value shown in the UI), and editing
  the Perrypedia ID didn't save at all (found by editing, saving,
  reopening the book, and seeing it gone). Second one turned out to be
  a save-path helper with a hardcoded per-field list that just never
  had this field added to it. Fixed both, rebuilt, re-checked in the
  DB.

## AI usage disclosure

Built this with Claude Code doing most of the actual typing —
implementation, tests, and the up-front research into how Perrypedia's
API/pages actually work. I reviewed what it wrote, ran everything
against a real instance and a real database myself, and both bugs
above were things I found by using the feature, not things Claude Code
flagged on its own. I know what this touches and why.
```

## Notes for you before opening the PR

- Still worth a final read before pasting — this is closer to your
  voice than the first draft, but check the specifics (test counts,
  bug descriptions) against what you actually remember happening.
- Consider whether to mention the deferred items from
  [[TASK-metadata-perrypedia.md]] §4 (cover-image fetching,
  `DetailedMetadataProvider`, other-locale translations not added) in
  the PR body, or leave them for a maintainer to ask about.
- This description is for `perrypedia-metadata-source` (the clean
  branch), not `perrypedia-metadata-source-wip` — this TASK file itself
  lives only on `-wip`, per [[wip-then-clean-pr-branch]].
