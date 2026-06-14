# How `persistent` works

This is a tour of how the library is built, aimed at people curious about the
internals or thinking about contributing. If you just want to use the
collections, [the README](../README.md) is a better starting point.

## The shape of a persistent update

Every collection in `persistent` follows the same idea:

- read-only operations walk the existing nodes
- updates allocate new nodes only along the path that changed
- everything off that path is reused by pointer

This is the standard "path copy" recipe. It's what makes the previous version
remain valid: nothing in the old structure was overwritten.

`Rc` (reference counting) is what keeps the shared parts alive. When the last
version that points at a node goes away, the count hits zero and the node is
freed. We never need to walk the whole structure to free it.

## Why a macro instead of one generic type

Carp has generics, so this is a design choice rather than a forced move.
The constraint that drives it is `Rc`: it's not a `(Rc T)` type, it's a
macro that emits a concrete handle along with its `copy`/`delete`
implementations. Anything that stores reference-counted nodes has to be
concrete by the time `Rc.define` runs, which means the recursive node and
wrapper types above it have to be concrete too. A macro is what threads
all of those concrete pieces through one expansion.

The trade is that one `define-*` call is required per payload type. In
return you get fully specialized code, a regular Carp module per
generated collection, and `=`/`str`/`prn` wired up automatically through
`implements`. (`define-hash-set` is the one collection that layers on
another: it expands into a `define-hash-map` with `Bool` values.)

## How each collection is laid out

### List

Singly linked. A node holds a value and an `Rc` handle for the next node.
Prepend allocates one node; the rest of the chain is shared.

### Queue and deque

Both are two-list designs.

- **Queue:** a `front` list (in dequeue order) and a `rear` list (in reverse
  enqueue order). When you try to dequeue and `front` is empty, the `rear` is
  reversed into the new `front`. Amortizes to O(1).
- **Deque:** front and rear lists, with O(1) pushes on either end. Popping
  from an empty side reverses the other side, same idea.

### Trie

Keyed by `(Array K)`. Internally each node has a `label`, an optional `value`,
a `child` chain, and a `sibling` chain. Insert and remove only path-copy the
sibling/child chains they actually touch.

This is a general prefix trie over arbitrary key-part sequences (its
strength is `contains-prefix?` and friends). The hash map and vector
used to be built on it too, but they are now dedicated structures (see
below) — a first-child/next-sibling chain is the wrong shape for the
dense, bounded-radix digit tries they need.

### Ordered map and set

A persistent AVL tree (self-balancing binary search tree). Insert/remove
path-copy from the root to the leaf and rebalance on the way back up.
Two-child delete uses successor-from-right. Each node stores a height field
used to compute balance factors; single and double rotations keep the tree
within AVL balance (height difference of at most 1 between subtrees).

This guarantees O(log n) insert, remove, and lookup regardless of insertion
order.

### Heap

A persistent skew min-heap. The whole API is built around `merge`:

- `insert x h` is `merge h (singleton x)`
- `pop h` is `merge` of the root's two children

### Hash map and set

A hash array mapped trie (HAMT), radix 32. Internal nodes are
bitmap-indexed: a 32-bit bitmap marks which of the 32 slots are occupied,
and a popcount-packed array holds only the present slots (slot `f` lives
at array index `popcount(bitmap & ((1<<f)-1))`). So a node allocates space
for exactly its slots, and depth grows with the entry count rather than
being fixed. Each slot is either an **inline `Entry`** (the key and value
stored directly in the node array, no separate allocation) or a **`Sub`**
pointer to a child node; only an actual hash collision (same 31-bit hash,
different key) allocates a `Collision` bucket, scanned linearly (the same
`bucket-*` helpers used elsewhere). `hash-set` is a `hash-map` with `Bool`
values.

This replaced an earlier fixed-depth-8 radix-16 digit trie whose children
were sibling chains. The HAMT does an order of magnitude fewer allocations
per insert (measured ~4 vs ~40 at 1k entries) and turns lookups into
bitmap-indexed array hops; storing entries inline (read off Clojure's
`BitmapIndexedNode`) saves the per-entry node allocation.

The slot type carries hand-written `copy`/`delete` (rather than the
auto-derived ones). That works around a current Carp limitation: a
concrete deftype resolves whether a member needs memory management against
the env as it was at *definition* time, and the slot's node-handle gets
its `delete` from a later `Rc.define`, so the auto-derived versions would
treat it as unmanaged and leak. The explicit ones make a `Sub` slot share
on copy and free on delete. When the compiler resolves member
managed-ness against the live env, this block can be deleted.

### Vector

A Clojure-style persistent vector: a 32-way radix trie over the index bits,
plus a 32-element **tail** buffer. Most `push-back`s only copy the tail
(one allocation, no tree walk); when the tail fills, it is pushed into the
tree as a leaf and a fresh tail is started. Tree depth (`shift`) grows and
shrinks with the count rather than being fixed.

- `push-back` appends (tail copy, or a tail flush into the tree)
- `push-back-owned` appends when the caller hands over ownership (moves the
  vector in). Because tails are never shared between versions, owning the
  vector means owning its tail exclusively, so the tail is mutated in place
  with no copy. The owned tail is pre-sized to 32 slots (Clojure's
  transient tail trick) so the in-place append never reallocs. This is a
  transient-style fast path with the same result as `push-back`; use it in
  build/churn loops that discard the previous version.
- `assoc` overwrites an existing index (path-copies the spine)
- `pop-back` removes the last index
- reads (`get`) borrow down the spine through `Rc.value-ref` and copy out
  only the one indexed value — no node copies, no allocation

This gives O(log32 n) indexed reads and writes, with the tail making the
common append case effectively O(1) and allocation-light.

## Equality and identity

- `ptr-eq` compares the backing roots (and counts where applicable). Cheap.
  Useful for spotting "this is the same value, no work needed."
- `=` is structural. List, queue, trie, deque, ord-map, ord-set, hash-map,
  hash-set, and vector all implement it.
- The heap doesn't, on purpose. Two heaps with the same multiset of values
  can have different shapes, and the right answer depends on whether you
  want shape equality or multiset equality. We let the caller decide.

## Iteration

Everything exposes `reduce`, `each`, and `to-array`. Map-like collections
also expose `keys` and `values`. The trie additionally exposes
`reduce-values` so you can fold over values without paying for key path
reconstruction.

## Stack safety

The deeper *unbounded* traversals are written iteratively rather than via
C recursion:

- list `drop-n`
- queue/deque reversal
- trie insert/remove/lookup path rebuilds
- ord-map path rebuilds
- heap merge spine traversal
- hash-map/vector `reduce` (walks the whole structure)

In practice this means deep inputs are bounded by heap memory, not C stack
depth. The test suite has regressions at 100k–200k elements for the most
likely offenders.

The HAMT and vector per-operation paths (`node-insert`/`node-remove`,
`push-tail`/`pop-tail`) are recursive, but their depth is bounded by the
radix-32 fan-out — at most 7 levels for the vector and ~7 for the HAMT,
regardless of size — so the recursion is a small constant and safe.

## Safety model (inherited from `rc`)

- single-threaded; non-atomic counters
- no cycle collection
- iterative payload teardown via `rc`'s drain queue, so dropping a deeply
  nested structure doesn't recurse on the C stack

## What's deliberately not here yet

- finger-tree-based deque scheduling
- structural `=` for the heap
- a dedicated structure-level fuzzer (fuzzing happens at the `rc` layer
  instead, since that's where the lifetime risk concentrates)
