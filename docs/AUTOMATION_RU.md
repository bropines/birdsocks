# Автоматизация

*English version: [AUTOMATION.md](AUTOMATION.md)*

Включите **Настройки → Автоматизация** и нажмите **Сгенерировать**. До этого, как и без токена, любой запрос отклоняется.

## Рассылки: Tasker, MacroDroid, adb

- **Получатель:** `io.github.bropines.birdsocks/io.github.bropines.birdsocks.core.AutomationReceiver`. У отладочной сборки пакет `io.github.bropines.birdsocks.dev`; класс и действия те же.
- **Токен:** каждый intent передаёт его в строковом extra `secret` (или `token`, `key`).
- **Фон:** на Android 12+ рассылка может запустить BirdSocks, только если для него отключена оптимизация батареи.

| Действие `io.github.bropines.birdsocks.action.…` | Extras | Что делает |
|---|---|---|
| `CONNECT` | — | Запускает BirdSocks и подключается |
| `DISCONNECT` | — | Останавливает его, как кнопка «Остановить» |
| `TOGGLE` | — | Запускает или останавливает |
| `RESTART` | — | Перезапускает демон |
| `GET_STATUS` | `reply_to`: пакет (необязательно) | Шлёт `STATUS_CHANGED` этому приложению сейчас и после каждого изменения |
| `SET_EXIT_NODE` | `peer`: имя выходного узла или имя, FQDN или IP NetBird его пира; `none` | Выбирает выходной узел, ожидая подключения до 20 с |
| `SWITCH_ACCOUNT` | `name`: аккаунт (профиль) | Переключает аккаунт, при необходимости запуская BirdSocks |
| `SET_TUN` | `on`: `true` / `false` | Включает или выключает режим VPN |
| `SET_SERVER_CONNECTION` | `mode`: `direct` / `proxy` / `byedpi`; `flags` (ByeDPI, необязательно) | Сохраняет связь с сервером и перезапускает демон |

Имя пира подходит для выходного узла, который сейчас используется, или для узла, в имени которого есть имя пира.

**STATUS_CHANGED** (`io.github.bropines.birdsocks.action.STATUS_CHANGED`) уходит только приложению из `reply_to`, а не всем приложениям. Выключение автоматизации или смена токена забывает это приложение.

Extras:
- `running`, `tun`: булевы;
- `state`: `STOPPED`, `STARTING`, `CONNECTING`, `CONNECTED`, `NEEDS_LOGIN`, `IDLE`, `OFFLINE` или `STOPPING`;
- `profile`, `exit_node`, `ip`: строки.

`adb shell am broadcast` получает результат сразу: `result=-1` значит «выполнено», `1` — «не выполнено».

## Ссылки

| Ссылка | Что делает |
|---|---|
| `birdsocks://connect`, `disconnect`, `toggle` | То же, что рассылки |
| `birdsocks://exit-node?peer=<имя\|none>` | Выбирает выходной узел |
| `birdsocks://account?name=<аккаунт>` | Переключает аккаунт |
| `birdsocks://tun?on=true\|false` | Включает или выключает режим VPN |
| `birdsocks://add-account?server=<url>&name=<имя>&key=<setup key>` | Открывает **Добавить аккаунт** с заполненными полями. Ничего не добавляется, пока вы не нажмёте. Без `server` используется NetBird Cloud |

- **Подтверждение:** ссылку может открыть любое приложение или веб-страница, поэтому BirdSocks сначала спрашивает.
- **Без вопроса:** с `&secret=<токен>`, при включённой автоматизации, ссылка выполняется сразу. Используйте это только в своих ярлыках: URL может попасть в историю браузера.
- **Ссылки на экраны:** `birdsocks://peers`, `networks`, `dns`, `publish`, `diagnostics`, `events`, `permissions`, `settings?section=<id>`, `logs?category=NETBIRD`.

## Примеры

```bash
R=io.github.bropines.birdsocks/io.github.bropines.birdsocks.core.AutomationReceiver
A=io.github.bropines.birdsocks.action
adb shell am broadcast -n $R -a $A.CONNECT --es secret ТОКЕН
adb shell am broadcast -n $R -a $A.SET_EXIT_NODE --es secret ТОКЕН --es peer laptop
adb shell am broadcast -n $R -a $A.SET_TUN --es secret ТОКЕН --ez on true
adb shell am broadcast -n $R -a $A.GET_STATUS --es secret ТОКЕН --es reply_to net.dinglisch.android.taskerm
adb shell am start -a android.intent.action.VIEW -d 'birdsocks://exit-node?peer=none'
```

**Tasker**
- **Отправка:** Send Intent с действием, пакетом и классом выше, Extra `secret:ТОКЕН` и Target *Broadcast Receiver*.
- **Приём:** событие *Intent Received* на `…action.STATUS_CHANGED`. Extras приходят как `%state`, `%profile` и так далее.
