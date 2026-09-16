# AGENTS.md

Guidance for coding agents working in this repository.

## What this is

Grimmory is a self-hosted digital library application: an independent
community fork of BookLore, providing smart shelves, automatic metadata
matching, Kobo & KOReader sync, BookDrop imports, OPDS support, and a
built-in reader for EPUB, PDF, comics, and audiobooks. This repository is
the application source code (backend + frontend), not a deployment or
install repo. The backend's base Java package is still `org.booklore`
(retained from the pre-fork codebase).

## Project structure and build commands

See [DEVELOPMENT.md](DEVELOPMENT.md) for the top-level directory layout
and the `just` command surface (root, `api`, and `ui` namespaces). Component-
specific setup, build, and test workflows are in
[backend/DEVELOPMENT.md](backend/DEVELOPMENT.md) and
[frontend/DEVELOPMENT.md](frontend/DEVELOPMENT.md). Repository-wide
contribution process (branching, PR requirements, release semantics) is in
[CONTRIBUTING.md](CONTRIBUTING.md).

Backend package layout (`backend/src/main/java/org/booklore/`): `app/`,
`browse/`, `config/`, `context/`, `controller/`, `convertor/`, `crons/`,
`exception/`, `interceptor/`, `mapper/`, `model/` (`dto/`, `entity/`,
`enums/`), `nativelib/`, `repository/`, `security/`, `service/`, `task/`,
`util/`.

Frontend layout (`frontend/src/app/`): `core/`, `features/`, `shared/`,
following standard Angular standalone-component structure.

## Metadata providers

Metadata providers ("metadata plugins" in casual usage) fetch book/author
metadata and covers from external sources (Google Books, Amazon,
GoodReads, Hardcover, Comicvine, Douban, Lubimyczytac, Ranobedb, Audible).
There is no runtime plugin-loading mechanism (no SPI/`ServiceLoader`, no
external jars, no plugin manager) — a "provider" is a Spring `@Service`
bean compiled into the backend and wired through an explicit map, nothing
more.

Provider code lives under
`backend/src/main/java/org/booklore/service/metadata/parser/` (one file
per provider, e.g. `GoogleParser.java`, `AmazonBookParser.java`,
`HardcoverParser.java`; provider-specific helper code such as Hardcover's
GraphQL DTOs lives in a subpackage, e.g. `parser/hardcover/`). Orchestration
sits one level up in `service/metadata/` (`BookMetadataService`,
`MetadataRefreshService`, `MetadataManagementService`,
`MetadataMatchService`, `BookCoverService`, etc.).

Four independent interfaces cover different provider capabilities — a book
metadata source only needs the first, optionally the second:

- **`BookParser`** (`service/metadata/parser/BookParser.java`) — the core
  contract: `fetchMetadata(...)`, `fetchTopMetadata(...)`, and a
  `fetchMetadataStream(...)` default method. Required for any book metadata
  source.
- **`DetailedMetadataProvider`** (`service/metadata/parser/DetailedMetadataProvider.java`)
  — optional; implement when the source supports fetching full details by
  its own item ID (`fetchDetailedMetadata(providerItemId)`). Currently
  implemented by `AmazonBookParser`, `GoodReadsParser`, `ComicvineBookParser`,
  `AudibleParser`.
- **`AuthorParser`** (`service/metadata/parser/AuthorParser.java`) — author
  search/lookup, currently only implemented by `AudnexusAuthorParser`.
- **`BookCoverProvider`** (`service/metadata/BookCoverProvider.java`) —
  cover image search, currently only implemented by `DuckDuckGoCoverService`.

To add a new book metadata provider:

1. Add a value to the `MetadataProvider` enum
   (`model/enums/MetadataProvider.java`).
2. Implement `BookParser` (and `DetailedMetadataProvider` if applicable) as
   a `@Service`.
3. Register it in the `parserMap` bean in
   `config/BookParserConfig.java` (add a constructor parameter and a
   `Map.of(...)` entry) — this map is the only registration mechanism;
   there is no classpath scanning for providers.
4. If the provider needs configuration (API key, enabled flag, etc.), add a
   nested settings class to `MetadataProviderSettings`
   (`model/dto/settings/MetadataProviderSettings.java`), matching the
   pattern of the existing per-provider settings classes there.
5. Add the provider name to the `BOOK_METADATA_PROVIDERS` array in
   `frontend/src/app/features/book/data/book-response.models.ts` — this
   list is maintained by hand and drives provider-related UI (metadata
   search dialog, filters, lock/unlock dialog).

`AuthorParser` and `BookCoverProvider` currently have exactly one
implementation each and are injected by concrete type rather than through a
map; if a second implementation of either is added, introduce a map/registry
for it following the `parserMap` pattern above.

## Task files

`TASK.md` and `TASK-*.md` files at the repository root are local, ad hoc
working files used to hand pre-planned tasks to a coding agent. They are
not project documentation and are gitignored, so an ordinary `git add -A`
never sweeps them in by accident. On a working branch (e.g. a `-wip`
branch, see the Perrypedia work's `wip-then-clean-pr-branch` pattern)
they may deliberately be force-added and committed anyway, to keep
planning history alongside the code it describes. Regardless of whether
they were committed on a working branch, **`TASK.md`/`TASK-*.md` files
must never be included in a real pull request** — when cutting a clean
PR branch, exclude every such path (along with `.gitignore` itself if it
was touched only to add this exclusion, and `AGENTS.md`/`CLAUDE.md`
unless the PR is genuinely about repo documentation) from the diff.
