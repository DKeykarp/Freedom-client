'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('fs'); const os = require('os'); const path = require('path');
const { createConfigStore, DEFAULTS } = require('../src/config');

const tmp = () => path.join(fs.mkdtempSync(path.join(os.tmpdir(), 'frdm-')), 'sub', 'config.json');

test('нет файла — значения по умолчанию; запись создаёт папку', () => {
  const f = tmp(), s = createConfigStore(f);
  assert.deepEqual(s.read(), DEFAULTS);
  s.write({ window: { width: 900, height: 600, maximized: true } });
  assert.deepEqual(createConfigStore(f).read().window, { width: 900, height: 600, maximized: true });
  assert.ok(!fs.existsSync(f + '.tmp'));
});

test('повреждённый или странный файл не ломает запуск', () => {
  const f = tmp(); fs.mkdirSync(path.dirname(f), { recursive: true });
  const s = createConfigStore(f);
  for (const bad of ['{oops', '', 'null', '[]', '{"server": 5, "window": "x", "allowInsecure": "yes"}', '{"window": {"width": -5, "height": "big"}}']) {
    fs.writeFileSync(f, bad);
    const r = s.read();
    assert.ok(r.window.width >= 640 && r.window.height >= 480, bad);
  }
});

test('адрес сервера из старого конфига не читается и не сохраняется', () => {
  const f = tmp(); fs.mkdirSync(path.dirname(f), { recursive: true });
  fs.writeFileSync(f, JSON.stringify({ server: 'http://evil.example.com', allowInsecure: true, window: { width: 900, height: 600 } }));
  const s = createConfigStore(f), r = s.read();
  assert.deepEqual(Object.keys(r).sort(), ['dismissedUpdateVersion', 'window']);
  s.write({ window: r.window });
  assert.ok(!/evil|allowInsecure|server/.test(fs.readFileSync(f, 'utf8')));
});

test('версия обновления, отложенная пользователем, сохраняется и переживает перезапуск', () => {
  const f = tmp(), s = createConfigStore(f);
  assert.equal(s.read().dismissedUpdateVersion, null);
  s.write({ dismissedUpdateVersion: '1.2.0' });
  assert.equal(createConfigStore(f).read().dismissedUpdateVersion, '1.2.0');
  assert.deepEqual(createConfigStore(f).read().window, DEFAULTS.window, 'запись одного поля не портит остальные');
});

test('мусор вместо dismissedUpdateVersion не ломает чтение', () => {
  const f = tmp(); fs.mkdirSync(path.dirname(f), { recursive: true });
  fs.writeFileSync(f, JSON.stringify({ dismissedUpdateVersion: 12345 }));
  assert.equal(createConfigStore(f).read().dismissedUpdateVersion, null);
});
