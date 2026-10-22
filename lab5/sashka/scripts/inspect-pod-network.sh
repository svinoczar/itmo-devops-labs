#!/bin/bash
set -euo pipefail

readonly NAMESPACE="${NAMESPACE:-shop}"
readonly CHECK_CONNECTIVITY="${CHECK_CONNECTIVITY:-true}"

api_pod=$(kubectl get pods -n "${NAMESPACE}" \
  -l app.kubernetes.io/name=api \
  -o json | jq -r '
    .items[]
    | select(.status.phase == "Running")
    | select(any(.status.conditions[]?; .type == "Ready" and .status == "True"))
    | .metadata.name' | head -1)

if [[ -z "${api_pod}" ]]; then
  echo "No Ready API Pod found in namespace ${NAMESPACE}" >&2
  exit 1
fi
api_node=$(kubectl get pod -n "${NAMESPACE}" "${api_pod}" -o jsonpath='{.spec.nodeName}')
api_ip=$(kubectl get pod -n "${NAMESPACE}" "${api_pod}" -o jsonpath='{.status.podIP}')
iflink=$(kubectl exec -n "${NAMESPACE}" "${api_pod}" -- cat /sys/class/net/eth0/iflink)

echo "API Pod: ${api_pod}"
echo "Pod IP:  ${api_ip}"
echo "Node:    ${api_node}"
echo "eth0 from inside the Pod:"
kubectl exec -n "${NAMESPACE}" "${api_pod}" -- sh -c '
  printf "ifindex="; cat /sys/class/net/eth0/ifindex
  printf "iflink=";  cat /sys/class/net/eth0/iflink
  printf "address="; cat /sys/class/net/eth0/address
  printf "mtu=";     cat /sys/class/net/eth0/mtu
'

echo "Peer interface on ${api_node}:"
docker exec "${api_node}" ip -o link show | awk -F': ' -v link_index="${iflink}" '$1 == link_index {print}'

if [[ "${CHECK_CONNECTIVITY}" != "true" ]]; then
  echo "Cross-node connectivity check skipped (zero-trust policies are already enabled)."
  exit 0
fi

worker_data=$(kubectl get pods -n "${NAMESPACE}" \
  -l app.kubernetes.io/name=worker \
  -o json | jq -r '
    .items[]
    | select(.status.phase == "Running")
    | select(any(.status.conditions[]?; .type == "Ready" and .status == "True"))
    | [.metadata.name, .spec.nodeName, .status.podIP]
    | @tsv' | awk -F '\t' -v api_node="${api_node}" '$2 != api_node {print; exit}')

if [[ -z "${worker_data}" ]]; then
  echo "No worker Pod on a node different from ${api_node}" >&2
  exit 1
fi

IFS=$'\t' read -r worker_pod worker_node worker_ip <<<"${worker_data}"
echo "Direct cross-node check: ${api_pod}@${api_node} -> ${worker_pod}@${worker_node} (${worker_ip}:8081)"
kubectl exec -n "${NAMESPACE}" "${api_pod}" -- \
  curl --fail --silent --show-error --connect-timeout 2 --max-time 5 \
    "http://${worker_ip}:8081/health"
echo
