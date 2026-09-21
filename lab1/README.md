# The most ✨*aesthetic*✨ containerization and orchestration lab 1 report

## Часть 0
> Пишем тестовый http сервис с тремя ендпоинтами`  

Тестовый сервис Java API (Java 21)

![hot-dog](sources/1.png)

Сборка и запуск:

```bash
mvn package
java -jar target/api-1.0.0.jar
```

Сервис запускается на порту 8080

Ендпоинты:

```shell
curl localhost:8080/health
curl 'localhost:8080/eat?mb=100'
curl localhost:8080/burn
```

`/eat` увеличивает RSS на указанное число мегабайт и удерживает память до конца выполнения программы.  
`/burn` загружает ядро на 100% и не завершает HTTP запрос. 

## Часть 1
> Просто запускаем сервис и смотрим, что все работает + находим PID процесса.

Оно работает (клянусь):  

![/healthcheck](sources/2.png)
![ps aux](sources/3.png)

PID: 595098

## Часть 2
> Изолируем наш процесс по namespaces и проверяем, что все работает как надо

Сначала посмотрим к каким неймспейсам есть доступ у сервиса изначально. Используем `ls` чтобы просмотреть содержимое /proc/<pid нашего процесса>/ns. В /proc лежат все процессы в виде директорий, названных по pid, в ns - соответственно доступные неймспейсы.
```bash
ls -l /proc/595098/ns/
```  
Вывод:
```bash
total 0
lrwxrwxrwx 1 czar czar 0 Sep 15 16:59 cgroup -> 'cgroup:[4026531835]'
lrwxrwxrwx 1 czar czar 0 Sep 15 16:59 ipc -> 'ipc:[4026531839]'
lrwxrwxrwx 1 czar czar 0 Sep 14 15:13 mnt -> 'mnt:[4026531832]'
lrwxrwxrwx 1 czar czar 0 Sep 15 16:59 net -> 'net:[4026531833]'
lrwxrwxrwx 1 czar czar 0 Sep 15 16:59 pid -> 'pid:[4026531836]'
lrwxrwxrwx 1 czar czar 0 Sep 15 16:59 pid_for_children -> 'pid:[4026531836]'
lrwxrwxrwx 1 czar czar 0 Sep 15 16:59 time -> 'time:[4026531834]'
lrwxrwxrwx 1 czar czar 0 Sep 15 16:59 time_for_children -> 'time:[4026531834]'
lrwxrwxrwx 1 czar czar 0 Sep 15 16:59 user -> 'user:[4026531837]'
lrwxrwxrwx 1 czar czar 0 Sep 15 16:59 uts -> 'uts:[4026531838]'
```
Как видим, все доступные ✨*namespaces*✨ принадлежат хосту (czar). Нас такой расклад не очень устраивает, поэтому работаем по заданию и наводим твердый порядок (изолируем процесс по неймспейсам) жесткой рукой (`unshare`).  
По заданию нам нужны следующие неймспейсы: pid, mount, net, uts, ipc и user.  

К сожалению прочитать `unshare --help` и просто выполнить `unshare -pmnuiU <сервис>` не прокатило и пришлось думать мозгом(  

Итоговая команда выглядит так:
```bash
unshare -Urmpniu --fork --mount-proc java -jar lab1/api/target/api-1.0.0.jar 
```
**Теперь подробно про флаги:**  
Первая группа `-Urmpniu` указывает какие неймспейсы мы хотим создать для нового процесса.
- U — Создаем новый user namespace - изолируем процесс от пользователей хоста и их групп
- r — Относится к флагу `U`, он же --map-root-user. Нужен для того, чтобы смапить текущего пользователя хоста в root внутри процесса (UID 0).
- m — Создаем новый mount namespace - изолируем ✨*таблицу монтирования*✨ ФС процесса.
- p — Создаем новый PID процесса, ему присваивается значение PID 1.
- n — Аналогично создаем новый network namespace, изолируем процесс от всего сетевого стека хоста (таблицы маршрутизации, айпишники, сетевые интерфейсы, всякие настройки фаервола и т.д.)
- i — Создаем IPC namespace. То есть Inter-Process Communication. То есть изолируем межпроцессное взаимодействие. То есть всякие shared memory, семафоры и т.д.
- u — Создаем новый UTS namespace. Изолируем hostname и другие идентификаторы, благодаря чему можем назвать наш "контейнер" ***new-ultimate-innovative-russian-container-better-than-docker-better-than-any-decadent-west-app-called-rucker***.  

Этот аргумент кстати можно разбить на отдельные легкочитаемые флаги `-U -r -m -p -n -i -u`, нооо.... пожалуй нет.  
Идем далее:
- --fork — Нужен, чтобы запустить джарник как дочерний процесс unshare с PID 1. То есть получается, что у нас изначально есть процесс (условный `shell`) на хосте, который запускает новый процесс `unshare`, который создает еще один процесс внутри себя через форк. И ВИШЕНКОЙ НА ТОРТЕ ЯВЛЯЕТСЯ ТО, ЧТО В КАЧЕСТВЕ ДОЧЕРНЕГО ПРИМЕРА У НАС ЗАПУСКАЕТСЯ **ВИРТУАЛЬНАЯ МАШИНА** ДЖАВЫ... ох... ну то есть... виртуалка в "контейнере" типа... ох...
- --mount-proc — Монтируем новый экзмепляр procfs в /proc, чтобы внутри "контейнера" shell имел доступ только к внутренним процессам и такие команды как top и ps выводили корректные списки.

#### Проверки:
Для начала в другом терминале выполним `ps -ef | grep '[a]pi-1.0.0.jar'`, чтобы узнать PID нашего процесса в PID namespace хоста.  
Получаем:
```bash
czar     2476527 2273751  0 01:12 pts/10   00:00:00 unshare -Urmpniu --fork --mount-proc java -jar lab1/api/target/api-1.0.0.jar
czar     2476528 2476527  0 01:12 pts/10   00:00:00 java -jar lab1/api/target/api-1.0.0.jar
```
Нас интересует процесс с PID **2476528**.

1) > изнутри процесс — PID 1, чужих процессов не видит;
    
    Для доказательства PID = 1 используем команду
    ```bash
    sudo nsenter -t 2476528 -p -m ps -ef
    ```
    `nsenter` — утилита, позволяющая зайти внутрь процесса (точнее внутрь изолированного namespaces) и выполнить там команду. Можно было как-то иначе сделать, но так на мой взгляд проще всего + очень наглядно.  
    Флаги: 
    - t — (target) указываем PID в пространстве имен хоста
    - p — (pid) входим в PID namespaces процесса target
    - m — (mount) входим в mount namespaces процесса target, чтобы получить доступ к изолированной ФС и в частности к /proc
    - ps -ef — Собственно команда, которую выполняем внутри. В данном случаем смотри все процессы
    
    Вывод:
    ```bash
    UID          PID    PPID  C STIME TTY          TIME CMD
    czar           1       0  0 01:12 pts/10   00:00:01 java -jar lab1/api/target/api-1.0.0.jar
    root          59       0  0 01:33 pts/7    00:00:00 ps -ef
    ```

    Видим ТОЛЬКО процесс java -jar с PID 1 *ну и ps, но это из-за того, что она выполняется в том же контексте*.

2) > у него своё имя хоста и своя пустая сеть;
    
    Для данной проверки снова используем nsenter и проверим имя хоста.
    ```bash
    sudo nsenter -t 2476528 -u hostname
    ```
    Вывод:
    ```bash
    svinoserver
    ```
    Имя внутри процесса совпадает с именем хоста, но это из-за того, что при использовании unshare мы не указывали другое имя :(  
    НООО, можно поменять имя сейчас и посмореть, что на хосте оно не изменится.  
    Команды и вывод:
    ```bash
    [czar@svinoserver ~]$ sudo nsenter -t 2476528 -u hostname new-ultimate-innovative-russian-container-better-than-docker
    [czar@svinoserver ~]$ sudo nsenter -t 2476528 -u hostname
    new-ultimate-innovative-russian-container-better-than-docker
    [czar@svinoserver ~]$ hostname
    svinoserver
    ```
    **Во-первых**, название ***new-ultimate-innovative-russian-container-better-than-docker-better-than-any-decadent-west-app-called-rucker*** является *invalid argument*, так как слишком крутое (лимит по кол-ву символов)  
    **Во-вторых**, как видим название хоста не изменилось.

    Теперь по сетевой изоляции:  
    Снова импользуем nsenter и видим в выводе всего один локальный интерфейс.
    ```bash
    sudo nsenter -t 2476528 -n ip a
    1: lo: <LOOPBACK> mtu 65536 qdisc noop state DOWN group default qlen 1000
    link/loopback 00:00:00:00:00:00 brd 00:00:00:00:00:00
    ```
    В то же время на хосте `ip a` показывает 12 интерфейсов.   
    Могу вот вам интерфейс докера показать, но он спит, просьба не будить.
    ```bash
    [czar@svinoserver ~]$ ip a
    
    ...
    
    6: docker0: <NO-CARRIER,BROADCAST,MULTICAST,UP> mtu 1500 qdisc noqueue state DOWN group default
    link/ether d6:31:59:71:61:45 brd ff:ff:ff:ff:ff:ff
    inet 172.17.0.1/16 brd 172.17.255.255 scope global docker0
       valid_lft forever preferred_lft forever
    ```


3) > root внутри — это непривилегированный пользователь снаружи (проверь, под каким uid процесс виден на хосте).
    
    Снова смотрим `sudo nsenter -t 2476528 -p -m ps -ef`:
    ```bash
    UID          PID    PPID  C STIME TTY          TIME CMD
    czar           1       0  0 01:12 pts/10   00:00:05 java -jar lab1/api/target/api-1.0.0.jar
    ```
    Еще посмотрим `cat /proc/2476528/uid_map`:
    ```bash
             0       1000          1
    ```
    0 — UID внутри "контейнера", 1000 — UID на хосте.  
    Теперь посмотрим UID процесса с хоста:
    ``` bash
    ps -o ruid,user,pid,cmd -p 2476528
    ```
    Вывод:
    ```bash
    RUID USER         PID CMD
    1000 czar     2476528 java -jar lab1/api/target/api-1.0.0.jar
    ```
    Видим, что процесс выполняется под 1000, а не под 0, то есть под НЕпривелигированным пользователем.

## Часть 3
> Заведи для процесса cgroup v2 и навесь лимиты. Проверь каждый через свой сервис:

*Какой-то объемный отчет выходит, дальше постараюсь писать покороче (не обещаю)*.

Для начала создадим cgroup для нашего крутого процесса:
```bash
sudo mkdir /sys/fs/cgroup/new-ultimate-innovative-russian-container-better-than-docker
```
Переместим его в созданную директорию (PID поменялся, т.к. процесс был перезапущен):
```bash
echo 2742844 | sudo tee /sys/fs/cgroup/new-ultimate-innovative-russian-container-better-than-docker/cgroup.procs
```
Как говорит интернет: добавление процесса в контрольную группу работает через запись PID в ✨*специальный файл*✨.  
Используем `tee`, потому что `echo >` не пройдет из-за permission denied при попытке записи в системный файл.
#### Проверки:
1) > память: задай небольшой потолок, дёрни /eat?mb=... за него, поймай OOM. Это то же самое, что OOMKilled в Kubernetes;

    Сначала посмотрим сколько наша Java кушает памяти сейчас:
    ```bash
    czar@svinoserver ~]$ cat /sys/fs/cgroup/new-ultimate-innovative-russian-container-better-than-docker/memory.current
    1470464
    ```
    Проверяем доступность сервиса:  
    ```bash
    [czar@svinoserver ~]$ curl localhost:8080/health
    curl: (7) Failed to connect to localhost:8080 after 0 ms: Could not connect to server
    ```
    Первая причина такого вывода — нерабочий интерфейс `lo`. Это мы увидели еще при втором тесте второй части. Убедимся в этом еще раз и поднимем `lo` через `nsenter`:
    ```bash
    [czar@svinoserver ~]$ sudo nsenter -t 2742844 -n ip addr
    1: lo: <LOOPBACK> mtu 65536 qdisc noop state DOWN group default qlen 1000
        link/loopback 00:00:00:00:00:00 brd 00:00:00:00:00:00

    [czar@svinoserver ~]$ sudo nsenter -t 2742844 -n ip link set lo up

    [czar@svinoserver ~]$ sudo nsenter -t 2742844 -n ip addr
    1: lo: <LOOPBACK,UP,LOWER_UP> mtu 65536 qdisc noqueue state UNKNOWN group default qlen 1000
        link/loopback 00:00:00:00:00:00 brd 00:00:00:00:00:00
        inet 127.0.0.1/8 scope host lo
        valid_lft forever preferred_lft forever
        inet6 ::1/128 scope host proto kernel_lo
        valid_lft forever preferred_lft forever
    ```
    Теперь все чикибамбони.  
    Однако, дергать ручки сервиса так же нужно через `nsenter`, чтобы `curl` выполнился в изолированном пространстве имен.
    ```bash
    [czar@svinoserver ~]$ sudo nsenter -t 2742844 -n curl http://localhost:8080/health
    ok
    ```
    Еще раз проверим текущую память и зададим лимит в ~100МБ.
    ```bash
    [czar@svinoserver ~]$ cat /sys/fs/cgroup/new-ultimate-innovative-russian-container-better-than-docker/memory.current
    5746688
    [czar@svinoserver ~]$ echo 104857600 | sudo tee /sys/fs/cgroup/new-ultimate-innovative-russian-container-better-than-doc
    ker/memory.max
    104857600
    ```
    Несколько раз дернем ручку `/eat?mb=X`.
    ```bash
    [czar@svinoserver ~]$ sudo nsenter -t 2742844 -n curl http://localhost:8080/eat?mb=100
    allocated and holding 100 MB
    [czar@svinoserver ~]$ cat /sys/fs/cgroup/new-ultimate-innovative-russian-container-better-than-docker/memory.current
    103923712
    
    ...

    [czar@svinoserver ~]$ sudo nsenter -t 2742844 -n curl http://localhost:8080/eat?mb=250
    allocated and holding 250 MB
    [czar@svinoserver ~]$ cat /sys/fs/cgroup/new-ultimate-innovative-russian-container-better-than-docker/memory.current
    104787968
    [czar@svinoserver ~]$ sudo nsenter -t 2742844 -n curl http://localhost:8080/eat?mb=400
    curl: (52) Empty reply from server
    ```
    Наконец-то процесс ****мертв**** 🥰💀  
    ОДНАКО, есть небольшая проблема в фиксации OOM. Команда ` cat /sys/fs/cgroup/.../memory.events` показала, что oom и oom_kill равны 0:
    ```bash
    low 0
    high 0
    max 5790
    oom 0
    oom_kill 0
    oom_group_kill 0
    sock_throttled 1
    ```
    При этом Java выкинула следующие исключения:
    ```java
    Exception: java.lang.OutOfMemoryError thrown from the UncaughtExceptionHandler in thread "pool-1-thread-2"
    Exception: java.lang.OutOfMemoryError thrown from the UncaughtExceptionHandler in thread "HTTP-Dispatcher"
    ```
    При попытке выделить доп память для *кучи* JVM попыталась запросить ее у окружения и получив отказ выкинула исключение **JVM OOM**, то есть запрос так и не дошел до ядра линукса. Таким образом Java в очередной раз отлично проявила себя и защитила хост от возможных утечек памяти.  
    Считаю, что проверка на OOM прошла успешно. *(Если все таки потребуется, можем провести другой эксперимент)* 

2) > CPU: задай лимит (например, пол-ядра), дёрни /burn, найди throttling в статистике cgroup;
    
    Новый PID процесса: 2759207. Поднимаем `lo`, добавляем в `cgroup`.  
    Задаем лимит на пол ядра:
    ```bash
    echo "50000 100000" | sudo tee /sys/fs/cgroup/new-ultimate-innovative-russian-container-better-than-docker/cpu.max
    ```
    Нагружаем:  
    ```bash
    sudo nsenter -t 2759207 -n curl http://localhost:8080/burn
    ```
    Открываем третий терминал и смотрим `cpu.stat`:
    ```bash
    [czar@svinoserver ~]$ cat /sys/fs/cgroup/new-ultimate-innovative-russian-container-better-than-docker/cpu.stat
    
    usage_usec 26916451
    user_usec 23816548
    system_usec 3099902
    nice_usec 0
    core_sched.force_idle_usec 0
    nr_periods 537
    nr_throttled 435
    throttled_usec 21712819
    nr_bursts 0
    burst_usec 0
    ```
    Видим количество циклов `nr_periods 537`, что примерно 53.7 секунд. Из них `nr_throttled 435`, то есть проц тротлил в ~80% циклов. Непосредственное время тротлинга составило почти 22 секунды (`throttled_usec 21712819`).

3) > процессы: задай pids.max, запусти форк-бомбу через stress-ng --fork, покажи, что расплодиться не дают.  

    Проверим текущее кол-во процессов:
    ```bash
    [czar@svinoserver ~]$ cat /sys/fs/cgroup/new-ultimate-innovative-russian-container-better-than-docker/pids.current
    21
    ```
    Ставим лимит в 25 процессов:
    ```bash
    echo 25 | sudo tee /sys/fs/cgroup/new-ultimate-innovative-russian-container-better-than-docker/pids.max
    ```
    Запускаем форк-бомбу через nsenter👳🏽‍♂️
    ```bash
    sudo nsenter -t 3213869 -p -m -n stress-ng --fork 35 --timeout 10s
    ```
    В другом терминале выполняем `watch cat /sys/fs/cgroup/new-ultimate-innovative-russian-container-better-than-docker/pids.current` и видим, что форк не сработал, так как `stress-ng` попадает в те же namespaces, но не в ту же cgroup. Поэтому идем другим путем:
    ```bash
    sudo sh -c '
    echo $$ > /sys/fs/cgroup/new-ultimate-innovative-russian-container-better-than-docker/cgroup.procs
    exec stress-ng --fork 35 --timeout 10s
    '
    ```
    В другом терминале снвоа запускаем `watch cat ../pids.current` и видим:
    ```bash
    Every 2.0s: cat /sys/fs/cgroup/new-ultimate-innovative-russian-container-b… svinoserver: Sat 19 Sep 2026 03:31:31 PM MSK in 0.005s (0) 
    25
    ```
    Как видим, ограничения сработали и форк-бомба была успешно обезврежена.


## Часть 4
> Оставь процессу только нужный минимум:
1) > сбрось лишние capabilities и покажи, что привилегированное действие (например, смена системного времени) больше не проходит;

    Сначала проверим какие capabilities есть у нашего процесса:
    ```bash
    sudo nsenter -t 3213869 -p -m -u -i -n cat /proc/1/status | grep Cap
    ```
    Вывод:
    ```bash
    CapInh: 0000000000000000
    CapPrm: 000001ffffffffff
    CapEff: 000001ffffffffff
    CapBnd: 000001ffffffffff
    CapAmb: 0000000000000000
    ```
    Декодируем CapEff:
    ```bash
    capsh --decode=000001ffffffffff
    ```
    Видим полный список разрешений:
    ```
    0x000001ffffffffff=cap_chown,cap_dac_override,cap_dac_read_search,cap_fowner,cap_fsetid,cap_kill,cap_setgid,cap_setuid,cap_setpcap,cap_linux_immutable,cap_net_bind_service,cap_net_broadcast,cap_net_admin,cap_net_raw,cap_ipc_lock,cap_ipc_owner,cap_sys_module,cap_sys_rawio,cap_sys_chroot,cap_sys_ptrace,cap_sys_pacct,cap_sys_admin,cap_sys_boot,cap_sys_nice,cap_sys_resource,cap_sys_time,cap_sys_tty_config,cap_mknod,cap_lease,cap_audit_write,cap_audit_control,cap_setfcap,cap_mac_override,cap_mac_admin,cap_syslog,cap_wake_alarm,cap_block_suspend,cap_audit_read,cap_perfmon,cap_bpf,cap_checkpoint_restore
    ```
    Меняем системное время и видим что проблем не возникло:
    ```bash
    [czar@svinoserver ~]$ sudo nsenter -t 3213869 -p -m date -s "16:20:00":
    Sat Sep 19 04:20:00 PM MSK 2026
    ``` 
    Перезапускаем "контейнер", дропнус `cap_sys_time` и сразу подняв интерфейс `lo`:
    ```bash
    unshare -Urmpniu --fork --mount-proc sh -c "ip link set lo up && capsh --drop=cap_sys_time -- -c 'java -jar lab1/api/target/api-1.0.0.jar'"
    ```
    Новый PID: 3801043
    ```bash
    [czar@svinoserver ~]$ sudo nsenter -t 3801043 -p -m cat /proc/1/status | grep CapEff
    CapEff: 000001fffdffffff
    [czar@svinoserver ~]$ capsh --decode=000001fffdffffff
    0x000001fffdffffff=cap_chown,cap_dac_override,cap_dac_read_search,cap_fowner,cap_fsetid,cap_kill,cap_setgid,cap_setuid,cap_setpcap,cap_linux_immutable,cap_net_bind_service,cap_net_broadcast,cap_net_admin,cap_net_raw,cap_ipc_lock,cap_ipc_owner,cap_sys_module,cap_sys_rawio,cap_sys_chroot,cap_sys_ptrace,cap_sys_pacct,cap_sys_admin,cap_sys_boot,cap_sys_nice,cap_sys_resource,cap_sys_tty_config,cap_mknod,cap_lease,cap_audit_write,cap_audit_control,cap_setfcap,cap_mac_override,cap_mac_admin,cap_syslog,cap_wake_alarm,cap_block_suspend,cap_audit_read,cap_perfmon,cap_bpf,cap_checkpoint_restore
    ```
    Сравнение разрешений с прошлого запуска и с текущего:
    ```
    ...cap_sys_nice,cap_sys_resource,cap_sys_time,cap_sys_tty_config...
    ...cap_sys_nice,cap_sys_resource,cap_sys_tty_config...
    ```
    Как видим, `cap_sys_time` теперь не отображается, а при попытке поменять время теперь получим Operation not permitted.
    ```bash
    [czar@svinoserver ~]$ sudo nsenter -t 3801043 -p -m -U date -s "16:00:00"
    date: cannot set date: Operation not permitted
    ```
    Вряд ли вы до сюда дочитали, так что внезапный анекдот с сайта anekdotovstreet.com:
    > Лондон, зоопарк. Мальчик спрашивает смотрителя:  
    — Вы не объясните, сэр, почему у жирафа такая длинная шея?  
    — Видите ли, мой юный друг, у жирафа голова столь удалена от тела, что такая длинная шея ему просто необходима!

2) > навесь seccomp-профиль и покажи, что заблокированный системный вызов отклоняется.

    Сначала запускаем сервис без ограничений и выполняем системный вызов. Потом перезапускаем сервис под seccomp-профилем и смотрим на Operation not permitted.
    До seccomp:
    ```bash
    [czar@svinoserver ~]$ sudo nsenter -t 3816234 -p -m -U uname -a
    Linux svinoserver 7.1.5-arch1-1 #1 SMP PREEMPT_DYNAMIC Sun, 26 Jul 2026 00:49:06 +0000 x86_64 GNU/Linux
    ```
    После:
    ```java
    Sep 19 20:38:21 svinoserver unshare[9230]: uname: cannot get system name: Operation not permitted
    ```
    Навесить seccomp на уже рабочий процесс нельзя, поэтому пришлось делать это иначе. Полная команда:
    ```bash
    sudo systemd-run --unit=devops-lab1 \
    -p User=czar \
    -p SystemCallFilter='~uname' \
    -p SystemCallErrorNumber=EPERM \
    -- unshare -Urmpniu --fork --mount-proc sh -c \
    "ip link set lo up && uname -a; java -jar /home/czar/itmo/devops/lab1/api/target/api-1.0.0.jar"
    ```
    **Пояснения:**
    - `systemd-run --unit=devops-lab1` — создаёт временный **service**-юнит. Создаем service, а не scope, т.к. `--scope` создаёт юнит вокруг уже существующего процесса и не проходит через конвейер systemd, поэтому не умеет применять настройки вроде `SystemCallFilter=`. Service fork+exec целевой команды и успевает применить все ограничения до старта.

    - `-p User=czar` — без этого флага `systemd-run` стартует юнит от рута
    
    - `-p SystemCallFilter='~uname'` — seccomp-флаг. ~ означает разрешить все системные вызовы КРОМЕ указанного, то есть uname. Без тильды ноборот бы разрешили только uname.
    
    - `-p SystemCallErrorNumber=EPERM` — По умолчанию systemd вешает `SCMP_ACT_KILL_PROCESS` и ядро убивает процесс. Мы же задаем поведение *вернуть статус ошибки*, чтобы можно было проверить работу фильтров наглядно.
    
    - `-- unshare -Urmpniu --fork --mount-proc sh -c "..."` — тут особо ничего не изменилось, но перед запуском апишки сначала пытаемся вызвать uname.

    Доп пруфы работы фильтров:
    ```bash
    sudo nsenter -t 9226 -p -m cat /proc/1/status | grep Seccomp
    Seccomp:        2
    Seccomp_filters:        3
    ```

# Часть 5
> Собери все команды из частей 2–4 в один скрипт (например, mydocker.sh), который одной командой запускает api в своих namespaces, с cgroup-лимитами и урезанными правами. Проверь, что сервис поднимается и /health отвечает.

В кратце: собрали все команды в один sh скрипт:
```bash
#!/bin/bash
set -uo pipefail

CONTAINER_NAME="new-ultimate-innovative-russian-container-better-than-docker"
CGROUP_PATH="/sys/fs/cgroup/${CONTAINER_NAME}"
JAR_PATH="/home/czar/itmo/devops/lab1/api/target/api-1.0.0.jar"
MEM_LIMIT=104857600
CPU_QUOTA="50000 100000"
PIDS_LIMIT=25

sudo mkdir -p "${CGROUP_PATH}"
echo "${MEM_LIMIT}"  | sudo tee "${CGROUP_PATH}/memory.max"  >/dev/null
echo "${CPU_QUOTA}"  | sudo tee "${CGROUP_PATH}/cpu.max"     >/dev/null
echo "${PIDS_LIMIT}" | sudo tee "${CGROUP_PATH}/pids.max"    >/dev/null

sudo systemctl reset-failed "${CONTAINER_NAME}" 2>/dev/null || true

sudo systemd-run --unit="${CONTAINER_NAME}" \
  -p User=czar \
  -p SystemCallFilter='~uname' \
  -p SystemCallErrorNumber=EPERM \
  -- unshare -Urmpniu --fork --mount-proc sh -c \
  "ip link set lo up && capsh --drop=cap_sys_time -- -c 'java -jar ${JAR_PATH}'"

PID=""
for i in $(seq 1 20); do
  FOUND=$(pgrep -f "^java -jar ${JAR_PATH}$" | head -n1)
  if [ -n "${FOUND}" ]; then
    PID="${FOUND}"
    break
  fi
  sleep 0.5
done


echo "${PID}" | sudo tee "${CGROUP_PATH}/cgroup.procs" >/dev/null

echo "Контейнер запущен с PID ${PID}!"
echo "Юнит: ${CONTAINER_NAME}"
echo "Cgroup: ${CGROUP_PATH}"
echo ""
echo "Для остановки контейнера: sudo systemctl stop ${CONTAINER_NAME}"
```

Полный скрипт [здесь](mydocker.sh).  
Из интересного прописали `set -uo pipefail`, чтобы при ошибки какой-то из команд скрипт целиком не падал, но ошибки писал.

> Теперь запусти тот же сервис через docker run и сравни с запуском своего скрипта: что совпадает, чего в твоём скрипте нет и что Docker делает сверх него. Сведи сравнение в README.

Диагностика самописного скрипта:
```bash
[czar@svinoserver lab1]$ sudo nsenter -t 4352 -p -m ps -ef
UID          PID    PPID  C STIME TTY          TIME CMD
czar           1       0  0 21:09 ?        00:00:00 java -jar /home/czar/itmo/devops/lab1/api/target/api-1.0.0.jar
root          23       0  0 21:14 pts/1    00:00:00 ps -ef

[czar@svinoserver lab1]$ sudo nsenter -t 4352 -p -m cat /proc/1/status | grep CapEff
CapEff: 000001fffdffffff

[czar@svinoserver lab1]$ sudo nsenter -t 4352 -p -m cat /proc/1/status | grep Seccomp
Seccomp:        2
Seccomp_filters:        3

[czar@svinoserver lab1]$ sudo nsenter -t 4352 -n curl -s http://localhost:8080/health
ok

[czar@svinoserver lab1]$ cat /sys/fs/cgroup/new-ultimate-innovative-russian-container-better-than-docker/memory.max
104857600

[czar@svinoserver lab1]$ cat /sys/fs/cgroup/new-ultimate-innovative-russian-container-better-than-docker/cpu.max
50000 100000

[czar@svinoserver lab1]$ cat /sys/fs/cgroup/new-ultimate-innovative-russian-container-better-than-docker/pids.max
25
```
Все чикибомбони.

Теперь запуск через `docker run`:
```bash
docker run -d --name docker-lab \
  --memory=100m \
  --cpus=0.5 \
  --pids-limit=25 \
  --cap-drop=ALL --cap-add=NET_BIND_SERVICE \
  -p 8080:8080 \
  -v /home/czar/itmo/devops/lab1/api/target/api-1.0.0.jar:/app/api.jar:ro \
  eclipse-temurin:21-jre \
  java -jar /app/api.jar
```
Проверки:
```bash
[czar@svinoserver lab1]$ curl localhost:8080/health
ok

[czar@svinoserver lab1]$ docker inspect docker-lab --format '{{.HostConfig.Memory}} {{.HostConfig.NanoCpus}} {{.HostConfig.PidsLimit}}'
104857600 500000000 25

[czar@svinoserver lab1]$ docker exec docker-lab cat /proc/1/status | grep -E 'CapEff|Seccomp'
CapEff: 0000000000000400
Seccomp:        2
Seccomp_filters:        1

[czar@svinoserver lab1]$ docker exec docker-lab hostname
0c585e981a71

[czar@svinoserver lab1]$ docker exec docker-lab ip a
OCI runtime exec failed: exec failed: unable to start container process: exec: "ip": executable file not found in $PATH

[czar@svinoserver lab1]$ docker exec docker-lab ps -ef
UID          PID    PPID  C STIME TTY          TIME CMD
root           1       0  0 20:08 ?        00:00:00 java -jar /app/api.jar
root          39       0 66 20:09 ?        00:00:00 ps -ef
```

Выводы:

1. **User namespace**: у нас root внутри — обычный юзер снаружи (`0→1000`). У Docker root внутри — root хоста.
2. **Capabilities**: дропнули один флаг. Docker дропает всё, потом возвращает, то есть использует whitelist вместо blacklist.
3. **Seccomp**: скрипт банит один `uname`, Docker банит всё, что не в списке ~300 разрешённых.
4. **cgroups (память/CPU/pids)**: здесь все идентично.

# Образы
> Твоему скрипту не хватало готовой файловой системы — её и даёт образ.
> - Напиши Dockerfile для api и собери образ.
> - Сделай multi-stage-сборку с минимальной базой (для Go подойдёт scratch или distroless). Сравни размер, число слоёв и что переиспользовалось из кэша при повторной сборке.
> - Запиши файл внутрь контейнера, пересоздай контейнер — файл пропал. Повтори с томом — файл остался.

Напишем простой докерфайл:
```Dockerfile
FROM eclipse-temurin:21-jdk
WORKDIR /app
COPY . .
RUN mvn -B package -DskipTests
CMD ["java", "-jar", "target/api-1.0.0.jar"]
```
Соберем контейнер:
```bash
[czar@svinoserver api]$ docker build -t api -f Dockerfile .

Sending build context to Docker daemon  36.35kB
Step 1/5 : FROM maven:3.9-eclipse-temurin-21
3.9-eclipse-temurin-21: Pulling from library/maven
91f9926a0587: Pulling fs layer
edd1ed89f0d4: Pulling fs layer
a0f1cfdec651: Pulling fs layer
81a217830566: Pulling fs layer
e5d77f4fb216: Pulling fs layer
abd660e00db2: Pulling fs layer
1e47edd31b66: Pulling fs layer
2140a5e2ab92: Pulling fs layer
6e8eb09ed21b: Pulling fs layer
bb0eae36b0e8: Download complete
abd660e00db2: Download complete
a0f1cfdec651: Download complete
cce25323767b: Download complete
6e8eb09ed21b: Download complete
2140a5e2ab92: Download complete
81a217830566: Download complete
1e47edd31b66: Download complete
e5d77f4fb216: Download complete
edd1ed89f0d4: Download complete
edd1ed89f0d4: Pull complete
1e47edd31b66: Pull complete
91f9926a0587: Download complete
91f9926a0587: Pull complete
abd660e00db2: Pull complete
6e8eb09ed21b: Pull complete
e5d77f4fb216: Pull complete
81a217830566: Pull complete
a0f1cfdec651: Pull complete
2140a5e2ab92: Pull complete
Digest: sha256:c2a2c58516d160f43b50f12baa427ca86989e0bc942609e04aff61da5d9a7d74
Status: Downloaded newer image for maven:3.9-eclipse-temurin-21
 ---> c2a2c58516d1
Step 2/5 : WORKDIR /app
 ---> Running in 8df11e54bc1c
 ---> Removed intermediate container 8df11e54bc1c
 ---> 811c82182e65
Step 3/5 : COPY . .
 ---> 2fc390873df4
Step 4/5 : RUN mvn -B package -DskipTests
 ---> Running in a549265b6163
[INFO] Scanning for projects...
[INFO] 
[INFO] -------------------------< ru.itmo.devops:api >-------------------------
[INFO] Building api 1.0.0
[INFO]   from pom.xml
[INFO] --------------------------------[ jar ]---------------------------------
[INFO] Downloading from central: https://repo.maven.apache.org/maven2/org/apache/maven/plugins/maven-resources-plugin/3.4.0/maven-resources-plugin-3.4.0.pom
...
[INFO] Building jar: /app/target/api-1.0.0.jar
[INFO] ------------------------------------------------------------------------
[INFO] BUILD SUCCESS
[INFO] ------------------------------------------------------------------------
[INFO] Total time:  15.573 s
[INFO] Finished at: 2026-09-21T12:12:52Z
[INFO] ------------------------------------------------------------------------
 ---> Removed intermediate container a549265b6163
 ---> 067f35dc12e9
Step 5/5 : CMD ["java", "-jar", "target/api-1.0.0.jar"]
 ---> Running in c4c4c975dcf3
 ---> Removed intermediate container c4c4c975dcf3
 ---> 31e18f796c8c
Successfully built 31e18f796c8c
Successfully tagged api:latest
```

Теперь напишем и соберем multi-staged докерфайл.  
Dockerfile.multistaged:
```dockerfile
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /src
COPY pom.xml .
RUN mvn -B dependency:go-offline
COPY src ./src
RUN mvn -B package -DskipTests

FROM gcr.io/distroless/java21-debian12
WORKDIR /app
COPY --from=build /src/target/api-1.0.0.jar /app/api.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/api.jar"]
```
Сборка:
```bash
[INFO] Downloaded from central: https://repo.maven.apache.org/maven2/org/codehaus/plexus/plexus-java/1.2.0/plexus-java-1.2.0.jar (58 kB at 518 kB/s)
...
[INFO] ------------------------------------------------------------------------
[INFO] BUILD SUCCESS
[INFO] ------------------------------------------------------------------------
[INFO] Total time:  48.256 s
[INFO] Finished at: 2026-09-21T12:16:01Z
[INFO] ------------------------------------------------------------------------
 ---> Removed intermediate container c71c6f6e1d1a
 ---> ac6ac629c1ae
Step 5/11 : COPY src ./src
 ---> 31e3da5ba480
Step 6/11 : RUN mvn -B package -DskipTests
 ---> Running in e70a48e214c6
[INFO] Scanning for projects...
[INFO] 
[INFO] -------------------------< ru.itmo.devops:api >-------------------------
[INFO] Building api 1.0.0
[INFO]   from pom.xml
[INFO] --------------------------------[ jar ]---------------------------------
[INFO] 
[INFO] --- resources:3.4.0:resources (default-resources) @ api ---
[INFO] skip non existing resourceDirectory /src/src/main/resources
[INFO] 
[INFO] --- compiler:3.13.0:compile (default-compile) @ api ---
[INFO] Recompiling the module because of changed source code.
[INFO] Compiling 1 source file with javac [debug release 21] to target/classes
[INFO] 
[INFO] --- resources:3.4.0:testResources (default-testResources) @ api ---
[INFO] skip non existing resourceDirectory /src/src/test/resources
[INFO] 
[INFO] --- compiler:3.13.0:testCompile (default-testCompile) @ api ---
[INFO] No sources to compile
[INFO] 
[INFO] --- surefire:3.5.4:test (default-test) @ api ---
[INFO] Tests are skipped.
[INFO] 
[INFO] --- jar:3.4.2:jar (default-jar) @ api ---
[INFO] Building jar: /src/target/api-1.0.0.jar
[INFO] ------------------------------------------------------------------------
[INFO] BUILD SUCCESS
[INFO] ------------------------------------------------------------------------
[INFO] Total time:  1.205 s
[INFO] Finished at: 2026-09-21T12:16:07Z
[INFO] ------------------------------------------------------------------------
 ---> Removed intermediate container e70a48e214c6
 ---> 102ffbb53ab2
Step 7/11 : FROM gcr.io/distroless/java21-debian12
latest: Pulling from distroless/java21-debian12
3214acf345c0: Pulling fs layer
ef49c20a7b35: Pulling fs layer
dd64bf2dd177: Pulling fs layer
52630fc75a18: Pulling fs layer
dcaa5a89b0cc: Pulling fs layer
7c12895b777b: Pulling fs layer
250755fb415d: Pulling fs layer
66da007fd54f: Pulling fs layer
b839dfae01f6: Pulling fs layer
526604835308: Pulling fs layer
b16bb3b2bd07: Pulling fs layer
bf7a4185f015: Pulling fs layer
990a9c434e5e: Pulling fs layer
069d1e267530: Pulling fs layer
1fd3329b0de2: Pulling fs layer
c65bb0c25578: Pulling fs layer
8928cb22aa37: Pulling fs layer
08dd3c4351e9: Pulling fs layer
4486a6a259bb: Pulling fs layer
ba6750202c26: Pulling fs layer
ef335559898d: Pulling fs layer
6d7292fc835d: Pulling fs layer
d38b7f3e8045: Pulling fs layer
ace43640e860: Pulling fs layer
5822fa015fc5: Pulling fs layer
2780920e5dbf: Pulling fs layer
3a212aea01d1: Pulling fs layer
f164bc9f2b9e: Pulling fs layer
1e8acdaa2607: Pulling fs layer
a812c900745e: Pulling fs layer
52630fc75a18: Download complete
7c12895b777b: Download complete
66da007fd54f: Download complete
2780920e5dbf: Download complete
3214acf345c0: Download complete
dd64bf2dd177: Download complete
b839dfae01f6: Download complete
dcaa5a89b0cc: Download complete
526604835308: Download complete
526604835308: Pull complete
d38b7f3e8045: Download complete
4486a6a259bb: Download complete
990a9c434e5e: Download complete
ba6750202c26: Download complete
990a9c434e5e: Pull complete
6d7292fc835d: Download complete
b16bb3b2bd07: Download complete
bf7a4185f015: Download complete
08dd3c4351e9: Download complete
5822fa015fc5: Download complete
250755fb415d: Download complete
8928cb22aa37: Download complete
a812c900745e: Download complete
069d1e267530: Download complete
3a212aea01d1: Download complete
1e8acdaa2607: Download complete
f164bc9f2b9e: Download complete
ef49c20a7b35: Download complete
ef49c20a7b35: Pull complete
bf7a4185f015: Pull complete
7c12895b777b: Pull complete
2780920e5dbf: Pull complete
ace43640e860: Download complete
52630fc75a18: Pull complete
3214acf345c0: Pull complete
dd64bf2dd177: Pull complete
b839dfae01f6: Pull complete
dcaa5a89b0cc: Pull complete
069d1e267530: Pull complete
c65bb0c25578: Download complete
ef335559898d: Download complete
c65bb0c25578: Pull complete
250755fb415d: Pull complete
4486a6a259bb: Pull complete
ba6750202c26: Pull complete
8928cb22aa37: Pull complete
ef335559898d: Pull complete
d38b7f3e8045: Pull complete
6d7292fc835d: Pull complete
ace43640e860: Pull complete
5822fa015fc5: Pull complete
3a212aea01d1: Pull complete
f164bc9f2b9e: Pull complete
a812c900745e: Pull complete
1e8acdaa2607: Pull complete
b16bb3b2bd07: Pull complete
08dd3c4351e9: Pull complete
66da007fd54f: Pull complete
1fd3329b0de2: Download complete
1fd3329b0de2: Pull complete
Digest: sha256:f34fd3e4e2d7a246d764d0614f5e6ffb3a735930723fac4cfc25a72798950262
Status: Downloaded newer image for gcr.io/distroless/java21-debian12:latest
 ---> f34fd3e4e2d7
Step 8/11 : WORKDIR /app
 ---> Running in a612f242b6f3
 ---> Removed intermediate container a612f242b6f3
 ---> 9b9dbb28099b
Step 9/11 : COPY --from=build /src/target/api-1.0.0.jar /app/api.jar
 ---> cecc862fe6c2
Step 10/11 : EXPOSE 8080
 ---> Running in 2f4b09bb9bd1
 ---> Removed intermediate container 2f4b09bb9bd1
 ---> 6abd60982945
Step 11/11 : ENTRYPOINT ["java", "-jar", "/app/api.jar"]
 ---> Running in bcd617f18a0b
 ---> Removed intermediate container bcd617f18a0b
 ---> 3aeec311305b
Successfully built 3aeec311305b
Successfully tagged api-multistage:latest
```
Теперь сравни *images*:
```bash
[czar@svinoserver api]$ docker images api
                                                                                                                                                                                                                                                                                                       i Info →   U  In Use
IMAGE        ID             DISK USAGE   CONTENT SIZE   EXTRA
api:latest   31e18f796c8c        832MB          259MB        

[czar@svinoserver api]$ docker images api-multistage
                                                                                                                                                                                                                                                                                                       i Info →   U  In Use
IMAGE                   ID             DISK USAGE   CONTENT SIZE   EXTRA
api-multistage:latest   3aeec311305b        261MB         63.2MB 
```

Как видим, image мультистейдж докера занимает почти в 4 раза меньше места на диске. Сравним слои.  
Обычный образ:
```bash
[czar@svinoserver api]$ docker history api:latest
IMAGE          CREATED         CREATED BY                                      SIZE      COMMENT
31e18f796c8c   7 minutes ago   /bin/sh -c #(nop)  CMD ["java" "-jar" "targe…   0B        
067f35dc12e9   7 minutes ago   /bin/sh -c mvn -B package -DskipTests           20.7MB    
2fc390873df4   7 minutes ago   /bin/sh -c #(nop) COPY dir:71c2c997116807626…   49.2kB    
811c82182e65   7 minutes ago   /bin/sh -c #(nop) WORKDIR /app                  0B        
c2a2c58516d1   5 days ago      CMD ["mvn"]                                     0B        buildkit.dockerfile.v0
<missing>      5 days ago      ENTRYPOINT ["/usr/local/bin/mvn-entrypoint.s…   0B        buildkit.dockerfile.v0
<missing>      5 days ago      ENV MAVEN_CONFIG=/root/.m2                      0B        buildkit.dockerfile.v0
<missing>      5 days ago      ARG USER_HOME_DIR=/root                         0B        buildkit.dockerfile.v0
<missing>      5 days ago      RUN /bin/sh -c ln -s ${MAVEN_HOME}/bin/mvn /…   4.1kB     buildkit.dockerfile.v0
<missing>      5 days ago      COPY /usr/local/bin/mvn-entrypoint.sh /usr/l…   4.1kB     buildkit.dockerfile.v0
<missing>      5 days ago      COPY /usr/share/maven /usr/share/maven # bui…   11.1MB    buildkit.dockerfile.v0
<missing>      5 days ago      ENV MAVEN_HOME=/usr/share/maven                 0B        buildkit.dockerfile.v0
<missing>      5 days ago      LABEL org.opencontainers.image.description=A…   0B        buildkit.dockerfile.v0
<missing>      5 days ago      LABEL org.opencontainers.image.url=https://g…   0B        buildkit.dockerfile.v0
<missing>      5 days ago      LABEL org.opencontainers.image.source=https:…   0B        buildkit.dockerfile.v0
<missing>      5 days ago      LABEL org.opencontainers.image.title=Apache …   0B        buildkit.dockerfile.v0
<missing>      5 days ago      RUN /bin/sh -c apt-get update   && apt-get i…   77.2MB    buildkit.dockerfile.v0
<missing>      5 days ago      CMD ["jshell"]                                  0B        buildkit.dockerfile.v0
<missing>      5 days ago      ENTRYPOINT ["/__cacert_entrypoint.sh"]          0B        buildkit.dockerfile.v0
<missing>      5 days ago      COPY --chmod=755 entrypoint.sh /__cacert_ent…   8.19kB    buildkit.dockerfile.v0
<missing>      5 days ago      RUN /bin/sh -c set -eux;     echo "Verifying…   0B        buildkit.dockerfile.v0
<missing>      5 days ago      RUN /bin/sh -c set -eux;     ARCH="$(dpkg --…   309MB     buildkit.dockerfile.v0
<missing>      5 days ago      ENV JAVA_VERSION=jdk-21.0.12+8                  0B        buildkit.dockerfile.v0
<missing>      5 days ago      RUN /bin/sh -c set -eux;     apt-get update;…   69.3MB    buildkit.dockerfile.v0
<missing>      5 days ago      ENV LANG=en_US.UTF-8 LANGUAGE=en_US:en LC_AL…   0B        buildkit.dockerfile.v0
<missing>      5 days ago      ENV PATH=/opt/java/openjdk/bin:/usr/local/sb…   0B        buildkit.dockerfile.v0
<missing>      5 days ago      ENV JAVA_HOME=/opt/java/openjdk                 0B        buildkit.dockerfile.v0
<missing>      10 days ago     /bin/sh -c #(nop)  CMD ["/bin/bash"]            0B        
<missing>      10 days ago     /bin/sh -c #(nop) ADD file:43d479b270bbaf479…   85.7MB    
<missing>      10 days ago     /bin/sh -c #(nop)  LABEL org.opencontainers.…   0B        
<missing>      10 days ago     /bin/sh -c #(nop)  ARG LAUNCHPAD_BUILD_ARCH     0B        
<missing>      10 days ago     /bin/sh -c #(nop)  ARG RELEASE                  0B     
```
Мультистейдже:
```bash
[czar@svinoserver api]$ docker history api-multistage:latest
IMAGE          CREATED         CREATED BY                                      SIZE      COMMENT
3aeec311305b   4 minutes ago   /bin/sh -c #(nop)  ENTRYPOINT ["java" "-jar"…   0B        
6abd60982945   4 minutes ago   /bin/sh -c #(nop)  EXPOSE 8080                  0B        
cecc862fe6c2   4 minutes ago   /bin/sh -c #(nop) COPY file:6891f4ab1af8d445…   8.19kB    
9b9dbb28099b   4 minutes ago   /bin/sh -c #(nop) WORKDIR /app                  0B        
f34fd3e4e2d7   N/A             bazel build //java:temurin_jre_21_amd64         166MB     
<missing>      N/A             bazel build //common:locale_debian12_amd64      430kB     
<missing>      N/A             bazel build @bookworm_java//libpng16-16/amd6…   442kB     
<missing>      N/A             bazel build @bookworm_java//gcc-12-base/amd6…   106kB     
<missing>      N/A             bazel build @bookworm_java//libgcc-s1/amd64:…   143kB     
<missing>      N/A             bazel build @bookworm_java//libstdc++6/amd64…   2.34MB    
<missing>      N/A             bazel build @bookworm_java//libcrypt1/amd64:…   246kB     
<missing>      N/A             bazel build @bookworm_java//libbrotli1/amd64…   819kB     
<missing>      N/A             bazel build @bookworm_java//libuuid1/amd64:d…   86kB      
<missing>      N/A             bazel build @bookworm_java//libfontconfig1/a…   594kB     
<missing>      N/A             bazel build @bookworm_java//libexpat1/amd64:…   406kB     
<missing>      N/A             bazel build @bookworm_java//fontconfig-confi…   643kB     
<missing>      N/A             bazel build @bookworm_java//fonts-dejavu-cor…   3.12MB    
<missing>      N/A             bazel build @bookworm_java//libfreetype6/amd…   909kB     
<missing>      N/A             bazel build @bookworm_java//liblcms2-2/amd64…   434kB     
<missing>      N/A             bazel build @bookworm_java//libjpeg62-turbo/…   696kB     
<missing>      N/A             bazel build @bookworm_java//zlib1g/amd64:dat…   176kB     
<missing>      N/A             bazel build @bookworm//libc6/amd64:data_stat…   13.4MB    
<missing>      N/A             bazel build //common:cacerts_debian12_amd64     238kB     
<missing>      N/A             bazel build //common:os_release_debian12        4.1kB     
<missing>      N/A             bazel build //static:nsswitch                   4.1kB     
<missing>      N/A             bazel build //common:tmp                        0B        
<missing>      N/A             bazel build //common:group                      4.1kB     
<missing>      N/A             bazel build //common:home                       0B        
<missing>      N/A             bazel build //common:passwd                     4.1kB     
<missing>      N/A             bazel build //common:rootfs                     0B        
<missing>      N/A             bazel build @bookworm//media-types/amd64:dat…   102kB     
<missing>      N/A             bazel build @bookworm//tzdata/amd64:data_sta…   5.51MB    
<missing>      N/A             bazel build @bookworm//netbase/amd64:data_st…   45.1kB    
<missing>      N/A             bazel build @bookworm//base-files/amd64:data…   397kB    
```
У обычного файла 29 слоев, у multistage 30.  
У обычного слои apt/JDK/Maven-установки + код апи сверху; у multistage — слои сборки distroless-образа и поверх 4 слоя (WORKDIR, COPY jar, EXPOSE, ENTRYPOINT).

Меняем немного код и повторно собираем multistage.
```bash
[czar@svinoserver api]$ docker build -t api-multistage:v2 -f Dockerfile.multistaged .

Sending build context to Docker daemon  36.35kB
Step 1/11 : FROM maven:3.9-eclipse-temurin-21 AS build
 ---> c2a2c58516d1
Step 2/11 : WORKDIR /src
 ---> Using cache
 ---> 5a1b16aa00f3
Step 3/11 : COPY pom.xml .
 ---> Using cache
 ---> 99e2215edcf6
Step 4/11 : RUN mvn -B dependency:go-offline
 ---> Using cache
 ---> ac6ac629c1ae
Step 5/11 : COPY src ./src
 ---> 0793cae78cc9
Step 6/11 : RUN mvn -B package -DskipTests
 ---> Running in 216778dc6a80
[INFO] Scanning for projects...
[INFO] 
[INFO] -------------------------< ru.itmo.devops:api >-------------------------
[INFO] Building api 1.0.0
[INFO]   from pom.xml
[INFO] --------------------------------[ jar ]---------------------------------
[INFO] 
[INFO] --- resources:3.4.0:resources (default-resources) @ api ---
[INFO] skip non existing resourceDirectory /src/src/main/resources
[INFO] 
[INFO] --- compiler:3.13.0:compile (default-compile) @ api ---
[INFO] Recompiling the module because of changed source code.
[INFO] Compiling 1 source file with javac [debug release 21] to target/classes
[INFO] 
[INFO] --- resources:3.4.0:testResources (default-testResources) @ api ---
[INFO] skip non existing resourceDirectory /src/src/test/resources
[INFO] 
[INFO] --- compiler:3.13.0:testCompile (default-testCompile) @ api ---
[INFO] No sources to compile
[INFO] 
[INFO] --- surefire:3.5.4:test (default-test) @ api ---
[INFO] Tests are skipped.
[INFO] 
[INFO] --- jar:3.4.2:jar (default-jar) @ api ---
[INFO] Building jar: /src/target/api-1.0.0.jar
[INFO] ------------------------------------------------------------------------
[INFO] BUILD SUCCESS
[INFO] ------------------------------------------------------------------------
[INFO] Total time:  1.137 s
[INFO] Finished at: 2026-09-21T12:26:47Z
[INFO] ------------------------------------------------------------------------
 ---> Removed intermediate container 216778dc6a80
 ---> 206ebc730680
Step 7/11 : FROM gcr.io/distroless/java21-debian12
 ---> f34fd3e4e2d7
Step 8/11 : WORKDIR /app
 ---> Using cache
 ---> 9b9dbb28099b
Step 9/11 : COPY --from=build /src/target/api-1.0.0.jar /app/api.jar
 ---> a72207dd3cde
Step 10/11 : EXPOSE 8080
 ---> Running in 3d8d96ec6156
 ---> Removed intermediate container 3d8d96ec6156
 ---> 91866bcda789
Step 11/11 : ENTRYPOINT ["java", "-jar", "/app/api.jar"]
 ---> Running in 5794a82f02f3
 ---> Removed intermediate container 5794a82f02f3
 ---> 1bb055fef420
Successfully built 1bb055fef420
Successfully tagged api-multistage:v2
```
**Кэширование при повторной сборке:**  
После изменения кода, слои `pom.xml` и `dependency:go-offline` остались закэшированными (`pom.xml` не менялся). Кэш инвалидировался начиная с `COPY src ./src`.
Компиляция заняла буквально секунду, так как зависимости
уже были на диске => изменение кода не приводит к повторному скачиванию зависимостей.

Теперь проверим последний пункт `Запиши файл внутрь контейнера, пересоздай контейнер — файл пропал. Повтори с томом — файл остался.`
```bash
[czar@svinoserver api]$ docker run -d --name vol-test api-multistage:v2
743e90fc8fb0f014afb1a77d2f9b22b2f18da2b047c7c8b9d7ac23f5059383f3
[czar@svinoserver api]$ echo "hello, i will die :D" > /tmp/test.txt
[czar@svinoserver api]$ docker cp /tmp/test.txt vol-test:/app/test.txt
Successfully copied 21B (transferred 2.05kB) to vol-test:/app/test.txt
[czar@svinoserver api]$ cat /tmp/test.txt
hello, i will die :D

[czar@svinoserver api]$ docker rm -f vol-test
vol-test
[czar@svinoserver api]$ docker run -d --name vol-test api-multistage:v2
f7708ac8e1f9a6d8c0a62870a6e725e587e40a15fa5c26664c91e084baffcd3e
[czar@svinoserver api]$ docker diff vol-test
C /tmp
A /tmp/hsperfdata_root
A /tmp/hsperfdata_root/1
[czar@svinoserver api]$ docker cp vol-test:/app/test.txt /tmp/check2.txt 2>&1
Error response from daemon: Could not find the file /app/test.txt in container vol-test
```
В общем файл пропал, грустим :(  
Теперь то же самое с volume:
```bash
[czar@svinoserver api]$ docker volume create api-data
api-data
[czar@svinoserver api]$ docker run -d --name vol-test2 -v api-data:/data api-multistage:v2
1354f52e1634e4a06b22d8ff7825668733af62ebff1b55e9887b8d54ccc56bea
[czar@svinoserver api]$ echo "hello, i will survive :D" > /tmp/persisted.txt
[czar@svinoserver api]$ docker cp /tmp/persisted.txt vol-test2:/data/persisted.txt
Successfully copied 25B (transferred 2.05kB) to vol-test2:/data/persisted.txt
[czar@svinoserver api]$ docker diff vol-test2
A /data
C /tmp
A /tmp/hsperfdata_root
A /tmp/hsperfdata_root/1
[czar@svinoserver api]$ docker rm -f vol-test2
vol-test2
[czar@svinoserver api]$ docker run -d --name vol-test2 -v api-data:/data api-multistage:v2
978d2145f4e5766ddf26c018c8c49f89b1e16ff806b9bf68d975583787a645f1
[czar@svinoserver api]$ docker cp vol-test2:/data/persisted.txt /tmp/check3.txt
Successfully copied 25B (transferred 2.05kB) to /tmp/check3.txt
[czar@svinoserver api]$ cat /tmp/check3.txt
hello, i will survive :D
```
Все чикибомбони!

# Часть 7
> Запусти образ под gVisor (runsc) и сравни его изоляцию с обычным Docker и своим скриптом. Разберись, чем gVisor устроен иначе и почему его считают более изолированным. Отдельно ответь на вопрос: что у обычного контейнера остаётся общим с хостом в любом случае и почему это предел контейнерной изоляции. Выводы — в README.

Поставили `gVisor` и т.д.
Добавим в `/etc/docker/daemon.json` регистрацию рантайма и перезаупстим докер через `systemctl`:
```json
{
  "runtimes": {
    "runsc": {
      "path": "/usr/bin/runsc"
    }
  }
}
```
Собираем и запускаем контейнер как обычно. Смотрим рантайм и пид:
```bash
[czar@svinoserver api]$ docker build -f Dockerfile.multistaged -t api:multistage .

Sending build context to Docker daemon  36.35kB
...
Successfully built 1bb055fef420
Successfully tagged api:multistage

docker run -d \
  --name api-runc \
  -p 8080:8080 \
  api:multistage

[czar@svinoserver api]$ docker run -d \
  --name api-runc \
  -p 8080:8080 \
  api:multistage
64cc9b1f60f35c5312ad2a57a15e5453a7f792bf3d4936a5881a83b176b7dc45

[czar@svinoserver api]$ curl http://localhost:8080/health
OK

[czar@svinoserver api]$ docker inspect api-runc --format '{{.HostConfig.Runtime}}'
runc

[czar@svinoserver api]$ docker inspect api-runc --format '{{.State.Pid}}'
454006
```
Теперь то же самое через `gVisor`:
```bash
[czar@svinoserver api]$ docker rm -f api-runc
api-runc

[czar@svinoserver api]$ docker run -d \
  --name api-runsc \
  --runtime=runsc \
  -p 8080:8080 \
  api:multistage
4c47a857d38b1450064e471c30614c81f615d75a03e08bc19cd9e72e1a3de672

[czar@svinoserver api]$ curl http://localhost:8080/health
OK

[czar@svinoserver api]$ docker inspect api-runsc --format '{{.HostConfig.Runtime}}'
runsc

[czar@svinoserver api]$ docker inspect api-runsc --format '{{.State.Pid}}'
464332
```
Через `runsc` все чикибомбони 😎

ОТДЕЛЬНО ОТВЕЧАЮ НА ВОПРОС:  
Обычный контейнер не является отдельной виртуалкой. Несмотря на изоляцию PID, networkи т.д. контейнер и host используют одно ядро Linux. Namespaces, cgroups, capabilities и seccomp ограничивают доступ процесса к ресурсам и системным интерфейсам, но не создают отдельного экземпляра ядра.  
Это создает уязвимость, которая может привести к выходу из контейнера. gVisor добавляет дополнительный слой, уменьшая прямое взаимодействие приложения с host kernel.












# КОНЕЦ
