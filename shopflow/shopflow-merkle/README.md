# shopflow-merkle — Merkle (hash) tree

Hash the leaves, hash pairs up to a single **root**. One small hash then certifies a whole dataset, and you
get cheap proofs and diffs out of the same structure. SHA-256, pure Java, fully unit-tested.

> Plain library like `shopflow-outbox` — never boots.

---

## What it buys you
- **Tamper-evidence** — change any one leaf and the root changes. Git (commits/trees), Bitcoin (block
  transactions), container image manifests, and signed logs all commit to data this way.
- **Inclusion proof** — prove "leaf X is in the dataset with root R" using only **log₂(n)** sibling hashes
  (an "audit path"), not the whole dataset. This is how Certificate Transparency and light clients verify.
- **Anti-entropy diff** — two replicas compare roots; equal → done, nothing to send. Different → walk DOWN
  the differing branch and find the exact leaf (or range) that diverged in **O(log n)** comparisons, then
  sync only that. Dynamo/Cassandra repair replicas this way instead of streaming everything.

```
        root            change leaf "c"  ->  only the hashes on its path to the root change:
       /    \                                 h(c), parent(c,d), root  — everyone else untouched,
     h_ab   h_cd                              so a diff needs just log(n) comparisons to locate it.
    /  \    /  \
  h_a h_b h_c h_d
```

Domain separation (leaf hashed with a `0x00` prefix, node with `0x01`) stops an internal node being passed
off as a leaf — the second-preimage defence.

## Use it
```java
var tree = MerkleTree.of(List.of(doc0, doc1, doc2, doc3));   // each a byte[]
byte[] root = tree.root();                                    // publish/sign this

var proof = tree.proof(2);                                    // audit path for doc2
boolean ok = MerkleTree.verify(doc2, proof, root);            // true only if doc2 is really in the tree

int diff = replicaA.firstDifferingLeaf(replicaB);             // -1 if identical, else the leaf index
```

## Where it fits in shopflow
The ledger (`ledger-service`) is an append-only log of journal entries — a natural Merkle use: publish a
daily root so any entry's inclusion can be proven and tampering is detectable (audit/compliance gold for a
payments story). Cross-region, Merkle diffs would reconcile two ledger replicas by shipping only the entries
that differ, not the whole book — the same idea `shopflow-sketch`/`shopflow-stream` serve for approximate
questions, here for exact integrity.

## Tests
`MerkleTreeTest`: same data → same root and one change flips it (tamper-evidence); an inclusion proof
verifies for the real leaf and fails for a wrong one and against a different root; and `firstDifferingLeaf`
pinpoints the single changed leaf (index 5 of 8) or returns -1 when identical.
