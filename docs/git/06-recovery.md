# 6. Recovery and garbage collection

Git almost never deletes data immediately. Knowing exactly when it *does* is what lets you
recover from anything with confidence, and what lets you truly delete something when you must.

## 6.1 Reachability

An object is **reachable** if you can get to it from some ref (any branch, tag, remote-tracking
ref, `refs/stash`, notes, or HEAD) by following commit parents, tree entries and tag targets.

Reflog entries also count as roots for garbage collection, so a commit you "lost" with
`reset --hard` is still protected while any reflog entry mentions it.

Everything else is **unreachable**. Unreachable objects with nothing pointing to them at
all are called **dangling**.

## 6.2 What is recoverable

| You did                                         | Recoverable?                        | From                     |
|-------------------------------------------------|-------------------------------------|--------------------------|
| `reset --hard`, `rebase`, `amend`, branch delete | Yes, committed work                 | Reflog, `ORIG_HEAD`      |
| `stash drop` / `stash clear`                    | Yes                                 | `git fsck` (dangling commit) |
| Overwrote a file you had `git add`ed            | Yes, the staged version             | `git fsck` (dangling blob) |
| `reset --hard` over edits never staged          | **No** (git never saw them)         | Your editor's local history, backups |
| `git clean -fdx`                                | **No** (untracked files)            | Backups only             |
| `git restore <file>` / `checkout -- <file>`     | **No** for the unstaged edits       | Editor history           |
| Anything, after `gc` with expired reflogs       | **No**                              | Another clone            |

Rule of thumb: **if git ever stored it as an object, you can get it back for at least two
weeks.** Run `git add` (or commit) often; it is cheap insurance.

## 6.3 Recovering with the reflog

```sh
git reflog                          # HEAD's history: every checkout, commit, reset, rebase
git reflog show feature             # one branch's history
git log -g --oneline feature        # the same, as a log
git reflog --date=iso               # show when each move happened
```

Deleting a branch also deletes that branch's own reflog. HEAD's reflog still has every commit
you made or checked out on it, so search HEAD's reflog for deleted branches.

Recipes:

```sh
# undo the last reset, rebase, merge or amend
git reset --hard ORIG_HEAD          # ORIG_HEAD is set by these commands
git reset --hard HEAD@{1}           # or: where HEAD was one move ago

# restore a deleted branch (find its last commit in HEAD's reflog)
git reflog | grep 'moving from feature'
git branch feature <id>

# where was main before this morning?
git switch -c main-yesterday main@{yesterday}
```

`HEAD@{n}` counts moves of HEAD; `feature@{n}` counts moves of `feature`. After a rebase,
`feature@{1}` is the branch exactly as it was before the rebase, while `HEAD@{1}` is only the
last step inside the rebase. Prefer the branch's own reflog.

## 6.4 Recovering with `git fsck`

When no reflog mentions an object (stashes after `drop`, blobs that were only staged), search
the object store directly:

```sh
git fsck --no-reflogs --unreachable    # everything not reachable from refs
git fsck --lost-found                  # also writes them to .git/lost-found/{commit,other}/
```

Dropped stashes are dangling commits whose message starts with `WIP on` or `On <branch>`:

```sh
git fsck --no-reflogs --unreachable | awk '/commit/ {print $3}' \
  | xargs git log --no-walk --format='%h %cr %s' | grep -E 'WIP on|On '
git stash apply <id>                   # or: git branch rescued-stash <id>
```

Staged-but-never-committed content is a dangling blob. Blobs have no file names, so search by
content:

```sh
for b in $(git fsck --lost-found | awk '/blob/ {print $3}'); do
  git cat-file -p "$b" | grep -q 'some line I remember' && echo "$b"
done
git cat-file -p <blob> > recovered.txt
```

## 6.5 Garbage collection

`git gc` (run automatically by `git gc --auto` after many commands, or by
`git maintenance`, chapter 8) does housekeeping:

1. **Expires reflog entries.**
   * `gc.reflogExpire` (default 90 days): entries for commits that are still reachable.
   * `gc.reflogExpireUnreachable` (default 30 days): entries for commits that are no longer
     reachable from the ref. This is the window for recovering after `reset --hard` or a
     rebase.
2. **Packs loose objects and refs** (`git repack`, `git pack-refs`).
3. **Deals with unreachable objects.** Those newer than `gc.pruneExpire` (default 2 weeks)
   are kept; older ones are deleted. Since Git 2.43 they are stored in a **cruft pack**: a
   packfile plus an `.mtimes` file recording each object's age, instead of thousands of loose
   files. In the scratch repository, `gc` produced exactly that:

   ```
   pack-d6e0...cd.idx  pack-d6e0...cd.mtimes  pack-d6e0...cd.pack  pack-d6e0...cd.rev
   ```

   The cruft pack holds the pre-rebase commit `c1423c6` and its tree and blobs. In that lab the
   fixed `GIT_COMMITTER_DATE` also dated the reflog entries to January, so `gc` expired them
   all as older than 90 days. The commit survived only because the object itself was younger
   than the two-week prune window. With real dates, the reflog would have protected it and
   it would have stayed in the main pack.

So for an unreachable commit to be truly gone, its reflog entries must expire (30 days for
commits that are no longer reachable from that ref) **and** the object must be older than the
prune window (two weeks). In practice, a "lost" commit survives at least 30 days in a
repository with default settings.

Why the two-week grace period? Another git process might be writing objects that are not yet
referenced (a commit in progress, a concurrent fetch). Pruning them immediately would corrupt
that operation.

## 6.6 Deleting something for real

Sometimes you must make data unrecoverable locally (a committed secret, a huge accidental
file). After rewriting history to remove all references (chapter 5):

```sh
git reflog expire --expire=now --expire-unreachable=now --all
git gc --prune=now --aggressive
git count-objects -v                     # check size-pack dropped
```

Do this only when no other git process is running in the repository. This removes the data
from *this* repository only; every clone, fork and the hosting service still has it (5.7).

## 6.7 Corruption

Git verifies hashes on read, so corruption is detected, not silently propagated.

```sh
git fsck --full                          # verify every object and its connectivity
```

Typical findings and fixes:

| Symptom                                        | Likely cause                     | Fix                                    |
|------------------------------------------------|----------------------------------|----------------------------------------|
| `object file ... is empty`                     | Crash or full disk during write  | Delete the empty file; refetch the object from a clone |
| `missing blob/tree/commit`                     | Disk loss, botched manual edits, or a partial clone | Fetch from a remote that has it; copy from another clone |
| `bad object HEAD` / `fatal: not a git repository` | Damaged `HEAD`                | Rewrite `.git/HEAD` as `ref: refs/heads/main` |
| `index file corrupt`                           | Damaged `.git/index`             | `rm .git/index && git reset` (rebuilds it from HEAD; working tree untouched) |
| `Unable to create '....lock': File exists`     | A git process crashed            | Make sure none is running, then delete the `.lock` file |

To restore missing objects from a healthy clone, any of these work, because objects are
content-addressed and interchangeable between repositories:

```sh
git fetch <healthy-remote>                                 # usually enough
cd healthy && git cat-file -p <id> | (cd ../broken && git hash-object -w -t <type> --stdin)
cp healthy/.git/objects/pack/* broken/.git/objects/pack/   # brute force, also safe
```

Never "repair" a repository by editing objects by hand, and keep a copy
(`cp -a repo repo.bak`) before attempting any recovery.

**Cloud sync folders** (Dropbox, iCloud Drive, OneDrive) are a common cause of corruption:
they sync `.git` file by file, out of order, and can resurrect old lock and index files. Keep
repositories out of them; use a remote for backup.

## 6.8 Defensive settings

```sh
git config --global gc.reflogExpireUnreachable '90 days'  # longer safety window
git config --global core.fsync committed                  # fsync objects and refs (crash safety)
git config --global transfer.fsckObjects true             # verify objects you fetch and receive
```

## 6.9 In production

| Feature                          | When you'd reach for it                                            |
|----------------------------------|--------------------------------------------------------------------|
| Reflog                           | Recovering from a bad rebase, reset or force-push, usually within minutes of the mistake |
| `git fsck --full`                | After a disk failure or crash on a server; as a scheduled integrity check of backups and mirrors |
| `transfer.fsckObjects` / `receive.fsckObjects` | Self-hosted git servers: reject malformed or malicious objects at push time |
| `git bundle`                     | Offline or air-gapped transfer; a single-file backup; seeding CI caches or large clones |
| `git clone --mirror` on a schedule | Disaster recovery replica of every ref, independent of the hosting provider |
| `gc.auto=0`                      | Ephemeral CI clones: skip housekeeping that the throwaway clone will never benefit from |
| Expire + `gc --prune=now`        | After rewriting out a secret or large file, to remove it from a repository you control |
| `core.fsync`                     | Servers and laptops where a power loss must not corrupt the repository |

```sh
# backup: one file containing every ref and object
git bundle create backup-$(date +%F).bundle --all
git bundle verify backup-$(date +%F).bundle
git clone backup-2026-11-26.bundle restored            # restore anywhere, no network

# air-gapped / incremental: only what changed since the last transfer
git bundle create update.bundle last-sync..main
# on the other side: git fetch /media/usb/update.bundle main:main

# nightly mirror to an independent host, then verify
git clone --mirror git@github.com:acme/app.git app.git        # once
git -C app.git remote update --prune && git -C app.git fsck --full
git -C app.git push --mirror git@backup.example.com:acme/app.git

# CI job: no housekeeping in a clone that is deleted after the build
git config gc.auto 0
```

Large hosting services use the same mechanism to speed up clones: a **bundle URI**
(`git clone --bundle-uri=<url>`) lets the client download a pre-built bundle from a CDN, then
fetch only the recent remainder from the server.

## Exercises

1. Make a commit, `reset --hard HEAD~1`, and recover it three ways: `ORIG_HEAD`, `HEAD@{1}`,
   and `git fsck --no-reflogs --unreachable`.
2. Stash something, `git stash clear`, and recover it.
3. `git add` a file, overwrite it, and recover the staged version from a dangling blob.
4. Delete a branch, then run `gc`. Confirm with `git cat-file -t <id>` that the commit
   survives. Expire the reflogs and prune with `--prune=now` (6.6), and confirm it is gone.
5. In a copy of a repository, delete one object file under `.git/objects`, run
   `git fsck --full`, and restore it from the original.
