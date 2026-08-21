/**
 * MiniJson — dependency-free JSON for the Notibase SDK.
 *
 * Why not org.json / kotlinx.serialization? The SDK's promise is ZERO
 * runtime dependencies beyond firebase-messaging (Arch §8.2): no version
 * conflicts with the host app, no transitive surprises, tiny AAR. The
 * payloads we exchange are small and flat, so 200 lines of strict parser
 * beat a dependency. Runs identically on Android and plain JVM (tests).
 */
package com.notibase.sdk.internal

internal object MiniJson {

    // ── serialize ───────────────────────────────────────────
    fun write(value: Any?): String = StringBuilder().also { writeTo(it, value) }.toString()

    private fun writeTo(sb: StringBuilder, value: Any?) {
        when (value) {
            null -> sb.append("null")
            is String -> writeString(sb, value)
            is Boolean -> sb.append(value.toString())
            is Int, is Long -> sb.append(value.toString())
            is Double -> {
                require(!value.isNaN() && !value.isInfinite()) { "non-finite numbers are not JSON" }
                sb.append(value.toString())
            }
            is Float -> writeTo(sb, value.toDouble())
            is Map<*, *> -> {
                sb.append('{')
                var first = true
                for ((k, v) in value) {
                    require(k is String) { "JSON object keys must be strings" }
                    if (!first) sb.append(',')
                    first = false
                    writeString(sb, k); sb.append(':'); writeTo(sb, v)
                }
                sb.append('}')
            }
            is List<*> -> {
                sb.append('[')
                value.forEachIndexed { i, v ->
                    if (i > 0) sb.append(',')
                    writeTo(sb, v)
                }
                sb.append(']')
            }
            else -> throw IllegalArgumentException("cannot serialize ${value.javaClass.name}")
        }
    }

    private fun writeString(sb: StringBuilder, s: String) {
        sb.append('"')
        for (c in s) when {
            c == '"' -> sb.append("\\\"")
            c == '\\' -> sb.append("\\\\")
            c == '\n' -> sb.append("\\n")
            c == '\r' -> sb.append("\\r")
            c == '\t' -> sb.append("\\t")
            c < ' ' -> sb.append("\\u").append(c.code.toString(16).padStart(4, '0'))
            else -> sb.append(c)
        }
        sb.append('"')
    }

    // ── parse ───────────────────────────────────────────────
    fun parse(text: String): Any? {
        val p = Parser(text)
        val v = p.value()
        p.skipWs()
        require(p.done()) { "trailing characters at ${p.pos}" }
        return v
    }

    /** Convenience: parse and expect an object. */
    fun parseObject(text: String): Map<String, Any?> {
        @Suppress("UNCHECKED_CAST")
        return parse(text) as? Map<String, Any?>
            ?: throw IllegalArgumentException("expected JSON object")
    }

    private class Parser(private val s: String) {
        var pos = 0
        fun done() = pos >= s.length
        fun skipWs() { while (pos < s.length && s[pos].let { it == ' ' || it == '\n' || it == '\r' || it == '\t' }) pos++ }

        fun value(): Any? {
            skipWs()
            require(pos < s.length) { "unexpected end of input" }
            return when (s[pos]) {
                '{' -> obj()
                '[' -> arr()
                '"' -> str()
                't' -> lit("true", true)
                'f' -> lit("false", false)
                'n' -> lit("null", null)
                else -> num()
            }
        }

        private fun lit(word: String, v: Any?): Any? {
            require(s.startsWith(word, pos)) { "bad literal at $pos" }
            pos += word.length
            return v
        }

        private fun obj(): Map<String, Any?> {
            pos++ // {
            val out = LinkedHashMap<String, Any?>()
            skipWs()
            if (pos < s.length && s[pos] == '}') { pos++; return out }
            while (true) {
                skipWs()
                require(pos < s.length && s[pos] == '"') { "expected key at $pos" }
                val k = str()
                skipWs()
                require(pos < s.length && s[pos] == ':') { "expected : at $pos" }
                pos++
                out[k] = value()
                skipWs()
                require(pos < s.length) { "unterminated object" }
                when (s[pos]) {
                    ',' -> pos++
                    '}' -> { pos++; return out }
                    else -> throw IllegalArgumentException("expected , or } at $pos")
                }
            }
        }

        private fun arr(): List<Any?> {
            pos++ // [
            val out = ArrayList<Any?>()
            skipWs()
            if (pos < s.length && s[pos] == ']') { pos++; return out }
            while (true) {
                out.add(value())
                skipWs()
                require(pos < s.length) { "unterminated array" }
                when (s[pos]) {
                    ',' -> pos++
                    ']' -> { pos++; return out }
                    else -> throw IllegalArgumentException("expected , or ] at $pos")
                }
            }
        }

        private fun str(): String {
            pos++ // "
            val sb = StringBuilder()
            while (true) {
                require(pos < s.length) { "unterminated string" }
                when (val c = s[pos]) {
                    '"' -> { pos++; return sb.toString() }
                    '\\' -> {
                        pos++
                        require(pos < s.length) { "unterminated escape" }
                        when (val e = s[pos]) {
                            '"' -> sb.append('"'); '\\' -> sb.append('\\'); '/' -> sb.append('/')
                            'n' -> sb.append('\n'); 'r' -> sb.append('\r'); 't' -> sb.append('\t')
                            'b' -> sb.append('\b'); 'f' -> sb.append('\u000C')
                            'u' -> {
                                require(pos + 4 < s.length) { "bad \\u escape" }
                                sb.append(s.substring(pos + 1, pos + 5).toInt(16).toChar())
                                pos += 4
                            }
                            else -> throw IllegalArgumentException("bad escape \\$e at $pos")
                        }
                        pos++
                    }
                    else -> {
                        require(c >= ' ') { "unescaped control char at $pos" }
                        sb.append(c); pos++
                    }
                }
            }
        }

        private fun num(): Any {
            val start = pos
            if (pos < s.length && s[pos] == '-') pos++
            while (pos < s.length && (s[pos].isDigit() || s[pos] in ".eE+-")) pos++
            val raw = s.substring(start, pos)
            require(raw.isNotEmpty() && raw != "-") { "bad number at $start" }
            return if (raw.none { it in ".eE" }) {
                raw.toLongOrNull() ?: raw.toDouble()
            } else raw.toDouble()
        }
    }
}
