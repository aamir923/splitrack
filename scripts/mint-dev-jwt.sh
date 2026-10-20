#!/usr/bin/env bash
# Mints a LOCAL development JWT in the same shape the api-gateway produces, so a service can be
# developed and called directly (curl/Postman) without Google login or the gateway.
#
#   usage: scripts/mint-dev-jwt.sh <user-uuid> [ttl-seconds]
#   e.g.   curl -H "Authorization: Bearer $(scripts/mint-dev-jwt.sh 11111111-2222-3333-4444-555555555555)" ...
#
# Signs with YOUR local private key (see gen-dev-keys.sh). Production keys never exist on a dev machine.
set -euo pipefail

user_id="${1:?usage: mint-dev-jwt.sh <user-uuid> [ttl-seconds]}"
ttl="${2:-3600}"
key="${GAVE_JWT_PRIVATE_KEY:-$HOME/.gave/keys/jwt-private.pem}"
issuer="${GAVE_JWT_ISSUER:-gave-gateway}"
audience="${GAVE_JWT_AUDIENCE:-gave-services}"
key_id="${GAVE_JWT_KEY_ID:-gateway-1}"

[ -r "$key" ] || { echo "Private key not found at $key (run scripts/gen-dev-keys.sh)" >&2; exit 1; }

b64url() { openssl base64 -A | tr '+/' '-_' | tr -d '='; }

now=$(date +%s)
header=$(printf '{"alg":"RS256","kid":"%s","typ":"JWT"}' "$key_id" | b64url)
payload=$(printf '{"iss":"%s","aud":["%s"],"sub":"%s","sid":"dev-session","iat":%d,"exp":%d}' \
  "$issuer" "$audience" "$user_id" "$now" "$((now + ttl))" | b64url)
signature=$(printf '%s' "$header.$payload" | openssl dgst -sha256 -sign "$key" | b64url)

printf '%s.%s.%s\n' "$header" "$payload" "$signature"
