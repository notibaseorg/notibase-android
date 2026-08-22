/**
 * Action-button parsing, deliberately free of any android import.
 *
 * FCM data values are always strings, so structured extras travel JSON-encoded
 * — which means the one thing that can break here is parsing, and parsing is
 * the one thing a device emulator is a terrible way to test. Keeping it in
 * plain Kotlin lets it run on the JVM in the sandbox verification pass.
 */
package com.notibase.sdk.internal

/** One action button exactly as the send pipeline encodes it. */
public data class NotibaseButton(
    val id: String,
    val text: String,
    val url: String?,
)

public object NotibaseButtons {

    /** Android draws at most three actions and silently drops the rest. */
    public const val MAX_ACTIONS: Int = 3

    /**
     * Parse `nb_buttons`. A malformed array costs the buttons, never the
     * notification: a customer who typos a payload should still see the
     * message, not silence.
     */
    @JvmStatic
    public fun parse(raw: String?): List<NotibaseButton> {
        if (raw.isNullOrBlank()) return emptyList()
        return try {
            val parsed = MiniJson.parse(raw) as? List<*> ?: return emptyList()
            parsed.mapNotNull { item ->
                val o = item as? Map<*, *> ?: return@mapNotNull null
                val id = o["id"] as? String ?: return@mapNotNull null
                val text = o["text"] as? String ?: return@mapNotNull null
                if (id.isBlank() || text.isBlank()) null
                else NotibaseButton(id, text, (o["url"] as? String)?.takeIf { it.isNotBlank() })
            }.take(MAX_ACTIONS)
        } catch (e: Exception) {
            emptyList()
        }
    }
}
