'use strict';
/* Прогон настоящего src/main.js с подменённым модулем electron: проверяем логику, а не Chromium. */
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('fs'); const os = require('os'); const path = require('path');
const Module = require('module');
const { EventEmitter } = require('events');
const { pathToFileURL } = require('url');

const SRC = path.join(__dirname, '..', 'src');
const OFFLINE_URL = pathToFileURL(path.join(SRC, 'offline.html')).href;
const SERVER = 'https://contora.is-local.org';
const PLATFORM = process.platform === 'win32' ? 'windows' : 'linux';   // как в main.js
const tick = () => new Promise((r) => setImmediate(r));

function makeEnv({ config, lock = true, dark = false, fetchImpl, dialogResponse = 0 } = {}) {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'frdm-main-'));
  if (config) fs.writeFileSync(path.join(dir, 'config.json'), JSON.stringify(config));
  const rec = { switches: [], windows: [], handlers: {}, menu: null, external: [], perm: {}, quit: 0, dialog: [] };

  class WC extends EventEmitter {
    constructor() { super(); this.ua = 'Mozilla/5.0 Electron/31'; this.url = ''; this.reloaded = 0; }
    getUserAgent() { return this.ua; } setUserAgent(u) { this.ua = u; } getURL() { return this.url; }
    setWindowOpenHandler(fn) { this.openHandler = fn; } reload() { this.reloaded++; }
  }
  class BrowserWindow extends EventEmitter {
    constructor(opts) { super(); this.opts = opts; this.webContents = new WC(); this.loaded = []; this.shown = false; this.maximizedNow = false; rec.windows.push(this); }
    once(ev, cb) { this.on(ev, cb); return this; }
    loadURL(u) { this.loaded.push({ kind: 'url', url: u }); this.webContents.url = u; }
    loadFile(f, o) { this.loaded.push({ kind: 'file', file: f, query: o && o.query }); }
    show() { this.shown = true; } focus() { this.focused = true; } restore() { this.restored = true; } isMinimized() { return true; }
    maximize() { this.maximizedNow = true; } isMaximized() { return this.maximizedNow; }
    getNormalBounds() { return { width: 1234, height: 777 }; }
  }
  const app = Object.assign(new EventEmitter(), {
    getPath: () => dir, getVersion: () => '1.0.0', isPackaged: true,
    commandLine: { appendSwitch: (k, v) => rec.switches.push([k, v]) },
    requestSingleInstanceLock: () => lock, whenReady: () => Promise.resolve(),
    quit: () => { rec.quit++; }, relaunch: () => { rec.relaunch = true; }, exit: () => { rec.exit = true; },
  });
  const electron = {
    app, BrowserWindow,
    nativeTheme: { shouldUseDarkColors: dark },
    Menu: { buildFromTemplate: (t) => t, setApplicationMenu: (t) => { rec.menu = t; } },
    ipcMain: { handle: (ch, fn) => { rec.handlers[ch] = fn; } },
    session: { defaultSession: { setPermissionRequestHandler: (f) => { rec.perm.request = f; }, setPermissionCheckHandler: (f) => { rec.perm.check = f; } } },
    shell: { openExternal: (u) => rec.external.push(u) },
    dialog: { showMessageBox: (w, o) => { rec.dialog.push(o); return Promise.resolve({ response: dialogResponse }); } },
    net: { fetch: fetchImpl || (async (url) => {
      if (url.endsWith('/api/ping')) return { ok: true, status: 200, json: async () => ({ app: 'freedom', version: '1.0.0', min_client_version: { windows: null, linux: null, android: null } }) };
      if (url.endsWith('/api/downloads')) return { ok: true, status: 200, json: async () => [] };
      return { ok: false, status: 404, json: async () => ({}) };
    }) },
  };
  const orig = Module._load;
  Module._load = function (request) { return request === 'electron' ? electron : orig.apply(this, arguments); };
  for (const k of Object.keys(require.cache)) if (k.startsWith(SRC)) delete require.cache[k];
  try { require(path.join(SRC, 'main.js')); } finally { Module._load = orig; }
  return { electron, rec, dir, app, win: () => rec.windows[0], cfgFile: path.join(dir, 'config.json') };
}
const fromOffline = { senderFrame: { url: OFFLINE_URL + '?error=x' } };
const fromRemote = { senderFrame: { url: SERVER + '/' } };

test('цвет окна до загрузки страницы соответствует системной теме (светлая/тёмная)', async () => {
  const light = makeEnv({ dark: false }); await tick();
  assert.equal(light.win().opts.backgroundColor, '#f5f7fa', 'светлая система -> светлый фон окна');
  const night = makeEnv({ dark: true }); await tick();
  assert.equal(night.win().opts.backgroundColor, '#0e1621', 'тёмная система -> тёмный фон окна');
});

test('запуск: безопасное окно сразу открывает зашитый https-сервер', async () => {
  const e = makeEnv(); await tick();
  const w = e.win(), p = w.opts.webPreferences;
  assert.equal(p.contextIsolation, true); assert.equal(p.nodeIntegration, false); assert.equal(p.sandbox, true);
  assert.equal(p.webSecurity, true); assert.equal(p.allowRunningInsecureContent, false);
  assert.match(w.webContents.ua, /FreedomDesktop\/1\.0\.0$/);
  assert.deepEqual(w.loaded, [{ kind: 'url', url: SERVER + '/' }]);
  w.emit('ready-to-show'); assert.equal(w.shown, true);
  assert.deepEqual(e.rec.switches, [], 'никаких флагов, ослабляющих безопасность');
});

test('адрес нельзя подменить: ни настройками, ни старым конфигом, ни через IPC', async () => {
  const e = makeEnv({ config: { server: 'http://192.168.1.5:8000', allowInsecure: true } }); await tick();
  assert.deepEqual(e.win().loaded, [{ kind: 'url', url: SERVER + '/' }]);
  assert.deepEqual(e.rec.switches, []);
  assert.deepEqual(Object.keys(e.rec.handlers), ['freedom:retry'], 'нет команд смены сервера');
  const labels = JSON.stringify(e.rec.menu);
  assert.ok(!/Сменить сервер/.test(labels));
});

test('права только зашитому серверу', async () => {
  const e = makeEnv(); await tick();
  const ask = (perm, url) => new Promise((res) => e.rec.perm.request(e.win().webContents, perm, res, { requestingUrl: url }));
  assert.equal(await ask('media', SERVER + '/'), true);
  assert.equal(await ask('notifications', SERVER + '/x'), true);
  for (const [perm, url] of [['media', 'https://evil.com/'], ['media', 'https://contora.is-local.org.evil.com/'], ['media', 'http://contora.is-local.org/'],
    ['geolocation', SERVER + '/'], ['openExternal', SERVER + '/'], ['midi', SERVER + '/'], ['media', 'file:///x.html']])
    assert.equal(await ask(perm, url), false, `${perm} ${url}`);
  assert.equal(e.rec.perm.check(null, 'media', SERVER), true);
  assert.equal(e.rec.perm.check(null, 'media', 'https://evil.com'), false);
});

test('ошибки сертификата не обходятся', async () => {
  const e = makeEnv(); await tick();
  let prevented = false, verdict = 'unset';
  e.app.emit('certificate-error', { preventDefault() { prevented = true; } }, {}, SERVER, 'net::ERR_CERT_AUTHORITY_INVALID', {}, (ok) => { verdict = ok; });
  assert.equal(prevented, true); assert.equal(verdict, false);
});

test('IPC «повторить» принимается только с локальной страницы ошибки', async () => {
  const e = makeEnv(); await tick();
  const retry = e.rec.handlers['freedom:retry'];
  await assert.rejects(async () => retry(fromRemote), /forbidden/);
  await assert.rejects(async () => retry({}), /forbidden/);
  await assert.rejects(async () => retry({ senderFrame: { url: 'file:///etc/passwd' } }), /forbidden/);
  assert.equal(e.win().loaded.length, 1);
  await retry(fromOffline);
  assert.deepEqual(e.win().loaded.at(-1), { kind: 'url', url: SERVER + '/' });
});

test('ошибка загрузки показывает страницу «Нет связи», подресурсы и прерывания игнорируются', async () => {
  const e = makeEnv(); await tick();
  const w = e.win(), n0 = w.loaded.length;
  w.webContents.emit('did-fail-load', {}, -3, 'ERR_ABORTED', SERVER + '/', true);
  w.webContents.emit('did-fail-load', {}, -105, 'ERR_NAME_NOT_RESOLVED', SERVER + '/x.png', false);
  w.webContents.emit('did-fail-load', {}, -6, 'ERR_FILE_NOT_FOUND', 'file:///x', true);
  assert.equal(w.loaded.length, n0);
  w.webContents.emit('did-fail-load', {}, -200, 'ERR_CERT_COMMON_NAME_INVALID', SERVER + '/', true);
  const last = w.loaded.at(-1);
  assert.equal(last.kind, 'file'); assert.match(last.file, /offline\.html$/);
  assert.match(last.query.error, /сертификат/); assert.equal(last.query.server, SERVER);
  w.webContents.emit('render-process-gone', {}, { reason: 'crashed' }); assert.equal(w.webContents.reloaded, 1);
  w.webContents.emit('render-process-gone', {}, { reason: 'clean-exit' }); assert.equal(w.webContents.reloaded, 1);
});

test('переходы и новые окна: наружу только https через браузер', async () => {
  const e = makeEnv(); await tick();
  const wc = e.win().webContents; e.app.emit('web-contents-created', {}, wc);
  const nav = (url) => { const ev = { prevented: false, preventDefault() { this.prevented = true; } }; wc.emit('will-navigate', ev, url); return ev.prevented; };
  assert.equal(nav(SERVER + '/rooms'), false);
  assert.equal(nav('https://evil.com/phish'), true); assert.deepEqual(e.rec.external, ['https://evil.com/phish']);
  assert.equal(nav('http://contora.is-local.org/'), true); assert.equal(e.rec.external.length, 1, 'http:// в браузер не отдаётся');
  assert.equal(nav('file:///etc/passwd'), true); assert.equal(e.rec.external.length, 1);
  const open = (url) => wc.openHandler({ url });
  assert.deepEqual(open('https://example.org/a'), { action: 'deny' });
  for (const u of ['javascript:alert(1)', 'file:///etc/passwd', 'ftp://x', 'http://example.org/']) open(u);
  open('mailto:a@b.c');
  assert.deepEqual(e.rec.external, ['https://evil.com/phish', 'https://example.org/a', 'mailto:a@b.c']);
  const ev = { prevented: false, preventDefault() { this.prevented = true; } }; wc.emit('will-attach-webview', ev); assert.equal(ev.prevented, true);
});

test('один экземпляр; окно помнит размер; «О программе» показывает сервер', async () => {
  const e = makeEnv({ config: { window: { width: 1000, height: 700, maximized: true } } }); await tick();
  assert.equal(e.win().opts.width, 1000); assert.equal(e.win().maximizedNow, true);
  e.app.emit('second-instance'); assert.ok(e.win().restored && e.win().shown && e.win().focused);
  e.win().emit('close');
  assert.equal(JSON.parse(fs.readFileSync(e.cfgFile, 'utf8')).window.width, 1234);
  assert.ok(!/server/.test(fs.readFileSync(e.cfgFile, 'utf8')), 'адрес в конфиг не пишется');
  e.rec.menu.at(-1).submenu[1].click();
  assert.match(e.rec.dialog[0].detail, /https:\/\/contora\.is-local\.org/); assert.match(e.rec.dialog[0].message, /1\.0\.0/);
  const second = makeEnv({ lock: false }); await tick();
  assert.equal(second.rec.quit, 1); assert.equal(second.rec.windows.length, 0);
});

/* ---------- проверка обновлений ---------- */

const pingWith = (min) => ({ ok: true, status: 200, json: async () => ({ app: 'freedom', version: '9.9', min_client_version: min }) });
const downloadsWith = (items) => ({ ok: true, status: 200, json: async () => items });
const NO_MIN = { windows: null, linux: null, android: null };

test('фоновая проверка при старте: всё актуально — без диалога', async () => {
  const e = makeEnv(); await tick(); await tick();
  assert.deepEqual(e.rec.dialog, []);
});

test('фоновая проверка: сеть недоступна — тихо, без диалога и без падения', async () => {
  const e = makeEnv({ fetchImpl: async () => { throw new Error('net::ERR_INTERNET_DISCONNECTED'); } });
  await tick(); await tick();
  assert.deepEqual(e.rec.dialog, []);
});

test('фоновая проверка: есть более новая версия — мягкое предложение со ссылкой на сервер', async () => {
  const fetchImpl = async (url) => url.endsWith('/api/ping') ? pingWith(NO_MIN)
    : downloadsWith([{ platform: PLATFORM, version: '1.2.0', url: '/downloads/Freedom-1.2.0.pkg' }]);
  const e = makeEnv({ fetchImpl, dialogResponse: 0 }); // 0 = «Скачать»
  await tick(); await tick(); await tick();
  assert.equal(e.rec.dialog.length, 1);
  assert.deepEqual(e.rec.dialog[0].buttons, ['Скачать', 'Позже']);
  assert.match(e.rec.dialog[0].message, /1\.2\.0/);
  await tick();
  assert.deepEqual(e.rec.external, ['https://contora.is-local.org/downloads/Freedom-1.2.0.pkg']);
});

test('«Позже» запоминает версию — при следующем фоновом запуске для неё диалог не повторяется', async () => {
  const fetchImpl = async (url) => url.endsWith('/api/ping') ? pingWith(NO_MIN)
    : downloadsWith([{ platform: PLATFORM, version: '1.2.0', url: '/downloads/Freedom-1.2.0.pkg' }]);
  let e = makeEnv({ fetchImpl, dialogResponse: 1 }); // 1 = «Позже»
  await tick(); await tick(); await tick(); await tick();
  const saved = JSON.parse(fs.readFileSync(e.cfgFile, 'utf8'));
  assert.equal(saved.dismissedUpdateVersion, '1.2.0');

  e = makeEnv({ config: saved, fetchImpl });
  await tick(); await tick();
  assert.deepEqual(e.rec.dialog, [], 'та же версия уже отклонена — не напоминаем в фоне');

  const fetchImpl2 = async (url) => url.endsWith('/api/ping') ? pingWith(NO_MIN)
    : downloadsWith([{ platform: PLATFORM, version: '1.3.0', url: '/downloads/Freedom-1.3.0.pkg' }]);
  e = makeEnv({ config: saved, fetchImpl: fetchImpl2 });
  await tick(); await tick();
  assert.equal(e.rec.dialog.length, 1, 'но версия свежее отклонённой — предлагаем снова');
  assert.match(e.rec.dialog[0].message, /1\.3\.0/);
});

test('версия ниже минимально поддерживаемой — обязательное обновление показывается даже после «Позже»', async () => {
  const min = { windows: null, linux: null, android: null, [PLATFORM]: '2.0.0' };
  const fetchImpl = async (url) => url.endsWith('/api/ping') ? pingWith(min)
    : downloadsWith([{ platform: PLATFORM, version: '2.0.0', url: '/downloads/Freedom-2.0.0.pkg' }]);
  const e = makeEnv({ config: { dismissedUpdateVersion: '2.0.0' }, fetchImpl });
  await tick(); await tick();
  assert.equal(e.rec.dialog.length, 1, 'обязательное обновление не подавляется отметкой «Позже»');
  assert.equal(e.rec.dialog[0].type, 'warning');
  assert.deepEqual(e.rec.dialog[0].buttons, ['Скачать обновление', 'Продолжить всё равно']);
});

test('меню «Проверить обновления»: актуальная версия — сообщение об этом', async () => {
  const e = makeEnv(); await tick(); await tick();
  e.rec.dialog.length = 0;
  e.rec.menu.at(-1).submenu[0].click();
  await tick();
  assert.equal(e.rec.dialog.length, 1);
  assert.match(e.rec.dialog[0].message, /последняя версия/);
});

test('меню «Проверить обновления»: сбой сети — понятная ошибка, а не молчание', async () => {
  const e = makeEnv({ fetchImpl: async () => { const x = new Error('boom'); x.message = 'net::ERR_CONNECTION_REFUSED'; throw x; } });
  await tick(); await tick();
  e.rec.dialog.length = 0;
  e.rec.menu.at(-1).submenu[0].click();
  await tick();
  assert.equal(e.rec.dialog.length, 1);
  assert.equal(e.rec.dialog[0].type, 'warning');
  assert.match(e.rec.dialog[0].detail, /отклонил/);
});
