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
NAME                      READY   STATUS    RESTARTS        AGE
api-676898b8-4f4vc        1/1     Running   9 (5m35s ago)   22m
api-676898b8-8fzgt        1/1     Running   9 (5m57s ago)   22m
api-676898b8-mvfpz        1/1     Running   9 (6m24s ago)   22m
postgres-1                1/1     Running   0               3m41s
worker-576cff47f4-4mg84   1/1     Running   0               22m
worker-576cff47f4-mtkcv   1/1     Running   0               22m
NAME       AGE   INSTANCES   READY   STATUS                     PRIMARY
postgres   14m   1           1       Cluster in healthy state   postgres-1
```
Готово к работе.

## Часть 0 — Партийный сервис

## Часть 1 — Выберите механизм распространения

## Часть 2 — Плотное скопление

## Часть 3 — Реальные числа вместо угадывания

## Часть 4 — Распространение критической службы

## Часть 5 — Установить приоритеты и гарантии

## Часть 6 — Создайте дефицит, уловите упреждение

## Часть 7 — Давление памяти, приказ о выселении

## Часть 8 — Докажите SLA под загрузкой

## Часть 9 — Мониторинг
