# 06 — CI/CD, GitOps & DevSecOps (Interview Notes)

> Source of truth in this repo:
> - `.github/workflows/shopflow.yml` — ShopFlow pipeline: test → validate manifests/config + Terraform fmt/validate → build 3 images + Trivy scan → push to GHCR (main only) → `deploy-manifests` pins the SHA into the prod overlay
> - `.github/workflows/ci.yml` — older pipeline for `Devops/` and `microservice01/` (matrix build, docker build, kubeconform)
> - `.github/dependabot.yml` — weekly Maven, Docker, GitHub Actions and Terraform updates
> - `shopflow/Jenkinsfile` — the same stages as a Declarative Jenkins pipeline (matrix over the three services)
> - `shopflow/argocd/application-dev.yaml`, `application-prod.yaml` — Argo CD Applications (dev: manual sync; prod: automated + prune + selfHeal)
> - `shopflow/k8s/overlays/prod/kustomization.yaml` — image tags written by CI (`newTag: set-by-ci` until the first push to main)
>
> Honest scope: the repo has CI, image publishing, a **GitOps CD step** (commit of the SHA to the prod overlay) and Argo CD manifests. What is not in the repo: a running Argo CD instance or cluster credentials — the pipeline never touches a cluster, by design.

---

## 0. Cheat table

| Concept | In ShopFlow | Where |
|---|---|---|
| Triggers | `push` to `main` + `pull_request`, filtered by `paths: ["shopflow/**", "infra/**", ".github/workflows/shopflow.yml"]` | `shopflow.yml` |
| Jobs | `test`, `validate-manifests`, `terraform` (parallel) → `image` (`needs: [test, validate-manifests]`) → `deploy-manifests` (`needs: [image]`, main only) | `shopflow.yml` |
| Matrix | `service: [order-service, inventory-service, notification-service]` | `image` job |
| Terraform | `terraform fmt -check -recursive`, `init -backend=false`, `validate` on `infra/terraform/aws` (no plan/apply — needs the OIDC role) | `terraform` job |
| GitOps step | `kustomize edit set image` ×3 with `${{ github.sha }}` in `k8s/overlays/prod`, commit + push with `GITHUB_TOKEN` (`contents: write`) | `deploy-manifests` job |
| Caching | `setup-java cache: maven`; Docker `cache-from/to: type=gha,scope=<service>` | `shopflow.yml` |
| Permissions | top-level `contents: read`; `image` job adds `packages: write` | `shopflow.yml` |
| Concurrency | `group: ${{ github.workflow }}-${{ github.ref }}`, `cancel-in-progress: true` | `shopflow.yml` |
| Tagging | full git SHA (+ `latest` on default branch) via `docker/metadata-action` | `image` job |
| Security | Trivy (HIGH/CRITICAL, ignore-unfixed, exit 1), Dependabot | `shopflow.yml`, `dependabot.yml` |
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

ShopFlow today = **CI + artifact publishing** (immutable image per commit on main). Adding CD = update `overlays/prod` image tag (GitOps) — §8.

---

## 2. The ShopFlow pipeline, stage by stage

```
                    PR or push to main touching shopflow/** or infra/**
                                   │
           ┌───────────────────────┼───────────────────────────────┐
           ▼                       ▼                               ▼
 ┌──────────────────────┐ ┌──────────────────────────────────┐ ┌──────────────────────────┐
 │ test                 │ │ validate-manifests               │ │ terraform                │
 │ checkout, JDK 21     │ │ install kustomize v5.7.1 +       │ │ setup-terraform 1.13.3   │
 │ (temurin, maven cache) │   kubeconform v0.6.7             │ │ fmt -check -recursive    │
 │ ./mvnw -B verify     │ │ for each overlay (dev, prod):    │ │ init -backend=false      │
 │  (31 tests: H2,      │ │   kustomize build | kubeconform  │ │ validate                 │
 │   EmbeddedKafka,     │ │   -strict -summary -             │ │ (infra/terraform/aws)    │
 │   WireMock, jwt())   │ │ promtool check rules alert-rules │ └──────────────────────────┘
 │ on failure: upload   │ │ docker compose config --quiet    │
 │  surefire-reports    │ └───────────────┬──────────────────┘
 └──────────┬───────────┘                 │
            └──────────────────┬──────────┘
                               ▼  needs: [test, validate-manifests]
             ┌─────────────────────────────────────────────────────────────────┐
             │ image  (matrix: order-service, inventory-service, notification-service)
             │ setup-buildx                                                    │
             │ metadata-action → tags: <sha>, latest(main)                     │
             │ build (load: true, GHA cache per service)                       │
             │ Trivy scan  → fail on fixable HIGH/CRITICAL                     │
             │ ── only on refs/heads/main ──                                   │
             │ login ghcr.io (GITHUB_TOKEN)                                    │
             │ build-push (cache hit → fast) → GHCR                            │
             └───────────────────────────────┬─────────────────────────────────┘
                                             ▼  needs: [image], main only, permissions: contents: write
             ┌─────────────────────────────────────────────────────────────────┐
             │ deploy-manifests                                                │
             │ kustomize edit set image shopflow/<svc>=ghcr.io/<owner>/shopflow-<svc>:<sha>  (×3)
             │ kustomize build . > /dev/null   (still renders?)                │
             │ git commit "deploy(prod): pin images to <sha7>" && git push     │
             │   → Argo CD (argocd/application-prod.yaml) syncs overlays/prod  │
             └─────────────────────────────────────────────────────────────────┘
```

### Stage notes (things to say out loud)
1. **Path filters** — a change only in `Devops/` doesn't run the ShopFlow pipeline (monorepo efficiency). The workflow file itself is in the paths so editing the pipeline tests it.
2. **`test`** — `./mvnw -B verify` uses the Maven Wrapper (same Maven version everywhere), `-B` batch mode (no colour/interactive output). `verify` runs unit + integration tests (Spring Boot tests with H2 in PostgreSQL mode, WireMock for inventory — see [01-project-walkthrough.md](01-project-walkthrough.md)). Surefire reports uploaded only on failure for debugging.
3. **`validate-manifests`** — "shift left" for infrastructure:
   - `kustomize build` proves overlays render (broken patch paths fail here).
   - `kubeconform -strict` validates against K8s JSON schemas (typos like `replcas`, wrong types). `-strict` rejects unknown fields.
   - `promtool check rules` validates PromQL syntax of `monitoring/alert-rules.yml` (run inside the `prom/prometheus:v3.5.0` image with `--entrypoint promtool`). Honest gap: despite the step name, `prometheus.yml` itself is not checked — `promtool check config` would do it.
   - `docker compose config --quiet` validates compose YAML (anchors, merge keys).
   Downloaded tools are installed into `${{ runner.temp }}` and added to `$GITHUB_PATH`. Tools are version-pinned for reproducibility.
4. **`image`** — matrix fans out per service (three). Builds once with `load: true` so the image exists in the runner's Docker for Trivy → scans **before** pushing (a vulnerable image never reaches the registry). Push step rebuilds with `cache-from` → effectively instant.
   **`terraform`** — runs in parallel: `fmt -check`, `init -backend=false` (providers only, no state bucket), `validate`. A real deploy job would assume the OIDC role from `ci-oidc.tf`, `plan` on PRs and `apply` on main behind an approval environment (the workflow comment says exactly this).
   **`deploy-manifests`** — the GitOps hand-off (details in §8).
5. **`concurrency`** — a new push to the same branch/PR cancels the older run (saves minutes, and avoids an older run pushing after a newer one).
6. **`permissions`** — least privilege: whole workflow `contents: read`; only `image` gets `packages: write`.

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
- Docker: `cache-from: type=gha,scope=${{ matrix.service }}` / `cache-to: type=gha,mode=max,...`. `scope` keeps the three services' caches separate (otherwise they overwrite each other). `mode=max` caches all stages (build + extract), not only final layers.

---

## 4. Jenkins basics (very common in Indian companies)

Architecture: **controller** (UI, scheduling, config) + **agents** (run builds; static VMs, Docker, or Kubernetes pods via the Kubernetes plugin). Pipelines as code in a `Jenkinsfile` (Declarative or Scripted Groovy). Credentials stored in Jenkins credentials store; plugins for everything (Git, Docker, Kubernetes, SonarQube, Slack).

The repo has a real one: `shopflow/Jenkinsfile` — `agent any`, `options { timestamps(); disableConcurrentBuilds(); buildDiscarder(logRotator(numToKeepStr: '30')); timeout(45 min) }`, `tools { jdk 'temurin-21' }`, `IMAGE_TAG = env.GIT_COMMIT`; stages **Test** (`./mvnw -B verify`, `junit` report in `post { always }`), **Validate manifests** (kustomize + kubeconform + promtool in the Prometheus image), **Build & scan images** (`matrix { axis SERVICE: order-service, inventory-service, notification-service }` → `docker build` → `trivy image --exit-code 1`), **Push images** (`when { branch 'main' }`, `withCredentials(usernamePassword 'ghcr')`), **Pin prod overlay** (`sshagent(['github-deploy-key'])`, `kustomize edit set image` ×3, commit + push), `post { failure { echo ... } }` (real life: `slackSend`). Its header comment: *"Same pipeline as `.github/workflows/shopflow.yml`, written for Jenkins; the stages are identical, only the plumbing differs."* Simplified sketch of that file:
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

ShopFlow pipeline is trunk-shaped: PRs get full CI (no push), `main` publishes images. GitHub Flow = trunk-based with PRs. Protect `main` with branch protection: required status checks (by their job `name:` — "Unit + integration tests", "Validate K8s + monitoring config", "Image order-service", "Image inventory-service"), required reviews, no force push.

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
| **Rolling** | Replace pods gradually | No extra infra, default | Two versions live together; slow rollback for big fleets | **ShopFlow**: `maxSurge: 1, maxUnavailable: 0` |
| **Blue-green** | Full new env (green), switch traffic at once | Instant switch & rollback, test green before switch | 2× resources; DB must work for both | Two Deployments + flip Service selector; Argo Rollouts `blueGreen` |
| **Canary** | Send small % (5%→25%→100%) to new version, watch metrics | Limits blast radius, data-driven | Needs traffic splitting + good metrics | Argo Rollouts / Flagger with ingress/Gateway API weights or service mesh |
| **Feature flags** | Deploy code dark, enable per user/% at runtime | Decouples deploy from release, instant kill switch | Flag debt, testing combinations | LaunchDarkly, Unleash, Flagsmith, Spring config |
| A/B testing | Route by user segment to measure business metric | Product experiments | Not a safety mechanism per se | Mesh/ingress header routing |
| Shadow | Mirror traffic to new version, discard responses | Zero user impact | Side effects (writes!) | Mesh mirroring |

Canary with automated analysis (Argo Rollouts idea, applied to ShopFlow's metrics):
```yaml
strategy:
  canary:
    steps:
      - setWeight: 10
      - pause: { duration: 5m }
      - analysis:
          templates: [{ templateName: error-rate }]   # PromQL: same 5xx ratio as HighErrorRate alert < 0.05
      - setWeight: 50
      - pause: { duration: 10m }
```
> Tip: Interview mein trade-off zaroor bolo — "blue-green is fast rollback but double cost; canary is safer but needs metrics + traffic splitting".

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

The tag-bump job in `shopflow.yml` (simplified — the real one sets all three images and has no `environment:` gate yet):
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
| Secret scanning | GitHub secret scanning + push protection, gitleaks, trufflehog | Not configured in repo — add gitleaks step / enable push protection |
| SAST (code) | CodeQL, SonarQube, Semgrep, SpotBugs | Not in repo — CodeQL for Java is a 10-line workflow |
| SCA / dependency scan | Dependabot, OWASP Dependency-Check, Snyk, `trivy fs` | **Dependabot** weekly for Maven (Spring deps grouped), Docker base images, GitHub Actions |
| IaC / manifest scan | kubeconform (schema), Checkov, kube-linter, Trivy config, Kyverno policies | **kubeconform** (schema only) |
| Container image scan | Trivy, Grype, Snyk, ECR scan | **Trivy**: `HIGH,CRITICAL`, `ignore-unfixed: true`, `exit-code: "1"` |
| SBOM | Syft, `trivy image --format cyclonedx`, buildx `sbom: true` | Not yet — add `sbom: true` / `provenance` in build-push-action |
| Signing / provenance | cosign (keyless via OIDC), SLSA provenance, admission verification (Kyverno/Connaisseur) | Not yet |
| DAST | OWASP ZAP against staging | Not yet |
| Runtime | Falco, PSA restricted, NetworkPolicies | PSA-compliant securityContext + NetworkPolicies in k8s/ |
| Least privilege CI | `permissions:` block, OIDC, environments | **Yes**: `contents: read`, `packages: write` only for `image` |

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
| Blue-green | Flip Service/LB back to blue |
| Canary | Abort → traffic back to stable (automatic on failed analysis) |
| Feature flag | Turn flag off — no deploy needed (fastest) |
| Bad DB migration | **Roll forward** with a fix migration; backups/PITR as last resort |

Immutable SHA tags are what make rollback reliable: "previous version" is an exact image, not "whatever latest was yesterday".

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
On PRs and pushes to main touching `shopflow/` or `infra/`, three jobs run in parallel: tests with `./mvnw -B verify` on JDK 21 with Maven cache (31 tests incl. embedded Kafka and fake JWTs), config validation — every Kustomize overlay rendered and checked with kubeconform strict, Prometheus rules with promtool, the compose file — and Terraform `fmt`/`validate`. If tests and validation pass, a matrix job builds each of the three service images with Buildx and GHA cache, tags them with the full commit SHA, scans with Trivy and fails on fixable HIGH/CRITICAL CVEs. Only on main does it log in to GHCR with the GITHUB_TOKEN and push, and then a `deploy-manifests` job pins the SHA into `k8s/overlays/prod` and commits it; Argo CD syncs that overlay. Concurrency cancels superseded runs; permissions are read-only except `packages: write` for the image job and `contents: write` for the pin job.

**Q2. CI vs continuous delivery vs continuous deployment?**
CI: merge often, build and test automatically. Continuous delivery: every change produces a deployable artifact and deploying to prod is a manual decision. Continuous deployment: no manual gate. My project is at GitOps continuous **deployment** for prod (every main commit is pinned and auto-synced by Argo CD); adding a GitHub `environment: production` with required reviewers on the pin job would turn it into continuous delivery with an approval gate.

**Q3. Why scan before pushing?**
So a vulnerable image never lands in the registry where something could pull it. I build with `load: true`, scan the local image, and only then push; the second build is a cache hit.

**Q4. How do you avoid storing cloud credentials in CI?**
OIDC: the workflow gets `id-token: write`, exchanges GitHub's JWT for short-lived AWS credentials via an IAM role whose trust policy is restricted to my repo and branch. For GHCR, the built-in `GITHUB_TOKEN` with `packages: write` is enough.

**Q5. What does `concurrency` do in your workflow?**
Groups runs by workflow + ref; a new push cancels the in-progress older run for the same branch/PR. Saves runner minutes and prevents an older commit's image from being pushed after a newer one.

**Q6. Blue-green vs canary vs rolling?**
(Table §7.) Rolling is the default and what I use; blue-green for instant switch/rollback at 2× cost; canary for gradual exposure with metric-based analysis — I'd use the same 5xx-ratio PromQL as my HighErrorRate alert as the canary gate.

**Q7. What is GitOps, and how is it done here?**
Git is the source of truth; an in-cluster agent like Argo CD pulls and reconciles. CI's `deploy-manifests` job runs `kustomize edit set image` with the new SHA for all three services in `overlays/prod` and commits it; `argocd/application-prod.yaml` syncs that path with prune and self-heal (dev is synced manually). Rollback is `git revert` of the pin commit. No cluster credentials in CI. Improvement: a separate config repo so app and deploy history stay apart.

**Q8. How do you handle DB schema changes with zero downtime?**
Flyway versioned migrations, backward compatible, using expand-contract across releases so old and new pods can coexist during a rolling update. Hibernate `validate` catches drift at startup.

**Q9. Trunk-based vs GitFlow — what do you prefer?**
Trunk-based for microservices with CI/CD: small PRs, short-lived branches, feature flags for unfinished work, fast feedback. GitFlow fits scheduled releases with multiple supported versions.

**Q10. Why pin GitHub Actions to a commit SHA?**
Tags are mutable; a compromised action can retag malicious code and steal secrets (tj-actions incident). SHA pins are immutable; Dependabot updates them.

**Q11. What is an SBOM and why care?**
Software Bill of Materials — list of every component and version in an artifact (CycloneDX/SPDX). When a new CVE drops (like Log4Shell), you can query which images contain the library instantly instead of rescanning everything.

**Q12. How do you make pipelines fast?**
Path filters, parallel jobs, `needs` only where required, matrix, dependency caching (`cache: maven`), Docker layer cache (`type=gha`, per-service scope, `mode=max`), cancel superseded runs, fail fast (cheap checks first).

**Q13. Jenkins: Declarative vs Scripted pipeline?**
Declarative has a fixed structure (`pipeline { agent; stages; post }`), easier to read and validate; Scripted is full Groovy, more flexible. Most teams use Declarative + shared libraries for reuse.

**Q14. Your deploy broke prod. What do you do?**
Mitigate first: roll back to the previous SHA (`git revert` with GitOps or `rollout undo`), or kill switch via feature flag. Confirm recovery with metrics (5xx ratio, latency). Then root cause, blameless postmortem, add a test/canary check to catch it next time.

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
- Secrets in git (even "dev" ones) — ShopFlow keeps dev throwaway values only in the dev overlay and prod secrets out of git.

> Tip: "Build once, deploy many" aur "git is the source of truth" — ye do lines CI/CD interview mein bolni hi bolni hain.
