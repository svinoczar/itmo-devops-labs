# Лабораторная работа №2: observability-стек в Kubernetes

Цель работы — собрать для одного HTTP-сервиса сквозную наблюдаемость: RED-метрики и логи в Grafana, трейсы в Jaeger, алерты в Alertmanager и Karma. Весь инфраструктурный стек будет развёрнут в Kubernetes через Helm.

Работа разделена на пять этапов:

1. подготовка подопытного сервиса;
2. метрики: Prometheus и RED-дашборд Grafana;
3. логи: Loki, агент-сборщик и Grafana;
4. трейсы: OpenTelemetry и Jaeger;
5. алертинг: правила Prometheus, Alertmanager и Karma.

## Часть 0. Подопытный сервис

Сервис написан на Java 21 со Spring Boot. Его прикладная логика намеренно минимальна: в этой работе исследуется инфраструктура наблюдаемости, а не реализация API.

Эндпоинты:

- `GET /health` — проверка доступности;
- `GET /fail` — ответ `500` и тестовая запись уровня `ERROR`;
- `GET /slow` — задержка на случайное время от одной до трёх секунд;
- `GET /load?requests=50` — параллельные запросы сервиса к собственному `/health`;
- `GET /metrics` — метрики в формате Prometheus.

RED-сигналы представлены следующими метриками:

- `api_http_requests_total` — число запросов (`Rate`);
- `api_http_request_errors_total` — число ответов 5xx (`Errors`);
- `api_http_request_duration_seconds` — гистограмма длительности (`Duration`).

Spring Boot автоматически инструментирован OpenTelemetry Java Agent. В `/slow` через OpenTelemetry API создаётся дочерний спан `slow-op`, а активный серверный спан `/fail` получает статус `ERROR`. Logback пишет структурированные JSON-логи; `trace_id` и `span_id` активного спана добавляются через MDC, поэтому лог можно сопоставить с трейсом.

### Локальный запуск

```bash
cd lab2/api
mvn package
curl --fail --location \
  https://repo.maven.apache.org/maven2/io/opentelemetry/javaagent/opentelemetry-javaagent/2.20.1/opentelemetry-javaagent-2.20.1.jar \
  --output target/opentelemetry-javaagent.jar
java -javaagent:target/opentelemetry-javaagent.jar \
  -Dotel.traces.exporter=none \
  -Dotel.logs.exporter=none \
  -Dotel.metrics.exporter=none \
  -jar target/api-1.0.0.jar
```

Эти флаги отключают OTLP-экспорт при локальной проверке. В контейнере это делается автоматически: метрики идут через `/metrics`, логи — через `stdout`, поэтому OTLP нужен только трейсингу. Позднее Helm values передаст сервису `OTEL_EXPORTER_OTLP_ENDPOINT` с адресом OTLP-приёмника Jaeger.

Проверка:

```bash
curl http://localhost:8080/health
curl http://localhost:8080/fail
curl http://localhost:8080/slow
curl 'http://localhost:8080/load?requests=50'
curl http://localhost:8080/metrics
```

### Сборка контейнера

```bash
docker build -t lab2-api:local lab2/api
docker run --rm -p 8080:8080 lab2-api:local
```
