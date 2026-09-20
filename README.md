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

