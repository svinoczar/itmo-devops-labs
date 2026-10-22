#!/bin/bash
set -euo pipefail

readonly CLUSTER_NAME="lab5"
readonly CILIUM_VERSION="1.20.3"
readonly CNPG_VERSION="0.29.1"

SCRIPT_DIR=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
ROOT_DIR=$(cd -- "${SCRIPT_DIR}/.." && pwd)

if kind get clusters | grep -Fxq "${CLUSTER_NAME}"; then
  echo "kind cluster ${CLUSTER_NAME} already exists" >&2
  exit 1
fi

kind create cluster \
  --name "${CLUSTER_NAME}" \
  --config "${ROOT_DIR}/kind-config.yaml"

helm repo add cilium https://helm.cilium.io/ --force-update
helm repo add cnpg https://cloudnative-pg.github.io/charts --force-update
helm repo update

helm upgrade --install cilium cilium/cilium \
  --version "${CILIUM_VERSION}" \
  --namespace kube-system \
  --values "${ROOT_DIR}/helm/cilium-values.yaml" \
  --set hubble.metrics.serviceMonitor.enabled=false \
  --set prometheus.serviceMonitor.enabled=false \
  --set operator.prometheus.serviceMonitor.enabled=false \
  --wait \
  --timeout 15m

kubectl wait node --all --for=condition=Ready --timeout=10m

helm upgrade --install cnpg cnpg/cloudnative-pg \
  --version "${CNPG_VERSION}" \
  --namespace cnpg-system \
  --create-namespace \
  --wait \
  --timeout 10m

docker build -t registry.local/shop/api:1.0.1 "${ROOT_DIR}/api"
docker build -t registry.local/shop/worker:1.0.0 "${ROOT_DIR}/worker"
kind load docker-image registry.local/shop/api:1.0.1 --name "${CLUSTER_NAME}"
kind load docker-image registry.local/shop/worker:1.0.0 --name "${CLUSTER_NAME}"

helm upgrade --install shop "${ROOT_DIR}/helm/shop" \
  --namespace shop \
  --create-namespace \
  --set monitoring.enabled=false \
  --wait \
  --timeout 15m

kubectl wait --for=condition=Ready cluster/shop-postgres -n shop --timeout=10m
kubectl get nodes -o wide
kubectl get pods -n shop -o wide
echo "NodePort: http://127.0.0.1:30080/health"
