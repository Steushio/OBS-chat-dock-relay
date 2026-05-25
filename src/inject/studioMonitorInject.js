const { ipcRenderer } = require('electron');

// This preload only acts as a secure IPC bridge.
// The actual monkeypatching is injected from the main process via
// executeJavaScript(), which bypasses YouTube Studio's Content Security Policy.

// Forward intercepted API payloads to the main process
window.addEventListener('youtubei-api-data', (event) => {
  const { url, data } = event.detail;
  ipcRenderer.send('studio-api-data', { url, data });
});

// Forward DOM-scraped fallback data
window.addEventListener('youtubei-dom-scraped', (event) => {
  ipcRenderer.send('studio-dom-data', event.detail);
});
