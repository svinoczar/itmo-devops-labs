#!/bin/bash
set -euo pipefail

readonly NAMESPACE="${NAMESPACE:-shop}"
readonly SCRIPT_DIR=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
readonly ROOT_DIR=$(cd -- "${SCRIPT_DIR}/.." && pwd)
readonly ARTIFACTS_DIR="${ROOT_DIR}/artifacts"

mkdir -p "${ARTIFACTS_DIR}"
kubectl delete job continuity-client -n "${NAMESPACE}" --ignore-not-found
kubectl apply -n "${NAMESPACE}" -f "${ROOT_DIR}/tests/continuity-client.yaml"

"${SCRIPT_DIR}/chaos-pods.sh" 180 10 | tee "${ARTIFACTS_DIR}/chaos.log" &
chaos_pid=$!

set +e
kubectl wait --for=condition=Complete job/continuity-client -n "${NAMESPACE}" --timeout=5m
job_status=$?
wait "${chaos_pid}"
set -e

kubectl logs -n "${NAMESPACE}" job/continuity-client | tee "${ARTIFACTS_DIR}/continuity.csv"
"${SCRIPT_DIR}/render-continuity-svg.py" \
  "${ARTIFACTS_DIR}/continuity.csv" \
  "${ARTIFACTS_DIR}/continuity.svg"

exit "${job_status}"
