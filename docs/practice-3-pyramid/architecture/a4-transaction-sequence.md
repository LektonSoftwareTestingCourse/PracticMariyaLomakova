# A4. Sequence — путь транзакции

## Чего не хватало

> Mermaid sequence в `architecture.md` покрывает только happy path. Нет declined и reversal, хотя в коде есть и `DeclineOutcome` (все ветки отказа), и `publisherConfirmType=CORRELATED` с откатом. Эти два сценария — самые ценные для тестирования, и их надо дорисовать.

Почти. Исходная sequence-диаграмма уже содержала обобщённые ветки `approved` и `declined`. В ней не были раскрыты конкретный отказ для карты в статусе `BLOCKED`, сценарий ошибки публикации и последующий rollback, а также граница между синхронной обработкой и асинхронной доставкой. Кроме того, фактический код выполняет откат через `POST /api/internal/rollback`, а не отправкой сообщения с `mti=0400`. Также настройка correlated publisher confirms присутствует, но ожидание подтверждения в течение двух секунд не реализовано.

## Что сделано

```mermaid
sequenceDiagram
    actor Client as Terminal / Merchant
    participant GW as Gateway
    participant SW as Switch
    participant AUTH as Authorization
    participant BIN as BIN Lookup
    participant CMS as Card Management
    participant RMQ as RabbitMQ
    participant LOG as Transaction Logger
    participant NS as Notification

    Client->>GW: POST /api/transactions
    GW->>GW: validate request
    GW->>SW: POST /api/internal/route
    SW->>SW: BIN → issuerId
    SW->>AUTH: POST /api/internal/authorize
    AUTH->>CMS: GET /api/cards/{pan}
    CMS-->>AUTH: Card
    AUTH->>BIN: GET /api/bin/{bin}
    BIN-->>AUTH: issuerId / fallback

    alt Happy path
        AUTH->>AUTH: status, expiry, balance, limits
        AUTH->>CMS: POST /api/cards/{pan}/reserve
        CMS->>CMS: reservation + outbox PENDING
        CMS-->>AUTH: balance reduced
        AUTH-->>SW: APPROVED 00, RRN, authCode
        SW->>RMQ: publish transaction.log (async AMQP)
        RMQ-->>LOG: transaction-log (async AMQP)
        LOG->>LOG: save transaction
        CMS->>RMQ: publish card.* from outbox (async AMQP)
        RMQ-->>NS: card-notifications (async AMQP)
        NS->>NS: save notification
        SW-->>GW: APPROVED 00
        GW-->>Client: HTTP 200
    else Declined: Card.status = BLOCKED
        AUTH->>AUTH: status check stops algorithm
        AUTH-->>SW: DECLINED 05, CARD_BLOCKED
        SW->>RMQ: publish declined transaction (async AMQP)
        RMQ-->>LOG: transaction-log
        SW-->>GW: DECLINED 05
        GW-->>Client: response
        Note over AUTH,CMS: reserve is not called
    else Reversal after publish failure
        AUTH->>CMS: POST /api/cards/{pan}/reserve
        CMS-->>AUTH: reserved
        AUTH-->>SW: APPROVED 00
        SW-xRMQ: publish failed / not confirmed
        SW->>AUTH: POST /api/internal/rollback
        AUTH->>CMS: POST /api/cards/{pan}/rollback
        CMS->>CMS: Reservation RESERVED → ROLLED_BACK<br/>balance restored
        CMS-->>AUTH: rollback completed
        AUTH-->>SW: rollback APPROVED
        SW-->>GW: DECLINED 96
        GW-->>Client: system error
    end
```

В ТЗ откат назван reversal с `mti=0400`, но в текущем коде Switch вызывает отдельный `POST /api/internal/rollback` с `RollbackRequest`, в котором поля `mti` нет. Метод `AuthorizationRequest.forReversal()` создаёт `0400`, но в данном пути не вызывается.

Также конфигурация включает correlated publisher confirms, однако `LoggerClient` выполняет `convertAndSend` без `CorrelationData` и явного ожидания подтверждения 2 секунды. Фактический rollback запускается только при синхронном `AmqpException`.

**По коду:**

- Валидация входного запроса: [`TransactionValidationFilter.java`](../../../services/gateway/src/main/java/com/processing/gateway/validation/TransactionValidationFilter.java).
- Полная оркестрация, публикация и rollback: [`RouteService.java`](../../../services/switch/src/main/java/com/processing/service/RouteService.java).
- HTTP authorize/rollback: [`AuthorizationClient.java`](../../../services/switch/src/main/java/com/processing/service/AuthorizationClient.java).
- Публикация без явного ожидания confirm: [`LoggerClient.java`](../../../services/switch/src/main/java/com/processing/service/LoggerClient.java).
- Проверки карты и резервирование: [`AuthServiceImpl.java`](../../../services/authorization/src/main/java/com/processing/authorization/services/AuthServiceImpl.java).
- Резервирование и возврат баланса: [`CardServiceImpl.java`](../../../services/card-management/src/main/java/com/processing/cardmanagement/services/CardServiceImpl.java).
- Формат rollback-запроса: [`RollbackRequest.java`](../../../services/common/src/main/java/com/processing/common/dto/authorization/RollbackRequest.java).
- Неиспользуемое создание `mti=0400`: [`AuthorizationRequest.java`](../../../services/common/src/main/java/com/processing/common/dto/authorization/AuthorizationRequest.java).

**В итоге:** в одной диаграмме показаны happy path, declined для карты `BLOCKED` и откат резервирования при ошибке публикации. Сплошные сообщения — синхронные HTTP-вызовы, сообщения к RabbitMQ и от него — асинхронный AMQP.

Благодаря этой диаграмме мы видим, какие проверки можно сделать на нижних уровнях, а что требует полного контура.

