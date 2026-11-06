#!/usr/bin/env bash
set -euo pipefail

# Given the name of a pushed tag and a checkout at the commit it points to, refuses the tag
# when that commit does not say what the tag says. Written for issue #270: twice a v* tag was
# pushed at a commit that was not the release commit, and at 2.2.0 release.yml published
# 2.2.0 and latest from a tree whose POMs still read 2.2.0-SNAPSHOT and whose CHANGELOG.md
# had no [2.2.0] entry. CONTRIBUTING.md step 3 already asks for both checks by hand, and both
# times they printed exactly the right warning and nobody read it. This is the second
# defence, not a replacement: that step stays, and this is what catches it when it is skipped.
#
# It is a script, not a workflow step, for the reason compute-release-tags.sh gives for
# itself: a decision this size is wrong by one character and is normally only found by an
# actual release. As a script it can be tested directly; see check-release-tag.test.sh,
# which build.yml runs on every push and pull request.
#
# Inputs, as environment variables -- named after the GitHub Actions context fields they
# come from, so the workflow passes them through unchanged:
#
#   REF_NAME          the tag name, e.g. "v2.2.0"
#   GITHUB_WORKSPACE  the checkout to read; defaults to the current directory, which is
#                     where the workflow runs it
#
# What it checks, with the version being REF_NAME without its leading "v":
#
#   1. The root pom.xml's <version> equals that version. This subsumes the -SNAPSHOT case
#      and also catches a tag naming a different version entirely. Only the root POM is read:
#      the modules inherit it.
#   2. CHANGELOG.md has a heading that starts "## [<version>]" -- the repository writes
#      "## [2.2.0] - 2026-11-06", and an "## [Unreleased]" heading is not one.
#
# What it deliberately does not check: that the tagged commit is on main, the
# `git merge-base --is-ancestor` check in CONTRIBUTING.md step 3. A tag push cannot cheaply
# know what main looked like at the moment it was pushed, so that one stays the maintainer's.
#
# Output: one line on stdout and exit 0 when every check that applies passes. Otherwise one
# line per failure on stderr, each saying what was found against what was expected, and
# exit 1. Every failure is reported, not only the first.
#
# The pre-release decision (no pre-release tag has ever existed here, so this was decided by
# reading, not by precedent): a pre-release tag -- anything that is not a plain X.Y.Z, the
# same line compute-release-tags.sh draws for "latest" -- must match the root POM, and does
# NOT need a changelog heading.
#
#   - The POM check stays, because it is the one that protects what the images say about
#     themselves. A v1.0.0-rc1 tag at a tree reading 1.0.0-SNAPSHOT would publish an image
#     named 1.0.0-rc1 from a tree claiming something else, which is the 2.2.0 incident again
#     with a different suffix.
#   - The changelog check goes, because a heading is a release note and a pre-release is
#     not a release: CONTRIBUTING.md step 5 creates the GitHub release "neither draft nor
#     prerelease", compute-release-tags.sh says "a pre-release tag is not a release", and
#     the only pre-release tag this project ever pushed was the first rehearsal (issue
#     #103), before CHANGELOG.md existed. Requiring "## [1.0.0-rc1]" would also mean writing
#     a heading to be deleted at the real release, noise in the file operators read.
#   - A rehearsal that must not touch a POM has workflow_dispatch, which has no tag and is
#     never checked here. A pre-release tag that is pushed is an actual publish and has to
#     be what it says.

ref_name="${REF_NAME:?REF_NAME must be set}"
workspace="${GITHUB_WORKSPACE:-.}"

version="${ref_name#v}"

failures=0
fail() {
  echo "FAIL: $1" >&2
  failures=$((failures + 1))
}

pom="${workspace}/pom.xml"
if [ ! -f "$pom" ]; then
  fail "${pom} does not exist, so the tag ${ref_name} cannot be matched against a POM version"
else
  # The project's own <version> is the only one indented two spaces: the root has no
  # <parent> block, and every dependency or plugin version sits deeper. The test holds that.
  pom_version="$(grep -m1 -E '^  <version>[^<]*</version>' "$pom" | sed -E 's#^  <version>([^<]*)</version>.*#\1#' || true)"
  if [ -z "$pom_version" ]; then
    fail "${pom} has no project <version> to compare with the tag ${ref_name}"
  elif [ "$pom_version" != "$version" ]; then
    fail "pom.xml is at version ${pom_version}, but the tag ${ref_name} expects ${version}"
  fi
fi

if [[ "$version" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
  changelog="${workspace}/CHANGELOG.md"
  heading="## [${version}]"
  if [ ! -f "$changelog" ]; then
    fail "${changelog} does not exist, so it has no \"${heading}\" heading for the tag ${ref_name}"
  elif ! awk -v heading="$heading" 'index($0, heading) == 1 { found = 1 } END { exit !found }' "$changelog"; then
    found_first="$(grep -m1 '^## \[' "$changelog" || true)"
    fail "CHANGELOG.md has no \"${heading}\" heading for the tag ${ref_name}; its first is \"${found_first:-none}\""
  fi
fi

if [ "$failures" -gt 0 ]; then
  echo "${failures} check(s) failed: ${ref_name} does not match the commit it tags" >&2
  exit 1
fi

if [[ "$version" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
  echo "ok: ${ref_name} matches pom.xml and CHANGELOG.md"
else
  echo "ok: ${ref_name} matches pom.xml (a pre-release tag needs no changelog heading)"
fi
