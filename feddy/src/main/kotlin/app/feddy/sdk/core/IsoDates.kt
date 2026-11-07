package app.feddy.sdk.core

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * ISO 8601 in both directions without java.time, which is API 26 and the
 * SDK supports 23. SimpleDateFormat's `X` offset pattern is API 24, so the
 * parser is written out by hand: it is the one format the API sends.
 */
internal object IsoDates {
    private val pattern = Regex(
        """^(\d{4})-(\d{2})-(\d{2})[T ](\d{2}):(\d{2}):(\d{2})(?:\.(\d+))?(Z|[+-]\d{2}:?\d{2})?$""",
    )

    /** Epoch milliseconds. Throws on anything that is not a timestamp. */
    fun parse(value: String): Long {
        val match = pattern.matchEntire(value.trim())
            ?: throw IllegalArgumentException("Unrecognized date: $value")
        val g = match.groupValues
        val calendar = Calendar.getInstance(TimeZone.getTimeZone("UTC"), Locale.US)
        calendar.clear()
        calendar.set(g[1].toInt(), g[2].toInt() - 1, g[3].toInt(), g[4].toInt(), g[5].toInt(), g[6].toInt())
        val millis = g[7].takeIf { it.isNotEmpty() }?.padEnd(3, '0')?.take(3)?.toInt() ?: 0
        var epoch = calendar.timeInMillis + millis
        val zone = g[8]
        if (zone.isNotEmpty() && zone != "Z") {
            val sign = if (zone[0] == '-') -1 else 1
            val digits = zone.substring(1).replace(":", "")
            val offsetMinutes = digits.substring(0, 2).toInt() * 60 + digits.substring(2, 4).toInt()
            epoch -= sign * offsetMinutes * 60_000L
        }
        return epoch
    }

    fun format(date: Date): String {
        // Whole seconds, matching what the iOS SDK sends for the same value.
        val formatter = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
        formatter.timeZone = TimeZone.getTimeZone("UTC")
        return formatter.format(date)
    }
}
