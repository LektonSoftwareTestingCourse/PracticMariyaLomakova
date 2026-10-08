# A3. C4 Level 3 — Component

## Чего не хватало

> Компонентного уровня нет вообще: в документации нет схемы «что внутри сервиса». Нужно дорисовать с нуля, читая пакеты `services/authorization/...` и `services/card-management/...`.

Общая архитектурная схема показывала Authorization и Card Management только как отдельные сервисы, но не раскрывала их контроллеры, бизнес-логику, HTTP-клиенты, репозитории и outbox-процессор.

## Что сделано

```mermaid
flowchart LR
    subgraph authorization["Authorization Service"]
        ac["AuthControllerImpl"]
        as["AuthServiceImpl"]
        cmc["CardManagementClientImpl"]
        blc["BinLookupClient"]
        lur["LimitUsageRepository"]

        ac -->|"вызов метода"| as
        as -->|"HTTP client"| cmc
        as -->|"HTTP client"| blc
        as -->|"JPA"| lur
    end

    subgraph card_management["Card Management Service"]
        cc["CardController"]
        cs["CardServiceImpl"]
        cr["CardRepository"]
        rr["ReservationRepository"]
        rrr["ReservationRollbackRepository"]
        en["CardEventNotifierImpl"]
        oer["OutboxRepository"]
        op["OutboxProcessor"]
        oep["OutboxEventProcessorImpl"]

        cc -->|"вызов метода"| cs
        cs -->|"JPA adapter"| cr
        cs -->|"JPA adapter"| rr
        cs -->|"JPA adapter"| rrr
        cs -->|"domain event"| en
        en -->|"save PENDING"| oer
        op -->|"findPending"| oer
        op -->|"process"| oep
        oep -->|"update status"| oer
    end

    cms["Card Management HTTP API"]
    bin["BIN Lookup HTTP API"]
    authdb[("PostgreSQL<br/>limit_usage")]
    carddb[("PostgreSQL<br/>cards / reservations / outbox_event")]
    rmq[["RabbitMQ<br/>smp.card-events"]]

    cmc -->|"sync HTTP"| cms
    blc -->|"sync HTTP"| bin
    lur -->|"sync JDBC"| authdb
    cr -->|"sync JDBC"| carddb
    rr -->|"sync JDBC"| carddb
    rrr -->|"sync JDBC"| carddb
    oer -->|"sync JDBC"| carddb
    oep -.->|"async AMQP card.*"| rmq
```

**В коде:**

**Authorization**

- Контроллер делегирует в сервис: [`AuthControllerImpl.java`](../../../services/authorization/src/main/java/com/processing/authorization/controller/AuthControllerImpl.java).
- Бизнес-логика и зависимости: [`AuthServiceImpl.java`](../../../services/authorization/src/main/java/com/processing/authorization/services/AuthServiceImpl.java).
- HTTP к Card Management: [`CardManagementClientImpl.java`](../../../services/authorization/src/main/java/com/processing/authorization/client/CardManagementClientImpl.java).
- HTTP к BIN Lookup: [`BinLookupClient.java`](../../../services/authorization/src/main/java/com/processing/authorization/client/BinLookupClient.java).
- Доступ к `limit_usage`: [`LimitUsageRepository.java`](../../../services/authorization/src/main/java/com/processing/authorization/repositories/LimitUsageRepository.java).

**Card Management**

- REST-контроллер CRUD/reserve/rollback: [`CardController.java`](../../../services/card-management/src/main/java/com/processing/cardmanagement/controllers/CardController.java).
- Бизнес-логика: [`CardServiceImpl.java`](../../../services/card-management/src/main/java/com/processing/cardmanagement/services/CardServiceImpl.java).
- Сохранение карточного события в outbox: [`CardEventNotifierImpl.java`](../../../services/card-management/src/main/java/com/processing/cardmanagement/events/CardEventNotifierImpl.java).
- Периодическое чтение outbox: [`OutboxProcessor.java`](../../../services/card-management/src/main/java/com/processing/cardmanagement/services/OutboxProcessor.java).
- Публикация и смена статуса события: [`OutboxEventProcessorImpl.java`](../../../services/card-management/src/main/java/com/processing/cardmanagement/services/OutboxEventProcessorImpl.java).


В итоге: для двух сервисов показан путь от HTTP-контроллера к бизнес-логике и инфраструктурным зависимостям. Синхронные вызовы обозначены сплошными стрелками, асинхронная публикация — пунктирной.

Эта диаграмма пригодится выбора тестовых двойников: где stub/mock (HTTP-клиенты), а где настоящая зависимость (репозиторий, очередь).

