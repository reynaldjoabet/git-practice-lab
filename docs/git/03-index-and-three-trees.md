# 3. The index and the three trees

Most git confusion comes from not knowing which of three places a command reads from and
writes to. Learn these three and the porcelain becomes predictable.

| "Tree"           | Where                 | What it is                                         |
|------------------|-----------------------|----------------------------------------------------|
| **HEAD**         | a commit's tree       | The last commit: what you are building on          |
| **Index**        | `.git/index`          | The proposed next commit: a flat list of paths → blobs |
| **Working tree** | your checkout         | Ordinary files you edit                            |

`git status` is two diffs: *HEAD vs index* ("Changes to be committed") and *index vs working
tree* ("Changes not staged for commit"), plus untracked files.

```
┌──────────────────┐        ┌──────────────────┐        ┌──────────────────┐
│ working tree     │        │ index            │        │ HEAD commit      │
│ (files on disk)  │        │ (.git/index)     │        │ (repository)     │
└─────────┬────────┘        └─────────┬────────┘        └─────────┬────────┘
          │                           │                           │
          │───────── git add ────────►│                           │
          │                           │─────── git commit ───────►│
          │                           │                           │
          │◄────── git restore ───────│                           │
          │                           │◄─── restore --staged ─────│
          │                           │                           │
          │◄───────── switch / checkout: index + files ───────────│
          │                           │                           │
          │                           │◄───── reset --mixed ──────│
          │◄──────────── reset --hard: index + files ─────────────│
          │                           │                           │
```

## 3.1 What the index really is

The index is a binary file holding one entry per tracked path, sorted by path:

```sh
$ git ls-files -s --debug
100644 ce013625030ba8dba906f756967f9e9ca394464a 0	greeting.txt
  ctime: 1795645029:499173278
  mtime: 1795645029:499173278
  dev: 16777231	ino: 85000490
  uid: 501	gid: 0
  size: 6	flags: 200000
```

Each entry stores:

* **mode, blob ID and path.** This is the content of the next commit. The index is flat:
  `git write-tree` builds the nested tree objects from it.
* **A stage number** (0 normally; 1, 2, 3 during a conflict; see 3.5).
* **A stat cache**: ctime, mtime, device, inode, uid, gid and size, taken when git last read the
  file.

The stat cache is why `git status` is fast. For each path, git calls `lstat()` and compares
the result with the cached values. If they match, the file is assumed unchanged and is never
read or hashed. Only files whose stat data differs are re-hashed.

**Racy git.** If a file is modified within the same timestamp granularity as the index was
written, stat data alone cannot prove it is unchanged. Git detects such "racily clean" entries
(mtime not older than the index file itself) and compares their content. This is why
`git status` occasionally does more work right after a commit, and why it is still correct.

The index also carries optional **extensions**:

| Extension | Purpose                                                              |
|-----------|----------------------------------------------------------------------|
| `TREE`    | Cache tree: tree IDs of unchanged directories, so `commit` does not rehash them |
| `REUC`    | "Resolve undo": the conflict stages of paths you resolved, for `checkout -m` |
| `UNTR`    | Untracked cache (`core.untrackedCache`): speeds up finding untracked files |
| `FSMN`    | File-system monitor token (`core.fsmonitor`)                         |
| `link`    | Split index: a small delta index on top of a shared base             |
| `sdir`    | Sparse index: whole directories outside the sparse cone as one entry |

Chapter 8 explains when you want each of them.

## 3.2 `git add` and `git commit`

`git add <path>`:

1. reads the file, applies clean filters and line-ending conversion (chapter 11),
2. writes a blob object (immediately: staged content is already in the object store),
3. updates that path's index entry with the blob ID, mode and fresh stat data.

So once you `git add` a file, its content is safe even if you later overwrite the file: the
blob is there (see "dangling blobs" in chapter 6).

`git add -p` stages selected hunks. It builds a blob that matches neither the old nor the new
file; that blob is what gets committed. This is how you split one messy working tree into
several clean commits.

`git commit`:

1. runs the `pre-commit` hook, then prepares the message (`prepare-commit-msg`,
   `commit-msg` hooks),
2. writes trees from the index (`write-tree`; the cache tree makes unchanged directories free),
3. writes the commit object with HEAD as parent,
4. moves the branch HEAD points to, appends to the reflog, runs `post-commit`.

`git commit -a` is `git add -u` (stage every *tracked* modified or deleted file) followed by a
commit. It never adds untracked files.

## 3.3 The modern commands: `switch` and `restore`

`git checkout` does two unrelated jobs (move HEAD to another branch, and overwrite files). Git
2.23 split them:

| Goal                                         | Command                                 |
|----------------------------------------------|-----------------------------------------|
| Go to a branch                               | `git switch main`                       |
| Create a branch and go to it                 | `git switch -c feature` (`-C` to reset an existing one) |
| Inspect an old commit                        | `git switch --detach <commit>`          |
| Discard unstaged edits to a file             | `git restore <path>`                    |
| Unstage a file, keep the edits               | `git restore --staged <path>`           |
| Make file match HEAD in both index and disk  | `git restore --staged --worktree <path>` |
| Get a file as it was in another commit       | `git restore --source=<commit> <path>`  |

`git switch` refuses to overwrite local changes that conflict with the target branch, and it
carries non-conflicting changes across. `git switch -m` merges the local changes in, and
`--discard-changes` throws them away.

## 3.4 `git reset`, completely

`reset` does up to three things, in order, and the mode tells it where to stop:

1. **Move the branch HEAD points to** to the target commit (HEAD itself stays attached).
2. **Make the index match** the target commit's tree.
3. **Make the working tree match** the index.

| Mode               | 1. branch | 2. index | 3. working tree | Use it to                             |
|--------------------|-----------|----------|-----------------|---------------------------------------|
| `--soft`           | ✓         |          |                 | Uncommit, keep everything staged (squash the last N commits) |
| `--mixed` (default) | ✓        | ✓        |                 | Uncommit and unstage, keep the edits  |
| `--hard`           | ✓         | ✓        | ✓               | Throw away commits *and* local changes |
| `--keep`           | ✓         | ✓        | ✓ (refuses if it would lose local edits) | A safe `--hard` |
| `--merge`          | ✓         | ✓        | ✓ (keeps unstaged edits) | Abort a conflicted merge (prefer `merge --abort`) |

With a path, `git reset <commit> -- <path>` does step 2 for that path only; it never moves
the branch. `git restore --staged --source=<commit> <path>` is the same thing.

Examples:

```sh
git reset --soft HEAD~3   # turn the last 3 commits into one staged change; then commit
git reset HEAD~1          # undo the last commit, keep its changes unstaged
git reset --keep HEAD~1   # drop the last commit; refuse if that would lose local edits
git reset --hard @{u}     # make my branch identical to its upstream (discards everything!)
```

`--hard` is the one command in this chapter that destroys data that may be unrecoverable:
**uncommitted, unstaged** edits are not in the object store. Committed work (and anything you
ever `git add`ed) can be recovered through the reflog or `git fsck`.

## 3.5 The index during a conflict

When a merge cannot combine a file, the index holds up to three versions of that path instead
of one, using the stage number:

| Stage | Contents                                     | Show it with      |
|-------|----------------------------------------------|-------------------|
| 1     | The merge base ("base")                      | `git show :1:path` |
| 2     | Your side, HEAD ("ours")                     | `git show :2:path` |
| 3     | The incoming side, MERGE_HEAD ("theirs")     | `git show :3:path` |

From a real conflict in the scratch repository:

```sh
$ git ls-files -s greeting.txt
100644 ce013625030ba8dba906f756967f9e9ca394464a 1	greeting.txt
100644 d42034c5a27bff914ef42fc0f806deb800fed7b0 2	greeting.txt
100644 bb985012e7857ff80f6e9f7f27d0206ae809be19 3	greeting.txt
```

`git add <path>` resolves the conflict: it collapses the three stages into one stage-0 entry.
A commit is impossible while any path has stages 1–3; that is how git knows the merge is
unfinished. Chapter 4 covers resolving.

## 3.6 Stash, as objects

`git stash` saves the index and working tree as commits, then resets them to HEAD:

* an **index commit** `I` (tree = your index; parent = HEAD),
* a **working-tree commit** `W` (tree = your tracked files; parents = HEAD and `I`),
* with `-u`/`--include-untracked`, a third commit `U` holding untracked files, added as
  `W`'s third parent.

```
refs/stash ──► W ──────────────► HEAD   1st parent: the commit you were on
               ├──► I ─────────► HEAD   2nd parent: index commit; its parent is HEAD
               └──► U                   3rd parent: untracked files (only with -u)
```

`refs/stash` points to `W`. Older stashes are entries in `refs/stash`'s reflog, which is why
they are named `stash@{0}`, `stash@{1}`. Useful consequences:

* `git stash show -p stash@{1}` and `git diff stash@{0}^1 stash@{0}` are ordinary commit
  diffs.
* `git stash apply --index` restores the index separately; plain `apply` merges everything
  into the working tree only.
* `git stash pop` is `apply` followed by `drop`, and it skips the drop if the apply
  conflicts.
* A dropped stash is a dangling commit, recoverable with `git fsck` until it is pruned
  (chapter 6).
* `git stash push -p`, `git stash push -- <paths>` and `git stash -S` (staged changes
  only) stash a subset.

Prefer a temporary commit on a branch for anything you will want for more than a few minutes:
stashes are easy to forget, are not pushed, and stack up.

## 3.7 Worktrees: several working trees, one repository

`git worktree add ../hotfix main` creates another working directory with its own HEAD and
index, sharing the same object store and refs. Use it instead of stashing when you need to
switch context (review a PR, run a long test, fix production) without disturbing your current
checkout. Two worktrees cannot check out the same branch at once. `git worktree list`,
`remove` and `prune` manage them. [Chapter 13](13-worktrees.md) covers worktrees in depth.

## 3.8 Line endings and the index

The blob in the index is the canonical form; the working tree file may differ because of
filters. With `* text=auto` in `.gitattributes`, text files are stored with LF and checked
out with the platform's line ending. A file that shows as modified with no visible change
after a line-ending policy change needs `git add --renormalize .`. Chapter 11 covers
attributes.

## 3.9 In production

| Feature                          | When you'd reach for it                                            |
|----------------------------------|--------------------------------------------------------------------|
| `git add -p`                     | Separate a refactor from a behaviour change, or leave debugging code out of a commit, so review and later bisect see clean units |
| `git restore --source=<tag>`     | Incident: roll back one file or directory (a config, a migration) to the released version without reverting whole commits |
| `git reset --soft`               | Collapse a messy branch into one commit before opening a PR        |
| `git reset --keep`               | Move a branch back safely when you also have local edits           |
| `git worktree`                   | A production fix while you are mid-feature; reviewing a PR without disturbing your checkout; running a long test suite on another branch; giving parallel tasks (or coding agents) their own checkout of one repository |
| `git stash`                      | A quick pull or branch switch with a dirty tree; anything longer belongs in a worktree or a WIP commit |
| `git diff --exit-code`           | CI check that generated code, formatting or lock files are committed: regenerate, then fail if anything changed |
| `git status --porcelain`         | Release scripts that refuse to tag or build from a dirty tree      |
| `git ls-files`                   | Feed linters and build tools exactly the tracked files, with `.gitignore` already applied |
| `git diff --cached --name-only`  | Pre-commit hooks that check only the staged files, so they stay fast |
| `--renormalize`                  | After adding `.gitattributes` line-ending rules to a repository shared by Windows and Unix developers |

```sh
# hotfix without stashing: a second checkout of the same repository
git worktree add -b hotfix/INC-2231 ../app-hotfix origin/release/1.4
cd ../app-hotfix && ...fix, test, push... && cd - && git worktree remove ../app-hotfix

# CI: fail if code generation or formatting was not committed
make generate fmt
git diff --exit-code || { echo 'run "make generate fmt" and commit the result'; exit 1; }

# release script guard
test -z "$(git status --porcelain)" || { echo 'working tree is dirty'; exit 1; }

# incident: put one config file back to what shipped in v1.4.0
git restore --source=v1.4.0 -- config/rate-limits.yaml
git commit -am 'config: restore v1.4.0 rate limits'

# lint only what is staged
git diff --cached --name-only --diff-filter=ACM -z -- '*.py' | xargs -0 ruff check
```

`git update-index --skip-worktree <file>` makes git ignore your local edits to a tracked file
(a local config override). It is a per-clone workaround: it is lost on re-clone, and it breaks
when upstream changes the file. Prefer a tracked template plus an ignored local file
(`config.example.yaml` → `config.local.yaml`).

## Exercises

1. Edit a file, `git add` it, edit it again. Run `git diff`, `git diff --staged` and
   `git diff HEAD`, and explain each output in terms of the three trees.
2. For each reset mode, predict the output of `git status` after `git reset --<mode> HEAD~1`,
   then check.
3. Stage half of a file with `git add -p`. Find the staged blob with `git ls-files -s` and
   `git cat-file -p` it.
4. Create a conflict and inspect `:1:`, `:2:` and `:3:` of the file. Resolve it by choosing
   stage 3 with `git checkout --theirs <path>` and `git add`.
5. Stash with `-u`, then use `git log --graph --oneline stash@{0}` and `git cat-file -p` to
   find all three commits.
