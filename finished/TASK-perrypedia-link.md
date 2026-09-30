On the "Book Details page" (I will upload a screenshot while working on this task), if an ID for amazon, google, goodreads is present, this leads to the appropiate page on the metadata source site.

e.g. goodreads is "258734310" leads to a new tab to https://www.goodreads.com/book/show/258734310

Implement the same for perrypedia, take the icon from the site icon of perrypedia.org

## Notes (2026-09-30)

- Implemented in `metadata-viewer.component.html` (the external-ratings row on
  Book Details): shown when `perrypediaId` is set, links to
  `https://www.perrypedia.de/wiki/Quelle:<perrypediaId>` (same URL the parser
  sets as `externalUrl`). Divider condition extended accordingly.
- `perrypedia.org` does not respond; icon taken from perrypedia.de's
  `<link rel="icon">`: `https://www.perrypedia.de/mediawiki/images/Perrypedia_favicon.ico`.
- Verified: Docker `frontend-build` stage (`pnpm run build:prod`) compiles.
