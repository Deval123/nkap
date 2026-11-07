#!/usr/bin/env bash
set -euo pipefail

# Exercises check-trivy-ignore-expiry.sh directly, against small ignore files built in a temporary
# directory, so the rule written in trivy-ignore.yaml's header (every entry says why and expires)
# is an assertion this repository can fail on, and not a sentence. Written for #301. Run by
# build.yml on every push and pull request.
#
# TODAY is set on every call, so nothing here changes with the calendar. The repository's own
# trivy-ignore.yaml is not asserted here, on purpose: its entries expire, and a test that started
# failing on a date would be the check itself in the wrong place. image-scan.yml runs the script on
# the real file before every scan. The entries built here have the real file's shape (a folded
# statement, a list of purls), because a parser tested only on simpler input is tested on a shape
# nobody writes.

cd "$(dirname "${BASH_SOURCE[0]}")"
script="$(pwd)/check-trivy-ignore-expiry.sh"

scratch="$(mktemp -d)"
trap 'rm -rf "$scratch"' EXIT

failures=0

# ignore_file <body> -- prints the path of a file holding that body.
ignore_file() {
  local file
  file="$(mktemp "${scratch}/ignore.XXXXXX")"
  printf '%s\n' "$1" > "$file"
  echo "$file"
}

STATEMENT='ACCEPTED, NOT FIXED: a known finding stays until the date below, because the fix is a major upgrade
      and not a patch. Owner and end: issue #1, the upgrade.'

# entry <id> <expired_at line, or "" for none> -- one entry, in the shape the real file has.
entry() {
  printf '  - id: %s\n    purls:\n      - "pkg:maven/org.example/lib@1.0"\n    statement: >-\n      %s\n' "$1" "$STATEMENT"
  if [ -n "$2" ]; then printf '    %s\n' "$2"; fi
}

# assert <description> <expected exit code> <expected text in the output, or "" for none> <command...>
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

run() { # run <TODAY> <file>
  env TODAY="$1" IGNORE_FILE="$2" "$script"
}

# --- what passes ------------------------------------------------------------------------------

assert "an entry with a folded statement, purls and a future date passes" \
  0 "ok: 1 entry" \
  run 2026-11-07 "$(ignore_file "vulnerabilities:
$(entry CVE-2026-0001 'expired_at: 2027-06-01')")"

assert "two such entries pass" \
  0 "ok: 2 entries" \
  run 2026-11-07 "$(ignore_file "vulnerabilities:
$(entry CVE-2026-0001 'expired_at: 2027-06-01')
$(entry CVE-2026-0002 'expired_at: 2027-06-02')")"

assert "a file with a section and no entries passes, since it holds none" \
  0 "ok: 0 entries" \
  run 2026-11-07 "$(ignore_file 'vulnerabilities:')"

assert "an empty file passes, since it holds none" \
  0 "ok: 0 entries" \
  run 2026-11-07 "$(ignore_file '')"

assert "the day after today is still in the future" \
  0 "ok: 1 entry" \
  run 2026-11-07 "$(ignore_file "vulnerabilities:
$(entry CVE-2026-0001 'expired_at: 2026-11-08')")"

assert "a quoted date is read as the date it is" \
  0 "ok: 1 entry" \
  run 2026-11-07 "$(ignore_file "vulnerabilities:
$(entry CVE-2026-0001 'expired_at: "2027-06-01"')")"

assert "comments and blank lines between and inside entries do not matter" \
  0 "ok: 2 entries" \
  run 2026-11-07 "$(ignore_file "# a comment at the top

vulnerabilities:
  # a comment between entries
$(entry CVE-2026-0001 'expired_at: 2027-06-01')

  # another
$(entry CVE-2026-0002 'expired_at: 2027-06-02')")"

assert "an inline statement of enough length passes" \
  0 "ok: 1 entry" \
  run 2026-11-07 "$(ignore_file 'vulnerabilities:
  - id: CVE-2026-0001
    statement: "Accepted until the date, because the fix is a major upgrade (issue 1)."
    expired_at: 2027-06-01')"

# --- what is refused ---------------------------------------------------------------------------

assert "an entry with no expired_at is refused, because it would never lapse" \
  1 "has no expired_at" \
  run 2026-11-07 "$(ignore_file "vulnerabilities:
$(entry CVE-2026-0001 '')")"

assert "a date written only inside the statement is not an expired_at" \
  1 "has no expired_at" \
  run 2026-11-07 "$(ignore_file 'vulnerabilities:
  - id: CVE-2026-0001
    statement: >-
      Accepted until a date that is only mentioned here, which is the shape the dependency scan
      file warns about. expired_at: 2099-01-01
')"

assert "a date that has passed is refused, naming the entry" \
  1 "lapsed on 2026-01-01" \
  run 2026-11-07 "$(ignore_file "vulnerabilities:
$(entry CVE-2026-0001 'expired_at: 2026-01-01')")"

assert "a date that is today is refused, as already lapsed" \
  1 "lapsed on 2026-11-07" \
  run 2026-11-07 "$(ignore_file "vulnerabilities:
$(entry CVE-2026-0001 'expired_at: 2026-11-07')")"

assert "a date in another form is refused" \
  1 "not a date written YYYY-MM-DD" \
  run 2026-11-07 "$(ignore_file "vulnerabilities:
$(entry CVE-2026-0001 'expired_at: 07/02/2027')")"

assert "a word where the date should be is refused" \
  1 "not a date written YYYY-MM-DD" \
  run 2026-11-07 "$(ignore_file "vulnerabilities:
$(entry CVE-2026-0001 'expired_at: soon')")"

assert "a date that is not on the calendar is refused" \
  1 "not a real calendar date" \
  run 2026-11-07 "$(ignore_file "vulnerabilities:
$(entry CVE-2026-0001 'expired_at: 2027-02-30')")"

assert "an entry with no statement is refused" \
  1 "has no statement" \
  run 2026-11-07 "$(ignore_file 'vulnerabilities:
  - id: CVE-2026-0001
    expired_at: 2027-06-01')"

assert "an entry whose statement is too short to be a reason is refused" \
  1 "too short to be a reason" \
  run 2026-11-07 "$(ignore_file 'vulnerabilities:
  - id: CVE-2026-0001
    statement: ok
    expired_at: 2027-06-01')"

assert "every failure is reported, not only the first" \
  1 "3 failure(s) in 4 entries" \
  run 2026-11-07 "$(ignore_file "vulnerabilities:
$(entry CVE-2026-0001 'expired_at: 2027-06-01')
$(entry CVE-2026-0002 '')
$(entry CVE-2026-0003 'expired_at: 2026-01-01')
$(entry CVE-2026-0004 'expired_at: soon')")"

assert "the failing entries are named by their own id, not only by position" \
  1 "CVE-2026-0003" \
  run 2026-11-07 "$(ignore_file "vulnerabilities:
$(entry CVE-2026-0001 'expired_at: 2027-06-01')
$(entry CVE-2026-0003 'expired_at: 2026-01-01')")"

assert "an entry under another top-level section is held to the same rule" \
  1 "has no expired_at" \
  run 2026-11-07 "$(ignore_file "vulnerabilities:
$(entry CVE-2026-0001 'expired_at: 2027-06-01')
misconfigurations:
$(entry AVD-0001 '')")"

# --- what it cannot read: refused, never passed -------------------------------------------------

assert "a flow-style file is refused as unreadable, not passed as empty" \
  1 "cannot be read" \
  run 2026-11-07 "$(ignore_file 'vulnerabilities: [{id: CVE-2026-0001}]')"

assert "an odd indentation is refused as unreadable" \
  1 "which is not a level this check reads" \
  run 2026-11-07 "$(ignore_file 'vulnerabilities:
   - id: CVE-2026-0001')"

assert "a tab is refused as unreadable" \
  1 "a tab" \
  run 2026-11-07 "$(printf 'vulnerabilities:\n\t- id: CVE-2026-0001\n' > "${scratch}/tab.yaml"; echo "${scratch}/tab.yaml")"

assert "an entry outside any section is refused as unreadable" \
  1 "cannot be read" \
  run 2026-11-07 "$(ignore_file '  - id: CVE-2026-0001')"

assert "a missing file is refused, not passed" \
  1 "does not exist" \
  run 2026-11-07 "${scratch}/nowhere.yaml"

assert "a TODAY that is not a date is refused" \
  1 "TODAY must be YYYY-MM-DD" \
  run tomorrow "$(ignore_file '')"

if [ "$failures" -gt 0 ]; then
  echo "${failures} assertion(s) failed"
  exit 1
fi
echo "all check-trivy-ignore-expiry assertions passed"
