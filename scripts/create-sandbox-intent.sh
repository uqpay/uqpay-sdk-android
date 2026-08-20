#!/usr/bin/env bash
#
# Creates a SANDBOX payment intent and writes its id to local.properties as
# uqpay.intentId, where the smoke test and (later) the sample app pick it up.
#
#   ./scripts/create-sandbox-intent.sh [amount] [currency] [description]
#   ./scripts/create-sandbox-intent.sh              # defaults to 8.98 SGD
#
# Development convenience only. Creating intents is a MERCHANT BACKEND job — the SDK
# cannot do it and must not be able to. Your backend owns this call because it decides
# the real price; an app that chooses its own amount can be edited by the customer.
#
# Request shape copied from the shipped iOS demo backend
# (Examples/SwiftUIExample/.../DemoMerchantBackend.swift:130-145):
#   POST {base}/api/v2/payment_intents/create
#   headers: x-auth-token: Bearer <token>, x-client-id, Content-Type: application/json
#   body:    { amount, currency, merchant_order_id, description, return_url }
#
# `amount` is a DECIMAL STRING IN MAJOR UNITS — "8.98", never cents, never a number.
#
# Sandbox only: no real money moves. Intents auto-expire 30 minutes after creation.

set -euo pipefail

cd "$(dirname "$0")/.."

BASE="https://api-sandbox.uqpaytech.com"
PROPS="local.properties"
RETURN_URL="uqpaysample://payment"

AMOUNT="${1:-8.98}"
CURRENCY="${2:-SGD}"
DESCRIPTION="${3:-Android SDK sandbox test}"

[ -f "$PROPS" ] || { echo "No $PROPS." >&2; exit 1; }

CLIENT_ID=$(grep '^uqpay.clientId=' "$PROPS" | cut -d= -f2- | tr -d ' \r')
TOKEN=$(grep '^uqpay.sandboxToken=' "$PROPS" | cut -d= -f2- | tr -d ' \r')

[ -n "$CLIENT_ID" ] || { echo "uqpay.clientId is not set." >&2; exit 1; }
if [ -z "$TOKEN" ]; then
  echo "uqpay.sandboxToken is empty. Run ./scripts/mint-sandbox-token.sh first." >&2
  exit 1
fi

# Lowercased UUID: UQPAY rejects uppercase in id-shaped fields.
ORDER_ID=$(uuidgen | tr '[:upper:]' '[:lower:]')

echo "Creating a SANDBOX intent: $AMOUNT $CURRENCY"

# Every mutating call needs x-idempotency-key, and the gateway validates its FORMAT:
# omitting it (or sending a non-UUID) returns
#   {"type":"idempotency_error","code":"invalid idempotency key format"}
# Lowercased, because UQPAY rejects uppercase UUIDs.
IDEMPOTENCY_KEY=$(uuidgen | tr '[:upper:]' '[:lower:]')

RESPONSE=$(curl -sS -X POST "$BASE/api/v2/payment_intents/create" \
  -H "x-auth-token: Bearer $TOKEN" \
  -H "x-client-id: $CLIENT_ID" \
  -H "x-idempotency-key: $IDEMPOTENCY_KEY" \
  -H "Content-Type: application/json" \
  -H "Accept: application/json" \
  -d "{\"amount\":\"$AMOUNT\",\"currency\":\"$CURRENCY\",\"merchant_order_id\":\"$ORDER_ID\",\"description\":\"$DESCRIPTION\",\"return_url\":\"$RETURN_URL\"}")

python3 - "$PROPS" <<PY
import json, re, sys

props = sys.argv[1]
raw = """$RESPONSE"""

try:
    data = json.loads(raw)
except json.JSONDecodeError:
    sys.exit(f"Gateway did not return JSON:\n{raw[:400]}")

intent_id = data.get("id") or data.get("payment_intent_id")
if not intent_id:
    # Error bodies carry no credential, so showing this is safe.
    sys.exit(f"No intent id in the response: {json.dumps(data)[:400]}")

with open(props) as f:
    text = f.read()

line = f"uqpay.intentId={intent_id}"
if re.search(r"^uqpay\.intentId=.*\$", text, flags=re.M):
    text = re.sub(r"^uqpay\.intentId=.*\$", line, text, flags=re.M)
else:
    text = text.rstrip("\n") + "\n" + line + "\n"

with open(props, "w") as f:
    f.write(text)

print(f"Created {intent_id}")
print(f"  status   {data.get('intent_status')}")
print(f"  amount   {data.get('amount')} {data.get('currency')}")
methods = data.get("available_payment_method_types")
print(f"  methods  {methods}")
print(f"Wrote uqpay.intentId to {props}. Expires in ~30 minutes.")
PY
