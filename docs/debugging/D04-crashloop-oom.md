# D04 · A pod restarts in a loop; `kubectl describe` shows exit code 137

> Skill: Kubernetes / memory debugging. Related mechanism: [`../comprehension/07-k8s-readiness-cascade/`](../comprehension/07-k8s-readiness-cascade/).

---

## Page (symptom)

`KubePodCrashLooping` fires for one `order-service` pod. `kubectl get pods` shows it `CrashLoopBackOff` with a restart count climbing every few minutes. The other order-service pods are fine; the service is degraded (one fewer replica) but up.

**Before details: a pod restarting in a loop has a handful of classic causes. List them, then say which signal tells them apart.**

---

## What you can see (signals)

<details>
<summary>Signals available</summary>

- `kubectl describe pod` → `Last State: Terminated, Reason: OOMKilled, Exit Code: 137`. Events show repeated `OOMKilling` / `Back-off restarting failed container`.
- `kubectl logs <pod> --previous` → the app started fine, served traffic for ~6 minutes, then the log just **stops** (no exception, no stack trace) right before the restart.
- container memory limit: **512Mi**; the JVM is started with heap = 75% of the limit.
- A deploy went out ~40 min ago that added an in-memory cache of recent orders "for speed."
- The crash correlates with traffic: the pod survives longer when traffic is low, dies faster under load.
</details>

---

## Your move

1. Exit code **137** and `OOMKilled` — what exactly killed the process, and who made that decision (the JVM or the kernel/kubelet)?
2. The logs show **no exception** before death, just silence. Why is that consistent with OOMKill and *not* with a normal Java `OutOfMemoryError`-and-crash?
3. Heap is 75% of a 512Mi limit (~384Mi). Where does the **other ~128Mi** go, and why does "heap didn't OOM but the container did" happen?
4. "What changed?" is a new in-memory cache. Give the most likely mechanism and how you'd confirm it before blaming it.
5. Two fixes: a fast one and a correct one. What's the risk of *only* doing the fast one?

---

## Walkthrough

<details>
<summary>Diagnostic tree → root cause → fix</summary>

**Classic CrashLoop causes** (differential): (a) **OOMKilled** (exit 137), (b) failed liveness probe → killed, (c) bad config/secret → app exits at startup, (d) a startup exception, (e) exit 1 from the app. The **signal that separates them** is `kubectl describe` → *Last State reason + exit code* and `logs --previous`. Here it's unambiguous: **`OOMKilled`, exit 137**.

**Who killed it (Q1).** Exit **137 = 128 + 9 = SIGKILL**. The container exceeded its **512Mi memory limit**, so the **kernel's cgroup OOM killer** (via the kubelet) SIGKILLed it. This is *not* the JVM throwing `OutOfMemoryError` — it's the OS killing the whole process because the **container** (heap + non-heap + everything) went over the cgroup limit.

**Silent logs (Q2).** A `java.lang.OutOfMemoryError` would log a stack trace as the JVM fails an allocation. Here there's **no exception — the log just stops**, which is the signature of **SIGKILL**: the process is terminated instantly by the kernel with no chance to log. "Clean silence right before restart" ⇒ killed from outside, i.e. OOMKilled, not an in-JVM OOM.

**Heap vs container (Q3).** Heap is ~384Mi (75% of 512). The remaining ~128Mi is **non-heap**: JVM metaspace, thread stacks, code cache, direct/native buffers (Netty, NIO), GC structures. The container limit governs **RSS of the whole process**, not just heap. So you can have **plenty of heap headroom and still get OOMKilled** because non-heap + heap together exceeded 512Mi. Watching only heap graphs hides this.

**"What changed?" (Q4).** The new in-memory "recent orders" cache is almost certainly an **unbounded** (or too-large) cache that grows with traffic — which is exactly why the pod **dies faster under load and survives when quiet**. Confirm before blaming: a heap histogram / profile (or `jcmd GC.heap_info`, a heap dump on OOM) showing the cache's backing map dominating memory, growing with request volume.

**Root cause:** the new cache is unbounded; under load its retained memory pushes total container RSS past 512Mi → cgroup OOM kill → CrashLoop. It's a memory **leak-by-design** (no eviction/size cap), surfaced by load.

**Fixes (Q5):**
- **Fast:** bump the memory limit (and request) — buys time, stops the immediate CrashLoop. **Risk if you stop here:** an unbounded cache will eventually blow *any* limit; you've only moved the cliff and you're now paying for more memory to hide a leak. It'll OOM again at higher traffic.
- **Correct:** bound the cache — a size/TTL cap (or use the existing Redis cache instead of a per-pod in-memory one), so memory is bounded regardless of traffic. Then size the limit to the real working set with headroom for non-heap.

**Confirm:** restart count stops climbing, RSS plateaus under load well below the limit, no more `OOMKilled` events.
</details>

---

## The pattern

- **137 = SIGKILL; with `OOMKilled` it's the cgroup limit, not the JVM.** `describe` → Last State + exit code is the first read for any CrashLoop.
- **Silent logs before death = killed from outside** (OOM/SIGKILL); a stack trace = the app died by itself. The *absence* of an exception is itself a signal.
- **Container memory ≠ heap.** Non-heap (metaspace, threads, native buffers) counts; a limit is on total RSS. Tune `-Xmx` *and* leave headroom.
- **"Dies under load, survives when quiet" = a resource that grows with work** — usually an unbounded cache, buffer, or thread pool. Correlate failures with traffic.
- **Raising the limit treats the symptom; bounding the growth treats the cause.** A leak outruns any limit.

> Grounded in: `k8s/base/order-service/deployment.yaml` (512Mi limit, heap 75% note in the Dockerfile), and the general rule from comprehension topic 07 (resources, probes). Lab counterpart: `../LABS.md` (OOMKilled).
</content>
