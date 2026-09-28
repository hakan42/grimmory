# Rebase the Perrypedia fork onto the next upstream release

## Context

The Perrypedia metadata provider ([[TASK-metadata-perrypedia.md]]) is
not being upstreamed — PR #2654 against `grimmory-tools/grimmory` was
rejected 2026-09-16 (maintainer policy: no new metadata providers
accepted until a plugin architecture exists, independent of this
change's own quality). Decided: keep it as a **local-only fork**,
maintained by periodically rebasing onto each new upstream tagged
release as it ships, rather than by upstreaming.

This file is the checklist for that maintenance pass. It's a
recurring/generic procedure, not a one-time task — reuse it (or copy
it) for each future release, rather than treating it as done after one
run. As of 2026-09-28, the branch is rebased onto and pushed at the
`v3.5.0` tag's base (`402e89b44`) — **not** `origin/develop` HEAD, which
by that point was already ~70 commits ahead of any tag; rebase onto the
latest tag, not raw `develop`, per step 1 below. `perrypedia-metadata-source`
(the clean reference branch) is recut from the same `v3.5.0` base, commit
`dacd355fe`. The deployed image tag is `v3.5.0-perrypedia-metadata`,
built and pushed to GHCR + the local registry, and the sibling
`grimmory` repo's deploy-tag bump (`dcf9a8c`) is pushed too. **Deploy
itself is now Jenkins-owned** — see [[jenkins-handles-deploy]]; step 8
below is historical, not something to run manually going forward.

## When to run this

Trigger: a new `grimmory-tools/grimmory` tagged release ships (check
`gh api repos/grimmory-tools/grimmory/releases/latest`, or
`git ls-remote --tags upstream` for a new `vX.Y.Z` not seen before).
Don't run this reactively on every `develop` commit — only when a real
tagged release lands, matching how the deploy tag itself
(`vX.Y.Z-perrypedia-metadata`) is meant to track actual releases, not
arbitrary `develop` HEAD state.

## Steps

All commands run from `grimmory-upstream` unless noted.

1. **Update remotes and find the target tag:**
   ```sh
   git fetch origin && git fetch upstream
   git describe --tags --abbrev=0 upstream/develop   # latest tag, e.g. vX.Y.Z
   git rev-list --count <tag>..origin/develop         # how far develop has drifted past it
   ```
   **Rebase onto the tag itself, not `origin/develop` HEAD**, whenever
   that count is nonzero — confirmed necessary again 2026-09-28
   (`origin/develop` was ~70 commits past `v3.5.0` at rebase time). The
   deploy tag we bump to should track a real release, not an arbitrary
   `develop` commit, and rebasing onto a `develop` HEAD ahead of any tag
   would pull in unreleased, possibly-still-changing upstream work.

2. **Rebase the working branch onto the tag found above:**
   ```sh
   git checkout perrypedia-metadata-source-wip
   git status --short   # stash/commit anything in the way first, per repo convention
   git rebase <tag>
   ```
   Expect conflicts in the "register a new provider" touchpoints if
   upstream added its own provider(s) or refactored provider plumbing in
   the interim — see [[TASK-metadata-perrypedia.md]]'s 2026-09-15,
   2026-09-16, and 2026-09-28 rebase log entries for the concrete
   conflict list and resolution pattern from each round
   (`BookParserConfig.java`, `MetadataProvider.java`,
   `metadata-searcher.component.ts`,
   `metadata-advanced-fetch-options.component.ts`,
   `MetadataRefreshService.java`, etc. have been the recurring hot
   spots — 2026-09-28's round added a required `BookParser.isEnabled()`
   method and moved provider external-link generation server-side, so
   check whether `PerrypediaParser` still compiles against the interface
   after any future round, not just whether the rebase itself is
   conflict-free). Also check for a **migration number collision**
   (`V149__Add_perrypedia_id_column.sql` vs. whatever upstream's own next
   migration number is) — renumber ours forward if upstream has claimed
   `V149`+ already, content unchanged. Confirm via
   `git diff --name-status <old-base>..<new-tag> -- backend/src/main/resources/db/migration/`
   whether any migrations changed at all — 2026-09-28's round had none.

3. **Verify it actually builds before pushing anything:**
   ```sh
   cd backend && ./gradlew check --no-daemon --parallel --build-cache
   cd .. && docker buildx build --target frontend-build .
   ```
   Full frontend `pnpm` check (typecheck/lint/stylelint/test) needs
   Docker since this host has no local `node`/`npm`/`npx` — see the
   2026-09-16 log entries for the working invocation (root
   `pnpm install --frozen-lockfile --ignore-scripts`, then an
   *explicit* `pnpm -C frontend install --frozen-lockfile --ignore-scripts`
   before running `pnpm -C frontend run typecheck`/`lint`/`lint:styles`/`test`
   — skipping that second install step makes pnpm try to silently
   reinstall without `--ignore-scripts` and fail on
   `ERR_PNPM_IGNORED_BUILDS`).

4. **Push the rebased working branch:**
   ```sh
   git push --force origin perrypedia-metadata-source-wip
   ```

5. **Optionally recut the clean single-commit branch** (`perrypedia-metadata-source`)
   — this is no longer feeding a live PR, so only do this if it's still
   useful as a squashed reference/backup. If skipped, skip straight to
   step 6 using the `-wip` branch's tree instead of a fresh worktree.
   If doing it, use a throwaway `git worktree` (never the main checkout
   — see [[wip-then-clean-pr-branch]]), cut from **the same tag `-wip`
   was rebased onto in step 2** (not `origin/develop` HEAD — confirmed
   2026-09-28 that diffing against a `develop` HEAD ahead of that tag
   spuriously includes a revert of every commit between them):
   ```sh
   git worktree add /tmp/perrypedia-clean-recut -B perrypedia-metadata-source <tag>
   git diff <tag>..perrypedia-metadata-source-wip -- . ':!*.md' ':!.gitignore' > /tmp/perrypedia.patch
   cd /tmp/perrypedia-clean-recut && git apply --check /tmp/perrypedia.patch && git apply /tmp/perrypedia.patch
   cd backend && ./gradlew compileJava compileTestJava --no-daemon && cd ..
   git add -A && git commit -s -m "feat(metadata): add Perrypedia metadata provider"
   git push --force-with-lease origin perrypedia-metadata-source
   ```
   The `':!.gitignore'` exclusion matters as of 2026-09-16's `TASK.md`
   gitignore fix — confirm at recut time whether any other non-`.md`
   repo-hygiene file has landed on `-wip` since and extend the exclusion
   list to match (see [[TASK-metadata-perrypedia.md]]'s "Branch & commit
   strategy" note). Clean up the worktree afterward (`git worktree
   remove`, `chown`-ing back first if any step ran inside Docker as root
   — see the 2026-09-16 log for that gotcha).

6. **Bump the deploy tag** in the sibling `grimmory` repo:
   - Edit `docker-compose.template`'s `server.image` line to
     `${DOCKER_REGISTRY}/digital-library/grimmory:vX.Y.Z-perrypedia-metadata`
     (new upstream version, same `-perrypedia-metadata` suffix).
   - Validate: run that repo's `validate-compose` skill/steps (renders
     with dummy env vars, `docker compose config` parses it).
   - Commit there (`git commit -s`, short imperative message like
     `Bump Grimmory image to vX.Y.Z-perrypedia-metadata`) — only commit
     if actually deploying this round, per that repo's `bump-version`
     skill convention.

7. **Build and push the image**, from `grimmory-upstream` at the
   rebased `-wip` tree (or the recut clean branch if step 5 was done):
   ```sh
   docker buildx build --platform linux/amd64 -t grimmory:local --load .
   gh auth token | docker login ghcr.io -u hakan42 --password-stdin
   for tag in perrypedia-metadata vX.Y.Z-perrypedia-metadata; do
     docker tag grimmory:local ghcr.io/hakan42/grimmory:$tag
     docker tag grimmory:local registry.raven-alioth.ts.net/digital-library/grimmory:$tag
     docker push ghcr.io/hakan42/grimmory:$tag
     docker push registry.raven-alioth.ts.net/digital-library/grimmory:$tag
   done
   ```
   (`registry.raven-alioth.ts.net` push needs no login — anonymous push
   works against that pull-through cache's own namespace.)

8. **Deploy — Jenkins-owned as of 2026-09-28, see [[jenkins-handles-deploy]].**
   Don't run this step manually; it's kept here as historical record of
   how it worked before Jenkins took over. (Previously: `grimmory-dev`,
   if tracking the floating tag, updated itself automatically once the
   new image was pushed; the always-on `grimmory-server-1` (prod,
   tracking the version-pinned tag) needed an explicit `run.sh up` in
   the `grimmory` deploy repo to actually roll out the new tag.)

9. **Log the round** in [[TASK-metadata-perrypedia.md]] with a dated
   entry (new upstream version, what conflicted and how it was
   resolved, test results, any new bugs found) — matches every prior
   round's pattern in that file. Update this file's "As of ..." line
   at the top too, so it doesn't go stale.

## Not in scope here

- Re-attempting the upstream PR. Don't reopen it, or open a new one,
  without the user explicitly saying the "no new providers" policy has
  changed — see [[perrypedia-upstream-discussion]].
- Reacting to every `develop` commit — only real tagged releases
  trigger this.
