<p align="center">
  <img src="https://notibase.com/icon-512.png" width="72" alt="Notibase">
</p>
<h1 align="center">Notibase Android SDK</h1>
<p align="center">
  Push notifications, in-app inbox and click tracking for Android —
  <b>zero dependencies</b> beyond <code>firebase-messaging</code>.<br>
  <a href="https://notibase.dev/android.html">Documentation</a> ·
  <a href="https://notibase.com">Website</a> ·
  <a href="https://app.notibase.com">Console</a>
</p>

---

Kotlin, `minSdk 21`, built on platform APIs only — no okhttp, no coroutines,
no JSON library — so it can never version-conflict with your app.

## Install

```kotlin
// settings.gradle.kts
dependencyResolutionManagement {
    repositories { maven("https://jitpack.io") }
}

// app/build.gradle.kts
dependencies {
    implementation("com.github.notibaseorg:notibase-android:0.5.0")
}
```

## Requirements

Your app must be connected to **Firebase** (`google-services.json` + the
`com.google.gms.google-services` plugin) — Android push rides FCM. Notibase
sends through *your* Firebase project using the service-account JSON you
upload in the console.

Never set up Firebase before? The docs walk it from an empty account:
**[notibase.dev/android.html](https://notibase.dev/android.html)**

## Quick start

```kotlin
class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Notibase.init(this, clientKey = "ck_live_…")   // public by design
    }
}
```

Token registration and notification rendering happen automatically —
`NotibaseMessagingService` is merged into your manifest. Already have your
own `FirebaseMessagingService`? Forward the two callbacks instead:

```kotlin
override fun onNewToken(token: String) = Notibase.registerToken(token)

override fun onMessageReceived(msg: RemoteMessage) =
    NotibaseNotifications.display(this, msg.data,
        msg.notification?.title, msg.notification?.body)
```

> **Android 13+** requires the runtime `POST_NOTIFICATIONS` permission —
> request it from your activity. The SDK declares it in the manifest for you.

## Click tracking

Nothing to wire, on either path.

Notifications the SDK renders route their taps through a broadcast trampoline
and record themselves. Notifications drawn by Android's system tray — what
happens when your app is backgrounded and the payload carries a
`notification` block — deliver their data as extras on your launcher
activity's intent, and the SDK watches activity resumes to pick those up.

This used to ask for two overrides, `onCreate` and `onNewIntent`. An app that
added only the first lost every open from a warm start with nothing to
indicate anything was missing. `Notibase.Config(autoTrackOpens = false)` turns
the watcher off, and `Notibase.trackOpenFromIntent(intent)` is still public.

Which button was tapped arrives as an extra:

```kotlin
val tapped = intent.getStringExtra(NotibaseClickReceiver.EXTRA_ACTION_ID)
// null means the body was tapped rather than a button
```

## Where a tap goes

A message composed with a URL opens that URL, on both paths. Nothing to wire
for that either. To route a destination inside your app instead — a deep link
into a screen rather than a browser — claim the ones you want:

```kotlin
Notibase.urlHandler = NotibaseUrlHandler { _, url ->
    if (!url.startsWith("darlivo://")) return@NotibaseUrlHandler false
    Router.go(url)
    true            // handled — Notibase does nothing more
}
```

Returning `false` keeps the default for that URL, so your app can own its own
scheme and still let plain `https` links open a browser.
`Notibase.Config(openUrls = false)` turns opening off entirely. `file:`,
`content:`, `javascript:` and `data:` URLs are refused — a push payload
arrives from the network and would be acted on with your app's permissions.

## Attribution

Installs, sessions and revenue are reported for you. `install` goes out once
and `session_start` on each cold start, as soon as the device is registered —
which is what lets a campaign link be credited with the installs it drove.

```kotlin
// a deep link that opened the app — the SDK already does this for the
// launch intent, so reach for it only when the URL arrives another way
Notibase.handleDeepLink(url)

Notibase.trackPurchase(9.99, currency = "USD", productId = "pro_monthly")
```

`Notibase.Config(autoTrackSessions = false)` turns the automatic events off.
Full model: [notibase.dev/attribution.html](https://notibase.dev/attribution.html)

## Setup test

Everything that usually goes wrong during an integration is invisible from
inside the app: credentials that were never uploaded, an APNs key for another
bundle, a client key belonging to a different app. Add one line to a debug
build and the answer prints in Logcat — and in the console, under
Settings → Push platforms:

```kotlin
if (BuildConfig.DEBUG) Notibase.runSetupTest()
```

```
✔ Client key belongs to "helloworld"
✔ Android push credentials are uploaded
✘ A push token exists but no device was registered
    The token reached the SDK and the call to Notibase did not land. …
```

## Identity, events, inbox

```kotlin
// signature minted by YOUR backend — https://notibase.dev/security.html
Notibase.identify("user-42", signature = sig, attributes = mapOf("plan" to "pro"))

Notibase.track("level_complete", mapOf("level" to 3))

Notibase.inbox { items -> /* render your notification center */ }
Notibase.inboxMarkRead(listOf(itemId))
```

## Support

- Docs: [notibase.dev/android.html](https://notibase.dev/android.html)
- Issues & feature requests: [support@notibase.com](mailto:support@notibase.com)
- Security reports: [security@notibase.com](mailto:security@notibase.com)

## License

MIT © Notibase — see [LICENSE](LICENSE).

<sub>This repository is a published snapshot of the Notibase SDK, updated
automatically on each release.</sub>
