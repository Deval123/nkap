#!/usr/bin/env bash
set -euo pipefail

# Exercises check-suppression-expiry.sh directly, against small suppression files built in a
# temporary directory, so the rule issue #282 closes (every suppression in
# dependency-check-suppressions.xml carries an `until` that has not passed) is an assertion
# this repository can fail on, not a sentence in that file's header. Run by build.yml on every
# push and pull request.
#
# TODAY is set on every call that depends on the date, so nothing here changes with the
# calendar. The repository's own suppression file is not asserted here, on purpose: its entries
# expire, and a test that started failing on a date would be the check itself in the wrong place.
# vulnerability-check.yml runs the script on the real file.

cd "$(dirname "${BASH_SOURCE[0]}")"
script="$(pwd)/check-suppression-expiry.sh"

scratch="$(mktemp -d)"
trap 'rm -rf "$scratch"' EXIT

failures=0

NS='xmlns="https://jeremylong.github.io/DependencyCheck/dependency-suppression.1.4.xsd"'

# suppressions <body> -- prints the path of a suppression file holding that body.
suppressions() {
  local file
  file="$(mktemp "${scratch}/suppressions.XXXXXX")"
  printf '<?xml version="1.0" encoding="UTF-8"?>\n<suppressions %s>\n%s\n</suppressions>\n' "$NS" "$1" > "$file"
  echo "$file"
}

# entry <attributes> <cve> -- one <suppress> element, the way the file's header shows one.
entry() {
  printf '  <suppress%s>\n    <notes><![CDATA[ a reason ]]></notes>\n    <packageUrl regex="true">^pkg:maven/org\\.example/lib@.*$</packageUrl>\n    <cve>%s</cve>\n  </suppress>' "$1" "$2"
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

run() { # run <TODAY> <file>
  env TODAY="$1" SUPPRESSIONS_FILE="$2" "$script"
}

# --- what passes -----------------------------------------------------------------------------

assert "an empty file passes: it holds no suppression" \
  0 "ok: 0 suppression(s)" \
  run 2026-11-07 "$(suppressions '')"

assert "an entry with an until in the future passes" \
  0 "ok: 1 suppression(s)" \
  run 2026-11-07 "$(suppressions "$(entry ' until="2027-06-01Z"' CVE-2026-0001)")"

assert "an until the day after today passes: the entry still applies" \
  0 "ok: 1 suppression(s)" \
  run 2026-11-07 "$(suppressions "$(entry ' until="2026-11-08Z"' CVE-2026-0001)")"

# The real file's header carries a <suppress> example in a comment. It is not an entry, and a
# search for the text would have counted it.
assert "a <suppress> inside a comment is not an entry" \
  0 "ok: 0 suppression(s)" \
  run 2026-11-07 "$(suppressions '<!-- <suppress><cve>CVE-2026-9999</cve></suppress> -->')"

assert "several entries, all with a future until, pass" \
  0 "ok: 3 suppression(s)" \
  run 2026-11-07 "$(suppressions "$(entry ' until="2027-06-01Z"' CVE-2026-0001)
$(entry ' until="2027-06-01Z"' CVE-2026-0002)
$(entry ' until="2028-01-01Z"' CVE-2026-0003)")"

# --- what is refused -------------------------------------------------------------------------

assert "an entry with no until is refused, and named" \
  1 "suppression #1 (CVE-2026-0001" \
  run 2026-11-07 "$(suppressions "$(entry '' CVE-2026-0001)")"

assert "an entry with no until is refused for having none" \
  1 "has no until attribute" \
  run 2026-11-07 "$(suppressions "$(entry '' CVE-2026-0001)")"

# The shape the project this was copied from had: the date is written down, in <notes>, and
# nothing stops the suppression on it.
assert "a date written only in the notes does not count as an until" \
  1 "has no until attribute" \
  run 2026-11-07 "$(suppressions '  <suppress>
    <notes><![CDATA[ Expires 2027-06-01. ]]></notes>
    <cve>CVE-2026-0001</cve>
  </suppress>')"

assert "an entry whose until has passed is refused, and the message says so" \
  1 "expired on 2026-11-06 (today is 2026-11-07)" \
  run 2026-11-07 "$(suppressions "$(entry ' until="2026-11-06Z"' CVE-2026-0001)")"

# The schema: "On and after the 'until' date the suppression will no longer be active."
assert "an until that is today is refused: the plugin has already stopped applying it" \
  1 "expired on 2026-11-07" \
  run 2026-11-07 "$(suppressions "$(entry ' until="2026-11-07Z"' CVE-2026-0001)")"

assert "an until without the trailing Z is refused" \
  1 'until="2027-06-01", which is not a date written YYYY-MM-DDZ' \
  run 2026-11-07 "$(suppressions "$(entry ' until="2027-06-01"' CVE-2026-0001)")"

assert "an until that is not a date is refused" \
  1 'until="soon", which is not a date written YYYY-MM-DDZ' \
  run 2026-11-07 "$(suppressions "$(entry ' until="soon"' CVE-2026-0001)")"

assert "an until that is not a real calendar date is refused" \
  1 "which is not a real calendar date" \
  run 2026-11-07 "$(suppressions "$(entry ' until="2027-02-30Z"' CVE-2026-0001)")"

assert "an empty until is refused" \
  1 'until="", which is not a date' \
  run 2026-11-07 "$(suppressions "$(entry ' until=""' CVE-2026-0001)")"

assert "every failure is reported, not only the first" \
  1 "3 of 4 suppression(s) failed" \
  run 2026-11-07 "$(suppressions "$(entry ' until="2027-06-01Z"' CVE-2026-0001)
$(entry '' CVE-2026-0002)
$(entry ' until="2026-01-01Z"' CVE-2026-0003)
$(entry ' until="nope"' CVE-2026-0004)")"

assert "the failing entries are named by their own CVE, not only by position" \
  1 "CVE-2026-0003" \
  run 2026-11-07 "$(suppressions "$(entry ' until="2027-06-01Z"' CVE-2026-0001)
$(entry ' until="2026-01-01Z"' CVE-2026-0003)")"

assert "an entry nested in a group needs its own until" \
  1 "has no until attribute" \
  run 2026-11-07 "$(suppressions '  <suppressionGroup name="g" until="2027-06-01Z">
    <notes>why</notes>
    <suppress><cve>CVE-2026-0001</cve></suppress>
  </suppressionGroup>')"

# --- what it cannot read ---------------------------------------------------------------------

assert "a file that is not XML is refused, not passed" \
  1 "is not well-formed XML" \
  run 2026-11-07 "$(printf 'this is not xml' > "${scratch}/bad.xml"; echo "${scratch}/bad.xml")"

assert "a missing file is refused, not passed" \
  1 "does not exist" \
  run 2026-11-07 "${scratch}/nowhere.xml"

assert "a TODAY that is not a date is refused" \
  1 "TODAY must be YYYY-MM-DD" \
  run tomorrow "$(suppressions '')"

if [ "$failures" -gt 0 ]; then
  echo "${failures} assertion(s) failed"
  exit 1
fi
echo "all check-suppression-expiry assertions passed"
