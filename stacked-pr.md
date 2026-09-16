# Stacked Pull Request

You're building a feature that touches a schema, an API layer, and a UI. Done normally, that's one branch and one PR with 2,000 lines. What happens:

- Review latency scales superlinearly with diff size. A 2,000-line PR doesn't take 4× as long to review as a 500-line one — it sits for days, then gets a rubber-stamp "LGTM."
- You're blocked. You can't start the next thing because it depends on unmerged code.
- One contested design decision in the UI layer holds hostage three uncontroversial commits in the schema layer.

Stacked PRs break the change into a chain of small PRs that each build on the previous one, all open simultaneously. Reviewers see focused diffs. You keep working at the top of the stack while the bottom is under review. Layers merge as they're approved.

```sh
frontend      → PR #3 (base: api-endpoints)  ← top, you're working here
api-endpoints → PR #2 (base: auth-layer)
auth-layer    → PR #1 (base: main)           ← bottom, under review
──────────────
main (trunk)
```

## 1.1 A branch is a pointer, not a container
A branch is a 40-byte file containing a commit SHA. `refs/heads/api-endpoints` → a1b2c3d. That's it.

## 1.2 A pull request is a diff between two refs
GitHub computes a PR's diff as:

- `git diff $(git merge-base base head)..head`
The `merge base` — the most recent common ancestor of `base `and `head` — is the key. GitHub shows you `merge-base..head`, not `base..head`. That's why a PR doesn't show unrelated commits that landed on `main` after you branched.

`This single fact is the entire trick of stacked PRs.` If PR #2's base is set to `auth-layer` rather than `main`, the merge base is the tip of `auth-layer`, and the `diff` shows only the API commits. The reviewer never sees the auth work — even though those commits are physically in the branch's ancestry.

Set the base wrong and your "stacked" PR shows a cumulative diff of everything below it. That's the #1 beginner failure.

## 1.3 History is immutable; rebase creates new commits
A commit's SHA is a hash of its content, message, author, timestamp, and its parent's SHA. Change any of those and you get a different commit. So rebasing commit `X` onto a new base doesn't move `X` — it creates a brand-new commit `X'` with identical content and a different SHA.

Now the crux:
```sh
main ──● 
        \
         ●───● auth-layer
              \
               ●───● api-endpoints
```               
You amend a commit in auth-layer (review feedback). The auth commits get new SHAs:

```sh
main ──● 
        \
         ●'──●' auth-layer   ← new SHAs
        \
         ●───● (orphaned)
              \
               ●───● api-endpoints   ← still points at the OLD auth commits
```               

`api-endpoints` is now built on commits that no branch names anymore. Its PR diff explodes: GitHub's merge base between `auth-layer` and `api-endpoints` is now way back at `main`, so PR #2 suddenly shows the auth changes plus the API changes — and shows the old auth version as being "added," which conflicts with the new one.

Every stacked-PR tool exists to solve this one problem: when a lower branch's history is rewritten, every branch above it must be replayed onto the new tip. That's the "cascade rebase."


## Beginner: your first stack, by hand
Do this once manually. Tools will hide it forever after, but you need to have seen it.

```sh
git checkout main && git pull

# Layer 1
git checkout -b auth-layer
# ... edit, git add, git commit ...
git push -u origin auth-layer
gh pr create --base main --head auth-layer --title "Add auth middleware"

# Layer 2 — branch from the TIP of layer 1, not from main
git checkout -b api-endpoints          # implicitly branches from auth-layer
# ... edit, commit ...
git push -u origin api-endpoints
gh pr create --base auth-layer --head api-endpoints --title "Add API endpoints"
#            ^^^^^^^^^^^^^^^^^ this is the part everyone forgets

# Layer 3
git checkout -b frontend
# ... edit, commit ...
git push -u origin frontend
gh pr create --base api-endpoints --head frontend --title "Add frontend"
```

Three PRs. Each shows only its own diff. Now the same thing with the tool in your working directory:

```sh
gh stack init auth-layer
# ... commit ...
gh stack add api-endpoints
# ... commit ...
gh stack add frontend
# ... commit ...
gh stack submit
```

`submit` pushes all three branches, opens all three PRs with correct bases, and registers a Stack object on GitHub that links them in the UI. 


### The add semantics worth knowing early
`gh stack add <branch> `creates the branch at current HEAD and checks it out — it does not touch the working tree. Uncommitted changes follow you into the new layer. Commit or stash first if you want a clean start 

The compressed form folds staging, committing, and branching into one:
```sh
gh stack add -Am "Add login endpoint"    # stage all + commit + auto-named branch
```
with one nice special case: if the current branch has no commits yet, `add -Am` commits on that branch rather than creating a new one. So `init` → `add -Am` → `add -Am` → `add -Am` builds a four-layer stack with no branch naming at all 

Convenient, but the project's own design guide argues against it for real work: use explicit `git add` so you control which hunks land in which layer 


