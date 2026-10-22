#!/bin/bash
set -euo pipefail

SCRIPT_DIR=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
ROOT_DIR=$(cd -- "${SCRIPT_DIR}/.." && pwd)
ARTIFACT="${ROOT_DIR}/artifacts/access-matrix.md"
NAMESPACE=${NAMESPACE:-shop}

kubectl delete pod network-client outsider -n "${NAMESPACE}" \
  --ignore-not-found --wait=true >/dev/null
kubectl apply -n "${NAMESPACE}" \
  -f "${ROOT_DIR}/tests/network-test-pods.yaml" >/dev/null
kubectl wait -n "${NAMESPACE}" --for=condition=Ready \
  pod/network-client pod/outsider --timeout=2m >/dev/null

ready_pod() {
  kubectl get pod -n "${NAMESPACE}" -l "app.kubernetes.io/name=$1" \
  -o json | jq -r \
    '.items[]
    | select(.status.phase == "Running")
    | select(any(.status.conditions[]?; .type == "Ready" and .status == "True"))
    | .metadata.name' | head -1
}

API_POD=$(ready_pod api)
WORKER_POD=$(ready_pod worker)

if [[ -z "${API_POD}" || -z "${WORKER_POD}" ]]; then
  echo "API or worker has no Ready Pod" >&2
  exit 1
fi

declare -a SOURCES=("api:${API_POD}" "worker:${WORKER_POD}" \
  "client:network-client" "outsider:outsider")
declare -a TARGETS=(
  'api:curl --fail --silent --connect-timeout 2 --max-time 3 http://shop-api:8080/health >/dev/null'
  'worker:curl --fail --silent --connect-timeout 2 --max-time 3 http://shop-worker:8081/health >/dev/null'
  'postgres:timeout 3 bash -c "</dev/tcp/shop-postgres-rw/5432"'
)

expected() {
  case "$1:$2" in
    api:postgres|worker:postgres|client:api) echo allow ;;
    api:api|worker:worker) echo self ;;
    *) echo deny ;;
  esac
}

mkdir -p "$(dirname -- "${ARTIFACT}")"
{
  echo '| Источник | Назначение | Ожидалось | Фактически | Результат |'
  echo '| --- | --- | --- | --- | --- |'
} > "${ARTIFACT}"

failures=0
for source in "${SOURCES[@]}"; do
  source_name=${source%%:*}
  pod=${source#*:}
  for target in "${TARGETS[@]}"; do
    target_name=${target%%:*}
    command=${target#*:}
    wanted=$(expected "${source_name}" "${target_name}")

    actual=deny
    attempts=1
    [[ "${wanted}" == "allow" ]] && attempts=5
    for ((attempt = 1; attempt <= attempts; attempt++)); do
      if kubectl exec -n "${NAMESPACE}" "${pod}" -- \
        sh -c "${command}" >/dev/null 2>&1; then
        actual=allow
        break
      fi
      ((attempt < attempts)) && sleep 2
    done

    if [[ "${wanted}" == "self" ]]; then
      result='self-traffic'
      expected_value='не проверяется'
    elif [[ "${wanted}" == "${actual}" ]]; then
      result='OK'
      expected_value="${wanted}"
    else
      result='FAIL'
      expected_value="${wanted}"
      failures=$((failures + 1))
    fi
    echo "| ${source_name} | ${target_name} | ${expected_value} | ${actual} | ${result} |" \
      >> "${ARTIFACT}"
  done
done

cat "${ARTIFACT}"
if (( failures > 0 )); then
  echo "matrix mismatches: ${failures}" >&2
  exit 1
fi
