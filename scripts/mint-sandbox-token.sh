#!/usr/bin/env bash
#
# Mints a sandbox access token and writes it into local.properties.
#
# Development convenience only. This is what a MERCHANT BACKEND does — never an app.
# The x-api-key can issue refunds and payouts, so this script reads it from a hidden
# prompt, keeps it in memory only, and never writes it, logs it, or prints it.
#
#   ./scripts/mint-sandbox-token.sh
#
# Request shape verified against the shipped iOS SDK
# (Examples/.../DemoMerchantBackend.swift:93-101):
#   POST {base}/api/v1/connect/token
#   headers: x-client-id, x-api-key, Accept: application/json
#   returns: { "auth_token": "…", "expired_at": <unix epoch seconds> }
#
# ⚠️  UQPAY allows ONE active access token per merchant. Minting here INVALIDATES
#     whatever token your backend and any other device are currently holding.

set -euo pipefail

cd "$(dirname "$0")/.."

BASE="https://api-sandbox.uqpaytech.com"
PROPS="local.properties"

[ -f "$PROPS" ] || { echo "No $PROPS. Create it first." >&2; exit 1; }

CLIENT_ID=$(grep '^uqpay.clientId=' "$PROPS" | cut -d= -f2- | tr -d ' \r')
[ -n "$CLIENT_ID" ] || { echo "uqpay.clientId is not set in $PROPS." >&2; exit 1; }

echo "Minting a SANDBOX token for client ${CLIENT_ID:0:8}…"
echo "This invalidates your merchant's current active token. Ctrl-C to abort."
echo

# Prefer a key already parked in local.properties (gitignored, never read into
# BuildConfig). Falls back to a hidden prompt so the key need not be stored at all.
# -s: never echoed to the terminal, never enters shell history or the transcript.
API_KEY=$(grep '^uqpay.sandboxApiKey=' "$PROPS" | cut -d= -f2- | tr -d ' \r')
if [ -z "$API_KEY" ]; then
  read -r -s -p "sandbox x-api-key: " API_KEY
  echo
else
  echo "Using uqpay.sandboxApiKey from $PROPS."
fi

RESPONSE=$(curl -sS -X POST "$BASE/api/v1/connect/token" \
  -H "x-client-id: $CLIENT_ID" \
  -H "x-api-key: $API_KEY" \
  -H "Accept: application/json")
unset API_KEY

# Parses and rewrites the file without ever putting the token on stdout.
python3 - "$PROPS" <<PY
import json, re, sys

props = sys.argv[1]
raw = """$RESPONSE"""

try:
    data = json.loads(raw)
except json.JSONDecodeError:
    sys.exit("Gateway did not return JSON. Check the API key and client id.")

token = data.get("auth_token")
if not token:
    # Error bodies carry no credential, so this is safe to show.
    sys.exit(f"No auth_token in the response: {data}")

expires = data.get("expired_at")

with open(props) as f:
    text = f.read()

line = f"uqpay.sandboxToken={token}"
if re.search(r"^uqpay\.sandboxToken=.*$", text, flags=re.M):
    text = re.sub(r"^uqpay\.sandboxToken=.*$", line, text, flags=re.M)
else:
    text = text.rstrip("\n") + "\n" + line + "\n"

with open(props, "w") as f:
    f.write(text)

msg = f"Wrote a {len(token)}-char token to {props}."
if expires:
    import datetime
    when = datetime.datetime.fromtimestamp(float(expires))
    mins = (when - datetime.datetime.now()).total_seconds() / 60
    msg += f" Expires {when:%H:%M:%S} (~{mins:.0f} min)."
print(msg)
PY
