# Fetch the full-resolution Perrypedia cover image, not just the thumbnail

## Context

`PerrypediaParser.extractCoverUrl` (`backend/src/main/java/org/booklore/service/metadata/parser/PerrypediaParser.java`,
see [[TASK-metadata-perrypedia.md]] §0) currently sets `BookMetadata.thumbnailUrl`
from the **widest `srcset` candidate** of the `<img>` in the rendered
infobox HTML returned by the existing `action=parse` call — e.g. for
`PR3393.jpg` that's a 822px-wide JPEG thumbnail. The actual uploaded
cover on Perrypedia is much larger (1029×1500 for that same issue) and
lives at a different URL that the infobox HTML never links to directly.
This task is about fetching that full-resolution original instead of
settling for the largest available thumbnail. Implementation is
deferred — this file is the researched plan only.

## PR hygiene note

This file, and the `.gitignore`/`AGENTS.md` changes that accompanied it
(2026-09-16 — gitignoring `TASK.md`/`TASK-*.md`, which wasn't already the
case in this repo), were committed to the `-wip` branch at the user's
explicit request. **Neither belongs in the eventual clean, single-commit
PR branch** (`perrypedia-metadata-source`, see
[[TASK-metadata-perrypedia.md]]'s "Branch & commit strategy" section):
every `TASK*.md` file is excluded there by convention already, and the
`.gitignore` change needs an *explicit* additional exclusion when cutting
that branch, since it isn't a `.md` path and the usual `:!*.md` diff
filter won't catch it on its own.

## Research (live-verified 2026-09-16)

Checked `Datei:PR3393.jpg`, `Datei:Neo390.jpg`, and `Datei:A124_1.JPG`
(the three examples given), plus one `Kategorie:Cover_-_Rhodan_Heft`
listing page.

### Requests to perrypedia.de need a same-site `Referer`

Plain `curl` (any `User-Agent`, no `Referer`) got a **403 Forbidden**
from Apache on every path tried, including `/wiki/...` article pages and
`/api.php` — even though the exact same `action=parse` calls are known to
work from the deployed backend (see [[TASK-metadata-perrypedia.md]]'s
2026-08-29 log). Adding `-e "https://www.perrypedia.de/"` (a same-site
`Referer` header) made all of the same requests return 200 immediately,
no cookies required. Whatever WAF rule is doing this reacts to something
about a bare `curl` request specifically, not to IP reputation — worth
sending a `Referer: https://www.perrypedia.de/` header (or the
originating article's own URL) alongside the existing `User-Agent` if a
future direct-image or `Datei:`/`Kategorie:` request ever gets an
unexpected 403 from real backend traffic. Also hit a **transient,
IP-wide** block partway through this research (every path 403ing for
~10–15s at a time, including previously-200 URLs) that self-cleared after
a short backoff — pace requests and don't retry in a tight loop if
implementing live tests against the real site.

### The direct download link ("Originaldatei") — confirmed structure

A `Datei:<filename>` page's HTML has the full-resolution URL in two
places, both identical:

```html
<div class="fullImageLink" id="file">
  <a href="/mediawiki/images/c/c6/PR3393.jpg">
    <img alt="Datei:PR3393.jpg" src=".../thumb/c/c6/PR3393.jpg/411px-PR3393.jpg?20260804153630" .../>
  </a>
  ...
</div>
<div class="fullMedia">
  <p><a href="/mediawiki/images/c/c6/PR3393.jpg" class="internal" title="PR3393.jpg">Originaldatei</a>
     (1.029 × 1.500 Pixel, Dateigröße: 465 KB, MIME-Typ: image/jpeg)</p>
</div>
```

So either `div#file a[href]` or `div.fullMedia a.internal[href]` on the
`Datei:` page gives the same absolute-path original file URL
(`/mediawiki/images/c/c6/PR3393.jpg`) — no need to specifically anchor on
the German label text "Originaldatei". Confirmed identical shape for
`Neo390.jpg` (`/mediawiki/images/2/2a/Neo390.jpg`, 1528×2244).

### Better: `Special:FilePath` needs no `Datei:` page fetch at all

MediaWiki's standard `Special:FilePath/<filename>` special page (present
on any MediaWiki install, not Perrypedia-specific) redirects straight to
the original file, and Perrypedia has it enabled. Confirmed the full
redirect chain for `PR3393.jpg`:

```
GET /wiki/Special:FilePath/PR3393.jpg
  301 -> /wiki/Spezial:Dateipfad/PR3393.jpg   (localized alias, this wiki's UI language is German)
  302 -> /wiki/Spezial:Weiterleitung/file/PR3393.jpg
  301 -> /mediawiki/images/c/c6/PR3393.jpg
  200 OK
```

This means the **filename alone is enough** to reach the original image
— exactly the filename `extractCoverUrl` already reads off the infobox
`<img alt="...">` attribute (see existing `NON_COVER_IMAGE_FILENAMES`
filtering) — with no need to fetch a `Datei:` page or guess the
`/images/<hash1>/<hash2>/` hash-bucket path at all. Two implementation
options given this:

1. **Store the `Special:FilePath/<filename>` URL itself** as
   `thumbnailUrl` and let the client follow the redirect chain when it
   actually loads the image. Zero extra backend HTTP calls, ever — same
   request count as today. Only viable if nothing server-side needs the
   final resolved URL (e.g. no server-side image caching/proxying of
   `thumbnailUrl` today — check `BookCoverService` and any other
   `thumbnailUrl` consumer before assuming this).
2. **Resolve it server-side** with one additional HTTP request
   (`HttpClient` with redirect-following enabled, `HEAD` or `GET`),
   storing the final `/mediawiki/images/...` URL. One extra call per
   fetch, but the stored value is a stable direct link rather than a
   redirect chain.

Prefer option 1 unless something downstream needs the final URL — it's
strictly fewer HTTP calls, per the "minimize HTTP calls" goal.

### Casing in the URL matters — confirmed, not assumed

`Special:FilePath/pr3393.jpg` (lowercase extension; MediaWiki
auto-capitalizes only the *first* character of a title, not the rest)
redirects through the same first two hops but then **404s** instead of
resolving — `/wiki/Spezial:Weiterleitung/file/pr3393.jpg` → 404. So the
filename's casing after the first letter must be preserved exactly as
Perrypedia has it uploaded (e.g. Atlan's `A124_1.JPG`, all-caps
extension) — this is exactly why `extractCoverUrl` already reads the
filename off the rendered `alt` attribute instead of constructing it, and
that same already-correct value is what must be reused for
`Special:FilePath`/the `Datei:` page lookup too. Don't re-derive or
normalize the filename's case anywhere in this new code path.

### A zero-HTTP-call alternative: transform the thumbnail URL in place

The infobox thumbnail URL already extracted by `widestImageUrl` has a
predictable MediaWiki shape that can be string-transformed into the
original without any request at all:

```
.../images/thumb/<h1>/<h2>/<filename>/<width>px-<filename>?<cachebuster>
  -> .../images/<h1>/<h2>/<filename>
```

e.g. `.../thumb/c/c6/PR3393.jpg/822px-PR3393.jpg?20260804153630` →
`.../c/c6/PR3393.jpg`. Verified this produces the same URL
`Special:FilePath` resolves to, for both `PR3393.jpg` and `Neo390.jpg`.
This is genuinely free (no request), but it depends on an **undocumented
internal MediaWiki storage convention** rather than the officially
documented `Special:FilePath` mechanism — recommend using it only as a
cross-check/fallback if `Special:FilePath` is unavailable for some
reason, not as the primary source of truth. Note the thumbnail URL now
carries a `?<timestamp>` cache-busting query string (it didn't in the
2026-08-29 research captured in [[TASK-metadata-perrypedia.md]] §0) — any
regex/transform must strip that too, not just the `/thumb/`+width
segment.

### Kategorie fallback — for when the infobox has no cover at all

`extractCoverUrl` returning `null` (no infobox `<img>`, or only
non-cover icons present) is the trigger for this fallback, using the
three category URLs given:

- `Kategorie:Cover_-_Rhodan_Heft` (classic), `_Neo_Heft`, `_Atlan_Heft`.
- `filefrom=<filename>` jumps the listing to start near that filename
  alphabetically — confirmed against `Kategorie:Cover_-_Rhodan_Heft&filefrom=PR0122.jpg`
  (200 OK, listing starts at `PR0122.jpg`).

Confirmed page structure — a standard MediaWiki category gallery, so easy
to parse without guessing:

```html
<li class="gallerybox" style="width: 155px">
  <a href="/wiki/Datei:PR0122.jpg">...</a>
  ...
  <div class="galleryfilename galleryfilename-truncate" title="Datei:PR0122.jpg">PR0122.jpg</div>
</li>
```

Parse `li.gallerybox`, read `div.galleryfilename`'s `title` attribute
(`Datei:<filename>`, exact case) or text content (bare filename) to find
the entry matching the wanted issue number, then feed that filename into
the `Special:FilePath` step above. One extra request for the category
page, only hit when the primary infobox-image path already came up
empty — no `Datei:` page fetch needed even in this fallback, since the
filename harvested from the gallery is enough for `Special:FilePath`
directly.

**Not yet verified live**: the exact `filefrom` value needed to land near
an arbitrary issue number for each series, since the per-series filename
padding/separator convention isn't fully confirmed — see below.

## Open questions to resolve before/during implementation

- **Per-series filename convention isn't fully nailed down.** Examples
  seen so far: classic `PR<4-digit zero-padded>.jpg` (`PR0122.jpg`,
  `PR3393.jpg`); Neo `Neo<3-digit, unpadded>.jpg` (`Neo201.jpg`,
  `Neo390.jpg`); Atlan is the least certain — `Datei:A124_1.JPG` (given)
  uses an underscore before the `_1` suffix, but the category `filefrom`
  example given is `A259+1.JPG`, and a literal `+` in a URL query string
  is ambiguous with an encoded space (`A259 1.JPG`). Needs a live check
  against a real Atlan `Datei:` or category page before hardcoding a
  pattern — don't guess.
- **Whether option 1 or 2 above (store the redirect URL vs. resolve it
  server-side) is right** depends on how `thumbnailUrl` is consumed
  elsewhere in the codebase (caching, proxying, EPUB/CBX cover
  embedding, etc.) — check those call sites before picking.
- Only the classic/Neo/Atlan examples given were checked; the Kategorie
  gallery parsing approach should generalize to all three (same
  MediaWiki category-page rendering), but wasn't separately re-verified
  for Neo/Atlan category pages this round.

## Testing plan (sketch)

Mirror the existing `extractCoverUrl` tests in `PerrypediaParserTest`
(widest-`srcset` selection, disambiguation-icon skip, no-image null
case). Add, using captured real fixture HTML/redirect chains rather than
live requests (same pattern as the existing wikitext `.fixture` files):

- Thumbnail-URL → original-URL transform (or `Special:FilePath`
  resolution, depending on which option is chosen) for one fixture per
  series family, including a filename with the `_1` Atlan-style suffix
  and mixed-case extension, to guard the casing behavior confirmed above.
- Kategorie fallback: a captured gallery-page fixture, asserting the
  right filename is picked out of several `li.gallerybox` entries.
- Regression guard for the cache-busting query string now present on
  thumbnail URLs (`?<timestamp>`), if the string-transform approach is
  used at all.

## Out of scope here

- Re-litigating whether cover images should be fetched at all — already
  decided and shipped (`extractCoverUrl`, [[TASK-metadata-perrypedia.md]] §0).
  This is strictly an upgrade from "best available thumbnail" to
  "original upload".
- The Perrypedia PR/upstreaming question — this provider is a
  local-only fork per [[perrypedia-upstream-discussion]]; this change
  lands the same way prior Perrypedia work has (`-wip` branch, see
  [[wip-then-clean-pr-branch]]), not as a fresh upstream PR attempt.
