# 17 · ArchUnit architecture guardrails — concept notes

> Comprehension round: [`comprehension.md`](comprehension.md). Code: `order-service/src/test/.../architecture/ArchitectureTest.java`.

## Kya (one line)
Architecture ke "agreements" (controllers repositories ko touch na karein, services web-layer pe depend na karein) ko **ArchUnit tests** mein likho jo compiled classes scan karke rule toot-ne pe **build fail** kar dein — social rule ki jagah mechanical.

## Easy way (Hinglish analogy)
Ghar ke rules socho — "joote bahar utaro". Zubani bolo to naya mehmaan bhul jaata, aur tum har baar tok-te raho (review comment). **ArchUnit** = darwaze pe ek sensor jo bina joote utare andar aane hi na de (build fail). Ab rule khud enforce hota, kisi ke yaad rakhne pe nahi. Dasve contributor tak bhi architecture waisi ki waisi.

## Kyu (ye problem solve karta hai)
Layering decisions (web → service → persistence, ulta nahi) review mein sab maante hain, par **erode** hoti hain — ek reviewer miss karta, aur dheere-dheere controller repository ko call karne lagta, service `HttpServletRequest` pakad leti. ArchUnit in agreements ko **build-failing tests** bana deta: har PR pe automatically, bina effort, permanently. "Humne agree kiya tha" (decaying) → "build hone hi nahi dega" (mechanical).
Khaas: **rule 2** (service web-layer pe depend na kare) ek asli bug rokta — ShopFlow mein `OrderService` ko HTTP controller **aur** Kafka listener dono call karte, to web-dependency Kafka path pe saga mein toot jaati.

## Kaise (7 rules, step by step)
`@AnalyzeClasses("com.shopflow.order", DoNotIncludeTests)`, har rule ek `@ArchTest`:
1. `@RestController` → `*Repository` pe depend **na** kare (web service se jaaye).
2. `*Service` → `org.springframework.web.bind..`/`jakarta.servlet..` pe depend **na** kare (Kafka listener + scheduler se callable rahe). **← load-bearing.**
3. `@RestController` → `@Entity` pe depend **na** kare (DTO return karo; schema leak + lazy-load surprise nahi).
4. `@RestController` ka naam `*Controller` ho.
5. No field injection (constructor injection).
6. No `System.out` (logger use karo).
7. No `java.util.logging` (SLF4J).
- Fail hone pe `@ArchTest` red → CI red → PR merge nahi. Main classes only (test helpers exempt).

## Flow diagram (enforcement)
```
 PR → compile main classes → ArchUnit scans com.shopflow.order
        │
   rule violated? ──yes──► @ArchTest FAILS ──► CI red ──► merge blocked
        │ no
   all 7 pass ──► green ──► merge
 (controller rules match no classes in a no-REST service → pass vacuously)
```

## Layering diagram (what rules 1/2/3 protect)
```
      HTTP                      Kafka listener / scheduler
       │                                │
   Controller (DTOs) ──┐                │
                       ▼                ▼
                     Service  ◄─────────┘   (web-agnostic: rule 2)
                       │
                   Repository / @Entity    (controllers never reach here: rules 1,3)
 dependency direction: web → service → persistence, NEVER reverse/skip
```

## Code + dry run (samajh pakki)

**Asli code (ek rule — load-bearing #2):**
```java
@AnalyzeClasses(packages = "com.shopflow.order", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {
  @ArchTest static final ArchRule services_do_not_depend_on_the_web_layer = noClasses()
      .that().haveSimpleNameEndingWith("Service")
      .should().dependOnClassesThat().resideInAnyPackage("org.springframework.web.bind..","jakarta.servlet..")
      .because("services must be callable from Kafka listeners and schedulers, not only from HTTP");
  // + controllers↛Repository, controllers↛@Entity, @RestController named *Controller,
  //   no field-injection, no System.out, no java.util.logging
}
```

**Dry run — PR jo rule todta:**
```
 dev adds: OrderService method takes HttpServletRequest, returns ResponseEntity<ProductEntity>
 compile ok, HTTP endpoint chalta...
 CI: ArchitectureTest run → services_do_not_depend_on_the_web_layer FAIL (jakarta.servlet..)
     + controllers↛@Entity spirit FAIL (ResponseEntity<Entity>)
 → @ArchTest red → CI red → PR MERGE BLOCKED
 (bina rule: merge ho jaata; phir PaymentEventListener (Kafka) us service ko call kare →
  HttpServletRequest scope mein nahi → saga mein NPE, production, async)
```
**Seekh:** agreement ko build-failing test banao; rule 2 asli bug (service Kafka-listener se toote) build-time pe pakadta, runtime surprise ki jagah.

## Alternate approaches (kab kaunsa)
| Approach | Kaise | Pros | Cons | Kab |
|---|---|---|---|---|
| **ArchUnit tests** *(ShopFlow)* | JUnit scans classes, build-fail | automated, permanent, in-CI | over-strict = friction | growing team/codebase |
| **Style guide / review** | humans enforce | zero setup | decays, reviewer-dependent | tiny team |
| **Module boundaries (Gradle/Maven modules)** | separate modules, compile-time deps | hard compile barrier | heavier refactor | strict module isolation |
| **Spring Modulith** | module verification + events | richer module model | extra dependency/learning | modular monolith |

## Understanding check
1. Ye tests kya scan karte, violate pe kya hota?
2. Rule 2 + uski asli wajah (ShopFlow-specific bug)?
3. Controllers `@Entity` pe depend kyu nahi?
4. "Fail the build" vs review-checklist — materially farak kya?
5. Bina `@RestController` wale service pe controller-rules ka kya hota?

## Interview mein ye bolo
*"Hum architecture agreements ko ArchUnit tests banate hain jo build fail karte — controllers repositories/entities touch na karein, `@RestController` `*Controller` named ho, no field-injection/System.out/j.u.l, aur services web-layer pe depend na karein. Aakhri load-bearing hai: `OrderService` ko `OrderController` (HTTP) aur `PaymentEventListener` (Kafka) dono call karte — agar service `HttpServletRequest` pe depend kare to Kafka path pe saga mein toot jaye; ArchUnit ise build-time pe pakad leta, runtime surprise ki jagah. Checklist bhul jaate, build-fail nahi — social rule ko mechanical bana diya."*

> Related: [`../02-saga-cancel-refund/`](../02-saga-cancel-refund/) (service called from Kafka), [`../10-oauth2-jwt-idor/`](../10-oauth2-jwt-idor/) (DTOs not entities). LLD doc: `../../LLD-DESIGN-PATTERNS.md` (SOLID/ArchUnit).
</content>
