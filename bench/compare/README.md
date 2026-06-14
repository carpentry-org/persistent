# Head-to-head benchmarks vs Clojure

Identical workloads on both sides, comparing Carp `persistent` to the
Clojure collections that inspired it. Plain persistent operations only,
no transients and no type hints on the Clojure side.

## Running

```
# Carp side (--optimize is mandatory for any timing measurement)
cd ../..
carp --optimize -x bench/compare/carp/all_bench.carp
carp --optimize -x bench/compare/carp/slow_bench.carp

# Clojure side (jars are gitignored; fetch them once from Maven Central)
cd bench/compare/clojure
./lib/fetch.sh
java -cp "lib/clojure-1.12.1.jar:lib/spec.alpha-0.5.238.jar:lib/core.specs.alpha-0.4.74.jar" \
  clojure.main bench.clj
```

`slow_bench.carp` re-measures the workloads `Bench.carp` cannot stabilize
with manual best-of-N wall timing.

The Carp numbers below assume an `rc` release carrying this round's
changes (single-allocation cells, `NDEBUG`-gated checks, `value-ref`,
and `str`/`prn` for handles) and the matching pin bump in
`persistent.carp`.

## Workloads

- **vector push / get / assoc** — build, indexed read-sum, one-at-a-time
  overwrite.
- **hash-map insert / get** — build an Int→Int map, look up all keys.
- **ord-map insert / get** — same on the sorted (AVL) map.
- **hash-set insert / member**, **ord-set insert / member** — set builds
  and membership scans.
- **list prepend / sum** — build with `cons`/`prepend`, `reduce` `+`.
- **queue cycle** — enqueue n then dequeue-and-sum all n.
- **churn vec / map** — *mixed*: on each of 2000 steps, read one key and
  update one key (old version discarded). Models a live read/modify loop.
- **branch vec / map** — *retention*: derive 64 versions off a shared base
  (one update each), keep all 64 alive at once, read one element from each.
  Exercises structural sharing, not just throwaway churn.
- **vector push (owned/transient)** — build with `push-back-owned`, which
  mutates the tail in place when the vector is moved in. Compared to
  Clojure's `transient` / `conj!`.

Loop shapes match operation-for-operation; see `carp/all_bench.carp`
and `clojure/bench.clj`.

## Reference numbers

Measured on an M-series Mac, Carp at `--optimize`, Clojure 1.12.1 on
Java 18 with ~2s JIT warmup per bench, best-case timings, machine idle.
Sorted by ratio (Carp / Clojure; below 1.0 means Carp is faster):

| Workload | Clojure | Carp | ratio |
|---|---|---|---|
| hash-map get 1k | 21.5 µs | 10.0 µs | 0.46× |
| hash-set member 1k | 18.8 µs | 10.4 µs | 0.56× |
| list sum 1k | 10.4 µs | 6.27 µs | 0.60× |
| vector get 1k | 4.66 µs | 4.48 µs | 0.96× |
| ord-set member 1k | 68.4 µs | 91.4 µs | 1.34× |
| ord-map get 1k | 53.8 µs | 91.4 µs | 1.70× |
| queue cycle 1k | 33.9 µs | 91.2 µs | 2.69× |
| vector push 10k | 105 µs | 301 µs | 2.88× |
| churn map 2k | 186 µs | 583 µs | 3.13× |
| vector push 1k | 10.1 µs | 33.8 µs | 3.36× |
| ord-set insert 1k | 259 µs | 899 µs | 3.47× |
| branch map ×64 | 5.71 µs | 20.3 µs | 3.55× |
| hash-set insert 1k | 89.9 µs | 357 µs | 3.97× |
| hash-map insert 1k | 73.3 µs | 354 µs | 4.83× |
| branch vec ×64 | 3.57 µs | 17.5 µs | 4.90× |
| ord-map insert 1k | 168 µs | 900 µs | 5.37× |
| churn vec 2k | 94.1 µs | 515 µs | 5.47× |
| hash-map insert 10k | 857 µs | 4.74 ms | 5.53× |
| list prepend 1k | 3.63 µs | 25.9 µs | 7.13× |
| vector assoc 1k | 27.0 µs | 252 µs | 9.35× |

Transient/owned build (`push-back-owned` vs Clojure `transient`/`conj!`):

| Workload | Clojure transient | Carp owned | ratio |
|---|---|---|---|
| vector push 1k | 9.33 µs | 15.9 µs | 1.70× |
| vector push 10k | 92.9 µs | 180 µs | 1.94× |

The whole library sits in a 0.5×–9.4× band, and it splits cleanly along
one line: **reads vs writes.**

- **Every read workload is at or below parity.** Carp wins hash-map get,
  hash-set member, and list sum outright; ties vector get; and trails the
  AVL lookups by ~1.3–1.7×. Reads borrow down the structure with
  `Rc.value-ref` and copy out only the value asked for, so they allocate
  nothing.
- **Writes are 2.7×–9.4×.** This is the memory-model floor: every new node
  is a `malloc` (vs Clojure's TLAB bump) and every shared subtree carries
  `Rc` refcount traffic (vs none for a tracing GC). `vector assoc` (9.4×)
  is the high end — it path-copies a spine on every call with no tail
  shortcut.
- **Owned/transient narrows the write gap.** Where the caller discards the
  old version, `push-back-owned` mutates in place and the vector-push gap
  drops from 3.4× to 1.7× — closer to the JVM's own `transient`. Notably
  Clojure's *persistent* vector push is already near its transient (its
  allocation is a TLAB bump either way), while Carp gains more from going
  owned because its persistent path pays the system allocator.
- **The mixed workloads behave as predicted.** `churn` lands where its
  update cost dictates; `branch` (64 retained versions) stays in the same
  band as throwaway churn, so structural sharing holds up under retention.

## On the write floor, and GC

The write gap is the cost of `Rc` + `malloc` versus a bump-allocator and a
generational GC, and it is genuine — but two caveats keep it honest:

- The Clojure numbers already include their GC. On the allocation-heavy
  builds, young-gen collection measured at ~1–2% of wall time and fires
  inline during the timed loop, so it is in the numbers, not excluded.
  These workloads are the generational best case (intermediate versions
  die in eden, where collection cost is proportional to survivors ≈ 0).
- A micro-benchmark that discards each result does not capture the
  major-GC / promotion cost a long-running program with many retained
  versions would eventually pay — but neither does it capture `rc`'s
  win there: it reclaims promptly and deterministically, with lower peak
  memory and no pauses.

Carp's write cost is also somewhat allocator-state-dependent: vector push
measures ~28 ns/op against a lean heap but ~40 ns/op once the process has
built several large structures, because it goes through the system
allocator. Clojure's TLAB bump is constant. The numbers above are from a
single full run (realistic heap).

## History

This table is roughly an order of magnitude better than the first
measurement of this library. The path:

1. `rc` made cheaper and lighter — single-allocation cells, safety checks
   compiled out under `--optimize`, a borrowing `value-ref` accessor.
2. The vector and hash map/set stopped being thin wrappers over the
   general `define-trie` (a fixed-depth, sibling-chain digit trie) and
   became dedicated structures: a Clojure-style persistent vector with a
   tail buffer, and a bitmap-indexed HAMT. Allocations per operation fell
   from ~49 to ~1 (vector push) and ~40 to ~5 (hash-map insert); reads
   went to zero allocations.
3. Two techniques read off Clojure's own source: the owned vector tail is
   pre-sized to 32 so in-place appends never realloc (Clojure's transient
   tail trick), and HAMT bitmap nodes store single entries *inline* rather
   than as a separate node per entry (one fewer allocation per insert).
   Together these took churn-map from 4.9× to 3.1× and the owned-build gap
   to 1.7× of Clojure's transient.

What remains is the per-allocation floor (`malloc` + refcount vs TLAB +
GC), which the non-trie structures had already shown is a 2–7× tax. The
trie-backed outliers (hash map/set at 10–33×) are gone.
