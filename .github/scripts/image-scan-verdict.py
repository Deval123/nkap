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
# has, and also when there is no answer for an image (a report that is missing, names another
# image, shows no operating system or no Java archive). The two kinds of 1 are told apart by what
# is printed: a `::error::` line saying there was no answer, as opposed to the one saying a
# finding has a fix. A finding is fixable when the report gives it a non-empty FixedVersion.
#
# This file is the body that was in the workflow's heredoc, moved without a change to what it
# does.
import collections
import json
import os
import sys

tag, images = sys.argv[1], sys.argv[2:]
summary = ["| image | OS | fixable HIGH/CRITICAL (the gate reads this) | unfixable HIGH/CRITICAL (the gate does not) |",
           "| --- | --- | --- | --- |"]
problems, red = [], False
unfixable_total = 0

for image in images:
    ref, path = image + ":" + tag, "reports/" + image.rsplit("/", 1)[-1] + ".json"
    if not os.path.exists(path):
        problems.append(ref + ": no report was written")
        continue
    with open(path, encoding="utf-8") as handle:
        report = json.load(handle)
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
    # The gate: an always-red job on findings nobody can act on teaches people to
    # ignore it, so only a finding with a fix turns this red.
    red = red or fixable_n > 0
    eosl = " (end of life)" if os_meta.get("EOSL") else ""
    line = (image + " " + os_meta["Family"] + " " + os_meta.get("Name", "?") + eosl + ": " +
            str(os_packages) + " OS packages, " + str(jar_packages) + " jar packages; " +
            "fixable " + str(fixable_n) + " (OS " + str(fixable["os"]) + ", jar " + str(fixable["jar"]) + "), " +
            "unfixable " + str(unfixable_n) + " (OS " + str(unfixable["os"]) + ", jar " + str(unfixable["jar"]) + ")")
    label = "FIXABLE  " if fixable_n else ("UNFIXABLE" if unfixable_n else "clean    ")
    print(label + " " + line)
    summary.append("| `" + ref + "` | " + os_meta["Family"] + " " + os_meta.get("Name", "?") + eosl + " | " +
                   str(fixable_n) + " (OS " + str(fixable["os"]) + ", jar " + str(fixable["jar"]) + ") | " +
                   str(unfixable_n) + " (OS " + str(unfixable["os"]) + ", jar " + str(unfixable["jar"]) + ") |")

with open(os.environ["GITHUB_STEP_SUMMARY"], "a", encoding="utf-8") as out:
    out.write("## Published images, tag `" + tag + "`\n\n" + "\n".join(summary) + "\n")
    if not problems:
        out.write("\nThe job is red only for **fixable** HIGH or CRITICAL findings. **Green does not mean clean:** "
                  "the unfixable column is what is still in the image, and the uploaded report lists every one.\n")
    for problem in problems:
        out.write("\n**No answer:** " + problem + "\n")
for problem in problems:
    print("::error::" + problem)
if problems:
    sys.exit(1)
if red:
    print("::error::at least one image has a HIGH or CRITICAL finding with a fix available; see the report artifact")
    sys.exit(1)
if unfixable_total:
    print("::warning::" + str(unfixable_total) + " HIGH or CRITICAL finding(s) have no fix available. They do not fail this job and are in the report artifact; green is not clean.")
