# The most ✨_aesthetic_✨ containerization and orchestration lab 1 report

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
Как видим, все доступные ✨_namespaces_✨ принадлежат хосту (czar). Нас такой расклад не очень устраивает, поэтому работаем по заданию и наводим твердый порядок (изолируем процесс по неймспейсам) жесткой рукой (`unshare`).  
По заданию нам нужны следующие неймспейсы: pid, mount, net, uts, ipc и user.  

К сожалению прочитать `unshare --help` и просто выполнить `unshare -pmnuiU <сервис>` не прокатило и пришлось думать мозгом(  
P.S. ох... долго пришлось думать мозгом... и долго мучить мой endeavouros home server... 