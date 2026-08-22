/**
 * What a notification's `url` is allowed to be, decided without touching a
 * single android import.
 *
 * A push payload is data that arrives from the network and is acted on with
 * the host app's own permissions. `file:` and `content:` URLs turn a
 * notification into a local read against exactly those permissions, so the
 * decision about which schemes may be followed is worth having in one place,
 * in plain Kotlin, where the JVM verification pass can exercise every branch
 * of it. A rule that can only be tested on a device is a rule nobody tests.
 */
package com.notibase.sdk.internal

import java.util.Locale

public object NotibaseLinks {

    /**
     * Schemes a notification may never open.
     *
     * A deny list rather than an allow list, deliberately: apps deep-link
     * with schemes they invent (`myapp://`, `com.example.app://`), and an
     * allow list would refuse the ones nobody thought to add. The danger is
     * a small, known set — local files and script execution — so name that.
     */
    private val BLOCKED: Set<String> = setOf("file", "content", "javascript", "data", "about")

    /**
     * The scheme of [url], lowercased, or null when it has none.
     *
     * Written out rather than delegated to Uri.parse so it can be tested off
     * a device — and because Uri.parse is famously forgiving, which is the
     * wrong quality in the thing that decides whether something is safe.
     * RFC 3986: ALPHA *( ALPHA / DIGIT / "+" / "-" / "." ) ":".
     */
    @JvmStatic
    public fun schemeOf(url: String): String? {
        val colon = url.indexOf(':')
        if (colon <= 0) return null
        if (!url[0].isLetter()) return null
        for (i in 1 until colon) {
            val c = url[i]
            if (!c.isLetterOrDigit() && c != '+' && c != '-' && c != '.') return null
        }
        return url.substring(0, colon).lowercase(Locale.ROOT)
    }

    /**
     * The `nb_click` a Notibase campaign link put on the URL that opened the
     * app, or null.
     *
     * Parsed rather than handed to Uri.getQueryParameter for the same reason
     * as [schemeOf]: this runs on the deep-link path, which is the one path a
     * stranger can drive, and it is worth being able to test every input it
     * will ever see. Only digits are accepted — the value is a bigint primary
     * key on the way to a parameterised query, and anything else is somebody
     * probing.
     */
    @JvmStatic
    public fun clickIdFrom(url: String?): String? {
        if (url.isNullOrEmpty()) return null
        val q = url.indexOf('?')
        if (q < 0) return null
        // Stop at the fragment: `?a=1#nb_click=9` is not a query parameter.
        val hash = url.indexOf('#', q)
        val query = if (hash < 0) url.substring(q + 1) else url.substring(q + 1, hash)
        for (pair in query.split('&')) {
            val eq = pair.indexOf('=')
            if (eq <= 0 || pair.substring(0, eq) != "nb_click") continue
            val value = pair.substring(eq + 1)
            if (value.isEmpty() || value.length > 19 || !value.all { it in '0'..'9' }) return null
            return value
        }
        return null
    }

    /** True when a tapped notification may send the device to [url]. */
    @JvmStatic
    public fun isOpenable(url: String): Boolean {
        val trimmed = url.trim()
        if (trimmed.isEmpty()) return false
        // A control character in a URL is either a mangled payload or an
        // attempt to smuggle something past a log or an intent filter.
        if (trimmed.any { it.code < 0x20 || it.code == 0x7f }) return false
        val scheme = schemeOf(trimmed) ?: return false
        return scheme !in BLOCKED
    }
}
