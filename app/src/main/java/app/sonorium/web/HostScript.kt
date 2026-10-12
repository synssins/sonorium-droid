package app.sonorium.web

import kotlinx.serialization.json.JsonPrimitive

/**
 * `window.SONORIUM_HOST` for the server's web UI, contract version 1
 * (APP_API.md 12.2.2). Injected before any page script runs. Messages go to the
 * app through the `SonoriumNative` web message listener and come back as
 * `{type, data}` for the handlers the page registers with `on()`.
 */
object HostScript {
    const val HOST_API = 1
    const val BRIDGE = "SonoriumNative"

    fun build(streamBase: String): String = """
        (function () {
          if (window.SONORIUM_HOST) return;
          var bridge = window.$BRIDGE;
          var handlers = {};
          function send(type, data) {
            if (!bridge) return;
            var message = data || {};
            message.type = type;
            bridge.postMessage(JSON.stringify(message));
          }
          if (bridge) {
            bridge.onmessage = function (event) {
              var message;
              try { message = JSON.parse(event.data); } catch (e) { return; }
              (handlers[message.type] || []).forEach(function (handler) {
                try { handler(message.data || {}); } catch (e) { console.error(e); }
              });
            };
          }
          window.SONORIUM_HOST = {
            mode: 'server',
            baseUrl: '',
            streamBase: ${JsonPrimitive(streamBase)},
            authHeader: function () { return null; },
            unlinked: function (body) { send('unlinked', { detail: body && body.detail }); },
            showServers: function () { send('showServers'); },
            listen: function (sessionId, url, info) {
              info = info || {};
              send('listen', { sessionId: sessionId, url: url, name: info.name || '', themeName: info.themeName || '',
                               volume: typeof info.volume === 'number' ? info.volume : 100 });
            },
            stopListening: function (sessionId) { send('stopListening', { sessionId: sessionId }); },
            setListenVolume: function (sessionId, volume) { send('setListenVolume', { sessionId: sessionId, volume: volume }); },
            on: function (event, handler) { (handlers[event] = handlers[event] || []).push(handler); }
          };
          send('ready');

          // A "Servers" menu item, until the web UI adds its own (an element with
          // data-host="servers"): after Status in the System section.
          function addServersItem() {
            if (document.querySelector('[data-host="servers"]')) return;
            var status = document.querySelector('.sidebar-nav .nav-item[onclick*="showView(\'status\')"]');
            if (!status) return;
            var item = document.createElement('div');
            item.className = 'nav-item';
            item.setAttribute('data-host', 'servers');
            item.setAttribute('role', 'button');
            item.innerHTML =
              '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" aria-hidden="true">' +
              '<rect x="2" y="3" width="20" height="7" rx="2"/><rect x="2" y="14" width="20" height="7" rx="2"/>' +
              '<line x1="6" y1="6.5" x2="6.01" y2="6.5"/><line x1="6" y1="17.5" x2="6.01" y2="17.5"/></svg>' +
              '<span>Servers</span>';
            item.addEventListener('click', function () {
              if (typeof closeSidebar === 'function') closeSidebar();
              window.SONORIUM_HOST.showServers();
            });
            status.parentNode.insertBefore(item, status.nextSibling);
          }
          if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', addServersItem);
          else addServersItem();
        })();
    """.trimIndent()
}
