# 9. Investigating history

History is only valuable if you can question it: *when* did this break, *who* changed this
line and *why*, *what* is in this release and not that one. This chapter is the toolkit.

## 9.1 Selecting commits: the revision language

Single commits (chapter 2): `HEAD~3`, `HEAD^2`, `main@{u}`, `v1.0^{commit}`, `:/message`.

**Ranges** select sets of commits by reachability:

| Expression          | Commits                                                       |
|---------------------|---------------------------------------------------------------|
| `B`                 | Everything reachable from B                                   |
| `^A B` or `A..B`    | Reachable from B, **not** reachable from A: "what B has that A lacks" |
| `A...B`             | Reachable from either, but not from both: the **symmetric difference** |
| `B --not A C`       | Reachable from B, not from A or C                             |
| `C^@`               | All parents of C                                              |
| `C^!`               | C alone (C, excluding its parents): use to show one commit as a range |
| `C^-`               | `C^..C`: the changes a merge brought in (with `^-2` for the second parent) |

Everyday questions:

```sh
git log main..feature             # what will this branch bring into main?
git log feature..main             # what has main gained since I branched (roughly)?
git log --oneline v1.4..v1.5      # what is in this release?
git log --left-right --oneline main...feature   # < main-only, > feature-only commits
git rev-list --count --left-right @{u}...HEAD   # behind/ahead counts, as in `git status`
git log origin/main..HEAD         # what will `git push` send?
```

**`..` and `...` mean different things for `diff`.** `git diff A..B` is simply `git diff A B`
(two snapshots). `git diff A...B` is `git diff $(git merge-base A B) B`: only the changes
made on B since it forked. **That is what a pull request shows**, and it is usually what you
want for review.

## 9.2 `git log`, seriously

### Shaping the output

```sh
git log --oneline --graph --decorate --all
git log --format='%h %ad %an %s' --date=short
git log --stat                     # files changed per commit
git log -p -- path/                # full patches touching a path
git log --first-parent main        # one entry per merge into main: the integration history
git log --merges / --no-merges
git log --author='Ada' --since='2 weeks ago' --until=yesterday
git log --grep='JIRA-123' -i       # search messages
```

`--first-parent` is the most underused flag. On a branch that receives work through merge
commits, it shows the sequence of integrations (one line per merged pull request) and skips
the internals of each topic branch.

### Searching changes: the pickaxe

| Option            | Finds commits where...                                          |
|-------------------|-----------------------------------------------------------------|
| `-S <string>`     | The **number of occurrences** of the string changed (added or removed, not moved) |
| `-G <regex>`      | A changed line (added or removed) matches the regex             |
| `-S <s> --pickaxe-regex` | `-S` with a regex                                        |
| `--find-object=<id>` | A specific blob or tree was introduced or removed            |

```sh
git log -S 'MAX_RETRIES' --oneline          # when was this constant introduced or deleted?
git log -G 'retry\(.*timeout' -p             # every change to lines calling retry with a timeout
```

### Following code

```sh
git log --follow -p -- src/new_name.py       # follow one file across renames
git log -L '/^def charge/,/^def /:billing.py' # the history of one function
git log -L 120,150:billing.py                # the history of a line range
```

`-L` shows how the selected lines evolved commit by commit, following them as surrounding
code moves. It is often a better tool than `blame` for "how did this function get like this".

## 9.3 `git blame`, properly

`git blame <file>` annotates each line with the last commit that changed it. Raw blame is often
misleading: the last change might be a reformat, a rename, or code moved from another file.

```sh
git blame -w file                     # ignore whitespace-only changes
git blame -M file                     # detect lines moved within the file
git blame -C -C -C file               # detect lines moved or copied from other files,
                                      # including in the commit that created the file
git blame -L 40,60 file               # only some lines
git blame <commit>^ -- file           # blame as of just before a commit: "peel" a layer
```

**Ignore mechanical commits.** Put the IDs of bulk-formatting and rename commits in a file and
tell blame to see through them:

```sh
# .git-blame-ignore-revs
# Reformat with black
3f2a9c1e...
git config blame.ignoreRevsFile .git-blame-ignore-revs
```

GitHub and GitLab honour `.git-blame-ignore-revs` too. Adding the commit to it whenever you run
a formatter over a codebase keeps blame useful.

Blame tells you *which commit*; the commit's message, linked issue and review tell you *why*.
That is the main argument for good commit messages (chapter 10).

## 9.4 `git bisect`: binary search for the commit that broke something

```sh
git bisect start
git bisect bad                 # current commit is broken
git bisect good v1.4           # this release was fine
# git checks out the midpoint; test it, then:
git bisect good                # or: git bisect bad, or: git bisect skip (can't test this one)
# ...repeat; about log2(N) steps: 1,000 commits take about 10 tests
git bisect reset               # return to where you started
```

**Automate it.** Given a script that exits 0 for good, 1–127 (except 125) for bad, and 125 for
"cannot test":

```sh
git bisect start HEAD v1.4
git bisect run ./scripts/repro.sh
```

The script can be a single failing test written *after* the fact, kept outside the repository
(so it exists at every commit being tested):

```sh
cp tests/test_regression.py /tmp/ && git bisect run pytest -q /tmp/test_regression.py
```

Practical points:

* `git bisect start --first-parent` only tests merge commits on the main line: find which
  pull request broke it, then bisect inside it.
* `git bisect start --term-old=fast --term-new=slow` for things that are not "bugs", such as
  a performance regression.
* `git bisect log` / `git bisect replay` save and replay a session.
* Bisect works only if most commits build and pass tests. That is a practical argument for
  keeping every commit on the main branch green (chapter 10).

## 9.5 `git range-diff`: comparing two versions of a branch

After a rebase or a round of review fixes, `git diff` between the old and new branch tips
mixes your changes with everything that came from upstream. `range-diff` compares the two
*series of commits* patch by patch:

```sh
git range-diff main feature@{1} feature      # before vs after my last rebase
git range-diff old-base..old-tip new-base..new-tip
```

Output pairs each old commit with its new version and shows a diff *of the diffs*. Reviewers
use it to see exactly what changed in a force-pushed pull request.

## 9.6 Other questions git can answer

```sh
git branch --contains <commit>        # which branches include this fix?
git tag --contains <commit>           # which releases include it?
git describe --tags <commit>          # nearest tag: v1.4-12-g3f2a9c1 (12 commits after v1.4)
git name-rev <commit>                 # a readable name relative to branches/tags
git merge-base --is-ancestor A B && echo "A is in B"
git cherry -v main feature            # feature commits with no equivalent patch in main (+)
git shortlog -sne v1.4..v1.5          # who contributed to a release
git log --diff-filter=D --name-only   # commits that deleted files (A, M, R, D...)
git log --all -- path/that/is/gone    # history of a deleted file
git show <commit>:<path>              # a file as it was in a commit
git grep -n 'pattern' v1.4            # search a whole tree at any revision
git diff --stat --dirstat=files v1.4 v1.5     # where did change concentrate?
git log --format= --name-only --since=6.months | sort | uniq -c | sort -rn | head
                                      # most frequently changed files ("hotspots")
```

`git notes add -m 'deployed to prod 2026-03-02' <commit>` attaches metadata to a commit without
changing its ID. Notes live in `refs/notes/commits` and are not fetched by default.

## 9.7 Diff options worth knowing

```sh
git diff --word-diff                     # changes within lines, for prose and config
git diff --color-moved=zebra             # highlight moved blocks separately from edits
git diff -M -C                           # detect renames and copies
git diff --diff-algorithm=histogram      # usually clearer hunks for code
git config --global diff.algorithm histogram
git config --global diff.colorMoved zebra
git diff --check                         # whitespace errors (trailing spaces, conflict markers)
git diff --name-status A B               # just the list of changed paths and their status
```

## 9.8 In production

| Feature                          | When you'd reach for it                                            |
|----------------------------------|--------------------------------------------------------------------|
| `log --first-parent A..B`        | Release notes and deploy summaries: one line per merged PR between two releases or deploys |
| `git bisect run`                 | Finding the commit behind a regression, a performance drop or a newly flaky test, often during an incident or postmortem |
| `bisect --first-parent`          | First find which merged PR caused it, then bisect inside that PR    |
| `tag --contains`, `describe --contains` | Incident triage: "is the fix in the version that is deployed?" |
| `git grep <pattern> <tag>`       | Security (CVE) triage: does a vulnerable function or dependency version exist in each released version? |
| Pickaxe `-S` / `-G`              | Audits: when was this endpoint, permission check or feature flag added or removed, and by whom |
| `log -L`                         | Understanding how a critical function evolved before changing it    |
| `blame` + ignore-revs            | Finding the change, PR and ticket behind a line, past formatting commits |
| `range-diff`                     | Reviewing a force-pushed PR; checking that a backport matches the original series |
| `describe --tags --always --dirty` | Build version strings: `v1.4.1-12-g3f2a9c1-dirty`                 |
| `shortlog`, hotspots             | Release credits; finding the files that change most often (refactoring and test priorities, ownership gaps) |
| `notes`                          | Attach deploy records or CI results to commits without changing them |
| `log --format` + signatures      | Compliance evidence: every change to production with author, reviewer trailers and signature status |

```sh
# release notes: merged PRs between two releases
git log --first-parent --format='- %s' v1.4.0..v1.5.0

# is the fix deployed? (compare with the deployed version)
git merge-base --is-ancestor <fix-commit> v1.4.1 && echo 'v1.4.1 contains the fix'

# CVE triage across every release
for t in $(git tag -l 'v1.*' --sort=version:refname); do
  git grep -q 'unsafe_yaml_load' "$t" -- 'src/' && echo "$t is affected"
done

# regression hunt between the last good and first bad release
git bisect start v1.5.0 v1.4.0 && git bisect run ./scripts/repro.sh

# was the backport faithful?
git range-diff main~3..main release/1.4~3..release/1.4    # last 3 commits on each

# audit: every commit that reached main last quarter, with signature status
git log --first-parent main --since=2026-07-01 --until=2026-10-01 \
  --format='%h %G? %an %ad %s' --date=short
```

`%G?` prints `G` for a good signature, `N` for none, and other letters for bad, expired or
unknown keys. That makes unsigned changes easy to list for an auditor.

## Exercises

1. In a repository with merges, compare `git log --oneline main` with
   `git log --oneline --first-parent main`.
2. Use `git log -S` to find the commit that introduced a constant, then `git log -L` on the
   function that uses it.
3. Introduce a bug ten commits back in a scratch repository and find it with `git bisect run`.
4. Rebase a branch with review fixes, then show the reviewer exactly what changed with
   `git range-diff`.
5. Run a formatter over a file in one commit, add that commit to `.git-blame-ignore-revs`, and
   compare `git blame` with and without it.
