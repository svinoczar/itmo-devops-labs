# DevOps Course

## 0. Свой сервис
Первым делом был написан main.py на Flask (самостоятельно, так как мы УЧЕМСЯ) <br>
<img width="100" height="100" alt="image" src="https://github.com/user-attachments/assets/0f83310e-c95d-40ff-8ade-e53e611669e7" />

Все как по ТЗ с тремя эндпоинтами. 

## 1. Запускаем напрямую
Запустила локально через WSLечку (не бейте пж у меня ужк стоит dual boot) 
```python
python main.py #в venv ~/itmo-devops/lab1/.venv/)
```
Открыла в браузере:
<img width="1280" height="195" alt="image" src="https://github.com/user-attachments/assets/326af6fa-390d-4c1d-aa0d-5f882859b4e8" />
Все работает я молодец :) 
Фиксим PID на хосте:
<img width="1280" height="200" alt="image" src="https://github.com/user-attachments/assets/34ffc3aa-3935-487d-b1af-0648a3f63aa8" />

_* фан факт для читателей 
Я жеско не продвинутый юзер гитхаба и только сейчас узнала, что чтобы посмотреть изменения не обязательно комитить каждый раз ридми... есть вот такая кнопка:_ <br>
<img width="100" height="20" alt="image" src="https://github.com/user-attachments/assets/23776d1d-708b-4e7f-9fbf-9d1e2d832455" /> 
_И мне не стыдно._

## 2. namespaces
### PID
Изолируем номер процесса:
```python
sudo unshare --pid --fork --mount-proc bash
```
Тут ID 1:
<img width="965" height="65" alt="image" src="https://github.com/user-attachments/assets/36b3b450-3820-4eb0-9a26-2f4ac820ab19" />
Смотрим список процессов и видим что тут я с другим айдишником:
<img width="1418" height="470" alt="image" src="https://github.com/user-attachments/assets/ab071982-ccf6-465b-a156-efdda72a20d2" />
15143 — unshare создаёт namespace <br>
15144 — bash внутри namespace 

### Mount
Создаем изолированную файловую систему и примонтируем tmpfs в /mnt/test:
```python
sudo unshare --mount bash
sudo mkdir -p /mnt/test
mount -t tmpfs tmpfs /mnt/test
df -h | grep test
```
Внутри namespace появилось монтирование папок:
<img width="1404" height="168" alt="image" src="https://github.com/user-attachments/assets/046c81b3-6081-4f89-b763-76eca0eb15ea" />
А снаружи ничего не видно:
<img width="1060" height="83" alt="image" src="https://github.com/user-attachments/assets/5bffd3fa-09a6-4abd-9851-09641aac2ddd" />

### Net
Изолируем сетевые интерфейсы.
Фиксируем интерфейсы снаружи:
<img width="1378" height="562" alt="image" src="https://github.com/user-attachments/assets/c124eb67-c53d-43aa-ada8-a0beacdf36e1" />
и запускаем namespace:
```python
sudo unshare --net bash
```
Сеть внутри пустая:
<img width="1234" height="95" alt="image" src="https://github.com/user-attachments/assets/556243d9-f870-4029-a7b6-887fc9c0318a" />
соответственно и пинг не работает:
<img width="1160" height="65" alt="image" src="https://github.com/user-attachments/assets/2f710627-4a88-43ff-81eb-68f3c10c2af4" />

### UTS
Изолируем хост:
```python
sudo unshare --uts bash
```
И меняем имя хоста:
<img width="1167" height="200" alt="image" src="https://github.com/user-attachments/assets/31f51c9b-c965-47c9-b2fd-0c5584a1252f" />
Снаружи не изменился:
<img width="853" height="68" alt="image" src="https://github.com/user-attachments/assets/9af7edc2-be33-4dac-8441-215bade839b8" />

### IPC 
Изолируем "межпроцессорные процессы". 
Создадим сначала процесс снаружи для сравнения:
<img width="1184" height="253" alt="image" src="https://github.com/user-attachments/assets/0d74212b-b76f-41bb-b901-2a9696d56eef" />
Внутри он не виден:
<img width="1213" height="161" alt="image" src="https://github.com/user-attachments/assets/ed9a3c7a-4827-4a11-8f29-1093219e24b5" />
Создаем процесс внутри:
<img width="1196" height="244" alt="image" src="https://github.com/user-attachments/assets/6515b753-d803-4d71-8e38-8553c7b5d902" />
у него shmid 0, так как он первый в этом IPC namespace.
Выходим и видим, что внутренний процесс снаружи не отображается:
<img width="1209" height="164" alt="image" src="https://github.com/user-attachments/assets/73d33041-705e-4cab-9358-ecb8a9caaece" />

### User 
Изолируем пользователя 💀 <br>
Смотрим диапазоны UID/GID, которые можно отображать:
<img width="1074" height="75" alt="image" src="https://github.com/user-attachments/assets/1c0a8f74-7401-4dd6-a581-3041935c0e25" />
пользователь user может отображать 65536 UID, начиная с 100000.
Запускааем:
```python
unshare --user --map-root-user bash
```
Мы внутри:
<img width="1011" height="70" alt="image" src="https://github.com/user-attachments/assets/c210a79a-e5dd-4d72-abd6-37b00fcf6bab" />
Запускаем внутри процесс командой sleep 600 и смотрим снаружи:
<img width="1389" height="278" alt="image" src="https://github.com/user-attachments/assets/aa574001-4780-4473-ae69-cd8b5a8c4112" />
и видим, что процесс запущен обыкновенным пользователем user.
Также при попытках изнутри изменить системное время, у нас ничего не получается:
<img width="1151" height="101" alt="image" src="https://github.com/user-attachments/assets/1919bd76-518a-4022-b444-f239d1974d7f" />

## 3. cgroups
Смотрим доступные контроллеры:
<img width="1284" height="72" alt="image" src="https://github.com/user-attachments/assets/45a831b2-ed8e-41b0-8bec-d5e229a5bf0b" />
- memory — для лимита памяти
- cpu — для лимита ядра
- pids — для лимита числа процессов <br>
Они же доступны и для детей папки
<img width="1357" height="66" alt="image" src="https://github.com/user-attachments/assets/fa7597bc-e73e-457c-86c1-471abb3d7f80" />

Создаем изоляцию ресурсов:
```Python
sudo mkdir /sys/fs/cgroup/mygroup
```
Просматриваем содержимое созданной дерриктории:
<img width="1432" height="994" alt="image" src="https://github.com/user-attachments/assets/b40a4e50-4588-42a8-b6c9-10c4896def7d" />
Нужные нам:
- memory.max — лимит памяти
- memory.current — текущее потребление памяти
- cpu.max — лимит ядра
- cpu.stat — статистика CPU, включая throttling
- pids.max — лимит числа процессов
- cgroup.procs — список PID внутри  <br>

Перемещаем процесс в изолированную деррикторию:
<img width="1388" height="177" alt="image" src="https://github.com/user-attachments/assets/400d0e66-78ec-46b0-b6ce-64f557a04be8" />
### 1. Лимит памяти
В процессе выполнения столкнулась с проблемой, что WSL не учитывает память в cgroup. При попытках убить процесс превышением памяти меня ждало разочарование..( Фиксирование превышения было, но процесс не убивался.
Попробуем через докер на другом этапе.

Возвращаюсь сюда из части 6 чтобы сделать OOM через doker. 
Запускаю контейнер с лимитом по памяти:
```Python
docker run -d --name oom-test \ #контейнер и его имя
  --memory=50m \ #указывает лимит памяти
  -p 5004:5000 \ #указываем порт
  lab1-api:v2 #собери по этому образу
```
Проверяем установленный лисит:
<img width="1398" height="101" alt="image" src="https://github.com/user-attachments/assets/a0deb76e-ec8d-49f0-a4bf-89ec2688ce08" />
общий лимит - 150мб,  так как 50мб лимит оперативной памяти, который мы указали + лимит общей памяти по умолчанию наши 50мб * 2 <br> <br>
То есть тестить ООМ нужно на 150+ мб. <br>
Делаем запрос на 200мб:
<img width="1408" height="208" alt="image" src="https://github.com/user-attachments/assets/b5efde42-24f0-4b3a-b6d7-f9edd022e394" />
до превышения сервер работал, так как мы получили ответ ок от /health, а после превышения curl подключился к серверу, отправил запрос, сервер начал обрабатывать — выделять 200 МБ. Ядро убило процесс до того, как он успел ответить и соединение оборвалось без ответа.
И финальная проверка на OOM:
<img width="1404" height="108" alt="image" src="https://github.com/user-attachments/assets/f3abc6fd-6f52-4902-83b7-2a26b6e04cfe" />
видим, что контейнер убит, статус OOM true.

### 2. Лимит CPU
Устанавливаем лимит CPU 50% ядра:
```Python
sudo sh -c 'echo "50000 100000" > /sys/fs/cgroup/mygroup/cpu.max'
```
Спасибо интернету за информационную справку:
- 50000 — квота в микросекундах. 50 000 мкс = 50 мс.
- 100000 — период в микросекундах. 100 000 мкс = 100 мс. <br>
Итого: 50 мс работы за каждые 100 мс = 50% одного ядра.
<img width="1281" height="68" alt="image" src="https://github.com/user-attachments/assets/c6032efd-f2fb-44af-9423-2adc183401a1" />
Запрашиваем:
<img width="1264" height="93" alt="image" src="https://github.com/user-attachments/assets/63251116-c07f-4776-96e9-556a83159b60" />
и тишан была мне ответом...
Нагружаем CPU для проверки лимита и смотрим цифры:
<img width="1359" height="69" alt="image" src="https://github.com/user-attachments/assets/3e099e0b-5217-43dc-a812-fbb12bf27090" />
%CPU = 50.0 — процесс ровно 50% одного ядра <br>
Лимит работает!

### 3. Лимит Pids
Запускаем внутри cgroup shell, чтобы все дальнейшие процессы попадали сразу в него
```Python
sudo sh -c 'echo $$ > /sys/fs/cgroup/mygroup/cgroup.procs; exec bash'
```
Задаем лимит:
<img width="1399" height="128" alt="image" src="https://github.com/user-attachments/assets/0a6338a6-7d99-432e-96ec-fe4c878a8c65" />
Запускаем вредоносную пакость:
```Python
sudo sh -c 'echo $$ > /sys/fs/cgroup/mygroup/cgroup.procs; exec bash'
```
<img width="1410" height="886" alt="image" src="https://github.com/user-attachments/assets/02a5f92e-cdfd-4713-a54d-32b04b8ffafd" />
19 слипов успешные, а 20-й и дальше выдали ошибку.
Лимит сработал быстро:
<img width="1402" height="102" alt="image" src="https://github.com/user-attachments/assets/52512dc0-3783-4b0f-824b-46a68a96e248" />
ошибку выдало с 5 попытки, что видно на последних двух фото.

## 4. Права
### 1. Capabilities 
Capabilities разбивают права root на отдельные флаги.
Посмотрим на примере изменения даты и времени:
Через sudo запускаю команду от root. У root есть все capabilities, включая CAP_SYS_TIME. Поэтому время меняется:
<img width="1373" height="315" alt="image" src="https://github.com/user-attachments/assets/4d9714d8-531f-498a-bedb-c710e1d68630" />
Root без CAP_SYS_TIME не может менять время, хотя остаётся root.
### 2. Seccomp 
изолирует набор системных вызовов, доступных процессу. Заблокированные вызовы ядро не выполняет - процесс получает ошибку.
Запускаем bash как systemd-юнит с seccomp-фильтром, запрещающим вызов mkdir:
<img width="1408" height="203" alt="image" src="https://github.com/user-attachments/assets/91b65ae6-acf5-44be-b096-d61b9c3dfbff" />
Bad system call — ядро прервало процесс.
Снаружи без фильтра команда работает:
<img width="1402" height="106" alt="image" src="https://github.com/user-attachments/assets/148bb8d2-282a-4bdb-957f-9f3970f705b2" />

## 5. Собираем Docker
Создаем mydocker.sh.
- В начале скрипта помещаем проверку на root. Это нужно, чтобы работали все необходимые команды. 
- Прописываем лимиты памяти, CPU, процессов. Проверочка:
<img width="1413" height="205" alt="image" src="https://github.com/user-attachments/assets/5b29d0d4-3401-412e-b2bd-c1e6914883bb" />
- Прописываем запуск в namespace: свой hostname, IPC, монтирования, сеть, UTS, переход в папку проекта и активацию venv c запуском сервера
  
```Python
    hostname mydocker-container #меняем имя хоста
    ip link set lo up #поднимаем lo для проверки /health
    echo $$ > /sys/fs/cgroup/mydocker/cgroup.procs #записываем PID 
    cd /home/user/itmo-devops/lab1 #переходим в папку проекта
    source .venv/bin/activate #активируем venv
    python main.py & #запускаем сервер
    SERVER_PID=$! #записываем PID фонового процесса
    trap "kill $SERVER_PID 2>/dev/null; exit" INT TERM #обработчики сигналов
    sleep 2 #пауза
    echo "[mydocker] testing /health:" 
    curl -s http://localhost:5000/health || echo "FAIL" #проверка /health
    echo "" 
    echo "[mydocker] server PID (inside): $SERVER_PID" #выводит PID
    wait $SERVER_PID #ожидает завершения процесса
```
Тестим:
<img width="1372" height="516" alt="image" src="https://github.com/user-attachments/assets/7000eaf4-d65c-4c43-974e-ad4b778f6d36" />
все успешно запускается, проверка /health дала валидный ответ.
Namespace процесса init (PID 1) на хосте:
<img width="1371" height="475" alt="image" src="https://github.com/user-attachments/assets/6e4497d4-8dbc-4903-8b1d-87d0e86f89dd" />
Namespace процесса сервиса (запущен через mydocker.sh):
<img width="1402" height="489" alt="image" src="https://github.com/user-attachments/assets/e0a7f5f6-98cc-4432-a7c9-08ce0367d2a1" />
- 5 namespace изолированы (IPC, mount, net, PID, UTS): у сервиса **свои ID**, отличные от хоста.
- 3 namespace не изолированы (cgroup, time, user): ID **совпадают с хостом**.

## 6.Dockerfile
### 1. single-stage
Прописываем докерфайл для single-stage и запускаем его командой:
```Python
docker build -t lab1-api:v1 .
```
Образ успешно создан:
<img width="1395" height="1093" alt="image" src="https://github.com/user-attachments/assets/bb6d06f9-8127-4ce7-9a5d-344f4b035be3" />
<img width="1391" height="82" alt="image" src="https://github.com/user-attachments/assets/7b763aee-f2e1-4471-8712-f554f1a1ecda" />
Запускаем контейнер на основе образа и видим его IDшник:
<img width="1400" height="108" alt="image" src="https://github.com/user-attachments/assets/5845a7e1-3a23-4cac-b9e6-4c29a5c74925" />
Сервер рабочий:
<img width="1398" height="125" alt="image" src="https://github.com/user-attachments/assets/40e73b3d-78b8-404a-b27d-c1eb4db854d1" />
здесь /health отвечает снаружи (с хоста), так как docker настроил сеть и пробросил порт.
Смотрим имя хоста:
<img width="1396" height="84" alt="image" src="https://github.com/user-attachments/assets/af0249d3-8f23-475a-a168-432a466f4554" />
docker присваивает hostname = ID контейнера, если не задан.

При сборке Dockerfile в отличие от mydocker.sh:
- Namespaces и Cgroups создаются автоматически
- своя ФС из образа
- рабочая сеть с проброшенными портами
- образ работает не только на твоем компе
- остановка другой командой (docker stop,  а не Ctrl+C)

### 2. multi-stage
Проблема single сборки в том, что  в образ попадает всё, что мы делали при сборке: временные установочные файлы, кэш pip и тп. <br><br>
Поэтому прописываем dockerfile для multi-stage сборки: <br>
Этап 1 (builder): тяжёлый. Устанавливает зависимости куда-то в отдельную папку. <br>
Этап 2 (final): лёгкий. Копирует только готовые зависимости из builder'а.
Сборка успешна:
<img width="1423" height="850" alt="image" src="https://github.com/user-attachments/assets/f442a2e1-0aa8-40b6-be83-263fb0e61e9f" />
Запустим контейнер на основе второго образа:
<img width="1403" height="209" alt="image" src="https://github.com/user-attachments/assets/c4f0be24-1cb9-4e11-b165-931258ccb0bf" />
видим IDшник, ответ по запросу валидный.
Сравним размеры от 1 и 2 варианта сборки:
<img width="1391" height="112" alt="image" src="https://github.com/user-attachments/assets/dd2b91c5-0f4c-4239-baf0-7f7bd3ccc926" />
multi-stage сборка экономит память.
Посмотрим слои:
от 1 сборки
<img width="1406" height="1193" alt="image" src="https://github.com/user-attachments/assets/ee2a8767-99b4-409d-85ea-6a91f3db1b6b" />
от 2 сборки
<img width="1400" height="1121" alt="image" src="https://github.com/user-attachments/assets/b70f39e2-0203-481c-964d-2a8f2c7c8966" />
v1: слой pip install = 5.3 МБ. В нём — пакеты + кэш pip + метаданные установки.
v2: слой COPY /install = 0.66 МБ. Только пакеты. Кэш и метаданные остались в builder-этапе — в финальный образ не попали.

### 3.Volumes 
Эфемерность контейнера решается томами. Важную инфу кладут отдельно от контейнера, чтобы случайно не удалить ее,  ведь при удалении контейнер забирает в могилу все, что в нем было.

1. Без тома
  Запускаем контейнер с входом в него (добавляем в конце bash):
  <img width="1395" height="115" alt="image" src="https://github.com/user-attachments/assets/f1cee998-85dd-4867-9129-272e3bb618e2" />
  Создаем внутри контейнера файл:
  <img width="1090" height="105" alt="image" src="https://github.com/user-attachments/assets/901fefc9-3136-4323-b7de-6fea03954608" />
  Выходим из контейнера и удаляем его.
  Создаем новый контейнер из того же образа и видим:
  <img width="1423" height="206" alt="image" src="https://github.com/user-attachments/assets/e6f0fc8a-0914-41e5-8596-aa2dc1d1e34e" />
  что этого файла больше не существует. При удалении контейнера он был удален вместе с ним.

2. С томом
    Создаем том:
  ```Python
  docker volume create mydata
  ```
  И запускаем контейнер с подключением тома:
  ```Python
  docker run -it --name vol-with-volume -v mydata:/data lab1-api:v2 bash
  ```
  Создаем файл, но уже не в директории контейнера, а в томе:
  <img width="1138" height="268" alt="image" src="https://github.com/user-attachments/assets/ffcfd3d2-a584-4673-b060-1855e080b97d" />
  Выходим, удаляем, создаем, заходим и видим, что файл на месте:
  <img width="1395" height="378" alt="image" src="https://github.com/user-attachments/assets/a2413777-6951-4eb1-b140-eba6959a3b58" />

## 7. gVisor 
gVisor содержит собственное ядро, так называемый userspace-kernel, который является промежуточным звеном между приложением и ядром компьютера и обрабатывает большую часть обращений, а к настоящему ядру приложение обращается через очень ограниченный набор вызовов.  <br><br>
Более изолированным его считают за счет:<br>
- ограничение вызовов к настоящему ядру за счет обработки большинства вызовов userspace-kernel => снижается поверхность атаки
- даже при успешной атаки взломщик попадает в userspace, что значительно увеличивает трудозатраты на попытки добраться до настоящего ядра
- он контролирует вызываемые к настоящему ядру функции и не допускает "уязвимые" <br>

Да, мы можем поставить изоляцию: namespaces, cgroups, seccomp, capabilities, но.. <br>
в любом случае у контейнера с хостом остается единое ядро, так как у контейнера ядра нет, он переиспользует ядро хоста. Даже с учетом всех изоляций контейнер не может быть защищен от багов самого ядра, поэтому это предел изоляции контейнера. Полной изоляции можно будет добиться только ВМ, но как мы обсуждали на парах, этот метод нерентабельный в отношении использования памяти. <br>

В таком случае gVisor - компромисс. Легче ВМ и выше изоляция, чем у просто контейнера.

runc установлен
<img width="1088" height="111" alt="image" src="https://github.com/user-attachments/assets/3439c812-5dbf-4b43-be1a-b90ddab61b16" />
прописан в JSON докера
<img width="832" height="537" alt="image" src="https://github.com/user-attachments/assets/8188ea65-4aff-4087-8649-468cdf266701" />
но докер его не видит
<img width="1383" height="98" alt="image" src="https://github.com/user-attachments/assets/6b97c10f-1f6e-4f40-a554-9dc8a490bcec" />
ошибка запуска
<img width="1541" height="105" alt="image" src="https://github.com/user-attachments/assets/833f8cda-7f1b-402c-b888-7642ee6d62e1" />


