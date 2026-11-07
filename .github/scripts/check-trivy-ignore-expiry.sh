#!/usr/bin/env bash
set -euo pipefail

# Refuses an entry in trivy-ignore.yaml that does not say why, or does not expire. Written for
# issue #301, in the same change that added the file. image-scan.yml said it would be: "a format
# that can express a date does not enforce it", and Trivy's format makes `expired_at` optional, so
# an entry without one is ignored for ever and nothing in Trivy objects. This is the thing that
# objects. It is the counterpart of check-suppression-expiry.sh, for the same reason and to the
# same standard as dependency-check-suppressions.xml.
#
# Trivy does apply `expired_at` itself: tried with the pinned Trivy against a published image, an
# entry dated 2020 stopped being applied and its finding came back. So on the day an entry lapses
# the scan turns red by itself. This script makes sure that day exists, and, like its
# counterpart, it refuses an entry whose date is today or past, naming it, so the failure says why
# instead of showing a list of CVEs.
#
# Inputs, as environment variables:
#
#   IGNORE_FILE  the file to read; defaults to trivy-ignore.yaml in the current directory, which
#                is where the workflow runs it
#   TODAY        the date to compare against, YYYY-MM-DD; defaults to today in UTC. It exists so
#                the test can say what day it is; nothing in CI sets it.
#
# What it checks, for every entry in the file (under any top-level key: vulnerabilities,
# misconfigurations, secrets, licenses):
#
#   1. It has an `id`.
#   2. It has a `statement` of at least 40 characters. That is the reason, the evidence and the
#      owner, and the length is not a judgment of whether it is any good (that stays the
#      maintainer's review); it is the difference between a sentence and "ok".
#   3. It has an `expired_at`, written YYYY-MM-DD and a real calendar date.
#   4. That date is after today. On the day itself the entry is treated as already lapsed, as the
#      dependency-check counterpart does; whether Trivy still applies it on that day was not tried.
#
# What it deliberately does not check:
#
#   - That the statement is true, that the evidence holds, or that the entry is as narrow as the
#     finding allows. A `purls` scope is how an entry is kept narrow, and whether one is there is
#     review.
#   - That an entry still matches a finding. An entry that matches nothing is harmless.
#
# The file is read as text, not as YAML, because the repository's Python has no YAML module and
# this must run on a bare runner. That is only safe if it refuses what it does not understand, so
# it accepts exactly one shape, the block style the file is written in:
#
#     <section>:
#       - id: CVE-...
#         <key>: <value>          (a value may be a block scalar: `>-` then more-indented lines)
#         <key>:
#           - <list item>
#
# Anything else (a flow-style list, a tab, an odd indentation, a line at no known level) is a
# failure that says it cannot read the file, and never a pass.
#
# Output: one line on stdout and exit 0 when every entry passes (a file with no entries passes: it
# holds none). Otherwise one line per failure on stderr, each naming the entry, and exit 1. Every
# failure is reported, not only the first.

file="${IGNORE_FILE:-trivy-ignore.yaml}"
today="${TODAY:-$(date -u +%F)}"

exec python3 -I - "$file" "$today" <<'PY'
import datetime
import re
import sys

path, today_text = sys.argv[1], sys.argv[2]

failures = []

def fail(message):
    failures.append("FAIL: " + message)

try:
    today = datetime.date.fromisoformat(today_text)
except ValueError:
    print("TODAY must be YYYY-MM-DD, got " + repr(today_text), file=sys.stderr)
    sys.exit(1)

try:
    with open(path, encoding="utf-8") as handle:
        lines = handle.read().split("\n")
except FileNotFoundError:
    print("FAIL: " + path + " does not exist, so its entries cannot be checked", file=sys.stderr)
    sys.exit(1)

BLOCK = ("|", "|-", "|+", ">", ">-", ">+")
entries = []          # each: {"line": n, "keys": {name: text}}
unreadable = []
section = None
entry = None
last_key = None       # the key at indent 4 that deeper lines belong to
block_scalar = False

def cannot(number, why):
    unreadable.append("line " + str(number) + ": " + why)

for number, raw in enumerate(lines, start=1):
    if "\t" in raw:
        cannot(number, "a tab; this file is indented with spaces")
        continue
    stripped = raw.strip()
    indent = len(raw) - len(raw.lstrip(" "))
    if stripped == "" or stripped.startswith("#"):
        # A blank line belongs to a block scalar; a comment line ends nothing.
        continue
    if indent > 4 and last_key is not None:
        # Deeper than a key of an entry: the continuation of a block scalar or a list.
        if block_scalar:
            entry["keys"][last_key] = (entry["keys"][last_key] + " " + stripped).strip()
        continue
    block_scalar = False
    if indent == 0:
        if not re.fullmatch(r"[A-Za-z_]+:", stripped):
            cannot(number, "expected a section name such as `vulnerabilities:`, got " + repr(stripped))
            continue
        section, entry, last_key = stripped[:-1], None, None
    elif indent == 2:
        m = re.fullmatch(r"- ([A-Za-z_]+):\s*(.*)", stripped)
        if not m or section is None:
            cannot(number, "expected `- <key>: <value>` opening an entry, got " + repr(stripped))
            continue
        entry = {"line": number, "keys": {}}
        entries.append(entry)
        key, value = m.group(1), m.group(2)
        entry["keys"][key] = "" if value in BLOCK else value
        last_key, block_scalar = key, value in BLOCK
    elif indent == 4:
        m = re.fullmatch(r"([A-Za-z_]+):\s*(.*)", stripped)
        if not m or entry is None:
            cannot(number, "expected `<key>: <value>` inside an entry, got " + repr(stripped))
            continue
        key, value = m.group(1), m.group(2)
        entry["keys"][key] = "" if value in BLOCK else value
        last_key, block_scalar = key, value in BLOCK
    else:
        cannot(number, "indented by " + str(indent) + " spaces, which is not a level this check reads")

if unreadable:
    for why in unreadable:
        print("FAIL: " + path + " cannot be read, so its entries cannot be checked (" + why + ")", file=sys.stderr)
    print(str(len(unreadable)) + " line(s) in " + path + " were not understood", file=sys.stderr)
    sys.exit(1)

def unquote(text):
    text = text.strip()
    if len(text) >= 2 and text[0] == text[-1] and text[0] in "\"'":
        return text[1:-1]
    return text

for position, e in enumerate(entries, start=1):
    keys = e["keys"]
    name = "entry #" + str(position) + " (line " + str(e["line"]) + (", " + unquote(keys["id"]) if keys.get("id") else "") + ")"
    if not unquote(keys.get("id", "")):
        fail(name + " has no id")
    statement = unquote(keys.get("statement", ""))
    if "statement" not in keys or len(statement) < 40:
        fail(name + " has no statement, or one too short to be a reason (under 40 characters); say why, with the evidence and who owns ending it")
    if "expired_at" not in keys or not unquote(keys["expired_at"]):
        fail(name + " has no expired_at, so it never lapses; add expired_at: YYYY-MM-DD")
        continue
    value = unquote(keys["expired_at"])
    if not re.fullmatch(r"\d{4}-\d{2}-\d{2}", value):
        fail(name + " has expired_at " + repr(value) + ", which is not a date written YYYY-MM-DD")
        continue
    try:
        date = datetime.date.fromisoformat(value)
    except ValueError:
        fail(name + " has expired_at " + repr(value) + ", which is not a real calendar date")
        continue
    if date <= today:
        fail(name + " lapsed on " + date.isoformat() + " (today is " + today.isoformat() + "): decide again, then renew it with a pull request that says why, or remove it. Trivy applies an entry only until its date")

if failures:
    for line in failures:
        print(line, file=sys.stderr)
    print(str(len(failures)) + " failure(s) in " + str(len(entries)) + " entr" + ("y" if len(entries) == 1 else "ies") + " of " + path, file=sys.stderr)
    sys.exit(1)

print("ok: " + str(len(entries)) + " entr" + ("y" if len(entries) == 1 else "ies") + " in " + path + ", every one with a statement and an expired_at in the future")
PY
