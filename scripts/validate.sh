#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
CARP_BIN="${CARP_BIN:-carp}"
PERSISTENT_LIST_TEST="$ROOT/test/persistent_list.carp"
PERSISTENT_QUEUE_TEST="$ROOT/test/persistent_queue.carp"
PERSISTENT_TRIE_TEST="$ROOT/test/persistent_trie.carp"
PERSISTENT_DEQUE_TEST="$ROOT/test/persistent_deque.carp"
PERSISTENT_ORD_MAP_TEST="$ROOT/test/persistent_ord_map.carp"
PERSISTENT_ORD_SET_TEST="$ROOT/test/persistent_ord_set.carp"
PERSISTENT_HEAP_TEST="$ROOT/test/persistent_heap.carp"
PERSISTENT_HASH_MAP_TEST="$ROOT/test/persistent_hash_map.carp"
PERSISTENT_HASH_SET_TEST="$ROOT/test/persistent_hash_set.carp"
PERSISTENT_VECTOR_TEST="$ROOT/test/persistent_vector.carp"

if ! command -v "$CARP_BIN" >/dev/null 2>&1; then
  echo "carp executable not found in PATH: $CARP_BIN" >&2
  exit 1
fi

RUN_O3="${PERSISTENT_VALIDATE_RUN_O3:-1}"

run_lane() {
  local lane_name="$1"
  local opt_level="$2"
  local sanitize="$3"

  echo ""
  echo "==> $lane_name (PERSISTENT_OPT_LEVEL=$opt_level PERSISTENT_SANITIZE=$sanitize)"

  PERSISTENT_OPT_LEVEL="$opt_level" \
  PERSISTENT_SANITIZE="$sanitize" \
  "$CARP_BIN" "$PERSISTENT_LIST_TEST" -x --log-memory --no-profile

  PERSISTENT_OPT_LEVEL="$opt_level" \
  PERSISTENT_SANITIZE="$sanitize" \
  "$CARP_BIN" "$PERSISTENT_QUEUE_TEST" -x --log-memory --no-profile

  PERSISTENT_OPT_LEVEL="$opt_level" \
  PERSISTENT_SANITIZE="$sanitize" \
  "$CARP_BIN" "$PERSISTENT_TRIE_TEST" -x --log-memory --no-profile

  PERSISTENT_OPT_LEVEL="$opt_level" \
  PERSISTENT_SANITIZE="$sanitize" \
  "$CARP_BIN" "$PERSISTENT_DEQUE_TEST" -x --log-memory --no-profile

  PERSISTENT_OPT_LEVEL="$opt_level" \
  PERSISTENT_SANITIZE="$sanitize" \
  "$CARP_BIN" "$PERSISTENT_ORD_MAP_TEST" -x --log-memory --no-profile

  PERSISTENT_OPT_LEVEL="$opt_level" \
  PERSISTENT_SANITIZE="$sanitize" \
  "$CARP_BIN" "$PERSISTENT_ORD_SET_TEST" -x --log-memory --no-profile

  PERSISTENT_OPT_LEVEL="$opt_level" \
  PERSISTENT_SANITIZE="$sanitize" \
  "$CARP_BIN" "$PERSISTENT_HEAP_TEST" -x --log-memory --no-profile

  PERSISTENT_OPT_LEVEL="$opt_level" \
  PERSISTENT_SANITIZE="$sanitize" \
  "$CARP_BIN" "$PERSISTENT_HASH_MAP_TEST" -x --log-memory --no-profile

  PERSISTENT_OPT_LEVEL="$opt_level" \
  PERSISTENT_SANITIZE="$sanitize" \
  "$CARP_BIN" "$PERSISTENT_HASH_SET_TEST" -x --log-memory --no-profile

  PERSISTENT_OPT_LEVEL="$opt_level" \
  PERSISTENT_SANITIZE="$sanitize" \
  "$CARP_BIN" "$PERSISTENT_VECTOR_TEST" -x --log-memory --no-profile
}

echo "Running persistent validation matrix"

run_lane "sanitized lane" "O1" "1"
run_lane "release lane" "O2" "0"

if [[ "$RUN_O3" == "1" || "$RUN_O3" == "true" || "$RUN_O3" == "yes" ]]; then
  run_lane "optimizer-stress lane" "O3" "0"
fi

echo ""
echo "persistent validation matrix passed"
