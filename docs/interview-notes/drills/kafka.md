# Kafka — interview drill set (ShopFlow edition)

> **Ye kya hai:** ek **question-only drill sheet** — basic se prod-level failures tak. Study notes alag hain ([`../10-kafka-event-driven.md`](../10-kafka-event-driven.md)); ye wo set hai jise **ek-ek karke mock-interview** ki tarah drill karna hai: question padho, 2–4 sentence mein **bolo**, phir notes se check karo.
>
> **Tags:** `[C]` = core concept · `[SF]` = ShopFlow se directly defend kar sakta hai ("in my project…") · `[P]` = production / failure scenario.
>
> **Niyam:** har answer ke end mein *"in my project, …"* ShopFlow se jodo. Interviewer definition se zyada experience pe trust karta hai.

## ShopFlow mein Kafka kahan (grounding — taaki [SF] bol sako)

- **Topics:** `orders.events` (order-service produce karta), `payments.events` (payment-service produce karta).
- **Consumers / groups:** payment-service (`groupId=payment-service`) aur notification-service (`groupId=notification-service`) dono `orders.events` sunte; order-service (`groupId=order-service`) `payments.events` sunta.
- **Partition key:** `orderRef` — ek order ke saare events ek hi partition → **per-order ordering**.
- **Compose:** Kafka 4.1 **KRaft mode** (no ZooKeeper), `KAFKA_NUM_PARTITIONS=3`.
- **Reliability:** transactional **outbox** (`shopflow-outbox`: `OutboxPublisher` + `OutboxRelay`, `FOR UPDATE SKIP LOCKED`, `acks=all`), **idempotent consumers** (`processed_event` table on order side; business-state guard on payment side), **DLT** on repeated failure, saga + compensating transactions.

---

## TIER 1 — Fundamentals (warm-up)

1. Kafka kya hai aur kab use karte ho vs ek normal message queue (RabbitMQ/SQS)? `[C]`
2. Topic, partition, aur offset — teeno ko ek order ke example se samjhao. `[C]`
3. Producer, consumer, consumer group — in teeno ka rishta kya hai? `[C]`
4. Broker kya hai? Ek cluster mein multiple brokers kyun? `[C]`
5. Kafka message ko kitni der rakhta hai (retention)? Queue se ye kaise alag soch hai? `[C]`
6. Message ki **key** kis kaam aati hai? Agar key `null` ho to kya hota hai? `[C][SF]`
7. ShopFlow mein kaun-kaun se topics hain aur kaun produce / consume karta hai? `[SF]`

## TIER 2 — Core mechanics (ordering, partitions, groups)

8. Kafka ordering **kis level** pe guarantee karta hai — topic, partition, ya pura cluster? `[C]`
9. ShopFlow mein ek hi order ke saare events (Confirmed→Paid→Refunded) **sahi order** mein kaise aate hain? (hint: partition key = `orderRef`) `[SF][C]`
10. Partitions zyada = parallelism zyada — to 1000 partitions kyun nahi bana dete? Trade-off? `[C][P]`
11. Consumer group mein 3 consumers aur topic mein 3 partitions — kaam kaise bantta hai? Agar 4th consumer aa jaaye? `[C]`
12. Do alag services (payment, notification) ek hi `orders.events` topic kaise consume karte hain bina ek dusre ko affect kiye? (groupId) `[SF][C]`
13. `KAFKA_NUM_PARTITIONS=3` ShopFlow mein hai — iska matlab aur asar kya? `[SF]`

## TIER 3 — Delivery semantics & reliability (interview ka dil)

14. At-most-once, at-least-once, exactly-once — teeno ka farq aur kaun kab? `[C]`
15. ShopFlow **kaun sa** use karta hai aur **kyun**? `[SF]`
16. "At-least-once" ka matlab duplicate aayenge — to customer **double charge** hone se kaise bachaya? `[SF][P]`
17. Idempotent consumer kya hai? ShopFlow mein **do** style kaun se hain (`processed_event` table vs business-state guard)? `[SF][C]`
18. Kafka ki "exactly-once semantics (EOS)" / transactions kya hai? ShopFlow ne kyun **nahi** use kiya? `[C][P]`
19. Consumer offset **kab** commit karte ho — process se pehle ya baad? Dono ke failure modes? `[C][P]`
20. Auto-commit vs manual commit — Spring/ShopFlow default kya hai aur kya risk? `[C][P]`

## TIER 4 — Microservice patterns (senior-level)

21. **Dual-write problem** kya hai — DB save + Kafka publish dono karne mein dikkat? `[SF][C]`
22. **Transactional Outbox** pattern poora samjhao — `shopflow-outbox` kaise kaam karta? `[SF][C]`
23. Outbox relay publish karte waqt crash ho jaaye to? (at-least-once at the relay) `[SF][P]`
24. **Saga** kya hai? Choreography vs Orchestration — ShopFlow kaun sa aur kyun? `[SF][C]`
25. Compensating transaction kya hai? ShopFlow mein refund / stock-release kaise compensation hain? `[SF]`
26. **CDC (Debezium)** vs application-level outbox — dono kab? ShopFlow ne kaun chuna aur kyun? `[C][P]`
27. Event **fat** (poora data) rakho ya **thin** (sirf id, phir fetch)? Trade-off? `[C]`
28. Ek hi event do consumers alag-alag process karein (payment charge, notification bheje) — ek fail ho to dusra na ruke, kaise? `[SF][P]`

## TIER 5 — Production failures & operations (yahan jeet ya haar)

29. **Consumer lag** kya hai? Production mein kaise detect aur kaise theek karoge? `[P]`
30. **Poison pill / bad message** — ek message baar-baar fail, pura partition atak gaya. Kya karoge? `[P][SF]`
31. **Dead Letter Topic (DLT)** kya hai? ShopFlow mein kab message DLT hota hai? `[SF][P]`
32. **Rebalancing** kya hai, kab trigger hota, aur "rebalance storm" kyun khatarnak? `[P]`
33. Consumer ek message mein bahut der laga raha (lamba HTTP call) — `max.poll.interval.ms` exceed → kya hota hai? (ShopFlow ka inventory-release-inside-listener case) `[SF][P]`
34. Broker down ho gaya — producer / consumer ka kya hota hai? `acks` + replication ka role? `[P]`
35. `acks=0` / `acks=1` / `acks=all` — durability vs latency trade-off? ShopFlow ke paise wale event ke liye kaun? `[P][SF]`
36. Message **order** important hai par beech ka ek message fail — retry karoge to order tootega. Kaise handle? `[P]`
37. Consumer process ke beech crash (event handle hua par offset commit nahi) — restart pe duplicate. Kaise bachte ho? `[SF][P]`
38. **Back-pressure** — producers consumers se tez, lag badh raha. Options? `[P]`
39. **Schema evolution** — producer ne event mein naya field add/remove kiya. Purane consumers tootenge? (Schema Registry, compatibility) `[P][C]`
40. Topic ke partitions **production mein badha diye** — existing keys ke ordering / hashing pe kya asar? `[P]`

## TIER 6 — Performance, scaling & tuning

41. Throughput badhane ke levers? (batching, compression, partitions, `linger.ms`) `[P]`
42. Consumer slow hai — consumers badhaye par partitions kam hain. Ab? `[P]`
43. `fetch.min.bytes` / `linger.ms` / `batch.size` — latency vs throughput kaise tune? `[P]`
44. Kafka **replay** karna pada (naya consumer, ya bug-fix ke baad dobara process) — kaise? Offset reset strategies? `[P][SF]`
45. Retention bada (replay ke liye) vs chhota (disk bachao) — kaise decide? `[P]`

## TIER 7 — ShopFlow deep / scenario (full "my project" answers)

46. ShopFlow ka **ek order ka pura event journey** batao, Kafka ke across, place se paid tak. `[SF]`
47. `PaymentEventListener` `@Transactional` hai aur andar inventory release (HTTP, ~7s) karta — ye "no DB txn across remote call" rule todta. Kyun allow kiya, kya bound lagaya? `[SF][P]`
48. **Cancel/charge race** — customer ne cancel kiya jab payment capture ho raha tha; consumer lag se window minutes ka. Step-by-step kya hota hai? `[SF][P]`
49. `KafkaMessageDeadLettered` alert production mein fire — on-call ho, kya karoge? (runbook soch) `[SF][P]`
50. Single-partition = safe ordering par kam throughput; multi-partition = zyada throughput par ek order ke events bikhar sakte. ShopFlow ne kaise balance kiya? `[SF][P]`

---

### Drill log (apni progress yahan tick karo)

| # | Topic | Attempted | Confident |
|---|---|---|---|
| 1–7 | Fundamentals | ☐ | ☐ |
| 8–13 | Mechanics | ☐ | ☐ |
| 14–20 | Delivery semantics | ☐ | ☐ |
| 21–28 | Microservice patterns | ☐ | ☐ |
| 29–40 | Prod failures | ☐ | ☐ |
| 41–45 | Perf & tuning | ☐ | ☐ |
| 46–50 | ShopFlow deep | ☐ | ☐ |

> Tip: Jo question pe "umm" aaya — wahi `[P]` wale sabse zyada — usko star karke `../10-kafka-event-driven.md` mein wapas padho. Ratta nahi, "why".
