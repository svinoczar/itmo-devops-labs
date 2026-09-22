# Лабораторная работа №2: мониторинг сервиса в Kubernetes

## Окружение
- Ubuntu 26.04 LTS, 14 CPU, 30 GB RAM
- Docker Engine 29.8.0
- kubectl v1.31.14
- minikube v1.39.0 (driver: docker, 4 CPU, 8 GB RAM), Kubernetes v1.37.0
- Helm v3.22.0
- Python 3.14.4

## Часть 0. Сервис api
Создаем HTTP сервер с следующей структурой проекта:
api/
├── app.py             
├── requirements.txt   
└── .venv/              

-прописываем роуты
-прописываем метрики по формату RED(L-в нашем случае)
-прописываем хуки 

Запускаем локально:
```
cd api
python3 -m venv .venv
source .venv/bin/activate
pip install -r requirements.txt
python app.py
```

Ответ /health:
```
maria@ubuntu-dev:~/itmo-devops-labs/lab2/api$ curl -i localhost:5000/health
HTTP/1.1 200 OK
Server: Werkzeug/3.1.8 Python/3.14.4
Date: Tue, 22 Sep 2026 17:44:46 GMT
Content-Type: text/html; charset=utf-8
Content-Length: 2
Connection: close

ok
```
Сервер жив - ответ ok

Ответ /fail:
```
maria@ubuntu-dev:~/itmo-devops-labs/lab2/api$ curl -i localhost:5000/fail
HTTP/1.1 500 INTERNAL SERVER ERROR
Server: Werkzeug/3.1.8 Python/3.14.4
Date: Tue, 22 Sep 2026 17:44:50 GMT
Content-Type: text/html; charset=utf-8
Content-Length: 17
Connection: close

simulated failure
```
Функция возвращает ошибку 500 - счетчик видит, что ошибка серверная и инкрементируется.

Ответ /slow:
```
simulated failuremaria@ubuntu-dev:~/itmo-devops-labs/lab2/api$ time curl localhost:5000/slow
slept 1.70
real	0m1.722s
user	0m0.008s
sys	0m0.009s
```
-real — реальное время 
-user/sys — процессорное время <br>
у них маленькие значения, потому что сервер sleep

Ответ /load:
```
maria@ubuntu-dev:~/itmo-devops-labs/lab2/api$ time curl 'localhost:5000/load?count=10'
fired 10 requests
real	0m7.454s
user	0m0.008s
sys	0m0.009s
```
Функция делает заданное количество запросов на другие адреса => подскакивает время.

Ответ /metrics:
```
maria@ubuntu-dev:~/itmo-devops-labs/lab2/api$ curl -s localhost:5000/metrics | grep -E '^http_(requests|request_errors)'
http_requests_total{method="GET",path="/fail",status="500"} 8.0
http_requests_total{method="GET",path="/slow",status="200"} 8.0
http_requests_total{method="GET",path="/health",status="200"} 9.0
http_requests_total{method="GET",path="/load",status="200"} 2.0
http_requests_total{method="GET",path="/metrics",status="200"} 1.0
http_requests_created{method="GET",path="/fail",status="500"} 1.7900983969137557e+09
http_requests_created{method="GET",path="/slow",status="200"} 1.790098398648161e+09
http_requests_created{method="GET",path="/health",status="200"} 1.7900983987234986e+09
http_requests_created{method="GET",path="/load",status="200"} 1.7900984054311123e+09
http_requests_created{method="GET",path="/metrics",status="200"} 1.790099301891964e+09
http_request_errors_total{method="GET",path="/fail"} 8.0
http_request_errors_created{method="GET",path="/fail"} 1.7900983969137971e+09
```
Видим количество запросов к каждому адресу, сделанное к этому моменту. Метку ошибки только у /fail запросов (8 запросов = 8 ошибок).
+ /metrics считает сама себя, так как тоже является запросом

## Часть 1. Метрики (Prometheus + Grafana)
(заполним позже)

## Часть 2. Логи (Loki + Grafana)
(заполним позже)

## Часть 3. Трейсы (OpenTelemetry + Jaeger)
(заполним позже)

## Часть 4. Алерты (Alertmanager + Karma)
(заполним позже)

## Итог
(заполним позже)
