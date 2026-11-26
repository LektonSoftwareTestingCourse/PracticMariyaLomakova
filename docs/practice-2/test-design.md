# Test-design ядра: Authorization и Card-Management

## 0. Тестовый базис и обозначения требований

Тест-дизайн основан на `tz/02-gateway.md`, `tz/04-authorization.md`, `tz/05-card-management.md`, модели данных и API-контрактах репозитория, при этом сейчас используются только классы эквивалентности, граничные значения и попарное тестирование из лекции 3 (по условию тз).

| Код | Требование |
|---|---|
| `AUTH-REQ-1` | Получить карту; отсутствующая карта → DECLINED, `responseCode=14` |
| `AUTH-REQ-2` | Проверить статус ACTIVE/INACTIVE/BLOCKED/EXPIRED до остальных бизнес-проверок |
| `AUTH-REQ-3` | Истёкший `expiryDate` → DECLINED, `responseCode=54` |
| `AUTH-REQ-4` | Дневное использование вместе с текущей суммой не превышает `dailyLimit` |
| `AUTH-REQ-5` | Месячное использование вместе с текущей суммой не превышает `monthlyLimit` |
| `AUTH-REQ-6` | Сумма не превышает `availableBalance`; иначе `responseCode=51` |
| `AUTH-REQ-7` | При выполнении всех условий зарезервировать сумму и вернуть APPROVED, `responseCode=00`, RRN и authCode |
| `AUTH-REQ-8` | Недоступность Card-Management → DECLINED, `responseCode=05`, причина `ISSUER_TIMEOUT` |
| `AUTH-REQ-9` | Недоступность Bin Lookup не блокирует авторизацию: используется issuerId, полученный от Switch |
| `AUTH-REQ-10` | Публичный транзакционный запрос содержит положительный `amount`; ноль и отрицательное значение отклоняются Gateway до вызова Authorization |
| `CM-REQ-1` | Создать карту: PAN из BIN по Луну, `expiryDate` через 3 года, статус ACTIVE |
| `CM-REQ-2` | Получить существующую карту по PAN; отсутствующая карта → HTTP 404 |
| `CM-REQ-3` | Выдать список карт с пагинацией и фильтрами `status`/`bin` |
| `CM-REQ-4` | Частично обновить статус, лимиты и баланс карты |
| `CM-REQ-5` | Мягко удалить карту; DELETED не возвращается и не участвует в транзакциях |
| `CM-REQ-6` | Сгенерировать заданное положительное число карт по непустому списку шестизначных BIN |
| `CM-REQ-7` | PAN содержит 16 цифр и корректную контрольную цифру Луна; BIN содержит 6 цифр; `expiryDate` имеет формат MMYY |
| `CM-REQ-8` | Зарезервировать положительную сумму при достаточном балансе; обработать нехватку средств и повторный RRN |

## 1. Классы эквивалентности

Позитивные и негативные классы разделены. Несколько валидных классов объединяются только в базовом позитивном кейсе, а каждый невалидный класс проверяется отдельно.

### 1.1. Authorization

| Параметр | Классы и представители | Валидность / ожидаемое поведение | Тест-кейс |
|---|---|---|---|
| Наличие карты | существующая карта; отсутствующий PAN | существующая — продолжение; отсутствующая — DECLINED 14 | `AUTH-CE-001`, `AUTH-CE-002` |
| Статус | ACTIVE; INACTIVE; BLOCKED; EXPIRED | ACTIVE — продолжение; остальные — бизнес-отказ по статусу | `AUTH-CE-001`, `AUTH-CE-003`–`005` |
| Сумма транзакции | положительная `10000`; нулевая `0`; отрицательная `-1` | положительная — продолжение; ноль и отрицательная — HTTP 400 до вызова Authorization | `AUTH-CE-001`, `AUTH-CE-006`, `AUTH-CE-007` |
| Срок действия | не истёк — следующий месяц; истёк — предыдущий месяц | не истёк — продолжение; истёк — DECLINED 54; текущий месяц проверяется как граница | `AUTH-CE-001`, `AUTH-BVA-010`–`012` |
| Дневной лимит | итог не превышает лимит; итог превышает лимит | продолжение; DECLINED 61; точки непосредственно у границы вынесены в BVA | `AUTH-CE-001`, `AUTH-BVA-001`–`003` |
| Месячный лимит | итог не превышает лимит; итог превышает лимит | продолжение; DECLINED 61; точки непосредственно у границы вынесены в BVA | `AUTH-CE-001`, `AUTH-BVA-004`–`006` |
| Баланс | сумма не превышает баланс; сумма превышает баланс | продолжение; DECLINED 51; точки непосредственно у границы вынесены в BVA | `AUTH-CE-001`, `AUTH-BVA-007`–`009` |
| Card-Management | доступен; недоступен | доступен — обычный алгоритм; недоступен — DECLINED 05 | `AUTH-CE-001`, `AUTH-CE-008` |
| Bin Lookup | доступен; недоступен | оба класса допустимы; при недоступности используется fallback | `AUTH-CE-001`, `AUTH-CE-009` |

### 1.2. Card-Management

| Параметр / операция | Классы и представители | Валидность / ожидаемое поведение | Тест-кейс |
|---|---|---|---|
| Создание карты | полностью валидный запрос | HTTP 201; ACTIVE; PAN по Луну; expiry +3 года | `CM-CE-001` |
| BIN | 6 цифр; неверная длина; содержит букву | первый класс валиден; каждый невалидный класс → HTTP 400 | `CM-CE-001`, `CM-BVA-004`–`006`, `CM-CE-002` |
| Имя держателя | допустимые заглавные символы; пустое; строчные символы | первый класс валиден; два невалидных проверяются отдельно | `CM-CE-001`, `CM-CE-003`, `CM-CE-004` |
| Currency code | 3 цифры; 2 цифры; буква среди цифр | первый класс валиден; два невалидных → HTTP 400 | `CM-CE-001`, `CM-CE-005`, `CM-CE-006` |
| Daily/monthly limit | неотрицательный; отрицательный daily; отрицательный monthly | валидный запрос либо отдельный HTTP 400 | `CM-CE-001`, `CM-CE-007`, `CM-CE-008` |
| Получение по PAN | существующая; отсутствующая; неверный формат | HTTP 200; HTTP 404; HTTP 400 | `CM-CE-009`, `CM-CE-010`, `CM-BVA-001`–`003` |
| Фильтр статуса | ACTIVE; INACTIVE; BLOCKED; EXPIRED | возвращаются неудалённые карты выбранного статуса | `CARD-PW-*`, `CM-CE-011` |
| Удалённая карта | отсутствует; присутствует DELETED | DELETED не возвращается в GET и списке | `CARD-PW-*`, `CM-CE-012` |
| PATCH | существующая карта; отсутствующая карта | HTTP 200 с частичным обновлением; HTTP 404 | `CM-CE-013`, `CM-CE-014` |
| Генерация | count ≥ 1 и BIN непусты; count < 1; bins пуст; BIN неверного формата | HTTP 201 либо отдельный HTTP 400 | `CM-CE-015`, `CM-BVA-010`–`012`, `CM-CE-016`, `CM-CE-017` |
| Reserve amount | положительная и достаточная; положительная выше баланса; неположительная | HTTP 200; HTTP 402; HTTP 400 | `CM-CE-018`, `CM-CE-019`, `CM-BVA-019`–`021` |
| Reserve RRN | 12 цифр новый; неверный формат; повторный | HTTP 200; HTTP 400; HTTP 409 | `CM-CE-018`, `CM-CE-020`, `CM-CE-021` |
| Reserve PAN | существующая; отсутствующая карта | обработка либо HTTP 404 | `CM-CE-018`, `CM-CE-022` |

## 2. Граничные значения

Для каждой границы используются три точки: ниже, на границе (ON) и выше.

### 2.1. Authorization

| Граница | Ниже | ON | Выше | Ожидаемое поведение | Кейсы |
|---|---:|---:|---:|---|---|
| `dailyUsage + amount`, `dailyLimit=100000` | 99999 | 100000 | 100001 | APPROVED; APPROVED; DECLINED 61 | `AUTH-BVA-001`–`003` |
| `monthlyUsage + amount`, `monthlyLimit=1000000` | 999999 | 1000000 | 1000001 | APPROVED; APPROVED; DECLINED 61 | `AUTH-BVA-004`–`006` |
| `amount`, `availableBalance=10000` | 9999 | 10000 | 10001 | APPROVED; APPROVED; DECLINED 51 | `AUTH-BVA-007`–`009` |
| `expiryDate` относительно текущего месяца | предыдущий месяц | текущий месяц | следующий месяц | DECLINED 54; APPROVED; APPROVED | `AUTH-BVA-010`–`012` |

### 2.2. Card-Management

| Граница | Ниже | ON | Выше | Ожидаемое поведение | Кейсы |
|---|---|---|---|---|---|
| Длина PAN = 16 | 15 цифр | 16 цифр | 17 цифр | HTTP 400; существующая карта HTTP 200; HTTP 400 | `CM-BVA-001`–`003` |
| Длина BIN = 6 | 5 цифр | 6 цифр | 7 цифр | HTTP 400; карта создана; HTTP 400 | `CM-BVA-004`–`006` |
| `expiryDate` относительно текущего месяца | предыдущий | текущий | следующий | Card-Management возвращает сохранённое MMYY без решения об авторизации | `CM-BVA-007`–`009` |
| `count` генератора, минимум 1 | 0 | 1 | 2 | HTTP 400; создана 1 карта; созданы 2 карты | `CM-BVA-010`–`012` |
| Reserve amount, баланс 10000 | 9999 | 10000 | 10001 | HTTP 200; HTTP 200 и нулевой баланс; HTTP 402 | `CM-BVA-013`–`015` |
| `limit` списка, минимум 1 | 0 | 1 | 2 | HTTP 400; не более 1; не более 2 карт | `CM-BVA-016`–`018` |
| Reserve `amount`, минимум 1 копейка | 0 | 1 | 2 | HTTP 400; HTTP 200; HTTP 200 | `CM-BVA-019`–`021` |

`expiryDate` не является входным полем публичных create/patch endpoint Card-Management, поэтому здесь не придумывается отсутствующая валидация: три даты подготавливаются как состояние тестовых карт, а проверяется корректная выдача MMYY. Решение «истёкла ли карта» проверяет Authorization.

Граничные точки, которые одновременно являются представителями соседних классов эквивалентности, не дублируются отдельными CE-кейсами: один тест-кейс учитывается в покрытии обеих техник.

## 3. Попарное тестирование (pairwise)

Параметры выделены из требований, невозможные сочетания исключены constraints, наборы с покрытием допустимых пар сгенерированы PICT (как в лекции 3). Негативные классы Card-Management вынесены в отдельные кейсы, чтобы не смешивать несколько невалидных значений.

Значения `above` в PICT-модели описывают бизнес-состояния валидного формата, а не несколько некорректно заполненных полей. Их сочетание необходимо для pairwise; ожидаемый результат определяется первым нарушенным правилом. 

### 3.1. Authorization

| Параметр | Значения | Смысл |
|---|---|---|
| `card_status` | ACTIVE, INACTIVE, BLOCKED, EXPIRED | Статус карты; только ACTIVE продолжает алгоритм |
| `amount_vs_daily` | below, equal, above | Дневное использование вместе с текущей суммой относительно лимита |
| `amount_vs_monthly` | below, equal, above | Месячное использование вместе с текущей суммой относительно лимита |
| `amount_vs_balance` | below, equal, above | Текущая сумма относительно баланса |
| `expiry` | valid, current_month, expired | Будущий, текущий или прошедший месяц |
| `terminal_type` | pos, atm, ecom | Тип терминала из контракта |
| `mcc` | grocery, restaurant, electronics, travel | Категории с представителями 5411, 5812, 5732, 4722 |

Constraints фиксируют лимиты и баланс как несущественные после отказа по статусу и баланс как несущественный после отказа по сроку.

### 3.2. Card-Management

Модель построена для обязательной выборки `GET /api/cards`: все факторы задаются query-параметрами или воспроизводимым состоянием БД. Случайные выходные значения генератора не используются как управляемые факторы.

| Параметр | Значения | Смысл |
|---|---|---|
| `page_limit` | default_50, one, ten, hundred | Отсутствующий limit либо положительное значение |
| `offset_position` | zero, within_results, equal_result_count, above_result_count | Позиция offset относительно размера выборки |
| `status_filter` | omitted, ACTIVE, INACTIVE, BLOCKED | Отсутствующий или поддерживаемый фильтр статуса |
| `bin_filter` | omitted, matching, nonmatching | Отсутствующий, совпадающий или несовпадающий BIN |
| `matching_cards` | zero, one, many | Число подходящих неудалённых карт в предусловии |
| `deleted_cards` | absent, present | Наличие DELETED-карт в БД |

Constraints исключают ненулевой результат при несовпадающем BIN и невозможные позиции offset для нулевой/единичной выборки. 

### 3.3. Критичные сочетания

- `AUTH-MAN-001`: ACTIVE + валидный срок + одновременное превышение дневного, месячного лимитов и баланса. В pairwise-наборе такой полной комбинации нет; кейс добавлен вручную для проверки порядка алгоритма.
- Сочетание Card-Management ACTIVE + совпадающий BIN + несколько подходящих карт + присутствие DELETED уже покрыто `CARD-PW-008`.
- Сочетание Authorization ACTIVE + три значения equal + текущий месяц уже покрыто `AUTH-PW-011`.

## 4. Тест-кейсы

### 4.1. Общие тестовые данные Authorization

Во всех кейсах (если не сказано иначе): существующая карта `4000003458730237`, сумма `10000` коп., `dailyLimit=100000`, `monthlyLimit=1000000`; остальные поля запроса валидны. Профили PICT переводятся в конкретные данные так:

| Профиль | Конкретные данные |
|---|---|
| daily below / equal / above | daily usage 80000 / 90000 / 90001; вместе с amount получается 90000 / 100000 / 100001 |
| monthly below / equal / above | monthly usage 900000 / 990000 / 990001; итог 910000 / 1000000 / 1000001 |
| balance below / equal / above | availableBalance 20000 / 10000 / 9999; amount соответственно ниже / равна / выше баланса |
| expiry valid / current_month / expired | следующий / текущий / предыдущий календарный месяц |
| terminal pos / atm / ecom | POS + TERM0001 / ATM + ATM00001 / ECOM + ECOM0001 |
| MCC grocery / restaurant / electronics / travel | 5411 / 5812 / 5732 / 4722 |

#### 4.1.1. Классы эквивалентности Authorization

| ID | Требование | Вид | Источник | Предусловие | Шаги | Ожидаемый результат |
|---|---|---|---|---|---|---|
| AUTH-CE-001 | `AUTH-REQ-1`–`7`, `9`, `10` | позитивный | Валидные классы | ACTIVE; положительная сумма; срок следующий месяц; usage и amount ниже лимитов/баланса; зависимости доступны | POST `/api/transactions` с общим валидным запросом | APPROVED 00; RRN 12 цифр; authCode 6 символов; баланс уменьшен на 10000 |
| AUTH-CE-002 | `AUTH-REQ-1` | негативный | Класс «карта отсутствует» | PAN валидного формата отсутствует в CMS; остальные данные валидны | Отправить общий запрос с отсутствующим PAN | DECLINED 14, `CARD_NOT_FOUND`; резервирования нет |
| AUTH-CE-003 | `AUTH-REQ-2` | негативный | Класс INACTIVE | Карта INACTIVE; остальные условия валидны | Отправить общий запрос | DECLINED 05, `CARD_INACTIVE`; последующие проверки не выполняются |
| AUTH-CE-004 | `AUTH-REQ-2` | негативный | Класс BLOCKED | Карта BLOCKED; остальные условия валидны | Отправить общий запрос | DECLINED 05, `CARD_BLOCKED`; резервирования нет |
| AUTH-CE-005 | `AUTH-REQ-2` | негативный | Класс EXPIRED status | Статус карты EXPIRED, дата будущая; остальные условия валидны | Отправить общий запрос | DECLINED 54, `CARD_EXPIRED`; проверка статуса имеет приоритет |
| AUTH-CE-006 | `AUTH-REQ-10` | негативный | Класс amount=0 | Все поля, кроме amount, валидны | POST `/api/transactions` с amount=0 | HTTP 400; Switch и Authorization не вызваны; резервирования нет |
| AUTH-CE-007 | `AUTH-REQ-10` | негативный | Класс amount<0 | Все поля, кроме amount, валидны | POST `/api/transactions` с amount=-1 | HTTP 400; Switch и Authorization не вызваны; резервирования нет |
| AUTH-CE-008 | `AUTH-REQ-8` | негативный | Класс CMS unavailable | Card-Management недоступен; остальные условия валидны | Отправить общий запрос | DECLINED 05, причина `ISSUER_TIMEOUT`; резервирования нет |
| AUTH-CE-009 | `AUTH-REQ-9` | позитивный | Класс Bin Lookup unavailable | Bin Lookup недоступен; issuerId передан Switch; остальные условия валидны | Отправить общий запрос | Авторизация продолжается по fallback и завершается APPROVED 00 |

#### 4.1.2. Граничные значения Authorization

| ID | Требование | Вид | Источник | Предусловие и вход | Шаги | Ожидаемый результат |
|---|---|---|---|---|---|---|
| AUTH-BVA-001 | `AUTH-REQ-4` | позитивный | daily below | daily usage + amount = 99999 | Отправить запрос | APPROVED 00 |
| AUTH-BVA-002 | `AUTH-REQ-4` | позитивный | daily ON | daily usage + amount = 100000 | Отправить запрос | APPROVED 00 |
| AUTH-BVA-003 | `AUTH-REQ-4` | негативный | daily above | daily usage + amount = 100001 | Отправить запрос | DECLINED 61 |
| AUTH-BVA-004 | `AUTH-REQ-5` | позитивный | monthly below | monthly usage + amount = 999999 | Отправить запрос | APPROVED 00 |
| AUTH-BVA-005 | `AUTH-REQ-5` | позитивный | monthly ON | monthly usage + amount = 1000000 | Отправить запрос | APPROVED 00 |
| AUTH-BVA-006 | `AUTH-REQ-5` | негативный | monthly above | monthly usage + amount = 1000001 | Отправить запрос | DECLINED 61 |
| AUTH-BVA-007 | `AUTH-REQ-6` | позитивный | balance below | balance=10000, amount=9999 | Отправить запрос | APPROVED 00; баланс 1 |
| AUTH-BVA-008 | `AUTH-REQ-6` | позитивный | balance ON | balance=10000, amount=10000 | Отправить запрос | APPROVED 00; баланс 0 |
| AUTH-BVA-009 | `AUTH-REQ-6` | негативный | balance above | balance=10000, amount=10001 | Отправить запрос | DECLINED 51 |
| AUTH-BVA-010 | `AUTH-REQ-3` | негативный | expiry below | ACTIVE; expiry предыдущий месяц | Отправить запрос | DECLINED 54 |
| AUTH-BVA-011 | `AUTH-REQ-3` | позитивный | expiry ON | ACTIVE; expiry текущий месяц | Отправить запрос | APPROVED 00 |
| AUTH-BVA-012 | `AUTH-REQ-3` | позитивный | expiry above | ACTIVE; expiry следующий месяц | Отправить запрос | APPROVED 00 |

#### 4.1.3. Pairwise Authorization

| ID | Требование | Вид | Источник | Предусловие | Шаги | Ожидаемый результат |
|---|---|---|---|---|---|---|
| AUTH-PW-001 | `AUTH-REQ-2` | негативный | Pairwise, строка 1 | Карта `INACTIVE`; профили: expiry=`valid`, daily=`below`, monthly=`below`, balance=`below` | Отправить общий запрос авторизации с terminal=`pos`, MCC=`electronics` | DECLINED; responseCode=05; declineReason=CARD_INACTIVE; резервирование не выполнено |
| AUTH-PW-002 | `AUTH-REQ-5` | негативный | Pairwise, строка 2 | Карта `ACTIVE`; профили: expiry=`current_month`, daily=`below`, monthly=`above`, balance=`above` | Отправить общий запрос авторизации с terminal=`ecom`, MCC=`travel` | DECLINED; responseCode=61; declineReason=EXCEEDS_AMOUNT_LIMIT; резервирование не выполнено |
| AUTH-PW-003 | `AUTH-REQ-2` | негативный | Pairwise, строка 3 | Карта `EXPIRED`; профили: expiry=`expired`, daily=`below`, monthly=`below`, balance=`below` | Отправить общий запрос авторизации с terminal=`atm`, MCC=`grocery` | DECLINED; responseCode=54; declineReason=CARD_EXPIRED; резервирование не выполнено |
| AUTH-PW-004 | `AUTH-REQ-4` | негативный | Pairwise, строка 4 | Карта `ACTIVE`; профили: expiry=`valid`, daily=`above`, monthly=`equal`, balance=`equal` | Отправить общий запрос авторизации с terminal=`pos`, MCC=`grocery` | DECLINED; responseCode=61; declineReason=EXCEEDS_AMOUNT_LIMIT; резервирование не выполнено |
| AUTH-PW-005 | `AUTH-REQ-2` | негативный | Pairwise, строка 5 | Карта `BLOCKED`; профили: expiry=`current_month`, daily=`below`, monthly=`below`, balance=`below` | Отправить общий запрос авторизации с terminal=`atm`, MCC=`restaurant` | DECLINED; responseCode=05; declineReason=CARD_BLOCKED; резервирование не выполнено |
| AUTH-PW-006 | `AUTH-REQ-2` | негативный | Pairwise, строка 6 | Карта `INACTIVE`; профили: expiry=`current_month`, daily=`below`, monthly=`below`, balance=`below` | Отправить общий запрос авторизации с terminal=`ecom`, MCC=`grocery` | DECLINED; responseCode=05; declineReason=CARD_INACTIVE; резервирование не выполнено |
| AUTH-PW-007 | `AUTH-REQ-2` | негативный | Pairwise, строка 7 | Карта `INACTIVE`; профили: expiry=`expired`, daily=`below`, monthly=`below`, balance=`below` | Отправить общий запрос авторизации с terminal=`atm`, MCC=`restaurant` | DECLINED; responseCode=05; declineReason=CARD_INACTIVE; резервирование не выполнено |
| AUTH-PW-008 | `AUTH-REQ-2` | негативный | Pairwise, строка 8 | Карта `BLOCKED`; профили: expiry=`expired`, daily=`below`, monthly=`below`, balance=`below` | Отправить общий запрос авторизации с terminal=`ecom`, MCC=`electronics` | DECLINED; responseCode=05; declineReason=CARD_BLOCKED; резервирование не выполнено |
| AUTH-PW-009 | `AUTH-REQ-2` | негативный | Pairwise, строка 9 | Карта `BLOCKED`; профили: expiry=`expired`, daily=`below`, monthly=`below`, balance=`below` | Отправить общий запрос авторизации с terminal=`pos`, MCC=`travel` | DECLINED; responseCode=05; declineReason=CARD_BLOCKED; резервирование не выполнено |
| AUTH-PW-010 | `AUTH-REQ-5` | негативный | Pairwise, строка 10 | Карта `ACTIVE`; профили: expiry=`valid`, daily=`equal`, monthly=`above`, balance=`above` | Отправить общий запрос авторизации с terminal=`atm`, MCC=`electronics` | DECLINED; responseCode=61; declineReason=EXCEEDS_AMOUNT_LIMIT; резервирование не выполнено |
| AUTH-PW-011 | `AUTH-REQ-7` | позитивный | Pairwise, строка 11 | Карта `ACTIVE`; профили: expiry=`current_month`, daily=`equal`, monthly=`equal`, balance=`equal` | Отправить общий запрос авторизации с terminal=`pos`, MCC=`travel` | APPROVED; responseCode=00; RRN из 12 цифр; authCode из 6 символов; баланс уменьшен на 10000 |
| AUTH-PW-012 | `AUTH-REQ-4` | негативный | Pairwise, строка 12 | Карта `ACTIVE`; профили: expiry=`valid`, daily=`above`, monthly=`below`, balance=`equal` | Отправить общий запрос авторизации с terminal=`ecom`, MCC=`restaurant` | DECLINED; responseCode=61; declineReason=EXCEEDS_AMOUNT_LIMIT; резервирование не выполнено |
| AUTH-PW-013 | `AUTH-REQ-2` | негативный | Pairwise, строка 13 | Карта `EXPIRED`; профили: expiry=`current_month`, daily=`below`, monthly=`below`, balance=`below` | Отправить общий запрос авторизации с terminal=`ecom`, MCC=`electronics` | DECLINED; responseCode=54; declineReason=CARD_EXPIRED; резервирование не выполнено |
| AUTH-PW-014 | `AUTH-REQ-6` | негативный | Pairwise, строка 14 | Карта `ACTIVE`; профили: expiry=`valid`, daily=`equal`, monthly=`below`, balance=`above` | Отправить общий запрос авторизации с terminal=`pos`, MCC=`restaurant` | DECLINED; responseCode=51; declineReason=INSUFFICIENT_FUNDS; резервирование не выполнено |
| AUTH-PW-015 | `AUTH-REQ-2` | негативный | Pairwise, строка 15 | Карта `EXPIRED`; профили: expiry=`valid`, daily=`below`, monthly=`below`, balance=`below` | Отправить общий запрос авторизации с terminal=`pos`, MCC=`restaurant` | DECLINED; responseCode=54; declineReason=CARD_EXPIRED; резервирование не выполнено |
| AUTH-PW-016 | `AUTH-REQ-3` | негативный | Pairwise, строка 16 | Карта `ACTIVE`; профили: expiry=`expired`, daily=`equal`, monthly=`above`, balance=`below` | Отправить общий запрос авторизации с terminal=`pos`, MCC=`grocery` | DECLINED; responseCode=54; declineReason=CARD_EXPIRED; резервирование не выполнено |
| AUTH-PW-017 | `AUTH-REQ-4` | негативный | Pairwise, строка 17 | Карта `ACTIVE`; профили: expiry=`valid`, daily=`above`, monthly=`equal`, balance=`above` | Отправить общий запрос авторизации с terminal=`atm`, MCC=`travel` | DECLINED; responseCode=61; declineReason=EXCEEDS_AMOUNT_LIMIT; резервирование не выполнено |
| AUTH-PW-018 | `AUTH-REQ-4` | негативный | Pairwise, строка 18 | Карта `ACTIVE`; профили: expiry=`valid`, daily=`above`, monthly=`above`, balance=`equal` | Отправить общий запрос авторизации с terminal=`atm`, MCC=`restaurant` | DECLINED; responseCode=61; declineReason=EXCEEDS_AMOUNT_LIMIT; резервирование не выполнено |
| AUTH-PW-019 | `AUTH-REQ-2` | негативный | Pairwise, строка 19 | Карта `INACTIVE`; профили: expiry=`expired`, daily=`below`, monthly=`below`, balance=`below` | Отправить общий запрос авторизации с terminal=`ecom`, MCC=`travel` | DECLINED; responseCode=05; declineReason=CARD_INACTIVE; резервирование не выполнено |
| AUTH-PW-020 | `AUTH-REQ-3` | негативный | Pairwise, строка 20 | Карта `ACTIVE`; профили: expiry=`expired`, daily=`above`, monthly=`equal`, balance=`below` | Отправить общий запрос авторизации с terminal=`ecom`, MCC=`electronics` | DECLINED; responseCode=54; declineReason=CARD_EXPIRED; резервирование не выполнено |
| AUTH-PW-021 | `AUTH-REQ-6` | негативный | Pairwise, строка 21 | Карта `ACTIVE`; профили: expiry=`valid`, daily=`below`, monthly=`equal`, balance=`above` | Отправить общий запрос авторизации с terminal=`ecom`, MCC=`grocery` | DECLINED; responseCode=51; declineReason=INSUFFICIENT_FUNDS; резервирование не выполнено |
| AUTH-PW-022 | `AUTH-REQ-2` | негативный | Pairwise, строка 22 | Карта `EXPIRED`; профили: expiry=`current_month`, daily=`below`, monthly=`below`, balance=`below` | Отправить общий запрос авторизации с terminal=`ecom`, MCC=`travel` | DECLINED; responseCode=54; declineReason=CARD_EXPIRED; резервирование не выполнено |
| AUTH-PW-023 | `AUTH-REQ-7` | позитивный | Pairwise, строка 23 | Карта `ACTIVE`; профили: expiry=`valid`, daily=`below`, monthly=`equal`, balance=`equal` | Отправить общий запрос авторизации с terminal=`ecom`, MCC=`electronics` | APPROVED; responseCode=00; RRN из 12 цифр; authCode из 6 символов; баланс уменьшен на 10000 |
| AUTH-PW-024 | `AUTH-REQ-7` | позитивный | Pairwise, строка 24 | Карта `ACTIVE`; профили: expiry=`valid`, daily=`equal`, monthly=`equal`, balance=`below` | Отправить общий запрос авторизации с terminal=`ecom`, MCC=`restaurant` | APPROVED; responseCode=00; RRN из 12 цифр; authCode из 6 символов; баланс уменьшен на 10000 |
| AUTH-PW-025 | `AUTH-REQ-2` | негативный | Pairwise, строка 25 | Карта `BLOCKED`; профили: expiry=`valid`, daily=`below`, monthly=`below`, balance=`below` | Отправить общий запрос авторизации с terminal=`atm`, MCC=`grocery` | DECLINED; responseCode=05; declineReason=CARD_BLOCKED; резервирование не выполнено |
| AUTH-PW-026 | `AUTH-REQ-4` | негативный | Pairwise, строка 26 | Карта `ACTIVE`; профили: expiry=`current_month`, daily=`above`, monthly=`equal`, balance=`above` | Отправить общий запрос авторизации с terminal=`atm`, MCC=`grocery` | DECLINED; responseCode=61; declineReason=EXCEEDS_AMOUNT_LIMIT; резервирование не выполнено |
| AUTH-MAN-001 | `AUTH-REQ-4` | негативный | Критичное сочетание вручную | ACTIVE; valid; daily/monthly/balance=`above`; POS; grocery | Отправить общий запрос | DECLINED 61 по дневному лимиту как первой нарушенной проверке; резервирования нет |

### 4.2. Общие тестовые данные Card-Management

Валидный create-запрос: BIN `400000`, имя `IVAN IVANOV`, currency `643`, dailyLimit `15000000`, monthlyLimit `300000000`, initialBalance `100000000`. Для list-кейсов `matching_cards=many` означает 3 подходящие неудалённые карты; `deleted_cards=present` — дополнительную DELETED-карту. Совпадающий BIN — `400000`, несовпадающий валидный BIN — `499999`.

#### 4.2.1. Классы эквивалентности Card-Management

| ID | Требование | Вид | Источник | Предусловие | Шаги | Ожидаемый результат |
|---|---|---|---|---|---|---|
| CM-CE-001 | `CM-REQ-1`, `CM-REQ-7` | позитивный | Валидные классы create | BIN известен; валидное тело | POST `/api/cards` с валидным create-запросом | HTTP 201; ACTIVE; PAN 16 цифр с пройденным Луном и префиксом 400000; expiry текущий месяц +3 года |
| CM-CE-002 | `CM-REQ-1`, `CM-REQ-7` | негативный | BIN содержит букву | Остальные поля валидны | POST create с bin=`40000A` | HTTP 400; карта не создана |
| CM-CE-003 | `CM-REQ-1` | негативный | Пустое имя | Остальные поля валидны | POST create с пустым cardholderName | HTTP 400; карта не создана |
| CM-CE-004 | `CM-REQ-1` | негативный | Недопустимый регистр имени | Остальные поля валидны | POST create с cardholderName=`Ivan Ivanov` | HTTP 400; карта не создана |
| CM-CE-005 | `CM-REQ-1` | негативный | Currency из 2 цифр | Остальные поля валидны | POST create с currencyCode=`64` | HTTP 400; карта не создана |
| CM-CE-006 | `CM-REQ-1` | негативный | Currency содержит букву | Остальные поля валидны | POST create с currencyCode=`64A` | HTTP 400; карта не создана |
| CM-CE-007 | `CM-REQ-1` | негативный | Отрицательный dailyLimit | Остальные поля валидны | POST create с dailyLimit=`-1` | HTTP 400; карта не создана |
| CM-CE-008 | `CM-REQ-1` | негативный | Отрицательный monthlyLimit | Остальные поля валидны | POST create с monthlyLimit=`-1` | HTTP 400; карта не создана |
| CM-CE-009 | `CM-REQ-2` | позитивный | Существующий PAN | Карта существует и не DELETED | GET `/api/cards/{pan}` | HTTP 200; возвращена нужная карта |
| CM-CE-010 | `CM-REQ-2` | негативный | Отсутствующий PAN | PAN 16 цифр отсутствует; остальные условия валидны | GET по отсутствующему PAN | HTTP 404 |
| CM-CE-011 | `CM-REQ-3` | позитивный | Фильтр EXPIRED | Есть неудалённые EXPIRED и карты других статусов | GET списка со status=EXPIRED | HTTP 200; возвращены только EXPIRED; total согласован |
| CM-CE-012 | `CM-REQ-5` | позитивный | DELETED скрыт | Существующая карта удалена через DELETE | GET по PAN и GET списка | HTTP 404 по PAN; карта отсутствует в списке |
| CM-CE-013 | `CM-REQ-4` | позитивный | PATCH существующей карты | Карта существует | PATCH только availableBalance=`50000` | HTTP 200; баланс изменён, остальные поля сохранены |
| CM-CE-014 | `CM-REQ-4` | негативный | PATCH отсутствующей карты | Валидный PAN отсутствует; тело PATCH валидно | PATCH с валидным телом | HTTP 404; карта не создана |
| CM-CE-015 | `CM-REQ-6`, `CM-REQ-7` | позитивный | Валидная генерация | BIN 400000 и 400001 | POST generate: count=20, два BIN | HTTP 201; создано 20 карт, распределение равномерно, PAN по Луну |
| CM-CE-016 | `CM-REQ-6` | негативный | Пустой bins | count=1; остальные данные валидны | POST generate с пустым списком | HTTP 400; карты не созданы |
| CM-CE-017 | `CM-REQ-6` | негативный | Невалидный BIN генератора | count=1; остальные данные валидны | POST generate с bin=`40000` | HTTP 400; карты не созданы |
| CM-CE-018 | `CM-REQ-8` | позитивный | Положительная достаточная сумма | balance=10000; amount=5000; новый RRN | POST reserve | HTTP 200; баланс 5000; резервирование сохранено |
| CM-CE-019 | `CM-REQ-8` | негативный | Положительная сумма выше баланса | balance=10000; amount=15000; новый RRN | POST reserve | HTTP 402; баланс не изменён |
| CM-CE-020 | `CM-REQ-8` | негативный | RRN неверной длины | Карта существует; amount=5000 | POST reserve с 11-значным RRN | HTTP 400; баланс не изменён |
| CM-CE-021 | `CM-REQ-8` | негативный | Повторный RRN | Для карты уже есть резервирование с этим RRN; amount валиден | Повторить reserve | HTTP 409; повторного списания нет |
| CM-CE-022 | `CM-REQ-8` | негативный | Карта отсутствует | Валидный отсутствующий PAN; amount и RRN валидны | POST reserve | HTTP 404; резервирование не создано |

#### 4.2.2. Граничные значения Card-Management

| ID | Требование | Вид | Источник | Предусловие / вход | Шаги | Ожидаемый результат |
|---|---|---|---|---|---|---|
| CM-BVA-001 | `CM-REQ-2`, `CM-REQ-7` | негативный | PAN length below | PAN из 15 цифр | GET по PAN | HTTP 400 |
| CM-BVA-002 | `CM-REQ-2`, `CM-REQ-7` | позитивный | PAN length ON | Существующий PAN из 16 цифр | GET по PAN | HTTP 200 |
| CM-BVA-003 | `CM-REQ-2`, `CM-REQ-7` | негативный | PAN length above | PAN из 17 цифр | GET по PAN | HTTP 400 |
| CM-BVA-004 | `CM-REQ-1`, `CM-REQ-7` | негативный | BIN length below | bin=`40000` | POST create | HTTP 400 |
| CM-BVA-005 | `CM-REQ-1`, `CM-REQ-7` | позитивный | BIN length ON | bin=`400000` | POST create | HTTP 201; PAN начинается с BIN |
| CM-BVA-006 | `CM-REQ-1`, `CM-REQ-7` | негативный | BIN length above | bin=`4000000` | POST create | HTTP 400 |
| CM-BVA-007 | `CM-REQ-7` | позитивный | expiry below | В фикстуре карта с expiry предыдущий MMYY, не DELETED | GET по PAN | HTTP 200; возвращён точный четырёхзначный MMYY |
| CM-BVA-008 | `CM-REQ-7` | позитивный | expiry ON | Карта с expiry текущий MMYY | GET по PAN | HTTP 200; возвращён точный MMYY |
| CM-BVA-009 | `CM-REQ-7` | позитивный | expiry above | Карта с expiry следующий MMYY | GET по PAN | HTTP 200; возвращён точный MMYY |
| CM-BVA-010 | `CM-REQ-6` | негативный | count below | count=0, bins=[400000] | POST generate | HTTP 400 |
| CM-BVA-011 | `CM-REQ-6` | позитивный | count ON | count=1, bins=[400000] | POST generate | HTTP 201; одна карта |
| CM-BVA-012 | `CM-REQ-6` | позитивный | count above | count=2, bins=[400000] | POST generate | HTTP 201; две карты |
| CM-BVA-013 | `CM-REQ-8` | позитивный | reserve below | balance=10000, amount=9999 | POST reserve | HTTP 200; баланс 1 |
| CM-BVA-014 | `CM-REQ-8` | позитивный | reserve ON | balance=10000, amount=10000 | POST reserve | HTTP 200; баланс 0 |
| CM-BVA-015 | `CM-REQ-8` | негативный | reserve above | balance=10000, amount=10001 | POST reserve | HTTP 402; баланс 10000 |
| CM-BVA-016 | `CM-REQ-3` | негативный | page limit below | limit=0 | GET списка | HTTP 400 |
| CM-BVA-017 | `CM-REQ-3` | позитивный | page limit ON | limit=1, минимум 2 подходящие карты | GET списка | HTTP 200; не более 1 карты |
| CM-BVA-018 | `CM-REQ-3` | позитивный | page limit above | limit=2, минимум 3 подходящие карты | GET списка | HTTP 200; не более 2 карт |
| CM-BVA-019 | `CM-REQ-8` | негативный | reserve minimum below | Карта существует; amount=0; RRN валиден | POST reserve | HTTP 400; баланс не изменён |
| CM-BVA-020 | `CM-REQ-8` | позитивный | reserve minimum ON | balance ≥ 2; amount=1; новый RRN | POST reserve | HTTP 200; баланс уменьшен на 1 |
| CM-BVA-021 | `CM-REQ-8` | позитивный | reserve minimum above | balance ≥ 2; amount=2; новый RRN | POST reserve | HTTP 200; баланс уменьшен на 2 |

#### 4.2.3. Pairwise Card-Management

| ID | Требование | Вид | Источник | Предусловие | Шаги | Ожидаемый результат |
|---|---|---|---|---|---|---|
| CARD-PW-001 | `CM-REQ-3`, `CM-REQ-5` | позитивный | Pairwise, строка 1 | Подготовлено 3 подходящих неудалённых карт; deleted=`absent`; bin=`matching` | Выполнить `GET /api/cards?limit=10&offset=3&status=ACTIVE&bin=400000` | HTTP 200; `total=3`; в `cards` 0 элементов; `DELETED` отсутствуют |
| CARD-PW-002 | `CM-REQ-3`, `CM-REQ-5` | позитивный | Pairwise, строка 2 | Подготовлено 0 подходящих неудалённых карт; deleted=`present`; bin=`omitted` | Выполнить `GET /api/cards?limit=100&status=ACTIVE` | HTTP 200; `total=0`; в `cards` 0 элементов; `DELETED` отсутствуют |
| CARD-PW-003 | `CM-REQ-3`, `CM-REQ-5` | позитивный | Pairwise, строка 3 | Подготовлена 1 подходящая неудалённая карта; deleted=`present`; bin=`omitted` | Выполнить `GET /api/cards?limit=10&offset=2&status=BLOCKED` | HTTP 200; `total=1`; в `cards` 0 элементов; `DELETED` отсутствуют |
| CARD-PW-004 | `CM-REQ-3`, `CM-REQ-5` | позитивный | Pairwise, строка 4 | Подготовлено 0 подходящих неудалённых карт; deleted=`absent`; bin=`nonmatching` | Выполнить `GET /api/cards?limit=1&status=BLOCKED&bin=499999` | HTTP 200; `total=0`; в `cards` 0 элементов; `DELETED` отсутствуют |
| CARD-PW-005 | `CM-REQ-3`, `CM-REQ-5` | позитивный | Pairwise, строка 5 | Подготовлено 3 подходящих неудалённых карт; deleted=`present`; bin=`matching` | Выполнить `GET /api/cards?limit=10&offset=1&status=INACTIVE&bin=400000` | HTTP 200; `total=3`; в `cards` 2 элемента; `DELETED` отсутствуют |
| CARD-PW-006 | `CM-REQ-3`, `CM-REQ-5` | позитивный | Pairwise, строка 6 | Подготовлена 1 подходящая неудалённая карта; deleted=`present`; bin=`matching` | Выполнить `GET /api/cards?limit=1&offset=1&bin=400000` | HTTP 200; `total=1`; в `cards` 0 элементов; `DELETED` отсутствуют |
| CARD-PW-007 | `CM-REQ-3`, `CM-REQ-5` | позитивный | Pairwise, строка 7 | Подготовлено 3 подходящих неудалённых карт; deleted=`absent`; bin=`omitted` | Выполнить `GET /api/cards?offset=1&status=BLOCKED` | HTTP 200; `total=3`; в `cards` 2 элемента; `DELETED` отсутствуют |
| CARD-PW-008 | `CM-REQ-3`, `CM-REQ-5` | позитивный | Pairwise, строка 8 | Подготовлено 3 подходящих неудалённых карт; deleted=`present`; bin=`matching` | Выполнить `GET /api/cards?offset=1&status=ACTIVE&bin=400000` | HTTP 200; `total=3`; в `cards` 2 элемента; `DELETED` отсутствуют |
| CARD-PW-009 | `CM-REQ-3`, `CM-REQ-5` | позитивный | Pairwise, строка 9 | Подготовлена 1 подходящая неудалённая карта; deleted=`absent`; bin=`matching` | Выполнить `GET /api/cards?limit=100&offset=2&status=INACTIVE&bin=400000` | HTTP 200; `total=1`; в `cards` 0 элементов; `DELETED` отсутствуют |
| CARD-PW-010 | `CM-REQ-3`, `CM-REQ-5` | позитивный | Pairwise, строка 10 | Подготовлено 3 подходящих неудалённых карт; deleted=`present`; bin=`omitted` | Выполнить `GET /api/cards?limit=1&offset=4` | HTTP 200; `total=3`; в `cards` 0 элементов; `DELETED` отсутствуют |
| CARD-PW-011 | `CM-REQ-3`, `CM-REQ-5` | позитивный | Pairwise, строка 11 | Подготовлена 1 подходящая неудалённая карта; deleted=`present`; bin=`matching` | Выполнить `GET /api/cards?limit=1&status=ACTIVE&bin=400000` | HTTP 200; `total=1`; в `cards` 1 элемент; `DELETED` отсутствуют |
| CARD-PW-012 | `CM-REQ-3`, `CM-REQ-5` | позитивный | Pairwise, строка 12 | Подготовлено 3 подходящих неудалённых карт; deleted=`absent`; bin=`matching` | Выполнить `GET /api/cards?limit=1&offset=1&bin=400000` | HTTP 200; `total=3`; в `cards` 1 элемент; `DELETED` отсутствуют |
| CARD-PW-013 | `CM-REQ-3`, `CM-REQ-5` | позитивный | Pairwise, строка 13 | Подготовлено 0 подходящих неудалённых карт; deleted=`present`; bin=`nonmatching` | Выполнить `GET /api/cards?limit=100&offset=1&bin=499999` | HTTP 200; `total=0`; в `cards` 0 элементов; `DELETED` отсутствуют |
| CARD-PW-014 | `CM-REQ-3`, `CM-REQ-5` | позитивный | Pairwise, строка 14 | Подготовлено 3 подходящих неудалённых карт; deleted=`present`; bin=`omitted` | Выполнить `GET /api/cards?limit=100&offset=1&status=INACTIVE` | HTTP 200; `total=3`; в `cards` 2 элемента; `DELETED` отсутствуют |
| CARD-PW-015 | `CM-REQ-3`, `CM-REQ-5` | позитивный | Pairwise, строка 15 | Подготовлено 0 подходящих неудалённых карт; deleted=`present`; bin=`nonmatching` | Выполнить `GET /api/cards?status=INACTIVE&bin=499999` | HTTP 200; `total=0`; в `cards` 0 элементов; `DELETED` отсутствуют |
| CARD-PW-016 | `CM-REQ-3`, `CM-REQ-5` | позитивный | Pairwise, строка 16 | Подготовлена 1 подходящая неудалённая карта; deleted=`absent`; bin=`matching` | Выполнить `GET /api/cards?offset=2&status=BLOCKED&bin=400000` | HTTP 200; `total=1`; в `cards` 0 элементов; `DELETED` отсутствуют |
| CARD-PW-017 | `CM-REQ-3`, `CM-REQ-5` | позитивный | Pairwise, строка 17 | Подготовлено 3 подходящих неудалённых карт; deleted=`present`; bin=`matching` | Выполнить `GET /api/cards?limit=10&bin=400000` | HTTP 200; `total=3`; в `cards` 3 элемента; `DELETED` отсутствуют |
| CARD-PW-018 | `CM-REQ-3`, `CM-REQ-5` | позитивный | Pairwise, строка 18 | Подготовлено 0 подходящих неудалённых карт; deleted=`absent`; bin=`nonmatching` | Выполнить `GET /api/cards?limit=10&offset=1&status=ACTIVE&bin=499999` | HTTP 200; `total=0`; в `cards` 0 элементов; `DELETED` отсутствуют |
| CARD-PW-019 | `CM-REQ-3`, `CM-REQ-5` | позитивный | Pairwise, строка 19 | Подготовлено 0 подходящих неудалённых карт; deleted=`absent`; bin=`matching` | Выполнить `GET /api/cards?offset=1&bin=400000` | HTTP 200; `total=0`; в `cards` 0 элементов; `DELETED` отсутствуют |
| CARD-PW-020 | `CM-REQ-3`, `CM-REQ-5` | позитивный | Pairwise, строка 20 | Подготовлено 3 подходящих неудалённых карт; deleted=`present`; bin=`omitted` | Выполнить `GET /api/cards?offset=3&status=INACTIVE` | HTTP 200; `total=3`; в `cards` 0 элементов; `DELETED` отсутствуют |
| CARD-PW-021 | `CM-REQ-3`, `CM-REQ-5` | позитивный | Pairwise, строка 21 | Подготовлено 3 подходящих неудалённых карт; deleted=`absent`; bin=`matching` | Выполнить `GET /api/cards?limit=100&offset=3&status=BLOCKED&bin=400000` | HTTP 200; `total=3`; в `cards` 0 элементов; `DELETED` отсутствуют |
| CARD-PW-022 | `CM-REQ-3`, `CM-REQ-5` | позитивный | Pairwise, строка 22 | Подготовлено 0 подходящих неудалённых карт; deleted=`absent`; bin=`omitted` | Выполнить `GET /api/cards?limit=1&offset=1&status=INACTIVE` | HTTP 200; `total=0`; в `cards` 0 элементов; `DELETED` отсутствуют |
