# 04 — Docker (Interview Notes)

> Source of truth in this repo:
> - `shopflow/Dockerfile` — one multi-stage Dockerfile for both services (build arg `SERVICE`)
> - `shopflow/.dockerignore`
> - `shopflow/docker-compose.yml` — postgres + order-service + inventory-service + prometheus + grafana
> - Older learning versions: `Devops/Dockerfile`, `microservice01/Dockerfile`, Docker commands in `notes.txt`
>
> Rule: when you talk about "my project", only quote what is in these files. For anything else say
> "not in the project — this is how I would do it".

---

## 0. Cheat table (revise this 10 min before the interview)

| Topic | One-line answer | Where in repo |
|---|---|---|
| Image vs container | Image = read-only layered template; container = running instance + thin writable layer | — |
| Multi-stage | Build with JDK+Maven, ship only JRE + app | `shopflow/Dockerfile` (3 stages: `build`, `extract`, runtime) |
| Layer caching | Each instruction = layer; cache breaks at first changed layer and everything after | BuildKit `--mount=type=cache,target=/root/.m2` |
| Layered jar | Split Spring Boot fat jar into `dependencies/`, `spring-boot-loader/`, `snapshot-dependencies/`, `application/` | `java -Djarmode=tools -jar app.jar extract --layers --launcher` |
| Non-root | Fixed numeric UID `10001` so K8s `runAsNonRoot` can verify it | `USER 10001` |
| JVM memory | Heap = % of container limit, crash on OOM | `JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError"` |
| Entrypoint | Exec form so `java` is PID 1 and receives SIGTERM | `ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]` |
| Base image | `eclipse-temurin:21-jre-alpine` (JRE, not JDK) | runtime stage |
| Compose ordering | `depends_on: condition: service_healthy` + healthchecks | `docker-compose.yml` |
| Tags | Immutable git-SHA tag in CI, `latest` only as convenience on main | `.github/workflows/shopflow.yml` (`docker/metadata-action`) |
| Scan | Trivy, fail on fixable HIGH/CRITICAL | `.github/workflows/shopflow.yml` |

---

## 1. Images vs containers

```
            docker build                      docker run
Dockerfile ─────────────▶  IMAGE  ─────────────────────────▶  CONTAINER
(recipe)                  (read-only layers,                 (image layers + thin
                           identified by digest sha256:...)   writable layer + process
                                                              + namespaces + cgroups)
```

- **Image**: an ordered stack of read-only filesystem layers + JSON config (ENV, ENTRYPOINT, USER, exposed ports...). Identified by a **content digest** (`sha256:...`). Tags (`:21-jre-alpine`, `:latest`) are just movable pointers to a digest.
- **Container**: a process started from an image. Gets a thin **copy-on-write** writable layer on top (OverlayFS). Deleting the container deletes that layer → data is lost unless you use volumes.
- Under the hood a container is just a Linux process isolated by:
  - **namespaces** (what it can *see*): pid, net, mnt, uts, ipc, user
  - **cgroups** (what it can *use*): CPU, memory, pids
  - plus capabilities, seccomp, AppArmor/SELinux (what it is *allowed* to do)
- **Container vs VM**: containers share the host kernel → start in ms, MBs in size; VMs have their own kernel via a hypervisor → stronger isolation, heavier.

> Tip: Interviewer bole "container is a lightweight VM" — politely correct karo: "it's an isolated process sharing the host kernel".

**OCI**: images follow the Open Container Initiative spec, so the same image runs on Docker, containerd (what Kubernetes uses now), CRI-O, Podman. Kubernetes removed dockershim in 1.24 — Docker-built images still work fine because they are OCI images.

---

## 2. Layers and build cache

Every `RUN`, `COPY`, `ADD` creates a filesystem layer. (`ENV`, `WORKDIR`, `USER`, `LABEL` etc. only change metadata.)

Cache rule: Docker walks the Dockerfile top to bottom; for each instruction it checks whether an identical layer exists (same instruction + same parent + for `COPY` the same file checksums). **At the first miss, every following instruction is rebuilt.**

So: **put rarely-changing things first, frequently-changing things last.**

### Two caching approaches in this repo

**a) Classic "copy pom first" — `Devops/Dockerfile`, `microservice01/Dockerfile`:**
```dockerfile
COPY pom.xml .
RUN mvn -B -q dependency:go-offline     # cached until pom.xml changes
COPY src ./src
RUN mvn -B -q package -DskipTests && cp target/*.jar app.jar
```
Changing Java code only invalidates the last two steps — dependencies stay cached in a layer.

**b) BuildKit cache mount — `shopflow/Dockerfile`:**
```dockerfile
# syntax=docker/dockerfile:1
FROM maven:3.9-eclipse-temurin-21 AS build
ARG SERVICE
WORKDIR /src
COPY . .
RUN --mount=type=cache,target=/root/.m2 \
    mvn -B -q -pl ${SERVICE} -am package -DskipTests
```
- ShopFlow is a **multi-module** Maven project (parent `pom.xml` + `order-service` + `inventory-service`), so "copy only pom" would mean copying three poms in the right structure. Instead it does `COPY . .` and relies on a **cache mount**: `/root/.m2` persists across builds on the builder, but is **not** stored in the image.
- `-pl ${SERVICE} -am` = build only the selected module (`--projects`) plus what it depends on (`--also-make`).
- Trade-off: `COPY . .` means any source change re-runs `mvn package`, but dependencies come from the cache mount, so it's still fast locally. In CI the GHA cache (`cache-from: type=gha`) exports **layers, not cache mounts** — and since every commit changes the `COPY . .` layer, CI re-downloads Maven deps on each build. Honest answer if asked (fix: copy-poms-first layer, or a cache-mount export action).

| | Layer cache | Cache mount (`--mount=type=cache`) |
|---|---|---|
| Stored in image? | Yes (it *is* the image) | No |
| Invalidated by | Any change in instruction/inputs above | Never by Dockerfile changes; it's a persistent dir on the builder |
| Typical use | OS packages, dependency layer | `~/.m2`, `~/.gradle`, `~/.npm`, apt cache |
| Needs | Any builder | BuildKit |

Other BuildKit mounts worth knowing: `--mount=type=secret,id=npmrc` (secret available only during that RUN, never in a layer) and `--mount=type=ssh`.

### Commands
```bash
docker history shopflow/order-service:local         # see layers + sizes
docker build --no-cache -t x .                       # ignore cache (notes.txt uses this)
DOCKER_BUILDKIT=1 docker build --progress=plain .    # see CACHED vs executed steps
docker buildx du / docker builder prune               # builder cache usage / cleanup
```

---

## 3. Dockerfile instructions — the confusing pairs

### CMD vs ENTRYPOINT

| | ENTRYPOINT | CMD |
|---|---|---|
| Purpose | The executable that always runs | Default args (or default command) |
| Override at run | `docker run --entrypoint ...` | Anything after the image name: `docker run img arg1` |
| Combined | `ENTRYPOINT ["java"]` + `CMD ["-jar","app.jar"]` → `java -jar app.jar` | |
| In K8s | `command:` overrides ENTRYPOINT | `args:` overrides CMD |

ShopFlow uses only ENTRYPOINT:
```dockerfile
ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]
```

**Exec form vs shell form** (most important follow-up):
```dockerfile
ENTRYPOINT ["java", "-jar", "app.jar"]   # exec form: java is PID 1, gets SIGTERM directly
ENTRYPOINT java -jar app.jar             # shell form: /bin/sh -c is PID 1; sh does NOT forward SIGTERM
```
With shell form, `docker stop` / pod termination sends SIGTERM to `sh`, the JVM never sees it (some shells `exec` a single simple command, but don't rely on it; shell form also ignores `CMD`/`docker run` args), Spring's graceful shutdown (`server.shutdown: graceful`) never runs, and after the grace period you get SIGKILL. If you must use a shell wrapper, end it with `exec java ...` — exactly what `microservice01/helperdeployEmptyDirVolume.yaml` does:
```yaml
args: ["-c", "echo '...' > /cache/myfile.txt && exec java -jar /app/app.jar"]
```
PID 1 detail: the kernel does not apply default signal actions to PID 1, so a PID-1 process that installs no SIGTERM handler ignores it. The JVM installs one, so exec-form Java is fine; for other apps (or to reap zombies) use `docker run --init` / `tini`.

### COPY vs ADD
- `COPY src dst` — plain copy from build context (or `--from=<stage>`). **Use this by default.**
- `ADD` — also auto-extracts local tar archives and can fetch URLs. Surprising behaviour → avoid unless you want tar extraction. For URLs prefer `RUN curl ... && verify checksum`.

### ARG vs ENV

| | ARG | ENV |
|---|---|---|
| Available | Only during build | During build **and** at runtime in the container |
| Set via | `--build-arg SERVICE=order-service` | `-e` / compose `environment:` / K8s `env:` |
| Scope | Per stage — must be re-declared after each `FROM` | Inherited by later instructions in same stage |
| Secrets? | No — visible in `docker history` | No — visible in `docker inspect` |

ShopFlow example — note `ARG SERVICE` is declared **before the first FROM and again inside each stage**, because an ARG before `FROM` is only usable in `FROM` lines; inside a stage it must be redeclared:
```dockerfile
ARG SERVICE
FROM maven:3.9-eclipse-temurin-21 AS build
ARG SERVICE                       # redeclare, else ${SERVICE} is empty here
...
FROM eclipse-temurin:21-jre-alpine AS extract
ARG SERVICE                       # again
COPY --from=build /src/${SERVICE}/target/app.jar app.jar
```
`app.jar` exists because each service pom sets `<finalName>app</finalName>`.

Runtime ENV in ShopFlow:
```dockerfile
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError"
```

### Other instructions
- `WORKDIR` — creates + cd's; never `RUN cd ...` (it doesn't persist).
- `USER` — every following RUN/CMD/ENTRYPOINT runs as this user.
- `EXPOSE` — **documentation only**; doesn't publish anything. Publishing is `-p 8081:8081` or compose `ports:`. (`Devops/Dockerfile` has `EXPOSE 8080`; ShopFlow's Dockerfile omits it since it serves two different ports.)
- `HEALTHCHECK` — Docker-level health. ShopFlow defines healthchecks in compose instead; Kubernetes **ignores** Dockerfile HEALTHCHECK and uses probes.
- `LABEL` — metadata (OCI labels). CI adds them via `docker/metadata-action` (`labels: ${{ steps.meta.outputs.labels }}`).
- `# syntax=docker/dockerfile:1` — first line in `shopflow/Dockerfile`; tells BuildKit to use the latest stable 1.x Dockerfile frontend, so `RUN --mount` works regardless of the engine's built-in frontend version.

---

## 4. Multi-stage builds — ShopFlow walk-through

```
┌───────────────────────────┐   ┌──────────────────────────────┐   ┌──────────────────────────────────┐
│ stage 1: build            │   │ stage 2: extract             │   │ stage 3: runtime (final image)   │
│ maven:3.9-eclipse-        │   │ eclipse-temurin:21-jre-alpine│   │ eclipse-temurin:21-jre-alpine    │
│   temurin-21  (~500MB+)   │   │                              │   │                                  │
│ COPY . .                  │──▶│ COPY --from=build app.jar    │──▶│ addgroup/adduser 10001; USER     │
│ mvn -pl $SERVICE -am      │   │ java -Djarmode=tools -jar    │   │ COPY dependencies/               │
│   package (cache .m2)     │   │   app.jar extract --layers   │   │ COPY spring-boot-loader/         │
│ → target/app.jar          │   │   --launcher → out/          │   │ COPY snapshot-dependencies/      │
└───────────────────────────┘   └──────────────────────────────┘   │ COPY application/                │
         thrown away                    thrown away                │ ENV JAVA_TOOL_OPTIONS            │
                                                                   │ ENTRYPOINT JarLauncher           │
                                                                   └──────────────────────────────────┘
```

Why:
1. **Size** — no Maven, no JDK, no source code, no `~/.m2` in the final image.
2. **Security** — fewer packages → fewer CVEs, no compilers for an attacker.
3. **One Dockerfile for N services** — `--build-arg SERVICE=...`.

Build commands (from the header of `shopflow/Dockerfile`):
```bash
cd shopflow
docker build --build-arg SERVICE=order-service     -t shopflow/order-service .
docker build --build-arg SERVICE=inventory-service -t shopflow/inventory-service .
docker build --target build --build-arg SERVICE=order-service -t debug-build .   # stop at a stage
```

---

## 5. Spring Boot layered jars

A Spring Boot fat jar = your classes + ~60 MB of dependencies. If you `COPY app.jar` as one layer, every one-line code change produces a new ~70 MB layer to push, store and pull on every node.

`java -Djarmode=tools -jar app.jar extract --layers --launcher --destination out` (Spring Boot 3.3+ "tools" jarmode; older versions used `-Djarmode=layertools ... extract`) produces:

| Directory | Contents | Changes |
|---|---|---|
| `dependencies/` | Release third-party jars (Spring, Hibernate, Postgres driver...) | Rarely (dependency bump) |
| `spring-boot-loader/` | Boot's launcher classes | Only on Boot upgrade |
| `snapshot-dependencies/` | `-SNAPSHOT` deps | Sometimes |
| `application/` | Your compiled classes + `application.yml` | Every commit |

They're copied in that order → a code-only change rebuilds and pushes only the last layer (KBs). This is what the Dockerfile comment means by *"a code-only change pushes/pulls a few KB instead of ~60 MB"*.

`--launcher` keeps the exploded layout runnable with `org.springframework.boot.loader.launch.JarLauncher` (the Boot 3.2+ package name; old name was `org.springframework.boot.loader.JarLauncher`). Exploded classpath also starts slightly faster than nested jars.

Alternatives: Cloud Native Buildpacks (`mvn spring-boot:build-image`) or Jib — both produce layered images without a Dockerfile.

---

## 6. `.dockerignore`

`shopflow/.dockerignore`:
```
**/target/
.idea/
*.iml
k8s/
monitoring/
docker-compose.yml
*.md
```
Why it matters:
- The **build context** (everything in `.`) is sent to the daemon/BuildKit before building. Without ignore, `target/` dirs and IDE files are uploaded every time.
- Because ShopFlow does `COPY . .`, any file in the context affects the cache key. Editing a K8s manifest or alert rule would otherwise invalidate the Maven layer → `k8s/` and `monitoring/` are excluded.
- Security: keep `.env`, credentials, `.git` out of images. (Consider adding `.git` and `.env` here too.)

---

## 7. Image size & security

### Base image choice

| Base | Size (rough, uncompressed; varies by version — check `docker image ls`) | Shell? | Notes |
|---|---|---|---|
| `eclipse-temurin:21-jdk` | ~400 MB+ | yes | Has compiler — only for build stage |
| `eclipse-temurin:21-jre` (Ubuntu) | ~250 MB | yes | glibc, familiar tools |
| `eclipse-temurin:21-jre-alpine` | ~150–200 MB | yes (busybox) | **ShopFlow uses this**. musl libc, `wget` available (used by compose healthchecks) |
| `gcr.io/distroless/java21-debian12` | ~100–200 MB | **no** | No shell/package manager → smallest attack surface, harder to debug (`kubectl debug` ephemeral containers) |
| Custom `jlink` runtime | 60–80 MB | depends | Only the JDK modules you need |

Alpine caveat: musl vs glibc — rare issues with native libs (e.g., some Netty/gRPC native transports). For pure Java Spring apps it's fine.

### Run as non-root
```dockerfile
RUN addgroup -S -g 10001 app && adduser -S -u 10001 -G app app
USER 10001
```
- Root in a container = root on the host kernel if there's a container escape.
- **Numeric UID** is deliberate: Kubernetes `runAsNonRoot: true` can only verify a numeric UID. The older `Devops/Dockerfile` uses `USER spring` (a name) — K8s would reject it with `container has runAsNonRoot and image has non-numeric user (spring), cannot verify user is non-root` unless you also set `runAsUser`. ShopFlow's deployments set `runAsUser: 10001` to match.
- `readOnlyRootFilesystem: true` in K8s works because the app only needs `/tmp` (mounted as `emptyDir`).

### Security checklist
- Multi-stage, JRE-only, minimal base. Pin base images by tag (`21-jre-alpine`) — Dependabot `package-ecosystem: docker` in `.github/dependabot.yml` opens PRs for new base image versions weekly. Strictest: pin by digest `@sha256:...`.
- Non-root numeric user, drop capabilities (`capabilities.drop: ["ALL"]` in K8s).
- **No secrets** in `ENV`/`ARG`/layers. Deleting a file in a later layer does not remove it from the earlier layer. Use BuildKit `--mount=type=secret` for build-time secrets; inject runtime secrets via env from K8s Secrets (ShopFlow: `DB_PASSWORD` from `shopflow-db`).
- Scan images: **Trivy** in CI (`aquasecurity/trivy-action`, `severity: HIGH,CRITICAL`, `ignore-unfixed: true`, `exit-code: "1"`). Alternatives: Grype, Snyk, Docker Scout, ECR scanning.
- Sign images (cosign/Sigstore) and generate SBOM (Syft) — not in the project; mention as next step.

---

## 8. JVM in containers

Problem (old JVMs, Java 8 < u191): the JVM read the **host's** RAM/CPUs, sized the heap at 1/4 of a 64 GB node → container blew past its 512 MiB limit → **OOMKilled** by the kernel.

Modern JVMs (10+, 8u191+) are **container-aware** (`UseContainerSupport` on by default) and read cgroup limits. But default max heap is only 25% of the limit — wasteful. So ShopFlow sets:

```dockerfile
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError"
```

| Flag | Why |
|---|---|
| `-XX:MaxRAMPercentage=75` | Heap max = 75% of container memory limit. In K8s limit is `512Mi` → ~384 MiB heap. Remaining 25% for metaspace, thread stacks, code cache, direct buffers, GC. Don't go to 90–100% — non-heap memory will push you over the limit and get you OOMKilled. |
| `-XX:+ExitOnOutOfMemoryError` | On `java.lang.OutOfMemoryError`, exit immediately. A JVM after OOM is in an unknown state ("limping"); better to die and let Docker `restart: unless-stopped` / K8s restart it. Liveness probes might not catch a half-broken JVM. |
| `JAVA_TOOL_OPTIONS` (not `JAVA_OPTS`) | Read by the JVM itself, so it works with any ENTRYPOINT without a shell. `JAVA_OPTS` is only a convention used by shell scripts. JVM prints `Picked up JAVA_TOOL_OPTIONS: ...` on startup — that's normal. |

Two different "out of memory" events — know the difference:

| | Java `OutOfMemoryError` | Kernel **OOMKilled** |
|---|---|---|
| Who | JVM: heap (or metaspace) full | Linux cgroup: whole process RSS > container limit |
| Exit code | With `ExitOnOutOfMemoryError`: `3` | `137` (128 + SIGKILL 9), reason `OOMKilled` |
| Fix | Leak? Bigger heap? Heap dump | Lower `MaxRAMPercentage` or raise limit; check off-heap/threads |

CPU/GC: the JVM sizes GC threads and `ForkJoinPool` from the CPU count it detects (a CPU **limit**/quota lowers it; requests/shares are ignored since JDK 19). JVM ergonomics pick **SerialGC** when it sees < 2 CPUs **or** < ~1792 MB memory — so with a 512 MiB limit ShopFlow gets SerialGC regardless of CPU (fine for a small heap; force G1 with `-XX:+UseG1GC` if wanted). ShopFlow sets no CPU limit in K8s (see [05-kubernetes.md](05-kubernetes.md)). Override the count with `-XX:ActiveProcessorCount=2`.

Check inside a container:
```bash
docker run --rm -m 512m eclipse-temurin:21-jre-alpine \
  java -XX:MaxRAMPercentage=75 -XX:+PrintFlagsFinal -version | grep -E 'MaxHeapSize|UseContainerSupport'
```

---

## 9. Networking

| Driver | What | Use |
|---|---|---|
| `bridge` (default) | Private virtual network on the host (docker0); containers get private IPs; NAT for outbound; `-p` for inbound | Default for `docker run` |
| user-defined bridge | Same, but with **built-in DNS by container/service name** | Compose creates one per project |
| `host` | Container shares host network stack, no isolation, no `-p` needed | Perf-sensitive, Linux only |
| `none` | No network | Batch/isolated jobs |
| `overlay` | Multi-host (Swarm) | Rare now; K8s uses CNI instead |

Port publishing: `-p 8081:8081` = `HOST:CONTAINER`. In `notes.txt`: `docker run -d --name "nginxcontainer" -p 8080:80 nginx:latest` → host 8080 → container 80.

### Compose DNS — how ShopFlow services find each other
Compose project `name: shopflow` → network `shopflow_default`. Each service is reachable by its **service name**:
```yaml
DB_URL: jdbc:postgresql://postgres:5432/orders          # "postgres" = service name
INVENTORY_URL: http://inventory-service:8082            # container-to-container: container port
```
Prometheus scrapes `order-service:8081` and `inventory-service:8082` (`monitoring/prometheus.yml`), Grafana reaches `http://prometheus:9090` (datasource provisioning).

Classic mistake: using `localhost` inside a container — `localhost` is the container itself, not the host or another container. From container → host service use `host.docker.internal` (Docker Desktop; on Linux add `extra_hosts: ["host.docker.internal:host-gateway"]`).

Second classic mistake: using the **published** host port for container-to-container traffic. Containers talk on the container port over the compose network; `ports:` only matters from the host.

---

## 10. Storage: volumes vs bind mounts vs tmpfs

| | Named volume | Bind mount | tmpfs |
|---|---|---|---|
| Syntax | `pgdata:/var/lib/postgresql/data` | `./monitoring/prometheus.yml:/etc/prometheus/prometheus.yml:ro` | `--tmpfs /tmp` |
| Managed by | Docker (`/var/lib/docker/volumes`) | You (host path) | RAM |
| Use | DB data, persistent state | Config files, source code in dev | Scratch, secrets in memory |
| Survives `docker compose down` | Yes (unless `down -v`) | Yes (it's your file) | No |

ShopFlow compose uses both:
- **Named volume** `pgdata` for Postgres data (declared under top-level `volumes:`).
- **Bind mounts, read-only (`:ro`)** for config: `init-db.sh` into `/docker-entrypoint-initdb.d/`, prometheus.yml, alert-rules.yml, Grafana provisioning.
- `notes.txt` has the bind-mount example: `-v "D:/.../index.html:/usr/share/nginx/html/index.html"`.

Gotcha: `init-db.sh` runs **only on first start with an empty data dir**. If you change it, you must `docker compose down -v` to wipe `pgdata` — otherwise the script never re-runs. (The header comment in `k8s/base/postgres/init-db.sh`: "Runs once, on the first start of an empty Postgres data directory.")

---

## 11. Docker Compose — ShopFlow stack

```
                      host ports
   8081         8082          9090         3000
    │            │              │            │
┌───▼───────┐ ┌──▼──────────┐ ┌─▼────────┐ ┌─▼──────┐
│ order-    │─▶ inventory-  │ │prometheus│─▶grafana │
│ service   │ │ service     │ │ scrapes  │ │        │
└────┬──────┘ └────┬────────┘ │ /actuator│ └────────┘
     │ depends_on  │          │/prometheus
     │ healthy     │          └──────────┘
     ▼             ▼
   ┌─────────────────┐   volume: pgdata
   │ postgres:16-    │   init: init-db.sh → DBs "orders" + "inventory"
   │ alpine          │         (database-per-service)
   └─────────────────┘
          network: shopflow_default (DNS by service name)
```

Key features to talk about:

1. **YAML anchor / extension field** to avoid repetition:
   ```yaml
   x-service-defaults: &service-defaults
     restart: unless-stopped
     depends_on:
       postgres:
         condition: service_healthy
     deploy:
       resources:
         limits:
           memory: 512m          # same as K8s limit → MaxRAMPercentage behaves the same locally
   ...
   inventory-service:
     <<: *service-defaults
   ```
2. **Healthchecks + `depends_on: condition: service_healthy`**: plain `depends_on` only waits for the container to *start*, not for Postgres to *accept connections*. With the condition, the Java services start only after `pg_isready -U postgres` succeeds.
3. **App healthchecks hit Actuator readiness**:
   ```yaml
   healthcheck:
     test: ["CMD", "wget", "-qO-", "http://localhost:8082/actuator/health/readiness"]
     interval: 10s
     start_period: 40s     # JVM start-up grace; failures during this don't count
   ```
   `wget` works because the Alpine base has busybox. On distroless there's no wget → you'd need a different approach.
4. **`build:` + `image:`** together: compose builds with `args: SERVICE: ...` and tags the result `shopflow/order-service:local`.
5. Restart policy `unless-stopped` + `ExitOnOutOfMemoryError` → crash-and-restart behaviour similar to K8s.

Commands:
```bash
cd shopflow
docker compose up --build -d
docker compose ps                         # shows (healthy)/(unhealthy)
docker compose logs -f order-service
docker compose exec postgres psql -U postgres -c '\l'
docker compose config --quiet             # validate (CI runs this)
docker compose down                       # keep data
docker compose down -v                    # also delete pgdata volume
```

Compose vs Kubernetes: Compose = single host, dev / small deployments, no self-healing across nodes, no rolling updates, no autoscaling. That's why ShopFlow also has `k8s/`.

---

## 12. Registry, tagging, `:latest`

```
ghcr.io/<owner>/shopflow-order-service:<tag>
└──┬──┘ └──────────────┬─────────────┘ └─┬─┘
registry        repository              tag   (→ digest sha256:...)
```

CI (`.github/workflows/shopflow.yml`):
```yaml
env:
  IMAGE: ghcr.io/${{ github.repository_owner }}/shopflow-${{ matrix.service }}
...
- uses: docker/metadata-action@v6
  with:
    images: ${{ env.IMAGE }}
    tags: |
      type=sha,format=long,prefix=
      type=raw,value=latest,enable={{is_default_branch}}
```
- `type=sha,format=long,prefix=` → full 40-char git SHA, no `sha-` prefix.
- `type=raw,value=latest,enable={{is_default_branch}}` → `latest` only on main. (Don't put `#` comments inside a `|` block — they become part of the input string.)

### Why not `:latest` in deployments
- **Not immutable**: the same tag points to different images over time → you can't tell what's running, can't reproduce, rollback to "latest" is meaningless.
- K8s `imagePullPolicy` defaults to `Always` for `:latest` → every pod start hits the registry; and two pods on two nodes can run different code under the same tag.
- Changing a Deployment from `latest` to `latest` is **not a change** → no rollout happens.
- The older manifests (`Devops/backenddeploy.yaml` → `rajji01/backend-micro001:latest`) do exactly this — good "what I learned" story.

In ShopFlow, `k8s/overlays/prod/kustomization.yaml` never carries `latest`: the `deploy-manifests` job in `.github/workflows/shopflow.yml` runs, after the images are pushed on `main`,
```
kustomize edit set image shopflow/order-service=ghcr.io/<owner>/shopflow-order-service:${{ github.sha }}
```
and commits the result, so the overlay always names the exact commit that is deployed (until the first `main` build it holds the placeholder `set-by-ci`).
Best: deploy by **digest** (`image@sha256:...`) — truly immutable.

Login/push (manual, from `notes.txt`):
```bash
docker login
docker tag backend-micro001 rajji01/backend-micro001
docker push rajji01/backend-micro001
```
CI uses `docker/login-action` with `secrets.GITHUB_TOKEN` + `permissions: packages: write`, and pushes only when `github.ref == 'refs/heads/main'`.

---

## 13. Troubleshooting commands

```bash
docker ps -a                              # all containers incl. exited (see exit code)
docker logs -f --tail 200 <c>
docker inspect <c> --format '{{.State.ExitCode}} {{.State.OOMKilled}}'
docker inspect <c> --format '{{json .State.Health}}' | jq   # healthcheck history
docker exec -it <c> sh                    # alpine has sh, not bash
docker stats                              # live CPU/mem per container
docker top <c>                            # processes in container
docker events                             # die / oom / health_status events
docker run --rm -it --entrypoint sh <image>   # explore an image that crashes on start
docker image ls ; docker history <image>  # size per layer
docker system df ; docker system prune -a # disk usage / cleanup
docker network ls ; docker network inspect shopflow_default
docker cp <c>:/app/application/ ./out     # copy files out
```

Exit codes: `0` normal, `1` app error, `3` JVM exited due to `ExitOnOutOfMemoryError`, `126` not executable, `127` command not found, `137` SIGKILL (OOM or `docker kill` / stop timeout), `143` SIGTERM (graceful stop).

---

## 14. Interview Q&A

**Q1. What's the difference between an image and a container?**
An image is an immutable, layered filesystem plus config, identified by a digest. A container is a running process created from that image, with its own writable copy-on-write layer, isolated by namespaces and limited by cgroups. Many containers can run from one image.

**Q2. How did you reduce your image size?**
Multi-stage build: Maven + JDK only in the build stage; the final image is `eclipse-temurin:21-jre-alpine` plus the extracted Spring Boot layers. No source, no `.m2`, no build tools. Plus `.dockerignore` keeps `target/`, `k8s/`, `monitoring/` out of the context. Next step could be distroless or a jlink'd runtime.

**Q3. Explain layered jars and why you used them.**
I extract the fat jar with `-Djarmode=tools extract --layers --launcher` into dependencies, loader, snapshot-dependencies and application, and COPY them in that order. Dependencies rarely change, so their layer is cached and already present on nodes; a code change only ships the small `application` layer. Faster pushes, pulls and deployments.

**Q4. CMD vs ENTRYPOINT? Exec vs shell form?**
ENTRYPOINT is the fixed executable, CMD supplies default args and is easily overridden. I use exec form `["java", "...JarLauncher"]` so the JVM is PID 1 and receives SIGTERM directly — required for Spring graceful shutdown. Shell form wraps it in `sh -c`, which doesn't forward signals, so the app gets SIGKILLed after the timeout.

**Q5. ARG vs ENV?**
ARG exists only at build time (I use `ARG SERVICE` to pick which module to build, redeclared in each stage). ENV persists into the running container (I use `JAVA_TOOL_OPTIONS`). Neither is safe for secrets — both are visible in history/inspect.

**Q6. Why does your container run as UID 10001 and not a named user?**
Running as root increases the blast radius of a container escape. I use a fixed numeric UID because Kubernetes `runAsNonRoot` can only verify numeric users, and the Deployment's `securityContext.runAsUser: 10001` matches it. That also lets me use `readOnlyRootFilesystem` and drop all capabilities.

**Q7. How does Java behave with container memory limits? What do your flags do?**
Modern JVMs read cgroup limits, but the default heap is only 25% of it. `MaxRAMPercentage=75` gives ~384 MiB heap with a 512 MiB limit, leaving room for metaspace, threads and buffers. `ExitOnOutOfMemoryError` makes the JVM die on OOM so the orchestrator restarts it instead of it limping along. If heap + non-heap exceed the limit, the kernel kills it — exit 137, OOMKilled.

**Q8. How do services talk to each other in your compose file?**
Compose puts all services on one user-defined bridge network with built-in DNS, so order-service uses `http://inventory-service:8082` and `jdbc:postgresql://postgres:5432/orders`. Container ports, not host ports. `localhost` would point to the container itself.

**Q9. `depends_on` doesn't wait for the DB to be ready — how did you handle it?**
Postgres has a healthcheck with `pg_isready`, and both services use `depends_on: postgres: condition: service_healthy`. Apps also have healthchecks on `/actuator/health/readiness` with a 40s `start_period`. In Kubernetes there's no depends_on — readiness probes and retries handle it.

**Q10. Volume vs bind mount?**
Named volumes are Docker-managed and right for data like `pgdata`. Bind mounts map a host path and are right for config — I mount `prometheus.yml`, alert rules and `init-db.sh` read-only with `:ro`.

**Q11. Why not deploy `:latest`?**
It's mutable: you lose traceability, reproducibility and safe rollbacks, and K8s won't roll out when the tag string doesn't change. CI tags each image with the full git SHA; `latest` is only a convenience tag on main. Ideally deploy by digest.

**Q12. How do you make builds fast in CI?**
Layer ordering, BuildKit cache mount for `.m2` locally, and in GitHub Actions `cache-from/cache-to: type=gha` scoped per service with `mode=max` so intermediate stages are cached too. Plus `concurrency` cancels outdated runs.

**Q13. How do you handle secrets at build time?**
Never ARG/ENV/COPY them. Use `RUN --mount=type=secret,id=...` so they're available only during that step and never stored in a layer. Runtime secrets come from the orchestrator (K8s Secret → env `DB_PASSWORD`).

**Q14. How do you scan images?**
Trivy in the pipeline on the locally loaded image before push; it fails on HIGH/CRITICAL CVEs that have a fix (`ignore-unfixed: true` avoids blocking on unfixable ones). Dependabot keeps the base image and Maven deps current.

---

## 15. Scenario questions

**S1. "Your image is 700 MB. Bring it down."**
`docker history` to find big layers → switch runtime base to JRE (alpine/distroless) → multi-stage so Maven/JDK/source aren't shipped → `.dockerignore` → combine `RUN apt-get update && install && rm -rf /var/lib/apt/lists/*` in one layer (cleanup in a later layer doesn't shrink earlier ones) → layered jar so updates are small even if base is the same.

**S2. "Container keeps restarting with exit code 137."**
`docker inspect --format '{{.State.OOMKilled}}'`. If true: memory limit too low or heap % too high. Check `docker stats`, lower `MaxRAMPercentage` or raise `deploy.resources.limits.memory`, look for thread/direct memory leaks. If false: someone/something sent SIGKILL — e.g. stop timeout exceeded because the app ignored SIGTERM (shell-form entrypoint).

**S3. "Every build re-downloads all Maven dependencies."**
Cache is being invalidated early: `COPY . .` before dependency resolution with no cache mount, or `target/` in context changing checksums. Fix with `.dockerignore`, copy-pom-first, or `--mount=type=cache,target=/root/.m2`; in CI use the GHA cache backend.

**S4. "App works locally but in the container can't connect to the DB at localhost:5432."**
Inside the container `localhost` is itself. Use the compose service name (`postgres`) or `host.docker.internal` to reach a DB on the host. ShopFlow passes `DB_URL` via env so the same image works everywhere.

**S5. "`docker stop` takes 10 seconds and in-flight requests fail."**
Docker sends SIGTERM, waits 10s, then SIGKILL. Probably shell-form ENTRYPOINT so Java never got SIGTERM. Use exec form; enable `server.shutdown: graceful` (ShopFlow has it, with `timeout-per-shutdown-phase: 20s`); increase `stop_grace_period` if needed.

**S6. "Security team says your image has 40 CVEs."**
Most come from the OS layer — move to a slimmer/updated base, rebuild regularly (Dependabot docker updates), update vulnerable Java deps (Dependabot maven), fail CI on fixable HIGH/CRITICAL with Trivy, use `.trivyignore` with justification for accepted risks.

**S7. "Container exits immediately with no logs."**
`docker ps -a` for exit code; `docker logs`; run with `--entrypoint sh` to inspect files; check the ENTRYPOINT path/class name (e.g. old `org.springframework.boot.loader.JarLauncher` vs Boot 3.2+ `...loader.launch.JarLauncher`), file permissions for non-root user, missing env vars.

---

## 16. Common mistakes

- Shell-form ENTRYPOINT → no SIGTERM → no graceful shutdown.
- Shipping the JDK + Maven + source in the final image.
- `COPY . .` before dependency download with no cache mount and no `.dockerignore`.
- Secrets in `ENV`/`ARG`, or `COPY .env` — and thinking `RUN rm secret` removes it.
- Running as root; using a named user with K8s `runAsNonRoot`.
- `MaxRAMPercentage=100` (or `-Xmx` = limit) → OOMKilled.
- Deploying `:latest`; never rebuilding images so base CVEs pile up.
- `localhost` between containers; using published host ports for internal traffic.
- Relying on plain `depends_on` for readiness.
- Expecting Dockerfile `HEALTHCHECK` to be used by Kubernetes (it isn't).
- Separate `RUN apt-get update` and `RUN apt-get install` lines (stale cached index).
- Forgetting `down -v` when an `docker-entrypoint-initdb.d` script changes.

> Tip: Har answer mein ek line "in my project I did X in file Y" add karo — interviewer ko lagta hai tumne actually kiya hai, sirf padha nahi.
