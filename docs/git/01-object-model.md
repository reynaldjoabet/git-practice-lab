# 1. The object model

Git's database lives in `.git/objects`. It stores four kinds of object, and every one of them
is immutable and named by the hash of its own contents.

| Type     | Holds                                                           | Points to            |
|----------|-----------------------------------------------------------------|----------------------|
| `blob`   | The bytes of one file. No name, no mode, no timestamp.          | nothing              |
| `tree`   | One directory: a sorted list of (mode, type, ID, name) entries. | blobs, trees, commits (submodules) |
| `commit` | One snapshot: a root tree, parent commits, author, committer, message. | one tree, 0..n commits |
| `tag`    | An annotated tag: target object, tagger, message, optional signature. | any one object |

## 1.1 How an object is named

An object's ID is the hash of a short header followed by its content:

```
<type> SP <size in bytes> NUL <content>
```

For a file containing `hello\n` (6 bytes):

```sh
$ printf 'hello\n' | git hash-object --stdin
ce013625030ba8dba906f756967f9e9ca394464a

$ printf 'blob 6\0hello\n' | shasum            # the same thing, by hand
ce013625030ba8dba906f756967f9e9ca394464a  -
```

On disk, a newly written ("loose") object is that same header-plus-content, compressed with
zlib, stored at `.git/objects/<first 2 hex digits>/<remaining 38>`:

```sh
$ python3 -c "import zlib; print(zlib.decompress(open(
    '.git/objects/ce/013625030ba8dba906f756967f9e9ca394464a','rb').read()))"
b'blob 6\x00hello\n'
```

Consequences worth internalising:

* **Identical content is stored once.** Two files with the same bytes, in any directory, in any
  commit, share one blob. Renaming a file writes no new blob.
* **IDs are verifiable.** Anyone can re-hash an object and check it. This is why a commit ID
  pins down the *entire* history behind it: the commit contains its tree's ID and its
  parents' IDs, and those contain theirs, all the way down (a Merkle DAG, the same structure as
  a blockchain or a Merkle tree).
* **Nothing can be edited.** Changing one byte in a file changes its blob ID, which changes the
  tree that lists it, which changes every parent tree up to the root, which changes the commit,
  which changes every descendant commit. This is why rebasing "rewrites" every commit after the
  change.

### SHA-1 and SHA-256

Repositories use SHA-1 by default. Since 2017 git has used a hardened SHA-1 implementation
(SHA-1DC) that detects the known collision attack and refuses colliding input. Git also
supports SHA-256 repositories:

```sh
git init --object-format=sha256
git rev-parse --show-object-format        # sha1 or sha256
```

Today a SHA-256 repository cannot exchange objects with a SHA-1 repository, and many hosting
services do not yet accept SHA-256 repositories, so check your host before you choose it.
The Git project plans to make SHA-256 the default in Git 3.0.

## 1.2 A concrete repository

Create a repository with one file at the top level and one in `src/`, then commit:

```sh
git init -b main
echo hello > greeting.txt
mkdir src && echo 'print(1)' > src/app.py
git add . && git commit -m 'first commit'
```

That single commit wrote five objects:

```sh
$ find .git/objects -type f | sort
.git/objects/48/abd5a1d441dd76e761059160abace1d9d594e6     # root tree
.git/objects/5f/6ed9bfb37c89a89c650f1d713723d459bfcdd9     # src/ tree
.git/objects/b9/17a726c93f902e43291d9009d6488385133b67     # blob: print(1)
.git/objects/ce/013625030ba8dba906f756967f9e9ca394464a     # blob: hello
.git/objects/f0/a4725ef374150233e02ed4371956c198651f7a     # the commit
```

And they form this graph:

```
┌──────────────────────────────────────────────────┐
│ commit f0a4725                                   │
│ tree      48abd5a                                │
│ author    Ada <ada@example.com> 1767268800 +0000 │
│ committer Ada <ada@example.com> 1767268800 +0000 │
│                                                  │
│ first commit                                     │
└─────────┬────────────────────────────────────────┘
          │
          ▼
┌──────────────────────────────────────┐
│ tree 48abd5a                         │
│ 100644 blob ce01362  greeting.txt    │
│ 040000 tree 5f6ed9b  src             │
└─────────┬─────────────────────┬──────┘
          │                     │
          ▼                     ▼
┌───────────────────┐   ┌─────────────────────────────┐
│ blob ce01362      │   │ tree 5f6ed9b                │
│ hello             │   │ 100644 blob b917a72  app.py │
└───────────────────┘   └───────┬─────────────────────┘
                                │
                                ▼
                        ┌───────────────────┐
                        │ blob b917a72      │
                        │ print(1)          │
                        └───────────────────┘
```

Read each object with `git cat-file -p`:

```sh
$ git cat-file -p HEAD
tree 48abd5a1d441dd76e761059160abace1d9d594e6
author Ada <ada@example.com> 1767268800 +0000
committer Ada <ada@example.com> 1767268800 +0000

first commit

$ git cat-file -p 'HEAD^{tree}'
100644 blob ce013625030ba8dba906f756967f9e9ca394464a    greeting.txt
040000 tree 5f6ed9bfb37c89a89c650f1d713723d459bfcdd9    src
```

## 1.3 Blobs

A blob is only bytes. It does not know its file name, its permissions or which commits use
it. That information lives in the tree that points at it. This is what makes git's
deduplication and rename handling work: a rename is just a tree that lists the same blob
under a new name.

Git does not store diffs. Each commit's tree refers to complete blobs. (Packfiles, chapter 8,
compress similar blobs against each other as deltas, but that is a storage detail, invisible
at the object level.)

## 1.4 Trees

A tree is one directory. Each entry has a mode, an object ID and a name. The only modes git
records are:

| Mode     | Meaning                                                         |
|----------|-----------------------------------------------------------------|
| `100644` | Regular file                                                    |
| `100755` | Executable file (git tracks only the executable bit, nothing else) |
| `120000` | Symbolic link; the blob holds the link target path              |
| `040000` | Subdirectory (a tree)                                           |
| `160000` | Submodule ("gitlink"); the ID is a *commit* in another repository |

Things trees cannot represent, and therefore git cannot track:

* **Empty directories.** A tree with no entries is never written for a subdirectory. The
  convention is to add a placeholder file such as `.gitkeep`.
* **Ownership, timestamps, other permission bits, extended attributes.** Checkout sets mtime
  to "now", which is what make-style build tools want.

Entries are sorted by name (with directories compared as if their name ended in `/`), so the
same directory always hashes to the same tree ID. Unchanged subdirectories are shared between
commits: a commit that touches one file in a 100,000-file repository writes one blob and only
the trees on the path from that file to the root.

## 1.5 Commits

A commit's body is plain text:

```
tree <root tree ID>
parent <ID>              # zero lines for a root commit, one normally, two+ for a merge
author <name> <email> <unix seconds> <tz offset>
committer <name> <email> <unix seconds> <tz offset>
gpgsig ...               # optional signature header
                         # blank line
<message>
```

* **Author vs committer.** The author wrote the change; the committer last created this
  commit object. They differ after `rebase`, `cherry-pick`, `am`, or when a maintainer
  applies your patch. `git log --format='%an %ad | %cn %cd'` shows both.
* **Parents define history.** There is no separate "history" structure. `git log` simply starts
  at a commit and walks parent links. A merge commit has two or more parents; the first parent
  is the branch you were on (this matters for `--first-parent`, chapter 9).
* **A commit is a snapshot, not a diff.** `git show` computes a diff on demand by comparing
  the commit's tree against its first parent's tree.
* **Dates are data, not order.** The commit timestamp is whatever the committer's clock said.
  Git never relies on dates for correctness; ancestry comes only from parent links.

## 1.6 Annotated tags

`git tag -a v1.0 -m 'release'` writes a `tag` object:

```
object <ID of the tagged object>
type commit
tag v1.0
tagger Ada <ada@example.com> 1767268800 +0000

release
```

and a ref `refs/tags/v1.0` pointing at the tag object. A lightweight tag (`git tag v1.0`
without `-a`, `-m` or `-s`) is only the ref, pointing straight at the commit.

Use annotated (or signed, `-s`) tags for releases: they record who tagged, when, and why;
`git describe` uses only annotated tags by default; and they can be signed. Peeling,
written `v1.0^{commit}`, follows tag objects until it reaches the commit.

## 1.7 Building a commit with plumbing only

Git's commands split into **porcelain** (user-facing: `add`, `commit`, `merge`) and
**plumbing** (stable, scriptable building blocks: `hash-object`, `update-index`,
`write-tree`, `commit-tree`, `update-ref`). Every porcelain command is a composition of
plumbing. Building a commit by hand proves the model:

```sh
git init -q demo && cd demo

blob=$(echo 'hello' | git hash-object -w --stdin)              # 1. write a blob
git update-index --add --cacheinfo 100644,"$blob",greeting.txt  # 2. stage it in the index
tree=$(git write-tree)                                          # 3. write the index as a tree
commit=$(echo 'first commit' | git commit-tree "$tree")         # 4. wrap the tree in a commit
git update-ref refs/heads/main "$commit"                        # 5. point a branch at it

git log --oneline main
git status --short  # " D greeting.txt": it is in the index and the commit, but was never
                    # written to disk. `git restore greeting.txt` writes it from the index.
```

That is all `git commit` does, plus hooks, message editing and a reflog entry. Steps 1–4 only
add objects. Step 5 is the only one that changes anything a user can see.

## 1.8 What this means in practice

* **History cannot be silently altered.** If you know a commit ID, you know the exact tree
  and history behind it. Signing a commit or tag signs all of it transitively.
* **"Rewriting history" really means writing new commits and moving a ref.** The originals
  remain until garbage collection (chapter 6).
* **Large binaries are expensive forever.** Every version of every file is a full object
  that every clone downloads. Delta compression helps text far more than compressed binary
  formats. Use Git LFS or partial clone (chapters 8, 11) for large assets, and decide before
  they reach history; removing them later means rewriting history.
* **Secrets are forever, too.** A committed secret exists in every clone and every fork. Rotate
  it first, then clean history.

## 1.9 In production

| Feature                          | When you'd reach for it                                            |
|----------------------------------|--------------------------------------------------------------------|
| Commit ID as a version           | Tag container images and artifacts with the commit, expose it on a `/version` endpoint, print it in logs. Any running system then maps to an exact source state |
| Tree IDs as change detectors     | Monorepo CI: if a directory's tree ID is unchanged, nothing inside it changed, so skip that service's build, test and deploy |
| Blob IDs as cache keys           | Key a dependency cache on the lock file's blob ID: cheap, exact and already computed |
| Commit timestamp as build time   | Reproducible builds: `SOURCE_DATE_EPOCH` from the commit, not the wall clock |
| Annotated / signed tags          | Release pipelines trigger on `v*` tags; `git describe` builds version strings from them; signatures prove who released |
| Plumbing (`commit-tree`, `update-ref`) | Bots that commit without a checkout: publishing docs to a `gh-pages` branch, GitOps tools bumping an image tag in a manifest repository |
| `git archive`                    | Source tarballs for a release, with no `.git` and honouring `export-ignore` |
| `cat-file --batch`               | Tools that read thousands of objects fast: code-search indexers, license scanners, migration scripts |

```sh
# image tag and build metadata
IMAGE_TAG=$(git rev-parse --short=12 HEAD)
export SOURCE_DATE_EPOCH=$(git log -1 --format=%ct)

# monorepo: rebuild billing only if its directory changed since the last deploy
[ "$(git rev-parse HEAD:services/billing)" = "$(git rev-parse deployed/prod:services/billing)" ] \
  || make -C services/billing deploy

# cache key for a dependency cache
CACHE_KEY="deps-$(git rev-parse HEAD:package-lock.json)"

# a release tarball
git archive --format=tar.gz --prefix=app-v1.4.1/ -o app-v1.4.1.tar.gz v1.4.1

# diff the first commit (it has no parent) against the empty tree
git diff --stat "$(git hash-object -t tree /dev/null)" <first-commit>
```

In the scratch repository, `git rev-parse HEAD:src` returns `5f6ed9b` in every commit, because
only `greeting.txt` ever changed. That one comparison is what monorepo build tools use to
skip unaffected services.

## Exercises

1. Predict the blob ID of a file containing `hi` and a newline, then verify with
   `git hash-object`.
2. Commit the same file under two different names. Count the objects that the commit added.
3. Change one file deep in a nested directory. Use `git cat-file -p` on both commits' trees to
   find exactly which trees changed.
4. Build a second commit, with the first as its parent, using only plumbing
   (`commit-tree -p`).
5. Make an annotated tag and a lightweight tag on the same commit. Compare
   `git cat-file -t` and `git rev-parse` for both, with and without `^{}`.
