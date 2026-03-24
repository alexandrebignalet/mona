# Mona — CI/CD Specification

GitHub Actions pipelines for build validation, golden test execution, and production deployment.

---

## 1. Overview

Three workflows, each with a distinct trigger and purpose:

| Workflow | File | Trigger | Purpose |
|----------|------|---------|---------|
| **CI** | `ci.yml` | Push to `main`, all PRs | Build, test, lint — gate merges |
| **Golden Tests** | `golden-tests.yml` | Manual (`workflow_dispatch`) | Run LLM golden tests against real Claude API |
| **Deploy** | `deploy.yml` | Auto on CI success (`main`), manual fallback | Build & deploy to Fly.io production |

---

## 2. CI Workflow (`ci.yml`)

### Trigger

```yaml
on:
  push:
    branches: [main]
  pull_request:
    branches: [main]
```

### Environment

- **Runner:** `ubuntu-latest`
- **JDK:** Temurin 21 (via `actions/setup-java@v4`)
- **Gradle:** Wrapper-based, with `actions/cache@v4` on `~/.gradle/caches` and `~/.gradle/wrapper`

### Steps

1. **Checkout** — `actions/checkout@v4`
2. **Setup JDK 21** — `actions/setup-java@v4` with `distribution: temurin`, `java-version: 21`
3. **Cache Gradle** — Cache `~/.gradle/caches` and `~/.gradle/wrapper`, keyed on `build.gradle.kts` hash
4. **Build & Test** — `./gradlew build`
5. **Lint** — `./gradlew ktlintCheck`
6. **Upload test reports** — `actions/upload-artifact@v4` on `build/reports/tests/` (always, even on failure)

### Notes

- Golden tests are excluded automatically (they skip when `ANTHROPIC_API_KEY` is not set).
- No secrets required for this workflow.
- The `build` task already includes compilation and test execution.

---

## 3. Golden Tests Workflow (`golden-tests.yml`)

### Trigger

```yaml
on:
  workflow_dispatch:
    inputs:
      categories:
        description: "Comma-separated golden test categories (blank = all)"
        required: false
        default: ""
```

### Why Manual Only

Golden tests hit the real Claude API. Each run costs money and is non-deterministic. Running them on every push would be wasteful and produce flaky CI. Instead:

- Developers trigger manually before merging prompt/tool changes.
- Can optionally filter by category to run a subset.

### Environment

- **Runner:** `ubuntu-latest`
- **JDK:** Temurin 21
- **Secret:** `ANTHROPIC_API_KEY` (repository secret)

### Steps

1. **Checkout** — `actions/checkout@v4`
2. **Setup JDK 21** — Same as CI
3. **Cache Gradle** — Same as CI
4. **Run golden tests** — Command depends on category input:
   - All: `./gradlew test --tests "*.GoldenParsingTest" --tests "*.GoldenContextTest"`
   - Filtered: `./gradlew test --tests "*.GoldenParsingTest" --tests "*.GoldenContextTest" -Dgolden.categories=${{ inputs.categories }}`
5. **Upload test reports** — Same as CI (always)

### Secrets

| Secret | Source | Purpose |
|--------|--------|---------|
| `ANTHROPIC_API_KEY` | Anthropic Console | Authenticate Claude API calls during golden tests |

---

## 4. Deploy Workflow (`deploy.yml`)

### Trigger

```yaml
on:
  workflow_run:
    workflows: [CI]
    branches: [main]
    types: [completed]
  workflow_dispatch:
    inputs:
      confirm:
        description: "Type 'deploy' to confirm production deployment"
        required: true
```

### Trigger Logic

The workflow runs automatically when the CI workflow completes successfully on `main` (`workflow_run.conclusion == 'success'`). The `workflow_dispatch` fallback allows manual deploys; the confirmation guard (`inputs.confirm == 'deploy'`) prevents accidental dispatch in that path.

### Environment

- **Runner:** `ubuntu-latest`
- **Secret:** `FLY_API_TOKEN` (repository secret)

### Steps

1. **Validate confirmation** — Fail if `inputs.confirm` is not `deploy`
2. **Checkout** — `actions/checkout@v4`
3. **Setup Fly CLI** — `superfly/flyctl-actions/setup-flyctl@master`
4. **Deploy** — `flyctl deploy --remote-only`
   - `--remote-only`: Builds the Docker image on Fly.io's remote builders (no need for local Docker setup on the runner)
   - Fly.io uses the existing `Dockerfile` and `fly.toml`

### Secrets

| Secret | Source | Purpose |
|--------|--------|---------|
| `FLY_API_TOKEN` | `flyctl tokens create deploy` | Authenticate Fly.io deployments |

### Post-Deploy Verification

The workflow waits for the health check to pass after deploy. Fly.io's built-in health check (`GET /health` every 15s) handles machine-level verification. The workflow adds an explicit check:

5. **Health check** — `flyctl status --app mona` and verify the machine is in `started` state
6. **Annotate** — Add a GitHub deployment status annotation with the deployed commit SHA

---

## 5. Repository Secrets

Configure these in GitHub → Settings → Secrets and variables → Actions:

| Secret | Required By | How to Generate |
|--------|-------------|-----------------|
| `ANTHROPIC_API_KEY` | Golden Tests | Anthropic Console → API Keys |
| `FLY_API_TOKEN` | Deploy | `flyctl tokens create deploy -a mona` |

---

## 6. Branch Protection (Recommended)

Configure on `main`:

- **Require status checks to pass:** `ci` (the CI workflow job name)
- **Require branches to be up to date before merging:** Yes
- **Do not require golden tests** — they are manual and should not gate merges

---

## 7. File Structure

```
.github/
└── workflows/
    ├── ci.yml              # Build + test + lint on every push/PR
    ├── golden-tests.yml    # Manual: LLM golden tests with real API
    └── deploy.yml          # Manual: Deploy to Fly.io production
```

---

## 8. Implementation Notes

- **No Docker in CI.** The CI workflow builds with Gradle directly — faster than building the Docker image. The Docker image is only built during deploy (on Fly.io's remote builders).
- **Gradle daemon disabled in CI.** Use `--no-daemon` flag in all Gradle invocations to avoid memory issues on runners.
- **Test report artifacts retained for 7 days.** Default retention, sufficient for debugging.
- **No environment promotion.** V1 has a single environment (production). No staging. The manual trigger + confirmation guard is the safety mechanism.
