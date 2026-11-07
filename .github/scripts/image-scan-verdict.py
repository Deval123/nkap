#!/usr/bin/env python3
# The verdict of the image scan (issue #288, narrowed in #296; extracted from image-scan.yml
# for #297 so that it can have a test). It reads the Trivy reports the workflow has just written
# and decides what the job's result is. Which finding turns the job red, which one is only
# reported, and what counts as no answer at all are all decided here and nowhere else; read
# image-scan.yml's header for why. The test is image-scan-verdict.test.sh.
#
# Usage, as the workflow runs it, from the repository root:
#
#     python3 -I .github/scripts/image-scan-verdict.py <tag> <image> [<image> ...]
#
# where each <image> is a full name without a tag, such as ghcr.io/deval123/nkap-gateway. For
# each image it reads `reports/<last path segment of the image>.json`, relative to the current
# directory, which is where the workflow's scan loop writes it.
#
# Environment: GITHUB_STEP_SUMMARY, the file the job summary is appended to. It is REQUIRED, and
# the script fails with a KeyError when it is absent. That is deliberate: a verdict that quietly
# skipped its summary when the variable was missing could pass without reporting anything.
#
# Exit status: 0 when no image has a HIGH or CRITICAL finding with a fix available; 1 when one
# has, and also when there is no answer for an image (a report that is missing, cannot be read
# as JSON, names another image, or shows no operating system or no Java archive). The two kinds
# of 1 are told apart by what is printed: a `::error::` line naming the image and why there was
# no answer, as opposed to the one saying a finding has a fix. A finding is fixable when the
# report gives it a non-empty FixedVersion.
#
# This was the body of the workflow's heredoc, moved without a change in behaviour. Three things
# were then changed on purpose, and each has a case in the test: the "green does not mean clean"
# line is written only when something unfixable is in the images, a report that cannot be read as
# JSON is reported as no answer for its image instead of ending the run in a traceback, and
# findings that trivy-ignore.yaml told the scanner to leave out are counted, named and reported,
# never dropped, and never make the summary say there are no findings.

import collections
import json
import os
import sys

tag, images = sys.argv[1], sys.argv[2:]
summary = ["| image | OS | fixable HIGH/CRITICAL (the gate reads this) | unfixable HIGH/CRITICAL (the gate does not) | ignored by trivy-ignore.yaml (accepted, dated) |",
           "| --- | --- | --- | --- | --- |"]
problems, red = [], False
unfixable_total = 0
ignored_all = []

for image in images:
    ref, path = image + ":" + tag, "reports/" + image.rsplit("/", 1)[-1] + ".json"
    if not os.path.exists(path):
        problems.append(ref + ": no report was written")
        continue
    # A report that cannot be read is no answer, like a report that is missing: it is reported
    # as such per image, not left to end the whole run in a traceback that says nothing about
    # which image or why.
    try:
        with open(path, encoding="utf-8") as handle:
            report = json.load(handle)
    except (json.JSONDecodeError, UnicodeDecodeError) as error:
        problems.append(ref + ": the report cannot be read as JSON (" + str(error) + ")")
        continue
    if not isinstance(report, dict):
        problems.append(ref + ": the report is JSON but not a report object (it is a " + type(report).__name__ + ")")
        continue
    if report.get("ArtifactName") != ref:
        problems.append(ref + ": the report is for " + repr(report.get("ArtifactName")))
        continue
    os_meta = (report.get("Metadata") or {}).get("OS") or {}
    results = report.get("Results") or []
    os_results = [r for r in results if r.get("Class") == "os-pkgs"]
    jar_results = [r for r in results if r.get("Class") == "lang-pkgs" and r.get("Type") == "jar"]
    os_packages = sum(len(r.get("Packages") or []) for r in os_results)
    jar_packages = sum(len(r.get("Packages") or []) for r in jar_results)
    # A scan that read no operating system, or no Java archive, looked at nothing it was
    # asked about. That is a failure to answer, whatever the finding count says.
    if not os_meta.get("Family") or os_packages == 0:
        problems.append(ref + ": the scanner found no operating-system packages (OS " + repr(os_meta) + ")")
        continue
    if jar_packages == 0:
        problems.append(ref + ": the scanner found no Java archives, but the image runs a Java application")
        continue
    # One report, two readings of it. A finding is fixable when the report names a
    # fixed version; one with none (no fix yet, will not be fixed, fix deferred) is
    # not. Both are counted from the same report the artifact carries.
    fixable = {"os": 0, "jar": 0}
    unfixable = {"os": 0, "jar": 0}
    for kind, group in (("os", os_results), ("jar", jar_results)):
        for result in group:
            for finding in result.get("Vulnerabilities") or []:
                bucket = fixable if finding.get("FixedVersion") else unfixable
                bucket[kind] += 1
    fixable_n, unfixable_n = sum(fixable.values()), sum(unfixable.values())
    unfixable_total += unfixable_n
    # What trivy-ignore.yaml told the scanner to leave out. The scan runs with --show-suppressed,
    # which moves an ignored finding out of "Vulnerabilities" and into "ExperimentalModifiedFindings"
    # instead of dropping it, so it can be counted and named here and is in the report itself. An
    # ignored finding does not turn the job red, and it is never left out of what is said about it.
    ignored = [m.get("Finding", {}).get("VulnerabilityID", "?")
               for result in os_results + jar_results
               for m in (result.get("ExperimentalModifiedFindings") or [])
               if m.get("Type") == "vulnerability"]
    ignored_all += ignored
    # The gate: an always-red job on findings nobody can act on teaches people to
    # ignore it, so only a finding with a fix turns this red.
    red = red or fixable_n > 0
    eosl = " (end of life)" if os_meta.get("EOSL") else ""
    line = (image + " " + os_meta["Family"] + " " + os_meta.get("Name", "?") + eosl + ": " +
            str(os_packages) + " OS packages, " + str(jar_packages) + " jar packages; " +
            "fixable " + str(fixable_n) + " (OS " + str(fixable["os"]) + ", jar " + str(fixable["jar"]) + "), " +
            "unfixable " + str(unfixable_n) + " (OS " + str(unfixable["os"]) + ", jar " + str(unfixable["jar"]) + "), " +
            "ignored " + str(len(ignored)) + (" (" + ", ".join(sorted(ignored)) + ")" if ignored else ""))
    label = ("FIXABLE  " if fixable_n else ("UNFIXABLE" if unfixable_n else ("IGNORED  " if ignored else "clean    ")))
    print(label + " " + line)
    summary.append("| `" + ref + "` | " + os_meta["Family"] + " " + os_meta.get("Name", "?") + eosl + " | " +
                   str(fixable_n) + " (OS " + str(fixable["os"]) + ", jar " + str(fixable["jar"]) + ") | " +
                   str(unfixable_n) + " (OS " + str(unfixable["os"]) + ", jar " + str(unfixable["jar"]) + ") | " +
                   (str(len(ignored)) + " (" + ", ".join(sorted(ignored)) + ")" if ignored else "0") + " |")

with open(os.environ["GITHUB_STEP_SUMMARY"], "a", encoding="utf-8") as out:
    out.write("## Published images, tag `" + tag + "`\n\n" + "\n".join(summary) + "\n")
    if not problems:
        if unfixable_total:
            # The caution is written only where it applies. A run with nothing unfixable in it has
            # no use for a warning about unfixable findings, and a caution that is repeated where
            # it does not apply is one people learn to skip.
            out.write("\nThe job is red only for **fixable** HIGH or CRITICAL findings. **Green does not mean clean:** "
                      "the unfixable column is what is still in the image, and the uploaded report lists every one.\n")
        if ignored_all:
            out.write("\n**" + str(len(ignored_all)) + " HIGH or CRITICAL finding(s) are ignored by `trivy-ignore.yaml`:** "
                      + ", ".join(sorted(set(ignored_all))) + ". Accepted, not fixed: each entry carries its reason and an "
                      "expiry, after which Trivy stops ignoring it and this job turns red. They are in the uploaded report.\n")
        if not red and not unfixable_total and not ignored_all:
            out.write("\nThere are no HIGH or CRITICAL findings in any image.\n")
    for problem in problems:
        out.write("\n**No answer:** " + problem + "\n")
for problem in problems:
    print("::error::" + problem)
if problems:
    sys.exit(1)
if red:
    print("::error::at least one image has a HIGH or CRITICAL finding with a fix available; see the report artifact")
    sys.exit(1)
if ignored_all:
    print("::warning::" + str(len(ignored_all)) + " HIGH or CRITICAL finding(s) are ignored by trivy-ignore.yaml (" + ", ".join(sorted(set(ignored_all))) + "). Accepted, not fixed, until each entry's expiry; they are in the report artifact.")
if unfixable_total:
    print("::warning::" + str(unfixable_total) + " HIGH or CRITICAL finding(s) have no fix available. They do not fail this job and are in the report artifact; green is not clean.")
