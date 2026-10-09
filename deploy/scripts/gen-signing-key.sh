#!/usr/bin/env sh
# Generates the Ed25519 signing key of Back Office (ADR 0025: provenance attestations, signed runner
# assignments) in the env format the backend reads:
#   BACKOFFICE_SIGNING_KEYS_<n>_ID, _PRIVATE_KEY_PKCS8_BASE64, _PUBLIC_KEY_X509_BASE64, _ACTIVE
#
# Usage:
#   deploy/scripts/gen-signing-key.sh [--index N] [--id KEY_ID] [--inactive]            # print env lines
#   deploy/scripts/gen-signing-key.sh --secret back-office-signing --namespace back-office   # kubectl create
#   deploy/scripts/gen-signing-key.sh --secret back-office-signing --namespace back-office --index 1 --merge
#                                                           # rotation: add key 1 to the existing Secret
#
# Rotation (docs/runbooks/upgrade.md): add the new key with --index <next> --merge, set the old
# key's _ACTIVE to false, roll the pods. Keep old keys in the Secret: their public keys stay published at
# /.well-known/back-office/keys so older attestations still verify.
# Requires openssl 1.1.1+ (Ed25519) and base64; kubectl for --secret.
set -eu

INDEX=0
KEY_ID="bo-$(date -u +%Y%m%d)"
ACTIVE=true
SECRET=""
NAMESPACE=""
MERGE=false

while [ $# -gt 0 ]; do
  case "$1" in
    --index) INDEX="$2"; shift 2 ;;
    --id) KEY_ID="$2"; shift 2 ;;
    --inactive) ACTIVE=false; shift ;;
    --secret) SECRET="$2"; shift 2 ;;
    --namespace|-n) NAMESPACE="$2"; shift 2 ;;
    --merge) MERGE=true; shift ;;
    -h|--help) sed -n "2,17p" "$0"; exit 0 ;;
    *) echo "unknown argument: $1" >&2; exit 2 ;;
  esac
done

command -v openssl > /dev/null || { echo "openssl is required" >&2; exit 1; }
TMP="$(mktemp -d)"
trap "rm -rf \"$TMP\"" EXIT INT TERM

openssl genpkey -algorithm ed25519 -outform DER -out "$TMP/private.der"
openssl pkey -inform DER -in "$TMP/private.der" -pubout -outform DER -out "$TMP/public.der"
PRIV="$(base64 < "$TMP/private.der" | tr -d "\n")"
PUB="$(base64 < "$TMP/public.der" | tr -d "\n")"
P="BACKOFFICE_SIGNING_KEYS_${INDEX}"

if [ -z "$SECRET" ]; then
  printf "%s_ID=%s\n%s_PRIVATE_KEY_PKCS8_BASE64=%s\n%s_PUBLIC_KEY_X509_BASE64=%s\n%s_ACTIVE=%s\n" \
    "$P" "$KEY_ID" "$P" "$PRIV" "$P" "$PUB" "$P" "$ACTIVE"
  exit 0
fi

command -v kubectl > /dev/null || { echo "kubectl is required for --secret" >&2; exit 1; }
NS_ARG=""
[ -n "$NAMESPACE" ] && NS_ARG="--namespace=$NAMESPACE"

if [ "$MERGE" = true ]; then
  # shellcheck disable=SC2086
  kubectl $NS_ARG get secret "$SECRET" > /dev/null
  PATCH="{\"stringData\":{\"${P}_ID\":\"$KEY_ID\",\"${P}_PRIVATE_KEY_PKCS8_BASE64\":\"$PRIV\",\"${P}_PUBLIC_KEY_X509_BASE64\":\"$PUB\",\"${P}_ACTIVE\":\"$ACTIVE\"}}"
  # shellcheck disable=SC2086
  kubectl $NS_ARG patch secret "$SECRET" --type merge -p "$PATCH"
  echo "added key ${INDEX} (${KEY_ID}) to Secret ${SECRET}; deactivate the previous key and restart the pods"
else
  # shellcheck disable=SC2086
  kubectl $NS_ARG create secret generic "$SECRET" \
    --from-literal="${P}_ID=$KEY_ID" \
    --from-literal="${P}_PRIVATE_KEY_PKCS8_BASE64=$PRIV" \
    --from-literal="${P}_PUBLIC_KEY_X509_BASE64=$PUB" \
    --from-literal="${P}_ACTIVE=$ACTIVE"
  echo "created Secret ${SECRET} with key ${INDEX} (${KEY_ID}); set signing.existingSecret=${SECRET}"
fi
