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

