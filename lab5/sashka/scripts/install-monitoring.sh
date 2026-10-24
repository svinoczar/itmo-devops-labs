#!/bin/bash
set -euo pipefail

readonly CILIUM_VERSION="1.20.3"
readonly MONITORING_VERSION="92.10.1"
SCRIPT_DIR=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
ROOT_DIR=$(cd -- "${SCRIPT_DIR}/.." && pwd)

helm repo add prometheus-community \
  https://prometheus-community.github.io/helm-charts --force-update
helm repo add cilium https://helm.cilium.io/ --force-update
helm repo update

helm upgrade --install monitoring prometheus-community/kube-prometheus-stack \
  --version "${MONITORING_VERSION}" \
  --namespace monitoring \
  --create-namespace \
  --values "${ROOT_DIR}/helm/monitoring-values.yaml" \
  --wait \
  --timeout 15m

helm upgrade --install cilium cilium/cilium \
  --version "${CILIUM_VERSION}" \
  --namespace kube-system \
  --values "${ROOT_DIR}/helm/cilium-values.yaml" \
  --wait \
  --timeout 15m

helm upgrade shop "${ROOT_DIR}/helm/shop" \
  --namespace shop \
  --set monitoring.enabled=true \
  --set networkPolicy.fault=none \
  --wait \
  --timeout 10m

kubectl get servicemonitor -A
kubectl get prometheusrule -n shop
