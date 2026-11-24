package com.nirhib.boiler

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebView
import com.fasterxml.jackson.databind.node.ObjectNode
import com.nirhib.boiler.core.Settings
import com.nirhib.boiler.core.SwitcherClient
import java.time.ZoneId
import java.util.concurrent.Executors

/**
 * window.BoilerNative.call(id, action, payloadJson) from the web UI. Work runs off the UI thread and the
 * answer comes back through window.__nativeResolve(id, resultJson), which resolves a JS promise.
 */
class Bridge(private val context: Context, private val web: WebView) {
    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val json = Settings.mapper
    private val store = Store(context)

    @JavascriptInterface
    fun call(id: String, action: String, payload: String) {
        io.execute {
            val result: Any = try {
                handle(action, json.readTree(payload.ifBlank { "{}" }))
            } catch (e: Exception) {
                mapOf("ok" to false, "error" to (e.message ?: e.javaClass.simpleName), "message" to (e.message ?: ""))
            }
            val out = json.writeValueAsString(json.writeValueAsString(result)) // JSON string literal for JS
            main.post { web.evaluateJavascript("window.__nativeResolve(${json.writeValueAsString(id)}, $out)", null) }
        }
    }

    private fun handle(action: String, p: com.fasterxml.jackson.databind.JsonNode): Any = when (action) {
        "status" -> {
            val s = store.settings()
            val raw = store.settingsJson?.let { json.readTree(it) as ObjectNode }
            raw?.withObjectProperty("switcher")?.remove("key") // the key stays in native storage
            mapOf(
                "settings" to raw,
                "switcherConfigured" to (s?.switcher?.configured == true),
                "lastSync" to store.lastSync,
                "programmed" to store.programmed,
                "learn" to store.measuredHeaterKw?.let { mapOf("heaterKw" to it, "autoFactor" to 1) },
            )
        }
        "saveSettings" -> {
            val incoming = p as ObjectNode
            // keep the stored switch key when the UI sends it empty (the UI never receives it back)
            val oldKey = store.settings()?.switcher?.key.orEmpty()
            val sw = incoming.withObjectProperty("switcher")
            if (sw.path("key").asText().isBlank() && oldKey.isNotBlank()) sw.put("key", oldKey)
            store.settingsJson = json.writeValueAsString(incoming)
            Sync.soon(context)
            mapOf("ok" to true)
        }
        "discover" -> mapOf("devices" to Discovery.find(context))
        "sync" -> Sync.run(context)
        "on", "off" -> {
            val sw = store.settings()?.switcher ?: error("המתג לא הוגדר")
            if (!sw.configured) error("המתג לא הוגדר")
            SwitcherClient(sw.ip, sw.id, sw.key, ZoneId.systemDefault(), 4000).use {
                if (action == "on") it.turnOn(p.path("minutes").asInt(30).coerceIn(1, 240)) else it.turnOff()
            }
            mapOf("ok" to true)
        }
        else -> error("unknown action $action")
    }
}
