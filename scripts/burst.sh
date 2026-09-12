#!/usr/bin/env bash
#
# Burst / concurrency proof script for the wallet transfer service.
# Reproduces, against a running instance:
#   1. Race-free get-or-create      (50 concurrent POST /wallets, same user)
#   2. Idempotent retry storm       (30 concurrent POST /transfers, same key)
#   3. Conservation under contention (100 concurrent A->B + 100 concurrent B->A)
#
# Usage:
#   ./scripts/burst.sh [BASE_URL]
#
# Requires: bash, curl, jq
set -euo pipefail

BASE_URL="${1:-http://localhost:3000}"
TMP_DIR="$(mktemp -d)"
trap 'rm -rf "$TMP_DIR"' EXIT

FAILED=0
pass() { echo "  PASS: $1"; }
fail() { echo "  FAIL: $1"; FAILED=1; }

echo "Target: $BASE_URL"
echo

# ---------------------------------------------------------------------------
# Test 1: race-free get-or-create
# ---------------------------------------------------------------------------
echo "== Test 1: race-free get-or-create (50 concurrent POST /wallets, same user) =="
USER1="burst-user-$(date +%s)-$RANDOM"
for i in $(seq 1 50); do
  curl -s -X POST "$BASE_URL/wallets" \
    -H "Authorization: Bearer $USER1" \
    -o "$TMP_DIR/wallet_$i.json" &
done
wait

UNIQUE_IDS=$(jq -r '.id' "$TMP_DIR"/wallet_*.json | sort -u | wc -l | tr -d ' ')
echo "  distinct wallet ids returned: $UNIQUE_IDS"
if [ "$UNIQUE_IDS" = "1" ]; then pass "exactly one wallet"; else fail "expected 1 wallet, got $UNIQUE_IDS"; fi
echo

WALLET1_ID=$(jq -r '.id' "$TMP_DIR/wallet_1.json")

# ---------------------------------------------------------------------------
# Setup for tests 2 & 3
# ---------------------------------------------------------------------------
USER2="burst-user-$(date +%s)-$RANDOM-2"
WALLET2_ID=$(curl -s -X POST "$BASE_URL/wallets" -H "Authorization: Bearer $USER2" | jq -r '.id')

curl -s -X POST "$BASE_URL/wallets/$WALLET1_ID/seed" \
  -H "Content-Type: application/json" -d '{"amountPaise": 1000000}' > /dev/null
curl -s -X POST "$BASE_URL/wallets/$WALLET2_ID/seed" \
  -H "Content-Type: application/json" -d '{"amountPaise": 1000000}' > /dev/null

# ---------------------------------------------------------------------------
# Test 2: idempotent retry storm
# ---------------------------------------------------------------------------
echo "== Test 2: idempotent retry storm (30 concurrent POST /transfers, same key) =="
IDEMP_KEY="idem-$(date +%s)-$RANDOM"
BAL_BEFORE=$(curl -s "$BASE_URL/wallets/$WALLET1_ID" | jq -r '.balancePaise')

for i in $(seq 1 30); do
  curl -s -X POST "$BASE_URL/transfers" \
    -H "Authorization: Bearer $USER1" -H "Content-Type: application/json" \
    -d "{\"from\": $WALLET1_ID, \"to\": $WALLET2_ID, \"amountPaise\": 500, \"idempotencyKey\": \"$IDEMP_KEY\"}" \
    -o "$TMP_DIR/xfer_$i.json" &
done
wait

UNIQUE_XFER_IDS=$(jq -r '.id' "$TMP_DIR"/xfer_*.json | sort -u | wc -l | tr -d ' ')
BAL_AFTER=$(curl -s "$BASE_URL/wallets/$WALLET1_ID" | jq -r '.balancePaise')
DEBITED=$((BAL_BEFORE - BAL_AFTER))

echo "  distinct transfer ids returned: $UNIQUE_XFER_IDS"
echo "  debited from wallet 1: $DEBITED paise (expected 500)"
if [ "$UNIQUE_XFER_IDS" = "1" ]; then pass "all 30 responses reference the same transfer"; else fail "got $UNIQUE_XFER_IDS distinct transfer ids"; fi
if [ "$DEBITED" = "500" ]; then pass "debited exactly once"; else fail "expected 500 paise debited, got $DEBITED"; fi
echo

# ---------------------------------------------------------------------------
# Test 3: conservation + no-overdraft under contention
# ---------------------------------------------------------------------------
echo "== Test 3: conservation under contention (A->B and B->A concurrently) =="
TOTAL_BEFORE=$(( $(curl -s "$BASE_URL/wallets/$WALLET1_ID" | jq -r '.balancePaise') + \
                  $(curl -s "$BASE_URL/wallets/$WALLET2_ID" | jq -r '.balancePaise') ))

for i in $(seq 1 100); do
  curl -s -X POST "$BASE_URL/transfers" \
    -H "Authorization: Bearer $USER1" -H "Content-Type: application/json" \
    -d "{\"from\": $WALLET1_ID, \"to\": $WALLET2_ID, \"amountPaise\": 137, \"idempotencyKey\": \"a2b-$i-$RANDOM\"}" \
    -o "$TMP_DIR/a2b_$i.json" &
  curl -s -X POST "$BASE_URL/transfers" \
    -H "Authorization: Bearer $USER2" -H "Content-Type: application/json" \
    -d "{\"from\": $WALLET2_ID, \"to\": $WALLET1_ID, \"amountPaise\": 211, \"idempotencyKey\": \"b2a-$i-$RANDOM\"}" \
    -o "$TMP_DIR/b2a_$i.json" &
done
wait

BAL1=$(curl -s "$BASE_URL/wallets/$WALLET1_ID" | jq -r '.balancePaise')
BAL2=$(curl -s "$BASE_URL/wallets/$WALLET2_ID" | jq -r '.balancePaise')
TOTAL_AFTER=$((BAL1 + BAL2))

echo "  total before: $TOTAL_BEFORE, total after: $TOTAL_AFTER"
echo "  wallet1: $BAL1, wallet2: $BAL2"
if [ "$TOTAL_BEFORE" = "$TOTAL_AFTER" ]; then pass "conservation held"; else fail "total changed ($TOTAL_BEFORE -> $TOTAL_AFTER)"; fi
if [ "$BAL1" -ge 0 ] && [ "$BAL2" -ge 0 ]; then pass "no negative balances"; else fail "a balance went negative"; fi

ERR_500=$(cat "$TMP_DIR"/a2b_*.json "$TMP_DIR"/b2a_*.json 2>/dev/null | jq -r '.error? // empty' | grep -c "internal error" || true)
echo "  internal-error responses: $ERR_500"
if [ "$ERR_500" = "0" ]; then pass "no internal errors / deadlocks surfaced"; else fail "$ERR_500 internal errors seen"; fi

echo
if [ "$FAILED" = "1" ]; then
  echo "RESULT: one or more checks FAILED"
  exit 1
else
  echo "RESULT: all checks passed"
fi
