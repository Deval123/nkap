#!/usr/bin/env bash
set -euo pipefail

# Issue #123: a volume is namespaced "<project>_<volume>" by Compose, so two files that
# resolve to the same project name can produce the exact same volume -- which is what
# happened when compose.yaml and nkap-standalone.compose.yaml both said `name: nkap`.
# `docker compose config` only resolves and merges the configuration; it starts nothing and
# needs no running daemon, so this needs no Docker setup beyond the CLI itself.
#
# nkap-standalone.compose.yaml's required variables ("${VAR:?message}") must be set for it
# to parse at all. These are placeholders so `config` can run -- not credentials, and
# nothing here builds or starts a container that would use them.
export NKAP_VERSION=0.0.0-compose-project-names-check
export NKAP_DB_PASSWORD=compose-project-names-check
export NKAP_MERCHANT_ID=compose-project-names-check

cd "$(dirname "${BASH_SOURCE[0]}")/../.."

demo_name="$(docker compose -f compose.yaml config --format json | jq -r .name)"
standalone_name="$(docker compose -f nkap-standalone.compose.yaml config --format json | jq -r .name)"

echo "compose.yaml                 -> project \"${demo_name}\""
echo "nkap-standalone.compose.yaml -> project \"${standalone_name}\""

if [ "$demo_name" = "$standalone_name" ]; then
  echo "FAIL: both compose files resolve to the same project (\"${demo_name}\")." \
       "A volume declared under the same name in each file would then be the exact same" \
       "volume -- see PLAN.md and issue #123."
  exit 1
fi

echo "compose.yaml and nkap-standalone.compose.yaml resolve to different projects --" \
     "a volume either declares can never collide with the other's."
