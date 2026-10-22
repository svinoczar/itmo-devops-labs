#!/bin/bash
set -euo pipefail

SCRIPT_DIR=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
ROOT_DIR=$(cd -- "${SCRIPT_DIR}/.." && pwd)
ARTIFACTS_DIR="${ROOT_DIR}/artifacts"

mkdir -p "${ARTIFACTS_DIR}"

echo '=== Сеть Pod: IP, eth0 и veth ==='
CHECK_CONNECTIVITY=false "${SCRIPT_DIR}/inspect-pod-network.sh" \
  | tee "${ARTIFACTS_DIR}/pod-network.txt"

echo
echo '=== Итоговая матрица доступа ==='
"${SCRIPT_DIR}/check-access-matrix.sh"

echo
echo '=== PrometheusRule ==='
kubectl get prometheusrule shop-network-alerts -n shop -o wide \
  | tee "${ARTIFACTS_DIR}/prometheus-rule.txt"

cat <<'EOF'

Текстовые доказательства обновлены в artifacts/.
EOF
