#!/usr/bin/env bash
set -euo pipefail

# Enforces the DCO rule ADR 0012 and CONTRIBUTING.md's "License" section already state --
# every commit is certified by the Developer Certificate of Origin (git commit -s) -- which
# was written down in both places and checked in neither. One of the four outside
# contributions merged so far (b0d94b2, PR #79) carries no Signed-off-by trailer at all, and
# nothing at merge time caught it. This is that check, run by build.yml on every pull
# request; see check-dco.test.sh for the assertions against it.
#
# Usage: check-dco.sh <range>
#   <range>   any git revision range `git log` accepts -- a pull request's own
#             "<base sha>..<head sha>", or "origin/main..HEAD" to check a branch locally.
#
# The rule, and it has a deliberate edge:
#   - Merge commits are skipped (--no-merges). GitHub authors them; they certify nothing and
#     cannot carry a sign-off of their own.
#   - Every remaining commit must carry at least one Signed-off-by trailer, and one of those
#     trailers must match the commit's own author -- by email, or failing that by name,
#     case-insensitively.
#   - Matching on name as well as email is deliberate, not laxity: it is the cost of the door
#     ADR 0012 says this project's whole contribution model depends on keeping open. A
#     contributor who authors commits with a GitHub noreply address and signs off with their
#     real name and email is doing exactly what the DCO asks; a check that demanded the two
#     addresses match exactly would reject them for a reason that has nothing to do with
#     whether they certified their own commit. Rejecting them here would be friction placed
#     exactly where docs/positioning.md says this project cannot afford it.

range="${1:?usage: check-dco.sh <range>}"

lower() {
  tr '[:upper:]' '[:lower:]' <<< "$1"
}

failures=0

while IFS= read -r sha; do
  [ -z "$sha" ] && continue

  short="$(git rev-parse --short "$sha")"
  subject="$(git show -s --format='%s' "$sha")"
  author_name="$(git show -s --format='%an' "$sha")"
  author_email="$(git show -s --format='%ae' "$sha")"
  body="$(git show -s --format='%B' "$sha")"

  trailers="$(grep -iE '^Signed-off-by:' <<< "$body" || true)"

  fix_hint="fix with: git commit --amend -s (if it is your last commit), or git rebase --signoff <base> (for several)"

  if [ -z "$trailers" ]; then
    echo "::error::${short} \"${subject}\" has no Signed-off-by trailer -- ${fix_hint}"
    failures=$((failures + 1))
    continue
  fi

  matched=false
  while IFS= read -r trailer; do
    value="${trailer#*:}"
    value="$(sed -e 's/^[[:space:]]*//' -e 's/[[:space:]]*$//' <<< "$value")"
    trailer_email="$(sed -n 's/.*<\(.*\)>.*/\1/p' <<< "$value")"
    trailer_name="$(sed -e 's/<.*>//' <<< "$value" | sed -e 's/[[:space:]]*$//')"

    if [ -n "$trailer_email" ] && [ "$(lower "$trailer_email")" = "$(lower "$author_email")" ]; then
      matched=true
      break
    fi
    if [ -n "$trailer_name" ] && [ "$(lower "$trailer_name")" = "$(lower "$author_name")" ]; then
      matched=true
      break
    fi
  done <<< "$trailers"

  if [ "$matched" = false ]; then
    echo "::error::${short} \"${subject}\" carries a Signed-off-by trailer that matches neither the commit author's name (${author_name}) nor email (${author_email}) -- ${fix_hint}"
    failures=$((failures + 1))
  fi
done < <(git log --no-merges --format='%H' "$range")

if [ "$failures" -gt 0 ]; then
  echo "${failures} commit(s) failed the DCO check"
  exit 1
fi
echo "all commits in ${range} carry a Signed-off-by trailer matching their author"
