# <p align="center"><img src="../art/icon.png" /><br />fPhone - сервер системы персональной видеосвязи</p>

Серверная часть "Кукухи". Монолит на Go. Обеспечивает авторизацию, список контактов, сигнализацию вызовов, WebSocket-соединения, push-уведомления, хранение данных в SQLite и TURN-релей для медиатрафика.
Для работы требуется сервер с белым IP адресом.


Документ соответствует исходникам сервера из проекта **0.3.18**. Настройки и поведение конкретной сборки следует сверять с `fphone.example.yaml`.

## 1. Возможности

- REST API для входа, выхода, профиля, аватаров, контактов и регистрации устройств.
- WebSocket для сигнализации вызовов и событий присутствия.
- SQLite для пользователей, сессий, устройств и push-подписок.
- TURN по UDP/TCP для передачи WebRTC-медиа через собственный сервер.
- FCM для пробуждения Android-клиентов.
- Web Push с VAPID для браузерного PWA.
- Встроенный CLI для управления пользователями и устройствами.
- HTTPS в одном из режимов: за внешним TLS-прокси, собственный self-signed сертификат или заданные файлы сертификата.

**История звонков на сервере не хранится.** Android и PWA ведут собственную локальную историю.

## 2. Сборка

Нужен Go версии, совместимой с `server/go.mod`.

Linux / Raspberry Pi:

```bash
cd server
go build -o fphone .
```

Windows:

```bat
cd server
build.cmd
```

Полученный бинарный файл `fphone` можно запускать из каталога с конфигурацией либо передавать путь к конфигурации параметром `-c`.

## 3. Файлы и каталоги

Пример минимальной структуры установки:

```text
/etc/fphone/
     fphone.yaml

/usr/lib/sbin/
    fphone

/var/lib/fphone/
    web/
        pwa/
            index.html
            app.js
            app.css
            manifest.webmanifest
            sw.js
    avatars/
        alice.jpg
        alice.user.jpg
    fphone.db
    tls.crt       # при tls.mode=self-signed
    tls.key       # при tls.mode=self-signed
```

Фактические пути определяются конфигурацией. Путь `data_dir` задаётся относительно каталога файла конфигурации, если не указан абсолютный путь. База данных и служебные данные размещаются в `data_dir`.

Статические файлы PWA сервер отдаёт из `data_dir/web`. Поэтому файлы из каталога проекта `pwa/` нужно скопировать в `web/pwa/` внутри `data_dir`.

## 4. Конфигурация

Скопируйте пример:

```bash
cp fphone.example.yaml fphone.yaml
```

Основные параметры:

| Параметр | Назначение |
|---|---|
| `listen` | Адрес и порт HTTP(S)-сервера, по умолчанию `:8099` |
| `control_port` | Локальный CLI control API, по умолчанию `8098`, только loopback |
| `data_dir` | Каталог БД, сертификатов и статических файлов |
| `public_host` | Публичное DNS-имя или адрес сервера |
| `allowed_origins` | Разрешённые Origin для браузерных WebSocket-подключений |
| `static_cache_seconds` | Время кеширования статических файлов |
| `tls.mode` | `off`, `self-signed` или `files` |
| `auth.token_ttl_days` | Срок жизни токена с продлением при использовании |
| `turn.*` | Настройки TURN-релея |
| `calls.*` | Таймаут звонка, окно переподключения и лимит одновременных звонков |
| `fcm.*` | Данные сервисного аккаунта Firebase |
| `web_push.*` | VAPID-ключи для браузерных push-уведомлений |

Не публикуйте `fphone.yaml`, если в нём находятся приватные ключи или пути к секретным credentials. Для начала используйте `fphone.example.yaml`.

### TLS

Поддерживаются три режима:

- `off` — сервер работает по HTTP. Используйте только за доверенным TLS reverse proxy, который принимает HTTPS снаружи и проксирует запрос на локальный HTTP-порт.
- `self-signed` — сервер создаёт собственный сертификат и ключ в `data_dir`. Клиенту можно передать fingerprint сертификата.
- `files` — используются файлы сертификата и ключа, заданные в `tls.cert` и `tls.key`.

Для режима `self-signed` получите fingerprint:

```bash
./fphone fingerprint
```

В клиенте указывается fingerprint в формате `sha256/...`. При использовании собственного сертификата клиент доверяет именно этому ключу.

### Публичный адрес и TURN

`public_host` должен соответствовать адресу, по которому клиенты подключаются извне. Он используется в TURN URL и при создании собственного сертификата.

Если внешний IPv4 динамический, можно оставить `turn.relay_ip` пустым: сервер разрешает `public_host` и периодически обновляет адрес. Если DNS возвращает для сервера внутренний адрес, задайте публичный IPv4 явно.

Рекомендуемая политика:

```yaml
turn:
  enabled: true
  ice_policy: "relay"
  listen_port: 3478
  relay_port_min: 49160
  relay_port_max: 49200
  hairpin_src: "auto"
```

`relay` направляет медиатрафик через TURN. Значение `all` разрешает также прямые ICE-маршруты.

## 5. Сетевые порты

Пробросьте на устройство с сервером:

| Протокол | Порт | Назначение |
|---|---:|---|
| TCP | 8099 (или ваш `listen`) | HTTP(S) API, WebSocket и статика |
| UDP + TCP | 3478 (или `turn.listen_port`) | TURN |
| UDP | 49160–49200 | TURN relay media ports по умолчанию |

Если используется reverse proxy, порт приложения обычно оставляют доступным только из локальной сети или с localhost, а наружу публикуют HTTPS-порт прокси. Внешний TLS proxy должен поддерживать WebSocket upgrade.

Порт `control_port` (по умолчанию 8098) не нужно публиковать: он предназначен только для локального взаимодействия CLI с работающим сервером.

## 6. Push-уведомления

### Android: Firebase Cloud Messaging

1. Создайте Firebase project и зарегистрируйте Android-приложение с тем же application ID, который используется при сборке.
2. Получите service-account JSON для серверного доступа к FCM.
3. Поместите файл на сервер с ограниченными правами доступа.
4. Укажите путь в конфигурации:

```yaml
fcm:
  credentials_file: "/etc/fphone/firebase-service-account.json"
  project_id: ""
  send_caller_name: true
```

`project_id` обычно читается из JSON и может быть оставлен пустым. Для отключения FCM оставьте `credentials_file` пустым.

### Браузер: Web Push

Сгенерируйте VAPID-ключи:

```bash
./fphone generate-vapid
```

Добавьте выведенный блок `web_push` в `fphone.yaml`:

```yaml
web_push:
  public_key: "..."
  private_key: "..."
  subject: "mailto:admin@example.com"
```

`private_key` — секрет. Не меняйте пару ключей после регистрации браузерных подписок: старые подписки привязаны к публичному ключу.

После настройки PWA пользователь входит в аккаунт и включает уведомления в настройках. Для Web Push не требуется Apple Developer account.

### Проверка push

```bash
./fphone push-test login=LOGIN
./fphone push-test id=DEVICE_ID
```

Можно указать `title=...` и `text=...`. Команда поддерживает также отправку непосредственно на токен. Успешное принятие сообщения push-провайдером ещё не доказывает, что устройство фактически показало уведомление.

## 7. Управление пользователями и устройствами

Все команды поддерживают указание файла конфигурации перед командой:

```bash
./fphone -c /etc/fphone/fphone.yaml user list
```

Создать пользователя (пароль запрашивается интерактивно):

```bash
./fphone user add LOGIN
./fphone user add -name "Отображаемое имя" LOGIN
```

Другие команды:

```bash
./fphone user list
./fphone user name LOGIN
./fphone user name LOGIN "Новое имя"
./fphone user passwd LOGIN
./fphone user disable LOGIN
./fphone user enable LOGIN
./fphone user del LOGIN

./fphone device list
./fphone device list LOGIN
./fphone device revoke DEVICE_ID
./fphone device log DEVICE_ID
```

Смена пароля завершает сессии пользователя на всех устройствах. `device revoke` отзывает токен выбранного устройства. `device log` запрашивает диагностический журнал у подключённого клиента; устройство должно быть онлайн и иметь активное WebSocket-соединение.

При штатном выходе клиента вызывается `POST /api/v1/logout`, и сервер удаляет запись устройства из таблицы `devices`. Push-подписка устройства также удаляется в соответствии с каскадным ограничением БД.

## 8. Запуск и автозапуск на Linux

Проверочный запуск:

```bash
cd /opt/fphone
./fphone -c /opt/fphone/fphone.yaml serve
```

Для постоянной работы рекомендуется systemd. Пример `/etc/systemd/system/fphone.service` (проверьте пути и пользователя под свою установку):

```ini
[Unit]
Description=fPhone server
After=network-online.target
Wants=network-online.target

[Service]
Type=simple
User=fphone
Group=fphone
WorkingDirectory=/opt/fphone
ExecStart=/opt/fphone/fphone -c /opt/fphone/fphone.yaml serve
Restart=on-failure
RestartSec=3

[Install]
WantedBy=multi-user.target
```

Создайте системного пользователя и каталоги, назначьте владельца каталогам с данными, затем:

```bash
sudo systemctl daemon-reload
sudo systemctl enable --now fphone
sudo systemctl status fphone
sudo journalctl -u fphone -f
```

Если `log.file` пуст, журнал направляется в stderr и обычно доступен через `journalctl`. Не запускайте сервер от root без необходимости.

## 9. Диагностика

```bash
./fphone version
./fphone fingerprint
./fphone device list
./fphone push-test login=LOGIN
sudo journalctl -u fphone -n 200 --no-pager
```

Проверьте:

- корректность `public_host` и DNS;
- доступность TCP-порта API и TURN-портов;
- правильность проброса UDP relay-диапазона;
- время на сервере (TLS и push зависят от корректного времени);
- права чтения service-account JSON и записи в `data_dir`;
- совпадение Firebase application ID с Android-сборкой;
- неизменность VAPID-ключей после создания подписок.

## 10. Резервное копирование и база данных

Остановите сервер перед копированием SQLite-файла, чтобы получить согласованную резервную копию:

```bash
sudo systemctl stop fphone
cp /var/lib/fphone/fphone.db /var/backups/fphone.db
sudo systemctl start fphone
```

Путь к БД определяется `data_dir`. Также сохраните конфигурацию, TLS-ключи (если используются), Firebase credentials, VAPID private key и каталог пользовательских аватаров.


## 11. API

Основные REST endpoints:

| Метод | Путь | Назначение |
|---|---|---|
| `GET` | `/api/v1/ping` | Проверка доступности |
| `POST` | `/api/v1/login` | Вход |
| `POST` | `/api/v1/logout` | Выход и удаление устройства |
| `PUT` | `/api/v1/device` | Регистрация / обновление устройства |
| `GET` | `/api/v1/contacts` | Контакты |
| `GET` | `/api/v1/me` | Профиль текущего пользователя |
| `PUT` | `/api/v1/me` | Изменение профиля |
| `PUT` | `/api/v1/me/avatar` | Загрузка аватара |
| `DELETE` | `/api/v1/me/avatar` | Удаление пользовательского аватара |
| `PUT` | `/api/v1/push/subscription` | Регистрация Web Push |
| `DELETE` | `/api/v1/push/subscription` | Удаление Web Push-подписки |
| `GET` | `/api/v1/push/vapid-public-key` | Получение публичного VAPID-ключа |

Сигнализация вызовов и события устройств передаются через WebSocket. Серверная история звонков в этой версии отсутствует.


## Лицензия

Код написан Claude (Anthropic) free. Лицензия: свободное использование.
