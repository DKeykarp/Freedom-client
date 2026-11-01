'use strict';
/* Адрес сервера зашит в приложение: клиент работает только с ним и только по HTTPS.
   Чтобы собрать клиент для другого сервера, измените SERVER_ORIGIN и пересоберите установщики. */

function assertServerOrigin(origin) {
  if (!/^https:\/\/[a-z0-9]([a-z0-9.-]*[a-z0-9])?(:\d{1,5})?$/i.test(origin)) {
    throw new Error(`SERVER_ORIGIN должен быть вида https://хост[:порт] без пути, получено: ${origin}`);
  }
  return origin;
}

module.exports = { SERVER_ORIGIN: assertServerOrigin('https://contora.is-local.org'), assertServerOrigin };
