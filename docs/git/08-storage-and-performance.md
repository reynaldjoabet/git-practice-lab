# 8. Storage and performance at scale

Small repositories are fast however you use them. Past roughly a million commits, a hundred
thousand files or tens of gigabytes, you need to know how git stores data and which data
structures make each operation fast.

## 8.1 Loose objects and packfiles

New objects are written **loose**: one zlib-compressed file each. That is simple and safe for
concurrent writers, but wasteful: thousands of tiny files, and no compression between similar
versions of a file.

A **packfile** stores many objects in one file, with **delta compression**:

```
.git/objects/pack/pack-<hash>.pack   # the objects, many stored as deltas
.git/objects/pack/pack-<hash>.idx    # sorted index: object ID -> offset in the .pack
.git/objects/pack/pack-<hash>.rev    # reverse index: offset -> object (speeds up some operations)
.git/objects/pack/pack-<hash>.bitmap # optional reachability bitmaps (8.4)
.git/objects/pack/pack-<hash>.mtimes # cruft packs only: per-object ages (chapter 6)
```

`git verify-pack -v` lists a pack's contents: object ID, type, size, size in the pack,
offset, and for deltas the depth and base object:

```
dcbd9b37aaff577db227963895f03922ce7128cb commit 205 147 12
7e441454769a264787d7f3a98f6fbdb39d83595d commit 202 145 159
ce013625030ba8dba906f756967f9e9ca394464a blob   6 15 588
...
non delta: 11 objects
```

### How deltas are chosen

The packing algorithm (`git pack-objects`) sorts candidate objects by type, then by a hash of
their file name, then by size, and tries to express each object as a delta against one of
the previous `pack.window` objects (default 10), up to `pack.depth` (default 50) deltas deep.
Consequences:

* Deltas work across files and across commits. They are not "diffs between versions" in the
  version-control sense; any similar object can be a base.
* Usually the *newest* version of a file is stored whole and older versions as deltas, because
  recent objects are read most often.
* Already-compressed files (JPEG, ZIP, most binaries) barely delta. That is why large binaries
  bloat repositories.
* `git gc --aggressive` (larger window and depth) can shrink a repository noticeably, but it
  is slow; run it rarely, after big imports or history rewrites.

Packfiles are also the network format: fetch and push transfer one pack (chapter 7), so a fast
repack and a fast clone are the same problem.

## 8.2 Measuring a repository

```sh
git count-objects -vH                     # loose vs packed objects and sizes
git rev-list --count --all                # number of commits
git ls-files | wc -l                      # files in the index
git for-each-ref | wc -l                  # number of refs

# the largest blobs in all of history:
git rev-list --objects --all \
  | git cat-file --batch-check='%(objecttype) %(objectname) %(objectsize:disk) %(rest)' \
  | awk '$1 == "blob"' | sort -k3 -n | tail -20
```

[git-sizer](https://github.com/github/git-sizer) reports every dimension at once (largest
blob, deepest tree, most refs, biggest checkout) and flags the ones that cause trouble.

## 8.3 The commit-graph

Walking history normally means opening commit objects one at a time to find parents. The
**commit-graph** file (`.git/objects/info/commit-graph`, or a chain in
`.git/objects/info/commit-graphs/`) stores, for every commit: its tree, parents, date and a
**generation number** (its maximum distance from a root).

Generation numbers let git stop walking early. If commit X has a lower generation number than
Y, X cannot be a descendant of Y, so `merge-base`, `--contains`, `log --graph` and ahead/behind
counts skip most of the graph. On large repositories these commands get ten to a hundred
times faster.

The commit-graph can also hold **changed-path Bloom filters**: for each commit, a compact
probabilistic set of the paths it changed. `git log -- some/path` and `git blame` can then skip
commits that definitely did not touch the path without reading their trees.

```sh
git commit-graph write --reachable --changed-paths
git config --global fetch.writeCommitGraph true     # update it on every fetch
```

`git maintenance` (8.6) keeps it current for you.

## 8.4 Reachability bitmaps and the multi-pack index

To serve a clone or fetch, the server must find every object reachable from some tips and
missing on the client. Walking millions of commits and trees takes a long time.
**Reachability bitmaps** precompute, for selected commits, a bitmap with one bit per object in
the pack: bit set means "reachable from this commit". Answering "what is reachable from X but
not Y" becomes bitwise operations. Every large hosting service relies on them; on your own
server enable `repack.writeBitmaps` (the default for bare repositories).

A repository with many packs is slow because each lookup must search every `.idx`. The
**multi-pack-index** (`.git/objects/pack/multi-pack-index`) is one index over all packs.
Together with incremental repacking (combining small packs without rewriting big ones) it lets
huge repositories avoid expensive full repacks. `git multi-pack-index write --bitmap` adds
bitmaps across packs.

## 8.5 Making the working tree fast

`git status` cost grows with the number of files, because git must `lstat()` every tracked file
and scan directories for untracked ones. Three features remove most of that work:

| Feature                | Setting                        | What it skips                                  |
|------------------------|--------------------------------|------------------------------------------------|
| File-system monitor    | `core.fsmonitor=true`          | `lstat()` of unchanged files: a daemon (built in on macOS and Windows) tells git which paths changed since the last query |
| Untracked cache        | `core.untrackedCache=true`     | Re-scanning directories whose mtime has not changed |
| Index v4 / split index | `index.version=4`, `core.splitIndex=true` | Rewriting a huge index file on every change |
| Many-files preset      | `feature.manyFiles=true`       | Turns on index v4 and the untracked cache      |

```sh
git config core.fsmonitor true
git config core.untrackedCache true
git fsmonitor--daemon status
```

## 8.6 `git maintenance`

Instead of relying on `gc --auto` firing in the middle of your work, schedule background
maintenance:

```sh
git maintenance start          # registers the repository and installs a scheduler
                               # (launchd on macOS, cron/systemd on Linux, Task Scheduler
                               # on Windows)
git maintenance run --task=commit-graph   # run one task now
```

Scheduled tasks under the `incremental` strategy (check what your version configured with
`git config --get-regexp '^maintenance\.'`):

| Task                 | Frequency | Does                                                   |
|----------------------|-----------|--------------------------------------------------------|
| `prefetch`           | hourly    | Fetches into `refs/prefetch/*`, so your next `fetch` downloads almost nothing. Never touches your remote-tracking refs |
| `commit-graph`       | hourly    | Incrementally updates the commit-graph                 |
| `loose-objects`      | daily     | Packs loose objects                                    |
| `incremental-repack` | daily     | Combines small packs using the multi-pack-index        |
| `pack-refs`          | weekly    | Packs loose refs                                       |

Registering a repository also sets `maintenance.auto=false`, so the foreground `gc --auto`
does not compete with the scheduled tasks. `git maintenance unregister` undoes it.

## 8.7 Monorepos: download and check out only what you need

For a very large repository, combine:

1. **Blobless partial clone**: all history, file contents on demand.
2. **Sparse checkout in cone mode**: only the directories you work on are on disk.
3. **Sparse index**: the index stores directories outside the cone as single entries, so index
   size follows your cone, not the repository.
4. **fsmonitor + commit-graph + maintenance** as above.

```sh
git clone --filter=blob:none --sparse git@github.com:acme/monorepo.git
cd monorepo
git sparse-checkout set --cone services/billing libs/common
git config index.sparse true
git sparse-checkout add services/payments    # widen later
git sparse-checkout list
```

**Scalar**, shipped with git, applies all of this in one command:

```sh
scalar clone git@github.com:acme/monorepo.git   # blobless + sparse + fsmonitor + maintenance
scalar register                                 # apply the same settings to an existing clone
```

Cone mode matches whole directories (fast prefix matching). The older non-cone mode accepts
arbitrary gitignore-style patterns but is much slower at scale; avoid it.

## 8.8 Large files

| Option                     | How it works                                    | Use when                               |
|----------------------------|-------------------------------------------------|----------------------------------------|
| Git LFS                    | A `filter` (chapter 11) commits a small pointer file; the content lives on an LFS server and is downloaded at checkout | Binary assets you must version: design files, models, media |
| Partial clone              | Large blobs stay on the server until checked out | The repository is already large; clients use `--filter=blob:limit=1m` or `blob:none` |
| Keep them out of git       | Artifact registry, object storage, package manager | Build outputs, datasets, dependencies  |

Prevent the problem at the door: a pre-receive hook or a host's push rule that rejects files
over a size limit, and `.gitattributes` LFS rules set up *before* the first asset is committed.
Moving existing files into LFS (`git lfs migrate import`) rewrites history.

## 8.9 Diagnosing slowness

```sh
GIT_TRACE2_PERF=1 git status        # per-region timings: index read, refresh, untracked scan
GIT_TRACE_PERFORMANCE=1 git log -1  # overall timings (older trace format)
git status --untracked-files=no     # does the untracked scan dominate?
git config --show-origin --get-regexp 'core\.|feature\.|index\.'   # what is actually set
```

Usual culprits, in order: no fsmonitor and many files; huge untracked directories that are not
ignored (`node_modules`, build output); too many loose objects or packs (run maintenance); a
missing commit-graph; thousands of refs (prune remote-tracking refs, consider reftable); slow
filters or hooks (LFS, `pre-commit`).

## 8.10 In production

| Feature                          | When you'd reach for it                                            |
|----------------------------------|--------------------------------------------------------------------|
| `git maintenance start`          | Developer machines with large repositories; long-lived CI agents and build servers that keep a persistent clone |
| Commit-graph + Bloom filters     | Anything that walks history in a big repository: `log -- path`, `blame`, ahead/behind counts, CI jobs that compute changed files |
| Bitmaps + multi-pack-index       | Self-hosted servers (Gitea, GitLab, bare repositories over SSH) serving many clones; cuts CPU per clone dramatically |
| Blobless + sparse checkout       | Monorepo developers working in a few directories; CI jobs that build one service out of hundreds |
| Scalar                           | Onboarding developers onto a large monorepo with one command and the right settings |
| fsmonitor + untracked cache      | Large working trees on laptops. Skip them in short-lived containers and CI, where the daemon only adds overhead |
| Persistent CI clones / `--reference` | Build agents that would otherwise clone the same large repository on every job |
| Git LFS                          | Game assets, design files, ML model weights that must be versioned with the code. Datasets usually belong in object storage or a data-versioning tool instead |
| git-sizer                        | Before migrating hosts or adopting a monorepo; as a periodic repository health check |
| Changed-files detection          | Monorepo CI: build and test only what a PR touched                  |

```sh
# monorepo CI: check out only the service being built
git clone --filter=blob:none --no-checkout "$URL" src && cd src
git sparse-checkout set --cone services/billing libs/common
git checkout "$COMMIT"

# which top-level services did this PR touch?
git diff --name-only "$(git merge-base origin/main HEAD)" HEAD \
  | awk -F/ '$1 == "services" {print $2}' | sort -u

# build agent: share objects with a local cache clone instead of re-downloading
git clone --reference /var/cache/git/app.git --dissociate "$URL" work
```

`--dissociate` copies the borrowed objects into the new clone, so the job keeps working if the
cache is later pruned. Without it, the clone silently depends on the cache directory.

## Exercises

1. Run `git count-objects -vH`, then `git gc`, then again. Explain each number that changed.
2. Find the five largest blobs in a real repository you use, and which commits introduced them
   (`git log --all --find-object=<blob>`).
3. Time `git log --oneline -- <deep path>` before and after
   `git commit-graph write --reachable --changed-paths` on a large repository.
4. Make a blobless, sparse clone of a large open-source repository and compare its disk size
   and `git status` time with a full clone.
5. Run `GIT_TRACE2_PERF=1 git status` with and without `core.fsmonitor`.
