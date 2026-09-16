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
  and [[TASK-track-upstream-releases.md]] as their own separate commits.
  **Correction 2026-09-16 (superseded twice, see log below)**: as of
  2026-09-16, `AGENTS.md`, `CLAUDE.md`, `.gitignore` (a `TASK.md`/
  `TASK-*.md` exclusion entry), and **every** `TASK*.md` file in the repo
  are committed for real on this branch, at the user's explicit request —
  fully reversing the original init task's "not to be committed" call
  ([[TASK-init.md]]). Force-added past the `.gitignore` entry where
  needed, since these files are gitignored specifically so an *ordinary*
  `git add -A` never sweeps them in by accident, not to make them
  uncommittable outright.
- **`perrypedia-metadata-source`** — the clean PR branch, created only once
  the `-wip` branch is working end-to-end. Cut fresh from whatever
  `upstream/develop` is at that moment (not from `-wip`), and populated
  with **one single commit** containing the actual implementation
  (backend + frontend + migration + tests) — no `.md` files at all, so the
  diff GitHub shows on the PR is exactly the code change, nothing else.
  **Given the above, this exclusion list has grown**: every `TASK*.md`
  path, `AGENTS.md`, `CLAUDE.md`, and `.gitignore` all need explicit
  exclusion when cutting this branch — none of them are `.md`-filter-safe
  on their own (`.gitignore` isn't a `.md` path at all) or safe to assume
  excluded just because they were excluded before. Confirm with something
  like `git diff origin/develop..<wip-branch> -- . ':!*.md' ':!.gitignore'`
  (still needs `AGENTS.md`/`CLAUDE.md` covered by the `*.md` glob, and
  every `TASK*.md` also matches `*.md` — the one path that glob does
  **not** catch is `.gitignore`) rather than assuming any prior round's
  filter is still sufficient.

This keeps the messy/iterative history and the planning docs on `-wip`
only, while `perrypedia-metadata-source` stays a clean, single-commit,
docs-free PR candidate. Saved to memory as a general pattern for future
work in this repo, not just this one feature.

**2026-09-16: `AGENTS.md`/`.gitignore` committed, reversing part of the
original "never committed" call.** While writing
[[TASK-perrypedia-full-cover-image.md]] it came out that `TASK.md`/
`TASK-*.md` weren't actually gitignored in this repo, contradicting
`AGENTS.md`'s own "Task files" section (which says these must never be
committed) and the general-purpose `CLAUDE.md` convention for handling
task files. Fixed by adding a `/TASK.md`/`/TASK-*.md` entry to
`.gitignore` and a one-line note in `AGENTS.md` confirming they're
gitignored — user then explicitly asked to commit both, plus (for this
one instance only) the `TASK-perrypedia-full-cover-image.md` file itself
(force-added past its own new gitignore exclusion). **`AGENTS.md` is now
a real committed file on this branch**, no longer excluded the way
`CLAUDE.md` was at the time.

**2026-09-16, later the same day: everything else committed too.** User
asked to commit *all* remaining `TASK*.md` files plus `CLAUDE.md` as
well — no longer a one-off exception for a single TASK file.
`AGENTS.md`'s "Task files" section was revised accordingly: these files
are gitignored so a *blanket* `git add -A` doesn't sweep them in
unintentionally, but deliberate force-commits on a working branch like
this one are the established, sanctioned workflow, not a violation of
the gitignore. **The one rule that did not change**: none of this is
ever to reach a real pull request. That's now stated directly in
`AGENTS.md`'s "Task files" section itself (the durable, committed home
for this rule) as well as in the "Branch & commit strategy" note above
and in memory (`wip-then-clean-pr-branch`).

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

**Rebuilt and re-pushed again (2026-08-29)** after the cover-image
feature (`extractCoverUrl`, `33ef415d8`) — full backend test suite run
first (`./gradlew test`): 3913 tests, 0 failures, 0 errors. Frontend
tests could **not** be run locally — no `node`/`pnpm` on this host's
`PATH` at all (not just an unloaded version manager — no `nvm`/`volta`/
`asdf`/`mise` install found either); the Docker build's own frontend
stage is the only local validation of the frontend build for this round.
New digest `sha256:500c65dd2e96...`, was `sha256:6907be1f5412...`. All
four tags pushed (`perrypedia-metadata` + `v3.3.3-perrypedia-metadata`,
GHCR + zot).

**Deployment topology, corrected per user 2026-08-29**: `grimmory-dev`
isn't a permanently-running stack — it's stood up on demand for changes
the user considers possibly-breaking. Small enhancements (this
cover-image feature included) are tested directly against the single
always-running `grimmory` compose project
(`/home/grimmory/docker-compose.yml`, container `grimmory-server-1`) —
its absence from `docker compose ls` at push time doesn't mean it was
removed, just that it wasn't needed for this round. `grimmory-server-1`
was already tracking
`registry.raven-alioth.ts.net/digital-library/grimmory:v3.3.3-perrypedia-metadata`
(the zot version-prefixed tag) before this push — confirmed via `docker
inspect grimmory-server-1 --format '{{.Config.Image}}'` — so pushing
straight to it here was the correct, intended path for a change this
size, not an accident of a missing dev environment.
[[ghcr-local-test-image-tagging]] and [[dev-vs-prod-instance-color]]
corrected accordingly.

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
- **Perrypedia ID is not written to the EPUB `content.opf.ftl` template**
  (unlike Amazon/GoodReads, and unlike Apple Books after upstream #2616).
  Investigated during the 2026-09-15 rebase below: most single-ID
  providers (Comicvine, Hardcover, Douban, Ranobedb, Lubimyczytac — the
  pattern this provider followed) also don't get this treatment, so it's
  not a regression versus the chosen pattern. **Deliberately left as an
  open item, not added in this pass** — only worth doing if Perrypedia IDs
  embedded directly in generated EPUBs turns out to matter.

## 2026-09-15: rebase onto updated `develop`, upstream comparison

`origin/develop` had moved 62 commits since this branch was cut
(`0e675495b` → `539a0e080`), including upstream's own **OpenLibrary**
(#1764) and **Apple Books** (#1780) providers — landed independently, in
parallel with this work, following the same registration checklist
([[AGENTS.md]]). Local `develop` also had 1 stray commit (an upstream bot's
GoodReads API-key rotation, see [[goodreads-token-local-only]]) not
reachable from `origin/develop`; left alone rather than force-reset, since
rebasing onto `origin/develop` directly didn't require it.

`git rebase origin/develop` hit real conflicts in 11 files, all from
"register a new provider" touchpoints Apple Books/OpenLibrary and
Perrypedia both landed on independently: `BookParserConfig.java`,
`MetadataProvider.java`, `BookRuleEvaluatorService.java`,
`CbxMetadataExtractor.java`, `EpubMetadataExtractor.java`,
`CbxMetadataWriter.java`, `lock-unlock-metadata-dialog.component.ts`,
`magic-shelf-component.ts`, `metadata-searcher.component.ts`,
`metadata-advanced-fetch-options.component.ts`, plus a **modify/delete**
conflict on `SettingPersistenceHelper.java` — upstream's
`refactor(settings): simplify app settings service` (#2527) deleted that
355-line file entirely, inlining it into `AppSettingService.java` (429
lines). Resolved by porting Perrypedia's three additions (default
settings entry, null-provider/default-true field-builder entries,
`MetadataProviderSpecificFields` default-true entry) into the
corresponding new locations in `AppSettingService.java`, then `git rm`-ing
the old file. All resolutions were straightforward unions (both sides
added a provider/field to the same list) except one: this branch's own
later commit `18031727c` ("fix missing provider in priority dialog")
turned out to be adding `'Perrypedia'` to `providers`/`providersWithClear`
in `metadata-advanced-fetch-options.component.ts` in the **pre-refactor
single-line array format** — by the time that commit replayed, HEAD
already had the multi-line format (from Apple Books/OpenLibrary), so it
looked like a fresh conflict rather than the redundant reapplication it
actually was; resolved by keeping HEAD's (already-correct) side.

**One silent regression caught, not flagged as a conflict by git**: the
very first auto-merge of `metadata-advanced-fetch-options.component.ts`
(applying the main Perrypedia commit, before `18031727c` replayed)
silently dropped `'Perrypedia'` from those same `providers` /
`providersWithClear` arrays — git resolved it without a marker because the
line-level diff happened to not overlap, even though the resulting file
was wrong. Caught by manually reading the resolved file rather than
trusting "Auto-merging" output; fixed inline, then superseded correctly
when `18031727c` replayed. Lesson: after any rebase that touches a file
with no reported conflict, still worth a read if that file is one your
own commits also touch non-trivially — grep-for-your-feature-name sweeps
alone won't catch a dropped array entry when the same identifier still
appears elsewhere in the file for unrelated reasons.

**Database migration collision, found while investigating**: this
branch's `V147__Add_perrypedia_id_column.sql` collided with upstream's own
new `V147__Add_applebooks_provider.sql` (and `V148__Add_openlibrary_provider.sql`).
Renamed this branch's migration to `V149__Add_perrypedia_id_column.sql`
(content unchanged, so its checksum is unchanged) as part of the rebase.
**This only fixes the source tree — it does not fix a database that
already ran the old V147.** Any database (e.g. a personal dev instance)
that applied the original `V147__Add_perrypedia_id_column.sql` has a
`flyway_schema_history` row for version `147` describing that migration.
Deploying the renumbered code as-is will make Flyway see the classpath's
new (different) `V147__Add_applebooks_provider.sql` and complain about a
checksum/description mismatch for version 147, *and* try to (re-)run
`V149__Add_perrypedia_id_column.sql` as a new pending migration — which
will fail with a "column already exists" error, since that DDL already
ran.

**Reconciled against `grimmory-dev-db-1` on 2026-09-15 — and the first
attempt was incomplete, corrected here.** Step 1 below (the schema_history
row repoint) is necessary but **not sufficient on its own**: it stops the
checksum/description mismatch for version 147, but it also marks 149 as
already-applied while 147/148 are still pending — which Flyway's default
validator rejects as "out of order" (`Detected resolved migration not
applied to database: 147/148`), crashing the app on startup
(`FlywayValidateException` → `UnsatisfiedDependencyException` on
`jwtSecretRepository` → Tomcat fails to start). Confirmed live: this is
exactly what happened when `grimmory-dev-server-1` was restarted onto the
rebased image after step 1 alone.

**Step 2 fixes that**: apply the actual pending `V147`/`V148` DDL through
Flyway itself (not hand-crafted `INSERT`s — Flyway computes its own
checksums, which would be tedious and error-prone to replicate by hand),
using `-outOfOrder=true` for this one catch-up run only. This is a one-off
CLI invocation against the database, not a permanent app config change —
`FlywayConfig.java` still has `outOfOrder` off by default, so this
doesn't affect how the app behaves on any other database. Both new
migrations are trivial and idempotent (`ADD COLUMN IF NOT EXISTS`), so
this is safe to run even if unsure whether they already partially landed.

```sql
-- Step 1: repoint the old V147 row to V149 (checksum unchanged -- the SQL
-- content of the file didn't change, only its filename/version did).
-- Safe to run against a database that never had the old V147 (no-op) and
-- safe to run twice (no-op the second time).
UPDATE flyway_schema_history
SET version = '149',
    description = 'Add perrypedia id column',
    script = 'V149__Add_perrypedia_id_column.sql'
WHERE version = '147'
  AND script = 'V147__Add_perrypedia_id_column.sql';
```

```sh
# Step 2: apply the now-pending V147 (Apple Books) / V148 (OpenLibrary)
# migrations out of order, letting Flyway itself compute correct checksums.
# Run from a host that can reach the target DB's docker network/host+port;
# point -url/-user/-password at dev or prod as appropriate.
docker run --rm --network <db's docker network> \
  -v <repo>/backend/src/main/resources/db/migration:/flyway/sql:ro \
  flyway/flyway:12.4.0 \
  -url="jdbc:mariadb://<db host>:3306/grimmory" \
  -user=grimmory -password='<db password>' \
  -outOfOrder=true \
  migrate
```

```sql
-- Verify: 145, 146, 147, 148, 149 should all show success=1, in whatever
-- installed_rank order they actually ran (147/148 will show a later
-- installed_rank than 149, and an empty/later "Installed On" than 149 --
-- that's expected and harmless; the (version, success) columns are what
-- Flyway's own validator checks, not installed_rank order).
SELECT installed_rank, version, description, script, success
FROM flyway_schema_history
ORDER BY installed_rank;
```

Run both steps against each database **before** starting the app on the
rebased image. If step 1 matches 0 rows, that database never ran the old
V147 — skip straight to step 2 (147/148 will just apply as ordinary
pending migrations, no `-outOfOrder` needed in that case).

**Done for `grimmory-dev-db-1`** (2026-09-15): both steps run, verified
`book_metadata` has `applebooks_*`/`openlibrary_*`/`perrypedia_*` columns
and `flyway_schema_history` shows 147/148/149 all `success=1`;
`grimmory-dev-server-1` restarted clean (Tomcat up in ~9s, healthcheck
passing).

**Done for `grimmory-db-1` (prod) too** (2026-09-15): same two steps run
against it, same verified end state (147/148/149 all `success=1`, all
three column sets present). `grimmory-server-1` itself was **not**
touched or restarted — it's still running the old
`v3.3.3-perrypedia-metadata` image, which ignores the new columns; the
database is just pre-reconciled for whenever `v3.4.0-perrypedia-metadata`
actually gets deployed there. `docker-compose.template` in the `grimmory`
deploy repo has already been bumped to that tag (uncommitted there too),
but the live `docker-compose.yml` and running container are untouched.

**Verified against the two upstream PRs the user flagged** (#2616 fixing
Apple Books' missing `content.opf.ftl` entry, #2609 fixing an Apple
Books i18n copy-paste mislabel): Perrypedia has neither issue — see the
`content.opf.ftl` item above, and its `perrypediaId` i18n strings all
correctly read "Perrypedia ID" (no copy-paste-from-another-provider
mistake). Not yet done: a full field-by-field diff against OpenLibrary's
(#1764) or Apple Books' (#1780) complete implementations.

**Verification**: `./gradlew compileJava compileTestJava` passes clean.
No local `node`/`pnpm` toolchain in this environment, but the frontend
build is dockerized (`Dockerfile`'s `frontend-build` stage runs `pnpm -C
frontend run build:prod`) — ran `docker buildx build --target
frontend-build .` directly, which type-checks as part of `ng build
--configuration production`. Passes clean, including the
`metadata-advanced-fetch-options.component.ts` three-array restructure.

Not yet pushed — rebase was done locally; `origin/perrypedia-metadata-source-wip`
still points at the pre-rebase history, so publishing this needs a
force-push once reviewed.

## 2026-09-15: pushed rebase, deployed to dev + prod, recut clean PR branch

`perrypedia-metadata-source-wip` force-pushed to `origin` with the
rebased history above. Deployed the rebuilt image
(`v3.4.0-perrypedia-metadata`, see local-test-image log) to both
`grimmory-dev` and prod (`grimmory-server-1`) — both confirmed healthy,
Flyway clean, frontend serving the correct new build hashes.
`docker-compose.template` in the `grimmory` deploy repo bumped to match
and pushed (`dc7b111`).

**Recut `perrypedia-metadata-source`** (the clean PR branch — see
[[wip-then-clean-pr-branch]]): the existing one (`d43f9244c`) was cut
from the old pre-rebase base (`0279c15dc`), 62 commits stale versus
`origin/develop`'s current tip. Recut in a fresh `git worktree` from
current `origin/develop` (`539a0e080`): generated the full code diff
between `origin/develop` and the rebased `-wip` branch excluding every
`*.md` path (`git diff origin/develop..perrypedia-metadata-source-wip --
. ':!*.md'`), applied it cleanly (`git apply --check` passed first-try,
no conflicts), squashed into one commit (`6be5523bd`, 66 files, zero
`.md` files). Force-pushed over the old branch tip on `origin`.

**Fresh verification on the clean branch** (not just the -wip branch):
- Backend: `./gradlew test` — **3961 tests, 0 failures, 0 errors**
  (counted from the JUnit XML reports directly, not just eyeballing
  console output).
- Frontend build: `docker buildx build --target frontend-build .` —
  passes, same as on `-wip`.
- Frontend tests: ran `pnpm run test` for real numbers (no `node` on
  this host, so via a throwaway `node:24-alpine` container bind-mounting
  a temporary worktree) — **306 test files / 1720 tests passed** (80
  files / 136 tests skipped, pre-existing skips unrelated to this
  change). Slightly different from the draft PR description's "306
  files / 1709 tests" figure — that draft predates today's rebase;
  updated to this real count.
  **Same environment gotcha as the earlier Docker/Gradle mount** (see
  local-test-image log): running as root inside the container left the
  temp worktree root-owned, breaking `git worktree remove` afterward;
  fixed the same way, with a one-off `alpine chown -R $(id -u):$(id -g)`
  against the mount before retrying the removal.

**Not yet opened as a real PR — found a process reason to pause**: right
as this was being prepared, the user opened
https://github.com/orgs/grimmory-tools/discussions/2643 ("Perrypedia
Metadata Provider" feature request). grimmory-tools/grimmory's PR
template checklist requires "This PR links and implements an accepted
issue" (confirmed against the Apple Books #1780 / OpenLibrary #1764 PR
bodies fetched earlier as comparables) — a same-session discussion is
unlikely to already be accepted. See
[[perrypedia-upstream-discussion]]. **Decided (user, 2026-09-15): wait for the discussion first.** Branch
`perrypedia-metadata-source` stays pushed and ready; do not open the PR
until discussion #2643 gets a response/traction. Re-check its status
before opening.

The existing `TASK-pr-description-perrypedia.md` draft's title/body are
still a good starting point but need: the discussion link added, and the
test-count line refreshed to the numbers above.

## 2026-09-16: second rebase onto `develop` (now at `v3.4.1`), deploy tag bump

`origin/develop` (and `upstream/develop`) moved again since the
2026-09-15 rebase: `539a0e080` → `2d7a3e9c5`, including upstream's own
**modernised metadata searcher component**
(`fix(ui): modernise the metadata searcher component`, #2631) and a
`v3.4.1` tag. Re-fetched both `origin` and `upstream`, then
`git rebase origin/develop` on `perrypedia-metadata-source-wip`.

**One conflict** (vs. 11 last time — the provider-registration
touchpoints from the previous rebase are all long since merged and
didn't move again): `metadata-searcher.component.ts`. Upstream's #2631
rewrote the whole component (signal-based `providerHref`/`providerName`/
`providerKey` computed off `result.provider` directly, plus a new
`detailRequestFor`/`DETAIL_ID_FIELD` flow for providers implementing
`DetailedMetadataProvider`), replacing the old id-sniffing helpers
(`getProviderFromMetadata`, the old `getProviderHref`, `getProviderName`,
`trackByMetadata`) this branch's Perrypedia commit had extended. Verified
those old helpers aren't referenced anywhere else (grep across the
component directory) before dropping them — the new architecture doesn't
need a replacement for `getProviderFromMetadata`/`trackByMetadata` at
all. Ported the one bit of real behavior forward: added a `case
'Perrypedia':` arm to the new `providerHref` switch
(`https://www.perrypedia.de/wiki/Quelle:${result.perrypediaId}`, matching
the old inline URL). `PerrypediaParser` only implements `BookParser`, not
`DetailedMetadataProvider`, so no `DETAIL_ID_FIELD` entry needed —
confirmed against the backend source, not assumed. `npx tsc --noEmit`
clean on the resolved file afterward.

Rebase completed cleanly (23 commits replayed). Working-tree changes
present before the rebase (unrelated `docker-compose.yml` local test-tag
edit, in-progress `metadata-editor` moods/tags work, untracked
`AGENTS.md`/`CLAUDE.md`/`TASK-init.md`) were stashed first and popped
back afterward with no conflicts.

**Deploy tag bumped to `v3.4.1-perrypedia-metadata`** in
`docker-compose.template` in the sibling `grimmory` deploy repo (was
`v3.4.0-perrypedia-metadata`), validated with that repo's
`validate-compose` skill (renders and parses). **Left uncommitted** in
that repo per its `bump-version` skill's convention (only commit a
version bump when asked) — review and commit separately there. Note this
is only the version-label bump, matching the base this branch is now
rebased onto; it does **not** build or push a `v3.4.1-perrypedia-metadata`
image to any registry — that's a separate step (see the 2026-09-15
"Local test image" log entry for the build/push pattern) still to do
before that tag can actually be deployed.

**Not done this round, flagged as follow-up**: the squashed clean PR
branch `perrypedia-metadata-source` (`6be5523bd`, cut 2026-09-15 from
`origin/develop` at `539a0e080`) was **not** recut against the new tip —
it's now 23 commits stale the same way it was before the 2026-09-15
recut. Re-run that recut (fresh diff against current `origin/develop`,
excluding `*.md`, squash, force-push) before opening the real PR, same
as last time — still blocked on discussion #2643 getting traction per
the 2026-09-15 decision above, so no urgency yet.

Also created `TASK-perrypedia-provider-docs.md` in the sibling
`grimmory-docs-upstream` repo — a follow-up checklist for the docs site
(no `perrypedia` mentions there yet; RanobeDB is the closest existing
precedent to mirror) once this provider actually ships upstream.

**Recut the clean PR branch** (same day, follow-up): fresh `git worktree`
from current `origin/develop` (`2d7a3e9c5`), regenerated the non-`.md`
diff against the rebased `-wip` branch
(`git diff origin/develop..perrypedia-metadata-source-wip -- . ':!*.md'`,
66 files — same file count as the 2026-09-15 recut), applied cleanly
(`git apply --check` passed first try), squashed into one commit
(`a65a5aaf6`, same commit message as `6be5523bd` before it, DCO
sign-off), force-pushed over the old branch tip on `origin`.

Verified before pushing (lighter than the full 2026-09-15 pre-PR pass —
no full test suite this time, just confirm the squash didn't break
anything): `docker buildx build --target frontend-build .` — passes (Angular
production build, which type-checks as part of the build). `./gradlew
compileJava compileTestJava` — passes, no errors (one pre-existing
unrelated deprecation warning in `KoreaderUserControllerTest`). Run the
full verification pass (backend tests, frontend tests) before actually
opening the PR, same as last time.

**Correction to the earlier 2026-09-16 rebase entry above**: the `npx tsc
--noEmit` check claimed there had actually silently no-opped — `node`/
`npx` aren't installed on this machine at all (frontend is normally
built via Docker, as `local-test-image`/this entry both do); the command
errored with "command not found," but the error text didn't match the
`grep -i metadata-searcher` filter it was piped through, and a trailing
unconditional `echo "done"` masked the failure. **Not an established
verification** — caught only now, via the Docker build above, which
confirms the resolved `metadata-searcher.component.ts` does compile
correctly, so the outcome holds, but the earlier "clean" claim itself
was not actually checked at the time.

**Built and pushed a fresh image from the rebased `-wip` branch**
(same day, follow-up): working tree at push time also carried the
in-progress moods/tags autocomplete blur-commit fix (uncommitted
`metadata-editor.component.ts`/`.html`) — included in the image per the
same "Docker builds from the working tree, not git history" note as the
2026-08-29 round.

```
docker buildx build --platform linux/amd64 -t grimmory:local --load .
gh auth token | docker login ghcr.io -u hakan42 --password-stdin
docker tag grimmory:local ghcr.io/hakan42/grimmory:perrypedia-metadata
docker tag grimmory:local ghcr.io/hakan42/grimmory:v3.4.1-perrypedia-metadata
docker push ghcr.io/hakan42/grimmory:perrypedia-metadata
docker push ghcr.io/hakan42/grimmory:v3.4.1-perrypedia-metadata
docker tag grimmory:local registry.raven-alioth.ts.net/digital-library/grimmory:perrypedia-metadata
docker tag grimmory:local registry.raven-alioth.ts.net/digital-library/grimmory:v3.4.1-perrypedia-metadata
docker push registry.raven-alioth.ts.net/digital-library/grimmory:perrypedia-metadata
docker push registry.raven-alioth.ts.net/digital-library/grimmory:v3.4.1-perrypedia-metadata
```

All four tags (`perrypedia-metadata` + `v3.4.1-perrypedia-metadata`,
GHCR + zot) live at digest `sha256:d0c21158...`. `v3.4.1-perrypedia-metadata`
replaces `v3.3.3-perrypedia-metadata` as the version-matched tag,
matching the `docker-compose.template` bump in the sibling `grimmory`
repo (`a8b35b6`, committed separately) — `grimmory-server-1` (prod) picks
this tag up on its next `run.sh up`/redeploy, not automatically like
`grimmory-dev` does for the floating tag. Full backend/frontend test
suites not re-run for this build (already covered by the
`compileJava`/`compileTestJava` + Docker frontend-build check above,
which this build itself repeats) — same reduced-verification bar as the
clean-branch recut earlier today.

**PR opened**: https://github.com/grimmory-tools/grimmory/pull/2654,
against `perrypedia-metadata-source` at `a65a5aaf6`, targeting
`develop`. Discussion #2643 still had zero comments/reactions/answer at
open time — **user decided to open anyway**, overriding the 2026-09-15
"wait for traction" decision, rather than waiting further. Posted a
comment on #2643 linking the PR and committing to keep it updated
through review; same commitment added to the PR body's Additional
Context section.

Ran the real `just` check recipes before opening, on a fresh worktree of
the clean branch (not the reduced compile-only check from the build
round above):
- `api check` (`./gradlew check --no-daemon --parallel --build-cache`):
  **3961 tests, 0 failures, 0 errors** (3 skipped) — summed from the
  JUnit XML reports directly.
- `ui check`'s typecheck/lint/lint:styles/test steps (`build` not
  re-run — already covered by the Docker frontend-build check earlier
  today, same source): all four steps passed under `set -e` (so a
  failure in typecheck/lint/stylelint would have stopped the script
  before test ran) — **1722 tests passed, 0 failed** (135 skipped, 307
  test files passed / 79 skipped of 386). Needed `pnpm -C frontend
  install --frozen-lockfile --ignore-scripts` as an explicit second
  install step after the root workspace install — running `pnpm -C
  frontend run typecheck` directly after only a root install triggered
  pnpm's own internal dependency-status re-check, which tried (and
  failed, `ERR_PNPM_IGNORED_BUILDS`) to reinstall without inheriting
  `--ignore-scripts`, even though `pnpm-workspace.yaml`'s `allowBuilds`
  should have covered it.

PR body test-count line updated to these real numbers (draft had
2026-09-15's 3961/306-1720, close but for the previous recut, not this
one — re-verified rather than assumed still accurate).

## 2026-09-16: `DetailedMetadataProvider` implemented, coderabbitai review addressed

Implemented the deferred item flagged in §4 (see the struck-through
entry above) and in PR #2654's Additional Context: `PerrypediaParser`
now `implements DetailedMetadataProvider`, with `fetchDetailedMetadata`
reusing the existing `extractSourceId`/`fetchBySourceId` pair (`git
show 63ba17311`). `DETAIL_ID_FIELD` in `metadata-searcher.component.ts`
gets a `Perrypedia: 'perrypediaId'` entry. 6 new tests in
`PerrypediaParserTest` (one per series family, a case/separator variant,
two no-request-made guards) — that file goes from 11 to 17 tests, all
passing. This was committed directly to `-wip` first (backend +
frontend + tests together is small enough not to warrant its own
sub-branch), not opened as a separate PR — see below for how it reached
the existing PR instead.

**coderabbitai posted 7 automated review findings on PR #2654.**
Treated as untrusted review data per usual practice — verified each
against the actual current code before acting, rather than trusting the
finding text. Fixed 3, skipped 4 as invalid or not actually applicable
(`git show f9d70e4ec` has the fixes; the commit message there has the
full per-finding reasoning for what was skipped and why — not
duplicated here to avoid drift between the two). Two of the skipped
findings cited line numbers that didn't match what's actually at that
location in the file (`MetadataProviderSettings.java:14` is just a
`private Perrypedia perrypedia;` field with no doc-comment at all;
`MetadataRefreshService.java:357` is an unrelated `addProviderToSet`
call, not the `isProviderEnabled` switch the finding described) — a
reminder that an automated review's line references aren't necessarily
where its own described problem actually lives; always re-locate the
real code before judging or fixing.

**Recut the clean PR branch a second time** to bring both new commits
(`63ba17311`, `f9d70e4ec`) into PR #2654's scope — same worktree/diff/
squash/force-push process as the 2026-09-16 recut above,
`origin/develop` still unchanged at `2d7a3e9c5`. New squash commit
`6a747a45a`, same file count (66, no new files this round). Full
`./gradlew check` re-run on the recut branch before pushing: **3967
tests, 0 failures, 0 errors** (up from 3961 — the 6 new
`fetchDetailedMetadata` tests). Frontend Docker build re-run too, clean
(cached — this round's fixes were backend-only, so nothing to
re-verify there beyond confirming the cache hit means no frontend files
changed).

PR #2654's description updated to move `DetailedMetadataProvider` from
the deferred-items list into Changes, refresh the test-count line to
3967, and add a line to AI Disclosure noting coderabbitai's suggestions
were reviewed and addressed (matching what actually happened — reviewed
and selectively applied, not blindly accepted).

**Docstring Coverage pre-merge check — deliberately not chased.**
Separate from the 7 inline review findings above: coderabbitai's
pre-merge checks summary (an issue-level comment, not a line comment)
flagged "Docstring Coverage: 13.95% (required: 80.00%), 86 functions
across 50 files" as the one failing check (4 others passed, including
Title and Description). Checked whether this reflects a real gap
against this codebase's own convention before deciding whether to act
on it: sampled per-method javadoc coverage on three comparable,
already-merged provider classes (`RanobeDbParser`,
`ComicvineBookParser`, `AudibleParser`) — all effectively **0%**
between them. So an 80% threshold here isn't this project's actual
standard, it's coderabbitai's generic default, and writing docstrings
on ~86 functions just to satisfy it would be pure noise inconsistent
with every comparable class already in the codebase. **Decided (user,
2026-09-16): leave as-is, don't chase the metric.** Flagged as a
"Warning," not a blocking check — SonarCloud passed cleanly. Worth
re-checking if a maintainer raises it directly in review, but not
worth pre-emptively satisfying a bot metric the codebase itself doesn't
follow.

**coderabbitai rescan (triggered by user comment) surfaced 2 more
findings and confirmed the earlier 13 line comments** — total across
both rounds was 15 inline findings plus the docstring pre-merge check,
not the 7-8 relayed in the first batch. The rescan ran against
`634665e78` (after the first round of fixes) and added two new
`PerrypediaParser.java` findings not present in the original review:

- **Line ~163: swallowed `InterruptedException`.** Real — verified
  `MetadataRefreshService` (lines 186-188) really does check for a
  `RuntimeException` whose cause is `InterruptedException` as its
  cancellation signal, and 8 of this codebase's other parsers already
  restore-and-rethrow correctly; only `RanobeDbParser` (which this
  rate limiter was deliberately modeled on) shares the flaw. **Fixed**
  (`git show 2292d2535`) — split the catch in both `fetchBySourceId`
  and `fetchBySearch`, restore the interrupt flag, rethrow as
  `RuntimeException`.
- **Line ~187: no per-request HTTP timeout.** Real gap (only the
  shared client's 10s *connect* timeout exists, no response timeout),
  but checked how widespread it actually is: 12 of 14 metadata parsers
  in this codebase share it, including `RanobeDbParser` again. **Not
  applied** — a per-parser patch wouldn't close the actual risk;
  flagged in the PR body as a candidate for a centralized fix on the
  shared `HttpClient` bean instead.

Also re-examined the `PdfMetadataWriter.java:290` "locked perrypediaId
dropped during XMP rebuild" finding from the first batch (present in
that batch but not one of the ones the user relayed to me at the time —
caught it while cross-referencing the full comment list against what
had actually been fixed). **Not applied**: `MetadataCopyHelper.copyPerrypediaId`
is structurally identical to `copyComicvineId`/`copyOpenlibraryId` — if
this is a real bug, it already affects every locked single-ID field in
existing shipped code, not something specific to or introduced by this
PR.

Committed the interruption fix (`2292d2535`), pushed `-wip`, recut the
clean branch a third time (`0f706f024`, still 66 files, `origin/develop`
still unchanged at `2d7a3e9c5`), compile-checked, force-pushed. Backend
test count unchanged at 3967 (no new test added for the interruption
fix — not easily unit-testable without mocking interrupt timing, and
the existing `PerrypediaParserTest` suite doesn't cover cancellation
paths for any provider, so adding one just for this would be
inconsistent with how the rest of the suite tests this class of
behavior). PR body updated again: interruption fix added to Changes,
the timeout gap and docstring-coverage decision both written into
Additional Context so a reviewer sees the reasoning instead of an
unexplained gap, AI Disclosure's coderabbitai line updated to "4 real
issues" across both rounds.

**Resolved the 4 GitHub review threads for what was actually fixed**
(`isProviderEnabled`, URI encoding, `PdfProcessor` truncation, the
interruption fix) via `gh api graphql`'s `resolveReviewThread`
mutation — left the other 11 threads open/unresolved, since they
weren't fixed and a silent resolve would misrepresent that.

## 2026-09-16: PR #2654 rejected — moving to local-only fork maintenance

Closed by maintainer `alexhb1` at 12:07:32Z, same minute the last PR
body edit went out. Reason given, verbatim: "We're not looking for any
new metadata providers right now due to how convoluted the process is.
Once we have something more suitable in place, e.g a plugin
architecture, we can start promoting some suggestions to issues and
accepting PRs." This is a blanket policy call, not a response to
anything code-quality-related in this PR (all coderabbitai/SonarCloud
checks were passing at close time) — and not something the earlier
"wait for discussion #2643 to get traction" caution would have
prevented either way, since it's independent of discussion/issue
status entirely.

**Decided (user, 2026-09-16): keep this as a local-only fork.** Not
re-attempting a PR. Going forward, this becomes periodic maintenance
rather than upstreaming work: rebase `-wip` onto each new upstream
`grimmory-tools/grimmory` tagged release as it ships (same process as
the 2026-09-15 and 2026-09-16 rebase entries above — fetch, rebase,
resolve conflicts, optionally recut the clean single-commit branch if
still useful as a reference/backup rather than a PR candidate, bump the
`vX.Y.Z-perrypedia-metadata` tag in the sibling `grimmory` deploy
repo's `docker-compose.template`, build and push the image). Saved to
memory as [[perrypedia-upstream-discussion]] (content rewritten to
reflect the outcome, despite the slug's now-dated name — the file
covers the whole arc: discussion → PR → rejection → local-fork
decision, not just the original discussion-opening event).

Branches (`perrypedia-metadata-source-wip`, `perrypedia-metadata-source`)
left as-is on `origin` (`hakan42/grimmory`) — not deleted, no cleanup
requested. The closed PR #2654 itself also left as-is (closed, not
deleted — GitHub doesn't allow deleting a PR anyway).

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
- ~~Whether to add `DetailedMetadataProvider` for v1 or defer it~~ —
  **implemented 2026-09-16**, see the dated log entry below. Was cheap
  as predicted: `fetchDetailedMetadata` reuses `extractSourceId` +
  `fetchBySourceId` directly.
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
