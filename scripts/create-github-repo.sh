#!/usr/bin/env bash
# Compatible with macOS Bash 3.2. Creates a new private github.com repository.
set -euo pipefail

die() { printf '%s\n' "$*" >&2; exit 1; }

[[ $# -le 1 ]] || die 'Usage: bash scripts/create-github-repo.sh [repository-name]'
repo_name="${1:-deterministic-vliw-chisel}"
[[ "$repo_name" =~ ^[A-Za-z0-9][A-Za-z0-9._-]*$ ]] || die 'Repository name must contain only letters, digits, dot, underscore, or hyphen.'
[[ "$repo_name" != *.git ]] || die 'Choose a repository name without the .git suffix.'
[[ ${#repo_name} -le 100 ]] || die 'Repository name must be at most 100 characters.'

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd -P)"
cd "$repo_root"
command -v git >/dev/null 2>&1 || die 'Git is required. Install the Xcode command-line tools.'
command -v gh >/dev/null 2>&1 || die 'GitHub CLI is required. With Homebrew: brew install gh'

# Only a new folder or an empty repo initialized for local user.name/email is allowed.
if git rev-parse --show-toplevel >/dev/null 2>&1; then
  git_root="$(git rev-parse --show-toplevel)"
  git_root="$(cd "$git_root" && pwd -P)"
  [[ "$git_root" == "$repo_root" ]] || die 'This folder is inside another Git repository. Move it outside that repository.'
  if git rev-parse --verify HEAD >/dev/null 2>&1; then
    die 'This repository already has a commit. Follow docs/SETUP_MAC.md for a partial-failure recovery.'
  fi
  [[ -z "$(git remote)" ]] || die 'This repository already has a remote. No existing remote will be changed.'
  [[ -z "$(git ls-files)" ]] || die 'This repository already has staged files. Use a fresh copy of the package.'
fi

git config --get user.name >/dev/null || die 'Set Git user.name first; see docs/SETUP_MAC.md.'
git config --get user.email >/dev/null || die 'Set Git user.email first; see docs/SETUP_MAC.md.'
gh auth status --hostname github.com >/dev/null 2>&1 || die 'Authenticate first: gh auth login --hostname github.com'
repo_owner="$(gh api --hostname github.com user --jq .login)"
[[ "$repo_owner" =~ ^[A-Za-z0-9-]+$ ]] || die 'Could not determine the authenticated GitHub account.'
repo_full_name="$repo_owner/$repo_name"

# A failed existence check may also mean a network error. Creation below is
# authoritative; it never adopts an existing repository after such a failure.
if GH_HOST=github.com gh repo view "$repo_full_name" --json nameWithOwner >/dev/null 2>&1; then
  die "GitHub repository already exists: $repo_full_name. Choose another name."
fi

files=(
  README.md .gitignore
  docs/DESIGN.md docs/ISA.md docs/ROADMAP.md docs/SETUP_MAC.md docs/REFERENCES.md
  config/machine.json hw/README.md compiler/README.md sim/README.md benchmarks/README.md
  scripts/create-github-repo.sh
)
for file in "${files[@]}"; do
  [[ -f "$file" && ! -L "$file" ]] || die "Required regular file is missing: $file"
done

printf 'Creating private repository: %s\nLocal path: %s\n' "$repo_full_name" "$repo_root"
git init -b main
git symbolic-ref HEAD refs/heads/main
git add -- "${files[@]}"
git diff --cached --check
git commit -m 'docs: define deterministic VLIW architecture and development plan'

# Use HTTPS and gh credentials for this invocation only. No global Git/gh
# configuration is changed and no tokens are printed or written to the repo.
export GH_HOST=github.com
export GIT_CONFIG_COUNT=3
export GIT_CONFIG_KEY_0='credential.helper'
export GIT_CONFIG_VALUE_0=''
export GIT_CONFIG_KEY_1='credential.https://github.com.helper'
export GIT_CONFIG_VALUE_1='!gh auth git-credential'
export GIT_CONFIG_KEY_2='url.https://github.com/.insteadOf'
export GIT_CONFIG_VALUE_2='git@github.com:'

if ! gh repo create "$repo_full_name" --private --source=. --remote=origin --push \
  --description 'Compiler-scheduled, fixed-cycle VLIW microengine in Chisel'; then
  die 'GitHub creation or push failed. Local files and commit were retained. Follow docs/SETUP_MAC.md; do not rerun this initial-creation script.'
fi

local_commit="$(git rev-parse HEAD)"
remote_commit="$(git ls-remote origin refs/heads/main | cut -f1)"
[[ "$remote_commit" == "$local_commit" ]] || die 'Remote main could not be verified against the local commit. Inspect the repository before retrying.'
repo_private="$(gh repo view "$repo_full_name" --json isPrivate --jq .isPrivate)"
[[ "$repo_private" == true ]] || die 'Repository privacy verification failed. Inspect GitHub repository settings.'
repo_url="$(gh repo view "$repo_full_name" --json url --jq .url)"
printf '\nCreated and verified: %s\nCommit: %s\n' "$repo_url" "$local_commit"
