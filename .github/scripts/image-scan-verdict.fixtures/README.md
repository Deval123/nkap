# Fixtures for `image-scan-verdict.test.sh`

These are Trivy reports, or small edits of them, for the cases `image-scan-verdict.test.sh`
asserts. JSON has no comments, so where they came from, and what each edit is, is written here.

## They are suspect when the Trivy pin moves

Every report below was produced by **Trivy 0.76.2, at
`ghcr.io/aquasecurity/trivy@sha256:8f6bb947dbc528902e11f2f5be52f7b519f6a39d5d148f41166f2040499768fb`**,
the digest `.github/workflows/image-scan.yml` pins (each report also says `"Trivy": {"Version":
"0.76.2"}` itself). A fixture is only worth what its resemblance to what Trivy writes is worth.
**When that pin moves, these fixtures are suspect and should be derived again from reports of the new
digest.** Nothing enforces that. The test passes against the old shape without noticing the scanner
has changed.

## Where the real reports came from

| Source | What it is | How it was scanned |
| --- | --- | --- |
| Published gateway | `ghcr.io/deval123/nkap-gateway:latest`, the 2.2.0 image, from the `image-scan-reports` artifact of workflow run 42403875845 (2026-11-07) | by `image-scan.yml` itself: `image --quiet --scanners vuln --list-all-pkgs --severity HIGH,CRITICAL --format json` |
| Local gateway | `nkap-demo-gateway:latest`, built from `main` with the Java 21.0.13 / Alpine 3.24.4 base (2026-11-07) | the same flags, same digest, run by hand |
| Debian | `debian:bookworm-slim` (2026-11-07) | same digest, `--scanners vuln --format json`, all severities, no `--list-all-pkgs` |

## What each fixture is

The two reports are **trimmed, not rewritten**: re-serialised with two-space indentation, every key
in the order Trivy wrote it, and only list elements dropped. Each result keeps its first three
`Packages`; each result with findings keeps its first `CRITICAL` and its first `HIGH`.

| File | Derived from | What was changed |
| --- | --- | --- |
| `fixable.json` | Published gateway | trimmed as above. Four findings, all with a `FixedVersion` (two in the operating system, two in jars). |
| `none.json` | Local gateway | trimmed as above, and the jar result's `Vulnerabilities` key removed. The operating-system result of that same report has no findings and no such key, so this is the shape Trivy writes for a result with none. |
| `unfixable-only.json` | `none.json` | one finding added to the operating-system result: **copied untouched** from the Debian report (`CVE-2026-53613`, `bsdutils`, `HIGH`, `Status: affected`), which has no `FixedVersion` key. It is a real unfixed finding placed in an Alpine result, which Trivy would not do (its package is a Debian one); the verdict reads only `Severity` and `FixedVersion`. |
| `both.json` | `fixable.json` | the same Debian finding appended to its operating-system result. |
| `unfixable-empty-string.json` | `unfixable-only.json` | that finding's key set by hand to `"FixedVersion": ""`. **A hand edit, and the only fixture that is not a shape Trivy was seen to write.** See the next section. |
| `no-os.json` | `none.json` | `Metadata.OS` removed. |
| `no-jars.json` | `none.json` | the `lang-pkgs` result removed. |
| `malformed.json` | `none.json` | its first 600 bytes, so it ends in the middle of a string. |
| `not-an-object.json` | none | `[]`. |

`ArtifactName` is never edited. The test passes `ghcr.io/deval123/nkap-gateway` for the two
published-gateway fixtures and `nkap-demo-gateway` for the others, so each report names the image it
is asked about, and the wrong-image case works by asking for the other one.

## What was measured about `"FixedVersion": ""`

The test has a case for a finding whose `FixedVersion` is an empty string, because the one
construction in the verdict that tells a missing or empty value from a real version is the truthiness
of `finding.get("FixedVersion")`, and a reader could tighten it to a null check without anything
failing. **That shape was not observed in Trivy's output.** Measured on 2026-11-07 with Trivy 0.76.2
at the pinned digest:

- `debian:bookworm-slim`: 215 findings with no fix, and on all 215 the `FixedVersion` key is
  **absent**. None is an empty string.
- `alpine:3.19`: 10 findings, all with a version.

So the case that matches what Trivy writes is the **key being absent** (`unfixable-only.json`, a
real finding); the empty string (`unfixable-empty-string.json`) is a defensive case, and its fixture
is hand-edited. They guard different refactors of the one line that decides fixability:

- the absent-key case fails if the line indexes the key directly (`finding["FixedVersion"]`, a
  traceback, and no no-answer message), or gives a missing key a truthy default
  (`finding.get("FixedVersion", "unknown")`, which would turn every unfixable finding into a red
  job);
- the empty-string case fails if the line becomes a null check (`is not None`) or a presence check
  (`"FixedVersion" in finding`), the two that a tidy-minded reader might write, since an absent key
  satisfies both of them as "unfixable" and the empty string is the one input on which they and
  truthiness disagree.
