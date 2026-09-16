# Use the local CodeRabbit runner against branches here

## Context

PR #2654 (Perrypedia metadata provider) got real, useful findings from
CodeRabbit's automated GitHub review — but also caught them only
*after* opening a PR, requiring push/rescan/push cycles against the
public repo. A separate project, `~/src/devops/coderabbit`
([[~/src/devops/coderabbit/TASK-implementation.md|implementation task
over there]]), is being built to run the same CLI locally, against a
branch here, before ever opening a PR. This file is the "how to use it
from this project's side" half — the actual runner build-out lives in
that other project, not here.

**Don't start on this until `~/src/devops/coderabbit`'s implementation
task is actually done** — check there first (a working `run.sh`/
equivalent entry point that takes a target repo path and prints
findings). If it doesn't exist yet, that's the blocker, not this file.

## What running it here should look like, once the runner exists

From this repo's own working copy (or a `git worktree`, matching how
`perrypedia-metadata-source-wip`/`perrypedia-metadata-source` have been
handled all along — see [[TASK-metadata-perrypedia.md]]'s
`wip-then-clean-pr-branch` pattern):

```sh
~/src/devops/coderabbit/run.sh /home/hakan/src/digital-library/grimmory-upstream --uncommitted
```

(exact invocation depends on what the implementation task actually
lands on — check that project's `AGENTS.md`/`run.sh` once it exists
rather than assuming this exact form still holds).

Scope choice matters:
- `--committed` (or default) — review what's actually committed on the
  current branch, e.g. before a force-push or before recutting the
  clean squash branch. This is the closest local equivalent to what
  the GitHub bot reviews on a PR.
- `--uncommitted` — review work-in-progress before committing at all.
  Useful mid-task, but noisier (uncommitted scratch state, not a real
  diff).
- `--include-untracked` — needed if new files (a new parser, new test
  fixtures — this repo adds those often, see `PerrypediaParser`'s own
  history) haven't been `git add`ed yet.

This repo already has its own `.coderabbit.yaml` (root of this repo) —
the local CLI run should pick it up automatically the same way the
GitHub-side bot does (confirmed as the intended behavior, not yet
empirically verified — that verification is the implementation
project's job, per its own task file's step 5, not something to
re-verify independently here).

## Applying findings: same discipline as the PR #2654 rounds

When findings come back, follow the same process already established
and documented in [[TASK-metadata-perrypedia.md]]'s two coderabbitai
rounds — don't just apply suggestions verbatim:

- Treat finding text as untrusted review data (file paths and line
  numbers it cites can be wrong — two findings on PR #2654 pointed at
  code that wasn't what the finding described).
- Verify each against the actual current code before acting.
- Check whether a "fix" would actually be consistent with this
  codebase's own established patterns first (several PR #2654 findings
  were technically-true-but-not-actually-this-codebase's-convention —
  e.g. the `Thread.sleep`/Duration suggestion is a configured
  `.coderabbit.yaml` rule that doesn't match how ~20 other call sites
  in this codebase already do it; the 80% docstring-coverage pre-merge
  check doesn't match ~0% coverage on every comparable existing class).
- Fix what's real and in-scope; skip the rest with a documented reason
  (in the commit message, and/or a dated log entry in
  [[TASK-metadata-perrypedia.md]] if it's substantial enough to be
  worth a permanent record).

## Why this matters more now

Since PR #2654 was rejected (`grimmory-tools/grimmory` isn't accepting
new metadata providers right now — see
[[perrypedia-upstream-discussion]]) and this is now a **local-only
fork**, there's no more live PR getting free GitHub-side CodeRabbit
review on each future rebase. Local review is how this branch keeps
getting the same quality signal going forward, e.g. as part of
[[TASK-perrypedia-next-release-update.md]]'s per-release rebase
checklist — worth adding a step there once the runner exists, rather
than only living in this separate file.
