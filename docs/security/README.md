# Security reviews and scans

| document | what |
|---|---|
| [s-20-auth-review.md](s-20-auth-review.md) | S-20: the JSON sign-in flow and the BFF hand-off (2026-09-30) |
| [findings.md](findings.md) | S-104: the internal review before the external penetration test — every finding, its severity, status and test |
| [pentest-scope.md](pentest-scope.md) | S-104: the testers' packet — environments, accounts, scope, rules of engagement, threat model |

## Running the scans (`make security-*`, S-104)

Every target runs the tools that are installed and says `skipped` for the others, so it works offline. Tools are taken
from `PATH` or from `.cache/security-tools/` (git-ignored), which `make security-tools` fills with pinned versions
(gitleaks, osv-scanner and kube-score from their GitHub releases; semgrep and checkov from PyPI in a virtualenv).
Reports go to `build/security/` (git-ignored).

| target | what | fails the run on |
|---|---|---|
| `make security-scan` | all of the below except DAST | — (each part below) |
| `make security-secrets` | gitleaks over the whole git history with `.gitleaks.toml` (default rules; test fixtures and documented local keys allow-listed, each with its reason) | any finding |
| `make security-deps` | CycloneDX SBOMs of api, auth, bff and worker (`./gradlew cyclonedxDirectBom`), then osv-scanner over them and `web/` + `mobile/` pnpm lockfiles. `OSV_OFFLINE=1` uses a downloaded copy of the OSV database (`OSV_SCANNER_LOCAL_DB_CACHE_DIRECTORY`) instead of the API | any known vulnerability |
| `make security-sast` | semgrep: `p/java`, `p/typescript`, `p/react`, `p/secrets` from the registry, or `SEMGREP_RULES=<checkout of github.com/semgrep/semgrep-rules>` offline | — (report; triage by hand) |
| `make security-iac` | `helm template` of staging and prod, checkov over `infra/terraform` and the rendered chart, kube-score on the chart | — (report) |
| `make security-sbom` | only the SBOMs (`server/<app>/build/reports/cyclonedx-direct/bom.json`) | build errors |
| `make security-dast` | against a running api (`make up`, `local` profile — never a shared environment): `scripts/security/negative-tests.sh` (authentication, object/function authorization, oversized and malformed input, exposure, headers), then OWASP ZAP's API scan of `api-public.yaml` when Docker can run `zaproxy/zap-stable`. `API=http://localhost:8080` by default | any negative test answered unexpectedly |

CI: the `security` workflow (GitHub) and `PIPELINE_PART=security` (GitLab) run `make security-tools security-scan` and,
on request, the DAST job. Both are **manual only**, like every pipeline here ([ci.md](../runbooks/ci.md)).

A finding goes into [findings.md](findings.md) with an id, a severity, its status and the test that guards the fix.
Write open items by impact and location only — no exploit steps for something unfixed.

### Object-level authorization harness

`server/api/src/test/java/ca/northline/security/ObjectLevelAuthorizationTest.java` is part of the normal server build.
It reads `docs/api/openapi/*.yaml` and probes every operation: another business's `{merchantId}`, another tenant's
object ids under the caller's own business, another person's ids on consumer endpoints, and every console operation
without the right staff role or second factor. Regenerating the specs (`make openapi`) is enough to bring a new endpoint
under it. Map a new kind of path parameter to its table in `OBJECTS` so it gets someone else's real id rather than a
random one.
