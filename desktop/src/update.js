'use strict';
/* Проверка обновлений: чистая логика без Electron (сеть и диалоги — в main.js). */

/** Сравнивает версии вида "1.2.3" или "5.0" по числовым частям (не строкам): -1, 0 или 1. */
function compareVersions(a, b) {
  const pa = String(a).split('.').map(Number), pb = String(b).split('.').map(Number);
  const len = Math.max(pa.length, pb.length);
  for (let i = 0; i < len; i++) {
    const x = pa[i] || 0, y = pb[i] || 0;
    if (x !== y) return x < y ? -1 : 1;
  }
  return 0;
}

const isValidVersion = (v) => typeof v === 'string' && /^\d+(\.\d+){1,2}$/.test(v);

/**
 * Решает, что показать пользователю, по ответам сервера (/api/ping, /api/downloads) и текущей версии.
 * platform: 'windows' | 'linux'. Возвращает null (всё актуально) или { severity, current, latest, url }.
 * severity 'mandatory' — версия ниже той, что сервер ещё поддерживает (min_client_version); 'optional' —
 * просто вышла более новая версия. Битые/отсутствующие данные (например, на этой платформе пока нет
 * установщика) не считаются ошибкой — тогда просто нечего предложить.
 */
function decideUpdate({ currentVersion, platform, ping, downloads }) {
  const minVersion = ping && ping.min_client_version && ping.min_client_version[platform];
  const item = Array.isArray(downloads) ? downloads.find((d) => d.platform === platform && isValidVersion(d.version)) : null;

  if (isValidVersion(minVersion) && compareVersions(currentVersion, minVersion) < 0) {
    const qualifies = item && compareVersions(item.version, minVersion) >= 0;
    return {
      severity: 'mandatory', current: currentVersion,
      latest: qualifies ? item.version : minVersion,
      url: qualifies ? item.url : null,
    };
  }
  if (item && compareVersions(currentVersion, item.version) < 0) {
    return { severity: 'optional', current: currentVersion, latest: item.version, url: item.url };
  }
  return null;
}

module.exports = { compareVersions, isValidVersion, decideUpdate };
