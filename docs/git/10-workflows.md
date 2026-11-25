# 10. Collaboration and workflow design

The previous chapters are about what git does. This one is about the decisions a senior or
principal engineer makes for a team: how work flows into the main branch, what history should
look like, and which guardrails keep it healthy. There is no single right answer; there are
trade-offs, and you should be able to defend the ones you pick.

## 10.1 Branching models

| Model                      | Shape                                                     | Fits                                   |
|----------------------------|-----------------------------------------------------------|----------------------------------------|
| **Trunk-based development** | Everyone integrates into `main` at least daily, through short-lived branches (hours to a couple of days). Unfinished features hide behind feature flags | Continuous delivery, services, most product teams. The default recommendation |
| **GitHub flow**            | Branch from `main`, open a pull request, merge, deploy `main` | Same as trunk-based, with slightly longer branches |
| **Release branches**       | `main` keeps moving; `release/1.4` is cut for stabilisation and patches | Software with versions customers run: mobile apps, libraries, on-prem products |
| **GitFlow**                | `develop` + `main` + feature, release and hotfix branches | Rarely justified today. Its complexity was designed for infrequent, scheduled releases |

The research consensus (the DORA / *Accelerate* studies) is that teams with **few, short-lived
branches and frequent integration** deliver faster and more reliably. Long-lived branches
defer integration pain and make it worse: the merge conflict you avoid today is a bigger one
next month.

What makes trunk-based development work:

* **Feature flags** separate *deploy* from *release*, so unfinished work can merge safely.
* **Fast, reliable CI** on every pull request and on `main`.
* **Small pull requests**: easier review, fewer conflicts, cheap reverts.
* **Branch by abstraction** for large refactors: introduce an interface, migrate callers in
  small steps, delete the old implementation. No months-long refactor branch.

### Release branches and backports

When you must support released versions:

```
main:         A---B---C---D---E---F---G
                       \
release/1.4:            R---x'---y'

R  = version bump, tagged v1.4.0
x' = fix cherry-picked from main (-x), y' = another; y' tagged v1.4.1
```

* Fix on `main` first, then cherry-pick to the release branch with `git cherry-pick -x`, so
  the fix is never lost in the next release.
* Tag releases with annotated or signed tags (`git tag -s v1.4.1 -m '...'`).
* Avoid merging release branches back into `main` wholesale; it drags release-only commits
  (version bumps) into `main`.

## 10.2 How pull requests land: merge, squash or rebase

Hosting services offer three ways to land a pull request. This choice shapes your history more
than anything else.

| Method                | Result on `main`                              | Good                                     | Bad                                      |
|-----------------------|-----------------------------------------------|------------------------------------------|------------------------------------------|
| **Merge commit**      | All branch commits + a merge commit           | True history; PR boundary visible via `--first-parent`; revert the PR with `revert -m 1` | Noisy without `--first-parent`; WIP commits end up in history |
| **Squash and merge**  | One new commit per PR                         | Linear; one commit = one reviewed change; easy revert and bisect | Loses the internal structure of large PRs; the branch looks unmerged (breaks stacked branches built on it) |
| **Rebase and merge**  | The branch's commits, replayed linearly       | Linear and keeps individual commits      | Each commit must be good on its own; replayed commits were never tested as such |

A defensible default for most product teams:

* **Squash and merge**, with the PR title and description becoming the commit message (so
  they must be written well), and
* **small PRs** so that one squashed commit is a sensible unit.

Choose **merge commits** when individual commits inside a PR carry real meaning (large
open-source projects, kernels, compilers, libraries) and contributors curate their commits
with interactive rebase. Whichever you choose, enforce one method per repository; a mix gives
you the downsides of all of them.

## 10.3 Keeping `main` green: merge queues

A pull request's CI tests the branch merged with `main` *as it was when CI ran*. If two PRs
pass separately and merge one after the other, `main` can still break: they were never tested
together (a semantic conflict, chapter 4).

A **merge queue** (GitHub merge queue, GitLab merge trains, Bors, Mergify) fixes this:

1. Approved PRs enter a queue.
2. The queue builds a temporary branch: `main` + PR1, then `main` + PR1 + PR2, and so on,
   and runs CI on each combination (often in parallel).
3. A PR merges only if its combination passes; a failure removes it and re-tests the PRs
   behind it.

Adopt a merge queue once a repository has enough concurrent merges that "main is red" becomes
a regular event. It costs CI capacity and latency; it buys a main branch you can always
deploy and bisect.

## 10.4 Branch protection and repository guardrails

Configure these on the main branch (and release branches) of any repository that matters:

* **No direct pushes, no force pushes, no deletion.** All changes go through pull requests.
* **Required status checks** (build, tests, linters) that must pass on the *up-to-date* branch,
  or a merge queue.
* **Required reviews**, with **CODEOWNERS** routing reviews to the owning team:

  ```
  # .github/CODEOWNERS (last matching pattern wins)
  *                     @acme/platform
  /services/billing/    @acme/payments
  *.tf                  @acme/infra
  ```

* **Dismiss stale approvals** when new commits are pushed, so approval applies to the code
  that merges.
* **Signed commits required** where provenance matters (10.6).
* **Push rules**: maximum file size, blocked file types, secret scanning with push
  protection.
* **Linear history required**, if you chose squash or rebase merging.

Treat these settings as code: manage them with your host's API, Terraform provider or rulesets,
review changes to them, and audit them.

## 10.5 Commits that age well

A commit is read many more times than it is written: in review, in `blame`, in `bisect`, in
incident investigations years later. Write it for that reader.

**Atomic commits.** One logical change per commit. It builds and passes tests on its own. A
refactor and a behaviour change are two commits, so that the reviewer can verify the refactor
changed no behaviour. Use `git add -p` and `rebase -i` (chapters 3 and 5) to get there.

**Messages:**

```
billing: retry card charges on gateway timeouts

The payment gateway returns 504 under load, and we treated that as a hard
decline, so customers saw "card declined" and abandoned checkout (INC-2231).

Retry up to 3 times with jittered exponential backoff, only for 504 and
connection resets. Declines (402) are still never retried, so we cannot
double-charge.

Refs: PAY-881
```

* **Subject**: about 50 characters, imperative ("add", "fix", not "added"), optionally an area
  prefix, no trailing period. It completes "If applied, this commit will ...".
* **Body**, wrapped at about 72 characters: *why* the change was needed and *what was
  considered*; the diff already shows *what* changed.
* **Trailers** at the end: `Refs:`, `Fixes:`, `Co-authored-by:`, `Signed-off-by:`
  (`git commit -s`, the Developer Certificate of Origin). `git interpret-trailers` and
  `git log --format='%(trailers:key=Refs)'` read them.

**Conventional Commits** (`feat(billing): ...`, `fix: ...`, `feat!:` for breaking changes) is
worth adopting when tooling consumes it: generated changelogs, semantic-release version bumps.
Enforce it in CI or a `commit-msg` hook, not by reviewers' memory.

## 10.6 Signing and provenance

Anyone can set `user.name` and `user.email` to anything. Signatures prove who created a commit
or tag.

```sh
# SSH signing (simplest: reuse an SSH key)
git config --global gpg.format ssh
git config --global user.signingKey ~/.ssh/id_ed25519.pub
git config --global commit.gpgSign true
git config --global tag.gpgSign true

# verifying locally needs an allowed-signers file:
#   ada@example.com namespaces="git" ssh-ed25519 AAAA...
git config --global gpg.ssh.allowedSignersFile ~/.config/git/allowed_signers
git log --show-signature -1
git verify-commit HEAD
git verify-tag v1.4.1
```

GPG (`gpg.format=openpgp`) and X.509 (`gpg.format=x509`, with `gitsign` for keyless Sigstore
signing) are the alternatives. Hosting services show "Verified" when the key is registered with
the account.

Know the limits: a signature on a commit says who created that commit object; it does not say
the code is reviewed or safe. Signed release tags, combined with protected branches and
build provenance (SLSA attestations), are what make a supply chain verifiable.

## 10.7 Repository layout: monorepo or many repositories

| Concern                 | Monorepo                                         | Many repositories (polyrepo)                |
|-------------------------|--------------------------------------------------|---------------------------------------------|
| Cross-cutting changes   | One atomic commit and PR                         | Coordinated PRs and version bumps across repos |
| Dependency versions     | One version of everything ("live at head")       | Each repo pins versions; drift accumulates  |
| Tooling cost            | Needs scale features (chapter 8), build system with target-level caching (Bazel, Nx, Pants, Turborepo), CI that builds only what changed | Standard tooling per repo |
| Ownership boundaries    | CODEOWNERS and directory conventions             | Repository permissions                      |
| Blast radius            | A bad merge can affect everyone                  | Isolated                                    |

Neither is free. Monorepos move complexity into build tooling; polyrepos move it into
dependency and release management. Submodules (chapter 11) are rarely a good middle ground.

## 10.8 Running a migration or history rewrite

Organisation-wide git changes (renaming the default branch, rewriting history to remove a
secret or large files, moving hosts, merging repositories) need a plan:

1. **Announce** with a date, the reason, and the exact commands people must run.
2. **Freeze** merges during the change; close or retarget open pull requests.
3. **Back up**: `git clone --mirror` of the original.
4. **Do it in a fresh mirror clone**, verify (`git fsck`, build, compare `git log --stat`
   samples, check tags), then push with `--mirror` or `--force` as planned.
5. **Help people move**: give them a script (fetch, rebase `--onto` their branches, or re-clone)
   and keep the old repository read-only for a while.

To merge another repository into a subdirectory while keeping its history:

```sh
git remote add billing ../billing && git fetch billing
git merge --allow-unrelated-histories -s ours --no-commit billing/main
git read-tree --prefix=services/billing/ -u billing/main
git commit -m 'Import billing repository into services/billing'
```

(or rewrite the other repository first with `git filter-repo --to-subdirectory-filter
services/billing` and merge it normally, which keeps `git log -- services/billing` working
without `--follow`).

## 10.9 A checklist for a healthy repository

* [ ] One documented branching model and one merge method.
* [ ] Protected `main`: PRs only, required checks, required owner review, no force pushes.
* [ ] CI tests the merged result (up-to-date requirement or merge queue).
* [ ] `.gitignore` covers build output, dependencies, editor and OS files; `.gitattributes`
      sets line endings, LFS and generated-file rules.
* [ ] `.git-blame-ignore-revs` lists formatting commits.
* [ ] Secret scanning with push protection; a written procedure for leaked credentials
      (rotate first).
* [ ] File-size limits or LFS for binaries, decided before the first asset lands.
* [ ] Release tags are annotated and signed; release notes generated from history.
* [ ] Hooks and commit conventions enforced by CI, not only by local hooks (anyone can skip
      local hooks with `--no-verify`).
* [ ] Branch hygiene: merged branches deleted automatically; stale branches reported.

## 10.10 In production: reference setups

The right combination depends on how you ship. Four common situations:

| Situation                     | Branching                          | Merge method              | Guardrails that matter most                     |
|-------------------------------|------------------------------------|---------------------------|-------------------------------------------------|
| **SaaS product team**, deploys many times a day | Trunk-based; branches live hours to two days; feature flags | Squash and merge | Required CI, merge queue once traffic is high, CODEOWNERS, revert-first incident policy |
| **Mobile app or on-prem product** with versions in the field | `main` + `release/x.y` cut per release | Squash on `main`; cherry-pick `-x` to releases | Release branches protected; backport checklist; signed release tags; `range-diff` to verify backports |
| **Library or open-source project** | `main` + maintenance branches for supported majors | Merge commits; contributors curate commits | DCO `Signed-off-by` or CLA check; signed tags; Conventional Commits for changelogs; `.git-blame-ignore-revs` |
| **Regulated environment** (finance, health) | Trunk-based or release branches | Either, enforced consistently | Signed commits required; two-person review; no admin bypass; audit export from `git log`; branch-protection settings managed as code |

Scenarios you will meet, and the chapter that has the tool:

* **"Main is red and three teams are blocked."** Revert the offending PR (`revert -m 1` or
  revert the squash commit) before debugging (4.10). Then consider a merge queue (10.3).
* **"We need the fix in 1.3 and 1.4 too."** Fix on `main`, `cherry-pick -x` to each release
  branch, verify with `range-diff`, tag patch releases (4.11, 9.5).
* **"A secret was pushed."** Rotate the secret, then decide whether a history rewrite is worth
  the disruption. For a public repository it usually is; for a private one, rotation alone is
  often enough (5.7, 10.8).
* **"The monorepo is slow."** Measure with trace2, then fsmonitor, maintenance, blobless and
  sparse clones, and CI that builds only what changed (chapter 8).
* **"Who approved this change to production, and was it signed?"** `log --first-parent` with
  `%G?` and review trailers, plus the host's PR records (9.8).
* **"We are splitting a service out" or "merging two repositories."** filter-repo with a frozen,
  announced migration (5.7, 10.8).

## Exercises

1. Write down your team's current branching model and merge method, and one concrete problem
   each one causes. Propose a change and its cost.
2. Take a messy pull request with WIP commits and reshape it into three atomic commits with
   good messages.
3. Set up SSH commit signing and verify a signed commit with an allowed-signers file.
4. Simulate a semantic conflict: two branches that pass tests separately and break when
   combined. Explain how a merge queue would have caught it.
5. Import a small repository into a subdirectory of another, preserving its history.
