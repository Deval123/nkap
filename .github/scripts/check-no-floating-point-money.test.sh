#!/usr/bin/env bash
set -euo pipefail

# Exercises check-no-floating-point-money.sh against small source trees built in a temporary
# directory, so rule 4's "no double, no float" (issue #285) is an assertion this repository can
# fail on. Run by build.yml on every push and pull request.
#
# The four proofs the issue asks for are the first four assertions: a double added to a service is
# refused; a javadoc that mentions the float account is not; an exempted line passes; a marker
# with no reason is refused. The rest pin the edges: what a lexer must not mistake for code, and
# what a text search would have mistaken for floating point or missed.
#
# The repository's own tree is not asserted here. build.yml runs the script on it in the job that
# runs this test; a test that read the real tree would fail whenever someone added a double, and
# that failure belongs to the check, not to its test.

cd "$(dirname "${BASH_SOURCE[0]}")"
script="$(pwd)/check-no-floating-point-money.sh"

scratch="$(mktemp -d)"
trap 'rm -rf "$scratch"' EXIT

failures=0

# tree <java body for Service.java> -- prints a fresh ROOT whose one main source is that body.
tree() {
  local root
  root="$(mktemp -d "${scratch}/root.XXXXXX")"
  mkdir -p "${root}/svc/src/main/java/p"
  printf '%s\n' "$1" > "${root}/svc/src/main/java/p/Service.java"
  echo "$root"
}

# assert <description> <expected exit code> <expected text in the output, or "" for none> <ROOT>
assert() {
  local description="$1" expected_code="$2" expected_text="$3" root="$4" actual actual_code
  actual_code=0
  actual="$(ROOT="$root" "$script" 2>&1)" || actual_code=$?
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

# assert_not_in_output <description> <text that must NOT be in the output> <ROOT>
assert_absent() {
  local description="$1" unwanted="$2" root="$3" actual
  actual="$(ROOT="$root" "$script" 2>&1)" || true
  if grep -q -F -- "$unwanted" <<< "$actual"; then
    echo "FAIL: ${description}"
    echo "  the output should not contain: ${unwanted}"
    echo "  output: ${actual}"
    failures=$((failures + 1))
  fi
}

# --- the four proofs --------------------------------------------------------------------------

assert "a double field added to a service is refused, naming the file and line" \
  1 "svc/src/main/java/p/Service.java:3: the type 'double'" \
  "$(tree 'class Service {
    private final long amount = 1;
    private double rate;
}')"

assert "a javadoc, comments and literals that merely mention the words are not refused" \
  0 "ok:" \
  "$(tree '/**
 * The id looks like provider:mtn:float:XAF, and the float account is credited. Double-entry
 * bookkeeping, a fast double for the service unit tests, a Float.
 */
class Service {
    // the float account, a double entry
    /* a double, a float, 1000.0 */
    static final String ID = "provider:mtn:float:XAF";
    static final String NOTE = "a double of 0.75f and Math.pow(2, 3)";
    static final String BLOCK = """
        a double and a float and 1.5
        """;
}')"

assert "an exempted line, with a reason, passes" \
  0 "ok:" \
  "$(tree 'class Service {
    private static double gauge() { // allow-floating-point: Micrometer takes a double here, not money
        return 0;
    }
}')"

assert "a marker with no reason is refused" \
  1 "svc/src/main/java/p/Service.java:2: allow-floating-point has no reason" \
  "$(tree 'class Service {
    private double rate; // allow-floating-point:
}')"

# --- a marker is held to what it says ---------------------------------------------------------

assert "a marker with a reason too short to be one is refused" \
  1 "has no reason" \
  "$(tree 'class Service {
    private double rate; // allow-floating-point: ok
}')"

assert "a marker with no colon and no words is refused" \
  1 "has no reason" \
  "$(tree 'class Service {
    private double rate; // allow-floating-point
}')"

assert "a marker on a line with no floating point excuses nothing and is refused" \
  1 "excuses nothing" \
  "$(tree 'class Service {
    private long rate; // allow-floating-point: this line has no floating point on it at all
}')"

assert "a marker on its own line does not excuse the line below it" \
  1 "svc/src/main/java/p/Service.java:3: the type 'double'" \
  "$(tree 'class Service {
    // allow-floating-point: a reason that is long enough, on the wrong line
    private double rate;
}')"

assert "a marker inside a string literal is not a marker" \
  1 "the type 'double'" \
  "$(tree 'class Service {
    private double rate = parse("// allow-floating-point: a reason that is long enough");
}')"

assert "a marker inside a block comment is not a marker" \
  1 "the type 'double'" \
  "$(tree 'class Service {
    private double rate; /* allow-floating-point: a reason that is long enough */
}')"

assert "a marker excuses every use on its own line" \
  0 "ok:" \
  "$(tree 'class Service {
    double a = Double.NaN * 2.5; // allow-floating-point: a reason that is long enough to count
}')"

assert "every finding is reported, not only the first" \
  1 "3 finding(s)" \
  "$(tree 'class Service {
    double a;
    float b;
    long c = (long) Math.pow(10, 2);
}')"

# --- what is floating point without the word, and what only looks like it ---------------------

assert "a floating-point literal is refused though no type is named" \
  1 "the floating-point literal '1000.0'" \
  "$(tree 'class Service {
    long seconds(long ms) { return ms / 1000.0 > 1 ? 1 : 0; }
}')"

assert "a float literal is refused" \
  1 "the floating-point literal '0.75f'" \
  "$(tree 'class Service { Object m = new java.util.LinkedHashMap<>(16, 0.75f, true); }')"

assert "an exponent literal is refused" \
  1 "the floating-point literal '1e3'" \
  "$(tree 'class Service { long a = (long) 1e3; }')"

assert "a literal with a d suffix is refused" \
  1 "the floating-point literal '2d'" \
  "$(tree 'class Service { long a = (long) 2d; }')"

assert "a floating-point Math call is refused" \
  1 "a floating-point Math call 'Math.pow'" \
  "$(tree 'class Service { long a = (long) Math.pow(10, 2); }')"

assert "Math.round is refused" \
  1 "a floating-point Math call 'Math.round'" \
  "$(tree 'class Service { long a = Math.round(x); }')"

assert "Math.floorMod and Math.floorDiv are integer arithmetic and are not refused" \
  0 "ok:" \
  "$(tree 'class Service { int a = Math.floorMod(7, 3); long b = Math.floorDiv(7L, 3L); long c = Math.max(1, 2); }')"

assert "Double.NaN and Double.parseDouble are refused" \
  1 "the type 'Double'" \
  "$(tree 'class Service { Object n = Double.NaN; }')"

assert "a generic argument of Double is refused" \
  1 "the type 'Double'" \
  "$(tree 'class Service { java.util.List<Double> xs; }')"

assert "an array of double is refused" \
  1 "the type 'double'" \
  "$(tree 'class Service { double[] xs; }')"

assert "a cast to float is refused" \
  1 "the type 'float'" \
  "$(tree 'class Service { long a = (float) 1; }')"

assert "a stream of doubles is refused by its API name" \
  1 "a floating-point API 'DoubleStream'" \
  "$(tree 'class Service { Object s = java.util.stream.DoubleStream.empty(); }')"

assert "parseDouble is refused by its API name" \
  1 "a floating-point API 'parseDouble'" \
  "$(tree 'class Service { Object s = Parser.parseDouble("1"); }')"

assert "an identifier that merely contains Float is not refused" \
  0 "ok:" \
  "$(tree 'class Service { long providerFloat; long doubleEntry; long floatAccount; void doubleCheck() {} }')"

assert "a hex integer that ends in d or f, an underscore integer and a long are not refused" \
  0 "ok:" \
  "$(tree 'class Service { int a = 0xFD; int b = 0xDEADBEEF; int c = 100_000; long d = 1_000L; int e = 0x1f; }')"

assert "integers and dotted member access are not floating-point literals" \
  0 "ok:" \
  "$(tree 'class Service { long a = list.get(1).size(); long b = x.y1.z; int c = 10; }')"

# --- what the lexer must get right ------------------------------------------------------------

assert "a double quote inside a char literal does not open a string" \
  1 "svc/src/main/java/p/Service.java:3: the type 'double'" \
  "$(tree 'class Service {
    char q = (char) 34; char d = '\''"'\'';
    double rate;
}')"

assert "an escaped quote inside a string does not end it" \
  0 "ok:" \
  "$(tree 'class Service { String s = "a \" double and a float \" here"; }')"

assert "an escaped backslash before the closing quote does end the string" \
  1 "the type 'double'" \
  "$(tree 'class Service { String s = "a path\\"; double rate; }')"

assert "a // inside a string does not start a comment" \
  1 "the type 'double'" \
  "$(tree 'class Service { String s = "http://x"; double rate; }')"

assert "line numbers survive a multi-line block comment and a text block" \
  1 "svc/src/main/java/p/Service.java:9: the type 'double'" \
  "$(tree '/*
 * one
 */
class Service {
    String t = """
        text
        """;
    int ok;
    double rate;
}')"

assert_absent "a line whose only float is in a comment is not reported" \
  "Service.java:1:" \
  "$(tree '// the float account
class Service { double rate; }')"

assert "an unterminated block comment is refused, not passed" \
  1 "cannot be read as Java" \
  "$(tree 'class Service { /* never closed }')"

assert "an unterminated string is refused, not passed" \
  1 "cannot be read as Java" \
  "$(tree 'class Service { String s = "never closed; }')"

# --- where it looks ---------------------------------------------------------------------------

assert "a double in test sources is not looked at" \
  1 "no src/main/java" \
  "$(root="$(mktemp -d "${scratch}/root.XXXXXX")"; mkdir -p "${root}/svc/src/test/java/p"; printf 'class T { double d; }\n' > "${root}/svc/src/test/java/p/T.java"; echo "$root")"

assert "a double under target/ is not looked at" \
  0 "ok:" \
  "$(root="$(mktemp -d "${scratch}/root.XXXXXX")"; mkdir -p "${root}/svc/src/main/java/p" "${root}/svc/target/generated/src/main/java/p"; printf 'class S { }\n' > "${root}/svc/src/main/java/p/S.java"; printf 'class G { double d; }\n' > "${root}/svc/target/generated/src/main/java/p/G.java"; echo "$root")"

assert "a double in any module's main sources is refused" \
  1 "other/src/main/java/q/Other.java:1: the type 'double'" \
  "$(root="$(mktemp -d "${scratch}/root.XXXXXX")"; mkdir -p "${root}/svc/src/main/java/p" "${root}/other/src/main/java/q"; printf 'class S { }\n' > "${root}/svc/src/main/java/p/S.java"; printf 'class Other { double d; }\n' > "${root}/other/src/main/java/q/Other.java"; echo "$root")"

assert "a tree with no main sources fails rather than passing on nothing" \
  1 "nothing to check" \
  "$(mktemp -d "${scratch}/empty.XXXXXX")"

if [ "$failures" -gt 0 ]; then
  echo "${failures} assertion(s) failed"
  exit 1
fi
echo "all check-no-floating-point-money assertions passed"
