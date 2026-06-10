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

That also lets layered collections compose: `define-hash-map` and
`define-vector` both expand into `define-trie` internally, with the inner
trie fully specialized for its own key/value pair. The trade is that one
`define-*` call is required per payload type. In return you get fully
specialized code, a regular Carp module per generated collection, and
`=`/`str`/`prn` wired up automatically through `implements`.

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

The hash map and vector are both built on top of this same trie shape, just
indexed differently.

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

A trie keyed by the digits of `(hash key)`. Fixed depth of 8, radix 16
(nibbles per step). Each leaf is an array of `(Pair K V)` for collisions, and
collisions are resolved by linear scan within the bucket.

### Vector

A trie keyed by the digits of the index. Fixed depth of 7, radix 32. The
operations are:

- `push-back` inserts at index `count`
- `assoc` overwrites an existing index
- `pop-back` removes the last index

This gives you O(depth) indexed reads and writes, where depth is bounded by
the constants above.

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

All the deeper traversals are written iteratively rather than via C
recursion:

- list `drop-n`
- queue/deque reversal
- trie insert/remove/lookup path rebuilds
- ord-map path rebuilds
- heap merge spine traversal

In practice this means deep inputs are bounded by heap memory, not C stack
depth. The test suite has regressions at 100k–200k elements for the most
likely offenders.

## Safety model (inherited from `rc`)

- single-threaded; non-atomic counters
- no cycle collection
- iterative payload teardown via `rc`'s drain queue, so dropping a deeply
  nested structure doesn't recurse on the C stack

## What's deliberately not here yet

- finger-tree-based deque scheduling
- adaptive hash-trie depth
- structural `=` for the heap
- a dedicated structure-level fuzzer (fuzzing happens at the `rc` layer
  instead, since that's where the lifetime risk concentrates)
