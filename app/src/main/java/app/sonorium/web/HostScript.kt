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
            on: function (event, handler) { (handlers[event] = handlers[event] || []).push(handler); }
          };
          send('ready');
        })();
    """.trimIndent()
}
