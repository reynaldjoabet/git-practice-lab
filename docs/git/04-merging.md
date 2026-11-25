# 4. Merging

A merge combines two lines of history. Git does it by comparing both sides against their
common ancestor, the **merge base**. Everything in this chapter follows from that one idea.

## 4.1 Merge base

The merge base of two commits is their best common ancestor: a commit reachable from both,
with no other common ancestor that is a descendant of it.

```
        C---D        feature
       /
  A---B---E          main
```

```sh
$ git merge-base main feature
<B>
```

Everything git knows about "what changed on each side" is `diff(B, E)` (main's side) and
`diff(B, D)` (feature's side).

## 4.2 Fast-forward vs a true merge

If the current branch is itself an ancestor of the branch being merged, there is nothing to
combine. Git just moves the branch pointer forward:

```
  A---B             main            git merge feature            A---B---C---D   main, feature
       \                                    ===>
        C---D       feature
```

No commit is created. Otherwise git creates a **merge commit** with two parents:

```
        C---D        feature
       /     \
  A---B---E---M      main         M's 1st parent is E (main), 2nd parent is D (feature)
```

Flags that control this:

| Flag        | Behaviour                                                         |
|-------------|-------------------------------------------------------------------|
| `--ff`      | Default: fast-forward when possible, otherwise merge commit       |
| `--ff-only` | Fast-forward or fail. Safe default for `pull` (`pull.ff=only`)    |
| `--no-ff`   | Always create a merge commit, so the branch stays visible as a unit in history |
| `--squash`  | Apply the combined change to the index and stop. You commit it as one ordinary, single-parent commit; **no ancestry is recorded** |

## 4.3 The three-way merge, file by file

For every path, git compares three versions: base (B), ours (HEAD) and theirs (the commit
being merged).

| Base | Ours | Theirs | Result                                   |
|------|------|--------|------------------------------------------|
| x    | x    | x      | x                                        |
| x    | y    | x      | y (only we changed it)                   |
| x    | x    | y      | y (only they changed it)                 |
| x    | y    | y      | y (both made the same change)            |
| x    | y    | z      | **merge the contents line by line**      |

Inside a file changed on both sides, git diffs base→ours and base→theirs, and takes hunks that
do not overlap from either side. Hunks that touch the same or adjacent lines are **conflicts**.

This is why a merge is correct only relative to its base. Git never asks "which version is
newer"; it asks "which side changed this, relative to the ancestor".

### Conflict markers

Set the conflict style once, globally. The default (`merge`) hides the base, which is the most
useful part:

```sh
git config --global merge.conflictStyle zdiff3
```

With `zdiff3` (or `diff3`), a conflict shows all three versions:

```
<<<<<<< HEAD
hello from main
||||||| f0a4725
hello
=======
hello from feature
>>>>>>> feature
```

Reading it: the base said `hello`; we changed it to `hello from main`; they changed it to
`hello from feature`. Seeing the base tells you *what each side intended*, which is what
you need to write the correct combination (often neither side's text).

## 4.4 The `ort` merge strategy

The default strategy is **ort** ("Ostensibly Recursive's Twin"), the default since Git 2.34.
It replaced `recursive`; since Git 2.50, `-s recursive` is just another name for `ort`. It
does more than line merging:

* **Rename detection.** If one side renamed `a.py` to `b.py` and the other side edited
  `a.py`, the edit is applied to `b.py`. Renames are not recorded anywhere; git infers them
  by content similarity (50% by default, `-X find-renames=<n>`).
* **Directory rename detection.** If one side moved everything in `lib/` to `src/lib/`, a
  file the other side *added* to `lib/` is placed in `src/lib/`.
* **Criss-cross merges.** When there is more than one best merge base (two branches that have
  merged into each other), ort first merges the merge bases together into a virtual commit
  and uses that as the base. This is the "recursive" part of the name.
* **No working-tree side effects while merging.** ort computes the whole result in memory and
  then updates the index and files once. That is what makes it fast and what makes
  `git merge-tree` possible.

### Other strategies and strategy options

Strategies (`-s`) replace the algorithm; strategy options (`-X`) tune ort.

| Use                                 | What it does                                           |
|-------------------------------------|--------------------------------------------------------|
| `-X ours` / `-X theirs`             | On *conflicting hunks only*, take our or their side. Non-conflicting changes from both sides still merge |
| `-X ignore-space-change`, `-X ignore-all-space` | Treat whitespace-only differences as no change |
| `-X diff-algorithm=histogram`       | Better hunk alignment for code than the default `myers` |
| `-X renormalize`                    | Normalize line endings on all three sides before merging |
| `-s ours`                           | Record a merge but keep **our tree exactly**: discards everything from the other side. Used to mark a branch as merged without taking its changes |
| `-s octopus`                        | Merge more than two branches at once; refuses if there are conflicts |
| `-s subtree`                        | Merge a project that lives in a subdirectory of ours   |
| `-s resolve`                        | Older, simpler two-head algorithm; rarely needed       |

Do not confuse `-X ours` (resolve conflicts our way) with `-s ours` (ignore their changes
entirely).

## 4.5 Kinds of conflict

| Conflict          | Cause                                              | Typical resolution                     |
|-------------------|----------------------------------------------------|----------------------------------------|
| content           | Both sides changed the same lines                  | Edit the file, `git add`               |
| add/add           | Both sides added a file at the same path           | Combine, `git add`                     |
| modify/delete     | One side edited a file, the other deleted it       | `git add` to keep it, `git rm` to delete it |
| rename/delete     | One side renamed, the other deleted                | Same as modify/delete, on the new path |
| rename/rename     | Both sides renamed the same file to different names | `git rm` one name, `git add` the other |
| directory/file    | One side has a file where the other has a directory | ort moves the file aside (`path~HEAD`) |
| binary            | Both sides changed a binary file                   | Pick one: `git checkout --ours/--theirs` |

## 4.6 Resolving, step by step

```sh
git status                          # lists unmerged paths ("both modified" etc.)
git diff                            # combined diff: only the unresolved hunks, both sides
git log --merge -p -- <path>        # the commits on each side that touched this path
git show :1:<path>                  # base version (also :2: ours, :3: theirs)

# resolve each file by editing, or choose a side wholesale:
git checkout --ours   -- <path>     # take HEAD's version
git checkout --theirs -- <path>     # take the incoming version
git add <path>                      # mark resolved (collapses stages 1-3 to stage 0)

git diff AUTO_MERGE                 # what you changed compared with git's conflicted result
git merge --continue                # or: git commit
git merge --abort                   # give up: back to ORIG_HEAD, state before the merge
```

`git mergetool` opens a three-way merge tool (configure `merge.tool`, e.g. `vimdiff`,
`meld`, `vscode`). Set `mergetool.keepBackup=false` to avoid `*.orig` files.

**"Ours" and "theirs" swap during a rebase.** A rebase replays your commits onto the
upstream, so HEAD (ours) is the upstream plus already-replayed commits, and "theirs" is your
commit being replayed. Read `--ours` during a rebase as "the branch I am rebasing onto".

### Test the resolution, not just the syntax

A merge without textual conflicts can still be wrong: one side renames a function, the other
side adds a new call to the old name. Both apply cleanly, and the build breaks. That is a
**semantic conflict**, and git cannot detect it. Always build and test the merge result, and
prefer CI that tests the *merged* result (a merge queue, chapter 10) over the branch alone.

## 4.7 rerere: reuse recorded resolutions

```sh
git config --global rerere.enabled true
git config --global rerere.autoUpdate true   # also stage auto-resolved files
```

With rerere ("reuse recorded resolution") on, git records each conflict (the "preimage") and
your resolution, keyed by the conflict's content, in `.git/rr-cache`. The next time the same
conflict appears, during a rebase, a repeated merge or a re-run of a long-running integration
branch, git applies your resolution automatically. You will see `Recorded preimage for ...`
and later `Resolved ... using previous resolution`. `git rerere forget <path>` drops a bad
recorded resolution.

## 4.8 Merging without touching the working tree

`git merge-tree --write-tree` performs a full ort merge in memory and prints the resulting tree
ID, plus conflict information. It needs no working tree, so it is what hosting services and
merge queues use to test mergeability:

```sh
$ git merge-tree --write-tree main feature
<tree-id>                                         # exit code 0: clean
# exit code 1: conflicts; the output lists conflicted paths and messages

# turn a clean result into a merge commit without checking anything out:
tree=$(git merge-tree --write-tree main feature)
git commit-tree "$tree" -p main -p feature -m 'Merge feature'
```

## 4.9 Reviewing a merge

* `git show <merge>` shows a **combined diff**: only the lines that differ from *both*
  parents. A merge that resolved cleanly often shows nothing.
* `git show --remerge-diff <merge>` re-runs the merge and diffs that automatic result
  against what was actually committed. It shows exactly how conflicts were resolved and any
  "evil" changes (edits made in a merge commit that come from neither parent). Use it when
  reviewing merges.
* `git diff <merge>^1 <merge>` is everything the merge brought into the first-parent line.

## 4.10 Undoing a merge

* **Not pushed yet:** `git reset --hard ORIG_HEAD` (or `--keep`), right after the merge.
* **Already shared:** `git revert -m 1 <merge>`. `-m 1` names the first parent as the
  mainline to keep, so the revert undoes what the second parent brought in.

**The revert-the-revert trap.** Reverting a merge undoes its *changes*, but the merged
commits remain ancestors of the branch. If you later fix the feature branch and merge it
again, git sees those old commits as already merged and brings in only the new commits. The
original work stays reverted. Before re-merging, **revert the revert**
(`git revert <revert-commit>`), then merge the fixes.

## 4.11 Cherry-pick and revert are merges too

`git cherry-pick C` is a three-way merge with base = `C^`, ours = HEAD, theirs = `C`: it
applies the change `C^→C` to HEAD. `git revert C` is the same with base and theirs swapped
(base = `C`, theirs = `C^`), which applies the inverse change. That is why both can conflict,
and why both use the same conflict tools.

* `git cherry-pick -x` appends `(cherry picked from commit ...)` to the message, which keeps
  backports traceable. Use it on release branches.
* `git cherry-pick A..B` picks a range (excludes `A`).
* Cherry-picked commits have new IDs. `git cherry -v main feature` and
  `git log --cherry-pick --right-only main...feature` identify commits whose *patch* is
  already upstream, by comparing patch IDs.

## 4.12 In production

| Feature                          | When you'd reach for it                                            |
|----------------------------------|--------------------------------------------------------------------|
| `--ff-only`                      | Promoting a tested commit between environment branches (`main` → `production`) so the deployed commit is exactly the tested one; pulls on servers that must never create merges |
| `--no-ff`                        | Landing a release or long-running branch so it stays visible and revertible as one unit |
| `revert -m 1 <merge>`            | Roll back a bad pull request in production *first*, then debug. Fast, reviewable, no history rewrite |
| `cherry-pick -x`                 | Backport a fix from `main` to supported release branches, with a trace back to the original |
| `merge-tree --write-tree`        | Bots and merge queues that test mergeability without a checkout; a CI job that warns when a PR would conflict with a release branch |
| `rerere`                         | Maintaining a fork with downstream patches on top of upstream; repeatedly rebuilt integration branches; long rebases |
| `-s ours`                        | Record that a release branch's fix is already on `main` in another form, so later merges of the release branch do not bring it back |
| `-X theirs`                      | Automated sync of files you never edit locally (a vendored upstream directory). Never as a general "make the conflict go away" |
| `-s octopus`                     | Build a throwaway integration branch from many topic branches for a combined test run |
| `--remerge-diff`                 | Audits and review: show exactly what someone did while resolving conflicts, including hidden changes in a merge commit |

```sh
# environment promotion: production only ever fast-forwards to a tested commit
git fetch origin
git push origin origin/main:production          # rejected unless it is a fast-forward

# production rollback of a merged PR
git revert -m 1 <merge-commit> && git push

# backport to every supported release
for b in release/1.3 release/1.4; do
  git switch "$b" && git cherry-pick -x <fix> && git push origin "$b"
done

# would this PR merge cleanly into the release branch? (no checkout needed)
git merge-tree --write-tree --name-only origin/release/1.4 origin/feature >/dev/null \
  || echo 'conflicts with release/1.4'
```

The push in the first example works because, without `+` or `--force`, the server accepts only a
fast-forward. That property *is* the promotion policy.

## Exercises

1. Build the criss-cross history (two branches that merge into each other, then each gets one
   more commit). Run `git merge-base --all` and see two bases.
2. Rename a file on one branch and edit it on another. Merge, and confirm the edit landed on
   the new name. Repeat with `-X no-renames`.
3. Create a conflict, resolve it with a combination of both sides, commit, and inspect it with
   `git show --remerge-diff`.
4. Turn on rerere, resolve a conflict, `reset --hard ORIG_HEAD`, and merge again.
5. Merge a branch, revert the merge, add a fix on the branch, and merge again. Confirm the
   original changes are missing. Then fix it by reverting the revert.
