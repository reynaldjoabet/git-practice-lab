# 5. Rebasing and rewriting history

Commits are immutable, so git cannot edit history. "Rewriting" means creating new commits
that look like edited versions of the old ones, then moving a ref to them. Once you see it that
way, rewriting is not dangerous; *sharing* rewritten history is what needs care.

## 5.1 What `git rebase` does

```
        C---D        feature                          C'---D'   feature
       /                        git rebase main      /
  A---B---E          main          ===>     A---B---E           main
```

`git rebase main` (while on `feature`):

1. Finds the commits to replay: those in `main..feature` (reachable from `feature`, not from
   `main`): `C` and `D`. By default it also drops commits whose patch is already in `main`
   (same patch ID).
2. Detaches HEAD at the new base (`main`).
3. Cherry-picks each commit in order, creating `C'` and `D'`. Each is a three-way merge
   (chapter 4), and each can stop on a conflict.
4. Moves `refs/heads/feature` to the last new commit and re-attaches HEAD.

`C'` has the same message, author and diff as `C`, but a different parent and therefore a
different tree and ID. From the scratch repository: `feature` was `c1423c6` before the rebase
and `dcbd9b3` after it. `C` and `D` still exist; only the name moved. `ORIG_HEAD` and the
reflog point at them.

While stopped:

```sh
git status                   # shows which commit is being applied
git rebase --continue        # after resolving and `git add`
git rebase --skip            # drop this commit
git rebase --abort           # go back to exactly where you started
```

## 5.2 Choosing the base: `--onto`

The general form is:

```sh
git rebase --onto <newbase> <upstream> <branch>
```

"Take the commits in `<upstream>..<branch>` and replay them onto `<newbase>`." Plain
`git rebase main` is `git rebase --onto main main HEAD`.

Classic use: you branched `topic` from `feature`, but `topic` should only contain its own
commits, rebased onto `main`:

```
  A---B---E               main                                      F'---G'   topic
       \                             git rebase --onto             /
        C---D             feature    main feature topic   A---B---E           main
             \                             ===>                \
              F---G       topic                                 C---D         feature
```

```sh
git rebase --onto main feature topic
```

Another use: drop commits from the middle of a branch: `git rebase --onto X~3 X~1 X` removes
the two commits `X~2` and `X~1`.

## 5.3 Interactive rebase

`git rebase -i <base>` opens a todo list, one line per commit, oldest first:

```
pick c1423c6 feature: greet
pick 9a1b2c3 wip
pick 4d5e6f7 fix typo in greet

# p, pick   = use commit
# r, reword = use commit, but edit the message
# e, edit   = use commit, but stop to amend it (split it, change files)
# s, squash = meld into previous commit, combine messages
# f, fixup  = meld into previous commit, keep previous message (-C: keep this one's)
# d, drop   = remove commit
# x, exec   = run a shell command (stop if it fails)
# b, break  = stop here (continue with git rebase --continue)
# l, label / t, reset / m, merge = used by --rebase-merges
# u, update-ref = also move this ref (see --update-refs)
```

You can reorder lines, change verbs and add lines. Common recipes:

* **Squash fixes into the commit they fix.** Change `pick` to `fixup` and move the line
  under its target.
* **Split a commit.** Mark it `edit`. When rebase stops, run `git reset HEAD~1` (the
  changes become unstaged), make several commits with `git add -p`, then
  `git rebase --continue`.
* **Check every commit builds.** `git rebase -i --exec 'make test' main` adds an `exec` line
  after each commit; the rebase stops at the first commit that fails.

### Autosquash: fixups without editing the list

```sh
git commit --fixup=<commit>          # "fixup! <subject>", melds into <commit>
git commit --fixup=amend:<commit>    # also replaces <commit>'s message
git commit --fixup=reword:<commit>   # only replaces the message
git commit --squash=<commit>         # "squash! <subject>", combines messages

git rebase -i --autosquash main      # moves and marks the fixups automatically
git config --global rebase.autoSquash true
```

This is the professional way to respond to review comments: one `--fixup` per comment, and
one autosquash rebase before merging.

## 5.4 Rebasing a stack of branches: `--update-refs`

If `part-3` is based on `part-2`, which is based on `part-1`, rebasing `part-3` used to leave
`part-1` and `part-2` pointing at the old commits. With `--update-refs`, rebase moves every
local branch that points at a commit being rewritten:

```sh
git switch part-3
git rebase --update-refs main          # part-1, part-2 and part-3 all move
git config --global rebase.updateRefs true
```

Then push all of them (`git push --force-with-lease origin part-1 part-2 part-3`). The
repository's `stacked-pr.md` covers stacked pull requests in detail.

## 5.5 Rebasing merges: `--rebase-merges`

By default rebase **flattens** history: merge commits in the range are dropped and their
changes linearised. `--rebase-merges` (`-r`) preserves the branch-and-merge shape, using
`label`, `reset` and `merge` commands in the todo list. It re-performs each merge, so conflicts
that were resolved in the original merge may need resolving again (rerere helps).

## 5.6 Amend, and other one-commit rewrites

```sh
git commit --amend                  # replace the last commit with index + new message
git commit --amend --no-edit        # keep the message (add a forgotten file)
git commit --amend --reset-author   # take authorship and a new timestamp
git commit --amend --date=now       # only bump the author date
```

`--amend` writes a new commit with the old one's parents and moves the branch. The old commit
is in the reflog.

## 5.7 Rewriting a whole repository

For changes across all of history (remove a leaked secret or a huge file, extract a
subdirectory into its own repository, fix author emails) use
**[git-filter-repo](https://github.com/newren/git-filter-repo)**, the tool the Git project
recommends. `git filter-branch` is deprecated: it is slow and easy to get wrong.

```sh
git filter-repo --path secrets.env --invert-paths        # delete a file from all history
git filter-repo --strip-blobs-bigger-than 50M            # drop large blobs
git filter-repo --subdirectory-filter services/billing   # make a subdirectory the root
git filter-repo --mailmap .mailmap                       # rewrite names and emails
git filter-repo --replace-text expressions.txt           # redact strings everywhere
```

filter-repo insists on running in a fresh clone, removes `origin`, and rewrites every
branch and tag. Rewriting shared history is an organisational event: see 5.9.

**Removing a leaked secret is not enough.** Rotate the credential first. Copies remain in every
existing clone and fork, in the hosting service's caches and pull-request refs, and in CI
logs. Contact the host's support to purge cached views if needed.

## 5.8 The golden rule

> Do not rewrite commits that other people have based work on.

When you force-push a rewritten branch, everyone who has the old commits now holds a divergent
history. Their next `pull` produces a merge of the old and new versions of the same changes,
duplicating every commit.

What *is* fine:

* Rewriting your own branch that nobody else builds on (typical for a pull-request branch;
  reviewers only look, they do not commit on it).
* Rewriting before the first push. This is where most cleanup should happen.

If you do rewrite a shared branch, announce it. Others then run:

```sh
git fetch
git rebase --onto origin/feature <old-origin/feature> feature  # replay only their own work
# or, with no local work worth keeping:
git reset --hard origin/feature
```

`git pull --rebase` handles many of these cases automatically because it uses the reflog of
the remote-tracking ref to find the old fork point (`--fork-point`).

Force-push safely (chapter 7): `git push --force-with-lease --force-if-includes`, never plain
`--force`.

## 5.9 Rebase vs merge: what each costs

| Concern                       | Merge                                     | Rebase                                    |
|-------------------------------|-------------------------------------------|-------------------------------------------|
| History shape                 | True record of parallel work              | Linear; reads like a story                |
| Commit IDs                    | Preserved                                 | New IDs for every replayed commit         |
| Conflicts                     | Resolved once, in the merge commit        | Resolved per replayed commit (can repeat) |
| Safe on shared branches       | Yes                                       | Only before others build on it            |
| `bisect`, `blame`, `log` clarity | Noisy with many merge commits          | Easy to bisect commit by commit           |
| Tested states                 | The merge commit may be untested          | Each replayed commit is a new, untested state |

The last row matters more than it looks: after a rebase, intermediate commits never existed
before and were never built. `git rebase --exec 'make test'` checks them. Chapter 10 turns
this into a team policy.

## 5.10 In production

| Feature                          | When you'd reach for it                                            |
|----------------------------------|--------------------------------------------------------------------|
| `git rebase origin/main`         | Keep a PR branch current before review or merge, so CI tests it against today's `main` |
| `rebase --onto`                  | A stacked PR's parent was squash-merged: replay only the child's commits onto `main`. Or move a hotfix written on `main` onto a release branch |
| `--fixup` + `--autosquash`       | Answering review comments: one fixup per comment during review, one clean history at merge time |
| `rebase -i --exec 'make test'`   | Before a rebase-merge, prove every commit builds and passes, so `bisect` on `main` never lands on a broken commit |
| `--update-refs`                  | Stacked PRs: one rebase moves every branch in the stack            |
| `--rebase-merges`                | Rebasing a branch that has meaningful internal merges, such as a downstream fork that merges upstream releases |
| `commit --amend`                 | Fix the last commit before pushing (message, forgotten file); `--reset-author` when you take over someone's work |
| `git filter-repo`                | Remove a leaked secret or a huge file from history; split a service out of a monorepo with its history; open-source one directory; fix author emails after a company rename |
| `pull --rebase` / `rebase.autoStash` | Day-to-day: keep local commits on top of upstream without merge noise |

```sh
# the parent PR (part-1) was squash-merged into main; retarget the child PR
git fetch origin
git rebase --onto origin/main part-1 part-2     # replay only part-2's own commits
git push --force-with-lease --force-if-includes origin part-2

# a fix written on top of main must ship on release/1.4 too
git rebase --onto origin/release/1.4 origin/main hotfix

# extract services/billing into its own repository, keeping its history
git clone --no-local monorepo billing && cd billing
git filter-repo --subdirectory-filter services/billing
git remote add origin git@github.com:acme/billing.git && git push -u origin --all --tags
```

In the first example, `part-1` must still point at the parent's *old* tip (the commits `part-2`
was built on). If you deleted or updated it, use `part-1@{1}` or the old ID from the reflog.

## Exercises

1. Rebase a two-commit branch onto an advanced `main`. Use the reflog to show the old and
   new IDs side by side, then compare their patches with `git range-diff` (chapter 9).
2. Use `rebase -i` to split one commit into two, then reorder them.
3. Make three `--fixup` commits for different earlier commits and clean up with one
   `rebase -i --autosquash`.
4. Build a three-branch stack, add a commit to the bottom branch, and rebase the whole stack
   with `--update-refs`.
5. Rebase a branch, then undo the rebase completely using `ORIG_HEAD`, and again using
   `feature@{1}`.
