#!/bin/bash
set -euo pipefail

SCRIPT_DIR=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
ROOT_DIR=$(cd -- "${SCRIPT_DIR}/.." && pwd)
FAULTS=(dns postgresSelector postgresPort apiSelector)
FAULT=${FAULT:-${FAULTS[RANDOM % ${#FAULTS[@]}]}}

if [[ ! " ${FAULTS[*]} " =~ " ${FAULT} " ]]; then
  echo "Unknown fault: ${FAULT}" >&2
  echo "Allowed values: ${FAULTS[*]}" >&2
  exit 1
fi

mkdir -p "${ROOT_DIR}/artifacts"
printf '%s\n' "${FAULT}" > "${ROOT_DIR}/artifacts/.last-network-fault"

helm upgrade shop "${ROOT_DIR}/helm/shop" \
  --namespace shop \
  --set networkPolicy.fault="${FAULT}" >/dev/null

kubectl rollout restart deployment/shop-api deployment/shop-worker -n shop >/dev/null
if [[ -n "${FAULT_FOR_REPORT:-}" ]]; then
  echo "Сетевая поломка ${FAULT} применена."
else
  echo "Сетевая поломка применена. Причину нужно найти через Hubble."
fi
