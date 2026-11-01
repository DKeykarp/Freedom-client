'use strict';
/* Чистая логика без Electron: разрешённые переходы и тексты ошибок. */

/** Внутри окна разрешены только сервер и локальная страница ошибки; всё остальное открывается в браузере. */
function isAllowedNavigation(url, serverOrigin, localPageUrl) {
  try {
    const u = new URL(url);
    if (u.protocol === 'file:') return u.href.split(/[?#]/)[0] === localPageUrl;
    return u.protocol === 'https:' && u.origin === serverOrigin;
  } catch { return false; }
}

/** IPC принимается только с локальной страницы ошибки, а не с загруженного с сервера интерфейса. */
const isLocalPage = (url, localPageUrl) =>
  typeof url === 'string' && url.split(/[?#]/)[0] === localPageUrl;

function describeLoadError(desc) {
  const d = String(desc || '');
  if (/ERR_CERT|ERR_SSL|SSL_/.test(d)) return 'Не удалось проверить сертификат сервера. Проверьте дату и время на компьютере, обновите систему; если ошибка не проходит, сообщите администратору сервера.';
  if (/ERR_NAME_NOT_RESOLVED/.test(d)) return 'Адрес сервера не найден. Проверьте подключение к интернету.';
  if (/ERR_CONNECTION_REFUSED/.test(d)) return 'Сервер отклонил соединение: возможно, он сейчас выключен.';
  if (/ERR_INTERNET_DISCONNECTED|ERR_NETWORK_CHANGED/.test(d)) return 'Нет подключения к интернету.';
  if (/ERR_CONNECTION_TIMED_OUT|ERR_TIMED_OUT|ERR_CONNECTION_RESET|ERR_CONNECTION_CLOSED/.test(d)) return 'Сервер не отвечает.';
  return `Не удалось открыть сервер (${d.replace(/^net::/, '') || 'неизвестная ошибка'}).`;
}

module.exports = { isAllowedNavigation, isLocalPage, describeLoadError };
