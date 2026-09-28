#!/bin/sh
set -eu

# The Java agent defaults to OTLP. Keep local runs quiet when no collector is configured.
if [ -z "${OTEL_EXPORTER_OTLP_ENDPOINT:-}" ] && [ -z "${OTEL_TRACES_EXPORTER:-}" ]; then
    export OTEL_TRACES_EXPORTER=none
fi

# Logs go to stdout/Loki and metrics to Prometheus, not through OTLP.
export OTEL_LOGS_EXPORTER="${OTEL_LOGS_EXPORTER:-none}"
export OTEL_METRICS_EXPORTER="${OTEL_METRICS_EXPORTER:-none}"

exec java -javaagent:/app/opentelemetry-javaagent.jar -jar /app/api.jar
