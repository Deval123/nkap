#!/usr/bin/env bash
set -euo pipefail

# Exercises check-release-tag.sh directly -- no GitHub Actions context, no tag push, no
# release run -- against small checkouts built in a temporary directory, so the rule issue
# #270 asks for (a release tag must match the POM and the changelog of the commit it tags) and
# the one issue #277 adds (and be annotated) are assertions this repository can fail on, not
# only a reading of release.yml's YAML. Run by build.yml on every push and pull request.
#
# Each checkout is a real git repository with a real origin, built the way actions/checkout
# builds one on a tag push -- observed, not assumed (see the script's header): a shallow fetch
# of "+<sha>:refs/tags/<tag>", which leaves the local tag pointing at the commit whatever the
# pushed tag was. The test checks that this setup reproduces that (the "checkout leaves"
# assertions below), because a test whose checkout kept the tag object would pass a script that
# never fetched it.

cd "$(dirname "${BASH_SOURCE[0]}")"
script="$(pwd)/check-release-tag.sh"
repo_root="$(cd ../.. && pwd)"

scratch="$(mktemp -d)"
trap 'rm -rf "$scratch"' EXIT

failures=0

# make_checkout <pom version, or "-" for no pom.xml> <changelog, or "-" for no CHANGELOG.md>
#               [tag name, default v2.2.0] [annotated|lightweight, default annotated]
# Prints the directory of the checkout. The POM has the same shape as the repository's own: one
# project <version> indented two spaces, and a deeper one that must never be read instead.
# The checkout is built as actions/checkout builds one for a tag push: origin holds the commit
# and the tag as pushed, and the checkout is an empty repository that fetched
# "+<sha>:refs/tags/<tag>" with --depth=1, so its local tag is the commit, not the tag object.
make_checkout() {
  local pom_version="$1" changelog="$2" tag="${3:-v2.2.0}" kind="${4:-annotated}" dir src origin sha
  # mktemp, not a counter: this runs inside $(...), a subshell, where a counter never advances.
  dir="$(mktemp -d "${scratch}/checkout.XXXXXX")"
  src="$(mktemp -d "${scratch}/source.XXXXXX")"
  origin="$(mktemp -d "${scratch}/origin.XXXXXX")"
  if [ "$pom_version" != "-" ]; then
    cat > "${src}/pom.xml" <<POM
<?xml version="1.0" encoding="UTF-8"?>
<project>
  <modelVersion>4.0.0</modelVersion>
  <groupId>dev.nkap</groupId>
  <artifactId>nkap-parent</artifactId>
  <version>${pom_version}</version>
  <packaging>pom</packaging>
  <dependencyManagement>
    <dependencies>
      <dependency>
        <artifactId>some-library</artifactId>
        <version>9.9.9</version>
      </dependency>
    </dependencies>
  </dependencyManagement>
</project>
POM
  fi
  if [ "$changelog" != "-" ]; then
    printf '# Changelog\n\n%s\n' "$changelog" > "${src}/CHANGELOG.md"
  fi
  (
    export GIT_AUTHOR_NAME=test GIT_AUTHOR_EMAIL=test@example.invalid
    export GIT_COMMITTER_NAME=test GIT_COMMITTER_EMAIL=test@example.invalid
    git init -q --bare "$origin"
    git -C "$origin" config uploadpack.allowAnySHA1InWant true
    git init -q "$src"
    git -C "$src" add -A
    git -C "$src" commit -q --allow-empty -m "the release commit"
    if [ "$kind" = annotated ]; then
      git -C "$src" tag -a "$tag" -m "Nkap ${tag#v}"
    else
      git -C "$src" tag "$tag"
    fi
    git -C "$src" push -q "file://${origin}" "refs/tags/${tag}" HEAD:refs/heads/main
    sha="$(git -C "$src" rev-parse HEAD)"
    git init -q "$dir"
    git -C "$dir" remote add origin "file://${origin}"
    git -C "$dir" fetch -q --no-tags --depth=1 origin "+${sha}:refs/tags/${tag}"
    git -C "$dir" checkout -q "refs/tags/${tag}"
  ) >/dev/null 2>&1 || { echo "FAIL: could not build a checkout for the test" >&2; exit 1; }
  echo "$dir"
}

# assert <description> <expected exit code> <expected text in the output, or "" for none>
#        <command...>
assert() {
  local description="$1" expected_code="$2" expected_text="$3" actual actual_code
  shift 3
  actual_code=0
  actual="$("$@" 2>&1)" || actual_code=$?
  if [ "$actual_code" != "$expected_code" ]; then
    echo "FAIL: ${description}"
    echo "  expected exit ${expected_code}, got ${actual_code}"
    echo "  output: ${actual}"
    failures=$((failures + 1))
  elif [ -n "$expected_text" ] && ! grep -q -F -- "$expected_text" <<< "$actual"; then
    echo "FAIL: ${description}"
    echo "  expected the output to contain: ${expected_text}"
    echo "  output: ${actual}"
    failures=$((failures + 1))
  fi
}

release_changelog=$'## [2.2.0] - 2026-11-06\n\n### Added\n\n## [2.1.0] - 2026-09-27'

assert "a tag matching the POM and the changelog passes" \
  0 "ok: v2.2.0 matches pom.xml and CHANGELOG.md; the tag is annotated" \
  env REF_NAME=v2.2.0 GITHUB_WORKSPACE="$(make_checkout 2.2.0 "$release_changelog" v2.2.0)" "$script"

assert "a -SNAPSHOT POM fails, naming both versions (the 2.2.0 incident)" \
  1 "pom.xml is at version 2.2.0-SNAPSHOT, but the tag v2.2.0 expects 2.2.0" \
  env REF_NAME=v2.2.0 GITHUB_WORKSPACE="$(make_checkout 2.2.0-SNAPSHOT "$release_changelog" v2.2.0)" "$script"

assert "a POM naming another version entirely fails" \
  1 "pom.xml is at version 2.1.0, but the tag v2.2.0 expects 2.2.0" \
  env REF_NAME=v2.2.0 GITHUB_WORKSPACE="$(make_checkout 2.1.0 "$release_changelog" v2.2.0)" "$script"

assert "a changelog with only an [Unreleased] heading fails, naming the one it found (the 2.2.0 incident)" \
  1 'has no "## [2.2.0]" heading for the tag v2.2.0; its first is "## [Unreleased]"' \
  env REF_NAME=v2.2.0 GITHUB_WORKSPACE="$(make_checkout 2.2.0 $'## [Unreleased]\n\n## [2.1.0] - 2026-09-27' v2.2.0)" "$script"

assert "a changelog without the version fails even when older versions are there" \
  1 'has no "## [2.2.0]" heading' \
  env REF_NAME=v2.2.0 GITHUB_WORKSPACE="$(make_checkout 2.2.0 '## [2.1.0] - 2026-09-27' v2.2.0)" "$script"

assert "a heading for a longer version does not stand in for this one" \
  1 'has no "## [2.2.0]" heading' \
  env REF_NAME=v2.2.0 GITHUB_WORKSPACE="$(make_checkout 2.2.0 $'## [2.2.01] - 2026-11-06\n## [2.2.0-rc1] - 2026-11-01' v2.2.0)" "$script"

assert "a heading mentioned in prose, not at the start of a line, does not count" \
  1 'has no "## [2.2.0]" heading' \
  env REF_NAME=v2.2.0 GITHUB_WORKSPACE="$(make_checkout 2.2.0 'see ## [2.2.0] below' v2.2.0)" "$script"

assert "both failures are reported together, not only the first" \
  1 "2 check(s) failed: v2.2.0 does not match the commit it tags" \
  env REF_NAME=v2.2.0 GITHUB_WORKSPACE="$(make_checkout 2.2.0-SNAPSHOT '## [Unreleased]' v2.2.0)" "$script"

assert "a checkout with no pom.xml fails" \
  1 "pom.xml does not exist" \
  env REF_NAME=v2.2.0 GITHUB_WORKSPACE="$(make_checkout - "$release_changelog" v2.2.0)" "$script"

assert "a checkout with no CHANGELOG.md fails" \
  1 "CHANGELOG.md does not exist" \
  env REF_NAME=v2.2.0 GITHUB_WORKSPACE="$(make_checkout 2.2.0 - v2.2.0)" "$script"

assert "a POM version nested deeper than the project's own is never read in its place" \
  1 "pom.xml is at version 2.2.0, but the tag v9.9.9 expects 9.9.9" \
  env REF_NAME=v9.9.9 GITHUB_WORKSPACE="$(make_checkout 2.2.0 $'## [9.9.9] - 2026-11-06' v9.9.9)" "$script"

# The pre-release decision, written down in the script's header: the POM must match, the
# changelog heading is not required.
assert "a pre-release tag matching the POM passes without a changelog heading" \
  0 "ok: v2.3.0-rc1 matches pom.xml (a pre-release tag needs no changelog heading)" \
  env REF_NAME=v2.3.0-rc1 GITHUB_WORKSPACE="$(make_checkout 2.3.0-rc1 $'## [Unreleased]\n\n## [2.2.0] - 2026-11-06' v2.3.0-rc1)" "$script"

assert "a pre-release tag at a -SNAPSHOT POM fails, as a release tag would" \
  1 "pom.xml is at version 2.3.0-SNAPSHOT, but the tag v2.3.0-rc1 expects 2.3.0-rc1" \
  env REF_NAME=v2.3.0-rc1 GITHUB_WORKSPACE="$(make_checkout 2.3.0-SNAPSHOT '## [Unreleased]' v2.3.0-rc1)" "$script"

assert "a pre-release tag naming another version than the POM fails" \
  1 "pom.xml is at version 2.2.0, but the tag v2.3.0-rc1 expects 2.3.0-rc1" \
  env REF_NAME=v2.3.0-rc1 GITHUB_WORKSPACE="$(make_checkout 2.2.0 "$release_changelog" v2.3.0-rc1)" "$script"

assert "a pre-release tag does not need a CHANGELOG.md at all" \
  0 "ok: v2.3.0-rc1 matches pom.xml" \
  env REF_NAME=v2.3.0-rc1 GITHUB_WORKSPACE="$(make_checkout 2.3.0-rc1 - v2.3.0-rc1)" "$script"

# Issue #277: the tag must be annotated.
#
# First, that this test's checkout reproduces what actions/checkout does on a tag push: the local
# tag ends up pointing at the commit, so reading it as it stands says "commit" for an annotated
# tag as well as a lightweight one. If this assertion ever starts printing "tag", the setup no
# longer reproduces the problem, and the assertions below stop proving the script re-fetches.
assert "the checkout leaves an annotated tag looking like a commit, as actions/checkout does" \
  0 "commit" \
  git -C "$(make_checkout 2.2.0 "$release_changelog" v2.2.0 annotated)" cat-file -t refs/tags/v2.2.0

assert "a lightweight tag fails, saying what it is and how to redo it" \
  1 "the tag v2.2.0 is lightweight" \
  env REF_NAME=v2.2.0 GITHUB_WORKSPACE="$(make_checkout 2.2.0 "$release_changelog" v2.2.0 lightweight)" "$script"

assert "the failure for a lightweight tag names the command that fixes it" \
  1 "git tag -a v2.2.0 -m" \
  env REF_NAME=v2.2.0 GITHUB_WORKSPACE="$(make_checkout 2.2.0 "$release_changelog" v2.2.0 lightweight)" "$script"

assert "a lightweight pre-release tag fails too: every v* tag publishes" \
  1 "the tag v2.3.0-rc1 is lightweight" \
  env REF_NAME=v2.3.0-rc1 GITHUB_WORKSPACE="$(make_checkout 2.3.0-rc1 - v2.3.0-rc1 lightweight)" "$script"

assert "a lightweight tag at a matching POM and changelog fails on that alone" \
  1 "1 check(s) failed: v2.2.0 does not match the commit it tags" \
  env REF_NAME=v2.2.0 GITHUB_WORKSPACE="$(make_checkout 2.2.0 "$release_changelog" v2.2.0 lightweight)" "$script"

assert "all three failures are reported together, the lightweight tag included" \
  1 "3 check(s) failed: v2.2.0 does not match the commit it tags" \
  env REF_NAME=v2.2.0 GITHUB_WORKSPACE="$(make_checkout 2.2.0-SNAPSHOT '## [Unreleased]' v2.2.0 lightweight)" "$script"

assert "an annotated tag at a -SNAPSHOT POM still fails on the POM alone" \
  1 "1 check(s) failed: v2.2.0 does not match the commit it tags" \
  env REF_NAME=v2.2.0 GITHUB_WORKSPACE="$(make_checkout 2.2.0-SNAPSHOT "$release_changelog" v2.2.0 annotated)" "$script"

assert "a tag whose type cannot be read fails, saying so, rather than passing" \
  1 "could not fetch refs/tags/v2.2.0 from nowhere to read its type" \
  env REF_NAME=v2.2.0 TAG_REMOTE=nowhere GITHUB_WORKSPACE="$(make_checkout 2.2.0 "$release_changelog" v2.2.0 annotated)" "$script"

assert "a missing REF_NAME is refused, not treated as an empty tag" \
  1 "REF_NAME must be set" \
  env -u REF_NAME GITHUB_WORKSPACE="$(make_checkout 2.2.0 "$release_changelog")" "$script"

# The one assumption the script makes about the real root POM, held where it can fail: the
# project's own <version> is the only line indented two spaces. If the POM ever gains a
# <parent> block or another two-space <version>, this fails here, not at a release.
project_versions="$(grep -c -E '^  <version>[^<]*</version>' "${repo_root}/pom.xml" || true)"
if [ "$project_versions" != "1" ]; then
  echo "FAIL: the repository's own pom.xml has ${project_versions} two-space <version> lines, the script reads exactly one"
  failures=$((failures + 1))
fi

if [ "$failures" -gt 0 ]; then
  echo "${failures} assertion(s) failed"
  exit 1
fi
echo "all check-release-tag assertions passed"
