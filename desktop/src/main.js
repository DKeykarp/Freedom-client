'use strict';
/*
 * Freedom Desktop: окно с интерфейсом сервера Freedom (Windows и Linux).
 * Сервер зашит в приложение (app-config.js) и только по HTTPS. Интерфейс и шифрование выполняются в окне
 * (Chromium), как в веб-версии: ключи не покидают устройство. Загруженная страница не имеет доступа к Node.js,
 * права на камеру, микрофон и уведомления выдаются только этому серверу, а чужие ссылки открываются в браузере.
 */
const { app, BrowserWindow, Menu, ipcMain, session, shell, dialog, nativeTheme, net } = require('electron');
const path = require('path');
const { pathToFileURL } = require('url');
const { SERVER_ORIGIN } = require('./app-config');
const { createConfigStore } = require('./config');
const { isAllowedNavigation, isLocalPage, describeLoadError } = require('./navigation');
const { decideUpdate } = require('./update');

const OFFLINE_PAGE = path.join(__dirname, 'offline.html');
const OFFLINE_URL = pathToFileURL(OFFLINE_PAGE).href;
const PERMISSIONS = new Set(['media', 'notifications', 'fullscreen', 'clipboard-sanitized-write']);

const store = createConfigStore(path.join(app.getPath('userData'), 'config.json'));
let cfg = store.read();
let win = null;

const trusted = (url) => {
  try { return new URL(url).origin === SERVER_ORIGIN; } catch { return false; }
};

function openExternal(url) {
  try {
    const u = new URL(url);
    if (['https:', 'mailto:'].includes(u.protocol)) shell.openExternal(u.href);   // http:// наружу не отдаём
  } catch { /* некорректный адрес: игнорируем */ }
}

/* ---------- окно ---------- */

function createWindow() {
  const w = cfg.window;
  win = new BrowserWindow({
    width: w.width, height: w.height, minWidth: 800, minHeight: 560, show: false,
    // светлая или тёмная — под стать системе и остальным клиентам; страница поверх сама подстроится под CSS media
    backgroundColor: nativeTheme.shouldUseDarkColors ? '#0e1621' : '#f5f7fa', autoHideMenuBar: true, title: 'Freedom',
    icon: path.join(__dirname, 'icon.png'),
    webPreferences: {
      preload: path.join(__dirname, 'preload.js'),
      contextIsolation: true, nodeIntegration: false, sandbox: true, webSecurity: true,
      allowRunningInsecureContent: false, spellcheck: false, devTools: !app.isPackaged,
    },
  });
  win.webContents.setUserAgent(`${win.webContents.getUserAgent()} FreedomDesktop/${app.getVersion()}`);
  if (w.maximized) win.maximize();
  win.once('ready-to-show', () => win.show());

  win.webContents.on('did-fail-load', (_e, code, desc, url, isMainFrame) => {
    if (!isMainFrame || code === -3 || String(url).startsWith('file:')) return;   // -3: переход прерван
    showOffline(describeLoadError(desc));
  });
  win.webContents.on('render-process-gone', (_e, d) => {
    if (d.reason !== 'clean-exit' && d.reason !== 'killed') win.webContents.reload();
  });
  win.on('close', () => {
    try {
      const b = win.getNormalBounds();
      cfg = store.write({ window: { width: b.width, height: b.height, maximized: win.isMaximized() } });
    } catch { /* окно уже разрушено */ }
  });
  win.on('closed', () => { win = null; });
}

function showOffline(error) {
  if (win) win.loadFile(OFFLINE_PAGE, { query: { error: error || '', server: SERVER_ORIGIN } });
}

const loadServer = () => win && win.loadURL(SERVER_ORIGIN + '/');

/* ---------- IPC (только с локальной страницы ошибки) ---------- */

ipcMain.handle('freedom:retry', (e) => {
  if (!isLocalPage(e.senderFrame && e.senderFrame.url, OFFLINE_URL)) throw new Error('forbidden');
  loadServer();
});

/* ---------- проверка обновлений ---------- */
// Сервер — источник правды об актуальной версии: /api/ping отдаёт минимально поддерживаемую версию,
// /api/downloads — версию последнего опубликованного установщика для этой платформы (см. server/main.py).
const PLATFORM = process.platform === 'win32' ? 'windows' : 'linux';

async function fetchJson(url) {
  const ctl = new AbortController();
  const timer = setTimeout(() => ctl.abort(), 8000);
  try {
    const r = await net.fetch(url, { signal: ctl.signal });
    if (!r.ok) throw new Error('http ' + r.status);
    return await r.json();
  } finally {
    clearTimeout(timer);
  }
}

async function checkForUpdates(manual) {
  let ping, downloads;
  try {
    [ping, downloads] = await Promise.all([
      fetchJson(SERVER_ORIGIN + '/api/ping'),
      fetchJson(SERVER_ORIGIN + '/api/downloads'),
    ]);
  } catch (err) {
    if (manual) {
      dialog.showMessageBox(win, { type: 'warning', title: 'Проверка обновлений',
        message: 'Не удалось проверить обновления', detail: describeLoadError(String((err && err.message) || err)) });
    }
    return;
  }
  const update = decideUpdate({ currentVersion: app.getVersion(), platform: PLATFORM, ping, downloads });
  if (!update) {
    if (manual) {
      dialog.showMessageBox(win, { type: 'info', title: 'Проверка обновлений', message: 'У вас последняя версия' });
    }
    return;
  }
  // необязательное обновление, которое уже отклонили ("Позже") — не напоминаем сами; по кнопке меню — покажем
  if (!manual && update.severity === 'optional' && cfg.dismissedUpdateVersion === update.latest) return;
  showUpdateDialog(update, manual);
}

function showUpdateDialog(update, manual) {
  const mandatory = update.severity === 'mandatory';
  const buttons = mandatory
    ? (update.url ? ['Скачать обновление', 'Продолжить всё равно'] : ['Понятно'])
    : ['Скачать', 'Позже'];
  dialog.showMessageBox(win, {
    type: mandatory ? 'warning' : 'info',
    title: 'Доступно обновление',
    message: mandatory
      ? `Версия ${update.current} больше не поддерживается сервером. Нужна версия ${update.latest} или новее.`
      : `Вышла версия ${update.latest} (у вас ${update.current}).`,
    detail: mandatory && !update.url ? 'Установщик этой версии сервер пока не опубликовал — загляните позже.' : undefined,
    buttons, defaultId: 0, cancelId: buttons.length - 1,
  }).then(({ response }) => {
    if (buttons[response] === 'Скачать обновление' || buttons[response] === 'Скачать') {
      if (update.url) openExternal(SERVER_ORIGIN + update.url);
    } else if (buttons[response] === 'Позже') {
      cfg = store.write({ dismissedUpdateVersion: update.latest });
    }
  });
}

/* ---------- меню ---------- */

function buildMenu() {
  Menu.setApplicationMenu(Menu.buildFromTemplate([
    { label: 'Файл', submenu: [{ role: 'quit', label: 'Выход' }] },
    { label: 'Правка', submenu: [
      { role: 'undo', label: 'Отменить' }, { role: 'redo', label: 'Повторить' }, { type: 'separator' },
      { role: 'cut', label: 'Вырезать' }, { role: 'copy', label: 'Копировать' },
      { role: 'paste', label: 'Вставить' }, { role: 'selectAll', label: 'Выделить всё' },
    ] },
    { label: 'Вид', submenu: [
      { role: 'reload', label: 'Обновить' }, { role: 'togglefullscreen', label: 'Во весь экран' }, { type: 'separator' },
      { role: 'zoomIn', label: 'Увеличить' }, { role: 'zoomOut', label: 'Уменьшить' }, { role: 'resetZoom', label: 'Сбросить масштаб' },
    ] },
    { label: 'Справка', submenu: [
      { label: 'Проверить обновления', click: () => checkForUpdates(true) },
      { label: 'О программе', click: showAbout },
    ] },
  ]));
}

function showAbout() {
  dialog.showMessageBox(win, {
    type: 'info', title: 'О программе', message: `Freedom ${app.getVersion()}`,
    detail: `Сервер: ${SERVER_ORIGIN}\nElectron ${process.versions.electron}, Chromium ${process.versions.chrome}\n\n` +
      'Сообщения, файлы и звонки защищены сквозным шифрованием (FRDM-E2EE v1).',
  });
}

/* ---------- запуск ---------- */

function setupSecurity() {
  const ses = session.defaultSession;
  ses.setPermissionRequestHandler((wc, permission, callback, details) =>
    callback(PERMISSIONS.has(permission) && trusted((details && details.requestingUrl) || wc.getURL())));
  ses.setPermissionCheckHandler((_wc, permission, origin) => PERMISSIONS.has(permission) && trusted(origin));

  // Ошибки сертификата не обходятся никогда: никаких «всё равно продолжить».
  app.on('certificate-error', (event, _wc, _url, _error, _cert, callback) => {
    event.preventDefault();
    callback(false);
  });

  app.on('web-contents-created', (_e, wc) => {
    wc.setWindowOpenHandler(({ url }) => { openExternal(url); return { action: 'deny' }; });
    wc.on('will-navigate', (e, url) => {
      if (isAllowedNavigation(url, SERVER_ORIGIN, OFFLINE_URL)) return;
      e.preventDefault();
      openExternal(url);
    });
    wc.on('will-attach-webview', (e) => e.preventDefault());
  });
}

async function main() {
  setupSecurity();
  buildMenu();
  createWindow();
  loadServer();
  checkForUpdates(false).catch(() => {});   // тихая фоновая проверка; сбой сети не должен мешать работе
}

if (!app.requestSingleInstanceLock()) {
  app.quit();
} else {
  app.on('second-instance', () => {
    if (!win) return;
    if (win.isMinimized()) win.restore();
    win.show();
    win.focus();
  });
  app.whenReady().then(main);
  app.on('window-all-closed', () => app.quit());
}
