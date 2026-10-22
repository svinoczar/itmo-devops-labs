#!/bin/bash
set -euo pipefail

SCRIPT_DIR=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
ROOT_DIR=$(cd -- "${SCRIPT_DIR}/.." && pwd)
NAMESPACE=${NAMESPACE:-shop}

if [[ "$(kubectl get pod outsider -n "${NAMESPACE}" \
  -o jsonpath='{.status.phase}' 2>/dev/null || true)" != "Running" ]]; then
  kubectl delete pod outsider -n "${NAMESPACE}" \
    --ignore-not-found --wait=true >/dev/null
  kubectl apply -n "${NAMESPACE}" \
    -f "${ROOT_DIR}/tests/network-test-pods.yaml" >/dev/null
fi
kubectl wait -n "${NAMESPACE}" --for=condition=Ready pod/outsider --timeout=2m >/dev/null

TARGET_IP=$(kubectl get pod -n "${NAMESPACE}" \
  -l app.kubernetes.io/name=worker -o json | jq -r '
    .items[]
    | select(.status.phase == "Running" and .status.podIP != null)
    | .status.podIP' | head -1)

if [[ -z "${TARGET_IP}" ]]; then
  echo "No running worker Pod found" >&2
  exit 1
fi

kubectl exec -n "${NAMESPACE}" outsider -- env TARGET_IP="${TARGET_IP}" bash -c '
  for _ in $(seq 1 50); do
    for _ in $(seq 1 5); do
      timeout 1 bash -c "</dev/tcp/${TARGET_IP}/8081" >/dev/null 2>&1 &
    done
    wait || true
    sleep 1
  done
'
