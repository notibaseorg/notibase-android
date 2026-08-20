# Notibase Android SDK

Native Android SDK for [Notibase](https://notibase.com). Kotlin, minSdk 21,
**zero dependencies beyond `firebase-messaging`** — no okhttp, no coroutines,
no JSON library — so it can never version-conflict with a host app.

## Install

```kotlin
dependencies {
    implementation("com.notibase:notibase-android:0.1.0")
}
```

Your app must already be connected to Firebase (google-services.json) — the
SDK uses your FCM token; Notibase sends through **your** Firebase project
using the service-account JSON you upload in the dashboard.

## Use

```kotlin
class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Notibase.init(this, "ck_live_…")          // client key — public by design
    }
}

// after login (signature minted by YOUR backend — see docs/security)
Notibase.identify("user-42", signature = sig, attributes = mapOf("plan" to "pro"))

// custom events → segments + attribution
Notibase.track("level_complete", mapOf("level" to 3))

// in-app inbox
Notibase.inbox { items -> /* render */ }
Notibase.inboxMarkRead(listOf(id))
```

`NotibaseMessagingService` is registered by manifest merge: foreground
display, the Android 8+ notification channel, and `data.url` deep-link taps
all work with no additional code. Hosts with their own
`FirebaseMessagingService` forward `onNewToken` → `Notibase.registerToken`
and `onMessageReceived` → `NotibaseNotifications.display`.

## Layout & verification

- `notibase/src/main/kotlin/com/notibase/sdk/internal/` — **pure-Kotlin core**
  (HTTP, MiniJson, retry). No Android imports: compiled on plain JVM and
  e2e-tested against the real API on every round
  (`apps/api/scripts/e2e-android-core.mjs` + `scripts/TestMain.kt`).
- `notibase/src/main/kotlin/com/notibase/sdk/` — Android glue (storage,
  threading, FCM service, notification rendering).
- Real AAR builds run in CI (`.github/workflows/android-sdk.yml`) with AGP +
  real Firebase artifacts; the AAR is uploaded as a build artifact.

## Security model

The `ck_` client key ships inside the APK and is public by design (Arch §5.3):
it can only register devices, identify **with an HMAC signature your backend
mints**, track events, and read the inbox of its own device. The SDK refuses
`sk_` server keys at construction time with a teaching error.
