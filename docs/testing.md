# Testing

This page covers what's tested, how to run it, and which knobs you have. If
you just want to know whether things work on your machine, run
`./scripts/validate.sh` and watch for green.

## How testing is organized

Each collection has its own suite under `test/`:

- `test/persistent_list.carp`
- `test/persistent_queue.carp`
- `test/persistent_deque.carp`
- `test/persistent_trie.carp`
- `test/persistent_ord_map.carp`
- `test/persistent_ord_set.carp`
- `test/persistent_heap.carp`
- `test/persistent_hash_map.carp`
- `test/persistent_hash_set.carp`
- `test/persistent_vector.carp`

Tests are deterministic and semantic rather than randomized. Heavy randomized
fuzzing lives at the [`rc`](https://github.com/carpentry-org/rc) layer,
because that's where the shared-ownership and teardown risk lives. If `rc`
holds, the structures on top of it are mostly checking that they wire it up
correctly.

## What each suite covers

For every collection:

- constructors and emptiness checks
- the structure-specific update operations (insert/update/remove/push/pop/get)
- *persistence*: confirming that older versions are unchanged after updates
- `ptr-eq` behavior on branching scenarios (sharing actually happens)
- iteration via `reduce`/`each`/`to-array`
- structural `=` where defined

A few structure-specific checks worth calling out:

- **trie**: prefix lookup and empty-key behavior
- **ordered map**: min/max and successor-based delete
- **heap**: merge correctness and that repeated `pop` produces sorted output
- **hash map**: regression for `Int.MIN` hash paths (the bit-twiddling that
  derives the digit path used to be off for the most-negative integer)
- **vector**: `assoc` and `pop-back` branch behavior

And robustness:

- per-suite memory-balance check (no leaks, no double-frees)
- deep-input regressions for everything that traverses recursively in spirit:
  list `drop-n` at 200k; queue/deque/trie/heap at 100k; ordered map ascending
  inserts at 10k

## Running a single suite

```sh
carp -x test/persistent_list.carp --log-memory --no-profile
```

The `--log-memory` flag is what enables the memory-balance assertions.
`--no-profile` keeps the output focused on the test results.

## Running the full validation matrix

```sh
./scripts/validate.sh
```

This runs every suite under three configurations:

- **sanitized**: `O1` with ASan/UBSan. This is the lane that catches memory
  bugs and undefined behavior.
- **release-like**: `O2`. Closer to what you'd actually ship.
- **optimizer stress**: `O3`. Catches places where the optimizer reveals
  latent issues. Disable with `PERSISTENT_VALIDATE_RUN_O3=0` if you're in a
  hurry.

## Environment variables

For ad-hoc runs without the script:

| Variable | Values | Default |
| --- | --- | --- |
| `PERSISTENT_OPT_LEVEL` | `O0` / `O1` / `O2` / `O3` | `O1` |
| `PERSISTENT_SANITIZE` | `1` / `true` / `yes` to enable | off |
| `PERSISTENT_VALIDATE_RUN_O3` | `1` / `0` for the validate script's O3 lane | `1` |
| `CARP_BIN` | path to the `carp` executable | uses `$PATH` |

## Expected warning noise

The current Carp release sometimes emits warnings about internal generated
`Rc` node handle types: `No 'prn'` and `Too many 'delete'`. They're an
artifact of how `Rc.define` interacts with the surrounding deftype system,
not signals of a real problem. As long as the test suites pass and the
sanitizer lane is clean, treat them as known noise.

## Why no fuzzer here?

The structures in this library are layered: `persistent` collections are all
made of `Rc`-managed nodes. The interesting failure modes (lifetime races,
strong/weak interactions, drop ordering) are all expressible at the `rc`
layer, and that's where the dedicated fuzzer lives. Writing another fuzzer
on top would mostly re-find the same bugs through more indirection.

If you're poking at something you suspect is a memory issue here, the right
first move is to enable the sanitized lane and rerun.
