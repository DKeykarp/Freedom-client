'use strict';
const fs = require('fs');
const path = require('path');

const DEFAULTS = { window: { width: 1100, height: 720, maximized: false }, dismissedUpdateVersion: null };

/** Размер и положение окна, и до какой версии обновления человек уже сказал «позже» (JSON в папке данных
    приложения). Повреждённый файл не должен мешать запуску. Адрес сервера здесь не хранится: он зашит в
    приложение (app-config.js). */
function createConfigStore(file) {
  const store = {
    read() {
      try {
        const c = JSON.parse(fs.readFileSync(file, 'utf8'));
        const w = c && typeof c.window === 'object' && c.window ? c.window : {};
        return {
          window: {
            width: Number.isFinite(w.width) ? Math.max(640, w.width) : DEFAULTS.window.width,
            height: Number.isFinite(w.height) ? Math.max(480, w.height) : DEFAULTS.window.height,
            maximized: w.maximized === true,
          },
          dismissedUpdateVersion: typeof c.dismissedUpdateVersion === 'string' ? c.dismissedUpdateVersion : null,
        };
      } catch { return JSON.parse(JSON.stringify(DEFAULTS)); }
    },
    write(patch) {
      const next = { ...store.read(), ...patch };
      fs.mkdirSync(path.dirname(file), { recursive: true });
      const tmp = file + '.tmp';
      fs.writeFileSync(tmp, JSON.stringify(next, null, 2));
      fs.renameSync(tmp, file);       // запись целиком или не происходит вовсе
      return next;
    },
  };
  return store;
}

module.exports = { createConfigStore, DEFAULTS };
