# Лабораторная работа №2: мониторинг сервиса в Kubernetes

## Окружение
- Ubuntu 26.04 LTS, 14 CPU, 30 GB RAM
- Docker Engine 29.8.0
- kubectl v1.31.14
- minikube v1.39.0 (driver: docker, 4 CPU, 8 GB RAM), Kubernetes v1.37.0
- Helm v3.22.0
- Python 3.14.4

## Часть 0. Сервис api 
Создаем HTTP сервер с следующей структурой проекта: <br>
api/ <br>
├── app.py             
├── requirements.txt   
└── .venv/              

**1. прописываем роуты:** <br>
GET /health — возвращает ok;<br>
GET /fail — возвращает ошибку 5xx и увеличивает счётчик ошибок;<br>
GET /slow — отвечает медленно (спит 1–3 секунды);<br>
GET /load — делает пачку запросов к себе, чтобы подскочил RPS.<br>
(прописываем хуки Flask для работы функций)

**2. прописываем метрики:** <br>
счётчик запросов,<br>
счётчик ошибок,<br>
гистограмму времени ответа (RED);<br>

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
-real — реальное время <br>
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
Видим количество запросов к каждому адресу, сделанное к этому моменту. Метку ошибки только у /fail запросов (8 запросов = 8 ошибок). <br>
/metrics считает сама себя, так как тоже является запросом

**3. устанавливаем зависимости для работы OpenTelemetry и JSON-логов**<br>
Зависимости:
```Python
(.venv) maria@ubuntu-dev:~/itmo-devops-labs/lab2/api$ pip install \
  opentelemetry-distro \
  opentelemetry-exporter-otlp \
  opentelemetry-instrumentation-flask
```
и импорты их в app.py.

Прописываем провайдер и экспортер:
```Python
resource = Resource.create({"service.name": os.getenv("OTEL_SERVICE_NAME", "api")})
provider = TracerProvider(resource=resource)
otlp_endpoint = os.getenv("OTEL_EXPORTER_OTLP_ENDPOINT", "http://localhost:4317")
provider.add_span_processor(
    BatchSpanProcessor(OTLPSpanExporter(endpoint=otlp_endpoint, insecure=True))
)
trace.set_tracer_provider(provider) #делаем глобальным
tracer = trace.get_tracer("api") #трейсер для создания своих спанов
```
Resource.create - имя сервиса в Jaeger <br>
endpoint - адрес OTLP-приёмника (пропишем в части 3)

Прописываем логгеры:
```Python
class JsonFormatter(logging.Formatter):
    def format(self, record):
        span = trace.get_current_span() #узнает активный спан
        ctx = span.get_span_context() if span else None
        trace_id = format(ctx.trace_id, "032x") if ctx and ctx.trace_id else ""
        payload = { 
            "ts": self.formatTime(record, "%Y-%m-%dT%H:%M:%S.%fZ"),
            "level": record.levelname, #уровень для фильтрации
            "msg": record.getMessage(), #итоговое сообщение
            "logger": record.name,
            "trace_id": trace_id,
        }
        if record.exc_info:
            payload["exc"] = self.formatException(record.exc_info)
        return json.dumps(payload, ensure_ascii=False)


handler = logging.StreamHandler(sys.stdout) #пишет в stdout, оттуда уходит в Loki
handler.setFormatter(JsonFormatter())
log = logging.getLogger("api")
log.setLevel(logging.INFO)
log.handlers = [handler]
log.propagate = False #не дублирует в родительский логер
```
format(record) - получает одну запись лога и возвращает строку, которая уйдёт в вывод <br>
```Python
FlaskInstrumentor().instrument_app(app) #создает корневой спан
```

**4. прописываем логи** <br>
/health:
```Python
log.info("health check")
```
/fail:
```Python
span = trace.get_current_span()
span.set_status(trace.Status(trace.StatusCode.ERROR, "simulated failure")) #подсветится красным
span.set_attribute("error", True) #тег для отображения ошибки
log.error("simulated failure on /fail")
```
/slow:
```Python
with tracer.start_as_current_span("slow-op") as sp: #создаем вложенный спан и открываем про входе
  sp.set_attribute("delay.seconds", delay) #закрепляем значение за спаном
  log.info(f"slow op start delay={delay:.2f}") 
  time.sleep(delay)
log.info("slow op done")
```
/load:
```Python
log.info(f"load fired {count} requests")
```

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
