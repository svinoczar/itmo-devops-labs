# DevOps Course

## 0. Свой сервис
Первым делом был написан main.py на Flask (самостоятельно, так как мы УЧЕМСЯ) 
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

