# Лаба 3 — Собери платформу для shop

Создаем структуру папок нашего проекта:
```
lab3/
├── README.md                    
├── .gitignore                   
│
├── api/                         HTTP-сервис (FastAPI)
│   ├── main.py                  эндпоинты /health, /order, /orders
│   ├── requirements.txt         зависимости Python
│   └── Dockerfile               сборка образа api
│
├── worker/                      фоновый обработчик заказов
│   ├── main.py                 
│   ├── requirements.txt        
│   └── Dockerfile               
│
├── policies/                    ClusterPolicy Kyverno
│   └── *.yaml                   5 правил для кластера
│
├── bad-manifests/               плохие манифесты для проверки политик
│   └── *.yaml                   5 манифестов, нарушающих правила
│
└── shop-chart/                  Helm-чарт
    ├── Chart.yaml               метаданные чарта
    ├── values.yaml              параметры
    └── templates/
        ├── api-deployment.yaml
        ├── api-service.yaml
        ├── worker-deployment.yaml
        ├── postgres-cluster.yaml   CRD CloudNativePG 
        ├── servicemonitor.yaml    
        └── prometheusrule.yaml     3 алерта 
```

## Часть 0 — Сервисы
### Пишем серевер api:<br>
def get_db() - функция подключения к БД <br>
def init_db() - функция создания таблицы товаров, при ее отсутствии <br>
def on_startup() - функция инициализации работы БД <br>
def create_order(order: OrderIn) - функция создания товара и возврата id <br>
def list_orders() - функция возврата последних 100 заказов в валидном формате <br>
<br>
Проверяем:<br>
1. Поднимаем БД в докере
```
docker run -d --name pg-shop \
  -e POSTGRES_USER=shop -e POSTGRES_PASSWORD=shop -e POSTGRES_DB=shop \
  -p 5432:5432 postgres:16
```
2. Запускаем апи
```
cd ~/itmo-devops-labs/lab3/api
python3 -m venv .venv && source .venv/bin/activate
pip install -r requirements.txt
uvicorn main:app --reload
```
ответ
```
INFO:     Will watch for changes in these directories: ['/home/maria/itmo-devops-labs/lab3/api']
INFO:     Uvicorn running on http://127.0.0.1:8000 (Press CTRL+C to quit)
INFO:     Started reloader process [65099] using WatchFiles
INFO:     Started server process [65102]
INFO:     Waiting for application startup.
INFO:     Application startup complete.
```
3. Во втором терминале дегаем эндпоинты
```
curl localhost:8000/health
curl -X POST localhost:8000/order -H 'Content-Type: application/json' -d '{"item":"apple"}'
curl -X POST localhost:8000/order -H 'Content-Type: application/json' -d '{"item":"banana"}'
curl localhost:8000/orders
```
ответ
```
[{"id":2,"item":"banana","status":"new","created_at":"2026-10-03T17:04:46.869905+00:00"},{"id":1,"item":"apple","status":"new","created_at":"2026-10-03T17:04:46.824437+00:00"}]
```
4. Проверяем таблицу с БД
```
docker exec pg-shop psql -U shop -d shop -c "SELECT * FROM orders;"
```
ответ
```
 id |  item  | status |          created_at           | processed_at 
----+--------+--------+-------------------------------+--------------
  1 | apple  | new    | 2026-10-03 17:04:46.824437+00 | 
  2 | banana | new    | 2026-10-03 17:04:46.869905+00 | 
(2 rows)
```

### Пишем серевер workers:<br>
def get_db() - функция подключения к БД <br>
def process_one() - обработка заказа <br>
main() — бесконечный цикл worker для постсенной обработки всех заказов <br>
Проверяем:<br>
1. Запускаем воркер
```
cd ~/itmo-devops-labs/lab3/worker
python3 -m venv .venv
source .venv/bin/activate
pip install -r requirements.txt
python main.py
```
ответ
```
Successfully installed psycopg-3.3.6 psycopg-binary-3.3.6 python-json-logger-4.2.0
{"time": "2026-10-03 20:21:42,740", "level": "INFO", "message": "worker started", "name": "worker", "poll_interval": 5}
{"time": "2026-10-03 20:21:42,765", "level": "INFO", "message": "order processed", "name": "worker", "order_id": 1, "item": "apple"}
{"time": "2026-10-03 20:21:47,792", "level": "INFO", "message": "order processed", "name": "worker", "order_id": 2, "item": "banana"}
```
2. Проверяем БД
```
docker exec pg-shop psql -U shop -d shop -c "SELECT id, item, status, processed_at FROM orders;"
```
ответ
```
 id |  item  |  status   |         processed_at          
----+--------+-----------+-------------------------------
  1 | apple  | processed | 2026-10-03 17:21:42.75536+00
  2 | banana | processed | 2026-10-03 17:21:47.780045+00
(2 rows)
```
### Оба сервера работают!


### Сборка через миникуб <br>
Загружаем:
```
 cd ~/itmo-devops-labs/lab3/api
docker build -t shop-api:0.1.0 .
minikube image load shop-api:0.1.0
```
```
cd ~/itmo-devops-labs/lab3/worker
docker build -t shop-worker:0.1.0 .
minikube image load shop-worker:0.1.0
```
Смотрим контейнеры:
```
minikube image ls | grep shop
```
ответ
```
docker.io/library/shop-worker:0.1.0
docker.io/library/shop-api:0.1.0
```

## Часть 1 — Ограждения на кластер
В качестве admission webhook выбираем Kyverno, так как язык правил привычный YAML и порог входа - низкий.
Устанавливаем:
```
helm repo add kyverno https://kyverno.github.io/kyverno/
helm repo update
helm install kyverno kyverno/kyverno -n kyverno --create-namespace
```
Смотрим поды
```
kubectl get pods -n kyverno
```
поды установлены
```
NAME                                             READY   STATUS    RESTARTS   AGE
kyverno-admission-controller-769b8f7647-qkv94    1/1     Running   0          101s
kyverno-background-controller-86d8df7447-nxzsw   1/1     Running   0          101s
kyverno-cleanup-controller-86c886ffff-qfq2h      1/1     Running   0          101s
kyverno-reports-controller-57c7978d69-4h78m      1/1     Running   0          101s

```
admission-controller - сам webhook (перехватывает kubectl apply и валидирует объекты) <br>
background-controller - проверяет уже созданные объекты на нарушения <br>
cleanup-controller - удаляет объекты по CleanupPolicy <br>
reports-controller - пишет отчеты о нарушениях <br>
<br>
Создаем namespace и проверяем создлся ли:
```
kubectl create namespace shop

kubectl get ns | grep shop
```
ответы
```
namespace/shop created

shop    Active   5s
```
### 1. Создаем правило с лимитом на ресурсы
В папке policies:
```Python
#require-resources.yaml

...
spec:
    ...
      validate:
        message: "Все контейнеры должны иметь resources.limits (cpu и memory)"
        pattern:
          spec:
            containers:
              - resources:
                  limits:
                    memory: "?*"
                    cpu: "?*"

```
Применяем политику и проверяем, что она загружена:
```
kubectl apply -f ~/itmo-devops-labs/lab3/policies/require-resources.yaml
kubectl get clusterpolicies
```
ответ
```
clusterpolicy.kyverno.io/require-resources created
NAME                ADMISSION   BACKGROUND   READY   AGE   MESSAGE
require-resources   true        true         True    50s   Ready
```
Создаем плохой манифест bad-resources.yaml в папке bad-manifests без resources и применяем его:
```
kubectl apply -f ~/itmo-devops-labs/lab3/bad-manifests/bad-resources.yaml
```
ответ
```
Error from server: error when creating "/home/maria/itmo-devops-labs/lab3/bad-manifests/bad-resources.yaml": admission webhook "validate.kyverno.svc-fail" denied the request: 

resource Pod/shop/bad-resources was blocked due to the following policies 

require-resources:
  check-containers-limits: 'validation error: Все контейнеры должны иметь resources.limits (cpu и memory). rule check-containers-limits failed at path /spec/containers/0/resources/limits/'
```
Для проверки в той же папке создадим под good-pod.yaml с заданными ресурсами и применим его:
```
kubectl apply -f ~/itmo-devops-labs/lab3/bad-manifests/good-pod.yaml
```
ответ
```
pod/good-pod created
```
убирем его:
```
kubectl delete pod -n shop good-pod
```

### 2. Создаем правило на обязательные метки
Метки - key-value в metadata.labels по которым K8s выбирает объекты.
В папке policies:
```Python
#require-labels.yaml

...
spec:
    ...
      validate:
        message: "Pod должен иметь метки 'app' и 'owner'"
        pattern:
          metadata:
            labels:
              app: "?*"
              owner: "?*"

```
Применяем политику и проверяем, что она загружена:
```
kubectl apply -f policies/require-labels.yaml
kubectl get clusterpolicies
```
ответ
```
clusterpolicy.kyverno.io/require-labels created

NAME                ADMISSION   BACKGROUND   READY   AGE   MESSAGE
require-labels      true        true         True    11s   Ready
require-resources   true        true         True    16m   Ready
```
Создаем плохой манифест bad-labels.yaml в папке bad-manifests без metadata.labels и применяем его:
```
kubectl apply -f bad-manifests/bad-labels.yaml
```
ответ
```
Error from server: error when creating "bad-manifests/bad-labels.yaml": admission webhook "validate.kyverno.svc-fail" denied the request: 

resource Pod/shop/bad-labels was blocked due to the following policies 

require-labels:
  check-labels: 'validation error: Pod должен иметь метки ''app'' и ''owner''. rule check-labels failed at path /metadata/labels/'
```
Для проверки в той же папке создадим под good-labels.yaml с метками:
```
kubectl apply -f bad-manifests/good-labels.yaml
```
ответ
```
pod/good-labels created
```
убирем его:
```
kubectl delete pod -n shop good-labels
```


### 3. Создаем правило на запрет привелегий
Ограничение на capabilities, hostPID, сеть и тд, если это не системные компоненты.
В папке policies:
```Python
#disallow-privileged.yaml

...
spec:
    ...
      validate:
        message: "Запрещены privileged контейнеры, hostNetwork, hostPID, hostPath"
        pattern:
          spec:
            =(hostNetwork): false
            =(hostPID): false
            =(volumes):
              - X(hostPath): "null"
            containers:
              - securityContext:
                  =(privileged): false
                  =(allowPrivilegeEscalation): false

```
Применяем политику и проверяем, что она загружена:
```
kubectl apply -f policies/disallow-privileged.yaml
kubectl get clusterpolicies
```
ответ
```
clusterpolicy.kyverno.io/disallow-privileged created
NAME                  ADMISSION   BACKGROUND   READY   AGE   MESSAGE
disallow-privileged   true        true         True    10s   Ready
require-labels        true        true         True    13m   Ready
require-resources     true        true         True    29m   Ready
```
Создаем плохой манифест bad-privileged.yaml в папке bad-manifests c securityContext.privileged: true и применяем его:
```
kubectl apply -f bad-manifests/bad-privileged.yaml
```
ответ
```
Error from server: error when creating "bad-manifests/bad-privileged.yaml": admission webhook "validate.kyverno.svc-fail" denied the request: 

resource Pod/shop/bad-privileged was blocked due to the following policies 

disallow-privileged:
  check-privileged: 'validation error: Запрещены privileged контейнеры, hostNetwork, hostPID, hostPath volumes. rule check-privileged failed at path /spec/containers/0/securityContext/privileged/'
```
Для проверки в той же папке создадим под good-privileged.yaml и применим его:
```
kubectl apply -f bad-manifests/good-privileged.yaml
```
ответ
```
pod/good-privileged created
```
убирем его:
```
kubectl delete pod -n shop good-privileged
```

### 4. Создаем правило на создание образов только из доверенного реестра
В папке policies:
```Python
#trusted-registry.yaml

...


```
Применяем политику и проверяем, что она загружена:
```

```
ответ
```

```
Создаем плохой манифест bad-resources.yaml в папке bad-manifests без resources и применяем его:
```

```
ответ
```

```
Для проверки в той же папке создадим под good-pod.yaml с заданными ресурсами и применим его:
```

```
ответ
```

```
убирем его:
```

```

### 5. Создаем правило
В папке policies:
```Python
#require-resources.yaml

...


```
Применяем политику и проверяем, что она загружена:
```

```
ответ
```

```
Создаем плохой манифест bad-resources.yaml в папке bad-manifests без resources и применяем его:
```

```
ответ
```

```
Для проверки в той же папке создадим под good-pod.yaml с заданными ресурсами и применим его:
```

```
ответ
```

```
убирем его:
```

```
