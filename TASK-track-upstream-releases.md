# Spec: track upstream releases for the Perrypedia patch

If [[TASK-metadata-perrypedia.md]] isn't accepted upstream, this covers
how to keep running it as a personal patch without hand-rebuilding on
every upstream minor release.

## Why a drop-in "minimal jar" doesn't work here

Investigated and ruled out: this codebase has no plugin/SPI surface (see
[[AGENTS.md]]'s Metadata providers section), so a jar with just the
changed classes can't be dropped onto the classpath and picked up.
Concretely:

- **`MetadataProvider` is a Java `enum`**
  (`backend/src/main/java/org/booklore/model/enums/MetadataProvider.java`).
  Enums are closed — there's no way to add a `Perrypedia` constant from
  outside the jar that declares it, and `parserMap` is typed
  `Map<MetadataProvider, BookParser>`, so no valid map entry can be built
  for a provider whose enum constant doesn't exist in the compiled jar.
- **`BookParserConfig.parserMap(...)` is not a component scan** — it's one
  `@Bean` method with the known parsers as literal constructor parameters.
  Spring won't register a new `@Service`-annotated `PerrypediaParser`
  dropped on the classpath unless that method itself is edited to accept
  and map it.
- **The frontend half can't be overlaid at all.**
  `BOOK_METADATA_PROVIDERS` (`frontend/src/app/features/book/data/book-response.models.ts`)
  is compiled into the static Angular production bundle; there's no
  runtime plugin surface on that side either.

The only genuinely jar-friendly piece is the Flyway migration
(`db/migration` is classpath-scanned across jars by default), which is
the smallest part of the change. Getting the provider selectable in the
fetch/refresh UI, respecting the enable toggle, and showing up in the
lock/viewer UI all require the enum and the map bean to exist in the
compiled backend jar and the array to exist in the compiled frontend
bundle. That means either upstream merges it, or the patch is maintained
against each release.

**Decision**: maintain the Perrypedia change as a small patch/branch
rebased onto each upstream release, and automate the rebase + rebuild so
it doesn't require manual work each minor release.

## Repo topology (confirmed from this checkout)

- `origin` → `git@github.com:hakan42/grimmory.git` — personal fork; the
  patch branch and the automation workflow both live here.
- `upstream` → `git@github.com:grimmory-tools/grimmory.git` — read-only
  source of new releases; never pushed to.
- Upstream release mechanics (`docs/MAKING-A-RELEASE.md`,
  `.github/workflows/publish-release.yml`): `semantic-release` cuts `v*`
  tags off `main` on every release-worthy push; `publish-release.yml`
  triggers `on: release: types: [released]`, builds multi-arch straight
  from the repo's own `Dockerfile` (which does its own pnpm+Gradle build
  in-container — no host toolchain needed), and pushes to GHCR (and
  optionally Docker Hub if configured). This is the release cadence and
  build mechanism the automation should shadow.
- Patch branch: `perrypedia` on `origin`, based on some upstream `v*` tag,
  holding just the Perrypedia commits from [[TASK-metadata-perrypedia.md]].

## Trigger strategy

GitHub Actions can't natively subscribe to another repo's `release` event
without a webhook relay, so poll instead: `schedule` cron +
`workflow_dispatch` for manual runs. A `merge-base --is-ancestor` check
against the latest upstream tag makes repeat runs a no-op until there's
actually something new, so the polling cost is negligible.

On conflict: abort the rebase, file/refresh a tracking GitHub issue, fail
the job loudly. Never publish an image built from a broken or
merely-textually-clean-but-semantically-wrong rebase — hence running
`just check && just test` after the rebase and before build/push.

## Workflow sketch

Lives at `.github/workflows/perrypedia-rebuild.yml` on `hakan42/grimmory`
(the fork) — **not** on upstream. Untested draft; adjust once the
`perrypedia` branch and its commits actually exist.

```yaml
name: Rebase Perrypedia patch & publish image

on:
  schedule:
    - cron: '0 6 * * *'   # daily; upstream releases aren't frequent enough to need tighter
  workflow_dispatch: {}

permissions:
  contents: write    # push the rebased branch back to origin
  packages: write    # push the image to ghcr.io/hakan42/*

env:
  PATCH_BRANCH: perrypedia
  IMAGE: ghcr.io/hakan42/grimmory

jobs:
  rebase-and-build:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v7
        with:
          ref: ${{ env.PATCH_BRANCH }}
          fetch-depth: 0        # full history — rebase needs it
          token: ${{ secrets.GITHUB_TOKEN }}

      - name: Configure git identity for CI commits
        run: |
          git config user.name "grimmory-perrypedia-bot"
          git config user.email "actions@users.noreply.github.com"

      - name: Fetch upstream tags
        run: |
          git remote add upstream https://github.com/grimmory-tools/grimmory.git
          git fetch upstream --tags --quiet

      - name: Determine latest upstream stable tag
        id: latest
        run: |
          tag=$(git tag -l 'v*' --sort=-v:refname --merged upstream/main | head -1)
          echo "tag=$tag" >> "$GITHUB_OUTPUT"

      - name: Skip if already based on latest tag
        id: gate
        run: |
          if git merge-base --is-ancestor "${{ steps.latest.outputs.tag }}" HEAD; then
            echo "up_to_date=true" >> "$GITHUB_OUTPUT"
          else
            echo "up_to_date=false" >> "$GITHUB_OUTPUT"
          fi

      - name: Rebase patch onto latest upstream tag
        if: steps.gate.outputs.up_to_date == 'false'
        id: rebase
        run: |
          if ! git rebase "${{ steps.latest.outputs.tag }}"; then
            git rebase --abort
            echo "conflict=true" >> "$GITHUB_OUTPUT"
            exit 1
          fi

      - name: File/refresh a tracking issue on rebase conflict
        if: failure() && steps.rebase.outputs.conflict == 'true'
        run: |
          gh issue create \
            --repo hakan42/grimmory \
            --title "Perrypedia patch conflicts with ${{ steps.latest.outputs.tag }}" \
            --body "Automatic rebase of \`${{ env.PATCH_BRANCH }}\` onto \`${{ steps.latest.outputs.tag }}\` failed. Resolve manually." \
            --label "needs-manual-rebase" || true
        env:
          GH_TOKEN: ${{ secrets.GITHUB_TOKEN }}

      - name: Verify the rebased patch still builds and passes checks
        if: steps.gate.outputs.up_to_date == 'false'
        run: just check && just test

      - name: Push rebased branch
        if: steps.gate.outputs.up_to_date == 'false'
        run: git push origin "${{ env.PATCH_BRANCH }}" --force-with-lease

      - uses: docker/setup-qemu-action@v4
        if: steps.gate.outputs.up_to_date == 'false'
      - uses: docker/setup-buildx-action@v4
        if: steps.gate.outputs.up_to_date == 'false'
      - uses: docker/login-action@v4
        if: steps.gate.outputs.up_to_date == 'false'
        with:
          registry: ghcr.io
          username: ${{ github.actor }}
          password: ${{ secrets.GITHUB_TOKEN }}

      - name: Build and push image
        if: steps.gate.outputs.up_to_date == 'false'
        run: |
          docker buildx build \
            --platform linux/amd64,linux/arm64 \
            -t "${{ env.IMAGE }}:${{ steps.latest.outputs.tag }}-perrypedia" \
            -t "${{ env.IMAGE }}:latest-perrypedia" \
            --push .
```

## Notes / open decisions

- **Auth is free**: `GITHUB_TOKEN` already has `packages: write` scope for
  the fork's own GHCR namespace and can push to `origin` — no extra
  secrets needed, unlike upstream's own workflow (which also handles
  optional Docker Hub creds that aren't needed here).
- **Rebase vs. merge**: rebase keeps the patch as a small, readable diff
  on top of each release, matching the "minimal footprint" goal — but it
  force-pushes `perrypedia` every run, so that branch should be treated as
  bot-owned, not hand-committed to in parallel.
- **`just check && just test` before push/build is deliberate**: a rebase
  can apply with zero textual conflicts and still be semantically wrong
  (e.g. upstream reshapes `BookParserConfig`'s constructor in a way that
  still compiles differently) — catch that before it ships as an image.
- **Deployment side** (the separate `grimmory` install/deployment repo)
  still needs to bump its pinned image tag to `:latest-perrypedia` or the
  versioned tag — that's a separate, smaller step (manual edit, or
  Renovate/Watchtower watching `ghcr.io/hakan42/grimmory`), not covered by
  this workflow.
- **Not yet drafted**: a local Justfile recipe to dry-run the rebase
  manually (checkout `perrypedia`, fetch upstream, rebase, `just check`)
  before trusting the unattended schedule — worth adding once the
  `perrypedia` branch exists.
