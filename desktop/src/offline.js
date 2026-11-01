'use strict';
(() => {
  const $ = (id) => document.getElementById(id);
  const q = new URLSearchParams(location.search);
  $('err').textContent = q.get('error') || '';
  $('server').textContent = q.get('server') || '';
  const api = window.freedomDesktop;
  if (!api) { $('retry').disabled = true; return; }

  let left = 20;
  const retry = async () => { $('retry').disabled = true; $('auto').textContent = 'Подключение…'; try { await api.retry(); } catch { $('retry').disabled = false; } };
  $('retry').addEventListener('click', retry);
  const tick = setInterval(() => {                       // само пробует снова каждые 20 секунд
    $('auto').textContent = `Повтор через ${left} с`;
    if (left-- <= 0) { clearInterval(tick); retry(); }
  }, 1000);
})();
