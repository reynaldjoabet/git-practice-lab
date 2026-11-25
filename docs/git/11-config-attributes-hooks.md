# 11. Configuration, attributes, hooks and submodules

Git is highly configurable, and most of the defaults are tuned for backward compatibility rather
than for how you should work today. This chapter explains how configuration is resolved, then
gives a reasoned baseline, then covers the extension points.

## 11.1 Where configuration comes from

From lowest to highest precedence (later wins):

| Scope       | File                                    | Set with                     |
|-------------|-----------------------------------------|------------------------------|
| system      | `$(prefix)/etc/gitconfig`               | `git config --system`        |
| global      | `~/.gitconfig` or `~/.config/git/config` | `git config --global`       |
| local       | `.git/config`                           | `git config` (default)       |
| worktree    | `.git/worktrees/<name>/config.worktree` | `git config --worktree`      |
| command     | `git -c key=value ...`, `GIT_CONFIG_*` env vars | per invocation       |

```sh
git config --list --show-origin --show-scope   # every value and where it came from
git config --get-all remote.origin.fetch       # multi-valued keys
git config --global --edit
```

**Conditional includes** let one machine hold several identities:

```ini
# ~/.gitconfig
[includeIf "gitdir:~/work/"]
    path = ~/.config/git/work.gitconfig       # work email, signing key
[includeIf "hasconfig:remote.*.url:git@github.com:acme/**"]
    path = ~/.config/git/acme.gitconfig
```

Repository-local `.git/config` is never cloned. Settings a team must share (hooks, attributes,
blame ignore lists) go into tracked files, and each developer opts in once, or a setup script
does it.

## 11.2 A reasoned baseline

```ini
[init]
    defaultBranch = main
[pull]
    ff = only                    # never create surprise merges (chapter 7)
[push]
    autoSetupRemote = true       # first push sets the upstream
    followTags = true
[fetch]
    prune = true                 # drop remote-tracking refs for deleted branches
[rebase]
    autoSquash = true            # chapter 5
    autoStash = true             # stash, rebase, unstash
    updateRefs = true            # move stacked branches too
[merge]
    conflictStyle = zdiff3       # show the base in conflicts (chapter 4)
[rerere]
    enabled = true
    autoUpdate = true
[diff]
    algorithm = histogram
    colorMoved = zebra
    renames = copies
[commit]
    verbose = true               # show the diff in the commit message editor
[branch]
    sort = -committerdate        # most recent branches first
[tag]
    sort = version:refname       # v1.10 after v1.9
[help]
    autocorrect = prompt
[transfer]
    fsckObjects = true           # verify objects received
[alias]
    lg = log --graph --format='%C(auto)%h%d %s %C(dim)%an, %ar'
    pushf = push --force-with-lease --force-if-includes
    st = status -sb
    undo = reset --soft HEAD~1
```

Every line here is explained in the chapter listed. Do not paste settings you cannot explain.

## 11.3 Ignoring files

| File                          | Scope                           | Use for                           |
|-------------------------------|---------------------------------|-----------------------------------|
| `.gitignore` (tracked)        | Everyone, this repository       | Build output, dependencies        |
| `.git/info/exclude`           | You, this repository            | Your scratch files                |
| `core.excludesFile` (default `~/.config/git/ignore`) | You, all repositories | `.DS_Store`, editor files |

Rules: patterns are relative to the `.gitignore`'s directory; a trailing `/` matches only
directories; a leading `/` anchors to that directory; `**` matches across directories; `!`
re-includes, but **cannot re-include a file whose parent directory is excluded**. Ignore rules
only affect untracked files: to stop tracking a file, `git rm --cached <file>` and ignore it.

```sh
git check-ignore -v path/to/file     # which rule ignores this, from which file and line
git status --ignored
git clean -ndX                       # dry run: remove only ignored files
```

## 11.4 `.gitattributes`

Attributes assign behaviour to paths. They are tracked, so the whole team gets them.

```gitattributes
# Line endings: store LF in the repository, check out natively
*               text=auto
*.sh            text eol=lf
*.bat           text eol=crlf
*.png           binary          # shorthand for -text -diff -merge

# Diffs that understand the language (function names in hunk headers)
*.py            diff=python
*.go            diff=golang
*.md            diff=markdown

# Generated and vendored files: collapsed in GitHub diffs, excluded from language stats
package-lock.json linguist-generated=true
vendor/**       linguist-vendored=true

# Merge behaviour
CHANGELOG.md    merge=union     # keep lines from both sides (append-only files only!)

# Large files through Git LFS
*.psd           filter=lfs diff=lfs merge=lfs -text

# Excluded from `git archive` release tarballs
/tests/         export-ignore
```

How the main attribute types work:

* **`text` / `eol`**: line-ending normalisation between the index (LF) and working tree.
  After changing these rules, run `git add --renormalize .` and commit.
* **`diff=<driver>`**: built-in drivers give better hunk headers. Custom drivers can convert
  binary files to text for diffing: `diff.<name>.textconv = <command>` (e.g. `exiftool` for
  images, `pdftotext` for PDFs).
* **`merge=<driver>`**: `union`, `binary`, or a custom `merge.<name>.driver` command.
* **`filter=<name>`**: `filter.<name>.clean` runs on `git add` (working tree → blob) and
  `filter.<name>.smudge` on checkout (blob → working tree). Git LFS is a filter that
  replaces content with a pointer file. Filters are powerful and dangerous: a non-deterministic
  clean filter makes files appear permanently modified.

```sh
git check-attr -a path/to/file      # every attribute that applies
```

## 11.5 Hooks

Hooks are executables in `.git/hooks/` (or in the directory named by `core.hooksPath`) that git
runs at fixed points. A non-zero exit from a `pre-` hook aborts the operation.

| Hook                  | Runs                                    | Typical use                          |
|-----------------------|-----------------------------------------|--------------------------------------|
| `pre-commit`          | Before the commit message is requested  | Formatters, linters, secret scanners on staged files |
| `prepare-commit-msg`  | Before the editor opens                 | Insert a ticket ID from the branch name |
| `commit-msg`          | After the message is written            | Enforce message conventions          |
| `post-commit`         | After the commit                        | Notifications                        |
| `pre-rebase`          | Before a rebase starts                  | Refuse to rebase published branches  |
| `post-checkout`, `post-merge` | After switching or merging      | Reinstall dependencies when lock files changed |
| `pre-push`            | Before objects are sent                 | Fast tests, block pushing to protected branches |
| `pre-receive`, `update`, `post-receive` | On the server, during a push (chapter 7) | Policy enforcement, CI triggers, deploys |
| `reference-transaction` | Every ref update                      | Auditing, replication                |

Key facts:

* **Hooks are not cloned.** `.git/hooks` is local. Share hooks by tracking them in the
  repository (`.githooks/`) and running `git config core.hooksPath .githooks`, or with a hook
  manager such as [pre-commit](https://pre-commit.com), Husky or lefthook.
* **Hooks are advisory.** `git commit --no-verify` and `git push --no-verify` skip them. Anything
  that must hold (tests pass, message format, no secrets) must also be checked in CI or by
  server-side rules.
* **Keep client hooks fast** (under a second or two for `pre-commit`), and only check staged
  changes: `git diff --cached --name-only --diff-filter=ACM`. Slow hooks train people to use
  `--no-verify`.
* **Hooks run with your privileges.** A repository's hooks never run until you put them in
  place yourself, which is why cloning does not install them. Review hook code from others as
  you would any script.

A minimal `commit-msg` hook:

```sh
#!/bin/sh
# .githooks/commit-msg: require "area: summary" subjects of at most 72 characters
subject=$(head -n1 "$1")
if ! printf '%s\n' "$subject" | grep -Eq '^[a-z0-9/-]+: [^ ].*'; then
  echo "commit-msg: subject must look like 'area: summary'" >&2
  exit 1
fi
if [ "${#subject}" -gt 72 ]; then
  echo "commit-msg: subject is ${#subject} characters; keep it to 72" >&2
  exit 1
fi
```

## 11.6 Submodules

A submodule pins another repository at an exact commit inside yours. The parent stores:

* a **gitlink** tree entry (mode `160000`) with the submodule's commit ID, and
* a `.gitmodules` file with its path and URL.

```sh
git submodule add git@github.com:acme/proto.git libs/proto
git clone --recurse-submodules <url>          # or after cloning:
git submodule update --init --recursive
git submodule update --remote libs/proto      # move to the latest commit of its branch
git config --global submodule.recurse true    # make checkout, pull etc. update submodules
git diff --submodule=log                      # show submodule changes as commit lists
```

Why submodules frustrate teams:

* The submodule is checked out at a **detached HEAD** at the pinned commit. Work done inside it
  without creating a branch is easy to lose.
* Updating requires **two commits**: one in the submodule, pushed first, and one in the parent
  that moves the gitlink. Pushing the parent first leaves everyone else with a reference to a
  commit they cannot fetch (`git push --recurse-submodules=check` prevents this).
* Every clone, CI job and branch switch has an extra step that people forget.

Alternatives, in order of preference for most teams: a **package manager** (publish the shared
code as a versioned library); a **monorepo**; **`git subtree`** (copies the other project's
history into a subdirectory, so clones need nothing special). Use submodules when you
genuinely need to pin an external repository at exact commits and the team accepts the
workflow.

## 11.7 Environment variables worth knowing

| Variable                                   | Use                                              |
|--------------------------------------------|--------------------------------------------------|
| `GIT_DIR`, `GIT_WORK_TREE`                 | Operate on a repository from elsewhere (scripts, bare-repo dotfiles) |
| `GIT_AUTHOR_NAME/EMAIL/DATE`, `GIT_COMMITTER_*` | Override identity and dates (reproducible examples, imports) |
| `GIT_TRACE`, `GIT_TRACE2_PERF`, `GIT_TRACE_PACKET`, `GIT_TRACE_CURL` | Debug what git is doing |
| `GIT_SSH_COMMAND`                          | Choose a key or debug SSH: `ssh -i ~/.ssh/deploy -v` |
| `GIT_EDITOR`, `GIT_SEQUENCE_EDITOR`        | Non-interactive editors in scripts (e.g. `GIT_EDITOR=true`) |
| `GIT_TERMINAL_PROMPT=0`                    | Fail instead of prompting for credentials in CI  |

## 11.8 Security settings

* **`safe.directory`.** Git refuses to operate in a repository owned by another user
  ("detected dubious ownership"), because that repository's config could run arbitrary commands
  (hooks, `core.fsmonitor`, filters). Add an exception only for directories you trust:
  `git config --global --add safe.directory /srv/app`.
* **Untrusted repositories.** Cloning is safe; *running git commands inside* a repository
  someone else prepared (for example a downloaded tarball containing `.git/`) can execute its
  config. Inspect `.git/config` and hooks first.
* **`protocol.file.allow=user`** (the default since 2.38.1) stops submodules from silently
  cloning local paths.
* Keep git updated: security releases fix real remote-code-execution bugs, often in clone and
  submodule handling.

## 11.9 In production

| Feature                          | When you'd reach for it                                            |
|----------------------------------|--------------------------------------------------------------------|
| `includeIf`                      | One laptop, several identities: work and personal emails, a separate signing key per client for consultants |
| `git -c` / `GIT_CONFIG_*`        | CI and scripts that need a setting for one command without changing any config file |
| `eol=lf` for scripts             | Teams with Windows developers: a CRLF shell script fails inside Linux containers with `bad interpreter` |
| `linguist-generated`             | Collapse generated code and lock files in PR diffs so reviewers focus on real changes |
| `diff=<driver>` with `textconv`  | Reviewing changes to binary-ish files: PDFs, images (metadata), Office documents, encrypted secrets (e.g. `sops`) |
| `export-ignore`                  | Release tarballs from `git archive` without tests, CI config and internal docs |
| `merge=union`                    | Append-only files edited by many branches at once (changelog fragments); never for code or structured files |
| `pre-commit` hooks               | Formatters, linters and secret scanners (gitleaks, trufflehog) that catch problems before they leave the laptop |
| `commit-msg` hooks               | Enforce ticket IDs or Conventional Commits locally; CI enforces the same rule for real |
| `post-checkout` / `post-merge`   | Reinstall dependencies or run migrations when a lock file or schema changed |
| Server `pre-receive`             | Self-hosted and enterprise servers: block large files, secrets, unsigned commits, pushes to protected branches |
| Submodules                       | Pinning C/C++ SDKs, firmware or shared protocol definitions where a package manager is not an option |
| `GIT_TERMINAL_PROMPT=0`          | CI: fail immediately on a missing credential instead of hanging until the job times out |
| `safe.directory`                 | CI containers that run as a different user than the one owning the mounted checkout ("dubious ownership") |

```sh
# CI: a setting for one command only, nothing persisted
git -c protocol.version=2 -c advice.detachedHead=false checkout "$COMMIT"

# CI containers: trust exactly the workspace, not everything
git config --global --add safe.directory "$CI_PROJECT_DIR"

# CI: never hang on a credential prompt
export GIT_TERMINAL_PROMPT=0

# review diffs of encrypted secrets files in plain text (sops)
git config diff.sopsdiffer.textconv 'sops --decrypt'
echo 'secrets/*.yaml diff=sopsdiffer' >> .gitattributes

# run a dependency install after checkout or merge only when the lock file changed
# .githooks/post-merge
git diff --quiet ORIG_HEAD HEAD -- package-lock.json || npm ci
```

Avoid `safe.directory '*'`: it disables the protection for every repository on the machine,
including ones an attacker could prepare.

## Exercises

1. Run `git config --list --show-origin --show-scope` and explain every entry.
2. Set up `includeIf` so repositories under `~/work` use a different email, and verify with
   `git config user.email` in each.
3. Add `*.md diff=markdown` and compare hunk headers in `git diff` before and after.
4. Write a `pre-commit` hook that blocks commits containing `console.log` or `breakpoint()` in
   staged files, share it via `core.hooksPath`, and bypass it once with `--no-verify`.
5. Add a submodule, change it, commit in both repositories, and clone the parent fresh with
   `--recurse-submodules`.
