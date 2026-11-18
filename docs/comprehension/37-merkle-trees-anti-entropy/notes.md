# 37 · Merkle trees / anti-entropy — NOTES

> Pair: [`comprehension.md`](comprehension.md). Real `shopflow-merkle`. Theme: **ek hash poore dataset ko certify; "kahan farak" O(log n) mein.**

---

## KYA (one breath)
Leaves hash → `parent=hash(left||right)` → ek **root**. 3 powers: **tamper-evidence** (root badla=data badla), **inclusion proof** (log n siblings), **anti-entropy diff** (roots compare → differing subtree descend → O(log n), sirf diff ship).

## EASY WAY
> **"Ek root = poore set ka fingerprint. Proof = log(n) siblings. Diff = roots match? → done; na ho → sirf differing branch mein utro (O(log n), na ki O(n))."**

## 3 POWERS
| Power | Kya | Cost | Kahan |
|-------|-----|------|-------|
| **Tamper-evidence** | koi leaf badla→root badla | 1 root hash | Git commit, Bitcoin block, image layers |
| **Inclusion proof** | leaf set mein hai, prove | **log₂(n)** siblings (`ProofStep`) | light client (tx in block) |
| **Anti-entropy diff** | kaunsi leaves differ | **O(log n)** compare | Dynamo/Cassandra replica repair (topic 30) |

## ANTI-ENTROPY (yaad rakho — topic 30 repair)
```
 replica A tree,  replica B tree
 compare ROOTS:  equal → IDENTICAL, ship NOTHING (common case = free)
                 differ → descend into child whose hash differs (matching subtree SKIP)
                        → repeat → specific differing leaves → ship ONLY those
 O(log n) to locate, not O(n) scan.
```

## CORRECTNESS DETAILS (code)
- **Domain separation:** leaf `0x00` prefix, node `0x01` → node ko leaf samajh ke **forgery** na ho (second-preimage; Certificate Transparency).
- **Odd level:** last node duplicate (Bitcoin-style) → clean pairing.
- **Root authenticity alag:** proof utna strong jitna root pe trust → root **sign/anchor** karo (attacker-chosen root = proof worthless).

## KYU tree-shape (flat concat se farak)
```
 flat: hash(all leaf hashes concatenated) → sirf tamper-evidence (yes/no "equal?")
   LOST: inclusion proof O(n) (saare hashes), diff O(n) (kahan? pata nahi)
 tree: HIERARCHY → prove/compare a LOGARITHMIC slice; subtrees skip
   root akela sirf "equal?"; tree "where / prove it" deta
```

## TRADE-OFF (kab Merkle worth)
| Worth | Not worth |
|-------|-----------|
| bada dataset, **mostly-equal**, **repeated** diff (replica repair, nightly recon) | chhota set (bas compare karo) |
| steady-state ~1 root compare | one-shot compare (build cost ek baar anyway) |
| slow-diverging | constantly-changing (har baar rebuild, no amortise) |

## TIES
- **Replica repair / reconciliation** (topic 30, fintech ledger): leaves=ledger entries sorted by id; roots compare nightly; sirf drifted entries ship.
- **Image integrity + incremental pull** (topic 08): content-addressed layers = Merkle/Git; already-have layers skip, naye hi pull; tamper = digest badal.

## UNDERSTANDING CHECK (khud bol)
1. Ek leaf badla to root kyun badla? → *parent=hash(left||right) chain, propagate up to root.*
2. Inclusion proof kitne hash? → *log₂(n) sibling hashes (height).*
3. Anti-entropy common case (in-sync) cost? → *ek root compare, ship nothing.*
4. `0x00`/`0x01` kyun? → *leaf vs node domain-separate → node-as-leaf forgery rok (second-preimage).*
5. Flat-concat se kya kho jaata? → *log(n) inclusion proof + log(n) diff (kahan); sirf yes/no bachta.*

## INTERVIEW LINE
> *"Merkle: leaves hash, `parent=hash(left||right)`, ek **root**. 3 powers — **tamper-evidence** (koi leaf badla→root badla, chhota hash poora set certify: Git/Bitcoin/image-layers), **inclusion proof** (`log₂(n)` siblings, billion items=~30 hashes), **anti-entropy diff** (roots compare → same=identical ship-nothing, alag=differing subtree descend O(log n) → sirf diff ship: Dynamo/Cassandra/replica-repair). Leaf `0x00`/node `0x01` forgery rokta; root authenticity alag sign karo. Tree-shape hi log(n) deta — flat concat sirf yes/no."*

---
**Grounded:** `shopflow-merkle/MerkleTree.java` (root build, `ProofStep` inclusion proof, domain separation `0x00`/`0x01`, odd-level duplicate). Ties: [30](../30-replication-multi-instance-db/) (repair), [08](../08-supply-chain-signing/) (digests), fintech ledger recon.
