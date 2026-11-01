'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const { isAllowedNavigation: nav, isLocalPage, describeLoadError } = require('../src/navigation');
const { SERVER_ORIGIN, assertServerOrigin } = require('../src/app-config');

test('адрес сервера зашит и только https', () => {
  assert.equal(SERVER_ORIGIN, 'https://contora.is-local.org');
  assert.equal(assertServerOrigin('https://chat.example.com:8443'), 'https://chat.example.com:8443');
  for (const bad of ['http://contora.is-local.org', 'contora.is-local.org', 'https://contora.is-local.org/', 'https://contora.is-local.org/path', 'https://user@x.com',
    'https://', 'ftp://x.com', 'https://a b.com', 'https://x.com?q=1', '', 'javascript:1'])
    assert.throws(() => assertServerOrigin(bad), /SERVER_ORIGIN/, bad);
});

const OFFLINE = 'file:///opt/Freedom/resources/app.asar/src/offline.html';
test('навигация внутри окна', () => {
  assert.ok(nav('https://contora.is-local.org/rooms', SERVER_ORIGIN, OFFLINE));
  assert.ok(nav(OFFLINE + '?error=x', SERVER_ORIGIN, OFFLINE));
  for (const u of ['https://evil.com/', 'http://contora.is-local.org/', 'https://contora.is-local.org.evil.com/', 'https://sub.contora.is-local.org/',
    'https://contora.is-local.org:8443/', 'file:///etc/passwd', 'file:///opt/Freedom/resources/app.asar/src/other.html', 'javascript:1', 'not a url'])
    assert.equal(nav(u, SERVER_ORIGIN, OFFLINE), false, u);
});

test('IPC только с локальной страницы ошибки', () => {
  assert.ok(isLocalPage(OFFLINE, OFFLINE) && isLocalPage(OFFLINE + '?a=1#b', OFFLINE));
  for (const u of ['https://contora.is-local.org/', undefined, null, OFFLINE + 'x', 'file:///etc/passwd']) assert.equal(isLocalPage(u, OFFLINE), false, String(u));
});

test('понятные тексты ошибок', () => {
  assert.match(describeLoadError('net::ERR_CERT_AUTHORITY_INVALID'), /сертификат/);
  assert.match(describeLoadError('ERR_NAME_NOT_RESOLVED'), /не найден/);
  assert.match(describeLoadError('net::ERR_CONNECTION_REFUSED'), /отклонил/);
  assert.match(describeLoadError('net::ERR_CONNECTION_TIMED_OUT'), /не отвечает/);
  assert.match(describeLoadError('net::ERR_WEIRD'), /ERR_WEIRD/);
  assert.match(describeLoadError(''), /неизвестная/);
});
