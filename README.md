# persistent

In which we define immutable collections for Carp: lists, queues, deques,
vectors, maps, sets, heaps, and tries. Every update returns a new collection
that shares memory with the old one, so the previous version stays valid and
cheap to keep around. An idea from Clojure.

```clojure
(Persistent.define-list IntList Int)

(let [a (IntList.prepend 1 &(IntList.empty))
      b (IntList.prepend 2 &a)]
  ; a is still (1); b is (2 1) and shares its tail with a.
  ...)
```

Reach for this when you want snapshots, undo, branching state, or to pass a
collection around without worrying about who mutated it. For single-version
mutable workloads, core Carp's `Array` and `Map` are lighter.

## Installation

```clojure
(load "git@github.com:carpentry-org/persistent@0.1.0")
```

`persistent` is built on [`rc`](https://github.com/carpentry-org/rc) to enable
the lifetimes of this.

## Collections

| Form | Notes |
| --- | --- |
| `define-list` | LIFO sequence. Cheap prepend/head/tail. |
| `define-queue` | FIFO. Amortized O(1) ends. |
| `define-deque` | Double-ended queue. |
| `define-vector` | Random-access indexed sequence. |
| `define-ord-map`, `define-ord-set` | Sorted. Supports min/max and ordered iteration. |
| `define-hash-map`, `define-hash-set` | Unordered. Near-O(1) lookup. |
| `define-trie` | Map keyed by `(Array K)`, with prefix queries. |
| `define-heap` | Min-heap. |

Every `define-*` generates a concrete type and module for one payload type.
The generated module always provides `empty`, `singleton`, `length`,
`empty?`, `reduce`, `each`, `to-array`, `str`, and `=` (heap omits `=`).
Map-likes additionally provide `get`, `insert`, `remove`, `contains?`,
`keys`, `values`. Per-collection extras (`min-key`, `merge`, `assoc`, …)
live in the generated HTML docs.

## Caveats

- Single-threaded (inherited from `rc`).
- Ordered map and set are unbalanced BSTs; adversarial input degrades to
  linear.
- Hash map and vector both use fixed-depth tries.
- Current Carp may emit `No 'prn'` / `Too many 'delete'` warnings for
  generated `Rc` handles. Known noise.

Read more about the design [here](docs/design.md).

## Testing

For full validation:

```sh
./scripts/validate.sh
```

More info [here](docs/testing.md).

<hr/>

Cheers
