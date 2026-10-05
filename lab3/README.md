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
В качестве admission webhook выбираем Kyverno, так как язык правил привычный YAML и порог входа - низкий.<br>
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

### 2. Создаем правило на обязательные метки <br>
Метки - key-value в metadata.labels по которым K8s выбирает объекты. <br>
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


### 3. Создаем правило на запрет привелегий <br>
Ограничение на capabilities, hostPID, сеть и тд, если это не системные компоненты. <br>
В папке policies:<br>
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

### 4. Создаем правило на создание образов только из доверенного реестра <br>
В папке policies: <br>
```Python
#trusted-registry.yaml

...
spec:
  ...
      validate:
        message: "Образы должны быть из docker.io/library/* или shop-*"
        foreach:
          - list: "request.object.spec.containers"
            pattern:
              image: "docker.io/library/* | shop-*"

```
Применяем политику и проверяем, что она загружена:
```
kubectl apply -f policies/trusted-registry.yaml
kubectl get clusterpolicies
```
ответ
```
clusterpolicy.kyverno.io/trusted-registry created
NAME                  ADMISSION   BACKGROUND   READY   AGE   MESSAGE
disallow-privileged   true        true         True    21m   Ready
require-labels        true        true         True    34m   Ready
require-resources     true        true         True    50m   Ready
trusted-registry      true        true         True    20s   Ready
```
Создаем плохой манифест bad-registry.yaml в папке bad-manifests образом не из docker.io/library/* и применяем его:
```
kubectl apply -f bad-manifests/bad-registry.yaml
```
ответ
```
Error from server: error when creating "bad-manifests/bad-registry.yaml": admission webhook "validate.kyverno.svc-fail" denied the request: 

resource Pod/shop/bad-registry was blocked due to the following policies 

trusted-registry:
  check-registry: 'validation failure: validation error: Образы должны быть из docker.io/library/* или shop-*. rule check-registry failed at path /image/'

```
Для проверки в той же папке создадим под good-registry.yaml и применим его:
```
kubectl apply -f bad-manifests/good-registry.yaml
```
ответ
```
pod/good-registry created
```
убирем его:
```
kubectl delete pod -n shop good-registry
```

### 5. Создаем правило на шапрет тега :latest <br>
Защита от нестабильной версии <br>
В папке policies: <br>
```Python
#rdisallow-latest-tag.yaml

...
spec:
     ...
      validate:
        message: "Запрещён тег :latest и отсутствие тега — указывайте конкретную версию"
        foreach:
          - list: "request.object.spec.containers"
            deny:
              conditions:
                any:
                  - key: "{{ images.containers.{{ element.name }}.tag }}"
                    operator: AnyIn
                    value: ["", "latest"]

```
Применяем политику и проверяем, что она загружена:
```
kubectl apply -f policies/disallow-latest-tag.yaml
kubectl get clusterpolicies
```
ответ
```
clusterpolicy.kyverno.io/disallow-latest-tag created
NAME                  ADMISSION   BACKGROUND   READY   AGE   MESSAGE
disallow-latest-tag   true        true         True    0s    Ready
disallow-privileged   true        true         True    12h   Ready
require-labels        true        true         True    12h   Ready
require-resources     true        true         True    12h   Ready
trusted-registry      true        true         True    12h   Ready
```
Создаем плохой манифест bad-latest.yaml в папке bad-manifests с образом с тегом latest и применяем его:
```
kubectl apply -f bad-manifests/bad-latest.yaml
```
ответ
```
Error from server: error when creating "bad-manifests/bad-latest.yaml": admission webhook "validate.kyverno.svc-fail" denied the request: 

resource Pod/shop/bad-latest was blocked due to the following policies 

disallow-latest-tag:
  check-image-tag: 'validation failure: Запрещён тег :latest и отсутствие тега — указывайте конкретную версию'
```
Для проверки в той же папке создадим под good-tag.yaml с заданными ресурсами и применим его:
```
kubectl apply -f bad-manifests/good-tag.yaml
```
ответ
```
pod/good-tag created
```
убирем его:
```
kubectl delete pod -n shop good-tag
```

## Часть 2 — Чарт api и worker <br> <br>
### 1. Создаем чарты <br>
Заполняем файлы папки shop-chart: 
```
shop-chart/
├── Chart.yaml
├── values.yaml
└── templates/
    ├── _helpers.tpl
    ├── api-deployment.yaml
    ├── api-service.yaml
    └── worker-deployment.yaml
```
В **Сhart.yaml** прописываем метаданные: версию апи, имя чарта, тип, версию чарта, верчию приложение. <br>
В **values.yaml** прописываем параметры для api и worker: количество реплик, ресурсы (лимиты) цпу и памяти, переменные окружения (как в сервере).
<br>
В **api-deployment.yaml** и **worker-deployment.yaml** прописываем: порты (только у апи), переменные окружения и ссылки на данные из values.
<br>
В **api-service.yaml** прописываем порт (selector: app: api - направляет трафик на поды с меткой api)
<br>
Собираем шаблоны:
```
helm template shop ./shop-chart --namespace shop | less
```
очень большой вывод, на котором видим, что Helm отрендерил три манифеста на нащих шаблонах.<br>
<br>
Так как сервера при запуске будут посылать запросы на postgres.shop.svc:5432, а у нас такого сервиса в кластере нет - его надо поставить. Ставим мини-деплоймент с Postgres.<br>
В той же папке создаем **postgres.yaml**.<br>
Устанавливаем:
```
helm install shop ./shop-chart -n shop --create-namespace
```
создался под:
```
maria@ubuntu-dev:~/itmo-devops-labs/lab3$ cd ~/itmo-devops-labs/lab3
helm install shop ./shop-chart -n shop
NAME: shop
LAST DEPLOYED: Sun Oct  4 22:00:26 2026
NAMESPACE: shop
STATUS: deployed
REVISION: 1
```
проверяем поды:
```
kubectl get pods -n shop
```
поды установлены 3 апи и 2 воркер и 1 субд:
```
NAME                       READY   STATUS    RESTARTS      AGE
api-f6866b5d9-fhjr2        1/1     Running   1 (30s ago)   33s
api-f6866b5d9-hckcv        1/1     Running   1 (30s ago)   33s
api-f6866b5d9-tqt6m        1/1     Running   1 (30s ago)   33s
postgres-b5d6fc86b-pgbk9   1/1     Running   0             33s
worker-5966f547dd-dgxjl    1/1     Running   0             33s
worker-5966f547dd-lwrkd    1/1     Running   0             33s
```
Проверяем функционал, запускаем сервер и делаем запросы:
```
kubectl port-forward -n shop svc/api 8000:80
```
ответы:
```
curl localhost:8000/health
{"status":"ok"}

curl -X POST localhost:8000/order -H 'Content-Type: application/json' -d '{"item":"apple"}'item":"apple"}'
{"id":1,"status":"created"}

curl -X POST localhost:8000/order -H 'Content-Type: application/json' -d '{"item":"banana"}'son' -d '{"item":"banana"}'
{"id":2,"status":"created"}

curl localhost:8000/orders
[{"id":2,"item":"banana","status":"processed","created_at":"2026-10-04T19:05:06.710439+00:00"},{"id":1,"item":"apple","status":"processed","created_at":"2026-10-04T19:04:59.251457+00:00"}]
```
смотрим обработал ли воркер:
```
curl localhost:8000/orders
[{"id":2,"item":"banana","status":"processed","created_at":"2026-10-04T19:05:06.710439+00:00"},{"id":1,"item":"apple","status":"processed","created_at":"2026-10-04T19:04:59.251457+00:00"}
```
все работает, статусы processed.
Проверяем логи:
```
kubectl logs -n shop -l app=api --tail=200 | grep "order created"
```
ответ 
```
{"time": "2026-10-04 19:04:59,265", "level": "INFO", "message": "order created", "name": "api", "order_id": 1, "item": "apple"}
{"time": "2026-10-04 19:05:06,721", "level": "INFO", "message": "order created", "name": "api", "order_id": 2, "item": "banana"}
```
api успешно создал два заказа и залогировал их в JSON с полями. <br>
<br>
### 2. Reconciliation <br>
Удаляем под:
```
kubectl delete pod -n shop api-f6866b5d9-fhjr2
```
смотрим поды:
```
NAME                  READY   STATUS    RESTARTS      AGE
api-f6866b5d9-7jqgq   1/1     Running   0             9s
api-f6866b5d9-hckcv   1/1     Running   1 (24m ago)   24m
api-f6866b5d9-tqt6m   1/1     Running   1 (24m ago)   24m
```
ReplicaSet тут же создал новый.<br>
Меняем replicas в values.yaml на 5, применяем:
```
helm upgrade shop ./shop-chart -n shop
```
видим новый релиз после изменнеий:
```
Release "shop" has been upgraded. Happy Helming!
NAME: shop
LAST DEPLOYED: Sun Oct  4 22:28:33 2026
NAMESPACE: shop
STATUS: deployed
REVISION: 2
```
смотрим поды:
```
kubectl get pods -n shop -l app=api
```
```
NAME                  READY   STATUS    RESTARTS      AGE
api-f6866b5d9-7jqgq   1/1     Running   0             4m27s
api-f6866b5d9-cfjvf   1/1     Running   0             56s
api-f6866b5d9-hckcv   1/1     Running   1 (29m ago)   29m
api-f6866b5d9-jt7q8   1/1     Running   0             56s
api-f6866b5d9-tqt6m   1/1     Running   1 (29m ago)   29m
```
стало 5 штукав)<br>
<br>
### 3. Rolling update <br>
В yaml мы прописали:
```
strategy:
  type: RollingUpdate
  rollingUpdate:
    maxUnavailable: 0
    maxSurge: 1
```
чтобы создавался на 1 под больше, чем replicas, и ни один под не был удален, пока все новые не пройдут readiness.Это позволяет избежать поподания на неготовый под. <br>
Для проверки в api-deployment.yaml добавляем в переменные окружения:
```
- name: NEW_VERSION
  value: {{ .Values.api.env.NEW_VERSION | default "v1" | quote }}
```
При helm upgrade получим новую версию. <br>
Добавляем NEW_VERSION в переменные окружения апи в values.yaml и рендерим:
```
helm template shop ./shop-chart -n shop | grep -A2 NEW_VERSION
```
корректно:
```
- name: NEW_VERSION
  value: "v1"
readinessProbe:
```
Делаем Rolling update. <br>
1 терминал:
```
kubectl port-forward -n shop svc/api 8000:80
```
цикл на /health во 2 терминале:
```
while true; do
  code=$(curl -s -o /dev/null -w "%{http_code}" localhost:8000/health)
  echo "$(date +%H:%M:%S) $code" | tee -a /tmp/health-check.log
  sleep 0.2
done
```
видим:
```
...
22:49:57 200
22:49:57 200
22:49:58 200
22:49:58 200
22:49:58 200
22:49:58 200
22:49:59 200
22:49:59 200
22:49:59 200
22:49:59 200
...
```
новую версию в терминале 3:
```
helm upgrade shop ./shop-chart -n shop
```
ответ:
```
helm upgrade shop ./shop-chart -n shop
Release "shop" has been upgraded. Happy Helming!
NAME: shop
LAST DEPLOYED: Sun Oct  4 22:50:05 2026
NAMESPACE: shop
STATUS: deployed
REVISION: 4
```
Смотрим поды:
```
kubectl get rs -n shop -l app=api
helm history shop -n shop
```
Deployment создал новый ReplicaSet 84677c665b (старый f6866b5d9 остался без подов).
```
NAME             DESIRED   CURRENT   READY   AGE
api-84677c665b   3         3         3       61s
api-f6866b5d9    0         0         0       50m
REVISION	UPDATED                 	STATUS    	CHART           	APP VERSION	DESCRIPTION     
1       	Sun Oct  4 22:00:26 2026	superseded	shop-chart-0.1.0	0.1.0      	Install complete
2       	Sun Oct  4 22:28:33 2026	superseded	shop-chart-0.1.0	0.1.0      	Upgrade complete
3       	Sun Oct  4 22:30:18 2026	superseded	shop-chart-0.1.0	0.1.0      	Upgrade complete
4       	Sun Oct  4 22:50:05 2026	deployed  	shop-chart-0.1.0	0.1.0      	Upgrade complete
```
Rolling update прошел без простоя за ~500 запросов к /health. 
<br>
### 4. Сломанный релиз <br>
maxUnavailable: 0 должно не дать убить ст арые поды, когда новые не поднимутся и оставить трафик на них
 <br>
Повторяем команды в 1 и 2 терминалах из прошлого пункта (запускам апи и цикл на /health-check.log),
терминал 3 апргейдим с меткой EALTH_FAIL=true (плохой релиз):
```
helm upgrade shop ./shop-chart -n shop --set api.env.HEALTH_FAIL=true 
```
смотрим поды:
```
kubectl get pods -n shop -l app=api
```
3 старых пода обслуживают трафик, а новый под не проходит readiness
```
NAME                   READY   STATUS    RESTARTS      AGE
api-84677c665b-85pf7   1/1     Running   0             28m
api-84677c665b-rlf9d   1/1     Running   0             28m
api-84677c665b-w6c92   1/1     Running   0             28m
api-d6966958c-ds7g8    0/1     Running   1 (30s ago)   61s
```
смотрим репоикасет:
```
kubectl get rs -n shop -l app=api
```
старые все 3 живы, новый создан, но не готов к получению трафика, 
```
NAME             DESIRED   CURRENT   READY   AGE
api-84677c665b   3         3         3       28m
api-d6966958c    1         1         0       69s
```
В это время цикл на /health-check.log еще жив и отдает 200
```
23:17:22 200
23:17:22 200
23:17:22 200
...
23:27:50 200
23:27:50 200
23:27:50 200
```
Все работает, делаем откат.<br>
Смотрим релизы:
```
helm history shop -n shop
```
ответ:
```
REVISION	UPDATED                 	STATUS    	CHART           	APP VERSION	DESCRIPTION     
1       	Sun Oct  4 22:00:26 2026	superseded	shop-chart-0.1.0	0.1.0      	Install complete
2       	Sun Oct  4 22:28:33 2026	superseded	shop-chart-0.1.0	0.1.0      	Upgrade complete
3       	Sun Oct  4 22:30:18 2026	superseded	shop-chart-0.1.0	0.1.0      	Upgrade complete
4       	Sun Oct  4 22:50:05 2026	superseded	shop-chart-0.1.0	0.1.0      	Upgrade complete
5       	Sun Oct  4 23:17:53 2026	deployed  	shop-chart-0.1.0	0.1.0      	Upgrade complete
```
5 сломан, откатываемся на 4:
```
helm rollback shop 4 -n shop
```
ответ:
```
Rollback was a success! Happy Helming!
```
смотрим поды:
```
kubectl get pods -n shop -l app=api
```
все живы:
```
NAME                   READY   STATUS    RESTARTS   AGE
api-84677c665b-85pf7   1/1     Running   0          40m
api-84677c665b-rlf9d   1/1     Running   0          40m
api-84677c665b-w6c92   1/1     Running   0          41m
```

## Часть 3 — Postgres через оператора <br> <br>
### 1.Разворачиваем оператор БД на кластере <br>
Сечас у нас Postgres - Deployment в чарте, который при удалении пода приведет к потере данных. Нет резервных копий и обновление версии нужно делать вручную.<br>
Эту проблему решает оператор (CRD (Custom Resource Definition) + контроллер). CRD — это новый тип объекта в Kubernetes. Описывается желаемое состояние БД и Контроллер (процесс) приводит реальность к желаемому (создает StatefulSet с репликами Postgres, Service для доступа, Secret с паролем, поднимает поды, обновляет версии-rolling update).<br>
Для работы выбираем CloudNativePG, так как с ним проще работать. Описываем 1 объект kind: Cluster и он сам делает репликацию, автоматические бэкапы, переключение при сбое.<br>
<br>
Устанавливаем CloudNativePG-jgthfnj:
```
helm repo add cnpg https://cloudnative-pg.github.io/charts
helm repo update

helm install cnpg cnpg/cloudnative-pg \
  --namespace cnpg-system \
  --create-namespace
kubectl get pods -n cnpg-system
```
ответ:
```
Update Complete. ⎈Happy Helming!⎈
NAME: cnpg
LAST DEPLOYED: Mon Oct  5 10:23:08 2026
NAMESPACE: cnpg-system
STATUS: deployed
REVISION: 1

NAME                                   READY   STATUS    RESTARTS   AGE
cnpg-cloudnative-pg-7b5f5d7b65-9bqzd   1/1     Running   0          60s
```
под появился и поднялся. 
<br>
Удаляем postgres.yaml и обновляем релиз на всякий:
```
helm upgrade shop ./shop-chart -n shop
Release "shop" has been upgraded. Happy Helming!
NAME: shop
LAST DEPLOYED: Mon Oct  5 10:26:13 2026
NAMESPACE: shop
STATUS: deployed
REVISION: 7
```
Cоздаем **postgres-cluster.yaml**, куда прописываем неймспейс, метки, ресурсы, секрет с логином поролем.<br>
В values в переменных окружения переименовываем на DB_HOST: postgres-rw, так как CloudNativePG создаёт сервисы <cluster>-rw, <cluster>-r, <cluster>-ro.<br>
Обновляем релиз:
```
helm upgrade shop ./shop-chart -n shop
```
```
Release "shop" has been upgraded. Happy Helming!
NAME: shop
LAST DEPLOYED: Mon Oct  5 10:43:11 2026
NAMESPACE: shop
STATUS: deployed
REVISION: 9
```
Возникли сложности, поэтому в политики добавляем:
```
      exclude:
        any:
          - resources:
              selector:
                matchLabels:
                  cnpg.io/cluster: "?*"
```
чтобы объекты с CloudNativePG не проверялись правилами (у них нет меток, образ не из указанного репозитория).
<br>
Если често, я вообще не выкупила че происходит и почему все падает, но дипсик помог, а я в это время просто: <br>
<img width="1080" height="1080" alt="изображение" src="https://github.com/user-attachments/assets/3c7f5919-dd0c-46ed-87b0-2f7d45e21bb2" />


Cluster создан, апи поднялся:
```
NAME                      READY   STATUS    RESTARTS       AGE 
api-676898b8-lwjdw        1/1     Running   0              5m12s  - новый
api-676898b8-nxxrp        1/1     Running   20 (10m ago)   83m   - новый с рестартами
api-676898b8-qfzgp        1/1     Running   0              5m6s  - новый
postgres-1                1/1     Running   0              6m30s  - создан оператором
worker-5966f547dd-dgxjl   1/1     Running   0              13h
worker-5966f547dd-lwrkd   1/1     Running   0              13h
```

Проверяем на работоспособность:
```
1 терминал
kubectl port-forward -n shop svc/api 8000:80

2 терминал
curl localhost:8000/health
curl -X POST localhost:8000/order -H 'Content-Type: application/json' -d '{"item":"cherry"}'
curl -X POST localhost:8000/order -H 'Content-Type: application/json' -d '{"item":"date"}'
sleep 6
curl localhost:8000/orders
```
ответ:
```
[{"id":2,"item":"date","status":"new","created_at":"2026-10-05T08:55:03.250317+00:00"},{"id":1,"item":"cherry","status":"new","created_at":"2026-10-05T08:55:03.158824+00:00"}]
```
работает!
<br>
### 2.Удаляем под СУБД <br>
Удаляем:
```
kubectl delete pod -n shop postgres-1
```
через 11 секунд под уже готов:
```
NAME         READY   STATUS    RESTARTS   AGE
postgres-1   0/1     Running   0          3s
postgres-1   0/1     Running   0          4s
postgres-1   0/1     Running   0          4s
postgres-1   0/1     Running   0          11s
postgres-1   1/1     Running   0          11s
```
Вызов на локолхосте /orders отдает:
```
[{"id":2,"item":"date","status":"new","created_at":"2026-10-05T08:55:03.250317+00:00"},{"id":1,"item":"cherry","status":"new","created_at":"2026-10-05T08:55:03.158824+00:00"}]
```
восстановленный контейнер рабочий. 
<br>
Смотрим объект:
```
kubectl get cluster postgres -n shop -o yaml > /tmp/cluster.yaml
cat /tmp/cluster.yaml
```
```Python
#spec задали сами

spec:
  instances: 1
  storage:
    size: 1Gi
  bootstrap:
    initdb:
      database: shop
      owner: shop
      secret:
        name: postgres-app-secret

#status прописал оператор

status:
  phase: Cluster in healthy state
  instances: 1
  readyInstances: 1
  currentPrimary: postgres-1
  ...
```
Что видим:
1. Мы прописали, что хотим: 1 инстанс Postgres, 1 GiB storage, базу shop, юзера shop, пароль из Secret. <br>
2. В статус записывается отчет оператора о том, что сделано. <br>

### 3.Отличие оператора от controller-manager <br>
- controller-manager встроен в кубер  (под в кубере,  является одним из control plane компонентов) <br>
- оператор - расширение кубера, которое является совокупностью контроллера (под, который следит за CRD и приводит их к желаемому состоянию) и самих CRD-объектов (новый тип объектов сервера апи)
оператор делает очень большой пласт работы самостоятельно: резервные копии, подъемы при падениях и тп
<br>
юююху -3 (да кринж, но че поделать)<br>

## Часть 4 — Падение control plane<br> <br>
Control plane - начальник коастера, поэтому мы его щас делитним, чтобы понять чем это черевато (ну мы уже знаем из лекции, но на слово верить нельзя). <br>
1. Фиксим состояние до:
```
kubectl get pods -n shop
kubectl get nodes
```
```
NAME                      READY   STATUS    RESTARTS       AGE
api-676898b8-lwjdw        1/1     Running   0              39m
api-676898b8-nxxrp        1/1     Running   20 (44m ago)   117m
api-676898b8-qfzgp        1/1     Running   0              39m
postgres-1                1/1     Running   0              26m
worker-5966f547dd-dgxjl   1/1     Running   0              14h
worker-5966f547dd-lwrkd   1/1     Running   0              14h
NAME       STATUS   ROLES           AGE   VERSION
minikube   Ready    control-plane   12d   v1.37.0
```
2. Останавливае апи сервер:<br>
на этом этапе новый дроп:<br>
сбой control plane в minikube не сработает через systemctl stop kubelet, так как kubelet внутри minikube - это один процесс, запущенный через systemd, а apiserver - контейнер, который запускается через манифест кубелетом. При остановке kubelet остановился, а его дети-поды остались живы в containerd.<br>

Заходим в миникуб и останавливаем кубелет:
```
minikube ssh
sudo systemctl stop kubelet
```
Убиваем сервер:
```
CID=$(sudo crictl ps --name kube-apiserver -q)
echo "Stopping apiserver: $CID"
sudo crictl stop $CID
```
Смотрим:
```
sudo crictl ps | grep apiserver

ответ: пусто
```
апи сервер бобик сдох, а поды живы
```
sudo crictl ps | grep -E 'api|worker|postgres'

ответ:
0b9e82da1674d       b1a257270af7e       59 minutes ago      Running             postgres                  0                   84f6759cc4522       postgres-1                                              shop
3eb64bd383484       d5b15fe715bb8       About an hour ago   Running             api                       0                   5d29e7686cc63       api-676898b8-qfzgp                                      shop
a5e76964cdbda       d5b15fe715bb8       About an hour ago   Running             api                       0                   c37bafb54ce8f       api-676898b8-lwjdw                                      shop
0d4e0917ca250       d5b15fe715bb8       About an hour ago   Running             api                       20                  3c85fdb8592f4       api-676898b8-nxxrp                                      shop
20464070a2e2f       553caf23e31dc       15 hours ago        Running             worker                    0                   6e6fe0923b496       worker-5966f547dd-lwrkd                                 shop
a25483ba9fb54       553caf23e31dc       15 hours ago        Running             worker                    0                   23a140bb593a3       worker-5966f547dd-dgxjl                                 shop
```
Во втором терминале (снаружи миникуба):
```
kubectl get pods -n shop
helm upgrade shop ./shop-chart -n shop

ответ:
The connection to the server 172.17.0.3:8443 was refused - did you specify the right host or port?
Error: UPGRADE FAILED: Kubernetes cluster unreachable: Get "https://172.17.0.3:8443/version": dial tcp 172.17.0.3:8443: connect: connection refused
```
Инсайт: Data plane продолжает работать, когда control plane лежит. <br>

3. Возвращаем кубелет<br>
В миникубе:
```
sudo systemctl start kubelet
exit
```
Снаружи:
```
kubectl get nodes
kubectl get pods -n shop
helm upgrade shop ./shop-chart -n shop
```
видим:
```
NAME       STATUS   ROLES           AGE   VERSION
minikube   Ready    control-plane   12d   v1.37.0
NAME                      READY   STATUS    RESTARTS       AGE
api-676898b8-lwjdw        1/1     Running   0              78m
api-676898b8-nxxrp        1/1     Running   20 (83m ago)   156m
api-676898b8-qfzgp        1/1     Running   0              78m
postgres-1                1/1     Running   0              64m
worker-5966f547dd-dgxjl   1/1     Running   0              15h
worker-5966f547dd-lwrkd   1/1     Running   0              15h
Release "shop" has been upgraded. Happy Helming!
NAME: shop
LAST DEPLOYED: Mon Oct  5 13:02:45 2026
NAMESPACE: shop
STATUS: deployed
REVISION: 13
```
все сново работает.

