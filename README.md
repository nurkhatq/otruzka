# WMS TSD — система отгрузки Kaspi

Система для операторов склада: сканировать штрихкоды заказов Kaspi через ТСД Mindeo, создавать отгрузки в МойСклад и контролировать историю смен.

---

## Архитектура

```
ТСД Mindeo (Android)
       │  HTTPS
       ▼
  VPS 194.238.41.18
  ┌────────────────────────────────────────┐
  │  nginx (reverse proxy, port 443/80)   │
  │  FastAPI + uvicorn (port 8000)        │
  │  PostgreSQL (основные данные)         │
  │  Redis (кэш + блокировки)             │
  └────────────────────────────────────────┘
       │
       ▼
  МойСклад API  +  Kaspi API
```

### Сервисы на VPS

| Сервис | Описание |
|--------|----------|
| `wms-api.service` | FastAPI бэкенд, автозапуск, Restart=always |
| `wms-worker.service` | Kaspi poller — обновляет статусы заказов каждые 15 мин |
| `nginx` | SSL termination, проксирует на 127.0.0.1:8000 |
| `postgresql` | Основная БД |
| `redis-server` | Кэш заказов + атомарные блокировки |

Оба сервиса включены в автозапуск (`systemctl enable`), поднимаются автоматически после перезагрузки VPS.

---

## Репозитории

| Часть | Репозиторий |
|-------|-------------|
| Android (этот репо) | `github.com/nurkhatq/otruzka` |
| Backend (FastAPI) | `ubuntu@194.238.41.18:/home/nurkhat/wms-backend.git` (VPS bare repo) |

---

## Полный флоу работы

### 1. Начало смены
Оператор открывает приложение → логинится → нажимает **"Начать смену"**.
Бэкенд создаёт запись `ScanSession` в БД и сохраняет активную сессию в Redis (`wms:session:active:{warehouse_id}`).

### 2. Сканирование заказа
Оператор подносит ТСД к штрихкоду посылки.
Приложение отправляет `POST /scan/lock` с кодом заказа.

Бэкенд делает следующее по порядку:

```
1. Проверяет Redis кэш МойСклад (wms:ms:{code}) — есть ли заказ в МС?
2. Если нет → проверяет Redis кэш Kaspi (wms:order:status:{code})
3. Если нет → ищет в PostgreSQL (таблица kaspi_orders)
4. Ставит Redis-блокировку (wms:lock:order:{code}) — атомарно (SET NX)
   TTL = 24 часа (операторы сканируют весь день, отгрузка создаётся в конце смены)
5. Сохраняет событие сканирования в scanned_orders
```

**Результаты сканирования:**

| Статус | Что означает |
|--------|-------------|
| `SUCCESS` | Заказ найден в МС, блокировка поставлена — готов к отгрузке |
| `KASPI_ONLY` | Есть только в Kaspi, не импортирован в МойСклад |
| `ALREADY_SHIPPED` | Уже был отгружен ранее |
| `ALREADY_LOCKED` | Другой ТСД уже сканирует этот заказ |
| `CANCELLING` | Заказ отменяется покупателем |
| `NOT_FOUND` | Не найден нигде |

### 3. Создание отгрузок
Оператор нажимает **"Создать отгрузку"**.
`POST /scan/create-demands` — бэкенд создаёт отгрузки в МойСклад для всех заказов со статусом `SUCCESS`.
После создания сразу обновляет кэш МойСклад (`asyncio.ensure_future(refresh_cache)`).
Результат записывается в `scanned_orders.demand_status` (CREATED / NOT_IN_MS / ERROR).

### 4. Конец смены
Оператор нажимает **"Завершить смену"** (COMPLETED) или **"Отменить"** (CANCELLED).
Redis-блокировка сессии удаляется. Заказы, занятые этим ТСД, автоматически освобождаются (TTL 24ч).

---

## Redis — структура ключей

| Ключ | Содержимое | TTL |
|------|-----------|-----|
| `wms:ms:{order_code}` | JSON заказа из МойСклад | 1 час |
| `wms:order:status:{order_code}` | Hash со статусом Kaspi | 15 мин |
| `wms:lock:order:{order_code}` | `{tsd_code}:{employee}:{batch_id}` | 24 часа |
| `wms:session:active:{warehouse_id}` | `{batch_id}:{username}` | 24 часа |

---

## База данных (PostgreSQL)

### Основные таблицы

**`scan_sessions`** — смены

```
batch_id        TEXT PRIMARY KEY
warehouse_id    INT
started_by      INT (FK → users)
status          TEXT: ACTIVE | COMPLETED | CANCELLED
order_count     INT
started_at      TIMESTAMPTZ
completed_at    TIMESTAMPTZ
```

**`scanned_orders`** — каждое событие сканирования

```
session_id      INT (FK → scan_sessions)
order_id        INT (FK → kaspi_orders, nullable)
order_code      TEXT
scan_result     TEXT: SUCCESS | ALREADY_LOCKED | ALREADY_SHIPPED | CANCELLING | NOT_FOUND | KASPI_ONLY
demand_status   TEXT: CREATED | NOT_IN_MS | ERROR | null
demand_name     TEXT (номер отгрузки в МС, напр. "Отгрузка 00123")
lock_holder     TEXT
scanned_at      TIMESTAMPTZ
```

**`kaspi_orders`** — кэш заказов Kaspi (обновляется каждые 15 мин)

```
order_code      TEXT UNIQUE
kaspi_status    TEXT
kaspi_state     TEXT
customer_name   TEXT
customer_phone  TEXT
total_price     NUMERIC
assembled       BOOL
express         BOOL
is_cancelling   BOOL
warehouse_id    INT
```

**`users`**

```
username        TEXT UNIQUE
password_hash   TEXT
full_name       TEXT
warehouse_id    INT
role            TEXT: admin | operator
is_active       BOOL
```

---

## API эндпоинты (бэкенд)

### Auth
- `POST /auth/login` — логин, возвращает JWT токен

### Sessions
- `POST /sessions/` — начать смену
- `GET /sessions/active` — текущая активная смена
- `PATCH /sessions/{batch_id}` — завершить/отменить смену
- `GET /sessions/` — история смен (фильтры: warehouse_id, user_id, search, date_from, date_to)
- `GET /sessions/{batch_id}/stats` — статистика смены
- `GET /sessions/{batch_id}/scans` — список сканов с пагинацией и фильтром scan_result

### Scan
- `POST /scan/lock` — сканировать заказ (атомарная блокировка в Redis)
- `DELETE /scan/lock/{order_code}` — снять блокировку
- `POST /scan/create-demands` — создать отгрузки в МС для списка кодов
- `POST /scan/cache-refresh` — принудительно обновить кэш МС

### Users
- `GET /users/list` — список всех активных сотрудников (доступен всем авторизованным)
- `GET /users/` — полный список с деталями (только admin)
- `POST /users/` — создать пользователя (только admin)
- `PATCH /users/{id}` — изменить пользователя (только admin)
- `DELETE /users/{id}` — удалить пользователя (только admin)
- `GET /users/stats` — статистика по складам (только admin)

### Orders
- `GET /orders/` — список заказов Kaspi с фильтрами (state, status, assembled)

---

## Склады

| ID | Название |
|----|----------|
| 1 | PP1 Шымкент |
| 2 | PP2 Алматы |
| 5 | PP5 Астана |

---

## Android приложение

**Структура экранов:**

| Файл | Экран |
|------|-------|
| `LoginActivity.kt` | Вход — username/password |
| `MainActivity.kt` | Главный: вкладки "Сканирование" и "Самовывоз" |
| `HistoryActivity.kt` | История смен с фильтрами (поиск, склад, сотрудник, даты) |
| `SessionDetailActivity.kt` | Детали смены: статистика + список сканов по фильтру |
| `OrderResultActivity.kt` | Результат создания отгрузок |

**Ключевые файлы:**

| Файл | Назначение |
|------|-----------|
| `WmsAuth.kt` | JWT токен и данные пользователя (SharedPreferences) |
| `ScanCache.kt` | Локальный кэш отсканированных заказов в памяти |
| `api/WmsApi.kt` | Retrofit интерфейс |
| `api/WmsApiClient.kt` | Retrofit клиент с Bearer token |
| `api/WmsModels.kt` | Data классы (WmsSession, SessionStats, UserItem и др.) |

**Сканер:**
BroadcastReceiver ловит Intent от сканеров: Mindeo, NLS, Sunmi, Honeywell.
Суффикс `-\d+$` обрезается (Mindeo добавляет счётчик сканирований к штрихкоду).

---

## Деплой бэкенда

```bash
# С локальной машины:
cd wms-backend
git push vps master

# post-receive хук автоматически:
# 1. git checkout в /home/nurkhat/wms-backend
# 2. pip install -r requirements.txt
# 3. alembic upgrade head  (миграции БД)
# 4. nginx -t && nginx reload
# 5. systemctl restart wms-api wms-worker
```

### SSH на VPS
```bash
ssh ubuntu@194.238.41.18
```

### Логи на VPS
```bash
journalctl -u wms-api -f          # логи FastAPI
journalctl -u wms-worker -f       # логи Kaspi poller
```

### Проверка статуса сервисов
```bash
systemctl status wms-api
systemctl status wms-worker
```

---

## Сборка APK

```bash
cd android-app
./gradlew assembleDebug
# APK: app/build/outputs/apk/debug/otgruzka-tsd.apk
```

---

## Переменные окружения (VPS: `/home/nurkhat/wms-backend/.env`)

```env
DATABASE_URL=postgresql+asyncpg://user:pass@localhost/wmsdb
REDIS_URL=redis://localhost:6379
SECRET_KEY=<jwt secret>
MOYSKLAD_TOKEN=<токен МойСклад>
KASPI_TOKEN=<токен Kaspi>
```
