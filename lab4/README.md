# Лаборатория 4 — SLA критического пути при перегрузке
## Переустановка контейнера для поготовки к лабе
Для лабораторной 4 нужно больше памяти, чем у нас заложено сейчас на контейнер, так как поставить вторую ноду система не дает. Docker не позволяет изменить память у существующего контейнера.
<br>
Создаем новый контейнер с 2 узлами:
```
minikube start --nodes=2 --memory=8192 --cpus=4 --disk-size=20g --driver=docker \
  --network=minikube-lab4 --subnet=10.99.0.0/24
```
Смотрим после создания:
```
kubectl get nodes
kubectl get nodes -o wide
```
Видим, что создано 2 узла:
```
NAME           STATUS   ROLES           AGE     VERSION
minikube       Ready    control-plane   2m10s   v1.37.0
minikube-m02   Ready    <none>          106s    v1.37.0
NAME           STATUS   ROLES           AGE     VERSION   INTERNAL-IP   EXTERNAL-IP   OS-IMAGE                         KERNEL-VERSION             CONTAINER-RUNTIME
minikube       Ready    control-plane   2m11s   v1.37.0   10.99.0.2     <none>        Debian GNU/Linux 12 (bookworm)   7.0.0-38-generic (amd64)   containerd://2.3.4
minikube-m02   Ready    <none>          107s    v1.37.0   10.99.0.3     <none>        Debian GNU/Linux 12 (bookworm)   7.0.0-38-generic (amd64)   containerd://2.3.4
```
Устанавливаем:
1. Helm-репозитории
```
helm repo add kyverno https://kyverno.github.io/kyverno/
helm repo add cnpg https://cloudnative-pg.github.io/charts
helm repo add prometheus-community https://prometheus-community.github.io/helm-charts
helm repo add grafana https://grafana.github.io/helm-charts
helm repo add jaegertracing https://jaegertracing.github.io/helm-charts
helm repo update
```
2. Kyverno
```
helm install kyverno kyverno/kyverno -n kyverno --create-namespace
```
3. CloudNativePG-оператор
```
helm install cnpg cnpg/cloudnative-pg -n cnpg-system --create-namespace
```
4.Предзагружаем образ Postgres для CNPG:
```
docker pull ghcr.io/cloudnative-pg/postgresql:18.6-system-trixie
minikube image load ghcr.io/cloudnative-pg/postgresql:18.6-system-trixie
```
5. kube-prometheus-stack
```
helm install kube-prom prometheus-community/kube-prometheus-stack -n monitoring --create-namespace
```
6. Loki + Promtail 
```
helm install loki grafana/loki-stack -n monitoring
```
7. Jaeger
```
helm install jaeger jaegertracing/jaeger -n monitoring
```
8. Karma
```
helm repo add karma https://pkgs.xo.nl/helm-charts/ 2>/dev/null
helm repo update
helm install karma karma/karma -n monitoring
```
Все контейнеры запущены:
```
maria@ubuntu-dev:~/itmo-devops-labs$ kubectl get pods -n monitoring
NAME                                                    READY   STATUS    RESTARTS   AGE
alertmanager-kube-prom-kube-prometheus-alertmanager-0   2/2     Running   0          26m
jaeger-f8d899587-fqt8h                                  1/1     Running   0          23m
karma-7947f898f4-nqq4w                                  1/1     Running   0          22m
kube-prom-grafana-6f46bf767f-9tprk                      3/3     Running   0          103s
kube-prom-kube-prometheus-operator-765d8d7c48-28xvk     1/1     Running   0          27m
kube-prom-kube-state-metrics-6cdcfcd9d8-zf877           1/1     Running   0          27m
kube-prom-prometheus-node-exporter-5797l                1/1     Running   0          27m
kube-prom-prometheus-node-exporter-fbwpj                1/1     Running   0          27m
loki-0                                                  1/1     Running   0          24m
loki-promtail-kqln7                                     1/1     Running   0          24m
loki-promtail-tnvxp                                     1/1     Running   0          24m
prometheus-kube-prom-kube-prometheus-prometheus-0       2/2     Running   0          26m
```
Применяем политики:
```
kubectl apply -f ~/itmo-devops-labs/lab3/policies/
kubectl get clusterpolicies 
```
Загружаем образы shop в minikube:
```
minikube image load shop-api:0.1.0
minikube image load shop-worker:0.1.0
```
Устанавливаем shop:
```
kubectl create namespace shop
helm install shop ~/itmo-devops-labs/lab3/shop-chart -n shop
```
Все поды запущены, кластер тоже:
```
NAME                      READY   STATUS    RESTARTS   AGE
api-676898b8-kfsrh        1/1     Running   0          31s
api-676898b8-l2l9r        1/1     Running   0          32s
api-676898b8-lshm9        1/1     Running   0          31s
postgres-1                1/1     Running   0          55m
worker-576cff47f4-4mg84   1/1     Running   0          73m
worker-576cff47f4-mtkcv   1/1     Running   0          73m

NAME       AGE   INSTANCES   READY   STATUS                     PRIMARY
postgres   14m   1           1       Cluster in healthy state   postgres-1
```
Готово к работе.

## Часть 0 — Партийный сервис
Генерируем сервер. Самое главное в блоке try бесконечного цикла.
<img width="363" height="550" alt="изображение" src="https://github.com/user-attachments/assets/65ea9fd1-21e1-4330-8e9e-861adbcc80e3" />

```
            iteration += 1
            
            for i in range(CPU_LOAD * 500_000):
                x = i * i
            
            held_memory = bytearray(MEM_MB * 1024 * 1024)
            
            logger.info("batch tick", extra={
                "iteration": iteration,
                "cpu_load": CPU_LOAD,
                "mem_mb": MEM_MB,
            })

            time.sleep(SLEEP_SEC)
```
Считаем итерации, нагружаем вычислениями, занимаем память, ну как бы все.
<br>
Проверяем образ:
```
cd ~/itmo-devops-labs/lab4/batch
python3 -m venv .venv
source .venv/bin/activate
pip install -r requirements.txt
CPU_LOAD=10 MEM_MB=20 SLEEP_SEC=2 python main.py
```
Успешно:
```
Successfully installed python-json-logger-4.2.0
{"time": "2026-10-07 12:02:44,268", "level": "INFO", "message": "batch started", "name": "batch", "cpu_load": 10, "mem_mb": 20, "sleep_sec": 2}
{"time": "2026-10-07 12:02:44,428", "level": "INFO", "message": "batch tick", "name": "batch", "iteration": 1, "cpu_load": 10, "mem_mb": 20}
{"time": "2026-10-07 12:02:46,595", "level": "INFO", "message": "batch tick", "name": "batch", "iteration": 2, "cpu_load": 10, "mem_mb": 20}
...
```
Собираем:
```
docker build -t shop-batch:0.1.0 .
docker images | grep shop-batch
```
```
shop-batch:0.1.0                                                                                      a72a183249af        178MB         43.3MB  
```
<br>

## Часть 1 — Выберите механизм распространения
Для защиты работы сервера реплики апи надо раскидать по разным нодам, чтобы это осуществить есть несколько способов. <br>
1. **podAntiAffinity** <br>
Этот метод запрещает помещать 2 пода с одинаковой меткой app на одну ноду. Если правильно нереализуемо, то под будет висеть Pending.<br>
В yaml выглядит вот так:
```Python
affinity:
  podAntiAffinity:
    requiredDuringSchedulingIgnoredDuringExecution: 
      - labelSelector:
          matchLabels:
            app: api
        topologyKey: kubernetes.io/hostname           
```
Плюсы: поды точно не окажутся на одной ноде. <br>
Минусы: если реплик больше, чем нод, то какие-то поды точно зависнут. <br>
<br>
 
2. **topologySpreadConstraints** <br>
Поды с одинаковой меткой app должны равномерно распределяться по нодам, но если раскидать все на разные не получится, то просто разобьет их на столько, на сколько возможно. <br>
В yaml выглядит вот так:
```Python
topologySpreadConstraints:
  - maxSkew: 1                     
    topologyKey: kubernetes.io/hostname  
    whenUnsatisfiable: ScheduleAnyway 
    labelSelector:
      matchLabels:
        app: api
```
Плюсы: даже если подов больше, чем нод, все будут на нодах и запустятся. <br>
Минусы: не гарантированное разделение подов по отдельным нодам, есть варианты, что разделения не будет вообще осущетслвено. <br>
<br>
Мы выбираем 2 вариант, просто потому, что по ТЗ у нас подов больше, чем нод, а при 1 варианте один из подов просто не запустится. <br>
Добавляем в yaml. <br>
Добавляем в shop-chart/templates/api-deployment.yaml:
```
    spec:
      {{- if eq .Values.spread.method "topologySpread" }}
      topologySpreadConstraints:
        - maxSkew: {{ .Values.spread.topologySpread.maxSkew }}
          topologyKey: {{ .Values.spread.topologySpread.topologyKey }}
          whenUnsatisfiable: {{ .Values.spread.topologySpread.whenUnsatisfiable }}
          labelSelector:
            matchLabels:
              app: api
      {{- else if eq .Values.spread.method "podAntiAffinity" }}
      affinity:
        podAntiAffinity:
          {{ .Values.spread.podAntiAffinity.type }}DuringSchedulingIgnoredDuringExecution:
            - labelSelector:
                matchLabels:
                  app: api
              topologyKey: kubernetes.io/hostname
      {{- end }}
```
Проверяем рендер:
```
cd ~/itmo-devops-labs/lab4
helm template shop ./shop-chart -n shop | grep -A 10 "topologySpreadConstraints"
```
Команда переключения, но мы пока не используем:
```
helm upgrade shop ./shop-chart -n shop --set spread.method=podAntiAffinity
```
<br>

## Часть 2 — Плотное скопление
Задача посмотреть что поды api/worker/postgres останутся живы при добавлении batch. <br>
1. добавляем секцию batch в values.yaml, куда прописываем:
```Python
    requests:
      cpu: "100m"
      memory: "64Mi"
    limits:
      cpu: "200m"
      memory: "128Mi"
```
2. Создаем batch-deployment.yaml.
3. Загружаем образы в миникуб:
```
minikube image load shop-batch:0.1.0
minikube image ls | grep shop-batch
```
ответ:
```
docker.io/library/shop-batch:0.1.0
```
4. Апргрейдим хелм:
```
cd ~/itmo-devops-labs/lab4
helm upgrade shop ./shop-chart -n shop
sleep 30
kubectl get pods -n shop
```
ответ:
```
Release "shop" has been upgraded. Happy Helming!
NAME: shop
LAST DEPLOYED: Wed Oct  7 13:22:53 2026
NAMESPACE: shop
STATUS: deployed
REVISION: 3
NAME                      READY   STATUS    RESTARTS   AGE
api-5c7ddcd847-8r85h      1/1     Running   0          18s
api-5c7ddcd847-l5sw5      1/1     Running   0          24s
api-5c7ddcd847-m4bdg      1/1     Running   0          31s
batch-86f5c787fd-8hl29    1/1     Running   0          31s
batch-86f5c787fd-z2t7j    1/1     Running   0          31s
postgres-1                1/1     Running   0          19h
worker-576cff47f4-4mg84   1/1     Running   0          19h
worker-576cff47f4-mtkcv   1/1     Running   0          19h
```
Появилось 2 пода batch. <br>
5. Смотрим логи batch:
```
kubectl logs -n shop -l app=batch --tail=15
```
ответ:
```
{"time": "2026-10-07 10:22:56,509", "level": "INFO", "message": "batch started", "name": "batch", "cpu_load": 50, "mem_mb": 30, "sleep_sec": 5}
{"time": "2026-10-07 10:23:02,908", "level": "INFO", "message": "batch tick", "name": "batch", "iteration": 1, "cpu_load": 50, "mem_mb": 30}
{"time": "2026-10-07 10:23:14,798", "level": "INFO", "message": "batch tick", "name": "batch", "iteration": 2, "cpu_load": 50, "mem_mb": 30}
```
6. Распределяем по узлам:
```
kubectl get pods -n shop -o wide
```
ответ:
```
NAME                      READY   STATUS    RESTARTS   AGE     IP            NODE           NOMINATED NODE   READINESS GATES
api-5c7ddcd847-8r85h      1/1     Running   0          2m54s   10.244.1.33   minikube-m02   <none>           <none>
api-5c7ddcd847-l5sw5      1/1     Running   0          3m      10.244.0.13   minikube       <none>           <none>
api-5c7ddcd847-m4bdg      1/1     Running   0          3m7s    10.244.1.31   minikube-m02   <none>           <none>
batch-86f5c787fd-8hl29    1/1     Running   0          3m7s    10.244.0.12   minikube       <none>           <none>
batch-86f5c787fd-z2t7j    1/1     Running   0          3m7s    10.244.1.32   minikube-m02   <none>           <none>
postgres-1                1/1     Running   0          19h     10.244.1.29   minikube-m02   <none>           <none>
worker-576cff47f4-4mg84   1/1     Running   0          19h     10.244.0.8    minikube       <none>           <none>
worker-576cff47f4-mtkcv   1/1     Running   0          19h     10.244.1.16   minikube-m02   <none>           <none>
```
распределение по узлам сработало, все поды живы, ура!  <br>
<br>

## Часть 3 — Реальные числа вместо угадывания
Для понимания реального потребления ресурсов нужно использовать спеациальные утилиты, в нашем случаек krr(Kubernetes Resource Recommender). <br>
Для создания нагрузки будем использовать оператор hey (CLI-инструмент). <br>
1. Устанавливаем hey:
```
sudo apt-get update
sudo apt-get install -y hey
```
2. Запускаем сеть и в другом терминале нагрузку через hey:
```
kubectl port-forward -n shop svc/api 8001:80

hey -z 30s -c 20 -m POST \
  -H "Content-Type: application/json" \
  -d '{"item":"load-test"}' \
  http://localhost:8001/order
```
Флаги: <br>
-z 30s - нагрузка 30 секунд <br>
-c 20 - нагрузка с 20 воркеров одновременно <br>
<br>
ответ:
```
Summary:
  Total:	30.3841 secs
  Slowest:	1.7268 secs
  Fastest:	0.3836 secs
  Average:	1.0041 secs
  Requests/sec:	19.8459
  
  Total data:	17383 bytes
  Size/request:	28 bytes

Response time histogram:
  0.384 [1]	|
  0.518 [12]	|■■■
  0.652 [35]	|■■■■■■■■■
  0.787 [53]	|■■■■■■■■■■■■■■
  0.921 [149]	|■■■■■■■■■■■■■■■■■■■■■■■■■■■■■■■■■■■■■■■■
  1.055 [99]	|■■■■■■■■■■■■■■■■■■■■■■■■■■■
  1.190 [95]	|■■■■■■■■■■■■■■■■■■■■■■■■■■
  1.324 [107]	|■■■■■■■■■■■■■■■■■■■■■■■■■■■■■
  1.458 [35]	|■■■■■■■■■
  1.592 [11]	|■■■
  1.727 [6]	|■■


Latency distribution:
  10% in 0.6966 secs
  25% in 0.8077 secs
  50% in 0.9984 secs
  75% in 1.1967 secs
  90% in 1.3073 secs
  95% in 1.4016 secs
  99% in 1.5989 secs

Details (average, fastest, slowest):
  DNS+dialup:	0.0001 secs, 0.3836 secs, 1.7268 secs
  DNS-lookup:	0.0000 secs, 0.0000 secs, 0.0014 secs
  req write:	0.0001 secs, 0.0000 secs, 0.0007 secs
  resp wait:	1.0026 secs, 0.3834 secs, 1.7244 secs - ждет ответа от сервера почти все время от обработки запроса
  resp read:	0.0013 secs, 0.0000 secs, 0.0957 secs

Status code distribution:
  [200]	603 responses
```
видим что апи обработал все запросы, но медленно. <br> 
Но это лирика в рамках данной лабы, главное, что нагрузка работает.  <br>
 <br>
3. Устанавливаем krr:
```
cd ~
curl -Lo krr.zip https://github.com/robusta-dev/krr/releases/download/v1.28.0/krr-ubuntu-latest-v1.28.0.zip

unzip krr.zip

sudo mv krr /usr/local/bin/
sudo chmod +x /usr/local/bin/krr
```
4. Запускаем krr
```
krr simple -n shop -p http://127.0.0.1:9090
```
ответ:
```
CPU request: 95.0% percentile, limit: unset  #KRR берёт 95-й перцентиль реального потребления
Memory request: max + 15.0%, limit: max + 15.0%  
History: 336.0 hours #смотрит за 2 недели

┏━━━━━━━━┳━━━━━━━┳━━━━━━━┳━━━━━━┳━━━━━━━┳━━━━━━━┳━━━━━━━┳━━━━━━┳━━━━━━━┳━━━━━━┳━━━━━━━┳━━━━━━┳━━━━━━━┓
┃        ┃       ┃       ┃      ┃ Old   ┃       ┃       ┃ CPU  ┃ CPU   ┃ CPU  ┃ Memo… ┃ Mem… ┃ Memo… ┃
┃ Number ┃ Name… ┃ Name  ┃ Pods ┃ Pods  ┃ Type  ┃ Cont… ┃ Diff ┃ Requ… ┃ Lim… ┃ Diff  ┃ Req… ┃ Limi… ┃
┡━━━━━━━━╇━━━━━━━╇━━━━━━━╇━━━━━━╇━━━━━━━╇━━━━━━━╇━━━━━━━╇━━━━━━╇━━━━━━━╇━━━━━━╇━━━━━━━╇━━━━━━╇━━━━━━━┩
│     1. │ shop  │ api   │ 3    │ 6     │ Depl… │ api   │ -27… │ (-90… │ 500m │ -84Mi │ (-2… │ 256Mi │
│        │       │       │      │       │       │       │ (3   │ 100m  │ ->   │ (3    │ 128… │ ->    │
│        │       │       │      │       │       │       │ pod… │ ->    │ uns… │ pods) │ ->   │ 100Mi │
│        │       │       │      │       │       │       │      │ 10m   │      │       │ 100… │       │
├────────┼───────┼───────┼──────┼───────┼───────┼───────┼──────┼───────┼──────┼───────┼──────┼───────┤
│     2. │ shop  │ batch │ 2    │ 0     │ Depl… │ batch │ +49m │ (+24… │ 200m │ +72Mi │ (+3… │ 128Mi │
│        │       │       │      │       │       │       │ (2   │ 100m  │ ->   │ (2    │ 64Mi │ ->    │
│        │       │       │      │       │       │       │ pod… │ ->    │ uns… │ pods) │ ->   │ 100Mi │
│        │       │       │      │       │       │       │      │ 125m  │      │       │ 100… │       │
├────────┼───────┼───────┼──────┼───────┼───────┼───────┼──────┼───────┼──────┼───────┼──────┼───────┤
│     3. │ shop  │ work… │ 2    │ 0     │ Depl… │ work… │ -18… │ (-90… │ 300m │ -56Mi │ (-2… │ 256Mi │
│        │       │       │      │       │       │       │ (2   │ 100m  │ ->   │ (2    │ 128… │ ->    │
│        │       │       │      │       │       │       │ pod… │ ->    │ uns… │ pods) │ ->   │ 100Mi │
│        │       │       │      │       │       │       │      │ 10m   │      │       │ 100… │       │
└────────┴───────┴───────┴──────┴───────┴───────┴───────┴──────┴───────┴──────┴───────┴──────┴───────┘                                        
```
Что видим на примере api:
- CPU request было 200 - пекомендует 10
- CPU limit было 500 - рекомендует убрать
- Memory request было 128 - рекомендует 100
- Memory limit было 256 - рекомендует 100
Аналогично видно для подов batch и worker. <br>
<br>
5. На основе рекомендаций правим values.yaml:
  
```
api:
  resources:
    requests:
      cpu: "10m"          
      memory: "100Mi"     
    limits:
      cpu: "500m"  
      memory: "100Mi" 

worker:
  resources:
    requests:
      cpu: "10m"
      memory: "100Mi"
    limits:
      cpu: "300m"
      memory: "100Mi"

batch:
  resources:
    requests:
      cpu: "125m"
      memory: "100Mi"
    limits:
      cpu: "200m"
      memory: "100Mi"
```
лимит cpu не трогаем, так как в части 8 нам провоцировать throttling.  <br>
helm upgrade: 
```
helm upgrade shop ./shop-chart -n shop
sleep 30
kubectl get pods -n shop
```
```
helm upgrade shop ./shop-chart -n shop
sleep 30
kubectl get pods -n shop
Release "shop" has been upgraded. Happy Helming!
NAME: shop
LAST DEPLOYED: Thu Oct  8 12:13:05 2026
NAMESPACE: shop
STATUS: deployed
REVISION: 4
NAME                      READY   STATUS        RESTARTS   AGE
api-77cc67487f-bkhkk      1/1     Running       0          24s
api-77cc67487f-nbnz5      1/1     Running       0          30s
api-77cc67487f-qztkz      1/1     Running       0          18s
batch-5bd99784f5-hdq8p    1/1     Running       0          30s
batch-5bd99784f5-v5bkk    1/1     Running       0          29s
batch-86f5c787fd-8hl29    1/1     Terminating   0          22h
batch-86f5c787fd-z2t7j    1/1     Terminating   0          22h
postgres-1                1/1     Running       0          42h
worker-576cff47f4-4mg84   1/1     Terminating   0          42h
worker-576cff47f4-mtkcv   1/1     Terminating   0          42h
worker-584b8b74c4-9hrlc   1/1     Running       0          29s
worker-584b8b74c4-rk7cb   1/1     Running       0          30s
```
 <br>
 
## Часть 4 — Распространение критической службы
Так как в части 1 мы уже поравили values.yaml и api-deployment.yaml, после чего обновляли чарт, этот принцип разделения уже применен. Усталось это показать наглядно.  <br>
Смотрим распределение подов api:
```
kubectl get pods -n shop -l app=api -o wide
```
ответ:
```
NAME                   READY   STATUS    RESTARTS   AGE   IP            NODE           NOMINATED NODE   READINESS GATES
api-77cc67487f-bkhkk   1/1     Running   0          35m   10.244.1.36   minikube-m02   <none>           <none>
api-77cc67487f-nbnz5   1/1     Running   0          35m   10.244.0.16   minikube       <none>           <none>
api-77cc67487f-qztkz   1/1     Running   0          34m   10.244.0.17   minikube       <none>           <none>
```
Без spread все 3 api могли оказаться на одном узле — при его падении сервис лёг бы целиком. <br>
 

## Часть 5 — Установить приоритеты и гарантии
Нам нужно проставить подам api, postgres вфсокий приоритет, в случае если кого-то придется выгонять, чтобы они были претендентами в последнюю очередь. <br>
<br>
При необходимости кого-то вытеснить, поды с PriorityClass будут рассматриваться последними. <br>
QoS работает так, что поды Burstable (лимиты выше запросов) выселяются первыми, в отличисе от подов Guaranteed (лимит и запрос равны). <br>
PodDisruptionBudget у подов говорит куберу, что при зачистках нельзя удалять больше X% этих подов, что приведет к тому, что сервис не упадет целиком. <br>
<br>

1. Создаем в папке shop-chart/templates **priorityclass.yaml**
2. В api-deployment.yaml и worker-deployment.yaml добавляем в секцию spec.template.spec:
```
priorityClassName: shop-critical
```
В batch-deployment.yaml:
```
priorityClassName: shop-batch
```
В postgres-cluster.yaml в spec после instances: 1:
```
priorityClassName: shop-critical
```
3. Создаем в папке shop-chart/templates **pdb.yaml** для PodDisruptionBudget с minAvailable: 2 для api и maxUnavailable: 0 для postgres. <br>
4. Обновляем values.yaml: <br>
api — Guaranteed QoS (requests = limits):
```
api:
  resources:
    requests:
      cpu: "100m"
      memory: "100Mi"
    limits:
      cpu: "100m"
      memory: "100Mi"
```
postgres-cluster.yaml аналогично:
```
  resources:
    requests:
      cpu: "100m"
      memory: "128Mi"
    limits:
      cpu: "100m"
      memory: "128Mi"
```
и перед spread:
```
priorityClass:
  enabled: true

podDisruptionBudget:
  enabled: true
```
Helm отрендерит эти объекты и создаст PriorityClass'ы в кластере <br>
5. helm upgrade:
```
helm upgrade shop ./shop-chart -n shop
```
ответ:
```
Release "shop" has been upgraded. Happy Helming!
NAME: shop
LAST DEPLOYED: Thu Oct  8 13:41:36 2026
NAMESPACE: shop
STATUS: deployed
REVISION: 6
```
проверки:
- PriorityClass
```
ubectl get priorityclass | grep shop
```
Видим, что создана 2 класса с разными приоритетами:
```
shop-batch                1000         false            49s   PreemptLowerPriority
shop-critical             1000000      false            49s   PreemptLowerPriority
```
- PDB
```
kubectl get pdb -n shop
```
```
NAME               MIN AVAILABLE   MAX UNAVAILABLE   ALLOWED DISRUPTIONS   AGE
api                2               N/A               1                     2m51s
postgres-primary   1               N/A               0                     43h
```
- QoS-классы
```
kubectl get pods -n shop -o jsonpath='{range .items[*]}{.metadata.name}{"  "}{.status.qosClass}{"\n"}{end}'
```
```
api-7d5d494ff5-6rj7k  Guaranteed
api-7d5d494ff5-cr6kl  Guaranteed
api-7d5d494ff5-f26wp  Guaranteed
batch-6458dbd5d-hmm6r  Burstable
batch-6458dbd5d-jgs7g  Burstable
postgres-1  Guaranteed
worker-858b5f7d96-4th4w  Burstable
worker-858b5f7d96-qc2ft  Burstable
```
 <br>
 
## Часть 6 — Создайте дефицит, уловите упреждение <br>
Preemption — это принудительное вытеснение подов с низким приоритетом, когда высокоприоритетному поду не хватает места.<br>
Смотрим на сколько сейчас используются ресурсы:
```
kubectl describe node minikube | grep -A 20 "Allocated resources"
kubectl describe node minikube-m02 | grep -A 20 "Allocated resources"
```
```
Allocated resources:
  (Total limits may be over 100 percent, i.e., overcommitted.)
  Resource           Requests    Limits
  --------           --------    ------
  cpu                1085m (7%)  700m (5%)
  memory             720Mi (2%)  520Mi (1%)
  ephemeral-storage  0 (0%)      0 (0%)
  hugepages-1Gi      0 (0%)      0 (0%)
  hugepages-2Mi      0 (0%)      0 (0%)
Events:              <none>
Allocated resources:
  (Total limits may be over 100 percent, i.e., overcommitted.)
  Resource           Requests    Limits
  --------           --------    ------
  cpu                935m (6%)   1 (7%)
  memory             898Mi (2%)  1346Mi (4%)
  ephemeral-storage  0 (0%)      0 (0%)
  hugepages-1Gi      0 (0%)      0 (0%)
  hugepages-2Mi      0 (0%)      0 (0%)
Events:              <none>
```
))))))))))  <br>
ну я на глаз ставила..  <br>
Щас нахимичим в values.yaml, плюнем, склеим скотчем и все будет.  <br>
 <br>
Делаам 30 реплик и поднимаем запросы у batch:
```
batch:
  replicas: 100
  resources:
    requests:
      cpu: "500m"    
      memory: "500Mi"   
    limits:
      cpu: "700m"
      memory: "600Mi"
```
ну и апгрейдим хелм:
```
helm upgrade shop ./shop-chart -n shop
sleep 30
kubectl get pods -n shop
```
```
Release "shop" has been upgraded. Happy Helming!
NAME: shop
LAST DEPLOYED: Thu Oct  8 22:41:02 2026
NAMESPACE: shop
STATUS: deployed
REVISION: 9
TEST SUITE: None
     25 Pending
     53 Running
```
Проверяем что api/worker/postgres живы при этом:
```
kubectl get pods -n shop | grep -v -E "^batch"
```
```
NAME                      READY   STATUS    RESTARTS   AGE
api-7d5d494ff5-6rj7k      1/1     Running   0          9h
api-7d5d494ff5-cr6kl      1/1     Running   0          9h
api-7d5d494ff5-f26wp      1/1     Running   0          9h
postgres-1                1/1     Running   0          9h
worker-858b5f7d96-4th4w   1/1     Running   0          9h
worker-858b5f7d96-qc2ft   1/1     Running   0          9h
```
Смотрим события preemption:
```
kubectl get events -n shop --sort-by=.lastTimestamp | grep -i preempt | tail -10
```
```
2m25s       Warning   FailedScheduling                  pod/batch-778c68dbc-f9m69              0/2 nodes are available: 2 Insufficient cpu. preemption: 0/2 nodes are available: 2 Insufficient cpu.
2m25s       Warning   FailedScheduling                  pod/batch-778c68dbc-dqws8              0/2 nodes are available: 2 Insufficient cpu. preemption: 0/2 nodes are available: 2 Insufficient cpu.
2m25s       Warning   FailedScheduling                  pod/batch-778c68dbc-mpkqt              0/2 nodes are available: 2 Insufficient cpu. preemption: 0/2 nodes are available: 2 Insufficient cpu.
2m25s       Warning   FailedScheduling                  pod/batch-778c68dbc-979lz              0/2 nodes are available: 2 Insufficient cpu. preemption: 0/2 nodes are available: 2 Insufficient cpu.
2m25s       Warning   FailedScheduling                  pod/batch-778c68dbc-8vkns              0/2 nodes are available: 2 Insufficient cpu. preemption: 0/2 nodes are available: 2 Insufficient cpu.
2m25s       Warning   FailedScheduling                  pod/batch-778c68dbc-mdh8t              0/2 nodes are available: 2 Insufficient cpu. preemption: 0/2 nodes are available: 2 Insufficient cpu.
2m25s       Warning   FailedScheduling                  pod/batch-778c68dbc-75hkv              0/2 nodes are available: 2 Insufficient cpu. preemption: 0/2 nodes are available: 2 Insufficient cpu.
2m25s       Warning   FailedScheduling                  pod/batch-778c68dbc-hdxpn              0/2 nodes are available: 2 Insufficient cpu. preemption: 0/2 nodes are available: 2 Insufficient cpu.
2m25s       Warning   FailedScheduling                  pod/batch-778c68dbc-j47fm              0/2 nodes are available: 2 Insufficient cpu. preemption: 0/2 nodes are available: 2 Insufficient cpu.
2m25s       Warning   FailedScheduling                  pod/batch-778c68dbc-jjv68              0/2 nodes are available: 2 Insufficient cpu. preemption: 0/2 nodes are available: 2 Insufficient cpu.
```
Видим, что не 1 из двух узлов не принял поды из-за перегруза CPU. Scheduler пытался вытеснить кого-нибудь, но не смог.<br>
Собстна говоря получили че хотели.<br>
<br>
Теперь увеличиваем реплики api и апргейдим helm:
```
helm upgrade shop ./shop-chart -n shop
sleep 30
```
```
Release "shop" has been upgraded. Happy Helming!
NAME: shop
LAST DEPLOYED: Thu Oct  8 22:47:45 2026
NAMESPACE: shop
STATUS: deployed
REVISION: 10
```
Смотрим события preemption:
```
kubectl get events -n shop --sort-by=.lastTimestamp | grep -i preempt | tail -10
```
И там все грустно, потому что упал kyverno, смотрим события в kyverno:
```
kubectl get events -n kyverno | tail -20
```
```
14m  Normal  Preempted  pod/kyverno-admission-controller-769b8f7647-gv6rf
     Preempted by pod 347a73d4-5bee-4340-84ec-de7dcb281cdf on node minikube-m02

14m  Normal  Preempted  pod/kyverno-background-controller-86d8df7447-n4wcf
     Preempted by pod 347a73d4-5bee-4340-84ec-de7dcb281cdf on node minikube-m02

14m  Normal  Preempted  pod/kyverno-cleanup-controller-86c886ffff-bffbz
     Preempted by pod 347a73d4-5bee-4340-84ec-de7dcb281cdf on node minikube-m02

14m  Normal  Preempted  pod/kyverno-reports-controller-57c7978d69-4jbfs
     Preempted by pod 347a73d4-5bee-4340-84ec-de7dcb281cdf on node minikube-m02
```
под kyverno был вытеснен другим подом. <br>
Так то задача поставленная достигнута.. хахах но чуток не туда сработало. <br>
Поэтому уменьшам реплики batch до 60 и апгрейдим.
```
helm upgrade shop ./shop-chart -n shop
sleep 40
kubectl get pods -n shop | grep batch | awk '{print $3}' | sort | uniq -c
Release "shop" has been upgraded. Happy Helming!
NAME: shop
LAST DEPLOYED: Thu Oct  8 23:16:14 2026
NAMESPACE: shop
STATUS: deployed
REVISION: 12
TEST SUITE: None
      9 Pending
     51 Running
```
но... тут снова преколы:
```
maria@ubuntu-dev:~/itmo-devops-labs/lab4$ kubectl get pods -n kyverno
NAME                                             READY   STATUS             RESTARTS   AGE
kyverno-admission-controller-769b8f7647-kqhdw    1/1     Running            0          36m
kyverno-background-controller-86d8df7447-p45kq   0/1     Pending            0          80s
kyverno-cleanup-controller-86c886ffff-m42x6      0/1     ImagePullBackOff   0          80s
kyverno-migrate-resources-jchc8                  0/1     ImagePullBackOff   0          9m29s
kyverno-reports-controller-57c7978d69-b2s25      1/1     Running            0          36m
maria@ubuntu-dev:~/itmo-devops-labs/lab4$ 
```
уже не все ноль, но тоже так себе... Admission-controller живет, так что политики работают (проверено на bad поде), но самое главно, что все api поды живы:
```
NAME                   READY   STATUS    RESTARTS   AGE
api-7d5d494ff5-5lrrl   1/1     Running   0          12m
api-7d5d494ff5-6rj7k   1/1     Running   0          9h
api-7d5d494ff5-7jzpt   1/1     Running   0          12m
api-7d5d494ff5-7lhds   1/1     Running   0          12m
api-7d5d494ff5-82grh   1/1     Running   0          12m
api-7d5d494ff5-cr6kl   1/1     Running   0          9h
api-7d5d494ff5-f26wp   1/1     Running   0          9h
api-7d5d494ff5-jprsz   1/1     Running   0          12m
api-7d5d494ff5-wcmqk   1/1     Running   0          12m
api-7d5d494ff5-zggr4   1/1     Running   0          12m
```
Смотрим события preemption:
```
6m19s       Warning   FailedScheduling                  pod/batch-778c68dbc-n4l8c      0/2 nodes are available: 2 Insufficient cpu. preemption: found a potential placement for pod on node minikube-m02, preempting 2 victims
6m18s       Warning   FailedScheduling                  pod/batch-778c68dbc-n4l8c      0/2 nodes are available: 2 Insufficient cpu. preemption: not eligible due to a terminating pod on the nominated node.
```
там большой вывод, но нам важны эти 2 события, где scheduler нашёл узел, где вытеснит 2 подов batch. <br>
Возвращаем все на место.<br>
<br>

## Часть 7 — Давление памяти, приказ о выселении 
В прошлой части создавали дефицит по requests, а в этой дефицит по реальному использованию памяти. <br>
<br>

## Часть 8 — Докажите SLA под загрузкой
Даже когда batch создает нагрузку, соединение api - postgres должно работать и отдавать ответы клиенту.<br>
1. Так как api медленный у нас, сформулируем SLA: «95% POST /order < 10 сек, доля ошибок < 20%, RPS ≥ 3».<br>
2. Pfgecrftv port-forward:
```
kubectl port-forward -n shop svc/api 8001:80
```
2. Создаем нагрузка hey:
```
hey -z 60s -c 20 -m POST \
  -H "Content-Type: application/json" \
  -d '{"item":"sla-nodeport"}' \
  http://10.99.0.2:30784/order
```
```
Summary:
  Total:	65.2188 secs
  Slowest:	17.6003 secs
  Fastest:	0.0043 secs
  Average:	4.3368 secs
  Requests/sec:	5.1519
  
  Total data:	8208 bytes
  Size/request:	28 bytes

Response time histogram:
  0.004 [1]	|
  1.764 [43]	|■■■■■■■■■■■■■■■■■
  3.524 [61]	|■■■■■■■■■■■■■■■■■■■■■■■■
  5.283 [100]	|■■■■■■■■■■■■■■■■■■■■■■■■■■■■■■■■■■■■■■■■
  7.043 [55]	|■■■■■■■■■■■■■■■■■■■■■■
  8.802 [17]	|■■■■■■■
  10.562 [1]	|
  12.322 [0]	|
  14.081 [0]	|
  15.841 [7]	|■■■
  17.600 [3]	|■


Latency distribution:
  10% in 0.1948 secs
  25% in 2.9004 secs
  50% in 4.1991 secs
  75% in 5.4922 secs
  90% in 7.0983 secs
  95% in 8.2959 secs
  99% in 16.7959 secs

Details (average, fastest, slowest):
  DNS+dialup:	0.0002 secs, 0.0043 secs, 17.6003 secs
  DNS-lookup:	0.0000 secs, 0.0000 secs, 0.0000 secs
  req write:	0.0001 secs, 0.0000 secs, 0.0005 secs
  resp wait:	4.3363 secs, 0.0037 secs, 17.6000 secs
  resp read:	0.0002 secs, 0.0000 secs, 0.0010 secs

Status code distribution:
  [200]	240 responses
  [500]	48 responses
``` 
SLA держится:  <br>
48 из 288 - 16.7% ошибок <br>
p95 latency - 8.3 сек <br>
RPS - 5.15 <br>



api не тянет такую нагрузку, так как на каждый запрос открывает новое соединение к Postgres. При 20 воркерах — соединения накапливаются, исчерпывают max_connections = 100
```
kubectl exec -n shop postgres-1 -- \
  bash -c 'PGPASSWORD=shop psql -h 127.0.0.1 -U shop -d shop -c "SELECT usename, count(*) FROM pg_stat_activity GROUP BY usename;"'

kubectl exec -n shop postgres-1 -- \
  bash -c 'PGPASSWORD=shop psql -h 127.0.0.1 -U shop -d shop -c "SHOW max_connections;"'
```
```
Defaulted container "postgres" out of: postgres, bootstrap-controller (init)
 usename  | count 
----------+-------
          |     8
 postgres |     1
 shop     |     1
(3 rows)

Defaulted container "postgres" out of: postgres, bootstrap-controller (init)
 max_connections 
-----------------
 100
(1 row)
```
3. Смотрим графики в Prometheus: <br>
RPS: <br>
```
sum(rate(http_requests_total{namespace="shop", handler="/order"}[1m]))
```
<img width="1361" height="1354" alt="изображение" src="https://github.com/user-attachments/assets/a3fd0683-30b5-457f-867e-8f5991ba244c" /> <br>
Несколько пиков - несколько попыток нагрузки через hey. Пик ~4.4 RPS - последний. RPS ≥ 3 выполнен.
<br>
p95 latency: 
``` 
histogram_quantile(0.95, 
  sum(rate(http_request_duration_seconds_bucket{namespace="shop", handler="/order"}[1m])) by (le)
)
```
<img width="1361" height="1354" alt="изображение" src="https://github.com/user-attachments/assets/351d7cd2-029b-44ba-ab78-0008e430f434" /> <br>
Линия на 1 сек только в моменты нагрузки. Prometheus занижает реальные значения из-за дефолтных бакетов.
<br>
доля 5xx: <br>
```
(
  sum(rate(http_requests_total{namespace="shop", status=~"5.."}[1m]))
  /
  sum(rate(http_requests_total{namespace="shop"}[1m]))
) or vector(0)
```
<img width="1361" height="1354" alt="изображение" src="https://github.com/user-attachments/assets/13d5f6a1-8983-4263-8483-6218d836e991" /> <br>
17% ~ совпадает с hey
<br>

## Часть 9 — Мониторинг
