/**
 * Notibase Android SDK — public entry point.
 *
 * Design rules (Arch §8.2):
 *  - ZERO dependencies beyond firebase-messaging: no coroutines, no okhttp,
 *    no gson. A plain single-thread executor does the background work.
 *  - Never crash the host app: every public call is fire-and-forget with an
 *    optional callback; failures are logged and retried, not thrown across
 *    the app's threads.
 *  - The client key is public by design (ck_…); anything sensitive
 *    (identify signatures) is minted by the app's own backend.
 *
 * Quickstart:
 *   Notibase.init(context, "ck_live_…")
 *   Notibase.identify("user-42", signature = sigFromYourBackend)
 *   Notibase.track("level_complete", mapOf("level" to 3))
 */
package com.notibase.sdk

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Build
import android.util.Log
import com.notibase.sdk.internal.InboxItem
import com.notibase.sdk.internal.NotibaseApi
import java.util.TimeZone
import java.util.Locale
import java.util.concurrent.Executors

public object Notibase {
    private const val TAG = "Notibase"
    private const val PREFS = "notibase_sdk"
    private const val KEY_DEVICE_ID = "device_id"
    private const val KEY_FCM_TOKEN = "fcm_token"
    private const val KEY_EXTERNAL_ID = "external_id"

    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "notibase-sdk").apply { isDaemon = true }
    }

    @Volatile private var api: NotibaseApi? = null
    @Volatile private var prefs: SharedPreferences? = null
    @Volatile internal var config: Config = Config()

    public data class Config(
        val apiUrl: String = "https://api.notibase.com",
        /** Channel used for notifications rendered by the SDK (Android 8+). */
        val notificationChannelId: String = "notibase_default",
        val notificationChannelName: String = "Notifications",
        /** Small icon resource; 0 = use the app's launcher icon. */
        val smallIconRes: Int = 0,
    )

    /**
     * Initialize once, e.g. in Application.onCreate(). Safe to call again
     * (subsequent calls are no-ops). Registers the device as soon as a
     * Firebase token is known — either the cached one or the next
     * onNewToken() callback.
     */
    @JvmStatic
    @JvmOverloads
    public fun init(context: Context, clientKey: String, config: Config = Config()) {
        if (api != null) return
        this.config = config
        api = NotibaseApi(clientKey, config.apiUrl)
        prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        // A token cached from a previous process lifetime → register now.
        prefs?.getString(KEY_FCM_TOKEN, null)?.let { registerToken(it) }
    }

    /** Called by NotibaseMessagingService.onNewToken; also callable manually. */
    @JvmStatic
    public fun registerToken(fcmToken: String) {
        val a = api ?: return logNotInit()
        val p = prefs ?: return logNotInit()
        p.edit().putString(KEY_FCM_TOKEN, fcmToken).apply()
        executor.execute {
            try {
                val deviceId = a.registerDevice(
                    fcmToken = fcmToken,
                    locale = Locale.getDefault().toLanguageTag(),
                    timezone = TimeZone.getDefault().id,
                )
                p.edit().putString(KEY_DEVICE_ID, deviceId).apply()
                Log.d(TAG, "device registered: $deviceId")
            } catch (e: Exception) {
                Log.w(TAG, "device registration failed (will retry on next token refresh)", e)
            }
        }
    }

    /**
     * Link this device to your user. When identity verification is enabled
     * for the app (recommended), pass the HMAC [signature] your backend
     * minted for [externalId] — never compute it in the app.
     */
    @JvmStatic
    @JvmOverloads
    public fun identify(
        externalId: String,
        signature: String? = null,
        attributes: Map<String, Any?> = emptyMap(),
        onResult: ((Boolean) -> Unit)? = null,
    ) {
        val a = api ?: return logNotInit()
        val p = prefs ?: return logNotInit()
        executor.execute {
            try {
                a.identify(externalId, p.getString(KEY_DEVICE_ID, null), signature, attributes)
                p.edit().putString(KEY_EXTERNAL_ID, externalId).apply()
                onResult?.invoke(true)
            } catch (e: Exception) {
                Log.w(TAG, "identify failed", e)
                onResult?.invoke(false)
            }
        }
    }

    /** Track a custom event (feeds segments + future attribution, Arch §7.3). */
    @JvmStatic
    @JvmOverloads
    public fun track(name: String, properties: Map<String, Any?> = emptyMap()) {
        val a = api ?: return logNotInit()
        executor.execute {
            try {
                a.track(name, properties, prefs?.getString(KEY_DEVICE_ID, null))
            } catch (e: Exception) {
                Log.w(TAG, "track($name) failed", e)
            }
        }
    }

    /** Fetch the in-app inbox for the identified user behind this device. */
    @JvmStatic
    @JvmOverloads
    public fun inbox(limit: Int = 50, onResult: (List<InboxItem>?) -> Unit) {
        val a = api ?: return logNotInit()
        val deviceId = prefs?.getString(KEY_DEVICE_ID, null)
            ?: return onResult(null)
        executor.execute {
            try {
                onResult(a.inboxList(deviceId, limit))
            } catch (e: Exception) {
                Log.w(TAG, "inbox fetch failed", e)
                onResult(null)
            }
        }
    }

    /** Mark inbox items read. */
    @JvmStatic
    public fun inboxMarkRead(ids: List<String>) {
        val a = api ?: return logNotInit()
        val deviceId = prefs?.getString(KEY_DEVICE_ID, null) ?: return
        executor.execute {
            try { a.inboxMarkRead(deviceId, ids) } catch (e: Exception) { Log.w(TAG, "markRead failed", e) }
        }
    }

    /**
     * Record a notification open from a raw FCM data payload — call this
     * when YOU handle taps (custom notifications, or system-tray taps
     * where the extras land on your launcher activity's intent).
     * SDK-rendered notifications track opens automatically.
     */
    @JvmStatic
    public fun trackNotificationOpen(data: Map<String, String?>) {
        val m = data["nb_m"] ?: return
        val d = data["nb_d"] ?: return
        val o = data["nb_o"] ?: return
        executor.execute { NotibaseApi.postClick(o, m, d) }
    }

    /**
     * Convenience for the system-tray path: when the app was BACKGROUNDED,
     * Android renders the notification itself and the tap opens your
     * launcher activity with the data payload as intent extras. Call this
     * from onCreate/onNewIntent:  Notibase.trackOpenFromIntent(intent)
     */
    @JvmStatic
    public fun trackOpenFromIntent(intent: Intent?) {
        val extras = intent?.extras ?: return
        trackNotificationOpen(
            mapOf(
                "nb_m" to extras.getString("nb_m"),
                "nb_d" to extras.getString("nb_d"),
                "nb_o" to extras.getString("nb_o"),
            )
        )
    }

    /** The stored Notibase device id, once registration has succeeded. */
    @JvmStatic
    public fun deviceId(): String? = prefs?.getString(KEY_DEVICE_ID, null)

    internal fun isInitialized(): Boolean = api != null

    private fun logNotInit() {
        Log.w(TAG, "Notibase.init(context, clientKey) has not been called yet")
    }

    /** Android release fingerprint, for future diagnostics. */
    internal fun osTag(): String = "android-${Build.VERSION.SDK_INT}"
}
