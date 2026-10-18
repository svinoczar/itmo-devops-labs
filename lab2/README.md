# Практика 2
## Step 0: Подготовка
### Устанавливаю все необходимые пакеты:
- **kind**, Kubernetes in Docker
- **kubectl**,  Kubernetes Control, CLI для управления кластером
- **helm**, Пакетный менеджер для Kubernetes

### Соберу api:
**Эндпойнты:**
- GET /health — возвращает ok
- GET /fail — возвращает ошибку 5xx и увеличивает счётчик ошибок
- GET /slow — отвечает медленно (спит 1–3 секунды)
- GET /load — делает пачку запросов к себе, чтобы подскочил RPS

Также:
- сервис отдаёт метрики Prometheus на /metrics: 
    - счётчик запросов
    - счётчик ошибок
    - гистограмму времени ответа (RED)
- записывает структурированные логи в JSON с trace_id текущего запроса
- сразу подключена OpenTelemetry для трейсов

Создаем [докерфайл](./service/Dockerfile) для сервиса.

Собираем наш образ api и прокидываем его в кубер, чтобы на основе его он потом запускал легковесные бинарники api.

```bash
docker build -t api-service:latest .
# тут много текста со сборкой образа

kind create cluster --name lab2

# чекаем, что кубер создан в отдельном контейнере 
docker ps

CONTAINER ID   IMAGE                  COMMAND                  CREATED          STATUS          PORTS                       NAMES
053116e063f0   kindest/node:v1.37.0   "/usr/local/bin/entr…"   34 seconds ago   Up 31 seconds   127.0.0.1:43727->6443/tcp   lab2-control-plane

# добавляем образ нашего api в кубер
kind load docker-image api-service:latest --name lab2

Image: "api-service:latest" with ID "sha256:ab9f0e5fc87cbc21a8a9d6a2d67ae368acd39971c51db431f0459b89f23588d0" not yet present on node "lab2-control-plane", loading...
```

### Настраиваем Helm
Описываю манифесты для [самого Helm](./api-chart/Chart.yaml), а также для [сетевой службы](./api-chart/templates/service.yaml) и [создания подов](./api-chart/templates/deployment.yaml). 

Лежат в отдельной папке [api-chart](./api-chart/) на хосте. 

И подвязываем чарт к нашему куберу:

```bash
helm upgrade --install my-api ./api-chart

Release "my-api" does not exist. Installing it now.
NAME: my-api
LAST DEPLOYED: Wed Oct  7 18:36:59 2026
NAMESPACE: default
STATUS: deployed
REVISION: 1
DESCRIPTION: Install complete
TEST SUITE: None
```

Ура, успех!

## Step 1: Метрики (Prometheus + Grafana)

Теперь нам нужно подключить Prometheus, для этого устанавливаем ```kube-prometheus-stack```.

```bash
helm repo add prometheus-community https://prometheus-community.github.io/helm-charts
helm repo update
helm upgrade --install monitoring prometheus-community/kube-prometheus-stack
```

Для работы нам не хватает только **ServiceMonitor**. Добавим его [манифест](./api-chart/templates/servicemonitor.yaml) и обновим наш helm.

```bash
helm upgrade --install my-api ./api-chart
Release "my-api" has been upgraded. Happy Helming!
NAME: my-api
LAST DEPLOYED: Wed Oct  7 19:00:23 2026
NAMESPACE: default
STATUS: deployed
REVISION: 2
DESCRIPTION: Upgrade complete
TEST SUITE: None
```

Теперь прокидываем нашу Grafana на порт, отличный от порта api. 

```bash
kubectl port-forward svc/monitoring-grafana 3000:80
```

Я не могу это - под с графаной завис, т.к. не может скачать какой-то образ =)

Пробую всякие хитрые штуки, по типу скачать нужный образ на хост и прокинуть его tar архивом в кубер.

## Step 2: Логи (Loki + Grafana)

## Step 3: Трейсы (OpenTelemetry + Jaeger)

## Step 4: Алерты (Alertmanager + Karma)
