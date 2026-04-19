# persistent

Immutable collections for Carp: lists, queues, deques, maps, sets, heaps, and
vectors that you can update without losing the old version.

```clojure
(Persistent.define-vector IntVec Int)

(let [v0 (IntVec.empty)
      v1 (IntVec.push-back 10 &v0)
      v2 (IntVec.push-back 20 &v1)]
  ; v0, v1, and v2 are all still valid and disagree about their contents.
  ...)
```

Each "update" returns a new collection that shares most of its memory with the
old one, so keeping multiple versions around is cheap. This is what people
usually mean by *persistent* data structures: the previous version persists,
even after you've moved on.

## When you might reach for this

- you want to thread a collection through a pipeline without worrying about
  whether some downstream function mutated it
- you want cheap snapshots, undo, or branching versions of state
- you're modeling something with multiple coexisting versions (game states,
  speculative search, time-travel debugging, etc.)

If you only ever need the latest version and never branch, the regular
mutable `Array`/`Map` in core Carp will be faster and lighter.

## Installation

```clojure
(load "git@github.com:carpentry-org/rc@0.1.1")
(load "git@github.com:carpentry-org/persistent@0.1.0")
```

`persistent` builds on [`rc`](https://github.com/carpentry-org/rc) for
reference-counted sharing. You need to load `rc` in the same load graph.

## Quick start

You generate a concrete collection type for the payload type you care about,
then use the module it produces.

```clojure
(load "git@github.com:carpentry-org/rc@0.1.1")
(load "git@github.com:carpentry-org/persistent@0.1.0")

; Generate a list of Int and a map from Int to String:
(Persistent.define-list IntList Int)
(Persistent.define-ord-map IntStrMap Int String)

(defn main []
  (let [l0 (IntList.empty)
        l1 (IntList.prepend 2 &l0)
        l2 (IntList.prepend 1 &l1)        ; l2 is (1 2), l1 is (2), l0 is ()
        m0 (IntStrMap.empty)
        m1 (IntStrMap.insert 7 @"seven" &m0)
        m2 (IntStrMap.insert 3 @"three" &m1)]
    (do
      (match (IntList.head &l2)
        (Maybe.Just x) (IO.println &(Int.str x))   ; prints "1"
        (Maybe.Nothing) (IO.println "empty"))
      (match (IntStrMap.get 7 &m2)
        (Maybe.Just s) (IO.println &s)             ; prints "seven"
        (Maybe.Nothing) (IO.println "missing")))))
```

The `define-*` form generates a type and a module. After
`(Persistent.define-list IntList Int)` you can use `IntList.empty`,
`IntList.prepend`, `IntList.head`, and so on, exactly as if you'd written that
module by hand.

## Why a generator instead of a single generic type?

Carp has generics, so this is a real choice. The blocker is `Rc`: it's not
a `(Rc T)` type, it's a macro that emits a concrete handle plus its
`copy`/`delete` plumbing. Anything storing reference-counted nodes has to
be concrete by the time `Rc.define` runs, and once that's true at the
bottom, the recursive node and wrapper types above it are concrete too.

Practically: one `define-*` per payload type up front, then a regular
Carp module afterwards, with no runtime dispatch.

## The collections

| Form | Use when |
| --- | --- |
| `define-list` | LIFO sequence with cheap prepend/head/tail. Classic cons list. |
| `define-queue` | FIFO. Amortized O(1) enqueue/dequeue. |
| `define-deque` | Double-ended queue. O(1) push/pop on both ends most of the time. |
| `define-vector` | Random-access indexable sequence with `push-back`/`assoc`/`pop-back`. |
| `define-ord-map` | Sorted map keyed by ordered key. Gives you `min-key`, `max-key`, in-order traversal. |
| `define-ord-set` | Sorted set. Same shape as ord-map without values. |
| `define-hash-map` | Unordered map with near O(1) lookup, for `hash`-able keys. |
| `define-hash-set` | Unordered set. |
| `define-trie` | Map keyed by `(Array K)`, with prefix queries (`contains-prefix?`). |
| `define-heap` | Min-heap. Cheap merge, insert, and pop-min. |

Rough rule of thumb: reach for `vector` for indexed sequences, `hash-map` for
key/value lookup, `ord-map`/`ord-set` when you need ordered iteration or
min/max, `heap` for priority queues, `trie` for keyed-by-path data, and
`list`/`queue`/`deque` when you want cheap structural sharing on the ends.

## What every generated module gives you

After `(Persistent.define-X MyType ...)`:

- **construction**: `empty`, `singleton`
- **size**: `length`, `empty?`
- **iteration**: `reduce`, `each`, `to-array`
- **identity**: `ptr-eq` (do these two share the same root?), `str` (debug print)
- **structural equality** via `=`, for everything except heap (multiset
  equality is application-specific, so we don't pick one for you)

Map-like collections (ord-map, hash-map, trie) also give you:

- `get`, `insert`, `remove`, `contains?`
- `keys`, `values`

The ordered map/set additionally give you `min-key`/`max-key`. The trie also
gives you `contains-prefix?` and `reduce-values`. The deque gives you both
ends. The heap gives you `merge`/`insert`/`peek`/`pop`.

Generated HTML docs ([docs/Persistent.html](docs/Persistent.html)) list the
exact signatures.

## A few worked examples

### Branch a list and observe sharing

```clojure
(Persistent.define-list IntList Int)

(let [base  (IntList.singleton 1)
      left  (IntList.prepend 2 &base)
      right (IntList.prepend 3 &base)]
  ; left = (2 1), right = (3 1), and they share the (1) tail in memory.
  (match (IntList.tail &left)
    (Maybe.Just lt)
      (match (IntList.tail &right)
        (Maybe.Just rt)
          (IO.println (Bool.str (IntList.ptr-eq &lt &rt))) ; "true"
        (Maybe.Nothing) ())
    (Maybe.Nothing) ()))
```

### A simple word counter using a hash map

```clojure
(Persistent.define-hash-map StrIntMap String Int)

(defn bump [m word]
  (let [cur (match (StrIntMap.get word m)
              (Maybe.Just n) n
              (Maybe.Nothing) 0)]
    (StrIntMap.insert @word (Int.inc cur) m)))
```

### Pop minimum repeatedly from a heap

```clojure
(Persistent.define-heap IntHeap Int)

(defn drain [h]
  (match (IntHeap.pop &h)
    (Maybe.Just pair)
      (do
        (IO.println &(Int.str @(Pair.a &pair)))
        (drain @(Pair.b &pair)))
    (Maybe.Nothing) ()))
```

## Limitations

Worth knowing before you build something large on top:

- **Single-threaded.** `rc` uses non-atomic counters. Don't share these
  collections across threads.
- **No cycle collection.** Inherited from `rc`. Persistent structures don't
  normally form cycles, but if you stuff your own cyclic payloads in, you'll
  leak.
- **Ordered map/set are unbalanced.** They're plain BSTs. Adversarial
  insertion order (e.g. always-ascending keys) degrades to linear behavior.
  If you need worst-case logarithmic, use the hash map or wait for a balanced
  variant.
- **Hash map** uses a fixed depth (8 nibbles) with linear-scan collision
  buckets. Good for typical workloads, not tuned for pathological hash
  distributions.
- **Vector** uses fixed depth (7 base-32 digits), so addressable size is
  bounded.
- **Heap** doesn't define `=` for you. Pick the multiset semantics that
  match your use case.
- **Compiler noise.** Current Carp may emit `No 'prn'` / `Too many 'delete'`
  warnings about internal generated `Rc` node handles even when everything
  works. These are known.

## Testing

Run a single suite:

```sh
carp -x test/persistent_list.carp --log-memory --no-profile
```

Run the full validation matrix (sanitizers, multiple optimization levels):

```sh
./scripts/validate.sh
```

See [docs/testing.md](docs/testing.md) for what each lane covers and the
environment variables you can use to tune it.

## More documentation

- [docs/design.md](docs/design.md): how each structure is implemented and why
- [docs/testing.md](docs/testing.md): what's tested and how to run it
- [docs/Persistent.html](docs/Persistent.html): full per-function API docs
- Regenerate HTML: `carp gendocs.carp -x`
