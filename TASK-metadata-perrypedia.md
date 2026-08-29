# Spec: Perrypedia metadata provider

Add a new metadata provider that fetches metadata for Perry Rhodan
Heftromane (the long-running German pulp-SF serial) from Perrypedia
(https://www.perrypedia.de), a MediaWiki-based fan encyclopedia. See
[[AGENTS.md]] for the general provider-registration checklist this spec
elaborates on with source-specific detail.

## Branch & commit strategy

Two branches, two different jobs:

- **`perrypedia-metadata-source-wip`** (this branch) — the working branch.
  Iterative commits land here as work progresses, including this TASK file
  and [[TASK-track-upstream-releases.md]] as their own separate commits
  (not `AGENTS.md`/`CLAUDE.md` — those are never committed at all, per
  [[TASK-init.md|the original init task]]).
- **`perrypedia-metadata-source`** — the clean PR branch, created only once
  the `-wip` branch is working end-to-end. Cut fresh from whatever
  `upstream/develop` is at that moment (not from `-wip`), and populated
  with **one single commit** containing the actual implementation
  (backend + frontend + migration + tests) — no `.md` files at all, so the
  diff GitHub shows on the PR is exactly the code change, nothing else.

This keeps the messy/iterative history and the planning docs on `-wip`
only, while `perrypedia-metadata-source` stays a clean, single-commit,
docs-free PR candidate. Saved to memory as a general pattern for future
work in this repo, not just this one feature.

**Done**: cut in a separate `git worktree` (never the main checkout
directly, to keep the uncommitted local-only
[[goodreads-token-local-only]] GoodReads fix out of harm's way) —
`upstream/develop` hadn't moved since `-wip` was based on it, so no
rebase was needed. Single commit `3ba898cc0`, 68 files, zero `.md`
files, pushed to `origin/perrypedia-metadata-source`. Technique saved to
memory as [[wip-then-clean-pr-branch]].

## Implementation log

Branch: `perrypedia-metadata-source-wip` on `origin` (hakan42/grimmory) —
see "Branch & commit strategy" above for why it's `-wip` and what happens
at the end.

The implementation below (steps 1–6) landed in one commit,
`847101dcc` — "feat(metadata): add Perrypedia metadata provider" — staged
via an explicit path list rather than `git add -A`, to keep two things
out: the uncommitted local-only [[goodreads-token-local-only]] GoodReads
token workaround (a modified *tracked* file, so `-A` would have swept it
in), and `AGENTS.md`/`CLAUDE.md`/`TASK-init.md` (untracked, but still
wrong to include). Worth the same care on any future commit to this
branch while that GoodReads workaround is still sitting in the working
tree uncommitted.

- [x] **Step 1 — data model (`perrypediaId` plumbing, id-only, no rating)**:
  `model/dto/BookMetadata.java` (+`perrypediaId`, +`perrypediaIdLocked`),
  `model/entity/BookMetadataEntity.java` (+column, +locked column, trim,
  `applyLockToAllFields`, `areAllFieldsLocked`),
  `model/dto/settings/MetadataProviderSpecificFields.java`,
  `model/dto/sidecar/SidecarIdentifiers.java`,
  `model/MetadataClearFlags.java`, `model/dto/EpubMetadata.java`,
  `model/dto/request/MetadataRefreshOptions.java` (`FieldOptions` +
  `EnabledFields`, both the field and the manual constructor init),
  Flyway `db/migration/V147__Add_perrypedia_id_column.sql`. All mirror the
  `comicvineId` pattern exactly, per §3 item 6.
- [x] **Step 2 — remaining service-layer `perrypediaId` plumbing.**
  `util/MetadataChangeDetector.java` (new `FieldDescriptor`, mirrors
  `comicvineId`, `includedInFileWrite=true`),
  `service/BookRuleEvaluatorService.java` (added to the string-fields
  `METADATA_PRESENCE` switch case list),
  `service/appsettings/SettingPersistenceHelper.java` (default settings,
  default refresh-options field/enabled maps, default provider-specific
  fields — pulled `MetadataProviderSettings.Perrypedia{enabled}` in from
  step 3 early since this file needed it to compile),
  `service/bookdrop/BookdropMetadataService.java`,
  `service/metadata/sidecar/SidecarMetadataMapper.java` (both directions:
  entity→sidecar and sidecar→DTO). Extractors/writers:
  `PdfMetadataExtractor`, `EpubMetadataExtractor`, `CbxMetadataExtractor`,
  `PdfMetadataWriter`, `EpubMetadataWriter`, `CbxMetadataWriter`, and the
  shared `MetadataCopyHelper` (`copyPerrypediaId`). **Correction to spec
  §3 item 6**: the writer side is 4 files, not 1 —
  `EpubMetadataWriter.java`, `CbxMetadataWriter.java`, and
  `MetadataCopyHelper.java` weren't in the original list (found via
  `grep -rln copyComicvineId .../writer/`); `ranobedbId` (not
  `comicvineId`, which has comic-specific URL-embedding logic that
  doesn't apply here) was used as the template for the identifier
  roundtrip pattern in these three files, since Perrypedia has no
  standard external-URL convention to mirror.
  Hit `Map.of()`'s 10-entry-pair ceiling adding the 11th identifier
  prefix to `EpubMetadataExtractor.CALIBRE_IDENTIFIER_PREFIXES` — switched
  that map to `Map.ofEntries(...)`.
  **Verified**: `./gradlew compileJava` succeeds cleanly.
- [x] **Step 3 — `MetadataProviderSettings.Perrypedia`** (done as part of
  step 2, see above). `MetadataProvider` enum entry still pending — see
  step 4.
- [x] **Step 4 — enum + parser + wikitext parser + registration.**
  `model/enums/MetadataProvider.java` (+`Perrypedia`).
  New: `model/dto/response/perrypediaapi/{PerrypediaQueryResponse,PerrypediaSearchResponse}.java`
  (formatversion=2 MediaWiki JSON shape — `query.redirects[]`,
  `query.pages[].revisions[0].slots.main.content`, `query.search[]`),
  `service/metadata/parser/perrypedia/InfoboxWikitextParser.java`
  (brace-depth-counting block extraction + depth-aware `|Key = Value`
  split, per spec §3 — does not match on template name), and
  `service/metadata/parser/PerrypediaParser.java` implementing
  `BookParser` (no `DetailedMetadataProvider` yet — deferred per spec
  §4). `config/BookParserConfig.java` registers it in `parserMap`.

  **Design decision beyond the original spec text**: rather than passing
  the matched `PR`/`PRN`/`A` prefix down from the caller, the series
  (and thus the `perrypediaId` prefix) is derived from the infobox
  **template name itself** (`detectSeriesPrefix`: contains "neo" → PRN,
  contains "atlan" → A, starts with "roman zyklus" or
  "handlungszusammenfassung" → PR) with the caller-matched id only as a
  fallback. This makes the title-search fallback path (§2 point 2) work
  correctly too, since a search-matched article title usually doesn't
  embed the source id at all (confirmed against real Perrypedia titles
  in the spec's research — "Wenn Sterne bluten" carries no "389" or
  "PRN").
  Also derives `seriesName` ("Perry Rhodan" / "Perry Rhodan Neo" /
  "Atlan") from the same detected prefix, and parses `Erscheinungsdatum`
  (German weekday + day + month-name + year, e.g. "Freitag, 15. Februar
  2019") into `publishedDate` via a small German-month lookup.
  Cover fetching and `DetailedMetadataProvider` intentionally left out of
  v1, per spec §4's open questions.
  **Verified**: `./gradlew compileJava` succeeds cleanly.
- [x] **Step 5 — frontend wiring.** Spec §3's frontend list (4 files) turned
  out to be a significant undercount — a repo-wide audit (`grep -rl
  comicvineId frontend/src`, then diffing `comicvineId` vs `perrypediaId`
  occurrence counts per file to find gaps) found **~20 frontend files**
  actually needed touching, not 4. Fixed as found:
  - Original 4: `book-response.models.ts` (provider array — plus its
    separate `BookMetadataResponse`-shaped id/locked fields, missed on
    the first pass and caught by the audit), `app-settings.model.ts`,
    `metadata-provider-settings.component.ts`+`.html`,
    `metadata-field.config.ts`.
  - Also: `embeddable-fields.config.ts` (EPUB/PDF embed toggle set, not
    CBX — CBX embeds `comicvineId`/`perrypediaId` unconditionally,
    confirmed by absence from `CBX_EMBEDDABLE`),
    `metadata-refresh-options.model.ts`, `sidecar.service.ts`,
    `book.model.ts` (value + locked + `MetadataClearFlags`, 3 spots),
    `bookdrop-file-review.component.ts` (2 spots),
    `metadata-provider-field-selector.component.ts` (2 spots),
    `library-metadata-settings.component.ts`,
    `book-rule-evaluator.service.ts` (magic shelf),
    `magic-shelf-component.ts`,
    `metadata-editor.component.ts` (9 spots) +`.html` (1 block),
    `metadata-advanced-fetch-options.component.ts` (4 spots),
    `book-table.helpers.ts`, `lock-unlock-metadata-dialog.component.ts`
    (2 spots), and `metadata-searcher.component.ts` (provider-badge
    detection, public profile link to
    `https://www.perrypedia.de/wiki/Quelle:<id>`, provider name/trackBy
    — deliberately **not** mirroring its `getDetailEnrichmentInfo`/
    `getProviderItemId` block, since that's gated to providers with a
    backend `DetailedMetadataProvider`, which Perrypedia doesn't have
    yet).
  - i18n: added `perrypedia`/`perrypediaId` source strings (English only)
    to `i18n/en/settings-metadata.json`, `i18n/en/magic-shelf.json`,
    `i18n/en/metadata.json`. Other 20 locale files intentionally left
    untouched — this repo's other language files look
    translation-pipeline-managed, not something a code PR hand-edits.
  - **Explicitly NOT touched, confirmed correct by the same audit
    technique**: `metadata-viewer.component.html`'s external-ratings
    strip (rating-only section; `comicvineId` isn't there either, so
    `perrypediaId` — which also has no rating — correctly doesn't belong
    there), `metadata-match-weights` (no rating to weight, per spec §3
    item 5).
  - Verification method: `grep -c comicvineId file` vs `grep -c
    perrypediaId file` per file that mentions `comicvineId` at all,
    across both frontend and backend, to surface every file where the
    counts didn't match 1:1 — each mismatch was individually checked and
    is either now fixed or confirmed intentional (documented above).
  - **Not yet run**: `just ui typecheck`/`just ui build` — this machine
    has no local Node/pnpm toolchain installed (checked: no `node`,
    `npm`, `pnpm`, or `corepack` on `PATH`). All edits were made by
    mirroring an adjacent, structurally-identical existing line
    (`comicvineId`, mostly), so risk is low, but this is unverified and
    should be run (locally with Node installed, or via `just dev-up`)
    before treating step 5 as done.
- [x] **Step 6 — tests + backend build verification.**
  `backend/src/test/resources/perrypedia/{mythos_erde,wenn_sterne_bluten,die_zeitfestung}.fixture`
  — the three real wikitext samples captured live during this spec's
  research (§1), reused verbatim as fixtures, one per series family.
  `service/metadata/parser/perrypedia/InfoboxWikitextParserTest.java` —
  pure unit tests of the wikitext parser against all three fixtures
  (template name, field extraction, `{{todo}}`→null, Atlan's
  `SonstigesWas`/`SonstigesInhalt` vs classic's `Besonderes`), plus
  empty/null input.
  `service/metadata/parser/PerrypediaParserTest.java` — HTTP-mocked
  (mirrors `RanobeDBParserTest`'s `HttpClient` mocking pattern) against
  all three series via the primary `Quelle:<id>` lookup path, plus one
  test for the title-search fallback path confirming `perrypediaId` and
  `seriesName` are still correctly recovered from the infobox template
  name even when the search-matched title carries no id (as designed in
  step 4).
  **Verified**: `./gradlew test --tests
  '...perrypedia.InfoboxWikitextParserTest' --tests
  '...parser.PerrypediaParserTest'` → 11/11 pass. Full backend suite
  (`./gradlew test`) → 3908 tests, 0 failures, 0 errors.

- [x] **Frontend verified via Docker** — this dev machine has no
  Node/pnpm toolchain installed, so verification used a throwaway
  `node:24-alpine` container mirroring the project's own `Dockerfile`
  frontend-build stage exactly (same pinned `pnpm@11.19.0`, same
  `--frozen-lockfile --ignore-scripts` install), repo root bind-mounted
  in:
  ```
  docker run --rm -v "$PWD":/workspace -w /workspace node:24-alpine \
    sh -c "npm install --ignore-scripts -g pnpm@11.19.0 && \
           pnpm install --frozen-lockfile --ignore-scripts && \
           pnpm -C frontend run <script>"
  ```
  Ran `typecheck` (`tsc --noEmit`), `lint:eslint` (`ng lint`), and
  `build:prod` (`CI=1 NG_CLI_ANALYTICS=false ... build:prod`) this way —
  **all three exit 0**, production bundle built successfully. No host
  Node install was needed; `node_modules`/`dist`/`.pnpm-store` stayed
  correctly gitignored (confirmed via `git status`).

## Local test image

Built and pushed a local end-to-end test image from this `-wip` branch's
working tree (uncommitted `GoodReadsParser.java` workaround included,
since Docker builds from the working tree, not git history):

```
docker buildx build --platform linux/amd64 -t grimmory:local --load .
docker tag grimmory:local ghcr.io/hakan42/grimmory:perrypedia-metadata
gh auth token | docker login ghcr.io -u hakan42 --password-stdin
docker push ghcr.io/hakan42/grimmory:perrypedia-metadata
```

Live at `ghcr.io/hakan42/grimmory:perrypedia-metadata`
(`sha256:2f256f1720e4...`). Authenticated to GHCR via `gh auth token`
rather than reading any credential file directly — `gh` was already
logged in as `hakan42` with `write:packages` scope. GHCR cross-mounted
several shared base-image layers from `grimmory-tools/grimmory` and
`marvinvr/docktail` already present in the registry, so only the actual
app layers needed uploading.

This is the general pattern for future local test images from this
repo/fork — `ghcr.io/hakan42/grimmory:<descriptive-tag>` — not just this
one build. Saved to memory as [[ghcr-local-test-image-tagging]].

**Environment gotcha hit along the way**: an earlier ad-hoc
`docker run` against `gradle:9.5.1-jdk25-alpine` (bind-mounting the repo
root to cross-check the backend build in Docker) ran as root and left
`backend/build/` root-owned, which then broke the next host-side
`./gradlew test` with `AccessDeniedException` on class files. Fixed with
a one-off `alpine chown -R $(id -u):$(id -g) /workspace` container
against the repo root. Worth remembering if mixing host and root-in-
container builds against the same working directory again.

**Rebuilt and re-pushed** after the Zyklus/`action=parse` fix (same tag,
new digest `sha256:9f4213538a...`, was `sha256:2f256f1720e4...`). The
`grimmory-dev-server-1` container (part of the `grimmory-dev`
docker-compose project) tracks this exact tag. **Correction to the
original note here**: it does auto-update — confirmed twice now
(`docker inspect grimmory-dev-server-1 --format '{{.Image}}'` matched
the freshly-pushed digest within ~5 minutes both times, no manual
`docker compose pull`/`up -d` needed). Something (likely `wud`, though
no `wud.watch` label is set on that container either way) is polling
`ghcr.io/hakan42/grimmory:perrypedia-metadata` and redeploying on a new
digest automatically.

**Rebuilt and re-pushed again** after the `BookMetadataUpdater`
persistence-bug fix — new digest `sha256:6907be1f5412...`, was
`sha256:9f4213538a4c...`. Confirmed live via `docker inspect
grimmory-dev-server-1` again — same auto-update behavior. Confirmed
persisted end-to-end: visually in the UI, and directly in
`grimmory-dev-db-1` (`perrypedia_id`/`series_name` columns holding real
values after an edit and a re-fetch).

Also tagged and pushed `ghcr.io/hakan42/grimmory:v3.3.3-perrypedia-metadata`
as an additional tag on the same digest (`v3.3.3` = the version the
currently-deployed prod container runs) — not a replacement for
`perrypedia-metadata`, which `grimmory-dev`'s compose file is hardcoded
to track.

Also pushed both tags (`perrypedia-metadata` and
`v3.3.3-perrypedia-metadata`) to the local `zot` registry at
`registry.raven-alioth.ts.net/digital-library/grimmory` — a
`zot-server-1` container exposed via `docktail` under service name
`registry` (its own `docktail.service.name` label says `zot`, but the
indexed label that actually got registered says `registry` — confirmed
from `docktail-docktail-1`'s logs). No login needed, anonymous push
works. Same digest as GHCR (`sha256:6907be1f5412...`) — all layers
already existed, near-instant push both times. Technique saved to
memory as [[ghcr-local-test-image-tagging]] (name predates the zot
addition, still covers both now).

## Real-instance testing findings (dev deployment)

Testing against the live `grimmory-dev` instance (see
[[ghcr-local-test-image-tagging]]) surfaced two real issues, both fixed:

1. **`seriesName` was wrong.** It was set from the series *family*
   ("Perry Rhodan" / "Perry Rhodan Neo" / "Atlan", derived from the
   `perrypediaId` prefix) instead of the story *cycle* ("Zyklus" — e.g.
   "Mythos" for PR 3000, "Artefakte" for PRN 389, "Im Auftrag der
   Kosmokraten" for A 800), which is what a reader actually wants tracked
   as a series (~100 issues per cycle vs. 3000+ for the whole franchise).
   **Root cause**: "Zyklus" is not a literal wikitext template parameter —
   confirmed live, `Mythos Erde (Roman)`'s raw wikitext has no `|Zyklus =`
   key at all. The classic-series template (`{{Roman Zyklus 42 ...}}`)
   only carries the cycle *number*; the cycle *name* ("Mythos") is
   template-expanded from that number by the wiki itself, so it only
   exists in rendered output, not the raw source.
   **Fix**: switched the API call from `action=query&prop=revisions` to
   `action=parse&prop=wikitext|text&redirects=1&section=0` — one request,
   same redirect-following, but now returns **both** the raw wikitext
   (still used for Titel/Untertitel/Autor/Nummer/Erscheinungsdatum via
   `InfoboxWikitextParser`, unchanged) **and** the rendered infobox HTML.
   Added `extractZyklus(html)` using `jsoup` (already a project
   dependency — see `GoogleParser`/`AudibleParser` for other users) to
   read the "Zyklus:" table row out of the rendered infobox. Verified
   live that all three series families render a "Zyklus:" row (classic,
   Neo, and Atlan alike), so this is a universal field, not
   classic-series-only. `detectSeriesPrefix`/`seriesLabel` — the old
   family-name derivation — is gone; `detectSeriesPrefix` is kept only
   for computing the `perrypediaId` prefix, which is unrelated.
   `PerrypediaQueryResponse.java` replaced by `PerrypediaParseResponse.java`
   (different response shape: top-level `parse` object with `title`,
   `redirects[]`, `wikitext`, `text`, confirmed against a real live
   response before writing the DTO). `PerrypediaParserTest` fixtures and
   assertions updated to match (real Zyklus values verified live:
   Mythos / Artefakte / Im Auftrag der Kosmokraten), plus a new
   `extractZyklus_MissingRow_ReturnsNullSeriesName` test.
2. **A hardcoded provider-name list was missing `Perrypedia`.**
   `metadata-advanced-fetch-options.component.ts` (the per-library
   "Default Settings" 1st–4th-priority dialog) has its own
   `providers`/`providersWithClear` `string[]` literals — display names
   like `'Comicvine'`, independent of `BOOK_METADATA_PROVIDERS` and of
   every `comicvineId`-style field-key list already audited. This is why
   the earlier repo-wide `comicvineId` vs `perrypediaId` occurrence-count
   audit didn't catch it — the audit only matched field-key strings, not
   this differently-shaped display-name array. Fixed; re-audited for any
   other `'Comicvine'`/`'Ranobedb'`-style display-name arrays afterward —
   none remain. The metadata-*search* dialog (`BOOK_METADATA_PROVIDERS`-
   driven) already correctly showed Perrypedia, confirmed via screenshot
   against the live dev instance — only this one settings dialog had the
   gap.

3. **The Perrypedia ID didn't persist on save at all** — editing it
   manually, or via the "copy" icon on a search result, then saving and
   reopening the book showed nothing. Verified the schema and migration
   were fine first (`docker exec`'d into `grimmory-dev-db-1`:
   `DESCRIBE book_metadata` shows `perrypedia_id`/`perrypedia_id_locked`
   present and correctly typed; `flyway_schema_history` shows `V147`
   applied with `success=1`) — so this wasn't a missing-column problem.
   **Root cause**: `BookMetadataUpdater.applyFieldUpdates`/`updateLocks`
   apply an edited `BookMetadata` DTO onto the entity via an explicit
   per-field `handleFieldUpdate(...)`/`Pair.of(...)` call list —
   `perrypediaId` was never added to it, so every save silently dropped
   it while every other field round-tripped normally. This is the exact
   save path both manual edits and the "copy from search results" icon
   go through.
   **How this was missed originally**: the repo-wide audit technique
   used throughout this spec (`grep -c comicvineId` vs `grep -c
   perrypediaId` per file) was case-sensitive, so it matched
   lowercase-first field-name usages but silently missed every JavaBean
   accessor — `setComicvineId`/`getComicvineId` (capital `C`) — which is
   exactly where `BookMetadataUpdater` lived. Re-running the same audit
   **case-insensitively** (`grep -ci`) surfaced **11 more real gaps**
   beyond `BookMetadataUpdater` itself:
   - `MetadataRefreshService` — the actual metadata-*refresh* apply
     logic (provider selection for a field, enabled-field application,
     lock carry-over on non-refreshed fields). Same class of bug as
     `BookMetadataUpdater`, different code path — a fetched Perrypedia
     result was never being applied here either, independent of the
     save-path bug.
   - `BookQueryService` — the metadata strip/redaction logic (both
     values and locks) and the "are all fields locked" check.
   - `BookFileDetachmentService` — metadata copy when detaching a file
     from a multi-file book.
   - `DuplicateDetectionService` — the external-ID set used for
     duplicate-book matching.
   - `BookdropMetadataService` — the "has any known identifier" check,
     which even carries a `// Keep in sync with identifier fields...`
     comment.
   - `Azw3Processor`/`CbxProcessor`/`EpubProcessor`/`Fb2Processor`/
     `MobiProcessor`/`PdfProcessor` — initial-import metadata copy from
     extracted file metadata onto a new book entity. Confirmed the
     AZW3/FB2/MOBI-specific extractors don't currently populate
     `comicvineId` either (0 hits, case-insensitive), so this is
     presently a structural no-op for those three formats — fixed for
     consistency with the existing `comicvineId` pass-through and to be
     correct if that ever changes.
   Re-audited case-insensitively afterward: only the three
   already-confirmed intentional exceptions remain
   (`ComicvineBookParser`'s own implementation, and the comic-specific
   web-URL logic in `CbxMetadataExtractor`/`CbxMetadataWriter` that has
   no Perrypedia equivalent). **Lesson for any future field addition in
   this codebase**: audit with a case-insensitive grep from the start —
   a case-sensitive one will miss every JavaBean getter/setter.

4. **On an existing/pre-existing installation** (a real prod instance,
   promoted to run `v3.3.3-perrypedia-metadata` directly — confirmed via
   `docker inspect grimmory-server-1`), Perrypedia worked fine as a
   metadata *source* (search/fetch), but the "Perrypedia ID" field never
   showed on the book detail/edit page. **Not a migration problem** —
   checked `grimmory-db-1` (prod's own DB, separate from
   `grimmory-dev-db-1`) directly: `V147` applied successfully, columns
   present and correctly typed, same as dev.
   **Root cause**: `MetadataProviderSpecificFields`
   (`model/dto/settings/MetadataProviderSpecificFields.java`) — the
   per-field visibility-toggle settings, a separate concern from the
   provider-*enabled* settings that Perrypedia's search/fetch actually
   depends on — is a plain bean of boxed `Boolean` fields with no
   Jackson null-handling. The persisted `metadata_provider_specific_fields`
   row in `app_settings` predates `perrypediaId`'s existence, so the key
   is simply absent from that JSON; Jackson deserialization leaves the
   field `null` rather than backfilling the class's intended default
   (`true`, from `getDefaultMetadataProviderSpecificFields()`) — and
   `null` reads as "hidden" wherever the frontend checks field
   visibility. **Confirmed this isn't new or Perrypedia-specific**: the
   same persisted JSON already has `"ranobedbId": null` for the
   identical reason from that field's own rollout — a systemic gap in
   this settings class, not something this PR introduced.
   **Not fixed in code** — the user chose to resolve it by simply
   opening the field-visibility settings screen (renders
   `metadata-provider-field-selector.component`) and saving once, which
   submits the full object (including `perrypediaId: true`) and fixes it
   for that instance. The real fix — giving every field in this class a
   default-`true` initializer plus `@JsonSetter(nulls = Nulls.SKIP)`,
   mirroring the exact pattern `MetadataRefreshOptions.EnabledFields`
   already uses elsewhere in this codebase for exactly this problem — is
   **out of scope for this PR** (touches all 19 fields, not just the new
   one) but worth flagging to a maintainer as a pre-existing gap, since
   it'll bite the next field added here too.

Also investigated (from a screenshot showing a book's title as "Wenn
Schatten bluten" for what should be PRN 389 "Wenn Sterne bluten"):
confirmed live via Perrypedia's own search API that **no article titled
"Wenn Schatten bluten" exists** (`totalhits: 0`), so this value cannot
have come from a real Perrypedia fetch through this parser — there's
nothing in `PerrypediaParser` that could turn "Sterne" into "Schatten"
either. Most likely explanation: pre-existing/current metadata on that
test book (e.g. from how the file was originally named), unrelated to
this provider. Added `fetchTopMetadata_SearchYieldsNoResults_ReturnsNull`
as a regression test using this exact confirmed zero-hit response, so a
search that matches nothing real can't silently produce a wrong result.

## Outstanding before this is PR-ready

- No frontend tests were added (e.g. for `metadata-searcher.component.ts`'s
  new `perrypediaId` branches) — existing coverage for the sibling
  `comicvineId`/`ranobedbId` branches in that file wasn't audited either,
  so this matches current repo conventions rather than being a new gap.
- Cover-image fetching is implemented (§0). `DetailedMetadataProvider`
  remains deferred (§4), as does confirming real-world Neo/Atlan filename
  conventions beyond the `PRN<nnnn>`/`A<nnnn>` forms implemented.

## 0. Cover images (investigated 2026-08-29)

Resolves the "Cover images" open question in §4 below.

**Finding: feasible with the existing data source, no second request
needed.** The same `action=parse&prop=wikitext|text` response already
fetched for `extractZyklus` includes the rendered infobox HTML, which
contains an `<img>` tag for the cover. Verified live via the actual
`Quelle:<prefix><nnnn>` redirect lookup path (`fetchBySourceId`, not just
search-matched titles) — one example per series family:

- PR classic, `Quelle:PR3000` → resolves to `Mythos Erde (Roman)`:
  `<img alt="PR3000.jpg" src="/mediawiki/images/thumb/1/12/PR3000.jpg/360px-PR3000.jpg" ... srcset="...540px-PR3000.jpg 1.5x, .../720px-PR3000.jpg 2x" />`
- Neo, `Quelle:PRN389` → resolves to `Wenn Sterne bluten`:
  `<img alt="Neo389.jpg" src=".../180px-Neo389.jpg" srcset="...270px... 1.5x, .../360px... 2x" />`
- Atlan, `Quelle:A800` → resolves to `Die Zeitfestung`:
  `<img alt="A800_1.JPG" src=".../180px-A800_1.JPG" srcset="...270px... 1.5x, .../360px... 2x" />`

Notable details:

- Filenames are **not** a clean `<prefix><nnnn>.jpg` convention across
  series — Atlan's is `A800_1.JPG` (mixed case, `_1` suffix). This
  confirms the risk the original open question flagged. Don't construct
  the `Datei:` filename from the id; read it off the rendered `<img>`
  instead.
- The infobox/section-0 HTML can contain other `<img>` tags that aren't
  the cover: `Logo_Begriffsklärung.png` (disambiguation marker icon, seen
  on `Mythos Erde (Roman)` since it's also a disambiguation page) and
  `Leseprobe.png`/`Hörprobe.png` (reading-sample/audio-sample icons, seen
  whenever those fields are populated). The cover is the first `<img>`
  whose filename isn't one of these three.
- `srcset` carries higher-resolution variants than the bare `src`
  thumbnail (up to 720px wide for PR3000 in this sample, 360px for the
  other two) — prefer the widest `srcset` candidate. This is still a
  thumbnail, not the original upload.

**Implemented**: `extractCoverUrl(Document doc)` in `PerrypediaParser`,
reusing the same Jsoup `Document` (now parsed once in `toMetadata` and
passed to both `extractZyklus` and `extractCoverUrl`, instead of each
re-parsing the HTML). Picks the first `<img>` not matching
`NON_COVER_IMAGE_FILENAMES`, prefers the widest `srcset` candidate via
`widestImageUrl`, resolves a relative URL against
`https://www.perrypedia.de` via `resolveImageUrl`, and sets
`BookMetadata.thumbnailUrl`. Covered by three new tests in
`PerrypediaParserTest` (widest-srcset selection, skipping the
disambiguation icon, and the no-image-present null case) using the real
`PR3000.jpg` markup captured live above. Fetching the true original
(non-thumbnailed) upload would need a follow-up
`action=query&titles=File:<name>&prop=imageinfo&iiprop=url` request —
still deferred as unnecessary; the widest `srcset` thumbnail is almost
certainly good enough for a library cover.

## 1. Data source facts (verified live, 2026-08-28)

- Domain is `perrypedia.de` — `perrypedia.proc.org` (an old domain) 301s to
  it. Use `perrypedia.de` everywhere.
- Standard MediaWiki API at `https://www.perrypedia.de/api.php`
  (`format=json` supported). **No Semantic MediaWiki `ask`/`askargs`
  module** — confirmed via the API help page, so there is no structured
  query endpoint; metadata has to come from parsing a page's wikitext.
- **There are multiple series with distinct ID prefixes**, confirmed via
  three separate lookups:
  - `PR<nnnn>` — the main series ("Klassische Serie" / "Erstauflage"),
    e.g. `Quelle:PR3000`.
  - `PRN<nnnn>` — "Perry Rhodan Neo", e.g. `Quelle:PRN389`.
  - `A<nnnn>` — the "Atlan-Heftserie" spin-off, e.g. `Quelle:A800`.
  Other Perry Rhodan spin-offs exist (Silberband, Stardust, PR Extra, ...)
  but are **out of scope for v1** unless requested — not covered by this
  spec's research.
- Each `Quelle:<prefix><nnnn>` page is purely a `#REDIRECT`
  (`#WEITERLEITUNG` in German wikitext) to the actual article — confirmed
  for all three prefixes above. Critically, **the target title is not a
  predictable `<Titel> (Roman)` pattern**: it's whatever the plain article
  title is, with a disambiguating suffix only added when needed (`Mythos
  Erde (Roman)` for PR 3000, but plain `Wenn Sterne bluten` for PRN 389 and
  plain `Die Zeitfestung` for A 800). **Implication for implementation:**
  never construct the target title — resolve it via the MediaWiki API's
  redirect-following in a single request:
  ```
  GET https://www.perrypedia.de/api.php?action=query&prop=revisions&rvslots=main&rvprop=content&titles=Quelle:<prefix><nnnn>&redirects=1&format=json
  ```
  Verified live for both `PR100` and `PRN212`: the response's top-level
  `redirects` array gives the resolved title
  (`{"from":"Quelle:PR100","to":"Der Zielstern"}`,
  `{"from":"Quelle:PRN212","to":"Welt der Hoffnungslosen"}`) and
  `query.pages[].revisions[0].slots.main.content` (or `["*"]` on older
  MediaWiki response shapes — confirm which against a live response at
  implementation time) holds the target article's full wikitext,
  including the infobox, in the same call. No second request needed to
  fetch the resolved page's content — this replaces the two-step
  "resolve redirect, then fetch page" approach implied by an earlier
  draft of this spec.
- **The infobox template name varies by series, not just by cycle**,
  confirmed via three different raw-wikitext fetches:
  - PR classic (issue 3000, cycle 42): `{{Roman Zyklus 42 | ... }}`
  - PR Neo (issue 389, "Staffel" 38): `{{Handlungszusammenfassung Neo Staffel 38 | ... }}`
  - Atlan (issue 800, cycle name, not number): `{{Handlungszusammenfassung Atlan Im Auftrag der Kosmokraten | ... }}`
  There is no single fixed template name or even a shared naming family
  to anchor a regex on. **Implementation must not match on template name
  at all** — instead, locate the first top-level `{{ ... }}` block in the
  article's wikitext (the infobox is always the first template call) using
  **brace-depth-counting**, not a naive non-greedy regex: values inside
  the block contain their own nested templates (`{{EVJ|2019}}`,
  `{{RZJ Atlan|0800}}`, `{{todo}}`), so a regex like `\{\{.*?\}\}` will
  stop at the first inner `}}` and truncate the block. Once the balanced
  block is isolated, split on top-level `|Key = Value` pairs (again brace-
  and bracket-depth-aware, since values contain `[[...]]` and `{{...}}`).
- **Field names also vary by series**, confirmed from the same three
  fetches — treat as optional/best-effort per field, not a fixed schema:
  - Common across all three: `Nummer`, `Titel`, `Autor`,
    `Erscheinungsdatum`.
  - PR classic only (from `Mythos Erde (Roman)`): `Untertitel`,
    `Titelbildzeichner`, `Innenillustrator`, `Hauptpersonen`,
    `Handlungszeitraum`, `Handlungsort`, `Glossar`, `Besonderes`,
    `Risszeichnung`, `Leseprobe`, `Hörprobe`, `DieserArtikel`.
  - PR Neo (`Wenn Sterne bluten`) reuses `Titelbildzeichner`,
    `Handlungszeitraum`, `Handlungsort`, `Leseprobe`, `Hörprobe`,
    `DieserArtikel`, but had `Handlungszeitraum`/`Handlungsort` as literal
    `{{todo}}` placeholders — **treat `{{todo}}` as "field not yet filled
    in", i.e. null, not as literal text**. No `Untertitel` in this example
    (may still be a valid optional field).
  - Atlan (`Die Zeitfestung`) has `Untertitel`, `Innenillustrator`,
    `Hauptpersonen`, `Handlungszeitraum`, `Handlungsort`, `Risszeichnung`
    like PR classic, but replaces `Besonderes` with two different fields,
    `SonstigesWas`/`SonstigesInhalt`, and has no `Glossar`/`Leseprobe`/
    `Hörprobe` in this example.
  All values contain wikilinks (`[[...]]`) and templates (`{{EVJ|2019}}`,
  `{{Quote|...}}`) that need stripping down to plain text — reuse one
  stripping helper for every field.
- **No synopsis/blurb field.** `Besonderes` is closer to trivia/production
  notes than a summary. A real synopsis would have to come from prose in
  the article body (unreliable to parse) or be left blank.
- **No cover image URL in the infobox.** Cover art lives on separate
  `Titelbildgalerie PR <range>` gallery pages and/or `Datei:`
  (File:) pages; resolving a cover requires a second lookup
  (`action=query&titles=Datei:...&prop=imageinfo&iiprop=url`) with a
  filename convention that hasn't been confirmed yet (open question, see
  §4).
- **No rating/score concept** — Perrypedia is an encyclopedia, not a
  review aggregator. There is no analogue to `ranobedbRating` /
  `doubanRating`.
- Content is German-only.

## 2. Scope decision

Given the above, Perrypedia should be modeled the way **Comicvine** is
modeled: an **identifier-only** provider (`perrypediaId`), no rating
field. `perrypediaId` should store the **full prefixed source id** (e.g.
`"PR3000"`, `"PRN389"`, `"A800"`), not a bare number — a bare `"389"`
would be ambiguous across the three series (PR Neo 389 vs. some future PR
classic 389 already exists as a distinct book), and the prefixed form is
exactly the `Quelle:<id>` key, so it doubles as a direct re-fetch key.

Matching strategy:
1. **Primary**: extract a source id matching one of the three known
   prefixes from the book title or filename — `PR<nnnn>` (classic),
   `PRN<nnnn>` (Neo), or `A<nnnn>` (Atlan). Needs a real survey of how
   these three series are actually named in filenames/titles in practice
   before picking a single regex (e.g. does Neo ever appear as
   `"PR Neo 389"` instead of `"PRN389"`? does Atlan ever appear as
   `"Atlan 800"` instead of `"A800"`? — treat as an implementation-time
   task, not guessed here) — but however it's found, resolve directly via
   `Quelle:<prefix><nnnn>` with `redirects=1` in the same API call (see
   §1). This is a single deterministic lookup, no search ranking needed.
2. **Fallback**: if no recognizable id is present, use
   `action=query&list=search&srsearch=<title>&srnamespace=0` over the main
   namespace and rank/filter results (no reliable title-suffix filter
   exists across all three series — see §1's finding that only some
   articles carry a disambiguating suffix). Lower confidence — flag as
   best-effort in code comments, and note in the PR description that this
   path is weaker than provider search elsewhere in the codebase.

This means Perrypedia will only ever return useful results for Perry
Rhodan/Atlan Heftromane, not general books — same category of narrowness
as Ranobedb (light novels) or Comicvine (comics only) in this codebase, so
that's an accepted precedent, not a new kind of limitation.

## 3. Implementation plan

Mirror `RanobeDbParser` (`backend/src/main/java/org/booklore/service/metadata/parser/RanobeDbParser.java`)
as the closest structural template: `java.net.http.HttpClient` +
`tools.jackson.databind.ObjectMapper`, `@Service` + `@RequiredArgsConstructor`,
a rate limiter (MediaWiki etiquette expects a descriptive `User-Agent` and
non-abusive request rates — reuse the existing
`"Grimmory/1.0 (...; +https://github.com/grimmory-tools/grimmory)"` UA
pattern already used by `RanobeDbParser`/others).

Backend changes:

1. `model/enums/MetadataProvider.java` — add `Perrypedia`.
2. `service/metadata/parser/PerrypediaParser.java` — new `@Service`
   implementing `BookParser`. No `DetailedMetadataProvider` needed unless
   a later iteration wants "refresh by known perrypediaId" support (cheap
   to add: it's just the `Quelle:<prefix><id>` lookup with no search step).
3. Wikitext-to-metadata parsing: a small dedicated parser (e.g.
   `service/metadata/parser/perrypedia/InfoboxWikitextParser.java`,
   following the `parser/hardcover/` precedent of giving a provider its
   own subpackage for non-trivial parsing helpers) — **not** a fixed-
   template-name regex (ruled out in §1: the name differs per series and
   even encodes the cycle name/number for Atlan/PR-classic). Instead:
   brace-depth-counting scan to isolate the first top-level `{{ ... }}`
   block, then a bracket/brace-depth-aware split of its body into top-level
   `|Key = Value` pairs (naive `split("\\|")` would break on values
   containing `|` inside `[[Link|Text]]` or nested templates), then a
   wikilink/template stripper shared across fields (`[[X|Y]]` → `Y`,
   `[[X]]` → `X`, `{{EVJ|2019}}` → `2019`, `{{todo}}` → null, drop
   `{{Quote|...}}` blocks, unescape `&nbsp;`). Look up each of the field
   names cataloged in §1 by key, treating every one as optional since the
   set differs per series (e.g. try `Besonderes`, and if absent, fall back
   to combining `SonstigesWas`/`SonstigesInhalt` for Atlan).
4. `config/BookParserConfig.java` — add `PerrypediaParser` to the
   constructor and the `Map.of(...)` (`MetadataProvider.Perrypedia`,
   `perrypediaParser`).
5. `model/dto/settings/MetadataProviderSettings.java` — nested
   `Perrypedia { boolean enabled; }` class (no API key: Perrypedia has no
   auth/rate-limit tier).
6. `perrypediaId` field plumbing — grep-confirmed touch points, using
   `comicvineId` (id-only, no rating) as the template rather than
   `ranobedbId` (which also touches rating-specific code):
   `model/dto/BookMetadata.java`, `model/entity/BookMetadataEntity.java`,
   `model/dto/settings/MetadataProviderSpecificFields.java`,
   `model/dto/sidecar/SidecarIdentifiers.java`,
   `model/dto/EpubMetadata.java`, `model/MetadataClearFlags.java`,
   `model/dto/request/MetadataRefreshOptions.java`,
   `util/MetadataChangeDetector.java`,
   `service/BookRuleEvaluatorService.java`,
   `service/appsettings/SettingPersistenceHelper.java`,
   `service/bookdrop/BookdropMetadataService.java`,
   `service/metadata/sidecar/SidecarMetadataMapper.java`,
   `service/metadata/extractor/{Epub,Pdf,Cbx}MetadataExtractor.java`,
   `service/metadata/writer/PdfMetadataWriter.java`. Re-grep for
   `comicvineId` at implementation time in case any of these have moved.
7. Flyway migration `backend/src/main/resources/db/migration/V147__Add_perrypedia_id_column.sql`
   (next free version after `V146`; re-check the highest `V*` file at
   implementation time in case other migrations have landed):
   ```sql
   ALTER TABLE book_metadata
       ADD COLUMN perrypedia_id VARCHAR(100),
       ADD COLUMN perrypedia_id_locked BOOLEAN DEFAULT FALSE;
   ```

Frontend changes:

1. `frontend/src/app/features/book/data/book-response.models.ts` —
   add `'Perrypedia'` to `BOOK_METADATA_PROVIDERS`.
2. `frontend/src/app/shared/model/app-settings.model.ts` — add a
   `Perrypedia { enabled: boolean }` interface and `perrypedia: Perrypedia`
   entry on the settings container interface (mirrors `Ranobedb`/`Douban`
   at lines ~58-85), plus `perrypediaId: boolean` alongside the other
   provider-field lock flags (~line 220 area).
3. `frontend/src/app/features/settings/global-preferences/metadata-provider-settings/metadata-provider-settings.component.ts`
   (+ its `.html`) — add an `perrypediaEnabled` toggle following the
   `ranobedbEnabled`/`comicvineEnabled` pattern (this component is a single
   monolithic form, not per-provider subcomponents — no new component
   file needed).
4. `frontend/src/app/shared/metadata/metadata-field.config.ts` — register
   `perrypediaId` the way `ranobedbId` is registered (line ~45), so it
   shows up in the metadata viewer/editor and lock UI.
5. No match-weight entry needed (`metadata-match-weights-component.ts`
   only carries rating-bearing fields; Perrypedia has none).

## 4. Open questions to resolve before/during implementation

- **Cover images**: resolved by investigation, see §0 — filename
  convention isn't predictable across series, but the cover is available
  as an `<img>` in the already-fetched infobox HTML, no extra request
  needed. Not yet implemented.
- **Language/audience fit**: this provider is useful only for the German
  Perry Rhodan/Atlan Heftroman audience — confirm this is a wanted
  addition before investing in the wikitext parser (higher parsing risk
  than a JSON API source, given the per-series template-name and
  field-set variance in §1).
- **Real-world id/title conventions**: §2 flags that the exact strings to
  match in filenames/titles for Neo and Atlan (`PRN389` vs `PR Neo 389`,
  `A800` vs `Atlan 800`, etc.) haven't been surveyed against real files —
  needs a look at how these are actually named in practice (e.g. existing
  library content, common fan/scene naming) before finalizing the primary
  match regex.
- **Series scope**: confirm PR classic + Neo + Atlan is the full target
  list for v1 — other spin-offs (Silberband, Stardust, PR Extra, ...)
  are unresearched and excluded here.
- **Whether to add `DetailedMetadataProvider`** for v1 or defer it — the
  `Quelle:<prefix><id>` lookup that would back it is nearly identical to
  the primary match path in §2, so it's cheap either way; deferring keeps
  the first PR smaller.
- **Rate limiting**: no documented Perrypedia API rate limit was found;
  default to a conservative self-imposed limiter (mirror `RanobeDbParser`'s
  60 req/min token bucket) rather than assuming unlimited access.

## 5. Testing plan

Mirror `RanobeDBParserTest.java` / `ComicvineBookParserTest.java`: mock
`HttpClient`, stash real fixture responses under
`backend/src/test/resources/perrypedia/`, assert on the mapped
`BookMetadata`. Add a wikitext-parsing unit test with **one fixture per
series family** (PR classic `Mythos Erde (Roman)`, PR Neo
`Wenn Sterne bluten`, Atlan `Die Zeitfestung` — raw wikitext already
captured in §1 and reusable verbatim as fixtures) to exercise the varying
template name, field set, and nested-template values (`{{EVJ|...}}`,
`{{RZJ Atlan|...}}`, `{{todo}}`) from §1 — a test suite with only one
fixture would not catch a regression in any of the other two series.
