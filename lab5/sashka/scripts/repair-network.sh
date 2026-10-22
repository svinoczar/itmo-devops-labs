#!/bin/bash
set -euo pipefail

SCRIPT_DIR=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
ROOT_DIR=$(cd -- "${SCRIPT_DIR}/.." && pwd)

helm upgrade shop "${ROOT_DIR}/helm/shop" \
  --namespace shop \
  --set networkPolicy.fault=none \
  --wait \
  --timeout 10m

"${ROOT_DIR}/scripts/check-access-matrix.sh"
