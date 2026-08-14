package ai.arena.webapp.vpn

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import android.util.Log
import java.io.BufferedReader
import java.io.DataOutputStream
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
import java.net.URL
import java.util.regex.Pattern
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext

/**
 * Бесплатный VPN-аккаунт Cloudflare WARP.
 *
 * Регистрирует устройство в WARP так же, как это делают публичные
 * генераторы конфигов (warp-generator и т.п.): генерируется ключ X25519,
 * POST на api.cloudflareclient.com/v0a2158/reg, в ответ приходит
 * WireGuard-конфиг (серверный ключ, endpoint, адрес).
 *
 * Конфиг кэшируется в SharedPreferences; при ошибках перерегистрируется.
 */
class WarpClient(context: Context) {

    data class WarpConfig(
        val clientPrivateKey: String,   // base64
        val serverPublicKey: String,    // base64
        val endpointHost: String,
        val endpointPort: Int,
        val address: String             // например 172.16.0.2
    )

    companion object {
        private const val TAG = "WarpClient"
        private const val PREFS = "arena_warp"
        private const val KEY_CONFIG = "warp_config_json"

        private const val REG_URL = "https://api.cloudflareclient.com/v0a2158/reg"

        // Запасные endpoint'ы WARP (публично известные адреса)
        val FALLBACK_ENDPOINTS = listOf(
            "162.159.192.1", "162.159.192.2", "162.159.192.3", "162.159.192.4",
            "162.159.192.5", "162.159.192.6", "162.159.192.7", "162.159.192.8",
            "162.159.192.9", "162.159.192.10",
            "162.159.193.1", "162.159.193.2", "162.159.193.3", "162.159.193.4",
            "162.159.193.5", "162.159.193.6", "162.159.193.7", "162.159.193.8",
            "162.159.193.9", "162.159.193.10",
            "engage.cloudflareclient.com"
        )
    }

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Загрузить сохранённый конфиг. */
    fun loadCached(): WarpConfig? {
        val json = prefs.getString(KEY_CONFIG, "") ?: return null
        if (json.isEmpty()) return null
        return parseConfig(json)
    }

    fun save(config: WarpConfig) {
        val json = buildString {
            append("{")
            append("\"pk\":\"").append(config.clientPrivateKey).append("\",")
            append("\"sk\":\"").append(config.serverPublicKey).append("\",")
            append("\"ep\":\"").append(config.endpointHost).append("\",")
            append("\"port\":").append(config.endpointPort).append(",")
            append("\"addr\":\"").append(config.address).append("\"")
            append("}")
        }
        prefs.edit().putString(KEY_CONFIG, json).apply()
    }

    fun clear() {
        prefs.edit().remove(KEY_CONFIG).apply()
    }

    /**
     * Зарегистрировать новый аккаунт WARP. Блокирующий сетевой вызов —
     * запускать в фоновом потоке.
     */
    fun register(): WarpConfig? {
        return try {
            val keyPair = X25519.generateKeyPair()
            val pubB64 = Base64.encodeToString(keyPair.publicKey, Base64.NO_WRAP)
            val body = "{\"key\":\"$pubB64\"," +
                "\"install_id\":\"\"," +
                "\"fcm_token\":\"\"," +
                "\"tos\":\"2022-06-01T00:00:00.000Z\"," +
                "\"type\":\"Android\"," +
                "\"model\":\"\"," +
                "\"serial_number\":\"\"," +
                "\"locale\":\"en_US\"," +
                "\"warp_enabled\":true}"

            val conn = URL(REG_URL).openConnection() as HttpsURLConnection
            conn.connectTimeout = 15000
            conn.readTimeout = 20000
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
            conn.setRequestProperty("User-Agent", "okhttp/3.12.1")
            conn.setRequestProperty("Accept", "application/json")
            DataOutputStream(conn.outputStream).use { it.write(body.toByteArray()) }

            val code = conn.responseCode
            if (code !in 200..299) {
                Log.w(TAG, "WARP register failed: HTTP $code")
                return null
            }
            val resp = conn.inputStream.bufferedReader().use(BufferedReader::readText)
            val config = parseRegisterResponse(resp, keyPair.privateKey)
            config?.let { save(it) }
            config
        } catch (t: Throwable) {
            Log.w(TAG, "WARP register error", t)
            null
        }
    }

    /** Достать конфиг из ответа регистрации. */
    fun parseRegisterResponse(body: String, clientPrivateKey: ByteArray): WarpConfig? {
        val serverKey = extractString(body, "\"public_key\":\"", "\"")
            ?: return null
        var endpoint = extractString(body, "\"v4\":\"", "\"")
        var port = 2408
        if (endpoint != null && endpoint.contains(':')) {
            val parts = endpoint.split(':')
            endpoint = parts[0]
            port = parts[1].toIntOrNull() ?: 2408
        }
        val address = extractString(body, "\"addresses\":[", "]")
            ?.let { block ->
                val m = Pattern.compile("\"(\\d+\\.\\d+\\.\\d+\\.\\d+)/32\"").matcher(block)
                if (m.find()) m.group(1) else null
            }
        if (serverKey.isEmpty()) return null
        return WarpConfig(
            clientPrivateKey = Base64.encodeToString(clientPrivateKey, Base64.NO_WRAP),
            serverPublicKey = serverKey,
            endpointHost = endpoint ?: FALLBACK_ENDPOINTS[0],
            endpointPort = port,
            address = address ?: "172.16.0.2"
        )
    }

    private fun parseConfig(json: String): WarpConfig? {
        val pk = extractString(json, "\"pk\":\"", "\"") ?: return null
        val sk = extractString(json, "\"sk\":\"", "\"") ?: return null
        val ep = extractString(json, "\"ep\":\"", "\"") ?: return null
        val port = extractString(json, "\"port\":", ",")?.toIntOrNull() ?: 2408
        val addr = extractString(json, "\"addr\":\"", "\"") ?: "172.16.0.2"
        if (pk.isEmpty() || sk.isEmpty() || ep.isEmpty()) return null
        return WarpConfig(pk, sk, ep, port, addr)
    }

    private fun extractString(body: String, prefix: String, suffix: String): String? {
        val i = body.indexOf(prefix)
        if (i < 0) return null
        val start = i + prefix.length
        val j = body.indexOf(suffix, start)
        if (j < 0) return null
        return body.substring(start, j)
    }

    /** Проверить достижимость endpoint (TCP connect). */
    fun isReachable(host: String, port: Int, timeoutMs: Int = 4000): Boolean {
        return try {
            val s = Socket()
            s.connect(InetSocketAddress(host, port), timeoutMs)
            s.close()
            true
        } catch (t: Throwable) {
            false
        }
    }
}
