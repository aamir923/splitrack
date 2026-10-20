#!/usr/bin/env bash
# Generates a LOCAL development RSA key pair for signing/verifying gave's internal JWTs.
# The private key stays on your machine (never commit it, never share it).
# Services only need the public key; only the api-gateway (or mint-dev-jwt.sh) uses the private one.
set -euo pipefail

dir="${GAVE_KEYS_DIR:-$HOME/.gave/keys}"

if [ -e "$dir/jwt-private.pem" ]; then
  echo "Keys already exist in $dir, not overwriting." >&2
  exit 0
fi

mkdir -p "$dir"
chmod 700 "$dir"
openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 -out "$dir/jwt-private.pem" 2>/dev/null
openssl pkey -in "$dir/jwt-private.pem" -pubout -out "$dir/jwt-public.pem"
chmod 600 "$dir/jwt-private.pem"
chmod 644 "$dir/jwt-public.pem"

echo "Created $dir/jwt-private.pem (PKCS#8) and $dir/jwt-public.pem (X.509)."
