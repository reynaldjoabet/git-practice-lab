# 12. Incident playbook

Short recipes for the situations that actually happen. Each one names the chapter that
explains *why* it works; if a recipe surprises you, read that chapter before using it on
shared history.

Two rules before any recovery:

1. **Stop and look.** `git status`, `git log --oneline --graph -10 --all`, `git reflog -10`.
2. **Make it cheap to undo.** `git branch backup-$(date +%s)` costs nothing and pins the
   current state. For anything involving the working tree, `cp -a` the repository first.

## Commits

**1. Wrong message on the last commit (not pushed).**
`git commit --amend` (5.6).

**2. Forgot a file in the last commit (not pushed).**
`git add <file> && git commit --amend --no-edit` (5.6).

**3. Need to fix an older commit (not pushed).**
`git commit --fixup=<commit>` then `git rebase -i --autosquash <commit>~1` (5.3).

**4. Committed to `main` instead of a new branch (not pushed).**

```sh
git branch feature            # name the work where it is
git reset --keep origin/main  # move main back (refuses if it would lose local edits)
git switch feature
```

**5. Committed on the wrong branch, and the right one exists.**

```sh
git switch right-branch && git cherry-pick <commit>     # (4.11)
git switch wrong-branch && git reset --keep HEAD~1
```

**6. Undo a commit that is already pushed and shared.**
`git revert <commit>` (a new commit with the inverse change; history is not rewritten). For a
merge: `git revert -m 1 <merge>` (4.10).

**7. Split the last commit into several.**
`git reset HEAD~1`, then `git add -p` and commit in pieces (3.4).

**8. Squash a branch into one commit before merging.**
`git reset --soft $(git merge-base main HEAD) && git commit` (3.4), or `rebase -i` (5.3).

## Lost work

**9. "I ran `reset --hard` and lost my commits."**
`git reflog`, find the commit, `git reset --hard <id>` or `git branch rescue <id>` (6.3).

**10. "My rebase went wrong."**
During: `git rebase --abort`. After: `git reset --hard ORIG_HEAD`, or
`git reset --hard <branch>@{1}` (6.3).

**11. Deleted a branch.**
`git reflog | grep <branch-name>` → `git branch <branch-name> <id>` (6.3). If it was pushed,
`git branch <branch-name> origin/<branch-name>` while the remote-tracking ref still exists.

**12. Dropped or cleared a stash.**
`git fsck --no-reflogs --unreachable | grep commit`, find the `WIP on` commit,
`git stash apply <id>` (6.4).

**13. Overwrote a file that was staged but never committed.**
Dangling blob search with `git fsck --lost-found` (6.4).

**14. Lost uncommitted, unstaged edits.**
Git cannot help; it never saw them. Check your editor's local history (VS Code: Timeline view;
JetBrains: Local History) and backups (6.2). Prevention: commit or `git add` often.

## Merging and rebasing

**15. Mid-merge, it is a mess.**
`git merge --abort` (4.6). Mid-rebase: `git rebase --abort`. Mid-cherry-pick:
`git cherry-pick --abort`.

**16. Conflict: take one side for a whole file.**
`git checkout --ours <path>` or `--theirs <path>`, then `git add <path>`. **During a rebase
they are swapped:** `--ours` is the branch you are rebasing onto (4.6).

**17. The same conflicts keep coming back on every rebase.**
Enable rerere (4.7), or squash the branch first so there is only one commit to replay.

**18. Merged the wrong branch (not pushed).**
`git reset --hard ORIG_HEAD` (4.10).

**19. After squash-merging, `git branch -d` says the branch is not fully merged.**
Expected: squash merges record no ancestry (4.2). Confirm the content landed
(`git diff main...feature` should be empty, or `git cherry main feature`), then `git branch -D`.

**20. My PR shows commits that are already in `main`.**
The branch was based on an old commit, or the base branch was rewritten. Rebase onto the
current base, replaying only your commits:
`git rebase --onto origin/main <old-base> feature` (5.2), then force-push with lease.

## Remotes

**21. Push rejected: non-fast-forward.**
Someone pushed first. `git fetch`, then `git rebase origin/<branch>` (or merge), then push
(7.5). Never "fix" this with `--force` on a shared branch.

**22. I force-pushed and wiped a colleague's commits.**
Their commits are in *their* clone, and in your reflog for `origin/<branch>` if you had fetched
them: `git reflog show origin/<branch>`. Restore with
`git push --force-with-lease origin <good-id>:<branch>`. Hosting services also keep a log of
force pushes (GitHub's activity view / events API) from which a branch can be restored. Then
adopt `--force-with-lease --force-if-includes` (7.6).

**23. Someone force-pushed a branch I have local work on.**

```sh
git fetch
git rebase --onto origin/feature <old-origin/feature-id> feature   # (5.8)
```

`git reflog show origin/feature` gives the old ID.

**24. Committed a secret.**
Not pushed: amend or rebase it out, then `git reflog expire` + `gc --prune=now` (6.6).
Pushed: **revoke and rotate the secret first**, it is compromised. Then rewrite history with
`git filter-repo` and coordinate everyone's re-clone (5.7, 10.8).

**25. Committed a huge file; push is rejected or slow.**
Not pushed: `git rm --cached <file>`, `git commit --amend` (or rebase it out of older commits),
ignore it or track it with LFS. Pushed: `git filter-repo --strip-blobs-bigger-than` (5.7).

**26. Authentication suddenly fails.**
`GIT_TRACE_CURL=1 git fetch` (HTTPS) or `GIT_SSH_COMMAND='ssh -v' git fetch` (SSH) to see
which credential is offered; check the credential helper and token expiry (7.7).

## Working tree and repository state

**27. Detached HEAD with new commits.**
`git switch -c <name>` to keep them (2.2).

**28. A file I ignored is still tracked.**
`git rm --cached <file>` and commit; ignore rules never apply to tracked files (11.3).

**29. Every file shows as modified, but there are no visible changes.**
Line endings or file modes. `git diff --stat` and `git diff -R | head` show which. For modes:
`git config core.fileMode false` (only on file systems that cannot store the executable bit).
For line endings: fix `.gitattributes`, then `git add --renormalize .` (3.8, 11.4).

**30. `Unable to create '.git/index.lock': File exists`.**
Another git process is running, or one crashed. Check with `ps aux | grep git`; if nothing is
running, delete the lock file (2.4).

**31. `fatal: detected dubious ownership in repository`.**
The repository belongs to another user. If you trust it:
`git config --global --add safe.directory <path>` (11.8).

**32. `git status` is slow.**
`GIT_TRACE2_PERF=1 git status`; then fsmonitor, untracked cache, ignore large untracked
directories, run `git maintenance start` (8.5, 8.9).

**33. "Which release first contained this fix?"**
`git tag --contains <commit> | sort -V | head -1`, or `git describe --contains <commit>` (9.6).

**34. "When did this break?"**
`git bisect run <test-script>` (9.4).

## A decision tree for undo

```
Is the commit pushed and used by others?
├── yes → git revert                    (never rewrite shared history)
└── no  → Do you want to keep the changes in your working tree?
          ├── yes, staged      → git reset --soft <target>
          ├── yes, unstaged    → git reset <target>
          └── no, discard them → git reset --keep <target>  (or --hard if you are sure)
```
