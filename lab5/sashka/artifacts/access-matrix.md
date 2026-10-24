| Источник | Назначение | Ожидалось | Фактически | Результат |
| --- | --- | --- | --- | --- |
| api | api | не проверяется | allow | self-traffic |
| api | worker | deny | deny | OK |
| api | postgres | allow | allow | OK |
| worker | api | deny | deny | OK |
| worker | worker | не проверяется | deny | self-traffic |
| worker | postgres | allow | allow | OK |
| client | api | allow | allow | OK |
| client | worker | deny | deny | OK |
| client | postgres | deny | deny | OK |
| outsider | api | deny | deny | OK |
| outsider | worker | deny | deny | OK |
| outsider | postgres | deny | deny | OK |
