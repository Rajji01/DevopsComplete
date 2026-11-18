# 37 · Merkle trees: one hash to certify a dataset, O(log n) proofs & anti-entropy — comprehension

> Companion notes: [`notes.md`](notes.md). Grounded in the **real** `shopflow-merkle` module: `MerkleTree.java` (leaf/node hashing to a root, `ProofStep` inclusion proofs, domain separation `0x00`/`0x01`, Bitcoin-style odd-level duplication). Ties to [30 replication](../30-replication-multi-instance-db/) (replica repair / reconciliation), [08 supply-chain](../08-supply-chain-signing/) (image-layer integrity), and the ledger reconciliation (`docs/fintech/`).
> **Theme:** ek chhota hash poore dataset ko certify karta; aur **"kahan farak hai"** ko `O(log n)` mein dhoondna (saara data bheje bina).

---

## Scenario (read cold)

> *A review of `shopflow-merkle`: "a hash tree gives you three superpowers from one idea — explain them and where each is used." Read once, then answer.*

A **Merkle (hash) tree** hashes the **leaves** (your data items), then each **parent = hash(left || right)**, all the way up to a single **root hash**. From that one construction, three powers fall out.

**(1) Tamper-evidence — one hash certifies a huge dataset.** Change **any** leaf and its hash changes, which changes its parent, and so on up to the **root** — so a single small root hash is a fingerprint of the **entire** dataset. If two parties agree on the root, they agree on all the data. This is how **Git commits** (a commit's tree hash), **Bitcoin blocks** (transactions in a block), and **container image layers** (each layer's digest) certify integrity.

**(2) Cheap inclusion proofs — prove a leaf is in the set with `log₂(n)` hashes.** To prove leaf X is in a tree of n leaves, you don't ship the whole tree — you ship X plus the **sibling hash at each level** (a `ProofStep`: the sibling hash and whether it's on the **right**). The verifier re-hashes up the path and checks it reaches the known root. Only **log₂(n)** hashes — so proving membership in a billion-item set costs ~30 hashes. (This is how a light client verifies a transaction is in a block without the whole block.)

**(3) Cheap diffs / anti-entropy — find *which* leaves differ in `O(log n)`, not `O(n)`.** Two replicas each build a Merkle tree over their data. Compare **roots**: equal → the datasets are **identical**, done (one comparison for the whole set). Different → **recurse into the child whose hash differs**, ignoring the matching subtree entirely, until you reach the **specific leaves** that differ. So you find the divergence in **O(log n)** comparisons and then ship **only the differing data** — exactly how **Dynamo/Cassandra anti-entropy** repairs replicas **without shipping everything**.

Two correctness details the code gets right. **Domain separation:** a **leaf** is hashed with a `0x00` prefix and an **internal node** with `0x01`, so an attacker can't pass an internal node off as a leaf (or vice-versa) to forge a proof — the **second-preimage** defence from Certificate Transparency. And **odd levels duplicate the last node** (Bitcoin style) so every level pairs up cleanly.

The through-line: hashing **composes**. One root hash means "all or nothing is equal," and the **tree shape** turns "are these equal, and if not where?" from an O(n) scan of the whole dataset into an O(log n) walk — the basis of integrity proofs and replica repair everywhere.

---

## Questions

### A — Literal
1. How does changing one leaf make the root change, and what does that let one small hash certify?
2. What exactly is shipped in an inclusion proof, and how many hashes for n leaves?
3. How do two replicas use their Merkle trees to find what differs **without** comparing every item?

### B — Inference
4. Anti-entropy compares roots first. Why is "roots equal" such a powerful early-out, and what does it save on the common case (replicas already in sync)?
5. Why does hashing a leaf with a `0x00` prefix and a node with `0x01` matter — what forgery does it stop?

### C — Trade-off
6. Merkle-tree diff is O(log n) to locate a divergence but requires both sides to **build and maintain** the tree. When is that worth it vs just streaming and comparing all rows?
7. A Merkle proof certifies a leaf against a **root** you already trust. What does the scheme **not** give you, and what must you still secure separately?

### D — Transfer / extension
8. ShopFlow's ledger must reconcile a replica (or a downstream's view) against the primary nightly, over millions of entries, cheaply. Design it with a Merkle tree: what are the leaves, what do you compare, and what do you ship when they differ? (Tie to topic 30 / the fintech reconciliation.)
9. A container registry (topic 08) wants to verify a pulled image wasn't tampered with, downloading only what changed. How do layer digests + a Merkle/Git-style structure give both **integrity** and **incremental** pulls?

### E — Spot-the-flaw
10. A dev builds a "Merkle-ish" integrity check that hashes all leaves and concatenates their hashes into one big string, then hashes that — "same idea, simpler." It detects tampering fine. Name two capabilities of a real Merkle tree they've lost, and why the tree shape (not just the root) is the point.

---

## Model answers

<details>
<summary>A — Literal</summary>

1. A leaf's hash feeds its parent (`parent = hash(left || right)`), whose hash feeds **its** parent, up to the root — so changing one leaf **propagates** a changed hash all the way up, changing the **root**. That lets a single small **root hash certify the entire dataset**: agree on the root ⇒ agree on every leaf (any difference anywhere would have changed the root).
2. The **leaf itself** plus, **at each level up to the root**, the **sibling hash** and whether it sits left/right (`ProofStep`). The verifier re-hashes the leaf with each sibling up the path and checks it lands on the trusted root. It's **log₂(n)** hashes (tree height) — ~30 for a billion leaves.
3. Each builds a Merkle tree over its data and they compare **root hashes**. If equal, **identical** — stop. If different, **descend into the child subtree whose hash differs** (skipping the matching sibling entirely) and repeat, until reaching the **specific differing leaves**. That's **O(log n)** hash comparisons to pinpoint the divergence, after which only the differing leaves are exchanged.
</details>

<details>
<summary>B — Inference</summary>

4. Because a **single** root comparison answers "is the **entire** dataset identical?" — one hash equality for millions of items. In the **common case** (replicas already in sync, which is most of the time), anti-entropy finishes in **one comparison** and ships **nothing** — no scanning, no data transfer. You only pay the O(log n) descent + data exchange when there's an actual divergence, so steady-state reconciliation is nearly free.
5. Without domain separation, a leaf hash and an internal-node hash are drawn from the **same space**, so an attacker could present an **internal node's hash as if it were a leaf** (or craft data whose hash equals an internal node) and build a **valid-looking proof for data that isn't actually a leaf** — a second-preimage/forgery. Prefixing leaves with `0x00` and nodes with `0x01` puts them in **disjoint** hash domains, so a node hash can never be mistaken for a leaf hash — the Certificate-Transparency defence.
</details>

<details>
<summary>C — Trade-off</summary>

6. It's worth it when the datasets are **large**, **mostly-equal**, and compared **repeatedly** (replica repair, periodic reconciliation) — the tree amortises: steady state is one root comparison, divergences are O(log n). It's **not** worth it for **small** datasets (just compare them — building a tree is overhead), **one-shot** comparisons where you'd pay the build cost once anyway, or **constantly-changing** data where the tree is rebuilt every time (little amortisation). Rule: Merkle shines for **repeated diffs of big, slowly-diverging** datasets.
7. It certifies a leaf **is in the dataset with that root** — it does **not** tell you the **root is the right/authentic one**. You must secure the **root's authenticity separately**: sign it, obtain it over a trusted channel, or anchor it (a commit you trust, a signed tree head, a blockchain). A Merkle proof against an **attacker-chosen root** proves nothing — integrity of the structure is only as good as your trust in the root.
</details>

<details>
<summary>D — Transfer / extension</summary>

8. **Leaves = ledger entries** (e.g. `hash(entryId || amount || state || ...)`), ideally **sorted/bucketed by id or time range** so both sides build the tree in the same order. Nightly, the replica and primary each build their Merkle tree and compare **roots**: equal → reconciled, **ship nothing**. Different → **descend** into the differing subtrees to find the **specific entries** (or ranges/buckets) that diverge, and **ship only those** to repair. Over millions of entries, steady state costs one root compare; a few drifted entries cost O(log n) to locate + just those rows to transfer — vs an O(n) full-table diff every night. (This is the data-layer mechanism behind topic 30's replica repair and the ledger reconciliation.)
9. Each **layer** has a content **digest** (a hash of its bytes); the image manifest references them, and the whole thing forms a hash-linked (Merkle/Git-style) structure rooted at the manifest digest. **Integrity:** re-hash each pulled layer and check it matches the digest the (signed) manifest claims — any tamper changes the digest. **Incremental:** because each layer is addressed by its **content hash**, the client **already-has** layers whose digests it holds and downloads **only the layers whose digests are new** — shared base layers aren't re-pulled. Content-addressing + the tree give integrity and dedup/incremental transfer at once (cf. topic 08's pin-by-digest).
</details>

<details>
<summary>E — Spot-the-flaw</summary>

10. Concatenate-all-hashes-then-hash gives **tamper-evidence** (the single top hash changes if any leaf changes) but loses the two things the **tree shape** provides: **(1) O(log n) inclusion proofs** — to prove one leaf is included you'd now have to ship (or re-hash) **all** leaves' hashes (O(n)), not log(n) siblings; and **(2) O(log n) diffs / anti-entropy** — with a flat concatenation you can only tell *that* something differs, not *where*, so finding the divergence is an **O(n)** scan, and you can't skip matching subtrees. The **tree** is the point precisely because its **hierarchy** lets you prove/compare a **logarithmic** slice instead of the whole set — the root alone only answers the yes/no "equal?" question, not the "where / prove it" questions that make Merkle useful. (Also, no domain separation → forgery risk.)
</details>

---

## Tod ke dekho — Local (real module + tests)

```bash
# the construction + the three powers, in the Javadoc and code:
grep -n "root\|ProofStep\|anti-entropy\|domain separation\|0x00\|0x01\|log" shopflow/shopflow-merkle/src/main/java/com/shopflow/merkle/MerkleTree.java | head

# run the tests (root changes on any leaf edit; inclusion proof verifies; diff finds the differing leaf):
cd shopflow && ./mvnw -q -pl shopflow-merkle test
```

**Proved:** one root certifies the set (tamper-evidence), an inclusion proof is log(n) sibling hashes, and the diff walks only the differing branch — with leaf/node domain separation against forgery. The tree *shape* is what buys the log(n).

## Tod ke dekho — Online
Pure library — no infra. On [`ONLINE-LABS.md`](../ONLINE-LABS.md) Codespaces run `./mvnw -pl shopflow-merkle test`; flip one leaf and watch the root change + the diff pinpoint it.

---

## Interview concepts is passage se
- **Merkle tree** — leaves→parents→root; `parent = hash(left||right)`.
- **Tamper-evidence** — one root certifies the whole set (Git/Bitcoin/image layers).
- **Inclusion proof** — log₂(n) sibling hashes (`ProofStep`), verify up to the root.
- **Anti-entropy / diff** — compare roots → descend differing subtree → O(log n) to find divergence; ship only the diff (Dynamo/Cassandra repair, topic 30).
- **Domain separation** — `0x00` leaf / `0x01` node → second-preimage/forgery defence (Certificate Transparency).
- **Trust the root separately** — proof is only as good as root authenticity (sign/anchor it).
- **Content-addressing** — hash-addressed layers → integrity + incremental pulls (topic 08).

**Interviewer's lens:** kya tu teen powers alag kar sakta (certify / prove / diff), **O(log n) anti-entropy** ka mechanism bol sakta (roots match → early-out, warna descend), aur jaanta ki **tree-shape** hi log(n) deta (flat concat sirf yes/no) + root ki authenticity alag secure karni padti.

> Interview line: *"Merkle tree: leaves hash karo, `parent=hash(left||right)`, ek **root** tak. Teen powers: **(1) tamper-evidence** — koi bhi leaf badla to root badla, ek chhota hash poore dataset ko certify (Git/Bitcoin/image layers). **(2) inclusion proof** — `log₂(n)` sibling hashes se prove karo ki leaf set mein hai (billion items = ~30 hashes). **(3) anti-entropy diff** — do replicas roots compare karein: same → identical (ek comparison, kuch ship nahi), alag → differing subtree mein descend → `O(log n)` mein pinpoint, phir **sirf diff ship** (Dynamo/Cassandra repair). Leaf `0x00` / node `0x01` domain-separation forgery rokta. Aur root ki authenticity alag secure karo (sign) — proof utna hi strong jitna root pe trust."*
