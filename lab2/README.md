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
```
api/ 
├── app.py             
├── requirements.txt   
└── .venv/
```

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
            ""ts": datetime.fromtimestamp(record.created, tz=timezone.utc).isoformat(),
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

Тестируем (запускаем сервер и вызываем /health, /fail, /slow) и видим в выводе JSON:
```
{"ts": "2026-09-23T12:54:41.%fZ", "level": "INFO", "msg": "health check", "logger": "api", "trace_id": "5ce0051037278d8e31143890fa6a1c1c"}

{"ts": "2026-09-23T12:54:41.%fZ", "level": "ERROR", "msg": "simulated failure on /fail", "logger": "api", "trace_id": "af116e9427667bcc4b8444baebb75878"}

{"ts": "2026-09-23T12:54:41.%fZ", "level": "INFO", "msg": "slow op start num=1.71", "logger": "api", "trace_id": "e988c8fd654ba9072ab2784423810af6"}

{"ts": "2026-09-23T12:54:42.%fZ", "level": "INFO", "msg": "slow op done", "logger": "api", "trace_id": "e988c8fd654ba9072ab2784423810af6"}
```
у каждого запроса свой id, кроме логов slow, у них одинаковый.

**5.Dockerfile**<br>
Так как в кубере все работает как контенер, необходимо наш серсер прописать в контейнер.
Прописываем Dockerfile и собираем:
```
maria@ubuntu-dev:~/itmo-devops-labs/lab2/api$ docker images | grep api
api:0.1                                                                                               2f3e0527d9a5        250MB         60.4MB     
```
образ успешно собран.<br>
Работает:
```
http_requests_total{method="GET",path="/health",status="200"} 1.0
http_requests_total{method="GET",path="/fail",status="500"} 1.0
http_requests_total{method="GET",path="/slow",status="200"} 1.0
```

Загружаем в minikube, чтобы он увидел образ:
```
maria@ubuntu-dev:~/itmo-devops-labs/lab2/api$ minikube image load api:0.1
maria@ubuntu-dev:~/itmo-devops-labs/lab2/api$ minikube image ls | grep api
registry.k8s.io/kube-apiserver:v1.37.0
docker.io/library/api:0.1
```

**6. Helm** <br>
Одно из условий лабы - стек на Helm, поэтому упаковываем наш сервис в Helm-чарт(пакет шаблонов для исполнения разных значений).

Создаем структуру проекта:
```
maria@ubuntu-dev:~/itmo-devops-labs/lab2/helm$ cd ~/itmo-devops-labs/lab2/helm
helm create api
Creating api
```
Теперь у нас создана дерриктория из файлов:
```
maria@ubuntu-dev:~/itmo-devops-labs/lab2/helm$ ls -la api/
total 32
drwxr-xr-x 4 maria maria 4096 Sep 25 11:27 .
drwxrwxr-x 3 maria maria 4096 Sep 25 11:27 ..
-rw-r--r-- 1 maria maria  349 Sep 25 11:27 .helmignore
-rw-r--r-- 1 maria maria 1139 Sep 25 11:27 Chart.yaml
drwxr-xr-x 2 maria maria 4096 Sep 25 11:27 charts
drwxr-xr-x 3 maria maria 4096 Sep 25 11:27 templates
-rw-r--r-- 1 maria maria 5249 Sep 25 11:27 values.yaml
maria@ubuntu-dev:~/itmo-devops-labs/lab2/helm$ ls -la api/templates/
total 44
drwxr-xr-x 3 maria maria 4096 Sep 25 11:27 .
drwxr-xr-x 4 maria maria 4096 Sep 25 11:27 ..
-rw-r--r-- 1 maria maria 2802 Sep 25 11:27 NOTES.txt
-rw-r--r-- 1 maria maria 1742 Sep 25 11:27 _helpers.tpl
-rw-r--r-- 1 maria maria 2360 Sep 25 11:27 deployment.yaml
-rw-r--r-- 1 maria maria  979 Sep 25 11:27 hpa.yaml
-rw-r--r-- 1 maria maria  945 Sep 25 11:27 httproute.yaml
-rw-r--r-- 1 maria maria 1076 Sep 25 11:27 ingress.yaml
-rw-r--r-- 1 maria maria  349 Sep 25 11:27 service.yaml
-rw-r--r-- 1 maria maria  381 Sep 25 11:27 serviceaccount.yaml
drwxr-xr-x 2 maria maria 4096 Sep 25 11:27 tests
```
не все из которых нам нужны, поэтому после удаления ненужых поучаем следующую картину:
```
helm/api/<br>
├── Chart.yaml         
├── values.yaml
└── templates/ 
  ├── _helpers.tpl
  ├── deployment.yaml
  └── service.yaml
```
Файлы заполнены по дефолту в соответсвии с шаблонами, сейчас будь править под наш проект.<br>

Настраиваем **Chart.yaml** - паспорт чарта (имя, версия, тип). В дефолтном заполнении не соответсвует только версия app, поэтому меняем ее и получем:
```Python
#Chart.yaml

apiVersion: v2
name: api
description: A Helm chart for Kubernetes
type: application
version: 0.1.0
appVersion: "0.1"
```
Настраиваем **values.yaml** - дефолтные значения для шаблонов. Сейчас этот файл очень громоздкий. Мы его почистим и оставим:
```Python
#values.yaml

replicaCount: 1  #число копий пода

image:    #какой образ запускать в поде
  repository: api
  tag: "0.1"
  pullPolicy: IfNotPresent  #тянуть образ только если нет локально

service:     #ну тут все понятно
  type: ClusterIP #доступен только внутри кластера
  port: 5000
  targetPort: 5000

env:     #переменные окружения
  PORT: "5000"
  SELF_URL: "http://localhost:5000"
  OTEL_SERVICE_NAME: "api"   #иям в трейсах
  OTEL_EXPORTER_OTLP_ENDPOINT: "http://localhost:4317"   #куда слать спаны
```

**Настраиваем Deployment** <br>
Deployment отвечает за то сколько подов создать и за их состоянием: сколько живо, какие воскресить и тд <br>
**deployment.yaml** <br>
Для работы пода оставляем только: <br>
- metadata.name и labels (имя Deployment и метки для гнруппировок)<br>
- spec.replicas (количество копий пода)<br>
- spec.selector и spec.template.metadata.labels (какие поды относятся к сервису по меткам)<br>
- spec.template.spec.containers[] (имя, образ, порт контейнера)<br>

Последние 3 отвечают за желаемое состояние, с которым все время сравнивает кубер текущее состояние сервиса. Получаем файл:
```Python
#deployment.yaml

apiVersion: apps/v1
kind: Deployment
metadata:
  name: {{ include "api.fullname" . }}
  labels:
    {{- include "api.labels" . | nindent 4 }}
spec:
  replicas: {{ .Values.replicaCount }}
  selector:
    matchLabels:
      {{- include "api.selectorLabels" . | nindent 6 }}
  template:
    metadata:
      labels:
        {{- include "api.selectorLabels" . | nindent 8 }}
    spec:
      containers:
        - name: api
          image: "{{ .Values.image.repository }}:{{ .Values.image.tag }}"
          imagePullPolicy: {{ .Values.image.pullPolicy }}
          ports:
            - name: http
              containerPort: {{ .Values.service.targetPort }}
              protocol: TCP
          env:
            {{- range $key, $val := .Values.env }}
            - name: {{ $key }}
              value: {{ $val | quote }}
            {{- end }}****
```

**Настраиваем Service**<br>
Service отвечает за стабильность доступа к подам. Так как айпишники контейнеров все время меняются, он дает им DNS-имена и направляет трафик на живые поды.<br>
**service.yaml** <br>
Файл сервича оставляем без изменений:
```Python
#service.yaml

apiVersion: v1
kind: Service
metadata:
  name: {{ include "api.fullname" . }}
  labels:
    {{- include "api.labels" . | nindent 4 }}
spec:
  type: {{ .Values.service.type }}
  ports:
    - port: {{ .Values.service.port }}
      targetPort: http
      protocol: TCP
      name: http
  selector:
    {{- include "api.selectorLabels" . | nindent 4 }}
```
Деплоим в кластер:
```
maria@ubuntu-dev:~/itmo-devops-labs/lab2/helm$ helm install api ./api
NAME: api
LAST DEPLOYED: Fri Sep 25 12:16:25 2026
NAMESPACE: default
STATUS: deployed
REVISION: 1
TEST SUITE: None
```
Просматриваем экземпляров чарта в namespace:
```
maria@ubuntu-dev:~/itmo-devops-labs/lab2/helm$ helm list
NAME	NAMESPACE	REVISION	UPDATED                                	STATUS  	CHART    	APP VERSION
api 	default  	1       	2026-09-25 12:16:25.748246738 +0300 MSK	deployed	api-0.1.0	0.1        
```
установлен.<br>

Проверям:
```
maria@ubuntu-dev:~/itmo-devops-labs/lab2/helm$ helm list 
NAME	NAMESPACE	REVISION	UPDATED                                	STATUS  	CHART    	APP VERSION
api 	default  	1       	2026-09-25 12:16:25.748246738 +0300 MSK	deployed	api-0.1.0	0.1        
maria@ubuntu-dev:~/itmo-devops-labs/lab2/helm$ kubectl get pods 
NAME                   READY   STATUS    RESTARTS   AGE
api-598b897dd7-xvmfr   1/1     Running   0          4m48s
maria@ubuntu-dev:~/itmo-devops-labs/lab2/helm$ kubectl get svc
NAME         TYPE        CLUSTER-IP       EXTERNAL-IP   PORT(S)    AGE
api          ClusterIP   10.103.151.249   <none>        5000/TCP   5m9s
kubernetes   ClusterIP   10.96.0.1        <none>        443/TCP    2d18h
maria@ubuntu-dev:~/itmo-devops-labs/lab2/helm$ kubectl get deploy
NAME   READY   UP-TO-DATE   AVAILABLE   AGE
api    1/1     1            1           5m24s
```
видим, что релиз установлен, под работает, сервис создан и слушает порт внутри кластера, деплоймент создан и реплика 1.

Проверяем работоспособность.<br>
В одном терминале:
```
maria@ubuntu-dev:~/itmo-devops-labs/lab2/helm$ kubectl port-forward svc/api 5001:5000
Forwarding from 127.0.0.1:5001 -> 5000
Forwarding from [::1]:5001 -> 5000
Handling connection for 5001
Handling connection for 5001
Handling connection for 5001
Handling connection for 5001
```
Во втором:
```
maria@ubuntu-dev:~/itmo-devops-labs/lab2/helm$ curl -i localhost:5001/health
curl -i localhost:5001/fail
time curl localhost:5001/slow
curl -s localhost:5001/metrics | grep '^http_requests_total'
HTTP/1.1 200 OK
Server: Werkzeug/3.1.8 Python/3.12.14
Date: Fri, 25 Sep 2026 09:30:24 GMT
Content-Type: text/html; charset=utf-8
Content-Length: 2
Connection: close

HTTP/1.1 500 INTERNAL SERVER ERROR
Server: Werkzeug/3.1.8 Python/3.12.14
Date: Fri, 25 Sep 2026 09:30:24 GMT
Content-Type: text/html; charset=utf-8
Content-Length: 17
Connection: close

slept 1.92failure
real	0m1.942s
user	0m0.004s
sys	0m0.011s
http_requests_total{method="GET",path="/health",status="200"} 1.0
http_requests_total{method="GET",path="/fail",status="500"} 1.0
http_requests_total{method="GET",path="/slow",status="200"} 1.0
```
Сервис работает через кубер. Ура спасибо.


## Часть 1.Prometheus + Grafana
Prometheus - база данныхз для метрик. <br>
Для реализации этой части задания мы будем использовать готовый Helm-чарт: kube-prometheus-stack. <br>
Он ставит сразу:
- Prometheus (сбор метрик) <br>
- Alertmanager (приём и рассылка алертов) <br>
- Grafana(визуализаци) <br>
- node-exporter (экспортёр метрик ноды) <br>
- kube-state-metrics (экспортёр метрик K8s-объектов) <br>
  
Дакавать команду о скрепинге мы будем через ServiceMonitor, чтобы не править конфиг файл вручну.

Добавляем репозиторий:
```
maria@ubuntu-dev:~/itmo-devops-labs/lab2$ helm repo add prometheus-community https://prometheus-community.github.io/helm-charts
helm repo update
"prometheus-community" has been added to your repositories
Hang tight while we grab the latest from your chart repositories...
...Successfully got an update from the "prometheus-community" chart repository
Update Complete. ⎈Happy Helming!⎈
```
Создаем **values.yaml** с настройками для чарта в папке monitoring.<br>
В нем прописывается prometheus, настройка для ServiceMonitor, данные для grafana, alertmanager.

Устанавливаем kube-prometheus-stack:
```
maria@ubuntu-dev:~/itmo-devops-labs/lab2/helm/monitoring$ helm install kube-prom prometheus-community/kube-prometheus-stack \
  --namespace monitoring \
  --create-namespace \
  -f kube-prometheus-stack-values.yaml
NAME: kube-prom
LAST DEPLOYED: Fri Sep 25 13:56:02 2026
NAMESPACE: monitoring
STATUS: deployed
REVISION: 1
```
установился, проверяем установку подов:
```
maria@ubuntu-dev:~/itmo-devops-labs/lab2/helm/monitoring$ kubectl get pods -n monitoring
NAME                                                    READY   STATUS    RESTARTS   AGE
alertmanager-kube-prom-kube-prometheus-alertmanager-0   2/2     Running   0          7m57s
kube-prom-grafana-7dbdf8d889-q9v2c                      3/3     Running   0          8m3s
kube-prom-kube-prometheus-operator-68764fddf6-r8qxj     1/1     Running   0          8m3s
kube-prom-kube-state-metrics-58fb46f59-bb5bb            1/1     Running   0          8m3s
kube-prom-prometheus-node-exporter-wnvjx                1/1     Running   0          8m3s
prometheus-kube-prom-kube-prometheus-prometheus-0       2/2     Running   0          7m57s
```
- 2 alertmanager: сам alertmanager и config-reloader (следит за изменениями конфига Alertmanager)  <br>
- 3 grafana: сама grafana, grafana-sc-dashboard (подхватывает новые дашборды), grafana-sc-datasources (отвечает за подключение к Prometheus)  <br>
- 1 prometheus-operator: сам оператор    <br>
- 2 prometheus-prometheus: сам prometheus и config-reloader (перезагружает конфиг Prometheus)  <br>

Проверяем сервисы в namespace:
```
maria@ubuntu-dev:~/itmo-devops-labs/lab2/helm/monitoring$ kubectl get svc -n monitoring
NAME                                     TYPE        CLUSTER-IP       EXTERNAL-IP   PORT(S)                      AGE
alertmanager-operated                    ClusterIP   None             <none>        9093/TCP,9094/TCP,9094/UDP   9m36s
kube-prom-grafana                        ClusterIP   10.109.128.238   <none>        80/TCP                       9m42s
kube-prom-kube-prometheus-alertmanager   ClusterIP   10.100.193.40    <none>        9093/TCP,8080/TCP            9m42s
kube-prom-kube-prometheus-operator       ClusterIP   10.97.70.235     <none>        443/TCP                      9m42s
kube-prom-kube-prometheus-prometheus     ClusterIP   10.103.62.166    <none>        9090/TCP,8080/TCP            9m42s
kube-prom-kube-state-metrics             ClusterIP   10.107.5.75      <none>        8080/TCP                     9m42s
kube-prom-prometheus-node-exporter       ClusterIP   10.99.140.190    <none>        9100/TCP                     9m42s
prometheus-operated                      ClusterIP   None             <none>        9090/TCP                     9m36s
```

Проверяем работу Prometheus UI:
запускаем в терминале 
```
maria@ubuntu-dev:~/itmo-devops-labs/lab2/helm/monitoring$ kubectl port-forward -n monitoring svc/kube-prom-kube-prometheus-prometheus 9090:9090
```
открываем http://localhost:9090:
<img width="1381" height="673" alt="изображение" src="https://github.com/user-attachments/assets/e06d7b3a-5999-401a-b0be-b728c1ed0a54" />
Проверяем работу Grafana UI:
запускаем в терминале 
```
maria@ubuntu-dev:~/itmo-devops-labs/lab2/helm/monitoring$ kubectl port-forward -n monitoring svc/kube-prom-grafana 3000:80
```
открываем http://localhost:3000:
<img width="1381" height="673" alt="изображение" src="https://github.com/user-attachments/assets/b4ced819-d913-4a7a-b07f-89ea4a12f988" />

Для работы ServiceMonitor создаем в папке templates **servicemonitor.yaml**. <br>
```Python
#servicemonitor.yaml

{{- if .Values.serviceMonitor.enabled }}
apiVersion: monitoring.coreos.com/v1  #api-версия для ServiceMonitor
kind: ServiceMonitor
metadata:
  name: {{ include "api.fullname" . }}  #совпадает с именем Deployment/Service
  labels:
    {{- include "api.labels" . | nindent 4 }}
    release: kube-prom
spec:
  namespaceSelector:
    matchNames:
      - default #искать Service в namespace default
  selector:
    matchLabels:  #по каким меткам искать Service
      {{- include "api.selectorLabels" . | nindent 6 }}
  endpoints:
    - port: http
      path: /metrics
      interval: 15s
{{- end }}
```
Добавляем в values.yaml:
```Python
serviceMonitor:
  enabled: true
```
Обновляем:
```
maria@ubuntu-dev:~/itmo-devops-labs/lab2/helm$ cd ~/itmo-devops-labs/lab2/helm
helm upgrade api ./api
Release "api" has been upgraded. Happy Helming!
NAME: api
LAST DEPLOYED: Fri Sep 25 14:28:36 2026
NAMESPACE: default
STATUS: deployed
REVISION: 2 #так как внесли изменения
```
Проверяем ServiceMonitor
```
maria@ubuntu-dev:~/itmo-devops-labs/lab2/helm$ kubectl get servicemonitor -A
NAMESPACE    NAME                                                AGE
default      api                                                 79s
```
сервис живет.<br>

Смотрим видит Prometheus ли наш ServiceMonitor:
```
maria@ubuntu-dev:~/itmo-devops-labs/lab2/helm$ kubectl get prometheus -n monitoring kube-prom-kube-prometheus-prometheus -o jsonpath='{.spec.serviceMonitorSelector}{"\n"}'
{}
```
пусто => erviceMonitorSelectorNilUsesHelmValues: false сработал, Prometheus берёт все ServiceMonitor
Открывем Prometheus UI - Status - Targets:
<img width="1343" height="549" alt="изображение" src="https://github.com/user-attachments/assets/a3785ce6-9ecb-4c3e-be71-a48d4ff0522f" />
serviceMonitor/default/api/0 - откуда узнал про наш сервис<br>
State: UP - успешно скрейпит наш сервис<br>

Еще есть картинка:
<img width="2741" height="1466" alt="изображение" src="https://github.com/user-attachments/assets/a4139af3-4e00-4d06-a4be-92cd8db36fef" />
Prometheus дергает /metrics, поэтому она подскочила, а остальные 0.

**Делаем дашборд**<br>
Открываем графану, создаем новый дашборд и добавляем визулизацию.<br>
**1. Rate**<br>
В поле для PromQL запроса ввоздим:
```
sum by (path) (rate(http_requests_total[5m]))
```
выводит количетсво запросов в секунду по каждому пути.
<img width="1233" height="648" alt="изображение" src="https://github.com/user-attachments/assets/d5d2a51d-3e17-4a8a-a126-04ad65c7441f" />
сейщас запросов нет, поэтому картинка грустная. <br>
Создади запросы: в одном терминале
```
kubectl port-forward svc/api 5001:5000
```
во втором:
```
for i in $(seq 1 30); do
  curl -s localhost:5001/health > /dev/null
done

for i in $(seq 1 10); do
  curl -s localhost:5001/fail > /dev/null
done

for i in $(seq 1 3); do
  curl -s localhost:5001/slow > /dev/null
done
```
настроим время отображения и видим:
<img width="1233" height="648" alt="изображение" src="https://github.com/user-attachments/assets/20f81be5-db28-4b76-8d47-fdf07dc5dd2e" />
график стал поинтереснее.

**2. Errors**<br>
В поле для PromQL запроса ввоздим:
```
sum(rate(http_request_errors_total[5m])) 
/ 
sum(rate(http_requests_total[5m]))
```
считает долю ошибок от всех запросов.

**3. 95p** <br>
В поле для PromQL запроса ввоздим:
```
histogram_quantile(
  0.95,
  sum by (le, path) (
    rate(http_request_duration_seconds_bucket[5m])
  )
)
```
считает 95-й % по каждой комбинации.

Еще раз создадти нагрузку и смотрим на панели:
<img width="2116" height="1039" alt="изображение" src="https://github.com/user-attachments/assets/b4277fec-5b39-43d0-bb55-52a1d8c752dd" />
Панель Request Rate: подскочили графики всех путей, /metrics стабильны, так как их дергает Prometheus постоянно.<br>
Панель Error Rate:с 0% до ~20%, так как передали 20 запросов.<br>
Панель p95 Latency: /slow — подскочил до ~4.4 секунды.


## Часть 2. Логи (Loki + Grafana)
(заполним позже)


## Часть 3. Трейсы (OpenTelemetry + Jaeger)
(заполним позже)

## Часть 4. Алерты (Alertmanager + Karma)
(заполним позже)

## Итог
(заполним позже)
