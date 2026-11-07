#!/usr/bin/env bash
set -euo pipefail

# Exercises image-scan-verdict.py directly, against Trivy reports in image-scan-verdict.fixtures/,
# so the decision image-scan.yml makes (issue #288, #296) is an assertion this repository can fail
# on and not something once tried on a laptop. Written for #297. Run by build.yml on every push and
# pull request.
#
# Every case asserts the exit code AND what was written, both on the screen and in the summary file
# the job summary is made from. Exit 1 alone cannot tell a finding from no answer, and that
# difference is why this workflow exists. So for each case that must be no answer, the test asserts
# that the output names the image and says why there was no answer, in the log and in the summary,
# and that it does not say a finding has a fix. The two are mixed up by nothing here.
#
# The fixtures are real Trivy reports, trimmed, or one-line edits of them; see the README beside
# them for where each came from and which one is not a shape Trivy was seen to write. They are
# suspect when the Trivy pin in image-scan.yml moves, and nothing enforces re-deriving them.
#
# The twelve cases of #297:
#
#    1  a fixable HIGH/CRITICAL finding: exit 1, named as fixable
#    2  an unfixable finding only: exit 0, the warning, the caution in the summary
#    3  both: exit 1, the unfixable count still reported
#    4  nothing at all: exit 0, no warning, and no caution (it says there are no findings)
#   5a  "FixedVersion" absent, which is what Trivy writes: counted as unfixable
#   5b  "FixedVersion": "", which Trivy was not seen to write: counted as unfixable
#    6  a missing report: exit 1, no answer
#    7  a report for a different image: exit 1, no answer
#    8  a report with no operating system: exit 1, no answer
#    9  a report with no Java archives: exit 1, no answer
#   10  malformed JSON (10a), and JSON that is not a report object (10b): exit 1, no answer
#   11  three images, one with a finding, one clean, one with no report: the missing report fails
#       the job and hides neither of the others
#
# And one assertion that is not a case: the summary file is required.

cd "$(dirname "${BASH_SOURCE[0]}")"
here="$(pwd)"
script="${here}/image-scan-verdict.py"
fixtures="${here}/image-scan-verdict.fixtures"

scratch="$(mktemp -d)"
trap 'rm -rf "$scratch"' EXIT

failures=0

# The two images the fixtures are about. Each report names the image it was made for, and the
# verdict refuses a report that names another, so a fixture is run under its own image.
PUBLISHED="ghcr.io/deval123/nkap-gateway"   # fixable.json, both.json
LOCAL="nkap-demo-gateway"                    # none.json and everything derived from it

CASE=""
CODE=0
OUT=""
SUMMARY=""

# run_verdict <image>=<fixture file, or - for no report> ...
# Runs the script the way the workflow does (from a directory holding reports/, with
# GITHUB_STEP_SUMMARY set) and leaves its exit code, output and summary file in CODE, OUT, SUMMARY.
run_verdict() {
  local dir pair image fixture
  local images=()
  dir="$(mktemp -d "${scratch}/case.XXXXXX")"
  mkdir "${dir}/reports"
  : > "${dir}/summary.md"
  for pair in "$@"; do
    image="${pair%%=*}"
    fixture="${pair#*=}"
    images+=("$image")
    if [ "$fixture" != "-" ]; then
      cp "${fixtures}/${fixture}" "${dir}/reports/${image##*/}.json"
    fi
  done
  CODE=0
  OUT="$(cd "$dir" && GITHUB_STEP_SUMMARY="${dir}/summary.md" python3 -I "$script" latest "${images[@]}" 2>&1)" || CODE=$?
  SUMMARY="$(cat "${dir}/summary.md")"
}

begin() { CASE="$1"; }

fail() {
  echo "FAIL: ${CASE}"
  echo "  $1"
  echo "  exit code: ${CODE}"
  echo "  output:"
  sed 's/^/    /' <<< "$OUT"
  echo "  summary:"
  sed 's/^/    /' <<< "$SUMMARY"
  failures=$((failures + 1))
}

exit_is()      { [ "$CODE" = "$1" ] || fail "expected exit ${1}"; }
out_has()      { grep -q -F -- "$1" <<< "$OUT" || fail "expected the output to contain: ${1}"; }
out_lacks()    { if grep -q -F -- "$1" <<< "$OUT"; then fail "expected the output NOT to contain: ${1}"; fi; }
summary_has()  { grep -q -F -- "$1" <<< "$SUMMARY" || fail "expected the summary to contain: ${1}"; }
summary_lacks() { if grep -q -F -- "$1" <<< "$SUMMARY"; then fail "expected the summary NOT to contain: ${1}"; fi; }

FIX_ERROR="at least one image has a HIGH or CRITICAL finding with a fix available"
CAUTION="Green does not mean clean"
NO_FINDINGS="There are no HIGH or CRITICAL findings in any image."

# no_answer <image ref as reported> <why>
# The two-part assertion for a run that has no answer: exit 1, AND output that names the image and
# the reason, in the log and in the summary, and does not say a finding has a fix.
no_answer() {
  exit_is 1
  out_has "::error::${1}: ${2}"
  summary_has "**No answer:** ${1}: ${2}"
  out_lacks "$FIX_ERROR"
  out_lacks "Traceback"
}

# --- fixture self-checks: the cases below must not pass because a fixture drifted -------------------

python3 -I - "$fixtures" <<'PY' || { echo "FAIL: a fixture is not what the cases below assume"; failures=$((failures + 1)); }
import json
import sys

d = sys.argv[1]

def findings(name):
    report = json.load(open(d + "/" + name, encoding="utf-8"))
    return [v for r in report["Results"] for v in (r.get("Vulnerabilities") or [])]

fixable = findings("fixable.json")
assert fixable and all(v.get("FixedVersion") for v in fixable), "fixable.json must have only findings with a version"
assert findings("none.json") == [], "none.json must have no findings"

only = findings("unfixable-only.json")
assert len(only) == 1 and "FixedVersion" not in only[0], "unfixable-only.json: one finding, with the key absent"
assert only[0]["Severity"] in ("HIGH", "CRITICAL")

empty = findings("unfixable-empty-string.json")
assert len(empty) == 1 and empty[0].get("FixedVersion") == "", "unfixable-empty-string.json: one finding, with an empty string"

both = findings("both.json")
assert any("FixedVersion" not in v for v in both) and any(v.get("FixedVersion") for v in both), "both.json: fixable and unfixable"
PY

# --- 1 to 4: what turns the job red, and what only gets reported ----------------------------------

begin "1. a fixable HIGH/CRITICAL finding fails the job and is named as fixable"
run_verdict "${PUBLISHED}=fixable.json"
exit_is 1
out_has "FIXABLE   ${PUBLISHED}"
out_has "fixable 4 (OS 2, jar 2), unfixable 0 (OS 0, jar 0)"
out_has "::error::${FIX_ERROR}"
summary_has "| \`${PUBLISHED}:latest\` | alpine 3.21.2 (end of life) | 4 (OS 2, jar 2) | 0 (OS 0, jar 0) |"
summary_lacks "$CAUTION"        # nothing unfixable here, so the caution does not apply
summary_lacks "No answer"

begin "2. an unfixable finding alone passes, with the warning raised and the caution in the summary"
run_verdict "${LOCAL}=unfixable-only.json"
exit_is 0
out_has "UNFIXABLE ${LOCAL}"
out_has "::warning::1 HIGH or CRITICAL finding(s) have no fix available"
out_has "green is not clean"
out_lacks "::error::"
summary_has "| \`${LOCAL}:latest\` | alpine 3.24.4 | 0 (OS 0, jar 0) | 1 (OS 1, jar 0) |"
summary_has "$CAUTION"

begin "3. a fixable and an unfixable finding together fail the job, and the unfixable count is still reported"
run_verdict "${PUBLISHED}=both.json"
exit_is 1
out_has "::error::${FIX_ERROR}"
out_has "fixable 4 (OS 2, jar 2), unfixable 1 (OS 1, jar 0)"
summary_has "| \`${PUBLISHED}:latest\` | alpine 3.21.2 (end of life) | 4 (OS 2, jar 2) | 1 (OS 1, jar 0) |"
summary_has "$CAUTION"

begin "4. a run with no findings passes with no warning, and says so instead of cautioning"
run_verdict "${LOCAL}=none.json"
exit_is 0
out_has "clean     ${LOCAL}"
out_has "fixable 0 (OS 0, jar 0), unfixable 0 (OS 0, jar 0)"
out_lacks "::warning::"
out_lacks "::error::"
summary_has "| \`${LOCAL}:latest\` | alpine 3.24.4 | 0 (OS 0, jar 0) | 0 (OS 0, jar 0) |"
summary_has "$NO_FINDINGS"
summary_lacks "$CAUTION"        # a caution repeated where it does not apply is one people skip

# --- 5: what counts as unfixable ------------------------------------------------------------------

begin "5a. a finding with no FixedVersion key, which is what Trivy writes, is unfixable and does not crash"
run_verdict "${LOCAL}=unfixable-only.json"
exit_is 0
out_has "fixable 0 (OS 0, jar 0), unfixable 1 (OS 1, jar 0)"
out_lacks "Traceback"
out_lacks "::error::"

# Trivy 0.76.2 was not seen to write this: on debian:bookworm-slim all 215 unfixed findings have the
# key absent, and none an empty string (see the fixtures' README). It is here because it is the one
# input on which truthiness and a null check or a presence check disagree, and the verdict is
# right by truthiness alone.
begin "5b. a finding whose FixedVersion is an empty string is unfixable, not fixable"
run_verdict "${LOCAL}=unfixable-empty-string.json"
exit_is 0
out_has "fixable 0 (OS 0, jar 0), unfixable 1 (OS 1, jar 0)"
out_has "::warning::1 HIGH or CRITICAL finding(s) have no fix available"
out_lacks "::error::"
summary_has "| \`${LOCAL}:latest\` | alpine 3.24.4 | 0 (OS 0, jar 0) | 1 (OS 1, jar 0) |"

# --- 6 to 10: no answer is not nothing found --------------------------------------------------------

begin "6. a missing report is no answer, not zero findings"
run_verdict "${LOCAL}=-"
no_answer "${LOCAL}:latest" "no report was written"

begin "7. a report for a different image is no answer"
run_verdict "${PUBLISHED}=none.json"
no_answer "${PUBLISHED}:latest" "the report is for '${LOCAL}:latest'"

begin "8. a report that shows no operating system is no answer"
run_verdict "${LOCAL}=no-os.json"
no_answer "${LOCAL}:latest" "the scanner found no operating-system packages"

begin "9. a report that shows no Java archives is no answer"
run_verdict "${LOCAL}=no-jars.json"
no_answer "${LOCAL}:latest" "the scanner found no Java archives"

begin "10a. a report that is not valid JSON is no answer, named as that, and not a traceback"
run_verdict "${LOCAL}=malformed.json"
no_answer "${LOCAL}:latest" "the report cannot be read as JSON"

begin "10b. JSON that is not a report object is no answer too"
run_verdict "${LOCAL}=not-an-object.json"
no_answer "${LOCAL}:latest" "the report is JSON but not a report object"

# --- 11: the three images the workflow actually scans --------------------------------------------------

begin "11. one image with a finding, one clean and one with no report: the missing report fails the job and hides neither of the others"
run_verdict "${PUBLISHED}=fixable.json" "${LOCAL}=none.json" "nkap-demo-missing=-"
exit_is 1
out_has "::error::nkap-demo-missing:latest: no report was written"
summary_has "**No answer:** nkap-demo-missing:latest: no report was written"
out_has "FIXABLE   ${PUBLISHED}"
out_has "clean     ${LOCAL}"
summary_has "| \`${PUBLISHED}:latest\` |"
summary_has "| \`${LOCAL}:latest\` |"

# --- what must not change quietly ---------------------------------------------------------------------

begin "the summary file is required: without GITHUB_STEP_SUMMARY the script fails instead of passing unreported"
dir="$(mktemp -d "${scratch}/case.XXXXXX")"; mkdir "${dir}/reports"; cp "${fixtures}/none.json" "${dir}/reports/${LOCAL}.json"
CODE=0
OUT="$(cd "$dir" && env -u GITHUB_STEP_SUMMARY python3 -I "$script" latest "$LOCAL" 2>&1)" || CODE=$?
SUMMARY=""
exit_is 1
out_has "GITHUB_STEP_SUMMARY"

if [ "$failures" -gt 0 ]; then
  echo "${failures} assertion(s) failed"
  exit 1
fi
echo "all image-scan-verdict assertions passed"
