#!/bin/bash
set -euo pipefail

readonly NAMESPACE="${NAMESPACE:-shop}"
readonly DURATION_SECONDS="${1:-180}"
readonly INTERVAL_SECONDS="${2:-10}"

deadline=$((SECONDS + DURATION_SECONDS))

while ((SECONDS < deadline)); do
  pods=()
  for component in api worker; do
    mapfile -t component_pods < <(
      kubectl get pods -n "${NAMESPACE}" \
        -l "app.kubernetes.io/name=${component}" \
        -o json |
      jq -r '
        .items[]
        | select(.metadata.deletionTimestamp == null)
        | select(any(.status.conditions[]?; .type == "Ready" and .status == "True"))
        | .metadata.name
      '
    )

    if ((${#component_pods[@]} > 1)); then
      pods+=("${component_pods[@]}")
    fi
  done

  if ((${#pods[@]} == 0)); then
    echo "No component currently has more than one Ready Pod; skipping this tick" >&2
    sleep "${INTERVAL_SECONDS}"
    continue
  fi

  victim=${pods[RANDOM % ${#pods[@]}]}
  printf '%(%FT%T%z)T deleting %s\n' -1 "${victim}"
  kubectl delete pod -n "${NAMESPACE}" "${victim}" --wait=false
  sleep "${INTERVAL_SECONDS}"
done
