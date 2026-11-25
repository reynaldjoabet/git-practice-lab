# 7. Remotes and the transfer protocol

A remote is just another repository. Fetching and pushing copy objects between object stores
and update refs. Nothing more.

## 7.1 What a remote is

```ini
[remote "origin"]
    url = git@github.com:acme/app.git
    fetch = +refs/heads/*:refs/remotes/origin/*
```

That is the entire definition: a URL and a **refspec** (7.2). `git remote add`,
`git remote set-url`, `git remote -v` and `git remote rename` edit this section.

```
┌─────────────────┐    ┌─────────────────┐    ┌─────────────────┐    ┌─────────────────┐
│ remote repo     │    │ remote-tracking │    │ local branch    │    │ working tree    │
│ refs/heads/main │    │ origin/main     │    │ main            │    │ + index         │
└────────┬────────┘    └────────┬────────┘    └────────┬────────┘    └────────┬────────┘
         │                      │                      │                      │
         │─────── fetch ───────►│                      │                      │
         │                      │──── merge/rebase ───►│                      │
         │                      │                      │─────── switch ──────►│
         │                      │                      │                      │
         │─────────────────── pull = fetch + merge/rebase ───────────────────►│
         │                      │                      │                      │
         │                      │                      │◄─── add + commit ────│
         │◄────────────────── push ────────────────────│                      │
         │                      │◄─ push also moves ───│                      │
         │                      │                      │                      │
```

**Remote-tracking refs** (`refs/remotes/origin/main`, shown as `origin/main`) are your local,
read-only record of where the remote's branches were *the last time you talked to it*. They
change only on `fetch` and `push`. `git status` saying "Your branch is up to date with
'origin/main'" means up to date with that record, which may be hours old. Run `git fetch` first
when it matters.

## 7.2 Refspecs

A refspec maps refs on one side to refs on the other:

```
[+]<src>:<dst>
```

* For **fetch**, `src` is on the remote and `dst` is local.
* For **push**, `src` is local and `dst` is on the remote.
* The leading `+` allows non-fast-forward updates (force).
* `*` is a wildcard that must appear on both sides.
* A refspec starting with `^` is a **negative refspec** that excludes refs.

| Refspec                                        | Meaning                                       |
|------------------------------------------------|-----------------------------------------------|
| `+refs/heads/*:refs/remotes/origin/*`          | Default fetch: mirror every branch into `origin/*` |
| `refs/heads/main:refs/remotes/origin/main`     | Fetch only `main`                             |
| `+refs/pull/*/head:refs/remotes/origin/pr/*`   | Also fetch GitHub pull-request heads          |
| `^refs/heads/dependabot/*`                     | Exclude matching refs from the fetch          |
| `main:main` (push)                             | Push local `main` to remote `main`            |
| `HEAD:refs/heads/release` (push)               | Push the current commit to a new branch       |
| `:old-branch` (push)                           | Empty source: **delete** `old-branch` on the remote |

Examples:

```sh
git fetch origin 'refs/pull/123/head:pr-123'    # fetch one pull request as a local branch
git push origin HEAD:refs/heads/experiment      # push without creating a local branch
git push origin --delete old-branch             # same as `git push origin :old-branch`
git config --add remote.origin.fetch '^refs/heads/renovate/*'
```

## 7.3 Fetch, step by step

`git fetch origin` with protocol version 2 (the default since Git 2.26):

1. **Connect** over SSH (`ssh git@host git-upload-pack 'acme/app.git'`) or HTTPS
   (`GET .../info/refs?service=git-upload-pack`, then `POST .../git-upload-pack`).
2. **Capability advertisement.** The server lists what it supports (`ls-refs`, `fetch`,
   `object-format`, filters, etc.).
3. **`ls-refs`.** The client asks only for refs matching its refspecs (v2 lets the client
   filter by prefix, so a repository with a million refs does not send all of them).
4. **Negotiation.** The client sends `want <id>` for each tip it lacks and `have <id>` for
   commits it already has, walking back from its own tips. The server replies `ACK` for common
   commits. This finds the boundary between "the client has it" and "the client needs it"
   without either side sending its whole history.
5. **Packfile.** The server builds a pack containing exactly the missing objects, using deltas
   against objects the client is known to have ("thin pack"), and streams it with progress on
   a sideband channel.
6. **Index and fix.** The client runs `index-pack`, completes thin-pack deltas, verifies every
   hash (and with `transfer.fsckObjects`, every object's structure), then checks that the new
   history connects to existing objects.
7. **Update refs** according to the refspec, and write `FETCH_HEAD`.

Fetch never touches your branches, index or working tree, so it is always safe to run.

Useful options:

```sh
git fetch --prune                      # delete origin/* refs whose remote branch is gone
git config --global fetch.prune true   # always
git fetch --all                        # every remote
git config --global fetch.parallel 0   # fetch remotes/submodules in parallel
git fetch --tags                       # all tags (default: only tags pointing into fetched history)
```

## 7.4 Pull

`git pull` is `git fetch` followed by integrating the upstream into the current branch.
Decide *how* it integrates, explicitly, instead of getting surprise merge commits:

```sh
git config --global pull.ff only        # only fast-forward; fail if you have diverged
# or
git config --global pull.rebase true    # rebase local commits onto the upstream
```

With `pull.ff=only`, divergence becomes a deliberate decision: run `git rebase` or
`git merge` yourself. Many experienced engineers simply never run `pull` and use
`git fetch` + `git rebase @{u}` (or `merge`).

## 7.5 Push, step by step

`git push origin feature`:

1. Connects to `git-receive-pack` on the server, which advertises the server's current refs.
2. The client computes which objects the server lacks (everything reachable from the pushed
   tips that is not reachable from the server's refs) and sends them as one packfile, with a
   list of commands `<old-id> <new-id> <ref>`.
3. The server runs `index-pack`, then the **`pre-receive`** hook (sees all ref updates, can
   reject the whole push), then for each ref the **`update`** hook (can reject one ref), then
   updates refs, then **`post-receive`** (notifications, CI triggers, deploys).
4. The client updates its remote-tracking refs (`origin/feature`) to match.

Hosting services implement branch protection, required reviews and status checks as server
logic at step 3. That is why a push can be rejected even though your local git allowed it.

**Why a push is rejected as non-fast-forward.** By default the server accepts a ref update only
if the new commit has the old one as an ancestor. If someone pushed since your last fetch, your
tip does not contain their commit, and the push is refused rather than discarding their work.
Fetch, integrate, push again.

Options worth knowing:

| Option                         | Effect                                                  |
|--------------------------------|---------------------------------------------------------|
| `-u` / `--set-upstream`        | Also set the branch's upstream                          |
| `--atomic`                     | Update all refs or none (server must support it)        |
| `--follow-tags`                | Also push annotated tags that point into pushed history (`push.followTags`) |
| `-o <option>` / `--push-option` | Pass a string to server hooks (e.g. `-o ci.skip` on GitLab) |
| `--dry-run`                    | Show what would be updated                              |
| `--signed`                     | Sign the push certificate (server must support it)      |

`push.default` decides what a bare `git push` does. The default `simple` pushes the current
branch to its upstream of the same name. `git config --global push.autoSetupRemote true` makes
the first `git push` of a new branch set the upstream automatically.

## 7.6 Force-pushing safely

`git push --force` overwrites the remote branch with your version, whatever it currently is.
If a colleague pushed in the meantime, their commits are silently discarded.

```sh
git push --force-with-lease --force-if-includes origin feature
```

* **`--force-with-lease`** sends a compare-and-swap: "update `feature` only if it is still
  at the commit my `origin/feature` says it is". If someone pushed since your last fetch, the
  push fails.
* The trap: a background `git fetch` (from your IDE, for example) updates `origin/feature`
  *without* you integrating it, and the lease then passes and discards the new commits anyway.
  **`--force-if-includes`** closes that hole: it also checks, using the reflog, that the
  remote tip was at some point integrated into your local branch.
* `--force-with-lease=feature:<expected-id>` states the expected value explicitly, which is
  the safest form in scripts.

Make it the only kind of force you use:

```sh
git config --global alias.pushf 'push --force-with-lease --force-if-includes'
```

## 7.7 Transports and authentication

| Transport | URL                              | Auth                       | Notes                              |
|-----------|----------------------------------|----------------------------|------------------------------------|
| SSH       | `git@github.com:acme/app.git`    | SSH keys, agent, FIDO keys | `~/.ssh/config` for per-host keys  |
| HTTPS     | `https://github.com/acme/app.git` | Token via credential helper | Works through corporate proxies   |
| File      | `/srv/git/app.git`, `file://...` | File permissions           | `file://` forces the network code path |
| Git       | `git://...`                      | None, read-only            | Unauthenticated, unencrypted; avoid |

* **Credential helpers** store HTTPS tokens: `osxkeychain` on macOS, Git Credential
  Manager on Windows and Linux. Never put tokens in remote URLs: they end up in
  `.git/config`, shell history and CI logs.
* `url.<base>.insteadOf` rewrites URLs, for example to force SSH for one host:

  ```sh
  git config --global url.'git@github.com:'.insteadOf 'https://github.com/'
  ```

* `GIT_TRACE_PACKET=1`, `GIT_TRACE_CURL=1` and `GIT_SSH_COMMAND='ssh -v'` show the protocol
  exchange when something fails.

## 7.8 Cloning, and making clones smaller

`git clone` is: `init`, add `origin`, `fetch`, create a local branch for the remote's default
branch, check it out. For large repositories, choose what to download:

| Kind                      | Command                                 | You get                              | Trade-off                    |
|---------------------------|-----------------------------------------|--------------------------------------|------------------------------|
| Full                      | `git clone URL`                         | All history, all blobs               | Largest, fully offline       |
| Shallow                   | `git clone --depth 1 URL`               | Recent commits only                  | `log`, `blame`, `merge-base` break at the boundary; good for one-off CI builds |
| Blobless (partial)        | `git clone --filter=blob:none URL`      | All commits and trees; blobs fetched on demand | Fast; `blame`/old checkouts fetch lazily. Best default for developers on big repos |
| Treeless (partial)        | `git clone --filter=tree:0 URL`         | All commits; trees and blobs on demand | Smallest; history operations can be slow. Good for CI that builds then discards |
| Sparse checkout           | `git clone --sparse URL` + `git sparse-checkout set dir1 dir2` | Only some directories on disk | Combine with blobless for monorepos |
| Single branch             | `git clone --single-branch -b main URL` | One branch                           | Narrow fetch refspec         |

Shallow clones can be extended: `git fetch --deepen=100`, `git fetch --unshallow`. Partial
clones remember their filter (`remote.origin.partialclonefilter`) and fetch missing objects
from the "promisor" remote when needed. Chapter 8 covers the monorepo setup.

## 7.9 Mirrors, bare repositories and forks

* A **bare repository** (`git init --bare`, `git clone --bare`) has no working tree: it is the
  contents of `.git` in a directory, usually named `app.git`. Servers store bare repositories.
  Push only to bare repositories (pushing to a checked-out branch of a non-bare repository is
  refused by default).
* `git clone --mirror` copies **all** refs (`+refs/*:refs/*`), including pull-request refs
  and notes, and `git push --mirror` replicates them, deleting remote refs that do not exist
  locally. Use it for migrating between hosts and for backups.
* A **fork workflow** has two remotes: `origin` (your fork, where you push) and `upstream`
  (the project). `git fetch upstream && git rebase upstream/main` keeps your branch current;
  `remote.pushDefault=origin` makes pushes go to your fork.

## 7.10 In production

| Feature                          | When you'd reach for it                                            |
|----------------------------------|--------------------------------------------------------------------|
| `--depth 1` (shallow)            | CI and Docker builds that only compile the current commit           |
| Full history (`fetch-depth: 0`)  | Jobs that compute versions or changelogs from tags (`git describe`, semantic-release, GitVersion), or analyse blame (SonarQube). A shallow clone gives wrong or missing results here |
| `--filter=blob:none`             | Developer clones of big repositories; CI that needs history but only some files |
| `--filter=tree:0`                | Throwaway CI checkouts in huge repositories that still need `git log` |
| Negative refspecs                | Big repositories where bots create thousands of branches (Renovate, Dependabot): stop fetching them |
| `git ls-remote`                  | Check a remote without cloning: "does tag v1.4.1 exist?", "what is main's commit?" for a cache key or deploy check |
| `--force-with-lease --force-if-includes` | Every force-push, by people and by bots that rebase PR branches |
| `--atomic`                       | Release scripts that push a branch and its tag together: both or neither |
| Push options (`-o`)              | GitLab: `-o ci.skip`, `-o merge_request.create`; custom server hooks reading options |
| `--mirror`                       | Host migration (Bitbucket → GitHub, GitLab → self-hosted); disaster-recovery replicas |
| `url.<x>.insteadOf`              | CI rewriting `https://github.com/` to a token-authenticated URL; private Go modules and other package managers that clone over HTTPS |
| Deploy keys + `GIT_SSH_COMMAND`  | A server or CI job that needs read access to exactly one repository |
| Server hooks (`pre-receive`)     | Self-hosted servers enforcing policy: protected branches, size limits, commit-message rules |
| Bare repository + `post-receive` | Small "push to deploy" setups; dotfiles managed with `--git-dir`/`--work-tree` |

```sh
# CI: fast checkout, then deepen only if the job needs tags for versioning
git clone --depth 1 --branch "$BRANCH" "$URL" src && cd src
git fetch --unshallow --tags     # only in jobs that run `git describe` / changelog tools

# don't fetch bot branches in a busy monorepo
git config --add remote.origin.fetch '^refs/heads/renovate/*'

# release: branch and tag land together or not at all
git push --atomic origin main v1.4.1

# check a remote without cloning
git ls-remote --exit-code --tags origin v1.4.1 >/dev/null && echo 'already released'

# CI auth without storing credentials in the repository config
git config --global url."https://x-access-token:${TOKEN}@github.com/".insteadOf https://github.com/

# a job that may only read one repository
GIT_SSH_COMMAND='ssh -i /run/secrets/deploy_key -o IdentitiesOnly=yes' \
  git clone git@github.com:acme/app.git
```

The `insteadOf` example writes the token into the CI machine's global config. Do it only on an
ephemeral runner, and make sure the job never prints its configuration.

## Exercises

1. Run `GIT_TRACE_PACKET=1 git fetch` on a small repository and identify `ls-refs`, the
   `want`/`have` lines and the packfile.
2. Add a fetch refspec for pull-request refs and fetch one PR.
3. Simulate a colleague's push from a second clone, then try `--force-with-lease` with and
   without a background `git fetch` in between. Then add `--force-if-includes`.
4. Clone one repository three ways (full, `--filter=blob:none`, `--depth 1`) and compare
   `du -sh .git`, `git log | wc -l` and the time `git blame` takes on an old file.
5. Write a `pre-receive` hook in a local bare repository that rejects pushes to `main`, and
   push to it.
