'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('fs'); const os = require('os'); const path = require('path'); const crypto = require('crypto');
const { collect, publish, NAME_RE } = require('../scripts/publish');

test('копируются только установщики Freedom; служебные файлы сборки — нет', () => {
  const dist = fs.mkdtempSync(path.join(os.tmpdir(), 'dist-')), to = path.join(fs.mkdtempSync(path.join(os.tmpdir(), 'to-')), 'downloads');
  const data = { 'Freedom-Setup-1.0.0.exe': 'MZ1', 'Freedom-1.0.0-linux-x64.deb': 'deb', 'Freedom-1.0.0-linux-x64.rpm': 'rpm',
    'Freedom-Setup-1.0.0.exe.blockmap': 'b', 'builder-debug.yml': 'y', 'latest.yml': 'l', 'freedom.exe': 'x' };
  for (const [n, c] of Object.entries(data)) fs.writeFileSync(path.join(dist, n), c);
  fs.mkdirSync(path.join(dist, 'win-unpacked')); fs.mkdirSync(path.join(dist, 'Freedom-dir.exe'));
  assert.deepEqual(collect(dist).map((f) => path.basename(f)).sort(), ['Freedom-1.0.0-linux-x64.deb', 'Freedom-1.0.0-linux-x64.rpm', 'Freedom-Setup-1.0.0.exe']);
  const res = publish(dist, to);
  assert.equal(res.length, 3);
  for (const r of res) {
    assert.equal(fs.readFileSync(r.dest, 'utf8'), data[r.name]);
    assert.equal(r.sha256, crypto.createHash('sha256').update(data[r.name]).digest('hex'));
  }
  assert.deepEqual(fs.readdirSync(to).sort(), Object.keys(data).filter((n) => NAME_RE.test(n)).sort());
});

test('нет установщиков — понятная ошибка', () => {
  assert.throws(() => publish(path.join(os.tmpdir(), 'nope-' + Date.now()), os.tmpdir()), /нет установщиков/);
});

test('имена, которые публикует скрипт, совпадают с теми, что принимает сервер', () => {
  // server/ теперь отдельный архив от клиентов — если его нет рядом, эту сверку просто пропускаем.
  const serverMain = path.join(__dirname, '..', '..', 'server', 'main.py');
  if (!fs.existsSync(serverMain)) {
    console.log('  server/main.py не найден рядом (это архив только клиентов) — сверка пропущена');
    return;
  }
  const py = fs.readFileSync(serverMain, 'utf8');
  const m = py.match(/DOWNLOAD_RE = re\.compile\(r"(.+?)", re\.ASCII\)/);
  assert.ok(m, 'regexp на сервере найден');
  const server = new RegExp('^' + m[1].replace(/\\\\/g, '\\') + '$');
  for (const n of ['Freedom-Setup-1.0.0.exe', 'Freedom-1.0.0-linux-x64.deb', 'Freedom-1.0.0-linux-x86_64.rpm', 'Freedom_1.2.3_amd64.deb',
    'Freedom-x.dmg', 'freedom.deb', 'Freedom-.exe', 'evil.exe', 'Freedom-a/b.exe'])
    assert.equal(server.test(n), NAME_RE.test(n), n);
});
