#!/bin/sh

set -eu

if [ -z "${OTEL_EXPORTER_OTLP_ENDPOINT:-}" ] && [ -z "${OTEL_TRACES_EXPORTER:-}" ]; then
    export OTEL_TRACES_EXPORTER=none
fi

export OTEL_SERVICE_NAME="${OTEL_SERVICE_NAME:-shop-worker}"
export OTEL_LOGS_EXPORTER="${OTEL_LOGS_EXPORTER:-none}"
export OTEL_METRICS_EXPORTER="${OTEL_METRICS_EXPORTER:-none}"

exec java -javaagent:/app/opentelemetry-javaagent.jar -jar /app/worker.jar

