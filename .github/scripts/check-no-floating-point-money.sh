#!/usr/bin/env bash
set -euo pipefail

# Refuses floating point in the main sources of the reactor. Written for issue #285: rule 4 of
# docs/conventions.md says money is an integer count of minor units, "no `double`, no `float`",
# and until now nothing looked.
#
# Why it is a lexer and not a search. Most occurrences of the words in this repository's main
# sources are prose: `provider:mtn:float:XAF` in AccountId, "double-entry bookkeeping", "the
# float account", "a fast double for the service unit tests". A text search reports all of them,
# gets switched off within a week, and rightly. So the file is first read the way javac reads it:
# comments, string literals, text blocks and char literals are blanked out (line numbers kept),
# and only what is left is looked at.
#
# What it reports, in code that is not a comment or a literal:
#
#   - the keywords `double` and `float`, and the words `Double` and `Float`: a field, a
#     parameter, a return type, a cast, a generic argument, `Double.NaN`, `Double.parseDouble`;
#   - a floating-point literal: `1000.0`, `0.75f`, `1e3`, `2d`, `0x1p3`. This is here because a
#     computation can be floating point without the word ever appearing: `x / 1000.0`;
#   - `Math.pow`, `sqrt`, `round`, `floor`, `ceil` and the rest of the floating-point half of
#     java.lang.Math (and StrictMath). `floorMod` and `floorDiv` are integer and not reported;
#   - a short list of java.util.function, stream and parsing names that exist only to move a
#     double or a float: DoubleStream, ToDoubleFunction, doubleValue, parseDouble, and so on.
#
# What it cannot see, said plainly: a value that is a double by inference and never named. `var x
# = service.rate();` is floating point when rate() returns one, and nothing here knows that, since
# this reads text and does not compile. It also reads only src/main/java; a test may use a double.
# It is a net for the way this rule gets broken in practice, not a proof that no double exists.
#
# The exemption goes on the line it excuses, with a reason, where a reviewer meets it:
#
#       private static double suspenseBalance(...) {   // allow-floating-point: Micrometer's gauge takes a double
#
# The marker is `allow-floating-point:` in a `//` comment on the SAME line as the use, followed by
# why. It is spelled like a word a person can grep for, and reads as a sentence in the code.
# There is no list of exempt files in this script, on purpose: a list in a checker is edited by
# whoever the checker is blocking, and nobody reviewing that diff sees the code it excuses.
#
# A marker is refused when:
#   - it has no reason, or a reason too short to be one (under 10 characters: "ok", "n/a");
#   - it is on a line that has no floating point on it. An exemption that excuses nothing is the
#     one that will excuse the next thing someone writes there.
# A marker inside a string, a text block or a /* block comment */ is not a marker.
#
# It is a script with its own test for the reason check-suppression-expiry.sh gives for itself: a
# check nobody has seen refuse anything is the mistake this repository has made before. See
# check-no-floating-point-money.test.sh, which build.yml runs, and which feeds it each shape it
# must refuse and each it must not.
#
# Inputs, as environment variables:
#
#   ROOT   the directory to scan; defaults to the repository root, two levels above this file.
#          It exists so the test can scan a small tree of its own.
#
# It scans every `src/main/java/**/*.java` under ROOT, skipping `target/`. Finding none is a
# failure, not a pass: a check that passes on nothing is the same as no check.
#
# Output: one line on stdout and exit 0 when nothing is reported. Otherwise one line per finding
# on stderr, each naming file:line and what was found, and exit 1. Every finding is reported,
# not only the first.

root="${ROOT:-$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)}"

exec python3 -I - "$root" <<'PY'
import os
import re
import sys

root = sys.argv[1]

MARKER = "allow-floating-point"
MIN_REASON = 10


class Unreadable(Exception):
    pass


def strip(source):
    """Blanks comments and literals, keeping newlines, and collects each `//` comment by line.

    Returns (code, line_comments). `code` has the same length and line structure as `source`.
    Only `//` comments are kept as text, because only they can carry the marker.
    """
    out = []
    comments = {}
    i, n, line = 0, len(source), 1

    def blank(text):
        return "".join(c if c == "\n" else " " for c in text)

    while i < n:
        two = source[i:i + 2]
        if two == "//":
            j = source.find("\n", i)
            j = n if j == -1 else j
            comments[line] = source[i + 2:j]
            out.append(" " * (j - i))
            i = j
        elif two == "/*":
            j = source.find("*/", i + 2)
            if j == -1:
                raise Unreadable("unterminated /* comment starting on line " + str(line))
            chunk = source[i:j + 2]
            out.append(blank(chunk))
            line += chunk.count("\n")
            i = j + 2
        elif source.startswith('"""', i):
            j = i + 3
            while True:
                j = source.find('"""', j)
                if j == -1:
                    raise Unreadable("unterminated text block starting on line " + str(line))
                backslashes = 0
                k = j - 1
                while k >= 0 and source[k] == "\\":
                    backslashes += 1
                    k -= 1
                if backslashes % 2 == 0:
                    break
                j += 1
            chunk = source[i:j + 3]
            out.append(blank(chunk))
            line += chunk.count("\n")
            i = j + 3
        elif source[i] in "\"'":
            quote = source[i]
            j = i + 1
            while j < n and source[j] != quote:
                if source[j] == "\n":
                    raise Unreadable("unterminated " + ("string" if quote == '"' else "char") +
                                     " literal on line " + str(line))
                j += 2 if source[j] == "\\" else 1
            if j >= n:
                raise Unreadable("unterminated literal starting on line " + str(line))
            out.append(" " * (j + 1 - i))
            i = j + 1
        else:
            if source[i] == "\n":
                line += 1
            out.append(source[i])
            i += 1
    return "".join(out), comments


MATH = r"(?:Strict)?Math\s*\.\s*(?:pow|sqrt|cbrt|exp|expm1|log|log10|log1p|sin|cos|tan|asin|acos|atan|atan2|sinh|cosh|tanh|hypot|ceil|floor|rint|round|random|signum|toRadians|toDegrees|ulp)\b"
API = (r"\b(?:DoubleStream|DoubleSupplier|DoubleFunction|DoubleUnaryOperator|DoubleBinaryOperator|"
       r"DoublePredicate|DoubleConsumer|ToDoubleFunction|ToDoubleBiFunction|DoubleAdder|"
       r"DoubleAccumulator|DoubleSummaryStatistics|DoubleBuffer|FloatBuffer|parseDouble|parseFloat|"
       r"doubleValue|floatValue|asDouble|getDouble|nextDouble|mapToDouble|readDouble|writeDouble)\b")
HEX_FLOAT = r"0[xX][0-9a-fA-F_]*\.?[0-9a-fA-F_]*[pP][+-]?\d+[fFdD]?"
DECIMAL_FLOAT = (r"(?:\d[\d_]*\.\d[\d_]*(?:[eE][+-]?\d+)?[fFdD]?"
                 r"|\.\d[\d_]*(?:[eE][+-]?\d+)?[fFdD]?"
                 r"|\d[\d_]*[eE][+-]?\d+[fFdD]?"
                 r"|\d[\d_]*[fFdD])")
# Not preceded by a word character, `.` or `$`, so `v1.5` or `a.b` never reads as a literal, and
# `0xFD` is a hex integer: it is consumed whole by the first alternative of HEXINT below.
LITERAL = re.compile(r"(?<![\w.$])(?:" + HEX_FLOAT + r"|0[xX][0-9a-fA-F_]+[lL]?|" + DECIMAL_FLOAT + r")(?![\w.])")
KINDS = [
    ("the type", re.compile(r"\b(?:double|float|Double|Float)\b")),
    ("a floating-point Math call", re.compile(MATH)),
    ("a floating-point API", re.compile(API)),
]


def findings_on(code_line):
    """What this line of code uses, as (kind, text) pairs."""
    found = []
    for kind, pattern in KINDS:
        for match in pattern.finditer(code_line):
            found.append((kind, re.sub(r"\s+", "", match.group(0))))
    for match in LITERAL.finditer(code_line):
        text = match.group(0)
        if re.match(r"0[xX][0-9a-fA-F_]+[lL]?$", text):
            continue  # a hex integer, which merely looks like one when it ends in d or f
        found.append(("the floating-point literal", text))
    return found


def reason_of(comment):
    """The reason after the marker in a `//` comment, or None when it is not a marker."""
    at = comment.find(MARKER)
    if at == -1:
        return None
    rest = comment[at + len(MARKER):]
    return rest.lstrip(" \t:-").strip()


failures = []
scanned = 0

for directory, subdirs, names in os.walk(root):
    subdirs[:] = sorted(d for d in subdirs if d not in ("target", "node_modules", ".git"))
    normal = directory.replace(os.sep, "/")
    if not (normal.endswith("/src/main/java") or "/src/main/java/" in normal):
        continue
    for name in sorted(names):
        if not name.endswith(".java"):
            continue
        path = os.path.join(directory, name)
        shown = os.path.relpath(path, root).replace(os.sep, "/")
        scanned += 1
        with open(path, encoding="utf-8") as handle:
            source = handle.read()
        try:
            code, comments = strip(source)
        except Unreadable as error:
            failures.append(shown + ": cannot be read as Java, so it cannot be checked: " + str(error))
            continue
        code_lines = code.split("\n")
        for number, comment in sorted(comments.items()):
            reason = reason_of(comment)
            has_code = bool(code_lines[number - 1].strip()) if number <= len(code_lines) else False
            used = bool(findings_on(code_lines[number - 1])) if has_code else False
            if reason is None:
                continue
            if len(reason) < MIN_REASON:
                failures.append(shown + ":" + str(number) + ": " + MARKER + " has no reason (or too short a one to be a reason); "
                                "write why this line needs floating point after the colon")
            elif not used:
                failures.append(shown + ":" + str(number) + ": " + MARKER + " excuses nothing, since there is no floating point "
                                "on this line; remove it, or move it to the line it is about")
        for number, code_line in enumerate(code_lines, start=1):
            found = findings_on(code_line)
            if not found:
                continue
            reason = reason_of(comments.get(number, ""))
            if reason is not None and len(reason) >= MIN_REASON:
                continue
            if reason is not None:
                continue  # already reported above as a marker without a reason
            what = "; ".join(sorted({kind + " '" + text + "'" for kind, text in found}))
            failures.append(shown + ":" + str(number) + ": " + what + " in main code. Money is an integer count of minor "
                            "units (docs/conventions.md, rule 4). If this is not money, say why on the line with // " +
                            MARKER + ": <reason>")

if scanned == 0:
    print("FAIL: no src/main/java/**/*.java under " + root + ", so there is nothing to check; a check that passes on nothing is no check",
          file=sys.stderr)
    sys.exit(1)

if failures:
    for line in failures:
        print("FAIL: " + line, file=sys.stderr)
    print(str(len(failures)) + " finding(s) in " + str(scanned) + " main source file(s)", file=sys.stderr)
    sys.exit(1)

print("ok: " + str(scanned) + " main source file(s), no floating point outside a marked line")
PY
