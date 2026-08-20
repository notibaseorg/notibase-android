/**
 * NotibaseApi — pure-Kotlin HTTP core (no Android imports, no deps).
 *
 * Uses HttpURLConnection (available since API 1 and on plain JVM), so this
 * whole file is exercised by the JVM e2e suite against the real API before
 * it ever ships inside an app. The Android layer wraps it with threading
 * and storage; this class is intentionally synchronous and stateless.
 *
 * Security model (Arch §5.3): this client carries a ck_ ("client") key,
 * which is PUBLIC BY DESIGN — it can only register devices, identify with
 * an HMAC signature minted by the app's backend, track events, and read
 * the inbox of a device it proves it owns. Server keys (sk_) are refused
 * outright so nobody ships one inside an APK by mistake.
 */
package com.notibase.sdk.internal

import java.io.IOException
import java.net.HttpURLConnection

import java.nio.charset.StandardCharsets

public class NotibaseApiException(
    public val statusCode: Int,
    message: String,
) : IOException("notibase api $statusCode: $message")

public class NotibaseApi(
    clientKey: String,
    private val apiUrl: String = "https://api.notibase.com",
    private val connectTimeoutMs: Int = 10_000,
    private val readTimeoutMs: Int = 15_000,
) {
    private val key: String

    init {
        require(!clientKey.startsWith("sk_")) {
            "You passed a SERVER key (sk_…) to the Android SDK. Server keys grant " +
                "full account access and must never ship inside an app — use your " +
                "client key (ck_…) from the Notibase dashboard instead."
        }
        require(clientKey.startsWith("ck_")) { "Notibase client keys start with ck_" }
        key = clientKey
    }

    /** POST /v1/devices → device id. Idempotent server-side per (app, token). */
    public fun registerDevice(
        fcmToken: String,
        locale: String? = null,
        timezone: String? = null,
    ): String {
        val body = mutableMapOf<String, Any?>(
            "platform" to "android",
            "token" to fcmToken,
        )
        if (locale != null) body["locale"] = locale
        if (timezone != null) body["timezone"] = timezone
        val res = request("POST", "/v1/devices", body)
        return res["id"] as? String ?: throw NotibaseApiException(500, "no device id in response")
    }

    /**
     * POST /v1/identify → user id. When the app has identity verification
     * enabled (recommended), [signature] is HMAC-SHA256(identify_secret,
     * externalId) hex — minted by YOUR backend, never in the app.
     */
    public fun identify(
        externalId: String,
        deviceId: String?,
        signature: String? = null,
        attributes: Map<String, Any?> = emptyMap(),
    ): String {
        val body = mutableMapOf<String, Any?>(
            "external_id" to externalId,
            "attributes" to attributes,
        )
        if (deviceId != null) body["device_id"] = deviceId
        if (signature != null) body["signature"] = signature
        val res = request("POST", "/v1/identify", body)
        return res["id"] as? String ?: throw NotibaseApiException(500, "no user id in response")
    }

    /** POST /v1/events — single event. Reserved names (install, session_start, purchase) feed attribution. */
    public fun track(
        name: String,
        properties: Map<String, Any?> = emptyMap(),
        deviceId: String? = null,
    ) {
        val body = mutableMapOf<String, Any?>("name" to name, "properties" to properties)
        if (deviceId != null) body["device_id"] = deviceId
        request("POST", "/v1/events", body)
    }

    /** GET /v1/inbox — messages for the identified user behind this device. */
    public fun inboxList(deviceId: String, limit: Int = 50, cursor: String? = null): List<InboxItem> {
        var path = "/v1/inbox?device_id=" + urlEncode(deviceId) + "&limit=" + limit
        if (cursor != null) path += "&cursor=" + urlEncode(cursor)
        val res = request("GET", path, null)
        val items = res["items"] as? List<*> ?: emptyList<Any?>()
        return items.mapNotNull { raw ->
            val m = raw as? Map<*, *> ?: return@mapNotNull null
            InboxItem(
                id = m["id"] as? String ?: return@mapNotNull null,
                content = (m["content"] as? Map<*, *>)?.entries
                    ?.associate { (k, v) -> k.toString() to v } ?: emptyMap(),
                readAt = m["read_at"] as? String,
                createdAt = m["created_at"] as? String ?: "",
            )
        }
    }

    /** POST /v1/inbox/read — mark inbox items read. */
    public fun inboxMarkRead(deviceId: String, ids: List<String>) {
        if (ids.isEmpty()) return
        request("POST", "/v1/inbox/read", mapOf("device_id" to deviceId, "ids" to ids))
    }

    // ── plumbing ────────────────────────────────────────────
    private fun request(method: String, path: String, body: Map<String, Any?>?): Map<String, Any?> {
        var lastError: IOException? = null
        for (attempt in 0..MAX_RETRIES) {
            if (attempt > 0) Thread.sleep(Backoff.delayMs(attempt))
            try {
                return requestOnce(method, path, body)
            } catch (e: NotibaseApiException) {
                // Retry only what retrying can fix: 429 + 5xx. 4xx are ours.
                if (e.statusCode == 429 || e.statusCode >= 500) { lastError = e; continue }
                throw e
            } catch (e: IOException) {
                lastError = e // network flake — retry
            }
        }
        throw lastError ?: IOException("notibase: request failed")
    }

    private fun requestOnce(method: String, path: String, body: Map<String, Any?>?): Map<String, Any?> {
        val conn = java.net.URI(apiUrl + path).toURL().openConnection() as HttpURLConnection
        try {
            conn.requestMethod = method
            conn.connectTimeout = connectTimeoutMs
            conn.readTimeout = readTimeoutMs
            conn.setRequestProperty("authorization", "Bearer $key")
            conn.setRequestProperty("user-agent", "notibase-android/$VERSION")
            if (body != null) {
                conn.doOutput = true
                conn.setRequestProperty("content-type", "application/json")
                conn.outputStream.use { it.write(MiniJson.write(body).toByteArray(StandardCharsets.UTF_8)) }
            }
            val status = conn.responseCode
            val text = (if (status < 400) conn.inputStream else conn.errorStream)
                ?.readBytes()?.toString(StandardCharsets.UTF_8) ?: ""
            if (status >= 400) {
                val message = try {
                    MiniJson.parseObject(text)["error"] as? String ?: text.take(200)
                } catch (_: Exception) { text.take(200) }
                throw NotibaseApiException(status, message)
            }
            return try { MiniJson.parseObject(text) } catch (_: Exception) { emptyMap() }
        } finally {
            conn.disconnect()
        }
    }

    private fun urlEncode(s: String): String =
        java.net.URLEncoder.encode(s, StandardCharsets.UTF_8.name())

    public companion object {
        /**
         * Notification-click beacon — unauthenticated by design (the pair
         * must match an existing delivery server-side; duplicates are
         * dropped there too). Never throws: a lost beacon must never crash
         * a notification tap.
         */
        @JvmStatic
        public fun postClick(origin: String, messageId: String, deviceId: String) {
            try {
                val conn = java.net.URI("$origin/v1/push/click").toURL()
                    .openConnection() as HttpURLConnection
                try {
                    conn.requestMethod = "POST"
                    conn.connectTimeout = 5000
                    conn.readTimeout = 5000
                    conn.doOutput = true
                    conn.setRequestProperty("content-type", "application/json")
                    conn.setRequestProperty("user-agent", "notibase-android/$VERSION")
                    conn.outputStream.use {
                        it.write(MiniJson.write(mapOf("m" to messageId, "d" to deviceId))
                            .toByteArray(StandardCharsets.UTF_8))
                    }
                    conn.inputStream.use { s -> s.readBytes() }
                } finally {
                    conn.disconnect()
                }
            } catch (_: Exception) { /* beacon only */ }
        }

        public const val VERSION: String = "0.2.0"
        private const val MAX_RETRIES = 2
    }
}

public data class InboxItem(
    val id: String,
    val content: Map<String, Any?>,
    val readAt: String?,
    val createdAt: String,
)

internal object Backoff {
    /** 500ms, 2s — with jitter; the SDK must never hammer a struggling API. */
    fun delayMs(attempt: Int): Long {
        val base = 500L shl (2 * (attempt - 1)).coerceAtMost(8)
        return base + (Math.random() * base * 0.25).toLong()
    }
}
