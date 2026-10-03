<h1 align="center">BirdSocks</h1>

<p align="center">
  <strong>Неофициальный клиент NetBird для Android — ваша сеть NetBird как прокси SOCKS5</strong>
</p>

<p align="center">
  <a href="readme.md">English</a> | <strong>Русский</strong>
</p>

BirdSocks запускает клиент [NetBird](https://netbird.io) как узел в пространстве
пользователя и отдаёт его сеть приложениям через локальный прокси SOCKS5. Слот
VPN Android он не занимает, поэтому уживается с другим VPN, блокировщиком
рекламы или рабочим профилем. Это брат
[TailSocks](https://github.com/bropines/tailsocks) для NetBird, собранный из него.

> Ранняя стадия: 0.1.0 не выпущен. См. [`CHANGELOG.md`](CHANGELOG.md) и
> [дорожную карту](docs/ROADMAP_RU.md).

## Что умеет

* Входит в NetBird Cloud или на ваш сервер — по ключу установки или через браузер.
* Поднимает прокси SOCKS5 (по желанию с именем и паролем, по желанию в локальную
  сеть), через который видны ваши узлы, сети, которые они маршрутизируют,
  DNS-имена NetBird — и остальной интернет, напрямую или через выходной узел.
* Показывает узлы с путём соединения, задержкой и трафиком; выбирает сети и
  выходной узел.

## Как

Демон NetBird (`client/server` из закреплённого релиза и несколько патчей)
работает отдельным процессом в режиме netstack; приложение общается с ним через
его gRPC API. Подробности — [`agents.md`](agents.md), сборка —
[`docs/BUILDING_RU.md`](docs/BUILDING_RU.md).

## Лицензия

BSD 3-Clause, как и клиент NetBird, на котором он построен. BirdSocks не связан с
NetBird GmbH; «NetBird» — их товарный знак.
