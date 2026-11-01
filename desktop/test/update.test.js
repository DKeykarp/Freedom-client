'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const { compareVersions: cmp, isValidVersion, decideUpdate } = require('../src/update');

test('сравнение версий по числам, а не по строкам', () => {
  assert.equal(cmp('1.2.0', '1.10.0'), -1);   // строковое сравнение дало бы обратный результат
  assert.equal(cmp('1.2.0', '1.2.0'), 0);
  assert.equal(cmp('5.0', '5.0.1'), -1);       // разное число частей — по умолчанию 0
  assert.equal(cmp('5.1', '5.0'), 1);
  assert.equal(cmp('2.0.0', '1.9.9'), 1);
  assert.equal(cmp('1.0', '1'), 0);
});

test('проверка формата версии', () => {
  for (const v of ['1.0', '1.0.0', '5.12', '10.0.3']) assert.ok(isValidVersion(v), v);
  for (const v of ['1', '1.a', 'v1.0', '', null, undefined, '1.0.0.0', '1..0', '01.0'.repeat(0) + '1.0-beta'])
    assert.ok(!isValidVersion(v), String(v));
});

const PING_NONE = { min_client_version: { windows: null, linux: null, android: null } };
const DOWNLOADS = [
  { platform: 'windows', version: '1.2.0', url: '/downloads/Freedom-Setup-1.2.0.exe' },
  { platform: 'linux', version: '1.2.0', url: '/downloads/Freedom-1.2.0-linux-x64.deb' },
];

test('актуальная версия — предложений нет', () => {
  assert.equal(decideUpdate({ currentVersion: '1.2.0', platform: 'windows', ping: PING_NONE, downloads: DOWNLOADS }), null);
  assert.equal(decideUpdate({ currentVersion: '1.3.0', platform: 'windows', ping: PING_NONE, downloads: DOWNLOADS }), null);
});

test('вышла более новая версия — мягкое предложение обновиться', () => {
  const u = decideUpdate({ currentVersion: '1.0.0', platform: 'windows', ping: PING_NONE, downloads: DOWNLOADS });
  assert.deepEqual(u, { severity: 'optional', current: '1.0.0', latest: '1.2.0', url: '/downloads/Freedom-Setup-1.2.0.exe' });
});

test('версия ниже минимально поддерживаемой — обязательное обновление', () => {
  const ping = { min_client_version: { windows: '1.1.0', linux: null, android: null } };
  const u = decideUpdate({ currentVersion: '1.0.0', platform: 'windows', ping, downloads: DOWNLOADS });
  assert.deepEqual(u, { severity: 'mandatory', current: '1.0.0', latest: '1.2.0', url: '/downloads/Freedom-Setup-1.2.0.exe' });
});

test('обязательное обновление, но в downloads пока нет новее минимума — версия берётся из min_client_version, ссылки нет', () => {
  const ping = { min_client_version: { windows: '2.0.0', linux: null, android: null } };
  const u = decideUpdate({ currentVersion: '1.0.0', platform: 'windows', ping, downloads: DOWNLOADS });
  assert.deepEqual(u, { severity: 'mandatory', current: '1.0.0', latest: '2.0.0', url: null });
});

test('своя платформа: linux не путается с windows', () => {
  const u = decideUpdate({ currentVersion: '1.0.0', platform: 'linux', ping: PING_NONE, downloads: DOWNLOADS });
  assert.equal(u.url, '/downloads/Freedom-1.2.0-linux-x64.deb');
});

test('нет установщика для этой платформы — молчим, не ошибка', () => {
  assert.equal(decideUpdate({ currentVersion: '1.0.0', platform: 'windows', ping: PING_NONE, downloads: [] }), null);
  assert.equal(decideUpdate({ currentVersion: '1.0.0', platform: 'windows', ping: PING_NONE, downloads: null }), null);
});

test('битые данные не приводят к ложному «обновлению»', () => {
  const badDownloads = [{ platform: 'windows', version: 'not-a-version', url: '/x' }];
  assert.equal(decideUpdate({ currentVersion: '1.0.0', platform: 'windows', ping: PING_NONE, downloads: badDownloads }), null);
  const badPing = { min_client_version: { windows: 'nope', linux: null, android: null } };
  assert.equal(decideUpdate({ currentVersion: '1.0.0', platform: 'windows', ping: badPing, downloads: [] }), null);
});
