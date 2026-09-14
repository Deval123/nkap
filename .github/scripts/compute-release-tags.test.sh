#!/usr/bin/env bash
set -euo pipefail

# Exercises compute-release-tags.sh directly -- no GitHub Actions context, no tag push, no
# workflow_dispatch run -- so the rule issue #103 fixed (latest moves only for a real,
# non-pre-release tag push) is an assertion this repository can fail on, not only a reading
# of release.yml's YAML. Run by build.yml on every push and pull request.

cd "$(dirname "${BASH_SOURCE[0]}")"
script=./compute-release-tags.sh

failures=0

assert() {
  local description="$1" expected_version="$2" expected_latest="$3"
  shift 3
  local actual actual_version actual_latest
  actual="$("$@")"
  actual_version="$(grep '^version=' <<< "$actual" | cut -d= -f2-)"
  actual_latest="$(grep '^latest=' <<< "$actual" | cut -d= -f2-)"
  if [ "$actual_version" != "$expected_version" ] || [ "$actual_latest" != "$expected_latest" ]; then
    echo "FAIL: ${description}"
    echo "  expected version=${expected_version} latest=${expected_latest}"
    echo "  actual   version=${actual_version} latest=${actual_latest}"
    failures=$((failures + 1))
  fi
}

assert "a real release tag moves latest" \
  "1.0.0" "true" \
  env EVENT_NAME=push REF_NAME=v1.0.0 "$script"

assert "a release-candidate tag does not move latest" \
  "1.0.0-rc1" "false" \
  env EVENT_NAME=push REF_NAME=v1.0.0-rc1 "$script"

assert "a beta tag does not move latest" \
  "1.1.0-beta" "false" \
  env EVENT_NAME=push REF_NAME=v1.1.0-beta "$script"

assert "the exact rehearsal that found issue #103 does not move latest" \
  "0.0.1-rc1" "false" \
  env EVENT_NAME=push REF_NAME=v0.0.1-rc1 "$script"

assert "workflow_dispatch never moves latest, even with a plain version" \
  "1.0.0" "false" \
  env EVENT_NAME=workflow_dispatch TEST_VERSION=1.0.0 "$script"

assert "workflow_dispatch with a pre-release test version does not move latest either" \
  "0.0.1-rc1" "false" \
  env EVENT_NAME=workflow_dispatch TEST_VERSION=0.0.1-rc1 "$script"

if [ "$failures" -gt 0 ]; then
  echo "${failures} assertion(s) failed"
  exit 1
fi
echo "all compute-release-tags assertions passed"
