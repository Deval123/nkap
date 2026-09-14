#!/usr/bin/env bash
#
# Runs docs/integration-guide.md for real (issue #88).
#
# "Every curl in it runs in CI" only means something if the thing that runs is the exact text
# a reader sees, not a hand-kept copy of it that can drift. So this script does not repeat the
# guide's commands: it extracts every ```bash fenced block from docs/integration-guide.md, in
# document order, concatenates them, and executes the result as one script. A block the guide
# marks with any other fence (```python, ```text — see the guide's own webhook-verification
# section for why one exists) is deliberately skipped, and the guide says in prose why.
#
# The guide's own blocks carry their own assertions (`[ "$STATUS" = ... ] || { echo FAIL; exit
# 1; }`, the same style examples/demo.sh uses) rather than relying only on this wrapper's
# `set -e` — a reader who copies one block into their own terminal gets the same check a
# missing `set -e` there would otherwise silently skip.
#
# Run against the stack from the quick start:
#
#   docker compose up --build -d
#   ./examples/run-integration-guide.sh
#
set -euo pipefail

cd "$(CDPATH= cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"

GUIDE="docs/integration-guide.md"
[ -f "$GUIDE" ] || { echo "FAIL: $GUIDE not found (run this from a checkout, not a downloaded copy)"; exit 1; }

EXTRACTED="$(mktemp)"
trap 'rm -f "$EXTRACTED"' EXIT

python3 - "$GUIDE" > "$EXTRACTED" <<'PYEOF'
import sys

path = sys.argv[1]
in_block = False
fence = None
blocks = 0

with open(path, encoding="utf-8") as guide:
    for line in guide:
        stripped = line.rstrip("\n")
        if not in_block:
            if stripped.strip() == "```bash":
                in_block = True
                fence = stripped[: len(stripped) - len(stripped.lstrip())] + "```"
                blocks += 1
                print(f"echo '--- guide block {blocks} ---'")
                continue
        else:
            if stripped.strip() == "```":
                in_block = False
                continue
            print(stripped)

if blocks == 0:
    sys.exit("no ```bash blocks found in " + path)
print(f"echo '--- {blocks} guide block(s) executed ---'", file=sys.stderr)
PYEOF

echo "extracted $(grep -c "^echo '--- guide block" "$EXTRACTED") runnable block(s) from $GUIDE"
echo

bash -euo pipefail "$EXTRACTED"
