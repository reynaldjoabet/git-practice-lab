# 2. Refs, HEAD and branches

Objects are immutable and anonymous. Refs give them names that can move.

## 2.1 A ref is a name that holds an object ID

```sh
$ cat .git/refs/heads/main
7e441454769a264787d7f3a98f6fbdb39d83595d
```

That is the whole of a branch: a 41-byte file. Creating a branch writes one file; deleting it
removes one file. No commits are copied, moved or deleted. This is why branches in git are
cheap and why "which commits are on a branch" is a *computed* answer (everything reachable
from the tip by following parents), not a stored list.

The namespaces under `refs/`:

| Ref                     | Short name      | Moves when                                  |
|-------------------------|-----------------|---------------------------------------------|
| `refs/heads/<name>`     | `<name>`        | You commit, merge, rebase, reset on it      |
| `refs/tags/<name>`      | `<name>`        | Never (by convention; `tag -f` can, but don't) |
| `refs/remotes/<r>/<b>`  | `<r>/<b>`       | `fetch` (and `push`) records the remote's state |
| `refs/stash`            | `stash`         | `git stash`; earlier stashes live in its reflog |
| `refs/notes/commits`    |                 | `git notes`                                 |
| `refs/pull/N/head`      |                 | Hosting services (GitHub) publish PR heads here |

A short name is resolved by trying, in order: `.git/<name>` (for `HEAD`, `ORIG_HEAD`...),
`refs/<name>`, `refs/tags/<name>`, `refs/heads/<name>`, `refs/remotes/<name>`,
`refs/remotes/<name>/HEAD`. So **a tag and a branch with the same name are ambiguous, and the
tag wins**; git warns `refname is ambiguous`. Never create one.

## 2.2 HEAD: "where am I"

`HEAD` is normally a **symbolic ref**: it holds the name of another ref, not an object ID.

```sh
$ cat .git/HEAD
ref: refs/heads/main
```

When you commit, git writes the commit object, then updates *the ref that HEAD points at*.
That is how the branch "moves forward": the commit is written with the old tip as parent, and
`refs/heads/main` is rewritten to the new ID.

### Detached HEAD

If HEAD holds an object ID directly, it is **detached**:

```sh
$ git switch --detach v1.0          # or: git checkout <commit>
$ cat .git/HEAD
f0a4725ef374150233e02ed4371956c198651f7a
```

Nothing is wrong with this; rebase, bisect and CI checkouts all work detached. The only risk:
commits you make move HEAD, but no branch. When you switch away, nothing names them, and
they are reachable only through the reflog (chapter 6). To keep them, name them before or
after leaving:

```sh
git switch -c rescue            # while still detached: makes a branch at HEAD
git branch rescue HEAD@{1}      # after switching away: name where HEAD just was
```

## 2.3 Special refs in `.git`

| File             | Written by                         | Meaning                                       |
|------------------|------------------------------------|-----------------------------------------------|
| `HEAD`           | switch, checkout, commit           | Current branch or commit                      |
| `ORIG_HEAD`      | reset, merge, rebase, am           | Where HEAD was before a "dangerous" operation |
| `FETCH_HEAD`     | fetch                              | What was just fetched; `git pull` merges this |
| `MERGE_HEAD`     | merge (while in progress)          | The commit(s) being merged in                 |
| `CHERRY_PICK_HEAD`, `REVERT_HEAD` | cherry-pick, revert (in progress) | The commit being applied              |
| `REBASE_HEAD`    | rebase (stopped)                   | The commit rebase is currently applying       |
| `AUTO_MERGE`     | merge with conflicts               | A tree of the merge result *with* conflict markers |

`git reset --hard ORIG_HEAD` is the classic undo for a merge or rebase you just finished.
`git diff AUTO_MERGE` shows what you have changed while resolving conflicts.

## 2.4 How refs are stored

There are three storage formats, and you must never assume which one a repository uses.

1. **Loose refs**: one file per ref under `.git/refs/`. Simple, but slow with tens of
   thousands of refs, and case-insensitive file systems (macOS, Windows) cannot hold two
   branches that differ only in case (`Feature` and `feature`).
2. **`packed-refs`**: one sorted text file. `git pack-refs` (run by `gc` and `maintenance`)
   moves loose refs into it. A loose ref, if present, overrides the packed entry.

   ```
   # pack-refs with: peeled fully-peeled sorted
   dcbd9b37aaff577db227963895f03922ce7128cb refs/heads/feature
   7e441454769a264787d7f3a98f6fbdb39d83595d refs/heads/main
   ```

   A line starting with `^` after a tag line holds the peeled commit ID of an annotated tag.
3. **reftable** (`git init --ref-format=reftable`, or `git refs migrate --ref-format=reftable`
   for an existing repository): a binary, block-based format that came from JGit. It gives
   atomic multi-ref updates, fast lookups across millions of refs, and no case-folding
   problems; it also stores reflogs. The Git project plans to make it the default in Git 3.0.

Because of this, **scripts must never read or write ref files directly**. Use plumbing:

```sh
git rev-parse refs/heads/main                       # read
git update-ref refs/heads/main <new> <expected-old> # compare-and-swap write
git symbolic-ref HEAD refs/heads/main               # point HEAD at a branch
git for-each-ref --format='%(refname) %(objectname:short)' refs/heads/
git show-ref --verify refs/heads/main               # check that a ref exists
```

`update-ref` with an expected old value is atomic: it fails if someone else moved the ref
first. Git implements that with a lock file (`refs/heads/main.lock`) that is created, written
and renamed into place. A stale `.lock` left by a crashed process makes later commands fail
with `Unable to create '....lock': File exists`; delete it only after confirming no git process
is running.

## 2.5 Branches, precisely

A branch is a ref under `refs/heads/`, plus optional configuration in `.git/config`:

```ini
[branch "feature"]
    remote = origin
    merge = refs/heads/feature
```

That configuration is the branch's **upstream**. It is what makes `git pull`, `git push`
(with `push.default=simple`), `@{upstream}`, and the "ahead 2, behind 1" in `git status` work.
Set it with `git push -u origin feature` or `git branch --set-upstream-to=origin/feature`.

Some facts that follow directly from "a branch is a pointer":

* **"Merged" is about reachability.** `git branch --merged main` lists branches whose tip is
  an ancestor of `main`. After a squash or rebase merge, the original commits are *not*
  ancestors, so the branch shows as unmerged even though its changes landed.
* **Deleting a branch deletes no commits.** It removes a name. Commits reachable from nothing
  else become unreachable and are later pruned (chapter 6).
* **Branches have no parent branch.** Git does not record "feature was branched from main".
  The fork point is computed from the graph (`git merge-base main feature`), and tools that
  appear to remember it (`--fork-point`) consult the reflog.
* **The default branch is a convention.** `init.defaultBranch` sets the name for new
  repositories; the remote's default is published as `refs/remotes/origin/HEAD`
  (`git remote set-head origin --auto` refreshes it).

## 2.6 The reflog

Every time a ref moves, git appends a line to its reflog: `.git/logs/HEAD` and
`.git/logs/refs/heads/<branch>` (or inside reftable). From the scratch repository:

```
dcbd9b3 HEAD@{0}: rebase (finish): returning to refs/heads/feature
dcbd9b3 HEAD@{1}: rebase (continue): feature: greet
7e44145 HEAD@{2}: rebase (start): checkout main
c1423c6 HEAD@{3}: checkout: moving from main to feature
7e44145 HEAD@{4}: reset: moving to HEAD
7e44145 HEAD@{5}: commit: main: greet
```

`HEAD@{3}` is where HEAD was three moves ago. `main@{yesterday}` is where `main` was
yesterday. `@{-1}` is the branch you were on before the current one (`git switch -` uses it).

Reflogs are **local only**: they are never pushed or fetched, and a fresh clone has none.
They are your safety net, and chapter 6 is mostly about using them.

## 2.7 Revision syntax you need early

| Expression        | Means                                                         |
|-------------------|---------------------------------------------------------------|
| `HEAD~3`          | Three generations back, following first parents               |
| `HEAD^2`          | The second parent of a merge commit                           |
| `HEAD^`           | Same as `HEAD^1` and `HEAD~1`                                 |
| `main@{u}`        | `main`'s upstream (`origin/main`)                             |
| `@{push}`         | Where `git push` would send the current branch                |
| `v1.0^{commit}`   | Peel a tag down to its commit                                 |
| `HEAD:src/app.py` | The blob at that path in HEAD's tree                          |
| `:src/app.py`     | The blob at that path in the index (stage 0)                  |
| `:/fix typo`      | The youngest reachable commit whose message matches the regex |

Chapter 9 covers ranges (`A..B`, `A...B`) and the rest.

## 2.8 In production

| Feature                          | When you'd reach for it                                            |
|----------------------------------|--------------------------------------------------------------------|
| Detached HEAD                    | CI runners and deploy servers check out a specific commit or tag, never a branch. Expect it in every pipeline |
| `refs/pull/N/head`, `.../merge`  | CI tests a pull request (or its merge with the base, `refs/pull/N/merge` on GitHub) without the contributor's fork |
| A "deployed" ref                 | Keep a tag or branch such as `deployed/prod` that the deploy job moves. Then "what will the next deploy ship?" is `git log deployed/prod..main` |
| `update-ref` with expected value | Bots that move branches must not lose a race with humans: compare-and-swap, never blind writes |
| `symbolic-ref` on `origin/HEAD`  | Scripts that must work whether the default branch is `main`, `master` or `trunk` |
| `for-each-ref`                   | Stale-branch reports and cleanup jobs; release scripts that list tags |
| `ORIG_HEAD`, reflog              | The first thing to reach for after a bad merge or rebase, before asking anyone |
| reftable                         | Repositories with very many refs (hosting servers, Gerrit, monorepos with many tags), and teams on macOS/Windows hitting branch names that differ only in case |

```sh
# default branch of the remote, for portable scripts
default=$(git symbolic-ref --short refs/remotes/origin/HEAD)   # e.g. origin/main

# what is about to be deployed, and which PRs those are
git log --first-parent --oneline deployed/prod..origin/main

# record a deploy (atomic: fails if someone else moved it first)
git update-ref refs/tags/deployed/prod "$NEW" "$OLD" && git push origin deployed/prod --force

# remote branches with no commits in 90 days, oldest first
git for-each-ref --sort=committerdate refs/remotes/origin \
  --format='%(committerdate:short) %(refname:short) %(authorname)' \
  | awk -v cutoff="$(date -v-90d +%F)" '$1 < cutoff'     # GNU date: date -d '90 days ago' +%F

# merged remote branches that can be deleted
git branch -r --merged origin/main | grep -vE 'origin/(main|HEAD)'
```

A moving tag like `deployed/prod` breaks the "tags never move" convention, so some teams use a
branch (`deploy/prod`) or a custom namespace (`refs/deploy/prod`, which must be pushed and
fetched explicitly). Pick one and document it.

## Exercises

1. Create a branch, then find the file (or `packed-refs` line) that stores it. Delete the
   branch and confirm with `git cat-file -t <id>` that the commit still exists.
2. Commit twice on a detached HEAD, switch to `main`, then recover both commits using only the
   reflog.
3. Create a tag and a branch both named `x`. What does `git rev-parse x` return, and what
   warning do you get?
4. Use `git update-ref` with a wrong expected-old value and observe it refuse.
5. Run `git pack-refs --all` and watch the loose ref files disappear while every command
   keeps working.
