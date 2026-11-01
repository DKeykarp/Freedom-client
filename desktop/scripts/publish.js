'use strict';
/* Копирует собранные установщики (dist/*.exe|deb|rpm) в папку downloads сервера, чтобы их можно было
   скачать со страницы входа. Запуск:  npm run publish:local [-- --to /путь/к/server/downloads] */
const fs = require('fs');
const path = require('path');
const crypto = require('crypto');

const NAME_RE = /^Freedom[-_][\w.+~-]{1,120}\.(exe|deb|rpm)$/;

function collect(distDir) {
  if (!fs.existsSync(distDir)) return [];
  return fs.readdirSync(distDir, { withFileTypes: true })
    .filter((d) => d.isFile() && NAME_RE.test(d.name))
    .map((d) => path.join(distDir, d.name));
}

const sha256 = (file) => crypto.createHash('sha256').update(fs.readFileSync(file)).digest('hex');

function publish(distDir, toDir) {
  const files = collect(distDir);
  if (!files.length) throw new Error(`В ${distDir} нет установщиков. Сначала: npm run dist:linux / npm run dist:win`);
  fs.mkdirSync(toDir, { recursive: true });
  return files.map((f) => {
    const dest = path.join(toDir, path.basename(f));
    fs.copyFileSync(f, dest);
    return { name: path.basename(f), size: fs.statSync(dest).size, sha256: sha256(dest), dest };
  });
}

module.exports = { collect, publish, NAME_RE };

if (require.main === module) {
  const i = process.argv.indexOf('--to');
  const to = path.resolve(i > -1 ? process.argv[i + 1] : process.env.FREEDOM_DOWNLOADS || path.join(__dirname, '..', '..', 'server', 'downloads'));
  try {
    for (const r of publish(path.join(__dirname, '..', 'dist'), to)) {
      console.log(`${r.name}  ${(r.size / 1048576).toFixed(1)} МБ  sha256 ${r.sha256}`);
    }
    console.log(`Готово: файлы скопированы в ${to}`);
  } catch (e) {
    console.error(e.message);
    process.exit(1);
  }
}
