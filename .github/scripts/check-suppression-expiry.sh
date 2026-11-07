#!/usr/bin/env bash
set -euo pipefail

# Refuses a dependency-check suppression that does not expire. Written for issue #282, before the
# first entry went into dependency-check-suppressions.xml: that file says every <suppress>
# carries `until="<date>Z"`, and the plugin's own schema (dependency-suppression.1.4.xsd in
# dependency-check-core) makes `until` OPTIONAL. An entry without it never lapses, nothing
# rejects it, and the rule the file proclaims is a sentence. This is the thing that rejects it.
#
# This is also what makes the expiry load-bearing here, rather than the plugin's own handling
# of `until`. The schema documents that the plugin stops applying an entry on and after its
# date, but nothing in this repository has ever watched it do that. It does not need to: this
# script refuses an entry whose date is today or past, and vulnerability-check.yml runs it
# before every scan, so on the day a suppression lapses the build fails because of this check,
# naming the entry, whatever the plugin does or stops doing in a later version. Nothing in the
# file rests on a behaviour of the plugin that is not tested here.
#
# It is a script, with its own test, for the reason check-release-tag.sh gives for itself: a
# rule this small is wrong by one character, and a check nobody has seen refuse anything is the
# mistake this repository has made before. See check-suppression-expiry.test.sh, which
# build.yml runs on every push and pull request, and which feeds it each shape it must refuse.
#
# Inputs, as environment variables:
#
#   SUPPRESSIONS_FILE  the file to read; defaults to dependency-check-suppressions.xml in the
#                      current directory, which is where the workflow runs it
#   TODAY              the date to compare against, YYYY-MM-DD; defaults to today in UTC. It
#                      exists so the test can say what day it is; nothing in CI sets it.
#
# What it checks, for every <suppress> element in the file:
#
#   1. It has an `until` attribute. A date written only in <notes> or a comment does not count,
#      which is exactly the shape the first project this was copied from had.
#   2. The value is a real calendar date written as YYYY-MM-DDZ, the form the file's header
#      asks for. The Z is required, not tolerated: it is what makes the date mean one day for
#      everyone, and a date the plugin cannot read does not lapse either.
#   3. The date is after today. The schema says a suppression is active only while its date is
#      still in the future, and "on and after" it is no longer applied, so an entry whose date is
#      today has already stopped working; the scan fails on that day, and so does this, with a
#      message that says why instead of a CVE list.
#
# What it deliberately does not check:
#
#   - That the justification is any good, or the entry as narrow as the finding allows. That is
#     the maintainer's review, which is why nothing goes into the file without it.
#   - That a suppression still matches a finding. An entry that matches nothing is harmless
#     and the plugin reports it; this script only looks at when entries end.
#   - An <suppress> nested in a group is held to the same rule as any other: it must carry its
#     own `until`. The schema also lets a group carry one for its members; this script does not
#     accept that, because the rule is stated per entry and a group date is easy to widen
#     without anyone noticing which entries it now covers.
#
# The file is parsed as XML, not searched for text, because its own header contains a
# <suppress> example inside a comment, and a grep would count it as an entry (and then, being
# the example, as one that passes or fails by accident). python3 is on every runner and every
# developer machine this project is built on.
#
# Output: one line on stdout and exit 0 when every entry passes (an empty file passes: it
# holds none). Otherwise one line per failure on stderr, each naming the entry, and exit 1.
# Every failure is reported, not only the first.

file="${SUPPRESSIONS_FILE:-dependency-check-suppressions.xml}"
today="${TODAY:-$(date -u +%F)}"

exec python3 -I - "$file" "$today" <<'PY'
import datetime
import re
import sys
import xml.etree.ElementTree as ET

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
    tree = ET.parse(path)
except FileNotFoundError:
    print("FAIL: " + path + " does not exist, so its suppressions cannot be checked", file=sys.stderr)
    sys.exit(1)
except ET.ParseError as error:
    print("FAIL: " + path + " is not well-formed XML, so its suppressions cannot be checked: " + str(error), file=sys.stderr)
    sys.exit(1)

def local(tag):
    return tag.rsplit("}", 1)[-1]

def describe(position, element):
    """Names an entry by where it is and what it says it is about, so it can be found."""
    # The CVE first: it is what a reader searches the file for.
    order = ("cve", "vulnerabilityName", "packageUrl", "gav", "cpe", "sha1", "filePath")
    found = [(order.index(local(child.tag)), (child.text or "").strip())
             for child in element.iter()
             if local(child.tag) in order and (child.text or "").strip()]
    about = [text for _, text in sorted(found, key=lambda pair: pair[0])]
    return "suppression #" + str(position) + (" (" + "; ".join(about[:3]) + ")" if about else "")

DATE = re.compile(r"^(\d{4}-\d{2}-\d{2})Z$")

entries = [element for element in tree.getroot().iter() if local(element.tag) == "suppress"]
for position, element in enumerate(entries, start=1):
    name = describe(position, element)
    until = element.get("until")
    if until is None:
        fail(name + " has no until attribute, so it never lapses; add until=\"<date>Z\" on the <suppress> element, not only a date in <notes>")
        continue
    match = DATE.match(until.strip())
    if not match:
        fail(name + " has until=\"" + until + "\", which is not a date written YYYY-MM-DDZ")
        continue
    try:
        date = datetime.date.fromisoformat(match.group(1))
    except ValueError:
        fail(name + " has until=\"" + until + "\", which is not a real calendar date")
        continue
    if date <= today:
        fail(name + " expired on " + date.isoformat() + " (today is " + today.isoformat() + "): re-evaluate it, then renew or remove it. The plugin has already stopped applying it")

if failures:
    for line in failures:
        print(line, file=sys.stderr)
    print(str(len(failures)) + " of " + str(len(entries)) + " suppression(s) failed in " + path, file=sys.stderr)
    sys.exit(1)

print("ok: " + str(len(entries)) + " suppression(s) in " + path + ", every one with an until in the future")
PY
