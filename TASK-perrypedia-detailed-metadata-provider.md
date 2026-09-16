# Implement `DetailedMetadataProvider` for Perrypedia

## Context

Deferred item from the Perrypedia metadata provider work
([[TASK-metadata-perrypedia.md]] §4, and called out explicitly in the
Additional Context section of
[PR #2654](https://github.com/grimmory-tools/grimmory/pull/2654)):
`PerrypediaParser` (`backend/src/main/java/org/booklore/service/metadata/parser/PerrypediaParser.java`)
currently implements `BookParser` only, not `DetailedMetadataProvider`
(`backend/src/main/java/org/booklore/service/metadata/parser/DetailedMetadataProvider.java`,
a one-method interface: `BookMetadata fetchDetailedMetadata(String providerItemId)`).

This means the metadata search results dialog's "fetch full details on
click" flow (`onBookClick` in
`frontend/src/app/features/metadata/component/book-metadata-center/metadata-searcher/metadata-searcher.component.ts`,
via `detailRequestFor`/`DETAIL_ID_FIELD`) never fires for Perrypedia
results — only for the four providers currently in `DETAIL_ID_FIELD`
(GoodReads, Amazon, Audible, Comicvine).

## Why this is cheap

`PerrypediaParser` already has everything this needs, built for the
primary search-by-ID path:

- `private record SourceId(String prefix, String number)` (line ~87),
  with `key()` returning `prefix + number` (e.g. `"PR3000"`,
  `"PRN389"`, `"A800"`) — this is exactly the string stored in
  `BookMetadata.perrypediaId` (set from `sourceId.key()` in
  `toMetadata`, line ~261).
- `private SourceId extractSourceId(String text)` (line ~129) parses
  that same `prefix+number` format back out via
  `SOURCE_ID_PATTERN` (`(?i)\b(PRN|PR|A)\s*-?\s*(\d{1,4})\b`, line 67)
  — already round-trip-compatible with `key()`'s output, confirmed by
  reading both.
- `private BookMetadata fetchBySourceId(SourceId sourceId)` (line ~140)
  does the actual `action=parse` lookup against
  `Quelle:<prefix><number>` and returns a fully-populated
  `BookMetadata` — this is the real "fetch by ID" implementation
  already, just not exposed as `fetchDetailedMetadata`.

So `fetchDetailedMetadata(String providerItemId)` is close to:

```java
@Override
public BookMetadata fetchDetailedMetadata(String providerItemId) {
    if (providerItemId == null || providerItemId.isBlank()) return null;
    SourceId sourceId = extractSourceId(providerItemId);
    if (sourceId == null) return null;
    return fetchBySourceId(sourceId);
}
```

Both `extractSourceId` and `fetchBySourceId` are already `private` —
no visibility changes needed since the new method lives in the same
class.

## Backend wiring already generic — no other backend changes needed

Confirmed in `BookMetadataService.getDetailedProviderMetadata`
(`backend/src/main/java/org/booklore/service/metadata/BookMetadataService.java`,
line ~156): it looks up the parser from `parserMap` and does an
`instanceof DetailedMetadataProvider` check — genuinely provider-agnostic,
no per-provider dispatch to touch. Adding the interface to
`PerrypediaParser`'s `implements` clause is sufficient; no controller or
service changes needed.

## Frontend: one line

`DETAIL_ID_FIELD` in `metadata-searcher.component.ts` (line ~17):

```ts
const DETAIL_ID_FIELD: Record<string, keyof BookMetadata> = {
  GoodReads: 'goodreadsId',
  Amazon: 'asin',
  Audible: 'audibleId',
  Comicvine: 'comicvineId',
};
```

Add `Perrypedia: 'perrypediaId',`. That's the only frontend change —
`detailRequestFor`/`onBookClick` are already generic over this map.

## Testing plan

Mirror `ComicvineBookParserTest`'s `fetchDetailedMetadata` tests. The
three existing fixtures in `backend/src/test/resources/perrypedia/`
(`mythos_erde.fixture` = PR classic `PR3000`, `wenn_sterne_bluten.fixture`
= Neo `PRN389`, `die_zeitfestung.fixture` = Atlan `A800` — see
`PerrypediaParserTest`) already back the `action=parse` response
`fetchBySourceId` consumes, so `fetchDetailedMetadata` tests can likely
reuse them directly rather than needing new fixtures — since the new
method is a thin wrapper around the same call. Add cases for:

- A valid id per series family (`PR3000`, `PRN389`, `A800`) round-trips
  through `extractSourceId` correctly and returns the same metadata
  `fetchBySourceId` would.
- Malformed input (`null`, blank, a string that doesn't match
  `SOURCE_ID_PATTERN`) returns `null` without making a request.
- Case/format variance `extractSourceId` already tolerates (lowercase
  prefix, an optional separator) — confirm it still round-trips from a
  stored `perrypediaId` value, even though `key()` itself only ever
  produces the canonical uppercase-no-separator form.

## Out of scope here

- Not revisiting the cover-image-is-thumbnail-only limitation ([[TASK-metadata-perrypedia.md]]
  §0) — unrelated.
- Not surveying real-world Neo/Atlan filename conventions — separate
  deferred item, unrelated to this one.
