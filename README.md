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
    implementation("com.github.notibaseorg:notibase-android:0.2.1")
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

Notifications rendered by the SDK record opens **automatically** (taps route
through a broadcast trampoline, then continue to the click URL or your
launcher). They power the Clicked / CTR columns in the console.

One path needs a line from you — when your app is backgrounded, Android's
system tray renders the notification and the tap opens your launcher activity
with the payload as intent extras:

```kotlin
override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    Notibase.trackOpenFromIntent(intent)
}

override fun onNewIntent(intent: Intent) {
    super.onNewIntent(intent)
    Notibase.trackOpenFromIntent(intent)
}
```

Non-Notibase notifications are ignored safely.

## Identity, events, inbox

```kotlin
// signature minted by YOUR backend — https://notibase.dev/security.html
Notibase.identify("user-42", signature = sig, attributes = mapOf("plan" to "pro"))

Notibase.track("purchase", mapOf("value" to 9.99))

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
