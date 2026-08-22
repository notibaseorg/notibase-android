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

import android.app.Activity
import android.app.Application
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import com.notibase.sdk.internal.InboxItem
import com.notibase.sdk.internal.NotibaseApi
import com.notibase.sdk.internal.NotibaseLinks
import com.notibase.sdk.internal.SetupCheck
import java.util.TimeZone
import java.util.Locale
import java.util.concurrent.Executors

public object Notibase {
    private const val TAG = "Notibase"
    /** Reported by the setup test, so a stale SDK is visible in the console. */
    private const val SDK_VERSION = "0.5.0"
    private const val PREFS = "notibase_sdk"
    private const val KEY_DEVICE_ID = "device_id"
    private const val KEY_FCM_TOKEN = "fcm_token"
    private const val KEY_EXTERNAL_ID = "external_id"
    private const val KEY_INSTALL_REPORTED = "install_reported"
    private const val KEY_PENDING_CLICK = "pending_click"

    /** Per-process, not persisted: one session_start per cold start. */
    @Volatile private var sessionReported = false

    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "notibase-sdk").apply { isDaemon = true }
    }

    @Volatile private var api: NotibaseApi? = null
    @Volatile private var prefs: SharedPreferences? = null
    /** Application context, so a destination can be opened from anywhere. */
    @Volatile private var appContext: Context? = null
    @Volatile internal var config: Config = Config()

    public data class Config(
        val apiUrl: String = "https://api.notibase.com",
        /** Channel used for notifications rendered by the SDK (Android 8+). */
        val notificationChannelId: String = "notibase_default",
        val notificationChannelName: String = "Notifications",
        /** Small icon resource; 0 = use the app's launcher icon. */
        val smallIconRes: Int = 0,
        /**
         * Watch activities so a tap on a system-tray notification is recorded
         * without your launcher activity forwarding its intent. Turn it off if
         * you would rather call [trackOpenFromIntent] yourself.
         */
        val autoTrackOpens: Boolean = true,
        /**
         * Open the notification's `url` when one is tapped. On by default —
         * a message composed with a URL should go there.
         *
         * Turn it off if you route every destination yourself, or set
         * [urlHandler] to intercept only the ones you care about.
         */
        val openUrls: Boolean = true,
        /**
         * Report `install` once and `session_start` on every cold start, so
         * campaign links can be credited with the installs they drove
         * (Arch §7.3). Two events per launch at most.
         */
        val autoTrackSessions: Boolean = true,
    )

    /**
     * Handle notification destinations yourself — for in-app routing, a
     * navigation graph, or a scheme your app resolves internally.
     *
     * Return true to say you handled it; Notibase then does nothing more.
     * Return false and the URL opens normally.
     *
     *   Notibase.urlHandler = NotibaseUrlHandler { _, url ->
     *       if (!url.startsWith("myapp://")) return@NotibaseUrlHandler false
     *       Router.go(url); true
     *   }
     */
    @JvmStatic
    @Volatile
    public var urlHandler: NotibaseUrlHandler? = null

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
        val app = context.applicationContext
        appContext = app
        prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        // A token cached from a previous process lifetime → register now.
        prefs?.getString(KEY_FCM_TOKEN, null)?.let { registerToken(it) }
        // A device known from a previous launch can report its session now;
        // a brand-new install reports as soon as registration lands.
        reportLifecycle()
        if (config.autoTrackOpens) watchActivitiesForOpens(app)
    }

    /**
     * Report notification opens from the one path the SDK cannot see.
     *
     * Notifications the SDK renders route their taps through
     * NotibaseClickReceiver and record themselves. The exception is a
     * backgrounded app receiving a payload with a `notification` block:
     * Android's system tray draws it, no SDK code runs, and the data arrives
     * as extras on the launcher activity's intent. Apps used to be asked for
     * two overrides — onCreate and onNewIntent — and an app that added only
     * the first lost every open from a warm start with no sign anything was
     * missing.
     *
     * Resume covers both, because onNewIntent is always followed by it.
     * `trackOpenFromIntent` marks the intent it has reported, so an ordinary
     * resume with the same intent does not count a second open.
     */
    private fun watchActivitiesForOpens(app: Context) {
        val application = app as? Application ?: run {
            Log.w(TAG, "context is not an Application — call Notibase.trackOpenFromIntent(intent) " +
                "from your launcher activity to record system-tray opens")
            return
        }
        application.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityResumed(activity: Activity) {
                // An App Link that opened the app carries the campaign click
                // id on its own URL — the deterministic half of attribution,
                // and it is only in the launch intent.
                handleDeepLink(activity.intent?.dataString)
                trackOpenFromIntent(activity.intent, activity)
            }
            override fun onActivityCreated(activity: Activity, state: Bundle?) {}
            override fun onActivityStarted(activity: Activity) {}
            override fun onActivityPaused(activity: Activity) {}
            override fun onActivityStopped(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, out: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        })
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
                reportLifecycle()
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

    /**
     * Record a purchase, with the revenue it earned.
     *
     * `purchase` is one of the three reserved event names, and its `value` is
     * what the attribution report rolls up per campaign. Written out as a
     * method because "which property does the money go in" is the sort of
     * thing that gets guessed wrong once and then produces a revenue column
     * of zeroes that nobody can explain.
     *
     *   Notibase.trackPurchase(9.99, "USD", productId = "pro_monthly")
     */
    @JvmStatic
    @JvmOverloads
    public fun trackPurchase(
        value: Double,
        currency: String = "USD",
        productId: String? = null,
        properties: Map<String, Any?> = emptyMap(),
    ) {
        val props = HashMap<String, Any?>(properties)
        props["value"] = value
        props["currency"] = currency
        if (productId != null) props["product_id"] = productId
        track("purchase", props)
    }

    // ── attribution (Arch §7.3) ───────────────────────────────────────
    // An install that is never reported is an install no campaign gets
    // credit for, and until now nothing on Android reported one. The server
    // side of first-touch matching has been there the whole time.

    /**
     * Hand Notibase a URL that opened your app — an App Link, or your own
     * scheme. If it came from a Notibase campaign link it carries the click
     * id that makes attribution deterministic rather than a guess from an IP
     * and a time window.
     *
     * The SDK already does this for the launch intent's own data URI, so most
     * apps never call it. Reach for it when the URL reaches you some other
     * way — a referrer, a custom router, a web view.
     */
    @JvmStatic
    public fun handleDeepLink(url: String?) {
        val click = NotibaseLinks.clickIdFrom(url) ?: return
        val p = prefs ?: return logNotInit()
        if (p.getString(KEY_PENDING_CLICK, null) == click) return
        p.edit().putString(KEY_PENDING_CLICK, click).apply()
        // Report immediately rather than waiting for the next launch: this is
        // the moment the click id exists, and the server's first-touch guard
        // makes a repeat free.
        reportLifecycle(force = true)
    }

    /**
     * Report the install once, and a session on every cold start.
     *
     * Both are no-ops until a device id exists, because an event with no
     * device cannot be attributed to anything — so this is called again from
     * [registerToken] once registration lands. Calling it twice is cheap and
     * calling it too early is silent, which is the right way round.
     */
    private fun reportLifecycle(force: Boolean = false) {
        if (!config.autoTrackSessions) return
        val p = prefs ?: return
        if (p.getString(KEY_DEVICE_ID, null) == null) return

        val click = p.getString(KEY_PENDING_CLICK, null)
        val props: Map<String, Any?> = if (click != null) mapOf("nb_click" to click) else emptyMap()

        if (!p.getBoolean(KEY_INSTALL_REPORTED, false)) {
            p.edit().putBoolean(KEY_INSTALL_REPORTED, true).apply()
            track("install", props)
        } else if (force || !sessionReported) {
            sessionReported = true
            track("session_start", props)
        } else {
            return
        }
        // A click id belongs to the install it produced. Keeping it would
        // re-attach it to every session for the life of the install.
        if (click != null) p.edit().remove(KEY_PENDING_CLICK).apply()
    }

    // ── setup test ────────────────────────────────────────────────────

    /**
     * Check the integration and print what is wrong with it.
     *
     * Call it once from a debug build. Everything that usually goes wrong is
     * invisible from inside the app — credentials that were never uploaded,
     * a key belonging to another app, a device that never actually
     * registered — so this reports what the app can see and prints what the
     * server makes of it, as a checklist, in Logcat under "Notibase".
     *
     *   if (BuildConfig.DEBUG) Notibase.runSetupTest()
     *
     * Pass [onResult] to render it yourself — an in-app debug screen, or a
     * failing instrumentation test.
     */
    @JvmStatic
    @JvmOverloads
    public fun runSetupTest(onResult: ((List<SetupCheck>) -> Unit)? = null) {
        val a = api ?: return logNotInit()
        val ctx = appContext
        val p = prefs
        executor.execute {
            val report = mutableMapOf<String, Any?>(
                "platform" to "android",
                "sdk" to "notibase-android",
                "sdk_version" to SDK_VERSION,
                "device_id" to p?.getString(KEY_DEVICE_ID, null),
                "has_push_token" to (p?.getString(KEY_FCM_TOKEN, null) != null),
            )
            if (ctx != null) {
                report["bundle_id"] = ctx.packageName
                report["push_permission"] = notificationPermission(ctx)
                report["messaging_service"] = hasMessagingService(ctx)
            }
            val checks = try {
                a.setupTest(report)
            } catch (e: Exception) {
                // The one failure the server cannot report on: it was never
                // reached. Say which of the two it was.
                Log.e(TAG, "setup test could not reach ${config.apiUrl} — check the API URL " +
                    "and this device's network", e)
                onResult?.invoke(emptyList())
                return@execute
            }
            Log.i(TAG, "── Notibase setup test ──")
            for (c in checks) {
                val mark = when (c.level) { "pass" -> "✔"; "warn" -> "!"; else -> "✘" }
                Log.i(TAG, "$mark ${c.title}")
                c.detail?.let { Log.i(TAG, "    $it") }
            }
            if (checks.none { it.level == "fail" }) Log.i(TAG, "── nothing blocking ──")
            onResult?.invoke(checks)
        }
    }

    /**
     * Whether the user has allowed notifications.
     *
     * areNotificationsEnabled() arrived in API 24 and cannot distinguish
     * "denied" from "never asked" — Android 13 made the prompt explicit, and
     * before that there was nothing to ask. Reporting "unknown" rather than
     * guessing keeps the server from inventing a check out of it.
     */
    private fun notificationPermission(ctx: Context): String {
        if (Build.VERSION.SDK_INT < 24) return "granted"     // pre-24: always on
        val manager = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            ?: return "unknown"
        if (manager.areNotificationsEnabled()) return "granted"
        return if (Build.VERSION.SDK_INT >= 33) "denied" else "unknown"
    }

    /** Whether anything in this app can receive an FCM message. */
    private fun hasMessagingService(ctx: Context): Boolean = try {
        ctx.packageManager
            .queryIntentServices(Intent("com.google.firebase.MESSAGING_EVENT"), 0)
            .any { it.serviceInfo?.packageName == ctx.packageName }
    } catch (e: Exception) {
        // A query that throws tells us nothing either way, and a false here
        // would put a warning in front of someone whose setup is fine.
        true
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
    @JvmOverloads
    public fun trackOpenFromIntent(intent: Intent?, context: Context? = null) {
        val extras = intent?.extras ?: return
        if (extras.getBoolean(EXTRA_OPEN_REPORTED, false)) return
        // Marked on the intent rather than in a field: the intent is the thing
        // that can be delivered twice, and it outlives any bookkeeping we would
        // otherwise have to keep in sync with the activity's lifecycle.
        intent.putExtra(EXTRA_OPEN_REPORTED, true)
        trackNotificationOpen(
            mapOf(
                "nb_m" to extras.getString("nb_m"),
                "nb_d" to extras.getString("nb_d"),
                "nb_o" to extras.getString("nb_o"),
            )
        )
        // The other half of a system-tray tap. Android renders the
        // notification itself, so nothing of ours ran when it was drawn and
        // the destination is sitting unread in the launcher intent's extras.
        // Without this, a message composed with a URL opens the app and stops
        // there — on the most common delivery path there is.
        extras.getString("url")?.let { openUrl(context, it) }
    }

    /**
     * Send a tapped notification to its destination.
     *
     * Returns true when the destination was taken care of — by the app's own
     * [urlHandler], or by an activity that opened. False means the tap still
     * needs somewhere to go, which is what lets the click trampoline fall
     * back to the launcher instead of stranding the person on nothing.
     *
     * [context] may be null when the caller had none to hand; the stored
     * application context is used then.
     */
    internal fun openUrl(context: Context?, url: String): Boolean {
        if (url.isBlank()) return false
        val ctx = context ?: appContext
        if (ctx != null && urlHandler?.handle(ctx, url) == true) return true
        if (!config.openUrls) return false
        if (ctx == null) {
            Log.w(TAG, "no context to open $url with — call Notibase.init(context, …)")
            return false
        }
        // A push payload is data, not code. `file:` and `content:` would turn
        // a notification into a local-file read against the host app's own
        // permissions, so only network and app schemes are followed.
        if (!NotibaseLinks.isOpenable(url)) {
            Log.w(TAG, "refusing to open notification url: $url")
            return false
        }
        val view = Intent(Intent.ACTION_VIEW, Uri.parse(url.trim()))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            ctx.startActivity(view)
            true
        } catch (e: Exception) {
            // No activity can handle it — a custom scheme with no intent
            // filter, most often. Say so, and let the caller decide what a
            // tap with nowhere to go should do instead.
            Log.w(TAG, "nothing on this device can open $url", e)
            false
        }
    }

    private const val EXTRA_OPEN_REPORTED: String = "com.notibase.sdk.open_reported"

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
