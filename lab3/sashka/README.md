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

## Часть 1. Ограждения кластера

В качестве policy engine выбран **OPA Gatekeeper**. В отличие от Kyverno, он требует знания Rego/CEL, но позволяет отделить переиспользуемую реализацию проверки (`ConstraintTemplate`) от её применения (`Constraint`) и подходит для сложных организационных политик.

Gatekeeper установлен до приложения в namespace `gatekeeper-system`. Системные namespace (`kube-system`, `gatekeeper-system`, `monitoring`, `local-path-storage`, `cnpg-system`) исключены из пользовательских ограничений, поскольку сторонние чарты не обязаны соответствовать правилам shop.

В кластере действуют пять правил:

| Правило | Назначение |
| --- | --- |
| Обязательные `app.kubernetes.io/name`, `app.kubernetes.io/part-of`, `owner` | Однозначная идентификация, группировка и принадлежность workload |
| CPU/memory `requests` и `limits` | Корректный scheduling и защита ноды от неконтролируемого потребления ресурсов |
| Разрешённые registry | Запрет запуска образов из произвольных источников |
| Запрет `securityContext.privileged: true` | Не позволяет контейнеру получить расширенный доступ к хосту |
| Обязательные readiness- и liveness-пробы | Не направляет трафик в неготовый контейнер и позволяет восстанавливать зависший процесс |

Шаблоны находятся в `policies/templates`, экземпляры правил — в `policies/constraints`. Для каждого правила подготовлен отдельный нарушающий манифест в `policies/violations`.

Проверка admission без сохранения объектов в etcd:

```bash
kubectl apply -f policies/templates
kubectl apply -f policies/constraints

for file in policies/violations/*.yaml; do
  kubectl apply --dry-run=server -f "$file"
done
```

Все пять объектов были отклонены соответствующими правилами: без меток, без ресурсов, с недоверенным образом, в privileged-режиме и без probes. Положительные варианты тех же объектов прошли server-side dry-run.

## Часть 2. Helm-чарт api и worker

Чарт `helm/shop` создаёт `Deployment` и `Service` для API и worker. Число реплик, образы, ресурсы и параметры probes вынесены в `values.yaml`. Исходное число реплик API — 3, worker — 2; при проверке масштабирования API было изменено до 4 и это значение оставлено в текущем файле.

Pod templates сразу соответствуют политикам Gatekeeper: содержат обязательные labels, requests/limits, непривилегированные контейнеры, доверенные имена образов и HTTP-пробы `/health`. Для API настроен `RollingUpdate`:

```yaml
maxUnavailable: 0
maxSurge: 1
```

Это сохраняет все доступные реплики до готовности нового Pod и создаёт не более одного дополнительного Pod.

Установка и предварительная проверка:

```bash
helm lint helm/shop
helm template shop helm/shop | kubectl apply --dry-run=server -f -
helm upgrade --install shop helm/shop \
  --namespace shop \
  --create-namespace \
  --wait \
  --timeout 10m
```

Проверка reconciliation дала следующие результаты:

- после ручного удаления Pod API ReplicaSet создал новый Pod с другим UID;
- после изменения `api.replicaCount` Deployment довёл число реплик с 3 до 4;
- при смене тега API с `1.0.0` на `1.0.1` был создан новый ReplicaSet, а Pod обновлялись постепенно;
- во время rolling update через Service выполнено 600 проверок `/health`: `ok=600 failed=0`.

Для негативного rollout было установлено `HEALTH_FAIL=true`. Новые Pod не проходили readiness, rollout завершился `Progress deadline exceeded`, но старые готовые Pod оставались за Service. За время проверки получено `ok=1200 failed=0`. Команда `helm rollback` восстановила предыдущую исправную ревизию; после этого значение в `values.yaml` также было возвращено, чтобы следующий upgrade повторно не применил сломанный конфиг.

## Часть 3. PostgreSQL через оператор

В кластер один раз установлен **CloudNativePG**, а chart shop создаёт только его namespaced-ресурс `Cluster` (`templates/postgres-cluster.yaml`). Оператор преобразует желаемое состояние из `spec` в Pod, Service, Secret и PVC, а результат reconciliation отражает в `status`.

В учебной конфигурации используется один экземпляр и PVC объёмом 1 GiB. Это проверяет автоматическое восстановление и сохранность данных, но не является высокой доступностью: для автоматического failover потребовалось бы несколько экземпляров на разных нодах.

API и worker подключаются к `shop-postgres-rw` — стабильному Service, указывающему на текущий primary. Учётные данные берутся из созданного CNPG Secret `shop-postgres-app` и не хранятся в Helm values.

Функциональная проверка:

```bash
kubectl exec -n shop deploy/shop-worker -- \
  curl --fail --silent -X POST http://shop-api:8080/order \
  -H 'Content-Type: application/json' \
  -d '{"item":"book","quantity":2}'

kubectl exec -n shop deploy/shop-worker -- \
  curl --fail --silent http://shop-api:8080/orders
```

Worker перевёл заказ из `PENDING` в `PROCESSED`. После удаления `shop-postgres-1` оператор создал новый Pod с тем же логическим именем, но новым UID; PVC остался `Bound`, а заказ сохранился.

`kube-controller-manager` содержит универсальные контроллеры Kubernetes, например Deployment/ReplicaSet, и не знает семантики PostgreSQL. CNPG — доменный оператор: он понимает CRD `Cluster` и управляет инициализацией PostgreSQL, primary/replicas, failover, резервным копированием и обновлениями.

## Часть 4. Падение control plane

В single-control-plane kind-кластере API server работает как static Pod. Для эксперимента его manifest был временно убран из каталога, наблюдаемого kubelet:

```bash
docker exec lab3-control-plane \
  mv /etc/kubernetes/manifests/kube-apiserver.yaml \
     /etc/kubernetes/kube-apiserver.yaml.disabled
```

После остановки API server команды `kubectl` завершались по timeout, а `helm upgrade` сообщал `kubernetes cluster unreachable`: менять желаемое состояние кластера было невозможно.

При этом data plane продолжил работу. Проверки выполнялись из контейнера worker-ноды напрямую через ClusterIP, без `kubectl exec` и `port-forward`:

- API и worker отвечали на `/health`;
- `GET /orders` возвращал сохранённые данные;
- новый заказ был записан в PostgreSQL и обработан worker.

Работа продолжилась потому, что уже запущенные контейнеры исполнялись kubelet, а существующая маршрутизация Service сохранялась в kube-proxy. Однако контроллеры и CNPG не могли выполнять операции, требующие API server, поэтому во время сбоя нельзя рассчитывать на создание замены для потерянного Pod.

После возврата manifest kubelet снова запустил API server:

```bash
docker exec lab3-control-plane \
  mv /etc/kubernetes/kube-apiserver.yaml.disabled \
     /etc/kubernetes/manifests/kube-apiserver.yaml

until kubectl get --raw=/readyz >/dev/null 2>&1; do sleep 2; done
kubectl get nodes
```

Обе ноды вернулись в `Ready`, а кластер снова принимал изменения.

## Часть 5. Мониторинг

Использован уже настроенный в Лабе 2 стек `kube-prometheus-stack` в namespace `monitoring`. Чарт shop не разворачивает второй Prometheus: он добавляет только два `ServiceMonitor` и один `PrometheusRule`.

`ServiceMonitor` выбирают Service API и worker по labels и обращаются к именованному порту `http` по пути `/metrics` каждые 15 секунд. Метка `release: monitoring` позволяет Prometheus Operator выбрать эти объекты. В Prometheus обнаружены и имеют состояние `up` четыре target API и два target worker.

В `PrometheusRule` определены три алерта:

| Алерт | Условие и смысл |
| --- | --- |
| `ShopApiUnavailable` | Нет ни одного `up` target API либо временной ряд отсутствует; обнаруживает полную недоступность |
| `ShopApiHighErrorRate` | Более 20% запросов за 2 минуты завершились 5xx; обнаруживает деградацию при формально работающем сервисе |
| `ShopApiHighP95Latency` | p95 времени ответа за 2 минуты выше 1 секунды; обнаруживает медленный сервис до полного отказа |

У всех правил установлен `for: 1m`, чтобы краткий всплеск не создавал инцидент.

Для проверки API был масштабирован до нуля:

```bash
kubectl scale deployment/shop-api -n shop --replicas=0
```

Prometheus зафиксировал переход `ShopApiUnavailable`:

```text
17:02:26  inactive
17:03:08  pending
17:05:21  firing
```

После восстановления релиза алерт вернулся в `inactive`:

```bash
helm upgrade shop helm/shop \
  -n shop \
  --reset-values \
  --wait \
  --timeout 10m
```

## Итог

Gatekeeper отклоняет workload, нарушающие пять кластерных правил. Helm-чарт разворачивает соответствующие политикам API, worker и CNPG Cluster; контроллеры восстанавливают удалённые Pod, rolling update не прерывает трафик, а сломанная версия не вытесняет исправную. При остановленном API server управление кластером недоступно, но существующий data plane продолжает обслуживать и обрабатывать заказы. Prometheus собирает метрики обоих сервисов и переводит проверенный алерт недоступности в `firing`.
