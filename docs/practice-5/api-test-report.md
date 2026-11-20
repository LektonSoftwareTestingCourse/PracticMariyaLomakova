# API-тесты СМП — отчёт

## 1. Покрытие

| Эндпоинты | Проверяемые статусы | Кол-во проверок | Статус | Тесты |
|---|---|---|---|---|
| `GET /health` | 200, 503 | 2 | Подтверждено | `OperationsApiTest`, `FaultApiTest` |
| `POST /api/cards` | 201, 400, 404, 500, 503 | 19 | Подтверждено | `CardsApiTest`, `ContractEdgesApiTest`, `StorageStatusesApiTest`, `FaultApiTest` |
| `GET /api/cards/{pan}` | 200, 400, 404, 500, 503 | 15 | Подтверждено | `CardsApiTest`, `OperationsApiTest`, `StorageStatusesApiTest`, `FaultApiTest` |
| `GET /api/cards` | 200, 400, 413, 500, 503 | 35 | Подтверждено | `CardsApiTest`, `CardQueriesApiTest`, `StorageStatusesApiTest`, `FaultApiTest` |
| `PATCH /api/cards/{pan}` | 200, 400, 404, 500, 503 | 12 | Подтверждено | `CardsApiTest`, `ContractEdgesApiTest`, `StorageStatusesApiTest`, `FaultApiTest` |
| `DELETE /api/cards/{pan}` | 204, 400, 404, 500, 503 | 4 | Подтверждено | `CardsApiTest`, `ContractEdgesApiTest`, `StorageStatusesApiTest`, `FaultApiTest` |
| `POST /api/cards/generate` | 201, 400, 404, 500, 503 | 8 | Подтверждено | `CardsApiTest`, `ContractEdgesApiTest`, `StorageStatusesApiTest`, `FaultApiTest` |
| `POST /api/cards/{pan}/reserve` | 200, 400, 402, 404, 409, 500, 503 | 13 | Подтверждено | `CardsApiTest`, `ContractEdgesApiTest`, `StorageStatusesApiTest`, `FaultApiTest` |
| `POST /api/cards/{pan}/rollback` | 200, 400, 404, 409, 500, 503 | 8 | Подтверждено | `CardsApiTest`, `ContractEdgesApiTest`, `StorageStatusesApiTest`, `FaultApiTest` |
| `POST /api/cards/bulk-update` | 200, 400, 500, 503 | 4 | Подтверждено | `CardsApiTest`, `ContractEdgesApiTest`, `StorageStatusesApiTest`, `FaultApiTest` |
| `POST /api/transactions` | 200, 400, 429, 503 | 73 | Подтверждено | `AuthorizationApiTest`, `GatewayValidationApiTest`, `DependencyFailuresApiTest`, `DeliveryApiTest`, `FaultApiTest` |
| `POST /api/internal/route` (Switch) | 200, 400 | 2 | Подтверждено | `AuthorizationApiTest`, `ContractEdgesApiTest` |
| `POST /api/internal/authorize` (Authorization) | 200, 400, 403, 404, 422, 503 | 31 | Подтверждено | `AuthorizationApiTest`, `DependencyFailuresApiTest`, `ContractEdgesApiTest` |
| `POST /api/internal/rollback` (Authorization) | 200, 400, 404, 409, 503 | 12 | Подтверждено | `AuthorizationApiTest`, `DependencyFailuresApiTest`, `ContractEdgesApiTest` |
| `POST /api/internal/log` (Logger) | 201, 200, 400, 405, 409, 503 | 55 | Подтверждено | `LoggerApiTest`, `FaultApiTest` |
| `GET /api/transactions/search` | 200, 400, 503 | 61 | Подтверждено | `SearchApiTest`, `StorageStatusesApiTest`, `DeliveryApiTest`, `LoggerApiTest`, `FaultApiTest` |
| `GET /api/transactions/export` | 200, 400, 503 | 4 | Подтверждено | `SearchApiTest`, `StorageStatusesApiTest`, `FaultApiTest` |
| `GET /api/dashboard/stats` | 200, 503 (зависимость, открытый circuit breaker) | 4 | Подтверждено | `OperationsApiTest`, `StorageStatusesApiTest`, `FaultApiTest` |
| `GET /api/dashboard/recent` | 200, 400, 503 | 7 | Подтверждено | `OperationsApiTest`, `StorageStatusesApiTest`, `FaultApiTest` |
| `GET /api/dashboard/charts` | 200, 400, 503 | 8 | Подтверждено | `OperationsApiTest`, `StorageStatusesApiTest`, `FaultApiTest` |
| `POST /api/simulator/terminal/run` | 200, 400, 503 | 9 | Подтверждено | `OperationsApiTest`, `FaultApiTest` |
| `POST /api/simulator/terminal/start-continuous`, `/stop` | 200, 400, 422 для start, 503 | 4 | Подтверждено | `OperationsApiTest`, `FaultApiTest` |
| `POST /api/simulator/merchant/run` | 200, 400, 404, 503 | 10 | Подтверждено | `OperationsApiTest`, `ContractEdgesApiTest`, `FaultApiTest` |
| `POST /api/simulator/merchant/fee` | 200, 400, 404, 500, 503 | 8 | Подтверждено | `OperationsApiTest`, `ContractEdgesApiTest`, `StorageStatusesApiTest`, `FaultApiTest` |
| `GET /api/simulator/merchants` | 404 через Gateway, 200/500 напрямую | 2 | Подтверждено | `ContractEdgesApiTest`, `StorageStatusesApiTest`, расхождение D03 |
| `GET /api/bin/{bin}` (зависимость Authorization) | 200, 404, 500 | 4 | Подтверждено | `OperationsApiTest`, `ContractEdgesApiTest` |
| `GET /v3/api-docs`, `/switch-docs`, `/logger-docs`, `/terminal-docs`, `/merchant-docs`, `/cards-docs` | 200 | 6 | Подтверждено | `OperationsApiTest` |
| `GET /actuator/health`, `/actuator/info`, `/actuator/metrics`, `/actuator/prometheus` Gateway | 200 | 4 | Подтверждено | `OperationsApiTest` |


| Класс | Кол-во проверок в каждом прогоне | Статус |
|---|---|---|
| `AuthorizationApiTest` | 17 | PASS |
| `CardQueriesApiTest` | 29 | PASS |
| `CardsApiTest` | 40 | PASS |
| `ContractEdgesApiTest` | 49 | PASS |
| `DeliveryApiTest` | 2 | PASS |
| `DependencyFailuresApiTest` | 20 | PASS |
| `FaultApiTest` | 24 | PASS |
| `GatewayValidationApiTest` | 56 | PASS |
| `LoggerApiTest` | 54 | PASS |
| `OperationsApiTest` | 41 | PASS |
| `SearchApiTest` | 47 | PASS |
| `StorageStatusesApiTest` | 16 | PASS |

Запросы к публичным контрактам проходят через Gateway. Внутренние контракты
вызываются по собственным адресам сервисов. Для Logger `/api/internal/log`
через Gateway маршрут отсутствует по конфигурации, поэтому используется прямой HTTP.

В каждом ответе проверяется тело. Для контрактов без тела проверяется пустое тело
(204 удаления, 200 start/stop, 404/500 заглушки BIN). Дополнительно проверяются
`Content-Type`, `X-Cache`, `Content-Disposition` и `Retry-After`.

## 2. Бизнес-отказы

| Исход | Код | Причина | Путь достижения и подготовка | Проверка |
|---|---|---|---|---|
| Карта отсутствует | 14 | `CARD_NOT_FOUND` | Неизвестный PAN с известным BIN и неизвестный BIN | Внешний HTTP 200, `DECLINED` |
| Карта заблокирована | 05 | `CARD_BLOCKED` | Создание карты и PATCH BLOCKED, отдельно ответ CMS 402 | Внешний 200 и внутренний 403 |
| Карта не активирована | 05 | `CARD_INACTIVE` | PATCH INACTIVE | Внешний 200 и внутренний 403 |
| Карта просрочена | 54 | `CARD_EXPIRED` | PATCH EXPIRED и отдельно дата операции после конца срока ACTIVE-карты | Проверены оба пути |
| Недостаточно средств | 51 | `INSUFFICIENT_FUNDS` | Баланс 1000, сумма 1001, отдельно CMS reserve 402 | Внешний 200 и внутренний 422 |
| Дневной лимит | 61 | `EXCEEDS_AMOUNT_LIMIT` | Лимит 1000, успешная операция 1000, затем операция 1 | Проверены отказ и остаток 9000 |
| Месячный лимит | 61 | `EXCEEDS_AMOUNT_LIMIT` | Лимиты 1000/1500, операции 1000 и 501 в разные дни одного месяца | Отказ второй операции |
| CMS недоступен | 96 | `SERVICE_UNAVAILABLE` | Остановка CMS, ответы CMS 500/503 при GET и reserve | Внутренний 503 |
| Непредусмотренный отказ резерва | 96 | `RESERVATION_FAILED` | HTTP 409 от reserve через тестовый посредник | Внутренний 503 и исходный баланс |
| Неопределённое состояние карты | 05 | `UNKNOWN_REASON` | HTTP 200 от CMS с null status через посредник | Внутренний 400 |
| БД Authorization недоступна | 96 | `DB_UNAVAILABLE` | Остановка отдельной БД Authorization | Внутренний 503 с доменным телом |
| Неизвестный резерв для отката | 14 | `TRANSACTION_NOT_FOUND` | Новая карта и новый RRN, отдельно CMS 400/404 | Внутренний 404 |
| Повторный откат | 05 | `ALREADY_ROLLED_BACK` | Авторизация, откат и повтор, отдельно CMS 409 | Внутренний 409 |
| Непредусмотренный отказ отката | 96 | `ROLLBACK_FAILED` | HTTP 502 от CMS rollback через посредник | Внутренний 503 |
| Брокер не принял одобренную операцию | 96 | `System error` | Остановка RabbitMQ после подготовки карты | DECLINED, восстановленный баланс, отсутствие записи |

## 3. Валидация

| Поля/параметры | Классы и границы | Тесты |
|---|---|---|
| Обязательные поля авторизации | Каждое поле отсутствует отдельно, null, пустая строка, пробел | `GatewayValidationApiTest` |
| mti | 0100 и неподдерживаемый 0200, длина 3/5 внутреннего DTO | Gateway и внутренний Authorization |
| PAN | 15/16/17 символов, буква вместо цифры, неизвестный корректный PAN | Gateway, CMS, Logger |
| amount | -1, 0, положительная сумма, баланс -1/баланс/баланс +1 | Gateway, Logger, авторизация, резерв |
| currencyCode | Длина 2/3/4, нецифровой код в CMS/Logger | Валидация отдельных границ сервисов |
| mcc | 3/4/5 символов и нецифровой символ | Gateway и Logger |
| stan, processingCode | Длина 5/6/7, нецифровые значения Logger | Внутренний DTO и Logger |
| terminalId, merchantId | Ограничения 8/15, короткие/длинные значения | Внутренний DTO и Logger |
| Дата | Отсутствие, ISO 8601 со смещением, формат без смещения | Gateway, внутренний DTO и Logger |
| UUID и JSON | Нечитаемый JSON, массив вместо объекта, неверный UUID | Gateway и Logger |
| Создание карты | BIN 5/6/7, неверный символ, имя, валюта, отрицательные лимиты | `CardsApiTest` |
| PATCH | Отрицательные лимиты, дневной выше месячного, месячный ниже дневного | `ContractEdgesApiTest` |
| reserve/rollback | Формат RRN, чужой PAN, неактивное состояние, повтор | Карты и Authorization |
| Генерация карт | count 0/-1/1/2 и превышение 10000 | `CardsApiTest` |
| Пагинация транзакций | limit 0/1/2/500/501, offset -1/0/1/конец выборки | `SearchApiTest` |
| Фильтры транзакций | Все 8 фильтров, все 28 пар, отрицательный контроль каждого из двух фильтров | `SearchApiTest.filterPairs` |
| Диапазон транзакций | Начало дня включительно, следующий день исключён, инверсия диапазона | `SearchApiTest` |
| Фильтры карт | status, bin, issuerId, startDate, endDate, все 10 пар и их отрицательный контроль, ошибочные типы | `CardQueriesApiTest` |
| Пагинация карт | limit 0/1/10000/10001, offset -1/0/больше выборки | `CardsApiTest`, `CardQueriesApiTest` |
| recent/charts | limit 0/1/500/501, hour/day и неверная гранулярность | `OperationsApiTest` |
| Симуляторы | Все перечисленные сценарии, count/tps 0 и 1, неизвестный сценарий | `OperationsApiTest`, `ContractEdgesApiTest` |

## 4. Расхождения контракта и реализации

| ID | Декларировано | Наблюдается и источник | Классификация и решение |
|---|---|---|---|
| D01 | MTI ответа 0110 в docs/api-spec.md | AuthorizationResponse.approved/declined возвращают mti запроса 0100, подтверждено API | Открытый дефект формата. Регрессионный набор явно фиксирует 0100 с этим обоснованием, схема допускает четырёхзначный MTI. Потребитель, ожидающий 0110, затронут |
| D02 | expiryDate в формате MMYY | CardModel содержит YearMonth, фактический JSON YYYY-MM | Известное ограничение представления. Проверки используют наблюдаемый ISO YearMonth, потребителю нужен согласованный парсер |
| D03 | Контроллер GET /api/simulator/merchants существует | Прямой вызов 200, Gateway 404 из-за маршрута /api/simulator/merchant/** | Открытый дефект маршрутизации. Оба ответа проверяются отдельным сценарием |
| D04 | POST по умолчанию не идемпотентен, лекция отдельно отмечает риск | Повтор /api/transactions с тем же STAN создаёт другой RRN и второе резервирование | Известное ограничение. Для финансового потребителя опасен слепой retry. Проверены оба остатка и две записи |
| D05 | Краткая спецификация перечисляет не все API и фильтры | В коде есть PATCH/DELETE/reserve/rollback/bulk-update, export, charts, issuerId и mcc | Неполная документация. Реализованные HTTP-контракты включены в реестр |
| D06 | Ветка DB_UNAVAILABLE должна возвращать доменный отказ | При остановке БД ошибка rollback JPA перекрывала ответ HTTP 500 | Исправлено в AuthControllerImpl. Теперь 503, 96, DB_UNAVAILABLE даже при ошибке начала/завершения транзакции |
| D07 | Ошибка должна сообщать нарушенное правило | Logger терял object-level ошибку диапазона, CMS не указывал имя поля, Authorization отдавал общий 400 | Исправлены обработчики валидации. Поле/правило доступны в message |
| D08 | Принятая через Gateway операция должна появиться в поиске | Logger отвергал Serializable TransactionRequest из-за отсутствующего allowlist десериализации | Исправлено ограниченным списком DTO и используемых JDK-типов. Сквозной HTTP-тест стал зелёным |
| D09 | Logger документирует 503 при недоступной БД | Ошибка начала транзакции возвращала 500 | Добавлена обработка CannotCreateTransactionException. Проверены log/search/export/stats/recent/charts при отказе БД |
| D10 | Карточный API должен отвергать неправильное представление запроса | Общий обработчик превращал ошибки типа параметра и JSON в 500 | Добавлены специфические обработчики 400 |
| D11 | Общий submission-guide называет практику 5 E2E и ручным запуском | Приложенное practice-05.md требует API и автоматический CI | Для этой работы принят приложенный файл. Создан отдельный автоматический workflow |
| D12 | Удалённая карта должна перестать читаться | После GET, DELETE 204 и повторного GET Gateway возвращал старую ACTIVE-карту из кеша | Исправлена инвалидация кеша при 204. Перед исправлением API-тест упал на фактическом 200 вместо ожидаемого 404 |
| D13 | Недоступность downstream должна давать 503 и учитываться circuit breaker | При остановленном Logger NoRouteToHostException приводил к 500, цепь не открывалась | Исправлен DownstreamExceptionUtils: сетевые ошибки маршрута и DNS распознаются как недоступность. HTTP-тест проверяет три отказа, Retry-After у открытой цепи и восстановление |
| D14 | Повтор сообщения с тем же UUID и данными должен возвращать 200 | В CI время с 9 знаками после секунды округлялось в timestamp(6), а точное сравнение давало ложный 409 | Исправлен TransactionMapper: время сравнивается с округлением до микросекунд. Проверены округление вниз и вверх, перенос секунды и настоящий конфликт при изменении времени на 1 мкс |

## 5. Обоснование достаточности

Набор составлен по спецификации, контроллерам, правилам валидации и маршрутам
Gateway. 
Проверяются:
- успешные запросы, 
- обязательность и формат полей, 
- граничные значения, 
- бизнес-отказы,
- повторная доставка. 

HTTP-статус проверяется вместе с телом ответа, а код отказа – вместе с причиной. Для отказов, достижимых разными
путями, проверены отдельные сценарии.

Для поиска транзакций проверены все 28 пар фильтров, для карт – все 10 пар.
В каждой паре проверяется совпадение и исключение записи при изменении каждого
из двух параметров. Дополнительно проверены пагинация, границы дат, пустая
выборка и некорректные параметры.

Каждый тест готовит данные через API и использует собственные идентификаторы.
Баланс, лимиты и статус карты задаются явно, результаты поиска привязаны к данным
теста. Три последовательных прогона на одном стеке прошли без падений.

Сквозной сценарий подтверждает, что операция из Gateway появляется в поиске.
При отказе брокера проверяются возврат денег и отсутствие записи в течение
10 секунд после его восстановления. Ожидания выполняются через Awaitility.
Для всех 20 бизнес-маршрутов Gateway проверены недоступность зависимого сервиса
и ответ 503. Отдельно – срабатывание и восстановление circuit breaker.

## 6. Доказательства работоспособности проверок

| Тест | Внесённое изменение | Подтверждённый результат |
|---|---|---|
| GatewayValidationApiTest.shouldIdentifyRequiredFieldWhenItIsAbsent | Ожидаемое VALIDATION_ERROR заменено на BROKEN_VALIDATION | 11 прогонов, 11 failures, 0 errors |
| AuthorizationApiTest.shouldApproveAndReserveWhenCardAndRequestAreValid | Ожидаемый доменный код 00 заменён на 99 | 3 прогона, 3 failures, 0 errors |
| LoggerApiTest.shouldKeepOneOriginalRecordWhenDeliveryIsRepeated | Убрана предварительная проверка findById в TransactionService.store | Повтор ошибочно возвращает 201 вместо 200, 1 прогон, 1 failure, 0 errors |


## 7. Порядок запуска

Предварительные требования — Java 21, работающий Docker с Compose, свободные
локальные порты 18080–18085, 18088, 18096, 18099. Maven используется из PATH

Из корня репозитория выполнить

```bash
bash scripts/run-api-tests.sh
```

Команда собирает выбранные сервисы и HTTP-посредник, поднимает отдельный Compose-проект,
ожидает готовности и запускает `mvn -f services/pom.xml -Papi-tests clean verify`.
По завершении сохраняет диагностику и удаляет только тестовые контейнеры и их тома.
Существующий проект `smp` использует другой Compose-проект и другие порты.

Для трёх последовательных прогонов выполнить

```bash
API_TEST_RUNS=3 bash scripts/run-api-tests.sh
```

Результаты находятся в `target/practice-5/run-{1,2,3}/surefire-reports/`,
логи в `target/practice-5/logs/`. При необходимости оставить стенд после проверки
используется `API_TEST_KEEP_STACK=1`. Повторный запуск на уже поднятом тестовом
стенде поддерживает `API_TEST_REUSE_STACK=1`.

Workflow `.github/workflows/api-tests.yml` автоматически запускается при push
изменений тестов/сервисов в ветку `practice-5-*` и выполняет три полных прогона.
JUnit XML и логи публикуются артефактом `practice-5-api-results` даже при ошибке.
Локально выполнена проверка конфигурации и той же команды запуска.
Удалённый Actions run появится после отправки изменений владельцем репозитория.
