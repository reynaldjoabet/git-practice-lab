# 13. Worktrees

A worktree is an extra checkout of the same repository: another directory with its own files,
its own HEAD and its own index, sharing one object database and one set of branches with every
other checkout. It is the right tool whenever you need two versions of the code on disk at the
same time.

All output below is from git 2.54, with the scratch directory shortened to `~/src`.

## 13.1 The problem worktrees solve

You are halfway through a feature, with uncommitted edits, a running dev server and a build
in progress. Production breaks and you must fix `release/1.4` now. Your options:

| Option                         | Cost                                                             |
|--------------------------------|------------------------------------------------------------------|
| Stash (or WIP-commit) and switch | Kills your running build and server, rebuilds everything twice, easy to forget the stash |
| Clone the repository again     | Downloads and stores all history twice; the two clones do not share branches, so moving a commit between them needs a push and a fetch |
| **`git worktree add`**         | A new directory in seconds, no download; commits made in either checkout are instantly visible to the other |

```sh
git worktree add -b hotfix/INC-2231 ../app-hotfix origin/release/1.4
cd ../app-hotfix          # fix, test, commit, push; your feature checkout is untouched
```

## 13.2 The mental model: one store, many checkouts

A repository has two kinds of state:

* **Shared, expensive, history-wide**: objects, branches, tags, remote-tracking refs, config,
  hooks. There is exactly one copy, in the **common directory** (the main checkout's `.git/`).
* **Per-checkout, cheap**: which commit is checked out (HEAD), the index, the files on disk,
  and HEAD's reflog. Each worktree has its own.

```
┌─────────────────────────────┐   ┌─────────────────────────────┐   ┌─────────────────────────────┐
│ ~/src/app                   │   │ ~/src/app-hotfix            │   │ ~/src/app-review            │
│ (main worktree)             │   │ (linked worktree)           │   │ (linked worktree)           │
│ checked-out tree            │   │ checked-out tree            │   │ checked-out tree            │
│ .git/  <- a directory       │   │ .git  <- a 1-line pointer   │   │ .git  <- a 1-line pointer   │
└──────────────┬──────────────┘   └──────────────┬──────────────┘   └──────────────┬──────────────┘
               │ own HEAD/index                  │ gitdir:                         │ gitdir:
               ▼                                 ▼                                 ▼
┌─────────────────────────────┐   ┌─────────────────────────────┐   ┌─────────────────────────────┐
│ .git/                       │   │ .git/worktrees/app-hotfix/  │   │ .git/worktrees/app-review/  │
│ HEAD                        │   │ HEAD   index   logs/HEAD    │   │ HEAD   index   logs/HEAD    │
│ index                       │   │ gitdir   commondir          │   │ gitdir   commondir          │
│ logs/HEAD                   │   │ config.worktree (optional)  │   │ config.worktree (optional)  │
└──────────────┬──────────────┘   └──────────────┬──────────────┘   └──────────────┬──────────────┘
               │ is the common dir               │ commondir                       │ commondir
               ▼                                 ▼                                 ▼
┌─────────────────────────────────────────────────────────────────────────────────────────────────┐
│ shared common dir:  ~/src/app/.git/                                                             │
│ objects/   refs/heads/   refs/tags/   refs/remotes/   packed-refs                               │
│ config   hooks/   refs/stash   logs/refs/* (branch reflogs)                                     │
└─────────────────────────────────────────────────────────────────────────────────────────────────┘
```

The **main worktree** is the original checkout, the one with a real `.git/` directory. Every
other checkout is a **linked worktree**. The difference matters in one way: deleting the main
worktree's directory deletes the repository itself; deleting a linked worktree deletes only that
checkout.

### Exactly what is shared

Verified in the lab, not assumed:

| State                                    | Scope            | Consequence                                       |
|------------------------------------------|------------------|---------------------------------------------------|
| Objects (`objects/`)                     | Shared           | No duplicate history on disk; a commit made anywhere exists everywhere |
| Branches, tags, remote-tracking refs     | Shared           | Commit in one worktree, and `git log feature` in another shows it immediately |
| Branch reflogs (`logs/refs/heads/*`)     | Shared           |                                                   |
| `git fetch` results                      | Shared           | Fetch once, in any worktree                       |
| Config (`.git/config`)                   | Shared           | `git config user.email x` in one worktree changes all of them (13.6) |
| Hooks                                    | Shared           | One hook directory for all checkouts              |
| **Stash** (`refs/stash`)                 | **Shared**       | `git stash list` shows stashes from *every* worktree. `stash pop` can pop another checkout's work |
| HEAD                                     | Per-worktree     | Each checkout is on its own branch or commit      |
| Index                                    | Per-worktree     | Staging in one checkout never affects another     |
| Files on disk, including untracked and ignored files | Per-worktree | `.env`, `node_modules`, build output are *not* there in a new worktree (13.10) |
| HEAD reflog, `ORIG_HEAD`, `MERGE_HEAD`... | Per-worktree    | `HEAD@{1}` means "where *this* checkout was"      |
| `FETCH_HEAD`                             | Per-worktree     | The fetched objects and refs are shared, but `FETCH_HEAD` exists only in the worktree that ran the fetch |
| Bisect state (`refs/bisect/*`, `BISECT_*`) | Per-worktree   | Bisect in one worktree while working in another   |
| Sparse-checkout patterns                 | Per-worktree     | Each checkout can contain different directories   |
| `refs/worktree/*`                        | Per-worktree     | Private refs a script can use without colliding   |

## 13.3 How git finds the repository from a linked worktree

A linked worktree's `.git` is a file, not a directory:

```sh
$ cat ~/src/app-hotfix/.git
gitdir: /Users/you/src/app/.git/worktrees/app-hotfix

$ ls ~/src/app/.git/worktrees/app-hotfix
HEAD  ORIG_HEAD  commondir  gitdir  index  logs  refs

$ cat ~/src/app/.git/worktrees/app-hotfix/commondir
../..
$ cat ~/src/app/.git/worktrees/app-hotfix/gitdir
/Users/you/src/app-hotfix/.git
```

When you run any git command inside `~/src/app-hotfix`:

1. Git walks up from the current directory, finds `.git`, sees it is a file, and follows
   `gitdir:` to the worktree's **private admin directory**, `.git/worktrees/app-hotfix/`.
2. It reads `commondir` there (`../..`, relative to the admin directory) to find the **common
   directory**, `~/src/app/.git/`.
3. Per-worktree state (HEAD, index, HEAD reflog, bisect, `refs/worktree`) is read from the
   admin directory; everything else from the common directory.

The `gitdir` file in the admin directory is the back-pointer. Git uses it to list worktrees and
to decide whether a worktree still exists (13.8).

Scripts should ask git instead of parsing these files:

```sh
$ cd ~/src/app-hotfix
$ git rev-parse --git-dir            # this worktree's admin directory
/Users/you/src/app/.git/worktrees/app-hotfix
$ git rev-parse --git-common-dir     # the shared repository
/Users/you/src/app/.git
$ git rev-parse --show-toplevel      # this worktree's root
/Users/you/src/app-hotfix
```

A script that writes to `$(git rev-parse --git-dir)/hooks` or `.../config` from a linked worktree
writes to the wrong place: use `--git-common-dir` for shared things, or
`git rev-parse --git-path hooks` which resolves either correctly.

## 13.4 The lifecycle

### Creating: `git worktree add`

| Command                                          | Result                                              |
|--------------------------------------------------|-----------------------------------------------------|
| `git worktree add ../app-feature feature`        | Check out an existing branch                        |
| `git worktree add -b fix ../app-fix origin/main` | Create branch `fix` from `origin/main` and check it out |
| `git worktree add -B fix ../app-fix origin/main` | Same, resetting `fix` if it already exists          |
| `git worktree add --detach ../app-review main`   | Check out the *commit*; no branch. Ideal for review, builds, bisect |
| `git worktree add ../app-spike`                  | Create a branch named after the directory (`app-spike`) from HEAD |
| `git worktree add ../app-rel rel`                | If `rel` exists only as `origin/rel`, create local `rel` tracking it |
| `git worktree add --orphan -b gh-pages ../site`  | A new branch with no history and an empty tree (publishing branches) |
| `git worktree add --no-checkout ...`             | Register the worktree but write no files (set up sparse checkout first) |
| `git worktree add --lock ...`                    | Create it already locked (13.8)                     |

```sh
$ git worktree add ../app-hotfix -b hotfix/INC-1
Preparing worktree (new branch 'hotfix/INC-1')
HEAD is now at b6ca5dc init

$ git worktree add ../app-rel rel
Preparing worktree (new branch 'rel')
branch 'rel' set up to track 'origin/rel'.
HEAD is now at 3ae6d2b svc
```

The target directory must not exist or must be empty
(`fatal: '../nonempty' already exists` otherwise). The admin directory is named after the
directory's basename, with a number appended if that name is taken.

### Inspecting: `git worktree list`

```sh
$ git worktree list
~/src/app         b6ca5dc [main]
~/src/app-feature b6ca5dc [feature]
~/src/app-hotfix  b6ca5dc [hotfix/INC-1]
~/src/app-review  b6ca5dc (detached HEAD)
```

`git worktree list -v` adds lock and prune reasons. Scripts use the stable porcelain format:

```sh
$ git worktree list --porcelain
worktree /Users/you/src/app
HEAD b6ca5dcd60f9cbe56f69e813592b98895b24052f
branch refs/heads/main

worktree /Users/you/src/app-review
HEAD b6ca5dcd60f9cbe56f69e813592b98895b24052f
detached
```

`git branch` marks branches checked out in *another* worktree with `+`:

```sh
$ git branch -v
  feature      8759155 feature work
+ hotfix/INC-1 b6ca5dc init
* main         b6ca5dc init
```

### Removing: `git worktree remove`

```sh
git worktree remove ../app-review
```

This deletes the directory *and* the admin directory. It refuses if the worktree has
modified or untracked files, so it never silently destroys work:

```
fatal: '../app-review' contains modified or untracked files, use --force to delete it
```

`--force` overrides that. Removing a worktree never deletes its branch; delete the branch
separately (`git branch -d hotfix/INC-1`) once it is merged.

### Cleaning up after `rm -rf`: `git worktree prune`

If a worktree directory is deleted by hand, the admin directory remains and git still considers
the branch checked out. `list` shows it as `prunable`:

```sh
$ rm -rf ../app-feature
$ git worktree list
~/src/app         b6ca5dc [main]
~/src/app-feature 8759155 [feature] prunable

$ git worktree prune -v
Removing worktrees/app-feature: gitdir file points to non-existent location
```

`git gc` also prunes worktree admin directories whose worktree has been missing for longer than
`gc.worktreePruneExpire` (default 3 months).

### Moving: `git worktree move`, and `repair` after a plain `mv`

`git worktree move ../app-hotfix ../app-hf2` moves the directory and updates both pointers.
If you move it with plain `mv`, the worktree still works *from inside* (its `.git` file still
points at the admin directory), but the main repository's back-pointer is stale, so the
repository believes the worktree is gone:

```sh
$ mv ../app-hf2 ../app-hf3
$ git worktree list
~/src/app-hf2    b6ca5dc [hotfix/INC-1] prunable     # danger: the next prune removes it

$ git worktree repair ../app-hf3
repair: gitdir incorrect: ~/src/app/.git/worktrees/app-hotfix/gitdir
$ git worktree list
~/src/app-hf3    b6ca5dc [hotfix/INC-1]
```

If you move the *main* worktree, run `git worktree repair` inside it (or pass the paths of every
linked worktree) to fix all the links in one go.

### Locking: protecting worktrees that are sometimes absent

A worktree on a USB drive or network share looks "missing" when unmounted, and would be pruned.
Lock it:

```sh
$ git worktree lock --reason 'on USB drive' ../app-hotfix
$ git worktree remove ../app-hotfix
fatal: cannot remove a locked working tree, lock reason: on USB drive
use 'remove -f -f' to override or unlock first
$ git worktree unlock ../app-hotfix
```

Locked worktrees are never pruned and cannot be moved or removed without `-f -f`.

## 13.5 The rule: one branch, one worktree

Git refuses to have the same branch checked out in two worktrees, and refuses to rewrite a
branch that another worktree has checked out:

```sh
$ git worktree add ../dup feature
fatal: 'feature' is already used by worktree at '~/src/app-feature'
$ git switch feature
fatal: 'feature' is already used by worktree at '~/src/app-feature'
$ git rebase main hotfix/INC-1
fatal: 'hotfix/INC-1' is already used by worktree at '~/src/app-hf3'
$ git branch -D hotfix/INC-1
error: cannot delete branch 'hotfix/INC-1' used by worktree at '~/src/app-hf3'
```

**Why.** A branch is one shared pointer; HEAD and the index are per worktree. If two worktrees
were both on `feature` and one committed, `feature` would move, and the other worktree's index
and files would silently describe an older commit. Its next `git status` would show the new
commit's changes reversed, and its next commit would undo them. The rule stops this at the door.

Ways to work with it:

* **A new branch:** `git worktree add -b feature-try ../try feature`.
* **Detached HEAD:** `git worktree add --detach ../review feature`. Any number of detached
  worktrees can sit on the same commit. Best for reading, building and testing.
* **Do it from the worktree that owns the branch:** rebase `hotfix/INC-1` inside
  `~/src/app-hf3`.
* `--force` (`add -f`, `switch --ignore-other-worktrees`) bypasses the rule and brings back
  exactly the hazard above. Avoid it.

## 13.6 Per-worktree configuration

`.git/config` is shared. To give one worktree its own settings, enable the extension once, then
use `--worktree`:

```sh
$ git config extensions.worktreeConfig true
$ cd ../app-review
$ git config --worktree user.email review@example.com
$ git config user.email                     # in app-review
review@example.com
$ cd ../app && git config user.email        # everywhere else
ada@example.com
$ cat .git/worktrees/app-review/config.worktree
[user]
	email = review@example.com
```

`git sparse-checkout` turns this extension on automatically, because sparse-checkout settings are
per worktree: one worktree can hold `services/billing` only while another has the full tree.
Older git versions (before 2.20) cannot open a repository that uses this extension.

## 13.7 Referring to another worktree's state

From any worktree you can name another worktree's per-worktree refs:

| Syntax                         | Means                                                  |
|--------------------------------|--------------------------------------------------------|
| `main-worktree/HEAD`           | HEAD of the main worktree                              |
| `worktrees/<name>/HEAD`        | HEAD of a linked worktree (`<name>` = its admin directory name) |
| `worktrees/<name>/refs/bisect/bad` | That worktree's bisect state                       |
| `refs/worktree/<anything>`     | A ref private to the *current* worktree                |

```sh
git log --oneline main-worktree/HEAD..HEAD          # commits here, not in the main checkout
git diff worktrees/app-feature/HEAD -- src/         # compare with another checkout's commit
git update-ref refs/worktree/before-experiment HEAD # a bookmark only this worktree sees
```

## 13.8 Garbage collection and safety

Git treats **every** worktree's HEAD, index and HEAD reflog as roots for reachability. In the
lab, a commit made on a branch in a linked worktree survived this:

```sh
# in app-spike2: commit, then detach so no branch points at it
git branch -D app-spike2
git reflog expire --expire=now --all
git gc --prune=now
git cat-file -t <that commit>        # still "commit": the worktree's HEAD protects it
```

So you never lose work in one worktree because of housekeeping run in another. The ways you
*can* lose work are the ordinary ones: `worktree remove --force` or `rm -rf` on a worktree with
uncommitted changes (git never stored those), and pruning a worktree that was only moved or
unmounted (use `move`, `repair` and `lock`).

## 13.9 Paths: absolute by default, relative on request

The `gitdir` pointers are absolute paths by default. That breaks when the whole tree is mounted
somewhere else: inside a container at `/workspace`, on another machine via a synced or network
directory, or after renaming a parent directory. Git 2.48 added relative links:

```sh
$ git worktree add --relative-paths ../app-rel -b rel
$ cat ../app-rel/.git
gitdir: ../app/.git/worktrees/app-rel
$ git config extensions.relativeWorktrees
true
```

Set `worktree.useRelativePaths=true` to make it the default, or run
`git worktree repair --relative-paths` to convert existing worktrees. The cost: it sets the
`extensions.relativeWorktrees` repository extension, and **git versions older than 2.48 refuse to
open the repository at all**. Check every tool that touches the repository (IDE-bundled git, CI
images, old servers) before enabling it.

## 13.10 What a new worktree does *not* have

A new worktree contains exactly the tracked files of its commit. Everything that is untracked or
ignored in your main checkout is missing:

* `.env`, local config overrides, credentials files
* dependencies: `node_modules/`, Python virtualenvs, `vendor/` if not committed
* build output and caches: `target/`, `dist/`, `.gradle/`, compiled assets
* submodule checkouts: run `git submodule update --init --recursive` in each worktree. (Git's
  own documentation still calls submodule support in multiple worktrees incomplete; test your
  workflow before relying on it.)
* IDE project files that are ignored (`.idea/`, `.vscode/settings.json`)

Automate the setup with a `post-checkout` hook. `git worktree add` runs it with an all-zero
"previous HEAD", which distinguishes a new worktree from an ordinary checkout:

```sh
#!/bin/sh
# .githooks/post-checkout
# $1 = previous HEAD, $2 = new HEAD, $3 = 1 for a branch checkout
if [ "$1" = "0000000000000000000000000000000000000000" ]; then
  main=$(git worktree list --porcelain | awk 'NR==1 {print $2}')
  [ -f "$main/.env" ] && cp "$main/.env" .env
  [ -f package-lock.json ] && npm ci --silent
fi
```

Running services from several worktrees at once also collides on **shared resources**: ports,
local databases, Docker container and volume names, lock files in your home directory. Derive
them from the worktree (for example `COMPOSE_PROJECT_NAME=$(basename "$PWD")`, and a port
chosen per worktree in its `.env`).

## 13.11 Directory layouts

**Siblings** (simplest): `~/src/app`, `~/src/app-hotfix`, `~/src/app-review`. Keep linked
worktrees *outside* the main worktree; a worktree nested inside it shows up as an untracked
directory in `git status` (unless ignored) and confuses file watchers and search tools.

**A folder per repository:**

```
~/src/app/main/          main worktree
~/src/app/release-1.4/   long-lived, for backports
~/src/app/review/        detached, reused for PR reviews
```

**Bare repository + worktrees.** No checkout is "special"; every branch is a peer directory:

```sh
mkdir app && cd app
git clone --bare git@github.com:acme/app.git .bare
echo 'gitdir: ./.bare' > .git                  # so git commands work from app/ itself
git config remote.origin.fetch '+refs/heads/*:refs/remotes/origin/*'   # bare clones omit this!
git fetch origin
git worktree add main
git worktree add -b feature/x feature-x origin/main
```

The fetch-refspec line is essential. A bare clone is configured as a mirror of branches, with
no `remote.origin.fetch`, so without that line `origin/*` remote-tracking refs are never created
or updated, and upstream tracking, `git status` ahead/behind and `pull` do not work.

## 13.12 In production

| Use                                  | Why a worktree is the right tool                                  |
|--------------------------------------|-------------------------------------------------------------------|
| **Incident hotfix** while mid-feature | Your feature checkout, its running server and its build stay untouched; the fix starts from the release branch in seconds |
| **Reviewing a pull request**         | Check out the PR detached in a reusable review worktree, run it, run its tests, without stashing anything |
| **Long test runs or builds**         | Run the full suite or a release build in one worktree while you keep editing in another |
| **Release maintenance**              | Keep a permanent worktree per supported release branch; backports (`cherry-pick -x`) happen in the right checkout, with its build cache warm |
| **Bisecting**                        | Bisect state is per worktree: hunt a regression in one checkout while you keep working |
| **Side-by-side comparison**          | Run old and new versions at once: compare outputs, API responses, benchmark numbers, generated files (`diff -r`) |
| **Publishing branches**              | An orphan `gh-pages` (or `dist`) worktree as the build output directory: build into it, commit, push |
| **Parallel coding agents**           | Give each agent or automated task its own worktree and branch, so they never edit the same files or fight over the index |
| **CI agents with a persistent cache** | One bare cache repository per build machine, one detached worktree per job: no full clone per job, isolated working trees |
| **Sparse checkouts per task**        | In a monorepo, one worktree with only `services/billing`, another with the full tree for a cross-cutting change |

### Recipes

```sh
# incident hotfix, then clean up
git fetch origin
git worktree add -b hotfix/INC-2231 ../app-hotfix origin/release/1.4
cd ../app-hotfix && make test && git commit -am 'billing: retry on 504' && git push -u origin HEAD
cd ../app && git worktree remove ../app-hotfix

# a reusable review worktree: re-point it at each PR
git worktree add --detach ../app-review
cd ../app-review
git fetch origin pull/482/head            # GitHub. Fetch *here*: FETCH_HEAD is per worktree
git switch --detach FETCH_HEAD

# docs site published from an orphan branch
git worktree add --orphan -b gh-pages ../site     # first time only
make docs OUT=../site
git -C ../site add -A && git -C ../site commit -m "docs: $(git rev-parse --short HEAD)"
git -C ../site push origin gh-pages

# run two versions side by side and compare their output
git worktree add --detach ../app-v1.4 v1.4.0
(cd ../app-v1.4 && make build && ./bin/report > /tmp/report-old.json)
make build && ./bin/report > /tmp/report-new.json
diff /tmp/report-old.json /tmp/report-new.json
```

### CI: persistent cache, worktree per job

```sh
# once per build machine
git clone --bare --filter=blob:none "$URL" /cache/app.git
git -C /cache/app.git config remote.origin.fetch '+refs/heads/*:refs/remotes/origin/*'

# per job (serialise fetches if jobs run concurrently on one machine)
flock /cache/app.lock git -C /cache/app.git fetch --quiet origin "$COMMIT"
git -C /cache/app.git worktree add --detach "$JOB_DIR" "$COMMIT"
make -C "$JOB_DIR" build test
git -C /cache/app.git worktree remove --force "$JOB_DIR"

# periodically
git -C /cache/app.git worktree prune
git -C /cache/app.git maintenance run --auto
```

Compared with `git clone --reference` (chapter 8), this gives each job a working tree in seconds
and shares one object store, but jobs on one machine share refs and config, so treat the cache
as owned by the CI system, never edited by hand. Fetching a bare commit ID requires the server
to allow it (GitHub and GitLab do for reachable commits).

## 13.13 Troubleshooting

| Symptom                                                | Cause and fix                                             |
|--------------------------------------------------------|-----------------------------------------------------------|
| `fatal: '<branch>' is already used by worktree at ...` | Another worktree has it. Work there, use `--detach`, or create a new branch (13.5) |
| `git worktree list` shows `prunable`                   | The directory was deleted or moved. Deleted: `git worktree prune`. Moved: `git worktree repair <new-path>` |
| A branch cannot be deleted, but no worktree seems to use it | A stale admin directory still claims it: `git worktree prune -v`, then delete |
| `fatal: not a git repository` inside a linked worktree | The main repository moved or was deleted. Moved: run `git worktree repair` in the main worktree. Deleted: the history is gone unless it was pushed |
| A worktree breaks inside a container or on another machine | Absolute `gitdir` paths. Mount at the same path, or use relative paths (13.9) |
| `git stash pop` applied something unexpected           | Stashes are shared across worktrees. Use `git stash list` and pick by message, or avoid stash and commit WIP on the branch |
| Changing config in one worktree affected the others    | Config is shared. Use `extensions.worktreeConfig` + `git config --worktree` (13.6) |
| A new worktree fails to build or run                   | Missing ignored files: `.env`, dependencies, generated code. Bootstrap with a `post-checkout` hook (13.10) |
| Two worktrees' dev servers or test databases interfere | Shared ports, databases or container names. Derive them per worktree (13.10) |
| `origin/*` refs missing in a bare + worktrees setup    | Bare clones have no fetch refspec. Add it and fetch (13.11) |
| Old tools fail with `unknown repository extension`     | `worktreeConfig` or `relativeWorktrees` is enabled. Upgrade the tool's git or remove the extension |

## 13.14 Command summary

```sh
git worktree add [-b|-B <new-branch>] [--detach] [--orphan] [--no-checkout] [--lock]
                 <path> [<commit-ish>]
git worktree list [-v | --porcelain [-z]]
git worktree remove [-f] <worktree>
git worktree prune [-n] [-v] [--expire <time>]
git worktree move <worktree> <new-path>
git worktree lock [--reason <text>] <worktree>
git worktree unlock <worktree>
git worktree repair [--relative-paths] [<path>...]

git rev-parse --git-dir --git-common-dir --show-toplevel
git rev-parse --git-path <name>             # correct path for hooks, config, etc.
```

## Exercises

1. Create a linked worktree and draw its three pointer files (`.git`, `gitdir`, `commondir`) from
   memory. Check yourself with `cat`.
2. Commit in a linked worktree and confirm with `git log` in the main worktree that the commit is
   there, while the main worktree's files are unchanged.
3. Stash in one worktree and run `git stash list` in another. Explain why both see it.
4. Delete a worktree with `rm -rf` and observe `prunable`. Then move another one with `mv` and
   fix it with `repair` before it is pruned.
5. Enable `extensions.worktreeConfig`, set a different `user.email` in one worktree, and make a
   commit there. Check the author with `git log -1 --format=%ae`.
6. Set up a bare + worktrees layout, forget the fetch refspec, and see what breaks. Then fix it.
7. Write a `post-checkout` hook that copies `.env` from the main worktree only when a new
   worktree is created, and test it with `git worktree add`.
