#!/usr/bin/env bash
set -euo pipefail

# Given an event name and either a ref name (a real tag push) or a test version
# (workflow_dispatch), computes the version to publish and whether "latest" should follow
# it. Extracted out of release.yml (issue #103) because the decision it replaces --
# three lines of shell keying "latest" off the event name alone -- is exactly the size of
# thing that is wrong by one character and is normally only found by an actual release. As
# its own script it can be tested directly; see compute-release-tags.test.sh, which
# build.yml runs on every push and pull request.
#
# Inputs, as environment variables -- named after the GitHub Actions context fields they
# come from, so the workflow passes them through unchanged rather than renaming anything:
#
#   EVENT_NAME    "push" or "workflow_dispatch"
#   REF_NAME      the tag name, e.g. "v1.0.0" -- read only when EVENT_NAME=push
#   TEST_VERSION  the version to use -- read only when EVENT_NAME=workflow_dispatch
#
# Output, on stdout, exactly two lines, in $GITHUB_OUTPUT's own "key=value" format so a
# workflow step can pipe this script's output straight into it:
#
#   version=<the version to publish, without a leading "v">
#   latest=<true|false>
#
# The rule (issue #103): latest is true only for a real tag push whose version has no
# pre-release suffix -- a plain "X.Y.Z", never "X.Y.Z-rc1", "X.Y.Z-beta" or the like, and
# never a workflow_dispatch run, however its version is spelled. A pre-release tag is not a
# release, and workflow_dispatch is a rehearsal, not one either.

event_name="${EVENT_NAME:?EVENT_NAME must be set}"

if [ "$event_name" = "workflow_dispatch" ]; then
  version="${TEST_VERSION:?TEST_VERSION must be set when EVENT_NAME=workflow_dispatch}"
else
  ref_name="${REF_NAME:?REF_NAME must be set when EVENT_NAME=push}"
  # Docker image tags are conventionally unprefixed ("1.0.0"), even though the git tag
  # itself carries the "v" CONTRIBUTING.md asks for.
  version="${ref_name#v}"
fi

latest=false
if [ "$event_name" = "push" ] && [[ "$version" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
  latest=true
fi

echo "version=${version}"
echo "latest=${latest}"
