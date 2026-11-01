'use strict';
const { contextBridge, ipcRenderer } = require('electron');

// Мост нужен только локальной странице ошибки (file://). Загруженный с сервера интерфейс его не получает.
if (location.protocol === 'file:') {
  contextBridge.exposeInMainWorld('freedomDesktop', { retry: () => ipcRenderer.invoke('freedom:retry') });
}
