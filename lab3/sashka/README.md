# Лабораторная работа №3: платформа для shop

## Часть 0. Сервисы

Подопытное приложение состоит из двух Java 21/Spring Boot сервисов и PostgreSQL:

- `api` принимает и возвращает заказы;
- `worker` периодически забирает необработанные заказы и переводит их в состояние `PROCESSED`;
- PostgreSQL хранит таблицу `orders`.

API предоставляет:

- `GET /health` — `200`, либо `503` при `HEALTH_FAIL=true`;
- `POST /order` — создаёт заказ из JSON `{"item":"book","quantity":2}`;
- `GET /orders` — возвращает все заказы;
- `GET /metrics` — метрики Prometheus.

Worker обрабатывает заказы пакетами через `FOR UPDATE SKIP LOCKED`, поэтому несколько его реплик не захватывают одну строку одновременно. Его endpoints `GET /health` и `GET /metrics` предназначены для Kubernetes probes и Prometheus.

Оба сервиса пишут JSON-логи в stdout и запускаются с OpenTelemetry Java Agent. Адрес Jaeger/OTLP задаётся через `OTEL_EXPORTER_OTLP_ENDPOINT`; без него экспорт трейсов отключён. Подключение к PostgreSQL настраивается переменными `DB_HOST`, `DB_PORT`, `DB_NAME`, `DB_USER` и `DB_PASSWORD`.

Сборка образов:

```bash
docker build -t shop-api:local ./lab3/sashka/api
docker build -t shop-worker:local ./lab3/sashka/worker
```
