# 17 · Architecture rules that fail the build (ArchUnit) — comprehension

> Companion notes: [`notes.md`](notes.md). Grounded in: `order-service/src/test/.../architecture/ArchitectureTest.java`, `OrderService` (called from both HTTP and Kafka), `OrderController`/`OrderResponse`.

---

## Scenario (read cold)

> *Excerpt from ShopFlow's "how the architecture stays intact" notes. Read once, then answer.*

"The controller should go through the service, not hit the repository directly" is the kind of rule everyone **agrees** to in review — and that **erodes** by the tenth contributor, because a reviewer eventually misses one. ShopFlow encodes these agreements as **ArchUnit** tests: plain JUnit tests that scan the compiled classes of `com.shopflow.order` and **fail the build** when a rule is broken. Cheap to write, and they save a review comment every week.

Seven rules run:

1. **Controllers don't depend on `*Repository`** — the web layer must go through services; repositories are a persistence detail.
2. **`*Service` classes don't depend on the web layer** (`org.springframework.web.bind..`, `jakarta.servlet..`) — *because services must be callable from Kafka listeners and schedulers, not only from HTTP*.
3. **Controllers don't depend on `@Entity` classes** — controllers return **DTOs**; exposing a JPA entity leaks the DB schema and drags lazy-loading into the serialization layer.
4. **`@RestController`s are named `*Controller`** — naming consistency the other rules rely on.
5. **No field injection** — constructor injection only (final fields, mandatory deps explicit, testable).
6. **No `System.out`** — use the logger.
7. **No `java.util.logging`** — use the project's SLF4J logger.

Rule **2** is the load-bearing one for ShopFlow specifically. `OrderService` is called from **two** entry points: `OrderController` (HTTP) **and** `PaymentEventListener` (a Kafka listener reacting to `PaymentCaptured`/`PaymentFailed`). If a `*Service` accidentally depended on a servlet/web type (say it read the `HttpServletRequest` or returned a `ResponseEntity`), it would still compile and work over HTTP — but it would **break or behave wrongly when invoked from the Kafka listener**, which has no HTTP request in scope. The ArchUnit rule makes that mistake a **compile-time-ish build failure** instead of a runtime surprise discovered in production during a saga.

The tests scan **main** classes only (`DoNotIncludeTests`), so test helpers aren't held to the same rules. They're ordinary `@ArchTest` fields; they run in CI with the rest of the suite, so a violating PR goes **red** before merge.

---

## Questions

### A — Literal
1. What do these tests scan, and what do they do when a rule is violated?
2. State rule 2 and the exact reason the notes give for it.
3. Why are controllers forbidden from depending on `@Entity` classes?

### B — Inference
4. Rule 2 is called "the load-bearing one for ShopFlow." Using the fact that `OrderService` is called from two places, explain the concrete production bug it prevents.
5. These rules "fail the build." Why is that materially different from writing the same guidance in a style guide or a review checklist?

### C — Trade-off
6. ArchUnit rules add build-time tests that can block a PR on a *structural* issue, not a behavioural bug. What's the upside of that, and what's the cost/risk if the rules are too strict or too many?
7. Rule 1 (controllers ↛ repositories) and rule 3 (controllers ↛ entities) both constrain the controller. What layering principle are they jointly enforcing, and what does violating it cost later?

### D — Transfer / extension
8. A new service, `payment-service`, is added. What do you need to do to get the same guarantees, and which of the 7 rules might need a tweak for a service that has **no** `@RestController` at all?
9. You want a new rule: "nothing in `com.shopflow.order.order` (domain) may depend on `com.shopflow.order.payment` (another slice)" — package-level isolation. Why is this valuable as the codebase grows, and what's the risk of adding it to an existing codebase?

### E — Spot-the-flaw
10. To "quickly expose stock levels on the order page", a dev adds a method to `OrderService` that takes the `HttpServletRequest`, reads a header, and returns a `ResponseEntity<ProductEntity>`. It compiles, the HTTP endpoint works, and they're confused why **CI fails** and why a teammate warns it'll "break the saga". Explain both — the CI failure and the saga risk.

---

## Model answers

<details>
<summary>A — Literal</summary>

1. They scan the **compiled classes of `com.shopflow.order`** (main only, `DoNotIncludeTests`), and on a violation they **fail the build** (the `@ArchTest` fails, so CI goes red and the PR can't merge).
2. **`*Service` classes must not depend on the web layer** (`org.springframework.web.bind..`, `jakarta.servlet..`) — *because services must be callable from Kafka listeners and schedulers, not only from HTTP*.
3. Because controllers must return **DTOs**; depending on a JPA `@Entity` would **leak the DB schema** to the API and pull **lazy-loading** behaviour into serialization (lazy-init exceptions / N+1 at the web layer).
</details>

<details>
<summary>B — Inference</summary>

4. `OrderService` is invoked from `OrderController` (HTTP) **and** `PaymentEventListener` (Kafka). If a `*Service` method depended on a web/servlet type — e.g. it read `HttpServletRequest` or returned `ResponseEntity` — it would compile and work when called over HTTP, but when the **Kafka listener** calls the same service there is **no HTTP request in scope**, so it would NPE / behave wrongly **during the saga** (processing `PaymentCaptured`), in production, asynchronously — a nasty bug far from the cause. Rule 2 makes that dependency a **build failure** instead, so the service stays a plain callable usable from any entry point.
5. A style guide or checklist relies on a **human remembering and enforcing** it on every PR — it erodes the moment a reviewer is busy or new. A build-failing test is **automated and unskippable**: the rule is enforced identically on every PR, forever, with zero reviewer effort. It converts "we agreed to" (social, decaying) into "the build won't let you" (mechanical, permanent).
</details>

<details>
<summary>C — Trade-off</summary>

6. **Upside:** architecture stays intact as the team and codebase grow — layering/decoupling decisions are enforced mechanically, so you don't re-litigate them in review or discover erosion months later. **Cost/risk:** rules that are **too strict or too many** create friction — false positives that block legitimate changes, developers fighting or `@SuppressWarnings`-ing the rules, or rules that encode a *preference* rather than a real constraint. Good ArchUnit rules encode constraints that have a **concrete failure** behind them (like rule 2's Kafka-callability), not stylistic opinions.
7. Together they enforce **layering / dependency direction**: the **web layer depends on the service/domain layer, never the reverse, and never skips to persistence**. A controller that reaches a repository or an entity **couples the API to the database shape**, so a schema change breaks the API, DTO mapping disappears, and the service layer (where transactions, validation, and business rules live) gets bypassed. The cost shows up later as tangled layers that are hard to change, test, or reuse from a non-HTTP entry point.
</details>

<details>
<summary>D — Transfer / extension</summary>

8. Add an equivalent `ArchitectureTest` (or a shared module) scanning `com.shopflow.payment`, with the same rules. For a service like notification- or payment-service that may have **no `@RestController`** (pure Kafka consumer), the controller-specific rules (1, 3, 4) simply **match no classes and pass vacuously** — that's fine — while the generally-applicable ones (**2** services↛web, **5** no field injection, **6** no `System.out`, **7** no j.u.l.) still apply and are the valuable ones there. (Rule 2 matters *even more* for a pure consumer — its services are only ever called from listeners.)
9. **Value:** it enforces **vertical-slice / module isolation** — the `order` domain and the `payment` domain evolve independently, communicating through events, not direct calls; as the codebase grows this prevents a tangle of cross-slice dependencies that make the system a distributed monolith. **Risk of adding it to an existing codebase:** there may already be violations, so the rule goes **red immediately** — you either fix all current cross-dependencies first (possibly large) or add a temporary freeze/allowlist and burn it down. Introduce isolation rules early; retrofitting them is the expensive case.
</details>

<details>
<summary>E — Spot-the-flaw</summary>

10. **CI fails** because the method breaks **two** ArchUnit rules at once: rule 2 (`*Service` depends on `jakarta.servlet..` via `HttpServletRequest`) and rule 3's spirit via the return type — returning a `ResponseEntity<ProductEntity>` makes a service depend on the **web layer** (`org.springframework.web..`) and on a JPA **`@Entity`**. The build goes red before merge — exactly the point. **The saga risk:** `OrderService` is also called by `PaymentEventListener` (Kafka). That path has **no `HttpServletRequest`** — so when a `PaymentCaptured` event drives this code, reading the request header NPEs/fails **inside the saga**, asynchronously, in production, with no HTTP call to point at. The fix: keep the service web-agnostic (take plain parameters, return domain/DTO types); do any HTTP-specific reading in the **controller** and pass values down. The ArchUnit rule caught at build time the bug that would otherwise have surfaced mid-saga.
</details>

---

## Tod ke dekho — Local

```bash
# run the architecture tests (part of the normal suite):
cd shopflow && ./mvnw -pl order-service -Dtest=ArchitectureTest test
#   -> all 7 @ArchTest rules pass

# BREAK it to see a rule bite: in OrderController, add a field `private final OrderRepository repo;`
#   and use it directly, then re-run -> `controllers_do_not_talk_to_repositories` FAILS the build.
# Or add `System.out.println(...)` anywhere in main -> `no_system_out` fails.
# Revert to green.

# see the rules themselves:
sed -n '1,60p' shopflow/order-service/src/test/java/com/shopflow/order/architecture/ArchitectureTest.java
```

**Proved:** the 7 rules are real build-failing tests; introduce a controller→repository dependency or a `System.out`, and CI goes red before the code can merge. (No Docker needed — these are plain JUnit; they run in `./mvnw verify` / CI.)

## Tod ke dekho — Online
[`ONLINE-LABS.md`](../ONLINE-LABS.md) **section A (Codespaces)** or any JDK-21 env — `./mvnw -pl order-service -Dtest=ArchitectureTest test`.

---

## Interview concepts is passage se
- **Architecture-as-tests (ArchUnit)** — agreements ko build-failing rules banao.
- **Layering / dependency direction** — web → service → persistence, ulta nahi; controllers return DTOs.
- **Service web-agnostic kyu** — same service HTTP + Kafka listener dono se callable (ShopFlow ka rule 2).
- **Constructor injection** (no field injection) — testable, explicit deps.
- **Vacuous pass** — controller rules bina controller wale service pe pass (fine).
- **Vertical-slice isolation** — package-level rules se modules decoupled (grow ke saath).
- **Social vs mechanical enforcement** — checklist decays, build-fail nahi.

**Interviewer's lens:** kya tu bol pata hai ki ye rules sirf "neatness" nahi — rule 2 ek **asli bug** (service Kafka-listener se toote) rokta hai. Aur "fail the build" vs "review comment" ka farak.

> Interview line: *"Hum architecture agreements ko ArchUnit tests banate hain jo build fail karte — jaise 'controllers repositories/entities ko touch nahi karte', 'services web-layer pe depend nahi karte'. Aakhri wala load-bearing hai: OrderService ko OrderController (HTTP) aur PaymentEventListener (Kafka) dono call karte, to agar service `HttpServletRequest` pe depend kare to Kafka path pe saga mein toot jaye — ArchUnit ise build-time pe pakad leta, runtime surprise ki jagah. Checklist bhul jaate hain, build-fail nahi."*
</content>
