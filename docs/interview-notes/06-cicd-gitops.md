# 06 — CI/CD, GitOps & DevSecOps (Interview Notes)

> Source of truth in this repo:
> - `.github/workflows/shopflow.yml` — ShopFlow pipeline: **lint** (yamllint, hadolint, gitleaks, tflint + AWS ruleset, ansible-lint) ∥ test ∥ validate manifests/config (kubeconform incl. Rollouts/Kyverno CRDs, `kyverno apply`, `promtool check` + `promtool test rules`, `amtool check-config`, helm lint) ∥ Terraform fmt/validate → build 4 images + Trivy scan + CycloneDX SBOM → push to GHCR (main only) + keyless cosign signature → `k6 inspect` on the load-test script → `deploy-manifests` pins the SHA into the prod overlay
> - Shift-left config at the repo root: `.yamllint.yaml`, `.hadolint.yaml`, `.gitleaks.toml` (allowlist), `.pre-commit-config.yaml` (the same linters before the commit exists), `Makefile` (`make help`, `make lint`, `make validate` = what CI runs)
> - `shopflow/k8s/rollouts/` — Argo Rollouts: `order-service-canary.yaml`, `inventory-service-bluegreen.yaml`, `analysis-template.yaml`, README with the RollingUpdate-vs-canary-vs-blue-green table
> - `shopflow/k8s/policies/` — Kyverno ClusterPolicies + a Gatekeeper ConstraintTemplate, `tests/bad-pod.yaml` (see [05](05-kubernetes.md))
> - `docs/runbooks/Rollback.md` — rollback per deploy method (Kustomize, Argo CD, Helm, Rollouts canary/blue-green, Ansible)
> - `.github/workflows/ci.yml` — older pipeline for `Devops/` and `microservice01/` (matrix build, docker build, kubeconform)
> - `.github/dependabot.yml` — weekly Maven, Docker, GitHub Actions and Terraform updates
> - `shopflow/Jenkinsfile` — the same stages as a Declarative Jenkins pipeline (matrix over the four services)
> - `shopflow/helm/` — generic Helm chart `shopflow-service` + `values/*.yaml` + `README.md` with the Kustomize-vs-Helm comparison (the manifests Argo CD syncs are still the Kustomize overlays)
> - `shopflow/argocd/application-dev.yaml`, `application-prod.yaml` — Argo CD Applications (dev: manual sync; prod: automated + prune + selfHeal)
> - `shopflow/k8s/overlays/prod/kustomization.yaml` — image tags written by CI (`newTag: set-by-ci` until the first push to main)
>
> Honest scope: the repo has CI, image publishing, a **GitOps CD step** (commit of the SHA to the prod overlay), Argo CD manifests, Argo Rollouts manifests and Kyverno policies. What is not in the repo: a running Argo CD / Argo Rollouts / Kyverno controller or cluster credentials — the pipeline never touches a cluster, by design. The Rollouts and policy manifests are validated offline (kubeconform with CRD schemas, `kyverno apply` against the rendered prod overlay); a real canary or an admission rejection only happens in a cluster.

---

## 0. Cheat table

| Concept | In ShopFlow | Where |
|---|---|---|
| Triggers | `push` to `main` + `pull_request`, filtered by `paths: ["shopflow/**", "infra/**", ".github/workflows/shopflow.yml", ".yamllint.yaml", ".hadolint.yaml", ".gitleaks.toml"]` | `shopflow.yml` |
| Jobs | `lint`, `test`, `validate-manifests`, `terraform`, `loadtest-syntax` (parallel) → `image` (`needs: [test, validate-manifests]`) → `deploy-manifests` (`needs: [image]`, main only). `image` does not `needs: lint` — lint gates merges as a required status check in branch protection (not configured in the repo), so a lint failure never blocks the image build feedback | `shopflow.yml` |
| Lint (shift-left) | `yamllint -c .yamllint.yaml .`, `hadolint-action` on `Dockerfile` + `Dockerfile.distroless`, `gitleaks-action` (full history, `fetch-depth: 0`, allowlist in `.gitleaks.toml`), `setup-tflint` + `tflint --config .tflint.ci.hcl` (AWS ruleset 0.49.0), `ansible-lint` + `ansible-playbook --syntax-check` | `lint` job |
| Policy tests | `kyverno apply` (CLI v1.19.1) on the rendered prod overlay must pass and on `k8s/policies/tests/bad-pod.yaml` must fail; signature policy skipped offline | `validate-manifests` job |
| Alert tests | `promtool check rules` on alert + recording + SLO rules, `promtool test rules tests/alerts_test.yml` (6 scenarios), `amtool check-config alertmanager.yml` | `validate-manifests` job |
| Pre-commit | `.pre-commit-config.yaml`: end-of-file/whitespace/merge-conflict/large-file hooks, yamllint, hadolint, gitleaks, `terraform_fmt` + `terraform_tflint` — same tools as the `lint` job, on the laptop | repo root |
| Matrix | `service: [order-service, inventory-service, notification-service, payment-service]` | `image` job |
| Terraform | `terraform fmt -check -recursive`, `init -backend=false`, `validate` on `infra/terraform/aws` (no plan/apply — needs the OIDC role) | `terraform` job |
| GitOps step | `kustomize edit set image` ×4 with `${{ github.sha }}` in `k8s/overlays/prod`, commit + push with `GITHUB_TOKEN` (`contents: write`) | `deploy-manifests` job |
| Caching | `setup-java cache: maven`; Docker `cache-from/to: type=gha,scope=<service>` | `shopflow.yml` |
| Permissions | top-level `contents: read`; `image` job adds `packages: write` + `id-token: write` (OIDC for the AWS role and keyless cosign); `deploy-manifests` gets `contents: write` | `shopflow.yml` |
| Concurrency | `group: ${{ github.workflow }}-${{ github.ref }}`, `cancel-in-progress: true` | `shopflow.yml` |
| Tagging | full git SHA (+ `latest` on default branch) via `docker/metadata-action` | `image` job |
| Security | Trivy (HIGH/CRITICAL, ignore-unfixed, exit 1), CycloneDX SBOM (`anchore/sbom-action`, artifact `sbom-<service>.cdx.json`), keyless `cosign sign` of the pushed digest, Dependabot | `shopflow.yml`, `dependabot.yml` |
| Load test | `loadtest-syntax` job: `grafana/setup-k6-action` + `k6 inspect shopflow/loadtest/flash-sale.js` (parses + evaluates options, no traffic) | `shopflow.yml` |
| Push gating | `if: github.ref == 'refs/heads/main'` on login + push | `image` job |
| Failure artifacts | `upload-artifact` surefire reports `if: failure()` | `test` job |

---

## 1. CI vs Continuous Delivery vs Continuous Deployment

```
 commit ─▶ build ─▶ unit/integration tests ─▶ static checks ─▶ package (image) ─▶ scan ─▶ push
 └──────────────────────── Continuous Integration ───────────────────────────────────────┘
                                                                     ─▶ deploy dev/staging ─▶ tests
                                                                     ─▶ [manual approval] ─▶ prod
 Continuous Delivery   = every change is releasable; prod deploy is a button / approval
 Continuous Deployment = no human gate; every green commit on main goes to prod automatically
```

| | CI | Continuous Delivery | Continuous Deployment |
|---|---|---|---|
| Goal | Detect integration bugs fast | Always deployable artifact | Ship every change |
| Output | Tested artifact (image) | Artifact deployed to staging; prod on approval | Prod |
| Needs | Fast tests, frequent merges | Automated deploys, env parity | Very good tests, monitoring, feature flags, automated rollback |

ShopFlow today = **CI + artifact publishing** (immutable image per commit on main) + a **GitOps CD step** (the SHA is committed to `overlays/prod` for Argo CD) — §8. Progressive delivery on top of that (canary/blue-green with Argo Rollouts) — §7.

---

## 2. The ShopFlow pipeline, stage by stage

```
                    PR or push to main touching shopflow/**, infra/** or the lint configs
                                   │
   ┌───────────────────────────────┼───────────────────────────────┬──────────────────────┐
   ▼                               ▼                               ▼                      ▼
 ┌──────────────────────┐ ┌──────────────────────────────────┐ ┌──────────────────────┐ ┌────────────────┐
 │ lint                 │ │ validate-manifests               │ │ test                 │ │ terraform      │
 │ yamllint (repo-wide) │ │ kustomize v5.7.1 + kubeconform   │ │ JDK 21, maven cache  │ │ fmt -check     │
 │ hadolint ×2 Dockerf. │ │  0.6.7: overlays dev + prod      │ │ ./mvnw -B verify     │ │ init -backend= │
 │ gitleaks (history)   │ │ k8s/rollouts + policies/kyverno  │ │  (46 tests: H2,      │ │  false         │
 │ tflint + AWS ruleset │ │  with CRD schemas (datreeio)     │ │   EmbeddedKafka,     │ │ validate       │
 │ ansible-lint +       │ │ kyverno apply: prod passes,      │ │   WireMock, jwt())   │ └────────────────┘
 │  --syntax-check      │ │  bad-pod.yaml fails              │ │ on failure: upload   │ ┌────────────────┐
 └──────────────────────┘ │ promtool check rules (3 files)   │ │  surefire-reports    │ │ loadtest-syntax│
                          │ promtool test rules alerts_test  │ └──────────┬───────────┘ │ k6 inspect     │
                          │ amtool check-config              │            │             └────────────────┘
                          │ docker compose config --quiet    │            │
                          │ helm lint + template|kubeconform │            │
                          └───────────────┬──────────────────┘            │
                                          └──────────────┬────────────────┘
                               ▼  needs: [test, validate-manifests]
             ┌─────────────────────────────────────────────────────────────────┐
             │ image  (matrix: order-service, inventory-service, notification-service, payment-service)
             │ setup-buildx                                                    │
             │ metadata-action → tags: <sha>, latest(main)                     │
             │ build (load: true, GHA cache per service)                       │
             │ Trivy scan  → fail on fixable HIGH/CRITICAL                     │
             │ SBOM: anchore/sbom-action → CycloneDX sbom-<svc>.cdx.json        │
             │ ── only on refs/heads/main ──                                   │
             │ login ghcr.io (GITHUB_TOKEN)                                    │
             │ build-push (cache hit → fast) → GHCR  (outputs.digest)          │
             │ cosign sign --yes IMAGE@digest  (keyless, OIDC id-token)        │
             │ optional: assume AWS role (OIDC) → mirror to ECR                │
             └───────────────────────────────┬─────────────────────────────────┘
                                             ▼  needs: [image], main only, permissions: contents: write
             ┌─────────────────────────────────────────────────────────────────┐
             │ deploy-manifests                                                │
             │ kustomize edit set image shopflow/<svc>=ghcr.io/<owner>/shopflow-<svc>:<sha>  (×4)
             │ kustomize build . > /dev/null   (still renders?)                │
             │ git commit "deploy(prod): pin images to <sha7>" && git push     │
             │   → Argo CD (argocd/application-prod.yaml) syncs overlays/prod  │
             └─────────────────────────────────────────────────────────────────┘
```

### Stage notes (things to say out loud)
1. **Path filters** — a change only in `Devops/` doesn't run the ShopFlow pipeline (monorepo efficiency). The workflow file itself is in the paths so editing the pipeline tests it.
2. **`test`** — `./mvnw -B verify` uses the Maven Wrapper (same Maven version everywhere), `-B` batch mode (no colour/interactive output). `verify` runs unit + integration tests (Spring Boot tests with H2 in PostgreSQL mode, WireMock for inventory — see [01-project-walkthrough.md](01-project-walkthrough.md)). Surefire reports uploaded only on failure for debugging.
3. **`lint`** — the cheapest checks, repo-wide, in their own job so a YAML indentation error or a leaked key is reported in seconds without waiting for Maven:
   - `yamllint -c .yamllint.yaml .` — every YAML file (manifests, compose, workflows, Ansible); Helm templates are excluded (Go templates are not YAML; `helm lint` covers them). Baseline when it was introduced: 25 findings in the legacy `Devops/` manifests, all fixed.
   - `hadolint/hadolint-action` on `shopflow/Dockerfile` and `Dockerfile.distroless` with `.hadolint.yaml` (`failure-threshold: warning`; `DL3018` "pin apk versions" ignored on purpose — the base image is pinned, apk pins rot on alpine).
   - `gitleaks/gitleaks-action` over the **whole history** (`fetch-depth: 0`). `.gitleaks.toml` extends the default rules and allowlists known non-secrets: `docs/LABS.md` (`TOKEN=$(token alice)` examples), the Keycloak dev realm (alice/alice), and the dev-overlay literals `orders-db-password=orders` etc. Baseline: 7 false positives, all allowlisted with a comment saying why.
   - `terraform-linters/setup-tflint` + `tflint --init --config .tflint.ci.hcl` — core rules (required_version, naming, unused declarations, deprecated interpolation) **plus** the AWS ruleset `0.49.0` (invalid instance types, deprecated arguments). Locally `make lint` uses `.tflint.hcl` (core rules only) so it works offline.
   - `ansible-lint` (production profile, `infra/ansible/.ansible-lint`) + `ansible-playbook --syntax-check -i inventory.example.ini site.yml`.
4. **`validate-manifests`** — "shift left" for infrastructure:
   - `kustomize build` proves overlays render (broken patch paths fail here).
   - `kubeconform -strict` validates against K8s JSON schemas (typos like `replcas`, wrong types). `-strict` rejects unknown fields. `k8s/rollouts` and `k8s/policies/kyverno/*.yaml` are CRDs, so a second `-schema-location` points at the datreeio CRDs-catalog (`{{.Group}}/{{.ResourceKind}}_{{.ResourceAPIVersion}}.json`) — otherwise kubeconform would skip them.
   - **Policy tests**: the Kyverno CLI (`v1.19.1`) runs `kyverno apply <policies> --resource <rendered prod overlay>` (must pass: 12 pass locally) and then against `k8s/policies/tests/bad-pod.yaml` (`nginx:latest`, no probes, no resources, root) — the step fails if the output says `fail: 0`, i.e. the policies themselves are tested, not only the manifests. `verify-image-signature.yaml` is excluded offline because it needs a registry + Rekor.
   - `promtool check rules` on `alert-rules.yml`, `recording-rules.yml` and `slo-rules.yml`, then **`promtool test rules tests/alerts_test.yml`** — six unit scenarios with synthetic series, including negative cases (2% errors must *not* page; 1% slow-but-no-5xx must not trip the SLO alert). Then `amtool check-config alertmanager.yml` (`prom/alertmanager:v0.28.1`): routes, receivers, inhibition and template syntax. All three run in the official images with `--entrypoint`. Honest gap: `prometheus.yml` itself is still not checked — `promtool check config` would do it.
   - `docker compose config --quiet` validates compose YAML (anchors, merge keys).
   - `helm lint` on the chart + `helm template | kubeconform -strict` for two values files.
   Downloaded tools are installed into `${{ runner.temp }}` and added to `$GITHUB_PATH`. Tools are version-pinned for reproducibility. `make validate` runs the same list locally.
5. **`image`** — matrix fans out per service (four). Builds once with `load: true` so the image exists in the runner's Docker for Trivy → scans **before** pushing (a vulnerable image never reaches the registry). Then `anchore/sbom-action` writes a **CycloneDX SBOM** of the local image and uploads it as the artifact `sbom-<service>.cdx.json` — when the next Log4Shell drops you grep the SBOMs instead of rescanning every image. Push step rebuilds with `cache-from` → effectively instant and exposes the pushed **digest**. `sigstore/cosign-installer` + `cosign sign --yes "$IMAGE@<digest>"` then signs that digest **keyless**: the job's OIDC token (`permissions: id-token: write`) proves "workflow X in repo Y on ref Z" to Fulcio, which issues a short-lived certificate; the signature and certificate go to the Rekor transparency log and next to the image in GHCR. No private key exists anywhere to leak. The other half is in the repo now: `k8s/policies/kyverno/verify-image-signature.yaml` makes the cluster refuse any `ghcr.io/rajji01/shopflow-*` / ECR `shopflow/*` image in the `shopflow` namespace that was not signed by **this workflow on `main`** (keyless attestor: `subject: https://github.com/Rajji01/DevopsComplete/.github/workflows/shopflow.yml@refs/heads/main`, `issuer: https://token.actions.githubusercontent.com`, Rekor), and `mutateDigest: true` rewrites the tag to the verified digest. It cannot be exercised in CI (needs a registry + a cluster running Kyverno).
   **`loadtest-syntax`** — `grafana/setup-k6-action` + `k6 inspect shopflow/loadtest/flash-sale.js`: compiles the script and evaluates `options` (scenarios, thresholds) without sending traffic, so a typo in a threshold expression fails the PR instead of the night before the sale. The real run is manual against compose/minikube.
   **`terraform`** — runs in parallel: `fmt -check`, `init -backend=false` (providers only, no state bucket), `validate`. A real deploy job would assume the OIDC role from `ci-oidc.tf`, `plan` on PRs and `apply` on main behind an approval environment (the workflow comment says exactly this).
   **`deploy-manifests`** — the GitOps hand-off (details in §8).
6. **`concurrency`** — a new push to the same branch/PR cancels the older run (saves minutes, and avoids an older run pushing after a newer one).
7. **`permissions`** — least privilege: whole workflow `contents: read`; only `image` gets `packages: write` + `id-token: write` (one OIDC token serves both the AWS role assumption and keyless cosign), only `deploy-manifests` gets `contents: write`.

Older `ci.yml` for contrast: runs on **every** push/PR (no path filter), matrix over `[Devops, microservice01]` with `defaults.run.working-directory: ${{ matrix.service }}`, Java 17, `mvn -B verify`, `docker build` without push, kubeconform on raw YAML.

---

## 3. GitHub Actions concepts

| Concept | Meaning | Example |
|---|---|---|
| **Workflow** | YAML in `.github/workflows/` | `shopflow.yml` |
| **Trigger (`on`)** | push, pull_request, schedule (cron), workflow_dispatch (manual), release, workflow_call (reusable) | `push: branches: [main]` |
| **Job** | Runs on a fresh runner VM; jobs run **in parallel** by default | `test`, `validate-manifests`, `image` |
| **`needs`** | Dependency / ordering between jobs | `needs: [test, validate-manifests]` |
| **Step** | `uses:` an action, or `run:` a shell command | `uses: actions/checkout@v7` |
| **Runner** | GitHub-hosted (`ubuntu-latest`) or self-hosted (in your VPC) | `runs-on: ubuntu-latest` |
| **Matrix** | Same job with different params | `service: [order-service, inventory-service]` |
| **`if`** | Conditional steps/jobs | `if: github.ref == 'refs/heads/main'`, `if: failure()` |
| **Contexts / expressions** | `${{ github.sha }}`, `${{ matrix.service }}`, `${{ secrets.X }}`, `${{ steps.meta.outputs.tags }}` | |
| **Outputs** | Pass data between steps (`id:` + `outputs`) and jobs (`jobs.<id>.outputs`) | `steps.meta.outputs.tags` |
| **Artifacts vs cache** | Artifact = output to keep/download (reports, jars); cache = speed-up for deps | `upload-artifact` vs `cache: maven` |
| **Environments** | Named targets (staging/prod) with required reviewers, env secrets, wait timers | Not in repo — how you'd gate prod |
| **Reusable workflows / composite actions** | DRY across repos | `workflow_call` |

### Secrets & `GITHUB_TOKEN`
- `secrets.*` are encrypted repo/org/environment secrets, masked in logs, **not passed to workflows triggered from forks**.
- `GITHUB_TOKEN` is an automatic, short-lived token per run, scoped by the `permissions:` block. ShopFlow uses it to push to GHCR — no personal access token needed.
- Default permissions can be broad (depending on repo settings) → always declare `permissions:` explicitly (ShopFlow does).

### OIDC to the cloud (no long-lived keys)
Used in the repo: the `image` job mirrors each SHA-tagged image to **ECR** when the repository variable `AWS_ROLE_ARN` is set (role from `terraform output github_actions_role_arn`, trust policy in `infra/terraform/aws/ci-oidc.tf`); GHCR itself needs no cloud creds.
```yaml
permissions:
  id-token: write          # allow requesting an OIDC JWT
  packages: write
steps:
  - uses: aws-actions/configure-aws-credentials@v6
    if: github.ref == 'refs/heads/main' && vars.AWS_ROLE_ARN != ''
    with:
      role-to-assume: ${{ vars.AWS_ROLE_ARN }}
      aws-region: ${{ vars.AWS_REGION || 'ap-south-1' }}
  - uses: aws-actions/amazon-ecr-login@v2
    id: ecr
  - run: docker tag "$SRC" "${{ steps.ecr.outputs.registry }}/shopflow/${{ matrix.service }}:${{ github.sha }}" && docker push ...
```
AWS IAM role trust policy trusts the OIDC provider `token.actions.githubusercontent.com` with conditions `aud = sts.amazonaws.com` and `sub = repo:Rajji01/DevopsComplete:ref:refs/heads/main`. Benefit: no `AWS_ACCESS_KEY_ID` stored in GitHub; credentials expire in ~1h; scoped to repo + branch.

### Caching
- `actions/setup-java` with `cache: maven` → caches `~/.m2/repository` keyed on hash of `**/pom.xml`.
- Docker: `cache-from: type=gha,scope=${{ matrix.service }}` / `cache-to: type=gha,mode=max,...`. `scope` keeps the four services' caches separate (otherwise they overwrite each other). `mode=max` caches all stages (build + extract), not only final layers.

---

## 4. Jenkins basics (very common in Indian companies)

Architecture: **controller** (UI, scheduling, config) + **agents** (run builds; static VMs, Docker, or Kubernetes pods via the Kubernetes plugin). Pipelines as code in a `Jenkinsfile` (Declarative or Scripted Groovy). Credentials stored in Jenkins credentials store; plugins for everything (Git, Docker, Kubernetes, SonarQube, Slack).

The repo has a real one: `shopflow/Jenkinsfile` — `agent any`, `options { timestamps(); disableConcurrentBuilds(); buildDiscarder(logRotator(numToKeepStr: '30')); timeout(45 min) }`, `tools { jdk 'temurin-21' }`, `IMAGE_TAG = env.GIT_COMMIT`; stages **Lint** (`yamllint -c .yamllint.yaml .`, `hadolint` on both Dockerfiles, `gitleaks git -c .gitleaks.toml`), **Test** (`./mvnw -B verify`, `junit` report in `post { always }`), **Validate manifests** (kustomize + kubeconform + `promtool check rules` in the Prometheus image — the Jenkinsfile has not caught up with the Rollouts/Kyverno/`promtool test`/`amtool` steps of the GitHub workflow; say so if asked), **Build & scan images** (`matrix { axis SERVICE: order-service, inventory-service, notification-service }` → `docker build` → `trivy image --exit-code 1`), **Push images** (`when { branch 'main' }`, `withCredentials(usernamePassword 'ghcr')`), **Pin prod overlay** (`sshagent(['github-deploy-key'])`, `kustomize edit set image` ×3, commit + push), `post { failure { echo ... } }` (real life: `slackSend`). Its header comment: *"Same pipeline as `.github/workflows/shopflow.yml`, written for Jenkins; the stages are identical, only the plumbing differs."* Simplified sketch of that file:
```groovy
pipeline {
  agent { label 'docker' }
  options {
    timeout(time: 30, unit: 'MINUTES')
    disableConcurrentBuilds(abortPrevious: true)   // like GHA concurrency cancel-in-progress
    buildDiscarder(logRotator(numToKeepStr: '20'))
  }
  environment {
    REGISTRY = 'ghcr.io/rajji01'
    GIT_SHA  = "${env.GIT_COMMIT}"
  }
  stages {
    stage('Test') {
      steps { dir('shopflow') { sh './mvnw -B verify' } }
      post  { always { junit 'shopflow/**/target/surefire-reports/*.xml' } }
    }
    stage('Validate manifests') {
      steps {
        dir('shopflow') {
          sh 'for o in k8s/overlays/*/; do kustomize build "$o" | kubeconform -strict -summary -; done'
        }
      }
    }
    stage('Build & scan images') {
      matrix {
        axes { axis { name 'SERVICE'; values 'order-service', 'inventory-service' } }
        stages {
          stage('Build') {
            steps {
              dir('shopflow') {
                sh 'docker build --build-arg SERVICE=$SERVICE -t $REGISTRY/shopflow-$SERVICE:$GIT_SHA .'
                sh 'trivy image --severity HIGH,CRITICAL --ignore-unfixed --exit-code 1 $REGISTRY/shopflow-$SERVICE:$GIT_SHA'
              }
            }
          }
          stage('Push') {
            when { branch 'main' }                     // branch condition works in multibranch pipelines
            steps {
              withCredentials([usernamePassword(credentialsId: 'ghcr', usernameVariable: 'U', passwordVariable: 'P')]) {
                sh 'echo $P | docker login ghcr.io -u $U --password-stdin'
                sh 'docker push $REGISTRY/shopflow-$SERVICE:$GIT_SHA'
              }
            }
          }
        }
      }
    }
    stage('Deploy to prod') {
      when { branch 'main' }
      steps {
        input message: 'Deploy to prod?', ok: 'Deploy'      // manual gate = Continuous Delivery
        sh 'echo "bump image tag in GitOps repo here"'
      }
    }
  }
  post {
    failure { echo 'notify Slack/email here' }
    always  { cleanWs() }
  }
}
```

| | GitHub Actions | Jenkins |
|---|---|---|
| Hosting | SaaS runners (or self-hosted) | Self-managed controller + agents |
| Config | YAML | Groovy Jenkinsfile |
| Ecosystem | Marketplace actions | 1800+ plugins (and plugin upgrade pain) |
| Maintenance | Low | High (patching, backups, plugin CVEs) |
| Strength | Tight GitHub integration, OIDC | Flexibility, on-prem, legacy, complex orchestration |

Jenkins terms to know: multibranch pipeline, shared libraries (`@Library`), `stash/unstash`, `parallel`, `when`, `post`, `input`, `credentials()`, webhooks vs SCM polling, agents on Kubernetes.

---

## 5. Branching strategies

| | Trunk-based | GitFlow |
|---|---|---|
| Branches | `main` + short-lived feature branches (< 1–2 days) | `main`, `develop`, `feature/*`, `release/*`, `hotfix/*` |
| Integration | Continuous, small PRs | Big merges into develop/release |
| Unfinished work | Hidden behind **feature flags** | Lives on long branches |
| Release | Any green commit on main (tag) | Release branch stabilisation |
| Fits | CI/CD, microservices, SaaS | Versioned/boxed products, mobile, slow release trains |
| Pain | Needs good tests + flags | Merge conflicts, slow feedback |

ShopFlow pipeline is trunk-shaped: PRs get full CI (no push), `main` publishes images. GitHub Flow = trunk-based with PRs. Protect `main` with branch protection: required status checks (by their job `name:` — "Lint (yaml, Dockerfile, secrets, Terraform, Ansible)", "Unit + integration tests", "Validate K8s + monitoring config", "Image order-service", "Image inventory-service"), required reviews, no force push.

---

## 6. Artifact versioning

- **Immutable** artifacts: build once, promote the *same* image through dev → staging → prod (never rebuild per env). Config differences live in overlays/env vars, not in the image.
- Tag schemes:
  - Git SHA (ShopFlow: `type=sha,format=long,prefix=` → `ghcr.io/<owner>/shopflow-order-service:3f9c2...`) — traceable to exact commit.
  - SemVer for releases (`v1.4.2`) via git tags (`type=semver,pattern={{version}}` in metadata-action — would also need `on: push: tags: ["v*"]`, which ShopFlow doesn't have).
  - `latest` only as convenience (`enable={{is_default_branch}}`) — never deploy it.
- Digest (`@sha256:`) is the truly immutable reference.
- Maven: `1.0.0-SNAPSHOT` (parent pom) vs release versions; `build-info` goal exposes version at `/actuator/info` so you can verify what's running.
- OCI labels from metadata-action (`org.opencontainers.image.revision`, `source`, `created`) → `docker inspect` shows the commit.

---

## 7. Deployment strategies

| Strategy | How | Pros | Cons | K8s implementation |
|---|---|---|---|---|
| **Recreate** | Stop all old, start new | Simple, no version mixing | Downtime | `strategy.type: Recreate` |
| **Rolling** | Replace pods gradually | No extra infra, default | Two versions live together; "healthy" = readiness only; slow rollback for big fleets | **ShopFlow `k8s/base`**: `maxSurge: 1, maxUnavailable: 0` |
| **Blue-green** | Full new env (green), switch traffic at once | Instant switch & rollback, test green before switch | 2× resources; DB must work for both | **ShopFlow `k8s/rollouts/inventory-service-bluegreen.yaml`** (Argo Rollouts `blueGreen`); by hand: two Deployments + flip Service selector |
| **Canary** | Send small % (10%→25%→50%→100%) to new version, watch metrics | Limits blast radius, data-driven | Needs traffic splitting + good metrics | **ShopFlow `k8s/rollouts/order-service-canary.yaml`** + `analysis-template.yaml`; Flagger as alternative; exact weights need ingress/Gateway API/mesh |
| **Feature flags** | Deploy code dark, enable per user/% at runtime | Decouples deploy from release, instant kill switch | Flag debt, testing combinations | LaunchDarkly, Unleash, Flagsmith, Spring config |
| A/B testing | Route by user segment to measure business metric | Product experiments | Not a safety mechanism per se | Mesh/ingress header routing |
| Shadow | Mirror traffic to new version, discard responses | Zero user impact | Side effects (writes!) | Mesh mirroring |

### Progressive delivery in the repo: `shopflow/k8s/rollouts/` (Argo Rollouts)

`k8s/base` stays on plain `Deployment` + RollingUpdate; `k8s/rollouts` is the advanced approach for the same two services, kept side by side (`kubectl apply -k k8s/rollouts` after installing the Argo Rollouts controller; `make rollouts-dev`). Key design choice: **`workloadRef`** — the Rollout points at the existing Deployment and reuses its pod template (env, probes, securityContext) instead of copying it, with `scaleDown: onsuccess` so the Deployment is scaled to 0 once the Rollout owns the pods. Services, HPA and PDB are reused.

**Canary — `order-service-canary.yaml`** (the checkout path, where a bad release costs money):
```yaml
strategy:
  canary:
    maxSurge: 1
    maxUnavailable: 0
    analysis:
      templates: [{ templateName: error-ratio }]
      args: [{ name: service, value: order-service }]
      startingStep: 1                 # analysis runs in the background from the first 10% step onwards
    steps:
      - setWeight: 10
      - pause: { duration: 5m }
      - setWeight: 25
      - pause: { duration: 5m }
      - setWeight: 50
      - pause: { duration: 10m }
      - setWeight: 100
```
**The gate — `analysis-template.yaml`**: every minute, 5 measurements, the canary's own 5xx ratio (`rollouts_pod_template_hash!=""` restricts the query to canary pods; `/actuator` excluded) must be `< 0.02`; `failureLimit: 3` → three bad measurements abort the rollout automatically and traffic goes back to stable. Prometheus at `http://prometheus.monitoring:9090`. Honest notes: without a traffic router (ALB/NGINX/Istio) `setWeight` is approximated by the **pod ratio**, so 10% of 3 replicas is really "1 canary pod of 4"; and the 2% threshold on a `[2m]` rate needs enough traffic — at 1 RPS one error is 0.8%, two in the same window trip it.

**Blue-green — `inventory-service-bluegreen.yaml`**: `activeService: inventory-service` (the existing ClusterIP), `previewService: inventory-service-preview` (new Service in the same file, port 8082, Rollouts adds the pod-template-hash selector), `autoPromotionEnabled: false` (someone runs the smoke test against the preview, then `kubectl argo rollouts promote inventory-service`), `prePromotionAnalysis` with the same `error-ratio` template, and `scaleDownDelaySeconds: 60` — blue stays alive for a minute after the flip, so rollback (`kubectl argo rollouts undo`) is instant.

The README's comparison table (abridged): traffic to the new version — all gradually by pod count / 10→25→50→100 with pauses / 0% until promoted then 100%; decision to continue — readiness probe / Prometheus analysis / manual promotion after preview tests; rollback — `rollout undo` ~1 min / automatic abort / flip the Service back; cost — 1 extra pod / a few canary pods / 2× pods during preview. Rollback steps per method are in `docs/runbooks/Rollback.md`.

Verification status: the two Rollouts + AnalysisTemplate are validated with kubeconform against the Argo CRD schemas (CI and `make validate`); an actual canary run needs the controller in a cluster (Lab 30).

> Tip: Interview mein trade-off zaroor bolo — "blue-green is fast rollback but double cost; canary is safer but needs metrics + traffic splitting" — aur phir file ka naam bolo.

---

## 8. GitOps with Argo CD (how ShopFlow does CD)

**GitOps principles**: desired state is **declarative**, **versioned in git**, **pulled** automatically by an agent in the cluster, and **continuously reconciled** (drift is corrected).

```
 Developer PR ──▶ main ──▶ GitHub Actions (shopflow.yml)
                              │  test, validate, build, Trivy, push
                              ▼
                        ghcr.io/<owner>/shopflow-order-service:<sha>
                              │
            CD step: kustomize edit set image ... :<sha>  in overlays/prod
            commit (or PR for approval) to git ─────────────┐
                                                            ▼
   ┌──────────────── Kubernetes cluster ─────────────────────────────┐
   │ Argo CD  ── watches repo path shopflow/k8s/overlays/prod ──▶    │
   │   detects OutOfSync → kustomize build → apply → Synced/Healthy  │
   │   selfHeal: reverts manual kubectl edits                        │
   └─────────────────────────────────────────────────────────────────┘
```

Push-based CD (CI runs `kubectl apply` with cluster creds) vs **pull-based** GitOps (agent in cluster pulls): pull means no cluster credentials in CI, git is the audit log, rollback = `git revert`, drift detection.

Argo CD Applications in the repo: `shopflow/argocd/application-dev.yaml` (path `overlays/dev`, `targetRevision: HEAD`, **no** automated policy — "sync by hand so you can inspect the diff in the Argo CD UI") and `application-prod.yaml` (path `overlays/prod`, `targetRevision: main`, automated `prune` + `selfHeal`, `ignoreDifferences` on replicas). Prod, essentially:
```yaml
apiVersion: argoproj.io/v1alpha1
kind: Application
metadata:
  name: shopflow-prod
  namespace: argocd
spec:
  project: default
  source:
    repoURL: https://github.com/Rajji01/DevopsComplete.git
    targetRevision: main
    path: shopflow/k8s/overlays/prod          # Argo CD natively understands kustomization.yaml
  destination:
    server: https://kubernetes.default.svc
    namespace: shopflow
  syncPolicy:
    automated: { prune: true, selfHeal: true }
    syncOptions: [CreateNamespace=true]
  ignoreDifferences:                          # HPA owns replicas
    - group: apps
      kind: Deployment
      jsonPointers: [/spec/replicas]
```

The tag-bump job in `shopflow.yml` (simplified — the real one sets all four images and has no `environment:` gate yet):
```yaml
  deploy-prod:
    needs: image
    if: github.ref == 'refs/heads/main'
    runs-on: ubuntu-latest
    environment: production                  # required reviewers = manual gate (Continuous Delivery)
    permissions: { contents: write }
    steps:
      - uses: actions/checkout@v7
      - run: |
          cd k8s/overlays/prod               # workflow default working-directory is already shopflow/
          kustomize edit set image \
            shopflow/order-service=ghcr.io/rajji01/shopflow-order-service:${GITHUB_SHA} \
            shopflow/inventory-service=ghcr.io/rajji01/shopflow-inventory-service:${GITHUB_SHA}
          git config user.name "github-actions[bot]"
          git config user.email "41898282+github-actions[bot]@users.noreply.github.com"
          git commit -am "deploy: shopflow ${GITHUB_SHA}" && git push
```
This is the real `deploy-manifests` job in `shopflow.yml` (runs on `main` after the `image` jobs, with `permissions: contents: write`): it replaces the placeholder tag in `overlays/prod` with the exact commit SHA and commits it back. Better practice: a **separate config repo** (or Argo CD Image Updater) so app history and deploy history stay apart. (A push made with `GITHUB_TOKEN` does not trigger new workflow runs, so there's no CI loop; with a PAT/App token you'd need `[skip ci]` or path filters. Branch protection on `main` would block this direct push → open a PR instead.) Secrets: prod `shopflow-db` via External Secrets Operator — git never contains secret values.

Argo CD terms: Application, AppProject, sync, sync waves/hooks (e.g., run a migration Job `PreSync`), health status, app-of-apps / ApplicationSet (one per env/cluster). Flux CD is the alternative.

---

## 9. DevSecOps — security in the pipeline

| Layer | Tool examples | ShopFlow status |
|---|---|---|
| Secret scanning | GitHub secret scanning + push protection, gitleaks, trufflehog | **gitleaks** in the `lint` job (full history) and as a pre-commit hook; `.gitleaks.toml` allowlists 7 documented false positives (LABS token examples, dev literals) |
| SAST (code) | CodeQL, SonarQube, Semgrep, SpotBugs | Not in repo — CodeQL for Java is a 10-line workflow |
| SCA / dependency scan | Dependabot, OWASP Dependency-Check, Snyk, `trivy fs` | **Dependabot** weekly for Maven (Spring deps grouped), Docker base images, GitHub Actions |
| IaC / manifest lint + scan | yamllint, hadolint, tflint, kubeconform (schema), Checkov, kube-linter, Trivy config, Kyverno policies | **yamllint, hadolint, tflint (+ AWS ruleset), ansible-lint, kubeconform** (incl. CRDs) and **Kyverno policies tested with `kyverno apply`** (prod overlay passes, `bad-pod.yaml` fails). Not yet: Checkov/`trivy config` |
| Container image scan | Trivy, Grype, Snyk, ECR scan | **Trivy**: `HIGH,CRITICAL`, `ignore-unfixed: true`, `exit-code: "1"` |
| SBOM | Syft, `trivy image --format cyclonedx`, buildx `sbom: true` | **Yes**: `anchore/sbom-action@v0` (Syft) → CycloneDX JSON uploaded as `sbom-<service>.cdx.json` per image |
| Signing / provenance | cosign (keyless via OIDC), SLSA provenance, admission verification (Kyverno/Connaisseur) | **Signing yes**: `sigstore/cosign-installer` + `cosign sign --yes <image>@<digest>` with the job's OIDC identity on `main`. **Admission policy written**: `k8s/policies/kyverno/verify-image-signature.yaml` (keyless attestor pinned to this workflow on `main`, `mutateDigest: true`) — only enforceable in a cluster running Kyverno. Not yet: SLSA provenance attestation |
| DAST | OWASP ZAP against staging | Not yet |
| Runtime | Falco, PSA restricted, NetworkPolicies | PSA-compliant securityContext + NetworkPolicies in k8s/ |
| Shift-left on the laptop | pre-commit, Makefile | **`.pre-commit-config.yaml`** (hooks: yamllint, hadolint, gitleaks, terraform_fmt/tflint, whitespace/EOF/merge-conflict/large files; `pip install pre-commit && pre-commit install`) and `make lint` / `make validate` |
| Least privilege CI | `permissions:` block, OIDC, environments | **Yes**: `contents: read`, `packages: write` + `id-token: write` only for `image`, `contents: write` only for `deploy-manifests` |
| Architecture rules | ArchUnit, Konsist | **Yes**: `order-service` `ArchitectureTest` (7 rules: controllers never touch repositories/entities, services never depend on Spring Web, no field injection, no `System.out`/JUL) runs inside `./mvnw verify` |

### Pinning actions to SHA
ShopFlow uses tags: `actions/checkout@v7`, `aquasecurity/trivy-action@v0.36.0`. Tags are **mutable** — if an action repo is compromised, the attacker can move the tag (real incident: `tj-actions/changed-files`, March 2025 — tags repointed to a malicious commit that dumped CI secrets into build logs of repos using it, ~23k dependents). Hardening:
```yaml
- uses: actions/checkout@<40-char-commit-sha>  # v7
```
Dependabot `package-ecosystem: github-actions` (configured) keeps SHA pins updated with the version in a comment. Honest answer: "I use version tags today plus Dependabot; for production I'd pin third-party actions to SHAs."

### Why `ignore-unfixed: true`?
Fail only on vulnerabilities that **have a fix** → the build is actionable. Unfixable OS CVEs would block every build with nothing the team can do; track those separately with a risk acceptance (`.trivyignore` with justification and expiry).

---

## 10. Rollback strategies

| Situation | Rollback |
|---|---|
| Plain kubectl/Kustomize | `kubectl rollout undo deploy/order-service -n shopflow` (needs `revisionHistoryLimit` > 0; ShopFlow keeps 5) |
| GitOps (Argo CD) | `git revert <deploy commit>` → Argo CD syncs old SHA. Argo CD UI rollback works only if auto-sync is disabled |
| Helm | `helm rollback <release> <revision>` |
| Argo Rollouts blue-green (`inventory-service`) | Before promotion: `kubectl argo rollouts abort`; after: `kubectl argo rollouts undo` — blue is kept `scaleDownDelaySeconds: 60`, so it is instant inside that minute |
| Argo Rollouts canary (`order-service`) | Automatic on failed `error-ratio` analysis (`failureLimit: 3`); manual: `abort` then `undo` — seconds |
| Ansible/VM | `ansible-playbook site.yml -e repo_version=<previous sha>` (~5 min, rebuilds) |
| Feature flag | Turn flag off — no deploy needed (fastest) |
| Bad DB migration | **Roll forward** with a fix migration; backups/PITR as last resort |

Immutable SHA tags are what make rollback reliable: "previous version" is an exact image, not "whatever latest was yesterday". The full procedure (confirm the old image is running, check the alert resolves, remember Flyway is forward-only so the old code must still work with the new schema, write it up) is `docs/runbooks/Rollback.md`.

Roll back vs roll forward: rollback when the cause is unclear and the previous version is known good; roll forward when the fix is small and rollback is unsafe (e.g., schema already changed).

---

## 11. Database migrations in CD (Flyway, expand–contract)

ShopFlow uses **Flyway** (`flyway-core` + `flyway-database-postgresql`), scripts in `src/main/resources/db/migration/` (`V1__create_tables.sql`, `V2__seed_products.sql` for inventory; `V1__create_orders.sql` for orders). Hibernate is set to `ddl-auto: validate` — the schema is owned by Flyway; Hibernate only checks it at startup.

How it runs: Flyway executes on application startup. Concurrency: with 3 replicas starting together, Flyway takes a lock (a Postgres advisory lock) so only one applies migrations; the others wait and then see them as already applied. Alternatives: run migrations as a K8s **Job** / Argo CD `PreSync` hook / init container, so app pods don't race and startup probes don't time out on long migrations.

Rules:
- Never edit an applied migration (checksum mismatch → startup fails). Add `V3__...`.
- Migrations must be **backward compatible** with the version still running (rolling updates run old + new together).

### Expand–contract (parallel change) — rename column `qty` → `quantity`
```
Release 1 (expand):   V3: ADD COLUMN quantity; backfill; app writes BOTH, reads old
Release 2 (migrate):  app reads quantity, still writes both
Release 3 (contract): app stops using qty; V4: DROP COLUMN qty
```
Each step is safe to roll back app-wise because the old app still finds what it needs. Same idea for NOT NULL columns (add nullable → backfill → add constraint), and for splitting tables. Avoid long locking DDL on big tables (`CREATE INDEX CONCURRENTLY` in Postgres can't run inside a transaction — Flyway detects it and runs that migration non-transactionally; keep it in its own script, since mixing it with transactional statements needs `spring.flyway.mixed=true`).

---

## 12. Interview Q&A

**Q1. Walk me through your pipeline.**
On PRs and pushes to main touching `shopflow/` or `infra/`, five jobs run in parallel: a lint job (yamllint over the repo, hadolint on both Dockerfiles, gitleaks over the full history, tflint with the AWS ruleset, ansible-lint + syntax check), tests with `./mvnw -B verify` on JDK 21 with Maven cache (46 tests incl. embedded Kafka, fake JWTs and ArchUnit rules), config validation — every Kustomize overlay plus the Argo Rollouts and Kyverno manifests checked with kubeconform strict, the Kyverno policies run against the rendered prod overlay and against a deliberately bad pod, Prometheus alert/recording/SLO rules syntax-checked and **unit-tested** with `promtool test rules`, the Alertmanager config with `amtool`, the compose file, the Helm chart — Terraform `fmt`/`validate`, and `k6 inspect` on the load-test script. The same linters run locally via pre-commit and `make lint`/`make validate`. If tests and validation pass, a matrix job builds each of the four service images with Buildx and GHA cache, tags them with the full commit SHA, scans with Trivy and fails on fixable HIGH/CRITICAL CVEs, and attaches a CycloneDX SBOM. Only on main does it log in to GHCR with the GITHUB_TOKEN and push, sign the pushed digest keyless with cosign using the job's OIDC token, and then a `deploy-manifests` job pins the SHA into `k8s/overlays/prod` and commits it; Argo CD syncs that overlay. Concurrency cancels superseded runs; permissions are read-only except `packages: write` for the image job and `contents: write` for the pin job.

**Q2. CI vs continuous delivery vs continuous deployment?**
CI: merge often, build and test automatically. Continuous delivery: every change produces a deployable artifact and deploying to prod is a manual decision. Continuous deployment: no manual gate. My project is at GitOps continuous **deployment** for prod (every main commit is pinned and auto-synced by Argo CD); adding a GitHub `environment: production` with required reviewers on the pin job would turn it into continuous delivery with an approval gate.

**Q3. Why scan before pushing?**
So a vulnerable image never lands in the registry where something could pull it. I build with `load: true`, scan the local image, and only then push; the second build is a cache hit.

**Q4. How do you avoid storing cloud credentials in CI?**
OIDC: the workflow gets `id-token: write`, exchanges GitHub's JWT for short-lived AWS credentials via an IAM role whose trust policy is restricted to my repo and branch. For GHCR, the built-in `GITHUB_TOKEN` with `packages: write` is enough.

**Q5. What does `concurrency` do in your workflow?**
Groups runs by workflow + ref; a new push cancels the in-progress older run for the same branch/PR. Saves runner minutes and prevents an older commit's image from being pushed after a newer one.

**Q6. Blue-green vs canary vs rolling?**
(Table §7.) Rolling is the default in `k8s/base`: zero downtime, but "healthy" only means readiness passed and every pod gets the new version within a minute. In `k8s/rollouts` I have both advanced forms as Argo Rollouts with `workloadRef` to the same Deployments: **canary** for order-service (10→25→50→100% with pauses, a Prometheus AnalysisTemplate gating on the canary pods' 5xx ratio < 2%, `failureLimit: 3` → automatic abort) because checkout is where a bad release costs money and a gradual, data-driven exposure is worth the setup; **blue-green** for inventory-service (preview Service, `autoPromotionEnabled: false`, pre-promotion analysis, blue kept 60 s) because it is an internal API where I want a smoke test against the preview and an instant flip/flip-back, and 2× pods for a minute is cheap. The trade-off in one line: blue-green = instant switch and rollback at double cost and no partial exposure; canary = limited blast radius but needs metrics, time, and a traffic router for exact weights.

**Q7. What is GitOps, and how is it done here?**
Git is the source of truth; an in-cluster agent like Argo CD pulls and reconciles. CI's `deploy-manifests` job runs `kustomize edit set image` with the new SHA for all four services in `overlays/prod` and commits it; `argocd/application-prod.yaml` syncs that path with prune and self-heal (dev is synced manually). Rollback is `git revert` of the pin commit. No cluster credentials in CI. Improvement: a separate config repo so app and deploy history stay apart.

**Q8. How do you handle DB schema changes with zero downtime?**
Flyway versioned migrations, backward compatible, using expand-contract across releases so old and new pods can coexist during a rolling update. Hibernate `validate` catches drift at startup.

**Q9. Trunk-based vs GitFlow — what do you prefer?**
Trunk-based for microservices with CI/CD: small PRs, short-lived branches, feature flags for unfinished work, fast feedback. GitFlow fits scheduled releases with multiple supported versions.

**Q10. Why pin GitHub Actions to a commit SHA?**
Tags are mutable; a compromised action can retag malicious code and steal secrets (tj-actions incident). SHA pins are immutable; Dependabot updates them.

**Q11. What is an SBOM and why care?**
Software Bill of Materials — list of every component and version in an artifact (CycloneDX/SPDX). When a new CVE drops (like Log4Shell), you can query which images contain the library instantly instead of rescanning everything. ShopFlow: `anchore/sbom-action` (Syft) produces `sbom-<service>.cdx.json` for every image build and keeps it as a workflow artifact; the next step would be attaching it to the image as a cosign attestation (`cosign attest --type cyclonedx`) so it travels with the digest.

**Q11b. How does keyless signing work and what does it buy you?**
`cosign sign` with no key: the workflow's OIDC token (claims `repo`, `ref`, `workflow`) is exchanged at Fulcio for a short-lived signing certificate, the signature is recorded in the Rekor transparency log and stored next to the image in the registry. Verification pins the *identity* (`--certificate-identity-regexp 'https://github.com/<owner>/<repo>/.*' --certificate-oidc-issuer https://token.actions.githubusercontent.com`), so a image pushed by anything other than this pipeline fails even with a valid GHCR login. Nothing to rotate or leak. The other half is `k8s/policies/kyverno/verify-image-signature.yaml`: a Kyverno `verifyImages` rule in the `shopflow` namespace whose keyless attestor is pinned to this exact workflow on `main` (subject + GitHub OIDC issuer + Rekor), with `mutateDigest: true` so the pod runs the digest that was verified. It ties CI signing to EKS admission; it is the one policy CI cannot test offline.

**Q11c. Kustomize or Helm for your own services?**
ShopFlow ships both for the same Deployment/Service/HPA/PDB: Kustomize overlays in `k8s/` (what Argo CD syncs) and a generic chart `helm/shopflow-service` with one small values file per service (`helm/values/payment-service.yaml` is ~15 lines: name, image, port, DB name/secret key, Kafka/OTLP env). `helm/README.md` has the comparison: Kustomize = copy + patch, no logic, very readable, but `k8s/base/<service>` is copied per service; Helm = template + values, one chart for N services, `helm history`/`helm rollback` per release, and the standard for vendor software (ingress-nginx, External Secrets, Argo CD). Rule of thumb from the README: when you copy `k8s/base/<service>` for the fourth time, you want a chart — payment-service *was* the fourth copy. Either way the output is validated the same: `helm template ... | kubeconform -strict -`.

**Q12. How do you make pipelines fast?**
Path filters, parallel jobs, `needs` only where required, matrix, dependency caching (`cache: maven`), Docker layer cache (`type=gha`, per-service scope, `mode=max`), cancel superseded runs, fail fast (cheap checks first).

**Q13. Jenkins: Declarative vs Scripted pipeline?**
Declarative has a fixed structure (`pipeline { agent; stages; post }`), easier to read and validate; Scripted is full Groovy, more flexible. Most teams use Declarative + shared libraries for reuse.

**Q14. Your deploy broke prod. What do you do?**
Mitigate first, following `docs/runbooks/Rollback.md`: with a Rollout the canary analysis should already have aborted; otherwise roll back to the previous SHA (`git revert` with GitOps or `rollout undo`), or kill switch via feature flag. Confirm recovery with metrics (5xx ratio, latency, the alert resolving). Then root cause, blameless postmortem (`docs/runbooks/postmortem-template.md`), and ask why the canary analysis did not catch it — wrong metric, too little traffic, too short a step.

**Q15. How does your canary analysis decide, and where can it lie?**
`AnalysisTemplate error-ratio`: a Prometheus query of 5xx over total for the canary pods only (`rollouts_pod_template_hash!=""`), every minute, 5 measurements, `successCondition: result[0] < 0.02`, `failureLimit: 3`. It can lie in three ways: (1) **weight without a router** — Argo Rollouts without ALB/NGINX/Istio integration can only approximate `setWeight` by the pod ratio, so with 3 replicas "10%" is one pod in four, 25%; (2) **metric latency** — a 15 s scrape plus a `[2m]` rate means a bad canary needs 2–3 minutes before the ratio is trustworthy, which is why the first pause is 5 min and analysis has `count: 5`; (3) **sparse traffic** — at low RPS one 5xx is already > 2%, so the step needs a minimum-request guard or synthetic load. A good analysis also compares canary against a *baseline* of the stable version, not the whole fleet, so a cluster-wide incident does not abort an innocent rollout.

**Q16. Pre-commit vs CI — why both?**
Pre-commit gives the developer a 5-second answer on their laptop (`.pre-commit-config.yaml`: yamllint, hadolint, gitleaks, terraform fmt/tflint, trailing whitespace, large files) and keeps secrets from ever entering a commit; CI is the **enforced** gate that cannot be skipped with `--no-verify`, runs the same versions for everyone, and adds what a laptop cannot do cheaply (full-history gitleaks, the AWS tflint ruleset download, kyverno, promtool in Docker). Same tools, same configs (`.yamllint.yaml`, `.hadolint.yaml`, `.gitleaks.toml`) so there is no "passes locally, fails in CI" drift. `make lint` is the middle ground when pre-commit is not installed.

**Q17. How do you test alert rules and admission policies before they reach a cluster?**
Alerts: `promtool test rules` with synthetic series in `monitoring/tests/alerts_test.yml` — e.g. 10 of 100 requests return 503 for 20 minutes → `HighErrorRate` must fire with these exact labels/annotations; 2 of 100 → must **not** fire; 10% 5xx for 90 minutes → `CheckoutSLOBurnRateFast` fires after the 1h window fills; 1% slow but no 5xx → stays silent. Policies: `kyverno apply` on the rendered prod overlay (must pass) and on `tests/bad-pod.yaml` (must fail, the step greps for `fail: 0` and exits 1). Both run on every PR, so a broken PromQL label or an over-broad `match:` is caught at review time.

---

## 13. Scenario questions

**S1. "Tests pass locally but fail in CI."**
Environment differences: Java version (CI uses 21 via setup-java — use the Maven Wrapper and toolchains), time zone/locale, test order dependence, ports, missing env vars, flaky timing. Use the uploaded surefire reports artifact; reproduce in a container with the same image.

**S2. "The pipeline takes 25 minutes."**
Measure per step. Cache Maven and Docker layers, parallelise jobs/matrix, split slow integration tests, path filters in monorepo, cancel superseded runs, bigger runners for build-heavy jobs.

**S3. "A developer committed an AWS key."**
Revoke/rotate the key immediately (removing from git history is not enough — it's already exposed), check CloudTrail for misuse, purge history (git filter-repo) if needed, enable secret scanning push protection / gitleaks pre-commit, move to OIDC.

**S4. "Trivy blocks the release on a CRITICAL CVE in the base image, release is urgent."**
Check whether a fixed base image exists → bump (Dependabot may already have a PR) and rebuild. If no fix and not exploitable in our context, documented temporary exception in `.trivyignore` with expiry and owner, approved by security. Never silently disable the scan.

**S5. "How do you promote the same build to staging and prod?"**
Build once, tag by SHA, deploy that tag to staging overlay; after tests/approval, set the *same* tag in the prod overlay. Never rebuild for prod.

**S6. "Someone changed a Deployment in prod with kubectl edit and Argo CD reverted it."**
That's self-heal working — git is the source of truth. Make the change via PR. For emergencies, either commit quickly or temporarily disable auto-sync for that app, then reconcile git.

**S7. "New version needs a column that old code doesn't know about; we do rolling updates."**
Expand: add nullable column (old code ignores it), deploy new code that writes it, backfill, then later add constraints. Never drop/rename in the same release as the code change.

---

## 14. Common mistakes

- Deploying `:latest`; rebuilding per environment instead of promoting one artifact.
- Long-lived cloud keys in CI secrets instead of OIDC; no `permissions:` block.
- Scanning after push (or not failing the build on findings).
- Unpinned third-party actions; no Dependabot for actions.
- Pipelines that only run on main (no PR validation).
- No path filters in monorepos → every change runs everything.
- Skipping manifest validation → broken YAML found only at deploy time.
- Breaking DB migrations in the same release as the code; editing applied Flyway scripts.
- No rollback plan; relying on `kubectl rollout undo` while Argo CD self-heal reverts it.
- Treating feature flags as permanent (flag debt).
- Secrets in git (even "dev" ones) — ShopFlow keeps dev throwaway values only in the dev overlay and prod secrets out of git; gitleaks allowlists them explicitly instead of disabling the rule.
- Linting only in CI (or only on the laptop): pre-commit without CI can be bypassed, CI without pre-commit is a 10-minute feedback loop for a missing newline.
- Alert rules that are syntax-checked but never unit-tested (the `spring_kafka_listener_*` metric that did not exist — see [07](07-observability-sre.md)).

> Tip: "Build once, deploy many" aur "git is the source of truth" — ye do lines CI/CD interview mein bolni hi bolni hain.
