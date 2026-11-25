# Git, end to end

A course in how git actually works, from the bytes on disk up to running it for a large team.
Each chapter builds on the one before it. Read them in order the first time. After that, use
them as a reference.

Every example was run against git 2.54. Commit IDs you see in examples are real output from a
scratch repository, so you can reproduce them: set `GIT_AUTHOR_DATE` and
`GIT_COMMITTER_DATE` to `2026-01-01T12:00:00Z`, name the author `Ada <ada@example.com>`, and
you get the same hashes.

## The one idea that explains everything

> Git is a content-addressed object store (a key-value database whose keys are hashes of
> the values), plus a small set of named pointers into it.

* **Objects** (blobs, trees, commits, tags) are immutable. Their name is the hash of their
  content. Nothing ever edits an object; you can only write new ones.
* **Refs** (branches, tags, `HEAD`, remote-tracking refs) are mutable names that point at
  objects. Almost every "scary" git operation (rebase, reset, amend, force-push) only moves
  refs and writes new objects. The old objects are still there until garbage collection.
* **The index** is a staging file that describes the *next* commit's tree.
* **The working tree** is an ordinary directory that git compares against the index.

Once you hold this model, commands stop being incantations: you can predict what each one
will do to the objects, the refs, the index and your files.

## Chapters

| #  | Chapter                                                        | You will be able to                                            |
|----|----------------------------------------------------------------|----------------------------------------------------------------|
| 1  | [The object model](01-object-model.md)                         | Build a commit by hand with plumbing; read any object          |
| 2  | [Refs, HEAD and branches](02-refs-and-head.md)                 | Explain what a branch is, fix a detached HEAD, read ref storage |
| 3  | [The index and the three trees](03-index-and-three-trees.md)   | Predict `add`, `commit`, `restore`, `reset` and `switch` exactly |
| 4  | [Merging](04-merging.md)                                       | Explain merge bases and three-way merge; resolve any conflict  |
| 5  | [Rebasing and rewriting history](05-rebasing-and-rewriting.md) | Rebase, split, squash and reorder safely; rebase stacks        |
| 6  | [Recovery and garbage collection](06-recovery.md)              | Recover "lost" commits, stashes and files; know what is truly gone |
| 7  | [Remotes and the transfer protocol](07-remotes-and-transfer.md) | Read refspecs; know what fetch and push send; push safely       |
| 8  | [Storage and performance at scale](08-storage-and-performance.md) | Tune packs, commit-graph, bitmaps, partial clone, sparse checkout |
| 9  | [Investigating history](09-investigating-history.md)           | Use revision ranges, pickaxe, blame, bisect and range-diff     |
| 10 | [Collaboration and workflow design](10-workflows.md)           | Choose a branching and merge policy; write commits that age well |
| 11 | [Configuration, attributes, hooks, submodules](11-config-attributes-hooks.md) | Configure git deliberately; extend it safely      |
| 12 | [Incident playbook](12-playbook.md)                            | Fix the most common "git broke" situations from memory      |
| 13 | [Worktrees](13-worktrees.md)                                   | Run several checkouts of one repository safely; use them for hotfixes, reviews, releases and CI |

## How to practise

Use a throwaway repository and look inside `.git` after every command. These are the tools you
will reach for most:

```sh
git cat-file -t <obj>            # type of an object
git cat-file -p <obj>            # pretty-print an object
git ls-files -s                  # the index: mode, blob, stage, path
git rev-parse <revision>         # resolve any revision expression to an object ID
git log --oneline --graph --all  # the whole commit graph
git reflog                       # where HEAD has been
find .git -type f | sort         # what is actually on disk
```

Chapters 1–11 each have an **"In production"** section just before the exercises. It lists when
you would reach for each feature in real systems (CI, deploys, releases, incidents, monorepos,
audits), with ready-to-adapt commands. Chapter 10's version gives reference setups for common
kinds of team.

Each chapter ends with exercises. Do them; reading about git builds far less skill than
breaking a repository and putting it back together.

## Levels

The chapters move through three levels:

* **Working engineer** (chapters 1–5): you never lose work, and you can explain what any
  command did.
* **Senior engineer** (chapters 6–9): you can recover from anything, find when and why any
  line changed, and keep large repositories fast.
* **Principal engineer** (chapters 10–12): you design the workflow, the guardrails and the
  repository layout that a whole organisation works in, and you are the person called when
  something goes wrong.
