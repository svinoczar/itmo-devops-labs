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

### Сборка контейнера

```bash
docker build -t lab2-api:local lab2/api
docker run --rm -p 8080:8080 lab2-api:local
```

## Часть 1. Метрики: Prometheus и Grafana

В `kind` через Helm развёрнуты API и `kube-prometheus-stack`. `ServiceMonitor` подключает `/metrics` к Prometheus, а в Grafana собран RED-дашборд с RPS, долей ошибок и p95 времени ответа; его работа проверена вызовами `/load`, `/fail` и `/slow`.

Для локального Kubernetes используется `kind`: он запускает ноды как Docker-контейнеры и позволяет загрузить уже собранный образ без внешнего registry. Понадобятся `kind`, `kubectl` и `helm`.

### Кластер и образ сервиса

```bash
kind create cluster --name lab2
kind load docker-image lab2-api:local --name lab2
kubectl cluster-info --context kind-lab2
```

Загрузка через `kind load` помещает образ в container runtime нод. В Helm chart сервиса используется `image: lab2-api:local` и `imagePullPolicy: IfNotPresent`, иначе Kubernetes попытается скачать локальный образ из внешнего registry.

### Prometheus

Prometheus и Grafana устанавливаются chart-ом `kube-prometheus-stack`. Вместе с ними устанавливается Prometheus Operator и CRD `ServiceMonitor`.

```bash
helm repo add prometheus-community https://prometheus-community.github.io/helm-charts
helm repo update

helm upgrade --install monitoring prometheus-community/kube-prometheus-stack \
  --namespace monitoring \
  --create-namespace

kubectl get pods -n monitoring
```

После готовности стека устанавливается собственный Helm chart `api`:

```bash
helm upgrade --install api ./lab2/helm/api --namespace monitoring
```

Chart сервиса создаёт `Deployment`, `Service` и `ServiceMonitor`. Связь строится по именам и labels:

- `Service` выбирает поды по label `app: api` и публикует порт с именем `http`;
- `ServiceMonitor.spec.selector` выбирает этот `Service` по `app: api`;
- `ServiceMonitor` скрейпит `path: /metrics` через порт `http`;
- label `release: monitoring` позволяет Prometheus из Helm-релиза `monitoring` выбрать этот `ServiceMonitor`.

Минимальная существенная часть `ServiceMonitor`:

```yaml
apiVersion: monitoring.coreos.com/v1
kind: ServiceMonitor
metadata:
  name: api
  labels:
    release: monitoring
spec:
  selector:
    matchLabels:
      app: api
  endpoints:
    - port: http
      path: /metrics
      interval: 15s
```

Проверка target в Prometheus:

```bash
kubectl port-forward -n monitoring \
  svc/monitoring-kube-prometheus-prometheus 9090:9090
```

После этого в `http://localhost:9090/targets` target `api` должен иметь состояние `UP`. Дополнительная проверка запросом PromQL: `up{service="api"}`.

### Grafana и RED-дашборд

`kube-prometheus-stack` уже добавляет установленный Prometheus в Grafana как datasource. Пароль администратора хранится в Kubernetes Secret:

```bash
kubectl get secret -n monitoring monitoring-grafana \
  -o jsonpath='{.data.admin-password}' | base64 --decode; echo

kubectl port-forward -n monitoring svc/monitoring-grafana 3000:80
```

Grafana открывается на `http://localhost:3000`, пользователь — `admin`. На новом dashboard создаются три панели:

```promql
# RPS
sum(rate(api_http_requests_total[1m]))

# доля ответов 5xx, %
100 * sum(rate(api_http_request_errors_total[5m]))
  / sum(rate(api_http_requests_total[5m]))

# p95 времени ответа, секунды
histogram_quantile(
  0.95,
  sum by (le) (rate(api_http_request_duration_seconds_bucket[5m]))
)
```

Для проверки API пробрасывается наружу, после чего создаётся нагрузка:

```bash
kubectl port-forward -n monitoring svc/api 8080:8080

curl 'http://localhost:8080/load?requests=100'
for i in $(seq 1 20); do curl -s http://localhost:8080/fail >/dev/null; done
for i in $(seq 1 10); do curl -s http://localhost:8080/slow >/dev/null & done; wait
```

После двух-трёх интервалов scrape на графиках должны быть видны всплеск RPS, рост error ratio и p95. Результат фиксируется скриншотом RED-дашборда.

## Часть 2. Логи: Loki и Alloy

Для хранения логов развёрнут Loki в monolithic-режиме: все его роли выполняет один экземпляр, а данные сохраняются в filesystem-хранилище на PVC размером 5 GiB. Такая схема подходит для однодового учебного кластера, но в production вместо локального диска обычно используется общее объектное хранилище.

Loki сам не забирает логи приложений, поэтому отдельно установлен Grafana Alloy в виде DaemonSet. На каждой ноде Alloy обнаруживает Pod API, читает его stdout через Kubernetes API, разбирает JSON и отправляет записи через `loki-gateway`:

```text
stdout API → Alloy → Loki gateway → Loki → Grafana
```

Компоненты установлены через Helm с сохранёнными в репозитории values-файлами:

```bash
helm upgrade --install loki grafana-community/loki --version 18.13.5 \
  --namespace monitoring \
  --values ./lab2/helm/monitoring/loki-values.yaml

helm upgrade --install alloy grafana/alloy --version 1.12.1 \
  --namespace monitoring \
  --values ./lab2/helm/monitoring/alloy-values.yaml
```

Loki добавлен в существующую Grafana как дополнительный datasource через `kube-prometheus-stack-values.yaml`. Для поиска всех логов API используется LogQL:

```logql
{namespace="monitoring", app="api"}
```

Только записи уровня `ERROR` выбираются по низкокардинальному label `level`:

```logql
{namespace="monitoring", app="api", level="ERROR"}
```

`trace_id` не добавляется в labels Loki из-за высокой кардинальности. Он остаётся полем JSON и извлекается во время запроса:

```logql
{namespace="monitoring", app="api"} | json | trace_id="<trace_id>"
```

Работоспособность проверена вызовом `/fail`: в Grafana найдена структурированная запись уровня `ERROR` с полями `route=/fail`, `status_code=500`, `trace_id` и `span_id`.

## Часть 3. Трейсы: OpenTelemetry и Jaeger

Для приёма и просмотра трейсов через Helm развёрнут Jaeger v2 в режиме all-in-one. Collector, memory storage и Query UI работают в одном Pod; это упрощает лабораторную установку, но при перезапуске Jaeger сохранённые трейсы теряются.

```bash
helm upgrade --install jaeger jaegertracing/jaeger --version 4.14.0 \
  --namespace monitoring \
  --values ./lab2/helm/monitoring/jaeger-values.yaml
```

API инструментирован OpenTelemetry Java Agent. Автоинструментация создаёт серверный span для каждого HTTP-запроса, `/slow` дополнительно создаёт вложенный span `slow-op`, а `/fail` помечает активный span статусом `ERROR`. Экспорт настроен через переменные окружения Helm chart сервиса:

```yaml
env:
  - name: OTEL_SERVICE_NAME
    value: api
  - name: OTEL_EXPORTER_OTLP_ENDPOINT
    value: http://jaeger:4318
  - name: OTEL_EXPORTER_OTLP_PROTOCOL
    value: http/protobuf
  - name: OTEL_TRACES_SAMPLER
    value: always_on
```

Трейсы передаются по следующему пути:

```text
API + OpenTelemetry Java Agent → OTLP/HTTP → Jaeger Collector → memory storage → Jaeger UI
```

Для доступа к интерфейсу используется port-forward:

```bash
kubectl port-forward -n monitoring svc/jaeger 16686:16686
```

Проверка выполнена запросами к `/slow` и `/fail`. В waterfall `/slow` виден корневой `GET /slow` и вложенный `slow-op` длительностью 1–3 секунды. Span запроса `/fail` содержит `http.status_code=500`, `error=true` и статус OpenTelemetry `ERROR`, поэтому Jaeger подсвечивает его красным.

Связь логов и трейсов проверена отдельно: полный `trace_id` записи `/fail` скопирован из Loki и найден через `Lookup by Trace ID` в Jaeger. Таким образом, одна операция прослеживается от структурированного лога в Grafana до соответствующего waterfall в Jaeger.

## Часть 4. Алертинг: Prometheus, Alertmanager и Karma

В Helm chart API добавлен объект `PrometheusRule` с label `release: monitoring`, по которому Prometheus Operator подключает правила к экземпляру Prometheus из `kube-prometheus-stack`. Настроены три алерта:

- `ApiUnavailable` — target API недоступен или полностью исчез из service discovery;
- `ApiHighErrorRate` — доля ошибок превышает 20%;
- `ApiHighP95Latency` — p95 времени ответа превышает одну секунду.

Каждое условие должно выполняться непрерывно две минуты (`for: 2m`). До истечения этого времени алерт находится в состоянии `pending`, после — в `firing`. Такая задержка не позволяет кратковременному сбою сразу породить уведомление.

Недоступность API проверяется двумя вариантами отказа:

```promql
(up{job="api"} == 0)
or absent(up{job="api"})
```

Первая ветка срабатывает, когда target найден, но scrape завершается ошибкой. `absent()` покрывает случай, когда после удаления всех Pod у Service не осталось endpoints и временной ряд `up` полностью исчез.

Alertmanager получает сработавшие алерты от Prometheus, группирует их по `alertname` и `service`, а критические алерты API отправляет в тестовый webhook `alert-receiver`. Параметр `send_resolved: true` включает отдельное уведомление после устранения проблемы:

```text
PrometheusRule → Prometheus → Alertmanager → webhook
                                  ↓
                                Karma
```

Для просмотра и фильтрации алертов установлен Karma. Он подключается непосредственно к API Alertmanager в режиме `read-only`, поэтому отображает группы, labels и состояния алертов, но не изменяет silences.

```bash
helm upgrade --install karma ./lab2/helm/karma --namespace monitoring
kubectl port-forward -n monitoring svc/karma 8081:8080
```

Проверка недоступности выполнена масштабированием API до нуля:

```bash
kubectl scale deployment/api -n monitoring --replicas=0
```

После двух минут `ApiUnavailable` перешёл в `firing`, появился в Alertmanager и Karma, а webhook получил уведомление. После восстановления API алерт перешёл в `resolved`:

```bash
kubectl scale deployment/api -n monitoring --replicas=1
kubectl rollout status deployment/api -n monitoring
```
