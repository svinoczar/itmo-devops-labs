# API test service

Тестовый Java API (Java 21)

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
